package com.kingzcheung.xime.ui.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.ui.settings.OfflineModelCard
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.ui.settings.SpeechModelPicker
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w320dp-h740dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechModelPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun cantoneseDetectionIsOnlyOfferedForChineseSenseVoice() {
        val models = AsrModelManager(rule.activity)
        val language = InputLanguage.CHINESE
        models.setModel(SpeechModelCatalog.SENSEVOICE, language)
        val revision = mutableIntStateOf(0)
        rule.setContent { XimeTheme { key(revision.intValue) { OfflineModelCard() } } }
        val toggle = rule.onNodeWithText("自动识别语音语言")
        toggle.assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(SpeechModelCatalog.SENSEVOICE_AUTO, models.getSelectedModelId(language))
            System.getenv("CYIME_RENDER_OUT")?.let { dir ->
                val view = rule.activity.window.decorView
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                File(dir, "sensevoice-cantonese.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        }
        toggle.performClick()
        rule.runOnIdle {
            assertEquals(SpeechModelCatalog.SENSEVOICE, models.getSelectedModelId(language))
            models.setModel(SpeechModelCatalog.PARAFORMER, language)
            revision.intValue++
        }
        toggle.assertDoesNotExist()
    }

    @Test fun providerPresetIsEditableAndDoesNotTransferAnotherProvidersKey() {
        val api = com.kingzcheung.xime.speech.OnlineSpeechApi
        val context = rule.activity
        api.save(context, com.kingzcheung.xime.speech.OnlineSpeechApi.Config(api.DEFAULT_BASE_URL, "synthetic-key", api.DEFAULT_MODEL))
        rule.setContent {
            XimeTheme(darkTheme = true) { com.kingzcheung.xime.ui.settings.OnlineSpeechApiSection() }
        }
        rule.onNodeWithText("OpenAI").performClick()
        rule.onNodeWithText("Groq").performClick()
        rule.onNodeWithTag("speech-api-url").assertDoesNotExist()
        rule.onNodeWithTag("speech-api-test").assertIsNotEnabled()
        rule.onNodeWithTag("speech-api-key").performTextInput("test-groq")
        rule.onNodeWithTag("speech-api-test").assertIsEnabled()
        rule.runOnIdle {
            assertEquals("whisper-large-v3-turbo", api.config(context).model)
            System.getenv("CYIME_RENDER_OUT")?.let { directory ->
                val view = rule.activity.window.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                File(directory, "voice-presets.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
        rule.onNodeWithText("修改接口配置").performClick()
        rule.onNodeWithTag("speech-api-model").performTextReplacement("whisper-large-v3")
        rule.runOnIdle { assertEquals("whisper-large-v3", api.config(context).model) }
    }

    @Test fun downloadAndRetryBelongToOneModelAtLargeFontAndNeverSelectIt() {
        val target = SpeechModelCatalog.ZIPFORMER_JA
        val installed = SpeechModelCatalog.ZIPFORMER_JA_INT8
        val downloads = mutableStateMapOf<String, ModelDownloadState>()
        val ready = mutableStateListOf(installed, SpeechModelCatalog.SENSEVOICE)
        val calls = mutableListOf<String>()
        RuntimeEnvironment.setFontScale(1.5f)
        rule.setContent {
            XimeTheme(darkTheme = true) {
                SpeechModelPicker(InputLanguage.JAPANESE, installed, ready.toSet(), downloads,
                    onSelect = { calls += "select:$it" }, onDownload = { calls += "get:$it" }, onClose = {})
            }
        }
        val choice = rule.onNodeWithTag("speech-choice:ja:$target")
        val button = rule.onNodeWithTag("speech-download:$target")
        button.performScrollTo()
        val row = choice.assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        val tap = button.fetchSemanticsNode().boundsInRoot
        assertTrue("整块下载热区在型号右侧且与型号同一行", tap.left >= row.right - 1 &&
            tap.center.y in row.top..row.bottom && minOf(tap.width, tap.height) >= 47 * rule.density.density)
        rule.onNodeWithTag("speech-download:$installed").assertDoesNotExist()
        rule.onNodeWithTag("speech-choice:ja:${SpeechModelCatalog.ZIPFORMER}").assertDoesNotExist()
        rule.runOnIdle { System.getenv("CYIME_RENDER_OUT")?.let { directory ->
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            File(directory, "speech-model-dialog-large.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        } }
        button.performClick()
        rule.runOnIdle { downloads[target] = ModelDownloadState.Downloading(.42f, 42, 100) }
        rule.onNodeWithText("下载中 42%", useUnmergedTree = true).assertExists()
        button.assertDoesNotExist()
        rule.runOnIdle { downloads[target] = ModelDownloadState.Error("连接中断") }
        rule.onNodeWithText("连接中断", useUnmergedTree = true).assertExists()
        button.performScrollTo().performClick()
        assertEquals(listOf("get:$target", "get:$target"), calls)
        rule.runOnIdle { downloads[target] = ModelDownloadState.Complete; ready.add(target) }
        button.assertDoesNotExist()
        rule.onNodeWithTag("speech-choice:ja:$installed").assertIsSelected()
        choice.assertIsEnabled().performScrollTo().performClick()
        assertEquals("select:$target", calls.last())
    }
}
