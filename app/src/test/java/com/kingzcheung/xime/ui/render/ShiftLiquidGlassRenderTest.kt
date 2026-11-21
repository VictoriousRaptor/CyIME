package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayout
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardExplicitWidth
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.LiquidGlass
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 11-19 用户「Shift 键还是没有液态玻璃效果」：Shift 键帽以前不经过 keyGlow，玻璃亮边从来没画。
 * 开着玻璃和液态玻璃时，Shift 上沿要和同是功能键的删除键一样有一道亮边。设了 CYIME_RENDER_OUT 时出图 shift-liquid-glass.png。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShiftLiquidGlassRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun liquidOn() {
        SettingsPreferences.setGlassLiquid(context, true)
        LiquidGlass.rebind(context)
    }

    @After fun liquidOff() {
        SettingsPreferences.setGlassLiquid(context, false)
        LiquidGlass.rebind(context)
    }

    private fun Bitmap.lum(x: Int, y: Int): Float = Color(getPixel(x, y)).luminance()

    /** 上沿往下 0~3 像素里最亮的一点，减去键中间的亮度：有亮边时明显大于 0。 */
    private fun rim(full: Bitmap, tag: String): Float {
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val x = b.center.x.roundToInt()
        val top = b.top.roundToInt()
        // 键帽有视觉内边距，从外框上沿往下找第一段亮边。
        val edge = (top..top + (b.height / 4).roundToInt()).maxOf { full.lum(x, it) }
        return edge - full.lum(x, b.center.y.roundToInt())
    }

    @Test fun shiftGetsTheSameRimAsLetterKeys() {
        KeysConfigHelper.loadConfig(context)
        KeysConfigHelper.setActiveKeyboardSchema("")
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(context)
        AppFonts.initialize(context)
        val vm = KeyboardViewModel(context)
        rule.setContent {
            XimeTheme(darkTheme = true, themeId = "soft_blue") {
                CompositionLocalProvider(LocalKeyboardExplicitWidth provides true,
                    LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = FrostedGlassConfig(enabled = true))) {
                    Box(Modifier.requiredSize(411.dp, 260.dp).background(Color(0xFF203040))) {
                        KeyboardLayout({}, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            KeyboardUiState(currentSchemaId = ""), false, Modifier.requiredSize(411.dp, 260.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(full)) }
        System.getenv("CYIME_RENDER_OUT")?.let { dir ->
            java.io.File(dir).mkdirs()
            java.io.File(dir, "shift-liquid-glass.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val letter = rim(full, "qwerty-key:z")
        val delete = rim(full, "qwerty-delete-key")
        val shift = rim(full, "shift-key")
        println("rim letter=$letter delete=$delete shift=$shift")
        assertTrue("字母键先要有亮边（$letter），否则这张图说明不了什么", letter > .03f)
        // Shift 和删除键同是功能键，玻璃样式一样（功能键本来就比字母键暗）：亮边要和删除键相当。
        assertTrue("Shift 没有液态玻璃亮边：Shift $shift，删除键 $delete，字母键 $letter", shift > .03f && shift > delete * .7f)
    }
}
