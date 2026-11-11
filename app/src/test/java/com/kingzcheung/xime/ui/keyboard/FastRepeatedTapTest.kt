package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 打快了吞键（用户反馈 10-30）：小鹤双拼打「输入」= uuru，两只拇指交替按同一个 u，
 * 前一下还没抬后一下已按下，后一下不能丢。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1280dp-xhdpi")
class FastRepeatedTapTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val typed = StringBuilder()

    private fun show(schema: String = "", ascii: Boolean = false, width: Int = 360, height: Int = 240,
                     prefs: KeyboardInputPreferences = KeyboardInputPreferences()) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        val onKeyPress: (String) -> Unit = { key -> typed.append(key) }
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true,
                LocalKeyboardInputPreferences provides prefs) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
                        KeyboardLayout(onKeyPress, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onGlideWord = { _, _ -> }),
                            KeyboardUiState(currentSchemaId = schema, isAsciiMode = ascii), ascii,
                            Modifier.requiredSize(width.dp, height.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun point(letter: Char): Offset {
        val single = "qwerty-key:$letter"
        val tag = if (runCatching { rule.onNodeWithTag(single, true).fetchSemanticsNode() }.isSuccess) single
            else "qwerty-key:" + listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "zx", "cv", "bn").first { letter in it }
        val bounds = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
        val pair = tag.substringAfter(':')
        return if (pair.length == 2) Offset(bounds.left + bounds.width * (pair.indexOf(letter) * 2 + 1) / 4f, bounds.center.y)
            else bounds.center
    }

    /** 一组触摸一次注入，相邻事件隔 [gap] 毫秒，远短于长按。 */
    private fun touches(gap: Long = 40L, block: TouchInjectionScope.(step: () -> Unit) -> Unit) {
        rule.onRoot().performTouchInput { block { advanceEventTime(gap) } }
        rule.waitForIdle()
    }

    /** 两只拇指：右拇指 u，左拇指 u（右拇指还没抬），左拇指 r，右拇指 u。 */
    private fun alternatingThumbsUuru(expected: String) {
        val u = point('u'); val r = point('r')
        touches { step ->
            down(0, u); step()
            down(1, u); step()
            up(0); step()
            down(0, r); step()
            up(1); step()
            down(1, u); step()
            up(0); step()
            up(1)
        }
        assertEquals(expected, typed.toString())
    }

    @Test fun alternatingThumbs26() { show(); alternatingThumbsUuru("uuru") }

    @Test fun alternatingThumbsEnglishGlide() {
        show(ascii = true, prefs = KeyboardInputPreferences(glideTyping = true)); alternatingThumbsUuru("uuru")
    }

    /**
     * 用户 11-10：开着滑行快打吞键。手指在键上挪了一点（过了触摸阈值、没成滑行），这一下照常出字；
     * 两手交替时也一样（中文滑行走同一个按键手势）。
     */
    @Test fun driftingTapWithGlideStillTypes() {
        show(ascii = true, prefs = KeyboardInputPreferences(glideTyping = true))
        val g = point('g'); val h = point('h')
        // xhdpi：触摸阈值 8dp = 16px，这里挪 24px，还在 g 键里。
        touches { step -> down(0, g); step(); moveTo(0, g + Offset(24f, 6f)); step(); up(0) }
        assertEquals("g", typed.toString())
        touches { step -> down(0, h); step(); moveTo(0, h + Offset(-20f, -10f)); step(); down(1, g); step(); up(0); step(); up(1) }
        assertEquals("ghg", typed.toString())
    }

    /** 14 键点双字母键提交整键：四下都要到。 */
    @Test fun alternatingThumbs14() { show("pinyin_14jian"); alternatingThumbsUuru("uiuierui") }

    /** 后按下的手指先抬起：两下都算，顺序按按下的先后。 */
    @Test fun secondFingerLiftsFirst() {
        show()
        val u = point('u')
        touches { step -> down(0, u); step(); down(1, u); step(); up(1); step(); up(0) }
        assertEquals("uu", typed.toString())
    }

    /** 第一根手指一直没抬，同一键上又连点两下：三下都算，抬起那根旧手指不再多出一个。 */
    @Test fun oldFingerStillDownWhileTappingAgain() {
        show()
        val u = point('u')
        touches { step ->
            down(0, u); step()
            down(1, u); step(); up(1); step()
            down(2, u); step(); up(2); step()
            up(0)
        }
        assertEquals("uuu", typed.toString())
    }

    /** 同一根手指快速连点，每下照常一个。 */
    @Test fun oneFingerFastTaps() {
        show()
        val u = point('u'); val r = point('r')
        touches(gap = 20L) { step ->
            down(0, u); step(); up(0); step()
            down(0, u); step(); up(0); step()
            down(0, r); step(); up(0); step()
            down(0, u); step(); up(0)
        }
        assertEquals("uuru", typed.toString())
    }
}
