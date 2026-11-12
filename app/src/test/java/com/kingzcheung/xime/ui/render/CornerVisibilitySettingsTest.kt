package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w420dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CornerVisibilitySettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var dark by mutableStateOf(true)
    private var width by mutableStateOf(360)
    private var appearance by mutableStateOf(false)

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val bounds = rule.onNodeWithTag("settings-root").fetchSemanticsNode().boundsInRoot
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val crop = Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(),
            bounds.width.toInt(), bounds.height.toInt())
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle(); bitmap.recycle()
    }

    @Test fun independentSwitchesKeepStoredValuesAndInputChoicesAcrossBothEntrances() {
        AppFonts.initialize(rule.activity)
        SettingsPreferences.setSwipeUpHintsEnabled(rule.activity, false)
        SettingsPreferences.setSwipeDownHintsEnabled(rule.activity, true)
        KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS, reverseSymbolSwipe = true).save(rule.activity)
        rule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.requiredSize(width.dp, 920.dp).testTag("settings-root")) {
                    if (appearance) KeyboardAppearanceSettings()
                    else LayoutDisplaySettingsContent(onBack = {}, initialPage = "gestures")
                }
            }
        }
        rule.onNodeWithTag("settings-search-anchor:上方角标提示").assertIsOff()
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsOn()
        rule.onNodeWithTag("settings-search-anchor:上方角标提示").performClick()
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").performClick()
        rule.onNodeWithTag("settings-search-anchor:上方角标提示").assertIsOn()
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsOff()
        rule.runOnIdle {
            val saved = KeyboardInputPreferences.read(rule.activity)
            assertEquals(SymbolInputMode.LONG_PRESS, saved.symbolInputMode)
            assertTrue(saved.reverseSymbolSwipe)
            assertTrue(SettingsPreferences.isSwipeUpHintsEnabled(rule.activity))
            assertFalse(SettingsPreferences.isSwipeDownHintsEnabled(rule.activity))
        }
        rule.onNodeWithTag("corner-input:SWIPE").performScrollTo().performClick()
        rule.onNodeWithTag("settings-search-anchor:上方角标提示").performScrollTo().assertIsOn()
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsOff()
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").performClick()
        for (w in listOf(320, 400)) for (isDark in listOf(true, false)) {
            rule.runOnIdle { width = w; dark = isDark }
            rule.waitForIdle()
            rule.onNodeWithTag("settings-search-anchor:上方角标提示").assertIsDisplayed()
            rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("A á", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            shot("corner-diagnostic-" + w)
            val layout = layouts.single()
            // A reused paragraph can retain a wider layout box after the card shrinks.
            // Check the actual visible text, rather than that cached paragraph width.
            val text = layout.layoutInput.text.text
            assertEquals("重音字母必须完整显示", text.length, layout.getLineEnd(0, visibleEnd = true))
            assertFalse("重音字母裁切: width=" + w + " size=" + layout.size +
                " widthOverflow=" + layout.didOverflowWidth + " heightOverflow=" + layout.didOverflowHeight +
                " intrinsicWidth=" + layout.multiParagraph.intrinsics.maxIntrinsicWidth +
                " paragraphWidth=" + layout.multiParagraph.width +
                " paragraphHeight=" + layout.multiParagraph.height,
                layout.didOverflowHeight || layout.isLineEllipsized(0) ||
                    layout.getLineLeft(0) < 0f || layout.getLineRight(0) > layout.size.width)
            shot("corner-settings-$w-" + if (isDark) "dark" else "light")
        }
        rule.runOnIdle { appearance = true }
        rule.onNodeWithTag("settings-search-anchor:上方角标提示").assertIsOn().performClick()
        rule.runOnIdle { appearance = false }
        rule.onNodeWithTag("settings-search-anchor:上方角标提示").assertIsOff()
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsOn()
        for (title in listOf("上方角标提示", "下方角标提示")) {
            assertEquals(SettingsRoutes.LayoutDisplay + "?page=gestures",
                SettingsSearchIndex.single { it.title == title }.target)
        }
    }
}
