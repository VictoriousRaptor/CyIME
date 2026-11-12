package com.kingzcheung.xime.speech

import com.kingzcheung.xime.speech.SpeechModelSelection.Finish
import org.junit.Assert.*
import org.junit.Test

class SpeechModelSelectionTest {
    private val bases = SpeechModelSelection.primaryIds
    private val modes = bases + listOf(SpeechModelCatalog.TWO_PASS, SpeechModelCatalog.ZIPFORMER_TWO_PASS)

    @Test fun everyPrimaryCanBeSelectedFromEveryStoredMode() {
        modes.forEach { from -> bases.forEach { to ->
            val result = SpeechModelSelection.selectPrimary(from, to)
            assertEquals(to, SpeechModelSelection.primary(result))
            assertEquals(to in listOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.ZIPFORMER) && SpeechModelSelection.hasCorrection(from),
                SpeechModelSelection.hasCorrection(result))
        } }
    }

    @Test fun correctionNeverChangesPrimaryOrPairsSenseVoiceWithItself() {
        modes.forEach { mode -> listOf(false, true).forEach { enabled ->
            val result = SpeechModelSelection.withCorrection(mode, enabled)
            assertEquals(SpeechModelSelection.primary(mode), SpeechModelSelection.primary(result))
            assertEquals(enabled && SpeechModelSelection.primary(mode) in listOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.ZIPFORMER), SpeechModelSelection.hasCorrection(result))
        } }
    }

    @Test fun storedPairsRemainCompatible() {
        assertEquals(SpeechModelCatalog.ZIPFORMER_TWO_PASS, SpeechModelSelection.withCorrection(SpeechModelCatalog.ZIPFORMER, true))
        assertEquals(SpeechModelCatalog.TWO_PASS, SpeechModelSelection.withCorrection(SpeechModelCatalog.PARAFORMER, true))
        assertEquals(SpeechModelCatalog.SENSEVOICE, SpeechModelSelection.withCorrection(SpeechModelCatalog.SENSEVOICE, true))
    }

    @Test(expected = IllegalArgumentException::class) fun pairCannotBeSelectedAsPrimary() {
        SpeechModelSelection.selectPrimary(SpeechModelCatalog.ZIPFORMER, SpeechModelCatalog.TWO_PASS)
    }

    @Test fun removedDialectCorrectionFallsBackToSenseVoice() {
        assertEquals(SpeechModelCatalog.TWO_PASS, SpeechModelSelection.withoutRetiredCorrector("${SpeechModelCatalog.PARAFORMER}+dolphin-base-ctc-int8"))
        assertEquals(SpeechModelCatalog.ZIPFORMER_TWO_PASS, SpeechModelSelection.withoutRetiredCorrector("${SpeechModelCatalog.ZIPFORMER}+telespeech-ctc-int8"))
        modes.forEach { assertEquals(it, SpeechModelSelection.withoutRetiredCorrector(it)) }
    }

    /** One rule for the :asr service and the settings page: which model writes the final Chinese text. */
    @Test fun chineseFinalizerMatchesWhatTheServiceRuns() {
        val c = SpeechModelCatalog
        fun final(mode: String, whole: Boolean, xasr: Boolean, chinese: Boolean = true) =
            SpeechModelSelection.pipeline(mode, chinese, whole, xasr).finalizer
        assertEquals(c.XASR, final(c.TWO_PASS, whole = true, xasr = true))
        assertEquals(c.XASR, final(c.ZIPFORMER_TWO_PASS, whole = true, xasr = true))
        assertEquals(c.SENSEVOICE, final(c.TWO_PASS, whole = false, xasr = true))
        assertEquals(c.SENSEVOICE, final(c.TWO_PASS, whole = true, xasr = false))
        // SenseVoice alone (public default): X-ASR, once downloaded, decodes Chinese whenever 整句定稿 is on.
        assertEquals(c.XASR, final(c.SENSEVOICE, whole = true, xasr = true))
        assertEquals(c.SENSEVOICE, final(c.SENSEVOICE, whole = false, xasr = true))
        assertEquals(c.SENSEVOICE, final(c.SENSEVOICE, whole = true, xasr = false))
        assertEquals(c.SENSEVOICE, final(c.SENSEVOICE, whole = true, xasr = true, chinese = false))
        assertEquals(c.PARAFORMER, final(c.PARAFORMER, whole = true, xasr = true))
        // English corrects with SenseVoice; X-ASR is Chinese only.
        assertEquals(c.SENSEVOICE, final(c.TWO_PASS, whole = true, xasr = true, chinese = false))
    }

    @Test fun wholeSentenceAppliesOnlyWhereTheSessionUsesIt() {
        val c = SpeechModelCatalog
        listOf(c.TWO_PASS, c.ZIPFORMER_TWO_PASS, c.SENSEVOICE).forEach {
            assertTrue(it, SpeechModelSelection.wholeSentenceApplies(it, chinese = true))
            assertFalse(it, SpeechModelSelection.wholeSentenceApplies(it, chinese = false))
        }
        listOf(c.PARAFORMER, c.ZIPFORMER, c.ZIPFORMER_JA, c.ZIPFORMER_JA_INT8).forEach {
            assertFalse(it, SpeechModelSelection.wholeSentenceApplies(it, chinese = true))
        }
        assertFalse(SpeechModelSelection.pipeline(c.TWO_PASS, chinese = true, wholeSentenceOn = false, xasrReady = true).wholeSentence)
        assertEquals(c.PARAFORMER, SpeechModelSelection.pipeline(c.TWO_PASS, true, true, false).preview)
        assertNull(SpeechModelSelection.pipeline(c.SENSEVOICE, true, true, false).preview)
    }

    /** 「说完后」单选的三项各对应一种原有存储组合，选了再读回来是同一项，且与 :asr 实际定稿一致。 */
    @Test fun finishChoiceRoundTripsThroughStoredValues() {
        val c = SpeechModelCatalog
        fun roundTrip(mode: String, chinese: Boolean, whole: Boolean, pick: SpeechModelSelection.Finish): SpeechModelSelection.Finish {
            val (stored, wholeAfter) = SpeechModelSelection.applyFinish(mode, chinese, pick)
            return SpeechModelSelection.finish(stored, chinese, wholeAfter ?: whole)
        }
        for (mode in listOf(c.PARAFORMER, c.TWO_PASS, c.ZIPFORMER_TWO_PASS, c.SENSEVOICE)) for (whole in listOf(true, false)) {
            SpeechModelSelection.finishOptions(mode, chinese = true).forEach { pick ->
                assertEquals("$mode whole=$whole", pick, roundTrip(mode, true, whole, pick))
            }
        }
        // 用户当前的组合：Paraformer + SenseVoice + 整句开 → 整句定稿 (X-ASR 已下载时由它定稿)。
        assertEquals(Finish.WHOLE, SpeechModelSelection.finish(c.TWO_PASS, chinese = true, wholeSentenceOn = true))
        assertEquals(Finish.SENSEVOICE, SpeechModelSelection.finish(c.TWO_PASS, chinese = true, wholeSentenceOn = false))
        assertEquals(Finish.NONE, SpeechModelSelection.finish(c.PARAFORMER, chinese = true, wholeSentenceOn = true))
        // 英文：整句开关不影响；只用 SenseVoice 或日语时没得选。
        assertEquals(Finish.SENSEVOICE, SpeechModelSelection.finish(c.TWO_PASS, chinese = false, wholeSentenceOn = true))
        assertEquals(listOf(Finish.NONE, Finish.SENSEVOICE), SpeechModelSelection.finishOptions(c.TWO_PASS, chinese = false))
        assertEquals(listOf(Finish.NONE), SpeechModelSelection.finishOptions(c.SENSEVOICE, chinese = false))
        assertEquals(listOf(Finish.NONE), SpeechModelSelection.finishOptions(c.ZIPFORMER_JA, chinese = false))
        assertEquals(listOf(Finish.NONE, Finish.WHOLE), SpeechModelSelection.finishOptions(c.SENSEVOICE, chinese = true))
        // 英文的选择从不改整句开关（它只管中文）。
        assertNull(SpeechModelSelection.applyFinish(c.TWO_PASS, chinese = false, finish = Finish.NONE).second)
        assertNull(SpeechModelSelection.applyFinish(c.PARAFORMER, chinese = false, finish = Finish.SENSEVOICE).second)
        // 流式中文选「不校正」不动整句开关（没有校正时它本来不生效）。
        assertNull(SpeechModelSelection.applyFinish(c.TWO_PASS, chinese = true, finish = Finish.NONE).second)
        // 各项与 :asr 的定稿模型一致。
        val (whole, on) = SpeechModelSelection.applyFinish(c.PARAFORMER, chinese = true, finish = Finish.WHOLE)
        assertEquals(c.XASR, SpeechModelSelection.pipeline(whole, true, on!!, xasrReady = true).finalizer)
        val (sense, off) = SpeechModelSelection.applyFinish(c.TWO_PASS, chinese = true, finish = Finish.SENSEVOICE)
        assertEquals(c.SENSEVOICE, SpeechModelSelection.pipeline(sense, true, off!!, xasrReady = true).finalizer)
    }

    @Test fun chineseZipformerIsOfferedOnlyToItsCurrentUsers() {
        val c = SpeechModelCatalog
        assertFalse(c.ZIPFORMER in SpeechModelSelection.offeredIds(c.SENSEVOICE))
        assertTrue(c.ZIPFORMER in SpeechModelSelection.offeredIds(c.ZIPFORMER))
        assertTrue(listOf(c.PARAFORMER, c.SENSEVOICE, c.ZIPFORMER_JA_INT8, c.ZIPFORMER_JA).all { it in SpeechModelSelection.offeredIds(c.SENSEVOICE) })
    }
}
