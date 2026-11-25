package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.kingzcheung.xime.association.AssociationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 用户 11-23：关了「智能联想」，英文直接输入什么候选都不给；开着照常补全。英文单词方案、滑行不归这里。 */
class EnglishCompletionOffTest {
    private fun typed(word: String, completionOn: Boolean): CandidateState {
        var state = CandidateState()
        for (letter in word) {
            state = requireNotNull(commitEnglishLetter(state, letter.toString(), false, { true },
                completionOn = completionOn) { true })
        }
        return state
    }

    @Test fun predictionOffShowsNothingForTheTypedWord() {
        val state = typed("hel", completionOn = false)
        assertTrue(state.englishCompletionOff)
        assertFalse(state.showsTypedEnglishWord)
        assertNull(associationWordAt(state, 0))
        assertFalse(commitAssociationSelection(state, 0, { error("nothing to pick") }, { _, _ -> error("nothing to pick") }))
    }

    @Test fun predictionOnKeepsTheTypedWordFirst() {
        val state = typed("hel", completionOn = true)
        assertFalse(state.englishCompletionOff)
        assertTrue(state.showsTypedEnglishWord)
        assertEquals("hel", associationWordAt(state, 0))
        val withWords = state.copy(associationCandidates = listOf("help", "hello"))
        assertEquals("help", associationWordAt(withWords, 1))
    }

    @Test fun separateSwitchesStillShowTheirWordsWithPredictionOff() {
        // 「英文出短语」、邮箱后缀是各自的开关：联想关着，它们给的词照样排在原词后面。
        val state = typed("qq", completionOn = false).copy(associationCandidates = listOf("qq.com"))
        assertTrue(state.showsTypedEnglishWord)
        assertEquals("qq", associationWordAt(state, 0))
        assertEquals("qq.com", associationWordAt(state, 1))
    }

    @Test fun predictionOffDoesNotOpenTheEnglishWordList() = runBlocking<Unit> {
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getBoolean(any(), any())).thenAnswer { it.getArgument<Boolean>(1) }
        whenever(prefs.getBoolean("smart_prediction_enabled", true)).thenReturn(false)
        val assets = mock<AssetManager>()
        val app = mock<Context>()
        whenever(app.assets).thenReturn(assets)
        whenever(app.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)).thenReturn(prefs)
        val job = Job()
        try {
            val words = PredictionManager(app, CoroutineScope(job), {}).getEnglishAssociations("hel")
            assertEquals(emptyList<String>(), words)
            assertFalse(AssociationService.isTrieInitialized())
            verify(assets, never()).open(anyOrNull<String>())
        } finally {
            job.cancel()
        }
    }

    @Before fun resetBefore() = AssociationService.release()
    @After fun resetAfter() = AssociationService.release()
}
