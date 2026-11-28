package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.ShiftMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 屏幕键盘的 Shift+回车（用户 11-04）：手指按住 Shift 点回车、或从 Shift 拖到回车上松手，发 Shift+回车命令（换行）；
 * 单次大写、大写锁定时点回车还是普通回车。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1280dp-xhdpi")
class ShiftEnterKeyTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()
    private val commands = mutableListOf<String>()
    private lateinit var vm: KeyboardViewModel

    private fun show(schema: String = "", enterKeyText: String = "回车") {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        vm = KeyboardViewModel(app)
        val onKeyPress: (String) -> Unit = { key ->
            when (key) {
                "shift" -> vm.toggleShift()
                "shift_single" -> vm.singleTapShift()
                "shift_caps" -> vm.doubleTapShift()
                else -> {
                    keys += key
                    vm.onCharacterTyped()
                }
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true) {
                MaterialTheme {
                    Box(Modifier.requiredSize(800.dp, 320.dp)) {
                        KeyboardLayout(onKeyPress, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onGestureAction = { action, value -> if (action == GestureAction.COMMAND) commands += value }),
                            KeyboardUiState(currentSchemaId = schema, enterKeyText = enterKeyText), false,
                            Modifier.requiredSize(800.dp, 320.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun enterIconShown(label: String) =
        rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun center(tag: String) = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot.center

    private fun holdShiftAndTapEnter() {
        val shift = center("shift-key")
        val enter = center("qwerty-enter-key")
        rule.onRoot().performTouchInput { down(0, shift) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { down(1, enter); up(1) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { up(0) }
        rule.waitForIdle()
    }

    @Test fun heldShiftPlusEnterIsNewline() {
        show()
        holdShiftAndTapEnter()
        assertEquals(listOf(CtrlShortcuts.SHIFT_ENTER_COMMAND), commands)
        assertEquals(emptyList<String>(), keys)
        // 用过的 Shift 松手不切成单次大写。
        assertEquals(ShiftMode.OFF, vm.shiftMode.value)
    }

    @Test fun heldShiftPlusEnterOn14Keys() {
        show("pinyin_14jian")
        holdShiftAndTapEnter()
        assertEquals(listOf(CtrlShortcuts.SHIFT_ENTER_COMMAND), commands)
    }

    @Test fun dragFromShiftOntoEnterIsNewline() {
        show()
        val shift = center("shift-key")
        val enter = center("qwerty-enter-key")
        rule.onRoot().performTouchInput {
            down(shift)
            for (step in 1..8) moveTo(shift + (enter - shift) * (step / 8f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.SHIFT_ENTER_COMMAND), commands)
        assertEquals(emptyList<String>(), keys)
    }

    /** 反例：单次大写（句首自动大写也是它）、大写锁定、不按 Shift 时，回车就是回车。 */
    @Test fun latchedShiftKeepsPlainEnter() {
        show()
        rule.onNodeWithTag("qwerty-enter-key", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.runOnIdle { vm.singleTapShift() }
        rule.onNodeWithTag("qwerty-enter-key", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.runOnIdle { vm.doubleTapShift() }
        rule.onNodeWithTag("qwerty-enter-key", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("enter", "enter", "enter"), keys)
        assertEquals(emptyList<String>(), commands)
    }

    /** 短按 Shift 切换大小写（未满 200ms）：发送/搜索等回车键图标不闪烁成默认回车。 */
    @Test fun quickShiftTapDoesNotFlickerEnterIcon() {
        show(enterKeyText = "发送")
        assertTrue(enterIconShown("发送"))
        val shift = center("shift-key")
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(0, shift) }
        rule.mainClock.advanceTimeBy(16)
        assertTrue(enterIconShown("发送"))
        assertFalse(enterIconShown("换行"))
        rule.mainClock.advanceTimeBy(150)
        assertTrue(enterIconShown("发送"))
        assertFalse(enterIconShown("换行"))
        rule.onRoot().performTouchInput { up(0) }
        rule.mainClock.advanceTimeBy(300)
        assertTrue(enterIconShown("发送"))
        assertFalse(enterIconShown("换行"))
        assertEquals(ShiftMode.SINGLE, vm.shiftMode.value)
    }

    /** 按住 Shift 超过 200ms 后回车图标切换为换行，松手立即恢复。 */
    @Test fun holdShiftPastDelayShowsNewlineIconAndReleaseRestores() {
        show(enterKeyText = "发送")
        val shift = center("shift-key")
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(0, shift) }
        rule.mainClock.advanceTimeBy(16)
        assertTrue(enterIconShown("发送"))
        rule.mainClock.advanceTimeBy(150)
        assertTrue(enterIconShown("发送"))
        assertFalse(enterIconShown("换行"))
        rule.mainClock.advanceTimeBy(64)
        assertTrue(enterIconShown("换行"))
        assertFalse(enterIconShown("发送"))
        rule.onRoot().performTouchInput { up(0) }
        rule.mainClock.advanceTimeBy(16)
        assertTrue(enterIconShown("发送"))
        assertFalse(enterIconShown("换行"))
    }

    /** 按住 Shift 不满 200ms 时另一指立即点回车，图标虽未切但仍立刻触发 Shift+回车换行。 */
    @Test fun immediateShiftPlusEnterStillSendsNewlineBeforeIconSwitches() {
        show(enterKeyText = "发送")
        val shift = center("shift-key")
        val enter = center("qwerty-enter-key")
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(0, shift) }
        rule.mainClock.advanceTimeBy(50)
        assertTrue(enterIconShown("发送"))
        rule.onRoot().performTouchInput { down(1, enter); up(1) }
        rule.mainClock.advanceTimeBy(16)
        rule.onRoot().performTouchInput { up(0) }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(listOf(CtrlShortcuts.SHIFT_ENTER_COMMAND), commands)
        assertEquals(emptyList<String>(), keys)
        assertEquals(ShiftMode.OFF, vm.shiftMode.value)
    }
}
