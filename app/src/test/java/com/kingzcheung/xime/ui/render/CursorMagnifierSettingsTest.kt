package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.settings.InputExperiencePart
import com.kingzcheung.xime.ui.settings.InputExperienceSettings
import com.kingzcheung.xime.ui.settings.SettingsSearchIndex
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw400dp-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CursorMagnifierSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun wholeRowAndWidthSliderPersistAndAreSearchableInBothThemes() {
        val app = rule.activity.application
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val dark = mutableStateOf(false)
        val width = mutableStateOf(400)
        rule.setContent {
            XimeTheme(darkTheme = dark.value, themeId = "soft_blue") {
                Surface(Modifier.width(width.value.dp).fillMaxHeight()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        InputExperienceSettings(setOf(InputExperiencePart.CURSOR), "光标与空格")
                    }
                }
            }
        }
        val target = SettingsSearchIndex.single { it.title == "键盘面板滑动光标" }.target
        for (title in listOf("光标放大镜", "放大镜宽度")) {
            val entry = SettingsSearchIndex.single { it.title == title }
            assertEquals(target, entry.target)
            assertEquals("光标与空格", entry.section)
        }
        val slider = rule.onNodeWithTag("cursor-magnifier-width-slider")
        val toggle = rule.onNodeWithTag("settings-search-anchor:光标放大镜")
        toggle.performScrollTo().assertIsOn()
        slider.performScrollTo().assertRangeInfoEquals(ProgressBarRangeInfo(70f, 50f..200f, 14))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        rule.runOnIdle { assertEquals(150f, KeyboardInputPreferences.read(app).cursorMagnifierWidthDp, 0f) }
        toggle.performClick().assertIsOff()
        slider.assertIsNotEnabled()
        rule.runOnIdle {
            val saved = KeyboardInputPreferences.read(app)
            assertFalse(saved.cursorMagnifierEnabled)
            assertEquals(150f, saved.cursorMagnifierWidthDp, 0f)
        }
        toggle.performClick().assertIsOn()
        slider.assertIsEnabled()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        rule.runOnIdle { assertEquals(200f, KeyboardInputPreferences.read(app).cursorMagnifierWidthDp, 0f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(50f, 50f..200f, 14))
        rule.runOnIdle { assertEquals(50f, KeyboardInputPreferences.read(app).cursorMagnifierWidthDp, 0f) }
        for (w in listOf(320, 400)) for (isDark in listOf(false, true)) {
            rule.runOnIdle { width.value = w; dark.value = isDark }
            slider.performScrollTo().assertIsDisplayed()
            toggle.assertIsDisplayed().assertIsOn()
            rule.onNodeWithText("放大镜宽度：50 dp").assertIsDisplayed()
            rule.onNodeWithText("宽度 50–200 dp，每档 10 dp，默认 70 dp，以屏幕可用宽度为上限。").assertIsDisplayed()
            rule.onNodeWithText("长按空格、滑动底部留白或键盘面板移光标时，放大当前行文字。", substring = false,
                useUnmergedTree = true).assertIsDisplayed()
            val out = System.getenv("CYIME_RENDER_OUT") ?: continue
            File(out).mkdirs()
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out, "magnifier-settings-$w-${if (isDark) "dark" else "light"}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
