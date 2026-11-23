package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 「输入成对符号」：用户 10-30 要的设置项（默认关）；这里只验证开着时的上屏与退格。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairedSymbolsTest {
    private val field = EditText(ApplicationProvider.getApplicationContext())
    private val connection: InputConnection = field.onCreateInputConnection(EditorInfo())
    /** 延后的光标检查：测试里攒着，[settle] 时执行，模拟编辑框处理完以后。 */
    private val pendingChecks = mutableListOf<() -> Unit>()

    private val defaultDefer = PairedSymbols.deferCaretCheck

    @org.junit.Before fun collectDeferredChecks() {
        PairedSymbols.deferCaretCheck = { pendingChecks += it }
    }

    @org.junit.After fun restoreDeferredChecks() {
        PairedSymbols.deferCaretCheck = defaultDefer
    }

    /** 不认 newCursorPosition 的编辑框，测试里用它模拟 NotallyX（#489）、浏览器地址栏（#436）。 */
    private fun caretIgnoring(ignoreSelection: Boolean = false) =
        object : android.view.inputmethod.InputConnectionWrapper(connection, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = super.commitText(text, 1)
            override fun setSelection(start: Int, end: Int) = if (ignoreSelection) true else super.setSelection(start, end)
            // 测试里的 EditText 没挂到窗口上，按键送不到它：照宿主的样子处理左方向键。
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && event.action == android.view.KeyEvent.ACTION_DOWN)
                    field.setSelection((field.selectionStart - 1).coerceAtLeast(0))
                return true
            }
        }

    private fun InputConnection.typeAndSettle(literal: String) {
        val (text, caret) = PairedSymbols.commitFor(literal)
        assertTrue(commitTextWithCaret(text, caret))
        settle()
        settle()
    }

    /** #489 / #436：不认光标位置的编辑框也补结尾，等它处理完把光标挪回中间；接着打字留在里面，打结尾跳出去。 */
    @Test fun editorsThatIgnoreTheCaretStillGetBothHalvesWithTheCaretBetween() {
        val ignoring = caretIgnoring()
        ignoring.typeAndSettle("《")
        assertEquals("《|》", shown())
        ignoring.typeAndSettle("好")
        ignoring.typeAndSettle("的")
        assertEquals("《好的|》", shown())
        ignoring.typeAndSettle("》")
        assertEquals("《好的》|", shown())
    }

    /** 连 setSelection 都不认的编辑框（部分网页）：再等一下按左方向键。 */
    @Test fun editorsThatAlsoIgnoreSetSelectionGetLeftArrowKeys() {
        val ignoring = caretIgnoring(ignoreSelection = true)
        val (text, caret) = PairedSymbols.commitFor("（")
        assertTrue(ignoring.commitTextWithCaret(text, caret))
        settle()
        assertEquals("（）|", shown())
        settle()
        assertEquals("（|）", shown())
    }

    /** 正常编辑框不挪：光标本来就在中间，延后检查什么都不做。 */
    @Test fun normalEditorsAreLeftAlone() {
        type("（")
        settle(); settle()
        assertEquals("（|）", shown())
    }

    private fun settle() {
        val checks = pendingChecks.toList()
        pendingChecks.clear()
        checks.forEach { it() }
    }

    private fun type(literal: String) {
        val (text, caret) = PairedSymbols.commitFor(literal)
        assertTrue(connection.commitTextWithCaret(text, caret))
    }

    /** 用 | 标出光标。 */
    private fun shown(): String = field.text.toString().let {
        it.substring(0, field.selectionStart) + "|" + it.substring(field.selectionEnd)
    }

    @Test fun openingHalvesCommitBothHalvesWithTheCaretBetween() {
        listOf("“" to "”", "（" to "）", "《" to "》", "【" to "】", "(" to ")").forEach { (open, close) ->
            field.setText("")
            type(open)
            assertEquals("$open|$close", shown())
        }
    }

    @Test fun typingTheClosingHalfStepsOverTheOneAlreadyThere() {
        type("“"); type("好"); type("”")
        assertEquals("“好”|", shown())
        // 光标后面不是这个结尾时照常输入。
        type("”")
        assertEquals("“好””|", shown())
    }

    @Test fun nestedPairsCloseInOrder() {
        type("《"); type("（"); type("一"); type("）"); type("》")
        assertEquals("《（一）》|", shown())
    }

    @Test fun otherSymbolsAndSameShapedQuotesAreNotPaired() {
        listOf("，", "\"", "'", "<", "……").forEach { literal ->
            assertEquals(literal to CommitCaret.NONE, PairedSymbols.commitFor(literal))
        }
    }

    @Test fun backspaceBetweenAnEmptyPairDeletesBothHalves() {
        type("“")
        assertTrue(connection.deleteEmptyPairAroundCursor())
        assertEquals("|", shown())
    }

    @Test fun backspaceElsewhereIsLeftToTheNormalDeleteKey() {
        type("“"); type("好")
        assertFalse(connection.deleteEmptyPairAroundCursor())
        assertEquals("“好|”", shown())
        field.setText("（）")
        field.setSelection(2)
        assertFalse(connection.deleteEmptyPairAroundCursor())
    }

    /** 正常编辑框：括号里连着打几个字都留在里面，打结尾才跳出去，不重复加（用户 11-14）。 */
    @Test fun typingSeveralCharactersStaysInsideUntilTheClosingHalf() {
        type("（"); type("n"); type("i"); type("好")
        assertEquals("（ni好|）", shown())
        type("）")
        assertEquals("（ni好）|", shown())
    }

    /**
     * 编辑框认光标位置，只是刚上屏时读回的是旧状态（光标看着在结尾后面）：等它处理完再查，结尾留着（用户 11-16）。
     */
    @Test fun aLaggingReadRightAfterCommitDoesNotDeleteTheClosingHalf() {
        var lagging = true
        val lagged = object : android.view.inputmethod.InputConnectionWrapper(connection, true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
                if (lagging) field.text.toString().takeLast(n) else super.getTextBeforeCursor(n, flags)
        }
        val (text, caret) = PairedSymbols.commitFor("（")
        assertTrue(lagged.commitTextWithCaret(text, caret))
        lagging = false
        settle()
        assertEquals("（|）", shown())
    }

    /** 连着点两次开头：只按最后一次查，前一次的检查不再挪光标。 */
    @Test fun onlyTheLatestPairIsChecked() {
        type("（"); type("《")
        settle()
        assertEquals("（《|》）", shown())
    }

    /** 点「(」后马上点「)」（延后检查还没跑）：跳过结尾后光标留在 () 后面，不被前一次的检查挪回中间（11-22 审查）。 */
    @Test fun closingRightAfterOpeningStaysOutside() {
        type("("); type(")")
        assertEquals("()|", shown())
        settle(); settle()
        assertEquals("()|", shown())
    }

    @Test fun aSelectionIsReplacedByThePairLikeAnyTypedSymbol() {
        field.setText("你好")
        field.setSelection(0, 2)
        type("“")
        assertEquals("“|”", shown())
    }
}
