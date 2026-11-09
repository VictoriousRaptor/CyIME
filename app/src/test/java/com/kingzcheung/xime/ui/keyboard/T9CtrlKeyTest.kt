package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.annotation.Config

/**
 * 九键按住左下角 !@# 当 Ctrl（用户 11-03），和 26 键同一套：
 * 1 关闭 2 刷新 3 重做 4 全选 5 保存 6 剪切 7 撤销 8 复制 9 粘贴，回车 = Ctrl+回车（发送）。
 * 拖到数字键上松手或另一根手指点数字键都发快捷键、不打字；按满时限数字键换成快捷键键面；点一下 !@# 照旧开符号。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xhdpi")
class T9CtrlKeyTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()
    private val commands = mutableListOf<String>()
    private val controller = mock<T9InputController> {
        on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
    }

    /** 数字键键面上的字母，按 1..9 排。 */
    private val digitFaces = listOf("分词", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
    private val shortcutLabels = listOf("关闭", "刷新", "重做", "全选", "保存", "剪切", "撤销", "复制", "粘贴")
    private val shortcutCommands = listOf("ctrl:w", "ctrl:r", "ctrl:y", "ctrl:a", "ctrl:s", "ctrl:x", "ctrl:z", "ctrl:c", "ctrl:v")

    private fun show(cycle: Boolean = false) {
        AppFonts.initialize(rule.activity.application)
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(symbolKeyHoldSeconds = 1f, symbolKeyCycle = cycle)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 260.dp)) {
                        T9KeyboardLayout({ keys += it }, callbacks, KeyboardUiState(currentSchemaId = "t9_pinyin"),
                            controller, Color.DarkGray, Color.White, Color.Gray,
                            modifier = Modifier.requiredSize(360.dp, 260.dp),
                            onGestureAction = { action, value -> if (action == GestureAction.COMMAND) commands += value })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
    /** 数字键中心（在快捷键键面出来前取，出来后原键面透明但还在）。 */
    private fun digitCenter(digit: Int): Offset =
        rule.onNodeWithText(digitFaces[digit - 1], ignoreCase = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
    private fun labelShown(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun holdShowsShortcutFacesOnDigitKeys() {
        show()
        assertTrue(shortcutLabels.none(::labelShown))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(900)
        assertTrue("没按满时限不换键面", !labelShown("撤销"))
        rule.mainClock.advanceTimeBy(100 + CTRL_FACE_FADE_MS + 50L)
        for (label in shortcutLabels) assertTrue("按满时限显示 $label", labelShown(label))
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("松手恢复数字键面", shortcutLabels.none(::labelShown))
        assertEquals("超过时限松手不开符号", emptyList<String>(), keys)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun secondFingerTapOnEachDigitSendsItsShortcut() {
        show()
        val symbol = bounds("mode-slot-1").center
        val digits = (1..9).map(::digitCenter)
        rule.onRoot().performTouchInput { down(0, symbol) }
        rule.waitForIdle()
        for (point in digits) {
            rule.onRoot().performTouchInput { down(1, point); up(1) }
            rule.waitForIdle()
        }
        rule.onRoot().performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(shortcutCommands, commands)
        assertEquals("不打字也不开符号", emptyList<String>(), keys)
        verify(controller, never()).onDigitPressed(any())
    }

    @Test fun dragFromSymbolKeyToEachDigitSendsItsShortcut() {
        show()
        for (digit in 1..9) {
            val symbol = bounds("mode-slot-1")
            val goal = digitCenter(digit)
            rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
                down(center)
                val target = goal - symbol.topLeft
                for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
                up()
            }
            rule.waitForIdle()
        }
        assertEquals(shortcutCommands, commands)
        assertEquals(emptyList<String>(), keys)
        verify(controller, never()).onDigitPressed(any())
    }

    /** 从 !@# 拖到回车上松手：Ctrl+回车；连拖两次都一样。 */
    @Test fun dragToEnterSendsCtrlEnter() {
        show()
        repeat(2) {
            val symbol = bounds("mode-slot-1")
            val enter = bounds("t9-enter-key").center
            rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
                down(center)
                val target = enter - symbol.topLeft
                for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
                up()
            }
            rule.waitForIdle()
        }
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND, CtrlShortcuts.ENTER_COMMAND), commands)
        assertEquals("不打回车也不开符号", emptyList<String>(), keys)
    }

    @Test fun singleCycleKeyStillSupportsCtrlAndTapToSymbols() {
        show(cycle = true)
        val symbol = bounds("mode-slot-1").center
        val enter = bounds("t9-enter-key").center
        rule.onRoot().performTouchInput { down(0, symbol) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { down(1, enter); up(1) }
        rule.onRoot().performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND), commands)
        assertEquals(emptyList<String>(), keys)
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { click(center) }
        assertEquals(listOf("symbol"), keys)
    }

    /** 按住 !@#，另一根手指点回车：Ctrl+回车，不发普通回车，松开 !@# 也不开符号。 */
    @Test fun secondFingerTapOnEnterSendsCtrlEnter() {
        show()
        val symbol = bounds("mode-slot-1").center
        val enter = bounds("t9-enter-key").center
        rule.onRoot().performTouchInput { down(0, symbol) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { down(1, enter); up(1) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND), commands)
        assertEquals("不打回车也不开符号", emptyList<String>(), keys)
    }

    @Test fun releasingOffTheTargetsDoesNothing() {
        show()
        val symbol = bounds("mode-slot-1")
        val zero = bounds("t9-zero-key").center
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
            down(center)
            val target = zero - symbol.topLeft
            for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
            up()
        }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), commands)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun tapOpensSymbols() {
        show()
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("symbol"), keys)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun digitsAndEnterWorkNormallyWithoutHoldingTheSymbolKey() {
        show()
        val eight = digitCenter(8)
        rule.onRoot().performTouchInput { click(eight) }
        rule.waitForIdle()
        verify(controller).onDigitPressed("8")
        rule.onNodeWithTag("t9-enter-key", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("enter"), keys)
        assertEquals(emptyList<String>(), commands)
    }
}
