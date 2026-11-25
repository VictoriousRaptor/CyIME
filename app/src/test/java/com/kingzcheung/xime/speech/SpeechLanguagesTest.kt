package com.kingzcheung.xime.speech

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*
import java.io.File

class SpeechLanguagesTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun japaneseLightModelUsesQuantizedFilesAndNeverRequiresSenseVoice() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(files.root)
        val manager = AsrModelManager(context)
        val small = manager.selection(SpeechModelCatalog.ZIPFORMER_JA_INT8, InputLanguage.JAPANESE)
        val original = manager.selection(SpeechModelCatalog.ZIPFORMER_JA, InputLanguage.JAPANESE)
        assertEquals("offline-transducer", small.first!!.modelType)
        assertTrue(small.first.encoderFile.endsWith(".int8.onnx"))
        assertTrue(small.first.decoderFile.endsWith(".int8.onnx"))
        assertTrue(small.first.joinerFile.endsWith(".int8.onnx"))
        assertNull(small.secondDir)
        assertNotEquals(small.firstDir, original.firstDir)
        assertFalse(small.ready)
        assertFalse(SpeechLanguages.supports(SpeechModelCatalog.ZIPFORMER_JA_INT8, InputLanguage.CHINESE))
        assertFalse(SpeechModelSelection.supportsCorrection(SpeechModelCatalog.ZIPFORMER_JA_INT8))
        val manifest = SpeechModelCatalog.models.first { it.id == SpeechModelCatalog.ZIPFORMER_JA_INT8 }
        assertTrue(manifest.files.sumOf { it.sizeBytes } < 170_000_000L)
    }

    @Test fun eachLanguageKeepsItsOwnModelAndCorrectionWithoutOverwritingLegacyPreferences() {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        val stored = mutableMapOf("selected_model" to SpeechModelCatalog.TWO_PASS)
        whenever(context.filesDir).thenReturn(files.root)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { stored[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { stored[it.getArgument(0)] = it.getArgument(1); editor }
        val manager = AsrModelManager(context)
        manager.setModel(SpeechModelCatalog.ZIPFORMER_TWO_PASS, InputLanguage.CHINESE)
        manager.setModel(SpeechModelCatalog.PARAFORMER, InputLanguage.ENGLISH)
        manager.setModel(SpeechModelCatalog.ZIPFORMER_JA, InputLanguage.JAPANESE)
        manager.setRefinementEnabled(false, InputLanguage.CHINESE)
        assertEquals(SpeechModelCatalog.ZIPFORMER, manager.getSelectedModelId(InputLanguage.CHINESE))
        assertEquals(SpeechModelCatalog.PARAFORMER, manager.getSelectedModelId(InputLanguage.ENGLISH))
        assertEquals(SpeechModelCatalog.ZIPFORMER_JA, manager.getSelectedModelId(InputLanguage.JAPANESE))
        assertEquals(SpeechModelCatalog.TWO_PASS, manager.getSelectedModelId())
        assertThrows(IllegalArgumentException::class.java) { manager.setModel(SpeechModelCatalog.ZIPFORMER, InputLanguage.JAPANESE) }
        assertThrows(IllegalStateException::class.java) { manager.selectionForLanguage(InputLanguage.JAPANESE) }
        assertEquals(SpeechModelCatalog.ZIPFORMER_JA, stored["selected_model_ja"])
    }

    @Test fun japaneseWithNothingDownloadedPointsToTheLightModel() {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        val stored = mutableMapOf("selected_model" to SpeechModelCatalog.TWO_PASS)
        whenever(context.filesDir).thenReturn(files.root)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { stored[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
        val manager = AsrModelManager(context)
        assertEquals(SpeechModelCatalog.ZIPFORMER_JA_INT8, manager.getSelectedModelId(InputLanguage.JAPANESE))
        // 只是没选时的指向，不写进设置；用户自己选的原版照旧保留（见上一个用例）。
        assertNull(stored["selected_model_ja"])
    }

    @Test fun primaryAndCorrectionMustBothSupportTheLanguage() {
        assertTrue(SpeechLanguages.supports(SpeechModelCatalog.ZIPFORMER_TWO_PASS, InputLanguage.CHINESE))
        assertFalse(SpeechLanguages.supports(SpeechModelCatalog.ZIPFORMER_TWO_PASS, InputLanguage.ENGLISH))
        assertFalse(SpeechLanguages.supports(SpeechModelCatalog.TWO_PASS, InputLanguage.JAPANESE))
        assertTrue(SpeechLanguages.supports(SpeechModelCatalog.TWO_PASS, InputLanguage.ENGLISH))
        InputLanguage.speech.forEach { assertTrue(SpeechLanguages.supports(SpeechModelCatalog.SENSEVOICE, it)) }
        // 没有西班牙、德、法、俄、韩语识别器：不能交给 SenseVoice 当中文识别。
        for (letters in com.kingzcheung.xime.settings.LetterLanguage.entries)
            SpeechModelSelection.primaryIds.forEach { assertFalse(SpeechLanguages.supports(it, letters.language)) }
        InputLanguage.entries.forEach { assertFalse(SpeechLanguages.supports("unknown-model", it)) }
        SpeechModelSelection.primaryIds.forEach { assertFalse(SpeechLanguages.supports(it, InputLanguage.UNSPECIFIED)) }
    }

    @Test fun compatibleChoiceIsKeptAndItsMissingFilesAreNotSilentlyReplaced() {
        assertEquals(SpeechModelCatalog.TWO_PASS, SpeechLanguages.select(InputLanguage.ENGLISH, SpeechModelCatalog.TWO_PASS) { true })
        val checked = mutableListOf<String>()
        val error = assertThrows(IllegalStateException::class.java) {
            SpeechLanguages.select(InputLanguage.CHINESE, SpeechModelCatalog.ZIPFORMER) { checked += it; false }
        }
        assertEquals(listOf(SpeechModelCatalog.ZIPFORMER), checked)
        assertTrue(error.message!!.contains("尚未完整下载"))
    }

    @Test fun incompatibleLanguageSelectsAnInstalledRecognizerWithoutUsingAnUnsupportedFirstPass() {
        assertEquals(SpeechModelCatalog.PARAFORMER,
            SpeechLanguages.select(InputLanguage.CHINESE, SpeechModelCatalog.ZIPFORMER_JA) { true })
        assertEquals(SpeechModelCatalog.SENSEVOICE,
            SpeechLanguages.select(InputLanguage.JAPANESE, SpeechModelCatalog.ZIPFORMER_TWO_PASS) { it == SpeechModelCatalog.SENSEVOICE })
        assertEquals(SpeechModelCatalog.PARAFORMER,
            SpeechLanguages.select(InputLanguage.ENGLISH, SpeechModelCatalog.ZIPFORMER) { it == SpeechModelCatalog.PARAFORMER })
        assertEquals(SpeechModelCatalog.SENSEVOICE,
            SpeechLanguages.select(InputLanguage.ENGLISH, SpeechModelCatalog.ZIPFORMER) { it == SpeechModelCatalog.SENSEVOICE })
        assertEquals(SpeechModelCatalog.SENSEVOICE,
            SpeechLanguages.select(InputLanguage.ENGLISH, SpeechModelCatalog.ZIPFORMER_TWO_PASS) { true })
    }

    @Test fun unknownLanguageAndMissingResourcesProduceSpecificErrors() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechLanguages.select(InputLanguage.UNSPECIFIED, SpeechModelCatalog.SENSEVOICE) { error("must not inspect resources") }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            SpeechLanguages.select(InputLanguage.JAPANESE, SpeechModelCatalog.TWO_PASS) { false }
        }
        assertTrue(error.message!!.contains("日语"))
        assertTrue(error.message!!.contains("SenseVoice"))
    }

    @Test fun multilingualSelectionPreservesPreferencesAndSharesLanguageDetectionModel() {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(context.filesDir).thenReturn(files.root)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
            if (it.getArgument<String>(0) == "selected_model") SpeechModelCatalog.ZIPFORMER_TWO_PASS else it.getArgument<String?>(1)
        }
        val sense = File(files.root, "models/${SpeechModelCatalog.SENSEVOICE}").apply { mkdirs() }
        File(sense, "model.int8.onnx").writeBytes(byteArrayOf(1))
        File(sense, "tokens.txt").writeText("test fixture")
        val manager = AsrModelManager(context)
        val japanese = manager.selectionForLanguage(InputLanguage.JAPANESE)
        val english = manager.selectionForLanguage(InputLanguage.ENGLISH)
        assertEquals(SpeechModelCatalog.SENSEVOICE, japanese.mode)
        assertNull(japanese.first)
        assertEquals("auto", japanese.recognitionLanguage)
        assertEquals("auto", english.recognitionLanguage)
        assertEquals(japanese.key, english.key)
        assertEquals(SpeechModelCatalog.ZIPFORMER_TWO_PASS, manager.getSelectedModelId())
        verify(prefs, never()).edit()
        File(sense, "tokens.txt").writeText("")
        assertThrows(IllegalStateException::class.java) { manager.selectionForLanguage(InputLanguage.JAPANESE) }
    }

    @Test fun incompatibleExplicitSelectionIsRejectedBeforeFileAccess() {
        val context = mock<Context>()
        assertThrows(IllegalArgumentException::class.java) {
            AsrModelManager(context).selection(SpeechModelCatalog.ZIPFORMER_TWO_PASS, InputLanguage.JAPANESE)
        }
        verifyNoInteractions(context)
    }
}
