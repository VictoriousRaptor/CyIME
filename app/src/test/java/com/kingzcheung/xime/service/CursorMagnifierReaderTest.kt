package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CursorMagnifierText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CursorMagnifierReaderTest {
    @Test fun unknownLayoutOrSelectionNeverQueriesTheEditor() {
        val connection = mock<InputConnection>()
        assertNull(readCursorMagnifierText(connection, selection = 4..4))
        assertNull(readCursorMagnifierText(connection, line = 0 until 20))
        assertNull(readCursorMagnifierText(connection, 5, 0 until 20, 4..4))
        verifyNoInteractions(connection)
    }

    @Test fun longSelectionsAreRejectedBeforeAnyTextIsRequested() {
        val connection = mock<InputConnection>()
        assertNull(readCursorMagnifierText(connection, 1_000_000, 999_990 until 1_000_010, 0..1_000_000))
        verifyNoInteractions(connection)
    }

    @Test fun softWrapBoundaryClipsEitherSideWithoutANewline() {
        val connection = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText("一二三四五六七八九十甲乙", 6, 6, 100)
        }
        assertEquals(CursorMagnifierText("七八九十", 0), readCursorMagnifierText(connection, 106, 106 until 112, 106..106))
        assertEquals(CursorMagnifierText("三四五六", 4), readCursorMagnifierText(connection, 106, 100 until 106, 106..106))
        verify(connection, never()).getTextBeforeCursor(any(), any())
        verify(connection, never()).getSelectedText(any())
        verify(connection, never()).getExtractedText(any(), any())
        verify(connection, never()).setSelection(any(), any())
    }

    @Test fun fourCodePointsPerSideKeepEmojiIntact() {
        val text = "甲乙丙丁😀己庚辛壬癸"
        val connection = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText(text, 6, 6, 0)
        }
        assertEquals(CursorMagnifierText("乙丙丁😀己庚辛壬", 5), readCursorMagnifierText(connection, 6, 0 until text.length, 6..6))
    }

    @Test fun reverseSelectionFollowsTheKnownActiveEnd() {
        val connection = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText("甲😀乙", 1, 3, 500)
        }
        assertEquals(CursorMagnifierText("甲😀乙", 1), readCursorMagnifierText(connection, 501, 500 until 504, 501..503))
        assertEquals(CursorMagnifierText("甲😀乙", 3), readCursorMagnifierText(connection, 503, 500 until 504, 501..503))
    }

    @Test fun staleSelectionAndUnknownOffsetsHideThePreview() {
        val stale = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText("甲😀乙", 1, 1, 500)
        }
        assertNull(readCursorMagnifierText(stale, 503, 500 until 504, 503..503))
        val unknown = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText("甲😀乙", 1, 1, -1)
        }
        assertNull(readCursorMagnifierText(unknown, 501, 500 until 504, 501..501))
    }

    @Test fun partialEditorsUseBoundedLegacyReadsOnlyForACollapsedSelection() {
        val connection = mock<InputConnection> {
            on { getTextBeforeCursor(8, 0) } doReturn "左😀"
            on { getTextAfterCursor(8, 0) } doReturn "右"
        }
        assertEquals(CursorMagnifierText("左😀右", 3), readCursorMagnifierText(connection, 103, 100 until 104, 103..103))
        verify(connection, never()).getSelectedText(any())
        verify(connection, never()).getExtractedText(any(), any())
        val selected = mock<InputConnection>()
        assertNull(readCursorMagnifierText(selected, 3, 0 until 20, 1..3))
        verify(selected, never()).getSelectedText(any())
        verify(selected, never()).getTextBeforeCursor(any(), any())
    }

    @Test @Config(sdk = [30]) fun olderAndroidReadsOnlySmallBeforeAndAfterWindows() {
        val connection = mock<InputConnection> {
            on { getTextBeforeCursor(8, 0) } doReturn "甲"
            on { getTextAfterCursor(8, 0) } doReturn "乙"
        }
        assertEquals(CursorMagnifierText("甲乙", 1), readCursorMagnifierText(connection, 1, 0 until 2, 1..1))
        verify(connection).getTextBeforeCursor(8, 0)
        verify(connection).getTextAfterCursor(8, 0)
        verifyNoMoreInteractions(connection)
    }

    @Test fun emptyAndUnreadableEditorsHideThePreview() {
        val modern = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText("", 0, 0, 0)
        }
        assertNull(readCursorMagnifierText(modern, 0, 0 until 1, 0..0))
        val fallback = mock<InputConnection> {
            on { getTextBeforeCursor(8, 0) } doReturn ""
            on { getTextAfterCursor(8, 0) } doReturn ""
        }
        assertNull(readCursorMagnifierText(fallback, 0, 0 until 1, 0..0))
        val broken = mock<InputConnection> {
            on { getTextBeforeCursor(any(), any()) } doThrow IllegalStateException("closed")
        }
        assertNull(readCursorMagnifierText(broken, 1, 0 until 2, 1..1))
    }

    @Test fun editorsIgnoringRequestedLimitsDoNotProduceAnUnboundedPreview() {
        val connection = mock<InputConnection> {
            on { getSurroundingText(8, 8, 0) } doReturn SurroundingText("甲".repeat(100), 50, 50, 0)
        }
        assertNull(readCursorMagnifierText(connection, 50, 0 until 100, 50..50))
        verify(connection, never()).getTextBeforeCursor(any(), any())
    }
}
