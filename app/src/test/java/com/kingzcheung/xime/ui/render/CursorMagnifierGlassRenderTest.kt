package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.service.HardwareCursorAnchor
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.LiquidGlass
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Simulates public system blur availability; GPU blur itself still requires device acceptance. */
@Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
class MagnifierWindowBlurShadow : ShadowWindowManagerImpl() {
    @Implementation fun isCrossWindowBlurEnabled(): Boolean = available
    @Implementation fun addCrossWindowBlurEnabledListener(executor: Executor, listener: Consumer<Boolean>) {
        listeners[listener] = executor
        executor.execute { listener.accept(available) }
    }
    @Implementation fun removeCrossWindowBlurEnabledListener(listener: Consumer<Boolean>) {
        listeners.remove(listener)
    }
    companion object {
        private val listeners = mutableMapOf<Consumer<Boolean>, Executor>()
        private var available = true
        fun setAvailable(value: Boolean) {
            available = value
            listeners.toMap().forEach { (listener, executor) -> executor.execute { listener.accept(value) } }
        }
        @Resetter @JvmStatic fun resetBlur() { listeners.clear(); available = true }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw400dp-w400dp-h850dp-mdpi",
    shadows = [MagnifierWindowBlurShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CursorMagnifierGlassRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun prepare() {
        SettingsPreferences.getPrefsPublic(rule.activity).edit().clear().commit()
        KeysConfigHelper.loadConfig(rule.activity)
        KeyboardThemes.initFromConfig(rule.activity)
        LiquidGlass.rebind(rule.activity)
    }

    private fun blurViews(): List<View> = WindowInspector.getGlobalWindowViews().filter {
        (it.layoutParams as? WindowManager.LayoutParams)?.title?.toString() == "CyIME keyboard blur: cursor-magnifier"
    }

    private fun popup(): View = WindowInspector.getGlobalWindowViews().last { it.javaClass.simpleName == "PopupLayout" }

    private fun settle() { repeat(4) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() } }

    private fun lensAlpha(): Float {
        var alpha = 0f
        rule.runOnIdle {
            val popup = popup()
            val image = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            popup.draw(android.graphics.Canvas(image))
            alpha = Color(image.getPixel((popup.width / 4).coerceAtLeast(1), 8)).alpha
        }
        return alpha
    }

    private fun assertBlurMatchesPopup(): View {
        val blur = blurViews().single()
        val params = blur.layoutParams as WindowManager.LayoutParams
        val popup = popup()
        val at = IntArray(2).also(popup::getLocationOnScreen)
        val origin = IntArray(2).also(rule.activity.window.decorView::getLocationOnScreen)
        assertEquals(at[0] - origin[0], params.x)
        assertEquals(at[1] - origin[1], params.y)
        assertEquals(popup.width, params.width)
        assertEquals(popup.height, params.height)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, params.type)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            (popup.layoutParams as WindowManager.LayoutParams).type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        return blur
    }

