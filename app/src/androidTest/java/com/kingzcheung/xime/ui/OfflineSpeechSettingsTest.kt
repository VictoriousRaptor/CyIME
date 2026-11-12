package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.ui.settings.OfflineModelCard
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineSpeechSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun choice(language: InputLanguage, model: String) = rule.onNodeWithTag("speech-choice:${language.id}:$model")
    private fun screen() = rule.setContent { XimeTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { OfflineModelCard() }
    } } }
    private fun withSavedChoices(test: (AsrModelManager) -> Unit) {
        val prefs = rule.activity.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        val keys = InputLanguage.supported.map { "selected_model_${it.id}" }
        val saved = keys.associateWith { prefs.getString(it, null) }
        try { test(AsrModelManager(rule.activity)) }
        finally { prefs.edit().also { editor -> saved.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        } }.commit() }
    }
    @Test fun eachLanguageSelectsIndependentlyAndShowsOnlyCompatibleModels() = withSavedChoices { manager ->
        screen()
        val zh = InputLanguage.CHINESE; val ja = InputLanguage.JAPANESE; val en = InputLanguage.ENGLISH
        rule.onNodeWithTag("speech-finish:ja:sensevoice").assertDoesNotExist()
        rule.onNodeWithTag("speech-finish:ja:whole").assertDoesNotExist()
        rule.onNodeWithTag("speech-language:ja").performScrollTo().performClick()
        choice(ja, SpeechModelCatalog.ZIPFORMER).assertDoesNotExist()
        if (manager.selection(SpeechModelCatalog.ZIPFORMER_JA).ready) {
            choice(ja, SpeechModelCatalog.ZIPFORMER_JA).performScrollTo().performClick()
            assertEquals(SpeechModelCatalog.ZIPFORMER_JA, manager.getSelectedModelId(ja))
        } else rule.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        val japanese = manager.getSelectedModelId(ja)
        rule.onNodeWithTag("speech-language:en").performScrollTo().performClick()
        choice(en, SpeechModelCatalog.ZIPFORMER_JA).assertDoesNotExist()
        if (manager.selection(SpeechModelCatalog.SENSEVOICE).ready) {
            choice(en, SpeechModelCatalog.SENSEVOICE).performScrollTo().performClick()
            assertEquals(SpeechModelCatalog.SENSEVOICE, manager.getSelectedModelId(en))
        } else rule.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        val english = manager.getSelectedModelId(en)
        rule.onNodeWithTag("speech-language:zh").performScrollTo().performClick()
        if (manager.selection(SpeechModelCatalog.PARAFORMER).ready) {
            choice(zh, SpeechModelCatalog.PARAFORMER).performScrollTo().performClick()
            if (manager.selection(SpeechModelCatalog.SENSEVOICE).ready) {
                rule.onNodeWithTag("speech-finish:zh:sensevoice").performScrollTo().performClick().assertIsSelected()
            }
        } else rule.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(english, manager.getSelectedModelId(en))
        assertEquals(japanese, manager.getSelectedModelId(ja))
        rule.onNodeWithTag("speech-language:zh").performScrollTo()
        val output = File(rule.activity.getExternalFilesDir(null), "speech-eval/model-settings.png")
        output.parentFile?.mkdirs()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    @Test fun senseVoiceCannotCorrectItselfAndDoesNotChangeOtherLanguages() = withSavedChoices { manager ->
        val japanese = manager.getSelectedModelId(InputLanguage.JAPANESE)
        manager.setModel(SpeechModelCatalog.SENSEVOICE, InputLanguage.CHINESE)
        screen()
        rule.onNodeWithTag("speech-language:zh").performScrollTo().performClick()
        choice(InputLanguage.CHINESE, SpeechModelCatalog.SENSEVOICE).assertIsSelected()
        rule.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        // SenseVoice 单独出字时没有「SenseVoice 校正」这一项，只能选不校正或整句定稿。
        rule.onNodeWithTag("speech-finish:zh:sensevoice").assertDoesNotExist()
        rule.onNodeWithTag("speech-finish:zh:none").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("speech-finish:zh:whole").performScrollTo().assertIsDisplayed()
        assertEquals(japanese, manager.getSelectedModelId(InputLanguage.JAPANESE))
    }
}
