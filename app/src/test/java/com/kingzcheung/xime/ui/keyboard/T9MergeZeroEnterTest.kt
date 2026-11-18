package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.settings.t9MergeZeroSubtitle
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 「去掉 0 键，回车加高」（用户 11-15，默认关）：
 * 关着时右边一列照旧是 删除 / 重输 / 0 / 回车；开了只剩 删除 / 重输 / 占两格的回车，
 * 回车点按照旧（含 Ctrl+回车），往角标方向滑打 0；打字选词时、关了「上下滑输入符号」时滑了不打 0。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xhdpi")
class T9MergeZeroEnterTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()
    private val commands = mutableListOf<String>()
    private val controller = mock<T9InputController> {
        on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
    }
    private val candidate = mutableStateOf(CandidateState())

    private fun show(prefs: KeyboardInputPreferences) {
        AppFonts.initialize(rule.activity.application)
        KeysConfigHelper.loadConfig(rule.activity.application)
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides prefs) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 260.dp)) {
                        T9KeyboardLayout({ keys += it }, callbacks, KeyboardUiState(currentSchemaId = "t9_pinyin"),
                            controller, Color.DarkGray, Color.White, Color.Gray,
                            modifier = Modifier.requiredSize(360.dp, 260.dp), candidateState = candidate,
                            onGestureAction = { action, value -> if (action == GestureAction.COMMAND) commands += value })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()

    /** 在回车上往下拉（默认方向下拉打上方角标，和 1～9 的数字一样）。 */
    private fun pullEnter(down: Boolean = true) {
        rule.onNodeWithTag("t9-enter-key", true).performTouchInput {
            down(center)
            val dy = (if (down) 1f else -1f) * 12.dp.toPx()
            for (step in 1..8) moveTo(center + Offset(0f, dy * step))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun defaultKeepsZeroKeyAndSameSizeEnter() {
        show(KeyboardInputPreferences())
        assertTrue("默认还有 0 键", exists("t9-zero-key"))
        val enter = bounds("t9-enter-key")
        val delete = bounds("t9-delete-key")
        assertTrue("默认回车和删除一样高", abs(enter.height - delete.height) < 2f)
    }

    @Test fun mergedRemovesZeroKeyAndEnterTakesTwoRows() {
        show(KeyboardInputPreferences(t9MergeZeroIntoEnter = true))
        assertFalse("开了没有 0 键", exists("t9-zero-key"))
        val enter = bounds("t9-enter-key")
        val delete = bounds("t9-delete-key")
        val rail = bounds("t9-right-rail")
        assertTrue("回车约两个删除键高：${enter.height} vs ${delete.height}", abs(enter.height - delete.height * 2) < 4f)
        assertTrue("回车到底", abs(enter.bottom - rail.bottom) < 2f)
    }

    @Test fun mergedEnterTapIsEnterAndPullTypesZero() {
        show(KeyboardInputPreferences(t9MergeZeroIntoEnter = true))
        rule.onNodeWithTag("t9-enter-key", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("enter"), keys)
        keys.clear()
        pullEnter()
        assertEquals("下拉打 0", listOf("0"), keys)
    }

    @Test fun reversedDirectionTypesZeroOnPushUp() {
        show(KeyboardInputPreferences(t9MergeZeroIntoEnter = true, reverseSymbolSwipe = true))
        pullEnter(down = false)
        assertEquals("反转后上滑打 0", listOf("0"), keys)
    }

    @Test fun noZeroWhileChoosingCandidates() {
        candidate.value = CandidateState(candidates = listOf("你好"), inputText = "64", preeditText = "ni", isComposing = true)
        show(KeyboardInputPreferences(t9MergeZeroIntoEnter = true))
        pullEnter()
        assertTrue("打字选词时滑了不打 0：$keys", "0" !in keys)
    }

    @Test fun noZeroWhenCornerSymbolInputOff() {
        show(KeyboardInputPreferences(t9MergeZeroIntoEnter = true, cornerSymbolInput = false))
        pullEnter()
        assertTrue("关了上下滑输入符号不打 0：$keys", "0" !in keys)
    }

    /** 大回车照旧是 Ctrl+回车的目标：从 !@# 拖到回车上松手发送。 */
    @Test fun mergedEnterStillTakesCtrlEnter() {
        show(KeyboardInputPreferences(t9MergeZeroIntoEnter = true, symbolKeyHoldSeconds = 1f))
        val symbol = bounds("mode-slot-1")
        val enter = bounds("t9-enter-key").center
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
            down(center)
            val target = enter - symbol.topLeft
            for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND), commands)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun settingsSubtitleSaysWhatIsLostAndWhereZeroIs() {
        val normal = t9MergeZeroSubtitle(cornerSymbolInput = true, reverseSymbolSwipe = false)
        assertTrue(normal.contains("没有「下一项」键"))
        assertTrue(normal.contains("长按换英文、拖到候选上屏英文也用不了"))
        assertTrue(normal.contains("数字 0 在回车上下滑打"))
        assertTrue(t9MergeZeroSubtitle(true, true).contains("数字 0 在回车上上滑打"))
        assertTrue(t9MergeZeroSubtitle(false, false).contains("回车上不能打 0，到数字键盘里打"))
    }
}
