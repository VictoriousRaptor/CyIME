package com.kingzcheung.xime.ui.render

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.LetterLayouts
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayout
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardExplicitWidth
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.menubar.SchemaListView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 法语 QWERTY 和 AZERTY（用户 11-16）：手机、平板，浅色和纯黑，再加法语的布局选择面板。
 * 只在设了 CYIME_RENDER_OUT 时出图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1000dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrenchAzertyRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun renderFrenchLayouts() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app); KeyboardThemes.reload(app)
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        val schemas = listOf(SchemaInfo("rime_ice", "雾凇拼音", "", "", ""), SchemaInfo("french", "法语", "", "", ""))
        var theme by mutableStateOf("soft_blue")
        var tablet by mutableStateOf(false)
        var panel by mutableStateOf(false)
        var revision by mutableStateOf(0)
        rule.setContent {
            @Suppress("UNUSED_EXPRESSION") revision
            val dark = theme == "pure_black"
            val palette = resolveKeyboardPalette(theme, dark)
            val config = FrostedGlassConfig.defaults(dark, enabled = false)
            val width = if (tablet) 900.dp else 400.dp
            val height = if (tablet) 340.dp else 280.dp
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (tablet) 900 else 400; screenHeightDp = if (tablet) 1280 else 860
                smallestScreenWidthDp = screenWidthDp
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalKeyboardExplicitWidth provides true,
                LocalKeyboardPalette provides palette,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = config)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width, height).testTag("layout-body")
                        .keyboardBackground(KeyboardThemes.getRenderingScheme(theme).keyboardBackground, dark,
                            palette.background, config)) {
                        if (panel) SchemaListView(schemas.filter { it.schemaId == "french" }, "french",
                            palette.background, palette.accent, palette.text, palette.key,
                            onSelectSchema = {}, modifier = Modifier.requiredSize(width, height), availableSchemas = schemas)
                        else KeyboardLayout({}, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            KeyboardUiState(currentSchemaId = "french", themeId = theme, isDarkTheme = dark, isAsciiMode = false),
                            false, Modifier.requiredSize(width, height))
                    }
                }
            }
        }
        File(out!!).mkdirs()
        fun shot(name: String) {
            val body = rule.onNodeWithTag("layout-body", true).fetchSemanticsNode().boundsInRoot
            val root = rule.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(full)) }
            val crop = Bitmap.createBitmap(full, body.left.toInt().coerceAtLeast(0), body.top.toInt().coerceAtLeast(0),
                body.width.toInt().coerceAtMost(full.width), body.height.toInt().coerceAtMost(full.height))
            File(out, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun show(azerty: Boolean, t: String, tab: Boolean, picker: Boolean = false) {
            LetterLayouts.setForTest(InputLanguage.FRENCH, if (azerty) LetterLayouts.AZERTY_FRENCH else null)
            KeysConfigHelper.applyLetterLayouts()
            KeysConfigHelper.setActiveKeyboardSchema("french")
            rule.runOnIdle { vm.resetKeyboard(false, "french"); theme = t; tablet = tab; panel = picker; revision++ }
            rule.waitForIdle()
        }
        for (azerty in listOf(false, true)) for (t in listOf("soft_blue", "pure_black")) for (tab in listOf(false, true)) {
            show(azerty, t, tab)
            shot("french-${if (azerty) "azerty" else "qwerty"}-$t-${if (tab) "tablet" else "phone"}")
        }
        for (azerty in listOf(false, true)) {
            show(azerty, "soft_blue", false, picker = true)
            shot("picker-french-${if (azerty) "azerty" else "qwerty"}-phone")
        }
        LetterLayouts.setForTest(InputLanguage.FRENCH, null)
        KeysConfigHelper.applyLetterLayouts()
    }
}