    @Test fun liveSettingsMoveResizeAndRemoveTheSameBlurWindowWithoutChangingTracking() {
        var glass = FrostedGlassConfig(enabled = true, backgroundOpacity = .8f)
        FrostedGlassPreferences.save(rule.activity, glass.copy(backgroundOpacity = .9f), isDark = false)
        FrostedGlassPreferences.save(rule.activity, glass, isDark = true)
        val caret = mutableStateOf<HardwareCursorAnchor?>(HardwareCursorAnchor(200f, 300f, 200f, 324f))
        val dark = mutableStateOf(true)
        val session = mutableStateOf(1L)
        val width = mutableStateOf(200f)
        val visible = mutableStateOf(true)
        val tracking = mutableListOf<Boolean>()
        rule.setContent {
            XimeTheme(darkTheme = dark.value) {
                CompositionLocalProvider(LocalKeyboardInputPreferences provides rememberKeyboardInputPreferences(dark.value)) {
                    Box(Modifier.fillMaxSize().background(Color.Gray)) {
                        if (visible.value) CursorMagnifier(session.value, null,
                            if (dark.value) Color.Black else Color.White, if (dark.value) Color.White else Color.Black, Color.Cyan,
                            composition = CursorMagnifierText("左右预览", 2), readAnchor = { caret.value },
                            onTrackingChange = { _, active -> tracking += active }, widthDp = width.value)
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(96)
        settle()
        val original = assertBlurMatchesPopup()
        assertEquals(.8f, lensAlpha(), .02f)
        rule.runOnIdle { dark.value = false }
        settle()
        assertEquals(.9f, lensAlpha(), .02f)
        assertSame(original, assertBlurMatchesPopup())
        rule.runOnIdle {
            dark.value = true
            glass = glass.copy(keyOpacity = 0f)
            FrostedGlassPreferences.save(rule.activity, glass, true)
        }
        settle()
        assertEquals(.8f, lensAlpha(), .02f)
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(200.dp).assertHeightIsEqualTo(56.dp)
        rule.runOnIdle {
            caret.value = HardwareCursorAnchor(310f, 400f, 310f, 424f)
            width.value = 50f
            glass = glass.copy(blurRadiusDp = 28f, backgroundOpacity = .95f)
            FrostedGlassPreferences.save(rule.activity, glass, isDark = true)
        }
        rule.mainClock.advanceTimeBy(96)
        settle()
        assertSame(original, assertBlurMatchesPopup())
        assertEquals(.95f, lensAlpha(), .02f)
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(50.dp).assertHeightIsEqualTo(56.dp)
        rule.runOnIdle { MagnifierWindowBlurShadow.setAvailable(false) }
        settle()
        assertTrue(blurViews().isEmpty())
        assertEquals(1f, lensAlpha(), .001f)
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle { MagnifierWindowBlurShadow.setAvailable(true) }
        settle()
        assertBlurMatchesPopup()
        rule.runOnIdle { glass = glass.copy(blurRadiusDp = 0f); FrostedGlassPreferences.save(rule.activity, glass, true) }
        settle()
        assertTrue(blurViews().isEmpty())
        rule.runOnIdle { glass = glass.copy(blurRadiusDp = 10f); FrostedGlassPreferences.save(rule.activity, glass, true) }
        settle()
        assertBlurMatchesPopup()
        rule.runOnIdle { glass = glass.copy(enabled = false); FrostedGlassPreferences.save(rule.activity, glass, true) }
        settle()
        assertTrue(blurViews().isEmpty())
        rule.runOnIdle { glass = glass.copy(enabled = true); FrostedGlassPreferences.save(rule.activity, glass, true) }
        settle()
        assertBlurMatchesPopup()
        rule.runOnIdle { caret.value = null }
        rule.mainClock.advanceTimeBy(96)
        settle()
        assertTrue(blurViews().isEmpty())
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { caret.value = HardwareCursorAnchor(200f, 300f, 200f, 324f) }
        rule.mainClock.advanceTimeBy(96)
        settle()
        assertBlurMatchesPopup()
        rule.runOnIdle { assertEquals(listOf(true), tracking); session.value = 2L }
        rule.mainClock.advanceTimeBy(96)
        settle()
        assertBlurMatchesPopup()
        rule.runOnIdle { assertEquals(listOf(true, false, true), tracking); visible.value = false }
        settle()
        assertTrue(blurViews().isEmpty())
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(true, false, true, false), tracking) }
    }

    @Test fun bothAppearancesShowOpacityAndLiquidRimAtUnchangedSizes() {
        SettingsPreferences.setGlassLiquid(rule.activity, false)
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF808080)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (dark in listOf(false, true)) {
                    XimeTheme(darkTheme = dark) {
                        val glass = FrostedGlassConfig(enabled = true, backgroundOpacity = .8f)
                        for (width in listOf(10, 160, 280)) {
                            CursorMagnifierCapsule(CursorMagnifierText("甲😀 العربية 光标预览", 8),
                                if (dark) Color.Black else Color.White, if (dark) Color.White else Color.Black,
                                Color(0xFF4285F4), Modifier.width(width.dp),
                                glass, blurBehind = true)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        fun shot(name: String): Bitmap {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            System.getenv("CYIME_RENDER_OUT")?.let {
                File(it).mkdirs()
                File(it, "$name.png").outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            }
            return bitmap
        }
        val plainRim = shot("magnifier-glass-light-dark")
        rule.runOnIdle { SettingsPreferences.setGlassLiquid(rule.activity, true) }
        rule.waitForIdle()
        val liquidRim = shot("magnifier-liquid-light-dark")
        val nodes = rule.onAllNodesWithTag("cursor-magnifier").fetchSemanticsNodes()
        assertEquals(6, nodes.size)
        nodes.forEach { assertEquals(56f, it.boundsInWindow.height, .1f) }
        // Sample the straight top edge of each wide capsule, away from text and the centre caret.
        for (index in listOf(2, 5)) {
            val bounds = nodes[index].boundsInWindow
            val x = (bounds.left + 70).roundToInt()
            val y = bounds.top.roundToInt()
            assertNotEquals(plainRim.getPixel(x, y), liquidRim.getPixel(x, y))
            val interior = Color(liquidRim.getPixel(x, y + 9))
            if (index == 2) assertTrue(interior.red > .8f && interior.red < .99f)
            else assertTrue(interior.red > .01f && interior.red < .2f)
        }
    }
}
