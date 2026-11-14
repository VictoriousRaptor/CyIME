package com.kingzcheung.xime.service

import android.os.Handler
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/** Exercise the actual service commit boundary, not a second prediction policy in a test. */
class ClipboardPastePredictionTest {
    private class Fixture(result: TextCommitResult) {
        val service = mock<XimeInputMethodService>()
        val prediction = mock<PredictionManager>()
        val handler = mock<Handler>()
        val candidates = mutableStateOf(CandidateState(
            associationCandidates = listOf("旧联想"), pendingEnglishText = "old",
        ))
        init {
            // Real methods in this class access backing fields directly, not mocked getters.
            mapOf(
                "predictionManager" to prediction, "candidateState" to candidates,
                "mainHandler" to handler, "uiState" to mutableStateOf(InputUIState()),
                "inputReadiness" to InputReadiness().apply { completeStartup() },
                // Constructor-initialized on a real service; a Mockito mock skips initializers.
                "editorInputPolicy" to EditorSessionInputPolicy(),
            ).forEach { (name, value) ->
                XimeInputMethodService::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                    set(service, value)
                }
            }
            whenever(service.isChineseMode).thenReturn(true)
            whenever(service.commitTextSilently(any(), any(), any(), any())).thenReturn(result)
            doCallRealMethod().whenever(service).dismissPredictionsForPaste()
            doCallRealMethod().whenever(service).commitTextAndPredict(any(), any(), any(), any(), any())
            doCallRealMethod().whenever(service).commitPastedText(any())
        }
    }

    @Test fun clipboardTextCancelsOldWorkAndCannotScheduleANewPrediction() {
        val f = Fixture(TextCommitResult.ACCEPTED_HOST)
        f.service.commitPastedText("刚复制的中文内容")
        verify(f.prediction).invalidatePendingPredictions()
        verify(f.service).commitTextSilently("刚复制的中文内容", true, false, CommitCaret.NONE)
        verify(f.handler, never()).post(any())
        assertTrue(f.candidates.value.associationCandidates.isEmpty())
        assertEquals("", f.candidates.value.pendingEnglishText)
    }

    @Test fun internalAndRejectedPastesAlsoInvalidateWithoutEnablingPrediction() {
        for (result in listOf(TextCommitResult.ACCEPTED_INTERNAL, TextCommitResult.REJECTED, TextCommitResult.NO_CONNECTION)) {
            val f = Fixture(result)
            assertEquals(result, f.service.commitTextAndPredict("粘贴", true))
            verify(f.prediction).invalidatePendingPredictions()
            verify(f.handler, never()).post(any())
            assertTrue(f.candidates.value.associationCandidates.isEmpty())
        }
    }

    @Test fun nextRealTypedCommitStillSchedulesPrediction() {
        val f = Fixture(TextCommitResult.ACCEPTED_HOST)
        f.service.commitPastedText("剪贴板")
        f.service.commitTextAndPredict("打字", false)
        verify(f.handler).post(any())
        verify(f.service).commitTextSilently("打字", false, false, CommitCaret.NONE)
    }
    // todo 139：上屏到联想请求发出隔着一次 post，空档在 post 之前就登记；post 跑完归还。
    @Test fun typedCommitReservesPredictionBeforeThePostAndReleasesItAfter() {
        val f = Fixture(TextCommitResult.ACCEPTED_HOST)
        whenever(f.prediction.reservePrediction()).thenReturn(7L)
        f.service.commitTextAndPredict("打字", false)
        val posted = argumentCaptor<Runnable>()
        verify(f.prediction).reservePrediction()
        verify(f.handler).post(posted.capture())
        verify(f.prediction, never()).releasePrediction(any())
        posted.firstValue.run()
        verify(f.prediction).releasePrediction(7L)
    }

    @Test fun pasteNeverReservesAPrediction() {
        val f = Fixture(TextCommitResult.ACCEPTED_HOST)
        f.service.commitPastedText("剪贴板")
        verify(f.prediction, never()).reservePrediction()
    }
}
