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
    @Test fun surroundingTextUsesReportedCaretAndNeverRequestsFullDocument() {
        val connection = mock<InputConnection> {
            on { getSurroundingText(64, 64, 0) } doReturn SurroundingText("甲😀乙", 1, 3, 500)
        }
        assertEquals(CursorMagnifierText("甲😀乙", 3), readCursorMagnifierText(connection))
        verify(connection, never()).getTextBeforeCursor(any(), any())
        verify(connection, never()).getExtractedText(any(), any())
        verify(connection, never()).setSelection(any(), any())
    }

    @Test fun reverseSelectionUsesTheControllersActiveEndWithinTheReturnedWindow() {
        val connection = mock<InputConnection> {
            on { getSurroundingText(64, 64, 0) } doReturn SurroundingText("甲😀乙", 1, 3, 500)
        }
        assertEquals(CursorMagnifierText("甲😀乙", 1), readCursorMagnifierText(connection, 501))
        assertEquals(CursorMagnifierText("甲😀乙", 3), readCursorMagnifierText(connection, 503))
        assertEquals(CursorMagnifierText("甲😀乙", 3), readCursorMagnifierText(connection, 100))
    }

    @Test fun partialEditorsUseSmallBeforeAndAfterWindows() {
        val connection = mock<InputConnection> {
            on { getTextBeforeCursor(64, 0) } doReturn "左😀"
            on { getTextAfterCursor(64, 0) } doReturn "右"
        }
        assertEquals(CursorMagnifierText("左😀右", 3), readCursorMagnifierText(connection))
        verify(connection, never()).getExtractedText(any(), any())
    }

    @Test fun unreadableEditorsAndConnectionErrorsHideThePreview() {
        assertNull(readCursorMagnifierText(mock()))
        val broken = mock<InputConnection> {
            on { getTextBeforeCursor(any(), any()) } doThrow IllegalStateException("closed")
        }
        assertNull(readCursorMagnifierText(broken))
    }

    @Test fun fallbackDoesNotGuessCaretWhenHostHasASelection() {
        val connection = mock<InputConnection> { on { getSelectedText(0) } doReturn "选中" }
        assertNull(readCursorMagnifierText(connection))
        verify(connection, never()).getTextBeforeCursor(any(), any())
    }
}
