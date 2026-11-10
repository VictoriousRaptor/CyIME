package com.kingzcheung.xime.service

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*

class EditorCursorTest {
    @Test fun `preview active end follows reverse selection and never leaks to another connection`() {
        val ic = editor(6, text = "hello world", normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.moveWithFeedback(ic, -2, selecting = true) {}
        assertEquals(4, cursor.selectionActiveEnd(ic))
        assertEquals(null, cursor.selectionActiveEnd(mock()))
        cursor.resetSelection()
        assertEquals(null, cursor.selectionActiveEnd(ic))
    }

    @Test fun `physical arrows clamp at all editor boundaries and never dispatch focus events`() {
        val ic = editor(0, text = "a😀b\nnext")
        val cursor = EditorCursor()
        repeat(20) { cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_LEFT) }
        verify(ic, atLeastOnce()).setSelection(0, 0)
        repeat(20) { cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_RIGHT) }
        verify(ic, atLeastOnce()).setSelection(9, 9)
        repeat(20) { cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(20) { cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_UP) }
        verify(ic, never()).sendKeyEvent(any())
        verify(ic, never()).setSelection(2, 2)
    }

    @Test fun `physical shift arrows preserve selection anchor and modifier navigation stays in editor`() {
        val ic = editor(6, text = "hello world", normalized = true)
        val cursor = EditorCursor()
        cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_RIGHT, selecting = true, byWord = true)
        verify(ic).setSelection(6, 11)
        cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_LEFT, selecting = true)
        verify(ic).setSelection(6, 10)
        cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_LEFT, toBoundary = true)
        verify(ic).setSelection(0, 0)
        verify(ic, never()).sendKeyEvent(any())
    }

    // 外接键盘用户 11-01：有的输入框读不出文字或不收 setSelection，方向键按了没反应（微信输入法能用）。
    // 自己移不动时把方向键交给宿主，和别的输入法一样。
    @Test fun `physical arrows go to the host when the text cannot be read or the selection is refused`() {
        for (ic in listOf(mock<InputConnection>(), editor(1))) {
            doReturn(false).whenever(ic).setSelection(any(), any())
            val cursor = EditorCursor()
            for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                cursor.movePhysical(ic, key)
            }
            verify(ic, times(8)).sendKeyEvent(any())
        }
    }

    @Test fun `physical arrows stay inside the editor at a known edge even when the selection is refused`() {
        val start = editor(0)
        val end = editor(4)
        val cursor = EditorCursor()
        for (ic in listOf(start, end)) doReturn(false).whenever(ic).setSelection(any(), any())
        assertEquals(false, cursor.movePhysical(start, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(false, cursor.movePhysical(start, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(false, cursor.movePhysical(end, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(false, cursor.movePhysical(end, KeyEvent.KEYCODE_DPAD_DOWN))
        verify(start, never()).sendKeyEvent(any())
        verify(end, never()).sendKeyEvent(any())
    }

    @Test fun `physical cursor uses surrounding text and reported absolute selection without extracted text`() {
        val ic = editor(3, text = "abc😀def")
        doReturn(null).whenever(ic).getExtractedText(any(), any())
        val cursor = EditorCursor()
        cursor.observeSelection(ic, 3, 3)
        cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
        cursor.movePhysical(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
        verify(ic).setSelection(5, 5)
        verify(ic).setSelection(6, 6)
        verify(ic, never()).sendKeyEvent(any())
    }

    // Android JVM stubs 不保存 KeyEvent 字段，直接核对真实构造参数和发送次数。
    private fun assertCancelKeyPair(ic: InputConnection, key: Int, cancel: () -> Unit) {
        val events = mutableListOf<List<Int>>()
        mockConstruction(KeyEvent::class.java) { _, construction ->
            val args = construction.arguments()
            events += listOf(args[2] as Int, args[3] as Int, args[5] as Int)
        }.use { cancel() }
        assertEquals(listOf(listOf(KeyEvent.ACTION_DOWN, key, 0), listOf(KeyEvent.ACTION_UP, key, 0)), events)
        verify(ic, times(2)).sendKeyEvent(any())
    }

    /** [ignoresReverse]：像部分 WebView，start > end 时返回成功但不改选区。 */
    private fun editor(start: Int, end: Int = start, offset: Int = 0, text: String = "abcd", normalized: Boolean = false,
        ignoresReverse: Boolean = false): InputConnection {
        val extracted = ExtractedText().apply {
            this.text = text
            startOffset = offset
            selectionStart = start
            selectionEnd = end
        }
        return mock<InputConnection> {
            on { getExtractedText(any(), any()) } doReturn extracted
            on { getTextBeforeCursor(any(), any()) } doAnswer {
                text.take(minOf(extracted.selectionStart, extracted.selectionEnd)).takeLast(it.getArgument(0))
            }
            on { getTextAfterCursor(any(), any()) } doAnswer {
                text.drop(maxOf(extracted.selectionStart, extracted.selectionEnd)).take(it.getArgument(0))
            }
            on { setSelection(any(), any()) } doAnswer {
                val anchor = it.getArgument<Int>(0) - offset
                val active = it.getArgument<Int>(1) - offset
                if (ignoresReverse && anchor > active) return@doAnswer true
                extracted.selectionStart = if (normalized) minOf(anchor, active) else anchor
                extracted.selectionEnd = if (normalized) maxOf(anchor, active) else active
                true
            }
        }
    }

    @Test fun `cursor feedback fires for each unicode character but stops at boundaries`() {
        val ic = editor(0, text = "a😀b")
        val cursor = EditorCursor()
        var ticks = 0
        cursor.moveWithFeedback(ic, 8) { ticks++ }
        assertEquals(3, ticks)
        cursor.moveWithFeedback(ic, 2) { ticks++ }
        assertEquals(3, ticks)
        cursor.moveWithFeedback(ic, -8) { ticks++ }
        assertEquals(6, ticks)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `space drag with shift extends from the anchor and collapses once shift is released`() {
        val ic = editor(2, text = "hello world")
        val cursor = EditorCursor()
        var ticks = 0
        cursor.moveWithFeedback(ic, 3, selecting = true) { ticks++ }
        verify(ic).setSelection(2, 3)
        verify(ic).setSelection(2, 5)
        cursor.moveWithFeedback(ic, -1, selecting = true) { ticks++ }
        verify(ic, times(2)).setSelection(2, 4)
        cursor.moveWithFeedback(ic, 1) { ticks++ }
        verify(ic).setSelection(4, 4)
        cursor.moveWithFeedback(ic, 2, selecting = true) { ticks++ }
        verify(ic).setSelection(4, 6)
        assertEquals(7, ticks)
    }

    // 用户 10-31：长按空格滑动延迟很大。每格都向宿主要一次全文，宿主主线程忙时一次约 130 ms，
    // 一格等一格越拖越远。拖动中只读一次文字，之后每格只发 setSelection，逐格照样走。
    @Test fun `space drag reads the editor text once and then moves one character per step`() {
        val ic = editor(0, text = "abcdefghij")
        val cursor = EditorCursor()
        var ticks = 0
        repeat(6) { step ->
            cursor.moveWithFeedback(ic, 1) { ticks++ }
            // 宿主报回的是自己刚设的位置：缓存继续用。
            cursor.observeSelection(ic, step + 1, step + 1)
        }
        cursor.moveWithFeedback(ic, -2) { ticks++ }
        verify(ic, times(1)).getExtractedText(any(), any())
        for (position in listOf(1, 2, 3, 6)) verify(ic).setSelection(position, position)
        verify(ic, times(2)).setSelection(5, 5)
        verify(ic, times(2)).setSelection(4, 4)
        assertEquals(8, ticks)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `space drag rereads the editor after a foreign selection, another edit or a long pause`() {
        var now = 0L
        val ic = editor(0, text = "abcdefghij")
        val cursor = EditorCursor { now }
        cursor.moveWithFeedback(ic, 2) {}
        verify(ic, times(1)).getExtractedText(any(), any())
        // 用户点了输入框别处（宿主报来不是自己设的选区）。
        cursor.observeSelection(ic, 7, 7)
        cursor.moveWithFeedback(ic, 1) {}
        verify(ic, times(2)).getExtractedText(any(), any())
        // 编辑面板等别的操作之后，拖动重新读。
        cursor.move(ic, 1)
        cursor.moveWithFeedback(ic, 1) {}
        verify(ic, times(4)).getExtractedText(any(), any())
        // 停顿超过 1.5 秒。
        now += 1_501
        cursor.moveWithFeedback(ic, 1) {}
        verify(ic, times(5)).getExtractedText(any(), any())
        cursor.moveWithFeedback(ic, 1) {}
        verify(ic, times(5)).getExtractedText(any(), any())
    }

    @Test fun `space drag with cached text still stops at the document edge without focus keys`() {
        val ic = editor(8, text = "abcdefghij")
        val cursor = EditorCursor()
        var ticks = 0
        cursor.moveWithFeedback(ic, 5) { ticks++ }
        assertEquals(2, ticks)
        verify(ic).setSelection(9, 9)
        verify(ic, atLeastOnce()).setSelection(10, 10)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `rejected editor operations do not report cursor feedback`() {
        val ic = editor(1)
        doReturn(false).whenever(ic).setSelection(any(), any())
        var ticks = 0
        EditorCursor().moveWithFeedback(ic, 3) { ticks++ }
        assertEquals(0, ticks)
    }

    @Test fun `vertical arrows use selection without focus navigation events`() {
        val up = editor(6, text = "abcd\nefgh\nijkl")
        EditorCursor().moveVertical(up, -1)
        verify(up).setSelection(1, 1)
        verify(up, never()).sendKeyEvent(any())
        val down = editor(6, text = "abcd\nefgh\nijkl")
        EditorCursor().sendDirection(down, KeyEvent.KEYCODE_DPAD_DOWN)
        verify(down).setSelection(11, 11)
        verify(down, never()).sendKeyEvent(any())
    }

    @Test fun `single line middle cannot transfer focus up or down`() {
        val up = editor(2)
        val down = editor(2)
        EditorCursor().moveVertical(up, -20)
        EditorCursor().moveVertical(down, 20)
        verify(up).setSelection(0, 0)
        verify(down).setSelection(4, 4)
        verify(up, never()).sendKeyEvent(any())
        verify(down, never()).sendKeyEvent(any())
    }

    @Test fun `unsupported vertical movement stops rather than navigating to another element`() {
        val missing = mock<InputConnection>()
        EditorCursor().moveVertical(missing, 1)
        verify(missing, never()).sendKeyEvent(any())
        val rejected = editor(1, text = "abc\ndef")
        doReturn(false).whenever(rejected).setSelection(any(), any())
        EditorCursor().moveVertical(rejected, 1)
        verify(rejected, never()).sendKeyEvent(any())
    }

    @Test fun `vertical selection preserves anchor across CRLF and emoji`() {
        val ic = editor(3, text = "a😀b\r\nc😀d")
        EditorCursor().moveVertical(ic, 1, true)
        verify(ic).setSelection(3, 9)
        verify(ic, never()).sendKeyEvent(any())
    }
    @Test fun `vertical arrows never escape the text at either boundary`() {
        val first = editor(0)
        val last = editor(4)
        EditorCursor().moveVertical(first, -20)
        EditorCursor().moveVertical(last, 20)
        verify(first, never()).sendKeyEvent(any())
        verify(last, never()).sendKeyEvent(any())
    }

    @Test fun `selection uses absolute offset and preserves emoji`() {
        val ic = editor(1, offset = 200, text = "a😀b")
        EditorCursor().move(ic, 1)
        verify(ic).setSelection(203, 203)
    }

    @Test fun `left collapses selection before moving another character`() {
        val ic = editor(1, 3)
        EditorCursor().move(ic, -1)
        verify(ic).setSelection(1, 1)
    }

    @Test fun `repeated selection uses active endpoint and keeps anchor`() {
        val ic = editor(1)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, 1, true)
        cursor.move(ic, 1, true)
        cursor.move(ic, -1, true)
        verify(ic).setSelection(1, 3)
        verify(ic, times(2)).setSelection(1, 2)
    }

    @Test fun `unsupported selection falls back to paired key events`() {
        val ic = editor(1)
        doReturn(false).whenever(ic).setSelection(any(), any())
        EditorCursor().move(ic, 2)
        verify(ic, times(4)).sendKeyEvent(any())
    }

    @Test fun `new input connection cannot reuse selection anchor`() {
        val first = editor(1)
        val second = editor(2, offset = 100)
        val cursor = EditorCursor()
        cursor.beginSelection(first)
        cursor.move(second, 1, true)
        verify(second).setSelection(102, 103)
    }

    @Test fun `unicode movement clamps at edges without splitting surrogate pair`() {
        assertEquals(1, offsetByCharacters("a😀b", 3, -1))
        assertEquals(4, offsetByCharacters("a😀b", 0, 20))
        assertEquals(0, offsetByCharacters("a😀b", 4, -20))
    }

    @Test fun `normalized reverse selection expands beyond one character`() {
        val ic = editor(3, normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        repeat(3) { cursor.move(ic, -1, true) }
        inOrder(ic) {
            verify(ic).setSelection(2, 3)
            verify(ic).setSelection(1, 3)
            verify(ic).setSelection(0, 3)
        }
    }

    @Test fun `normalized selection can shrink cross anchor and expand the other way`() {
        val ic = editor(3, normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -2, true)
        repeat(3) { cursor.move(ic, 1, true) }
        inOrder(ic) {
            verify(ic).setSelection(1, 3)
            verify(ic).setSelection(2, 3)
            verify(ic).setSelection(3, 3)
            verify(ic).setSelection(3, 4)
        }
    }

    @Test fun `reverse selection keeps absolute anchor and whole unicode codepoints`() {
        val ic = editor(4, offset = 200, text = "a😀b", normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -1, true)
        cursor.move(ic, -1, true)
        verify(ic).setSelection(203, 204)
        verify(ic).setSelection(201, 204)
    }

    @Test fun `cancel reverse selection collapses to the active smaller endpoint`() {
        val ic = editor(3, normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -2, true)
        cursor.endSelection(ic)
        verify(ic).setSelection(1, 1)
        cursor.beginSelection(ic)
        cursor.move(ic, 1, true)
        verify(ic).setSelection(1, 2)
    }

    @Test fun `cancel forward selection collapses to the active larger endpoint`() {
        val ic = editor(1, normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, 2, true)
        cursor.endSelection(ic)
        verify(ic).setSelection(3, 3)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `cancel selection without extracted text uses selected text confirmation`() {
        val ic = mock<InputConnection>()
        whenever(ic.getSelectedText(0)).thenReturn("selected")
        assertCancelKeyPair(ic, KeyEvent.KEYCODE_DPAD_RIGHT) { EditorCursor().endSelection(ic) }
        verify(ic, never()).setSelection(any(), any())
    }

    @Test fun `cancel refused forward selection sends unshifted right pair`() {
        val ic = editor(1, 3)
        doReturn(false).whenever(ic).setSelection(any(), any())
        assertCancelKeyPair(ic, KeyEvent.KEYCODE_DPAD_RIGHT) { EditorCursor().endSelection(ic) }
        verify(ic).setSelection(3, 3)
    }

    @Test fun `cancel refused normalized reverse selection sends unshifted left pair`() {
        val ic = editor(3, normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -2, true)
        doReturn(false).whenever(ic).setSelection(any(), any())
        clearInvocations(ic)
        assertCancelKeyPair(ic, KeyEvent.KEYCODE_DPAD_LEFT) { cursor.endSelection(ic) }
        verify(ic).setSelection(1, 1)
    }

    @Test fun `cancel without known selection never sends a direction`() {
        val ic = mock<InputConnection>()
        EditorCursor().endSelection(ic)
        verify(ic, never()).setSelection(any(), any())
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `cancel rejected collapsed selection does not move the cursor`() {
        val ic = editor(2)
        doReturn(false).whenever(ic).setSelection(any(), any())
        whenever(ic.getSelectedText(0)).thenReturn("")
        EditorCursor().endSelection(ic)
        verify(ic).setSelection(2, 2)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `session reset clears direction without changing the editor selection`() {
        val ic = editor(3, normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -1, true)
        clearInvocations(ic)
        cursor.resetSelection()
        verify(ic, never()).setSelection(any(), any())
        cursor.move(ic, 1, true)
        verify(ic).setSelection(2, 4)
    }

    @Test fun `paragraph boundaries use newline rather than document endpoints`() {
        val ic = editor(6, text = "one\nabcd\nlast")
        val cursor = EditorCursor()
        cursor.moveToParagraphBoundary(ic, false)
        cursor.moveToParagraphBoundary(ic, true)
        verify(ic).setSelection(4, 4)
        verify(ic).setSelection(8, 8)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `selecting paragraph start and end keeps the same anchor`() {
        val ic = editor(6, text = "one\nabcd\nlast", normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.moveToParagraphBoundary(ic, false, true)
        cursor.moveToParagraphBoundary(ic, true, true)
        verify(ic).setSelection(4, 6)
        verify(ic).setSelection(6, 8)
    }

    @Test fun `final paragraph end is verified using editor text after cursor`() {
        val ic = editor(10, text = "one\nabcd\nlast")
        EditorCursor().moveToParagraphBoundary(ic, true)
        verify(ic).setSelection(13, 13)
    }

    @Test fun `clipped extracted text is extended to the real paragraph end`() {
        val full = "prefix\n" + "x".repeat(1000) + "\nlast"
        val ic = editor(100, offset = 407, text = full.substring(407, 807))
        whenever(ic.getTextAfterCursor(any(), any())).thenAnswer { full.substring(507).take(it.getArgument(0)) }
        EditorCursor().moveToParagraphBoundary(ic, true)
        verify(ic).getTextAfterCursor(256, 0)
        verify(ic).getTextAfterCursor(512, 0)
        verify(ic).setSelection(1007, 1007)
    }

    @Test fun `clipped extracted text is extended to the real paragraph start`() {
        val full = "prefix\n" + "x".repeat(1000) + "\nlast"
        val ic = editor(100, offset = 407, text = full.substring(407, 807))
        whenever(ic.getTextBeforeCursor(any(), any())).thenAnswer { full.substring(0, 507).takeLast(it.getArgument(0)) }
        EditorCursor().moveToParagraphBoundary(ic, false)
        verify(ic).setSelection(7, 7)
    }

    @Test fun `paragraph delimiters preserve CRLF unicode separators and empty paragraphs`() {
        val text = "a😀\r\nb\u2028\u2029c"
        assertEquals(0, paragraphBoundary(text, 2, false))
        assertEquals(3, paragraphBoundary(text, 2, true))
        assertEquals(3, paragraphBoundary(text, 4, true))
        assertEquals(5, paragraphBoundary(text, 5, false))
        assertEquals(6, paragraphBoundary(text, 5, true))
        assertEquals(7, paragraphBoundary(text, 7, false))
        assertEquals(7, paragraphBoundary(text, 7, true))
        assertEquals(8, paragraphBoundary(text, 9, false))
    }
    @Test fun `overshooting document edges clamps without emitting focus navigation`() {
        val ic = editor(2, text = "a😀b")
        val cursor = EditorCursor()
        cursor.move(ic, 20)
        repeat(3) { cursor.move(ic, 1) }
        cursor.move(ic, -20)
        repeat(3) { cursor.move(ic, -1) }
        verify(ic, times(4)).setSelection(4, 4)
        verify(ic, times(4)).setSelection(0, 0)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `empty document never emits horizontal focus keys`() {
        val ic = editor(0, text = "")
        val cursor = EditorCursor()
        cursor.move(ic, -1)
        cursor.move(ic, 1)
        verify(ic, times(2)).setSelection(0, 0)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `boundary guard still works if editor refuses setSelection`() {
        val ic = editor(4)
        doReturn(false).whenever(ic).setSelection(any(), any())
        EditorCursor().move(ic, 5)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `boundary guard works without extracted text`() {
        val ic = mock<InputConnection>()
        whenever(ic.getTextBeforeCursor(1, 0)).thenReturn("")
        whenever(ic.getTextAfterCursor(1, 0)).thenReturn("")
        EditorCursor().move(ic, -1)
        EditorCursor().move(ic, 1)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `clipped horizontal snapshot does not stop at fragment edge`() {
        val ic = editor(2, offset = 2, text = "cd")
        whenever(ic.getTextAfterCursor(any(), any())).thenReturn("😀xy")
        EditorCursor().move(ic, 2)
        verify(ic).setSelection(7, 7)
        verify(ic, never()).sendKeyEvent(any())
        val reverse = editor(0, offset = 4, text = "xy")
        whenever(reverse.getTextBeforeCursor(any(), any())).thenReturn("ab😀")
        EditorCursor().move(reverse, -2)
        verify(reverse).setSelection(1, 1)
        verify(reverse, never()).sendKeyEvent(any())
    }

    @Test fun `reverse selection overshoot stays anchored and clamps both ends`() {
        val ic = editor(2, text = "abcd", normalized = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -20, true)
        cursor.move(ic, 20, true)
        verify(ic).setSelection(0, 2)
        verify(ic).setSelection(2, 4)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `selection expands left up and to paragraph start when host ignores reversed ranges`() {
        val ic = editor(6, text = "one\nabcd\nlast", ignoresReverse = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -1, true)
        cursor.move(ic, -1, true)
        cursor.moveVertical(ic, -1, true)
        cursor.move(ic, 1, true)
        cursor.moveToParagraphBoundary(ic, false, true)
        inOrder(ic) {
            verify(ic).setSelection(5, 6)
            verify(ic).setSelection(4, 6)
            verify(ic).setSelection(0, 6)
            verify(ic).setSelection(1, 6)
            verify(ic).setSelection(0, 6)
        }
        cursor.endSelection(ic)
        verify(ic).setSelection(0, 0)
    }

    private fun keyEvents(action: () -> Unit): List<List<Int>> {
        val events = mutableListOf<List<Int>>()
        mockConstruction(KeyEvent::class.java) { _, construction ->
            val args = construction.arguments()
            events += listOf(args[2] as Int, args[3] as Int, args[5] as Int)
        }.use { action() }
        return events
    }

    @Test fun `multi line arrows let the host move by visual line but never leave the text`() {
        val ic = editor(6, text = "one\nabcd\nlast")
        val cursor = EditorCursor()
        assertEquals(listOf(listOf(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0), listOf(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0)),
            keyEvents { cursor.moveLine(ic, -1, multiLine = true) })
        val first = editor(0, text = "one\nabcd")
        val last = editor(8, text = "one\nabcd")
        assertEquals(emptyList<List<Int>>(), keyEvents {
            assertEquals(false, cursor.moveLine(first, -1, multiLine = true))
            assertEquals(false, cursor.moveLine(last, 1, multiLine = true))
            assertEquals(false, cursor.moveLine(first, -1, selecting = true, multiLine = true))
        })
        val missing = mock<InputConnection>()
        whenever(missing.getTextBeforeCursor(1, 0)).thenReturn("")
        assertEquals(false, cursor.moveLine(missing, -1, multiLine = true))
        verify(missing, never()).sendKeyEvent(any())
    }

    @Test fun `single line arrows keep the in-text movement`() {
        val ic = editor(2)
        EditorCursor().moveLine(ic, -1)
        verify(ic).setSelection(0, 0)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `plain arrow with a selection collapses instead of sending a focus key`() {
        val ic = editor(0, 4)
        EditorCursor().moveLine(ic, 1, multiLine = true)
        verify(ic).setSelection(4, 4)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `selecting by line with the active end on the left moves it without shift and keeps the anchor`() {
        // 用户 10-30：选择中 ← ← 再按行首，选区少了末尾两个字。宿主不收反向选区，Shift 动的是锚点那一端。
        val ic = editor(6, text = "one\nabcd\nlast", ignoresReverse = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -1, true)
        clearInvocations(ic)
        assertEquals(listOf(listOf(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0), listOf(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0)),
            keyEvents { cursor.moveLine(ic, -1, selecting = true, multiLine = true) })
        verify(ic).setSelection(5, 5)
        // 宿主先回报折叠（还在 5，不算），再回报移到上一行的位置，此时接回锚点 6。
        cursor.observeSelection(ic, 5, 5)
        verify(ic, never()).setSelection(1, 6)
        cursor.observeSelection(ic, 1, 1)
        verify(ic).setSelection(1, 6)
    }

    @Test fun `line start after selecting leftwards keeps the original anchor`() {
        val ic = editor(7, text = "今天天气真好世", ignoresReverse = true)
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, -1, true)
        cursor.move(ic, -1, true)
        verify(ic).setSelection(5, 7)
        clearInvocations(ic)
        assertEquals(listOf(listOf(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME, 0), listOf(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME, 0)),
            keyEvents { cursor.moveToLineBoundary(ic, false, selecting = true) })
        verify(ic).setSelection(5, 5)
        cursor.observeSelection(ic, 5, 5)
        cursor.observeSelection(ic, 0, 0)
        verify(ic).setSelection(0, 7)
        // 已在行首：宿主不会动，不折叠选区。
        clearInvocations(ic)
        assertEquals(emptyList<List<Int>>(), keyEvents { assertEquals(false, cursor.moveToLineBoundary(ic, false, selecting = true)) })
        verify(ic, never()).setSelection(any(), any())
    }

    @Test fun `line end while selecting rightwards still holds shift`() {
        val ic = editor(2, text = "abcdef")
        val cursor = EditorCursor()
        cursor.beginSelection(ic)
        cursor.move(ic, 1, true)
        clearInvocations(ic)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, shift),
            keyEvents { cursor.moveToLineBoundary(ic, true, selecting = true) }[1])
        verify(ic, never()).setSelection(any(), any())
    }

    @Test fun `line start and end use the host visual line keys`() {
        val ic = editor(6, text = "one\nabcd\nlast")
        val cursor = EditorCursor()
        assertEquals(listOf(listOf(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME, 0), listOf(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME, 0)),
            keyEvents { cursor.moveToLineBoundary(ic, false) })
        cursor.beginSelection(ic)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, shift),
            keyEvents { cursor.moveToLineBoundary(ic, true, selecting = true) }[1])
        verify(ic, never()).setSelection(any(), any())
    }

    // 用户 11-08：剪贴板编辑栏（键盘自己的悬浮输入框）按上下键没反应。那里发方向键会被系统丢掉，
    // 必须按它自己的排版直接设选区。假排版：每 4 个字一行（"abcdefghij" → abcd / efgh / ij）。
    private val fourPerLine = object : EditorCursor.VisualLines {
        override fun verticalTarget(offset: Int, direction: Int): Int {
            val line = offset / 4 + direction
            return when {
                line < 0 -> 0
                line > 2 -> 10
                else -> minOf(line * 4 + offset % 4, 10)
            }
        }
        override fun lineBoundary(offset: Int, toEnd: Boolean): Int {
            val line = minOf(offset / 4, 2)
            return if (!toEnd) line * 4 else if (line == 2) 10 else line * 4 + 3
        }
    }

    @Test fun `internal editor up and down follow its own layout and never send keys`() {
        val ic = editor(6, text = "abcdefghij")
        val cursor = EditorCursor()
        assertEquals(true, cursor.moveLine(ic, -1, multiLine = true, lines = fourPerLine))
        verify(ic).setSelection(2, 2)
        assertEquals(true, cursor.moveLine(ic, -1, multiLine = true, lines = fourPerLine))
        verify(ic).setSelection(0, 0)
        assertEquals(false, cursor.moveLine(ic, -1, multiLine = true, lines = fourPerLine))
        cursor.moveLine(ic, 1, multiLine = true, lines = fourPerLine)
        verify(ic).setSelection(4, 4)
        cursor.moveLine(ic, 1, multiLine = true, lines = fourPerLine)
        verify(ic).setSelection(8, 8)
        cursor.moveLine(ic, 1, multiLine = true, lines = fourPerLine)
        verify(ic).setSelection(10, 10)
        assertEquals(false, cursor.moveLine(ic, 1, multiLine = true, lines = fourPerLine))
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `internal editor shift up and down keep the anchor`() {
        val ic = editor(6, text = "abcdefghij")
        val cursor = EditorCursor()
        cursor.moveLine(ic, 1, selecting = true, multiLine = true, lines = fourPerLine)
        verify(ic).setSelection(6, 10)
        cursor.moveLine(ic, -1, selecting = true, multiLine = true, lines = fourPerLine)
        cursor.moveLine(ic, -1, selecting = true, multiLine = true, lines = fourPerLine)
        verify(ic).setSelection(2, 6)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `internal editor line start and end use visual lines without keys`() {
        val ic = editor(6, text = "abcdefghij")
        val cursor = EditorCursor()
        assertEquals(true, cursor.moveToLineBoundary(ic, toEnd = true, lines = fourPerLine))
        verify(ic).setSelection(7, 7)
        cursor.moveToLineBoundary(ic, toEnd = false, selecting = true, lines = fourPerLine)
        verify(ic).setSelection(4, 7)
        verify(ic, never()).sendKeyEvent(any())
    }

    @Test fun `internal editor hardware arrows never fall back to sending keys`() {
        val ic = editor(0, text = "abcdefghij")
        doReturn(false).whenever(ic).setSelection(any(), any())
        val cursor = EditorCursor()
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)) {
            cursor.movePhysical(ic, key, lines = fourPerLine)
        }
        verify(ic, never()).sendKeyEvent(any())
    }
}
