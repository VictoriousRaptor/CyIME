package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.BackgroundMotionEffect
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.floatingFooterHeightDp
import com.kingzcheung.xime.ui.theme.KeyboardBackgroundMotion
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardBackgroundMotion
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮键盘整张卡片一张背景：拖动条那一截是键区那张图往下的延续，不是另一张、也不是镜像；开动态背景时一起动
 * （用户 11-25：「没有连成一块，变成镜像的了，上下镜像的，各动各的，不是一个整体」）。
 * 图片主题、开关玻璃、动态背景走几步，量接缝上下两行的色差和键区里相邻两行的色差一样小。
 * 设了 CYIME_RENDER_OUT 时存每一帧的卡片图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw800dp-w800dp-h1280dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingCardBackgroundRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun theFooterContinuesTheKeyAreaImageAndMovesWithIt() {
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        SettingsPreferences.setKeyboardTheme(app, "olivcelso_desert")
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(viewModel).bindT9Controller(callbacks)
        val motion = KeyboardBackgroundMotion(BackgroundMotionEffect.CYCLE, 6000L)
        val moving = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardBackgroundMotion provides motion.takeIf { moving.value }) {
                KeyboardView(viewModel, KeyboardUiState(isFloatingMode = true, keyboardWidthDp = 560,
                    keyboardHeightDp = 300, floatingOffsetY = 40, floatingMinOffsetY = 18,
                    currentSchemaId = "t9_pinyin", isDarkTheme = false, themeId = "olivcelso_desert"),
                    callbacks, inputSessionManagedByHost = true, modifier = Modifier.size(800.dp, 1280.dp).testTag("float-host"))
            }
        }
        rule.runOnIdle { viewModel.dispatch(KeyboardDispatchAction.InputSessionStarted(false, "t9_pinyin")) }
        val problems = mutableListOf<String>()
        for (glass in listOf(false, true)) {
            rule.runOnIdle { FrostedGlassPreferences.save(app, FrostedGlassConfig.defaults(false, enabled = glass)) }
            for (step in 0..3) {
                rule.runOnIdle {
                    moving.value = step > 0
                    repeat(step * 15) { motion.advance(100) }
                }
                rule.waitForIdle()
                val name = "card-${if (glass) "glass" else "plain"}-$step"
                val (seam, inside) = seamContrast(name)
                if (seam > inside * 2.5f + 4f) problems += "$name seam=$seam inside=$inside"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** 卡片左边一截（避开小横条和按键），接缝上下两行的平均色差，和键区里接缝上面相邻两行的平均色差。 */
    private fun seamContrast(name: String): Pair<Float, Float> {
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val card = rule.onNodeWithTag("floating-keyboard-card", true).fetchSemanticsNode().boundsInWindow
        val footer = with(rule.density) { floatingFooterHeightDp(800).dp.toPx() }
        val seam = (card.bottom - footer).roundToInt()
        val x0 = (card.left + 16f).roundToInt()
        val x1 = (card.left + card.width * 0.2f).roundToInt()
        fun rowDiff(a: Int, b: Int): Float {
            var sum = 0f
            for (x in x0 until x1) {
                val p = bitmap.getPixel(x, a); val q = bitmap.getPixel(x, b)
                sum += abs((p shr 16 and 255) - (q shr 16 and 255)) + abs((p shr 8 and 255) - (q shr 8 and 255)) +
                    abs((p and 255) - (q and 255))
            }
            return sum / (x1 - x0)
        }
        System.getenv("CYIME_RENDER_OUT")?.let { dir ->
            val crop = Bitmap.createBitmap(bitmap, card.left.roundToInt(), card.top.roundToInt(),
                card.width.roundToInt(), card.height.roundToInt())
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return rowDiff(seam - 1, seam + 1) to rowDiff(seam - 3, seam - 1)
    }
}
