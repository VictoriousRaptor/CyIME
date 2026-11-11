package com.kingzcheung.xime.service

import android.graphics.Matrix
import android.graphics.RectF
import android.text.SegmentFinder
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.function.Consumer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CursorMagnifierLineTest {
    private val firstLine = HardwareCursorAnchor(120f, 200f, 120f, 220f)
    private val secondLine = HardwareCursorAnchor(100f, 220f, 100f, 240f)

    private fun boundsInfo(): TextBoundsInfo {
        val lines = SegmentFinder.PrescribedSegmentFinder(intArrayOf(10, 14, 14, 18))
        val characters = SegmentFinder.PrescribedSegmentFinder(IntArray(16) { 10 + (it + 1) / 2 })
        val bounds = FloatArray(32)
        repeat(8) { index ->
            val x = (index % 4) * 10f
            val y = (index / 4) * 20f
            bounds[index * 4] = x
            bounds[index * 4 + 1] = y
            bounds[index * 4 + 2] = x + 10f
            bounds[index * 4 + 3] = y + 20f
        }
        return TextBoundsInfo.Builder(10, 18)
            .setMatrix(Matrix().apply { setTranslate(100f, 200f) })
            .setCharacterBounds(bounds).setCharacterFlags(IntArray(8)).setCharacterBidiLevel(IntArray(8))
            .setLineSegmentFinder(lines).setGraphemeSegmentFinder(characters).setWordSegmentFinder(lines).build()
    }

    private fun connection(result: TextBoundsInfoResult): InputConnection = mock {
        on { requestTextBoundsInfo(any(), any(), any()) } doAnswer { call ->
            call.getArgument<Executor>(1).execute { call.getArgument<Consumer<TextBoundsInfoResult>>(2).accept(result) }
        }
    }

    private fun characterInfo(): CursorAnchorInfo = CursorAnchorInfo.Builder()
        .setMatrix(Matrix().apply { setTranslate(100f, 200f) }).setSelectionRange(14, 14)
        .apply {
            for (index in 10 until 18) {
                val x = ((index - 10) % 4) * 10f
                val y = ((index - 10) / 4) * 20f
                addCharacterBounds(index, x, y, x + 10f, y + 20f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
            }
        }.build()

    @Test fun requestsOnlyASmallCaretAreaAndReturnsActualSoftWrappedOffsets() = runTest {
        val connection = connection(TextBoundsInfoResult(TextBoundsInfoResult.CODE_SUCCESS, boundsInfo()))
        assertEquals(10 until 14, readCursorMagnifierLine(connection, 12, firstLine))
        verify(connection).requestTextBoundsInfo(argThat {
            width() == 2f && height() == 2f && centerX() == 120f && centerY() == 210f
        }, any(), any())
    }

    @Test fun caretYDisambiguatesBothSidesOfASoftWrapOffset() = runTest {
        val connection = connection(TextBoundsInfoResult(TextBoundsInfoResult.CODE_SUCCESS, boundsInfo()))
        assertEquals(10 until 14, readCursorMagnifierLine(connection, 14, firstLine))
        assertEquals(14 until 18, readCursorMagnifierLine(connection, 14, secondLine))
        assertEquals(14 until 18, readCursorMagnifierLine(connection, 18, secondLine))
    }

    @Test fun staleCaretGeometryNeverRequestsEvenAvailableTextBounds() = runTest {
        val connection = connection(TextBoundsInfoResult(TextBoundsInfoResult.CODE_SUCCESS, boundsInfo()))
        val stale = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(13, 13).build()
        assertNull(readCursorMagnifierLine(connection, 14, firstLine, stale))
        verify(connection, never()).requestTextBoundsInfo(any(), any(), any())
    }

    @Test fun unsupportedEditorsUseOnlyCharactersVerifiedOnTheCaretLine() = runTest {
        val connection = connection(TextBoundsInfoResult(TextBoundsInfoResult.CODE_UNSUPPORTED))
        val info = characterInfo()
        assertEquals(10 until 14, readCursorMagnifierLine(connection, 14, firstLine, info))
        assertEquals(14 until 18, readCursorMagnifierLine(connection, 14, secondLine, info))
        assertNull(readCursorMagnifierLine(connection, 14, secondLine))
    }

    @Test fun missingOrStaleCharacterGeometryNeverEstimatesALine() = runTest {
        val connection = connection(TextBoundsInfoResult(TextBoundsInfoResult.CODE_UNSUPPORTED))
        val missing = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(14, 14).build()
        assertNull(readCursorMagnifierLine(connection, 14, secondLine, missing))
        assertNull(readCursorMagnifierLine(connection, 15, secondLine, characterInfo()))
        assertNull(readCursorMagnifierLine(connection, 14, secondLine.copy(top = 400f, bottom = 420f), characterInfo()))
    }

    @Test fun aHostWithoutACallbackTimesOutAndALateReplyDoesNotResumeIt() = runTest {
        var reply: Consumer<TextBoundsInfoResult>? = null
        val connection = mock<InputConnection> {
            on { requestTextBoundsInfo(any(), any(), any()) } doAnswer { call ->
                reply = call.getArgument(2)
            }
        }
        val read = async { readCursorMagnifierLine(connection, 12, firstLine) }
        runCurrent()
        assertFalse(read.isCompleted)
        advanceTimeBy(160); runCurrent()
        assertNull(read.await())
        reply!!.accept(TextBoundsInfoResult(TextBoundsInfoResult.CODE_SUCCESS, boundsInfo()))
        assertNull(read.await())
    }

    @Test fun cancellingAGestureDiscardsAnOutstandingLayoutReply() = runTest {
        var reply: Consumer<TextBoundsInfoResult>? = null
        val connection = mock<InputConnection> {
            on { requestTextBoundsInfo(any(), any(), any()) } doAnswer { call -> reply = call.getArgument(2) }
        }
        val read = async { readCursorMagnifierLine(connection, 12, firstLine) }
        runCurrent()
        read.cancel(); runCurrent()
        reply!!.accept(TextBoundsInfoResult(TextBoundsInfoResult.CODE_SUCCESS, boundsInfo()))
        assertTrue(read.isCancelled)
    }

    @Test fun invalidAnchorsNeverContactTheEditor() = runTest {
        val connection = mock<InputConnection>()
        assertNull(readCursorMagnifierLine(connection, 12, firstLine.copy(top = Float.NaN)))
        assertNull(readCursorMagnifierLine(connection, -1, firstLine))
        verifyNoInteractions(connection)
    }
}
