package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.floatingFooterHeightDp
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
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
import kotlin.math.roundToInt

/**
 * 悬浮键盘拖到最小（平板 300×186dp）时整块按比例缩小：工具栏、周边键、中间的键和正常大小时的比例一样
 * （用户 11-25「缩小到最小的时候比例都失调了，中间那 9 个键小成啥样了…这不是按比例的吗」）。
 * 设了 CYIME_RENDER_OUT 时把正常大小和最小两种、四种布局各存一张图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw800dp-w800dp-h1280dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingMinSizeRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val layouts = listOf("rime_ice" to "qwerty-enter-key", "pinyin_14jian" to "qwerty-enter-key",
        "t9_pinyin" to "t9-enter-key", "stroke" to null)

    @Test fun theMinimumFloatingKeyboardIsTheNormalOneScaledDown() {
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(viewModel).bindT9Controller(callbacks)
        val schema = mutableStateOf("rime_ice")
        val size = mutableStateOf(TABLET)
        val problems = mutableListOf<String>()
        rule.setContent {
            KeyboardView(viewModel, KeyboardUiState(isFloatingMode = true, keyboardWidthDp = size.value.first,
                keyboardHeightDp = size.value.second, floatingOffsetY = 40, floatingMinOffsetY = 18,
                currentSchemaId = schema.value, isDarkTheme = false),
                callbacks, inputSessionManagedByHost = true, modifier = Modifier.size(800.dp, 1280.dp).testTag("float-host"))
        }
        for ((id, enterTag) in layouts) {
            val shares = mutableListOf<Pair<Float, Float>>()
            for (dims in listOf(TABLET, REFERENCE, MINIMUM)) {
                rule.runOnIdle {
                    viewModel.dispatch(KeyboardDispatchAction.InputSessionStarted(false, id))
                    schema.value = id
                    size.value = dims
                }
                rule.waitForIdle()
                save("floating-${dims.first}x${dims.second}-$id", "floating-keyboard-card")
                if (enterTag != null && dims != TABLET) {
                    val card = rule.onNodeWithTag("floating-keyboard-card", true).fetchSemanticsNode().boundsInWindow
                    val enter = rule.onNodeWithTag(enterTag, true).fetchSemanticsNode().boundsInWindow
                    val footer = with(rule.density) { floatingFooterHeightDp(800).dp.toPx() }
                    shares += (enter.width / card.width) to (enter.height / (card.height - footer))
                }
            }
            // 回车键占键区宽、高的比例，最小时和放大回正常最矮（244dp）的同一块键盘一样。
            if (shares.size == 2) {
                if (kotlin.math.abs(shares[0].first - shares[1].first) > 0.02f) problems += "$id enter width share $shares"
                if (kotlin.math.abs(shares[0].second - shares[1].second) > 0.03f) problems += "$id enter height share $shares"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    private fun save(name: String, tag: String) {
        val dir = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val r = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInWindow
        val visible = Bitmap.createBitmap(bitmap, r.left.roundToInt().coerceAtLeast(0), r.top.roundToInt().coerceAtLeast(0),
            r.width.roundToInt().coerceAtMost(bitmap.width), r.height.roundToInt().coerceAtMost(bitmap.height))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { visible.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        /** 平板上常用的悬浮大小，只存图对照。 */
        val TABLET = 560 to 300
        /** SM-X800 拖到最小：内容高按毫米算 186dp，宽 = 186 × 1.6 取整 300dp。 */
        val MINIMUM = 300 to 186
        /** 最小那块放大到正常最矮 244dp：186 / 244 = 0.762，宽 300 / 0.762 ≈ 394。 */
        val REFERENCE = 394 to 244
    }
}
