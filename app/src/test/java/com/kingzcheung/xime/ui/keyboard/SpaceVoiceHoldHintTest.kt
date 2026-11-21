package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 长按空格说话：真开了麦，空格上就写「正在听…」并带麦克风；没开起来（总开关关着）就不假装在听。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpaceVoiceHoldHintTest {
    @get:Rule val rule = createComposeRule()

    private var listening by mutableStateOf(false)

    private fun setKey(micStarts: Boolean, swipeCancel: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE,
                    cursorGesture = CursorGestureMode.KEYBOARD, voiceSwipeCancel = swipeCancel),
                LocalKeyboardInputActions provides KeyboardInputActions(
                    onVoiceToggle = { if (micStarts) listening = true },
                    onVoiceHoldEnd = { listening = false },
                    isVoiceMode = listening, voiceSticky = listening),
            ) {
                Box(Modifier.size(240.dp, 64.dp)) {
                    SpaceKeyButton(onClick = {}, backgroundColor = Color.White, textColor = Color.Black,
                        modifier = Modifier.testTag("space"))
                }
            }
        }
    }

    /** 某个标签的节点上写着什么（没有这个节点就是空表）；不对时把整棵树带进失败信息，电脑上一眼看出键面是什么样。 */
    private fun texts(tag: String): List<String> = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }

    private fun tree(): String = rule.onRoot(useUnmergedTree = true).printToString()

    /**
     * 松手、滑回来这类「退回去」的变化：放开时钟，等手势协程跑完、动画走完再看（时钟停着时只推 500 ms，
     * 电脑上 11-20 两条在这里没等到；放开时钟后和别的键盘测试一样用 waitForIdle 等）。
     */
    private fun settle() {
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    @Test fun `holding space to talk shows that the microphone is listening until release`() {
        setKey(micStarts = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag("space-voice-hold-hint").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(500L)
        rule.onNodeWithTag("space-voice-hold-hint").assertTextEquals("正在听…")
        rule.onNodeWithTag("space-voice-hold-mic").assertExists()
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        // 松手就结束这次听写（onVoiceHoldEnd），键面回到空格。
        assertFalse("松手后还在听\n${tree()}", listening)
        assertEquals(tree(), emptyList<String>(), texts("space-voice-hold-hint"))
        assertEquals(tree(), 0, rule.onAllNodesWithTag("space-voice-hold-mic", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `no listening look when the microphone did not start`() {
        setKey(micStarts = false)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        rule.onNodeWithTag("space-voice-hold-mic").assertDoesNotExist()
        rule.onNodeWithTag("space").performTouchInput { up() }
    }

    @Test fun `swipe-to-cancel keeps its hint next to the microphone`() {
        setKey(micStarts = true, swipeCancel = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("上滑取消")
        rule.onNodeWithTag("space-voice-hold-mic").assertExists()
        rule.onNodeWithTag("space").performTouchInput { up() }
    }

    @Test fun `sliding up warns gradually and only says release-to-cancel past the line`() {
        setKey(micStarts = true, swipeCancel = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        // 往上走了半个键高：删除色只是透出来，字还是「上滑取消」。
        rule.onNodeWithTag("space").performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(centerX, -height * 0.5f)) }
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("上滑取消")
        // 过了取消线：「松手取消」。
        rule.onNodeWithTag("space").performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(centerX, -height * 1.5f)) }
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("松手取消")
        // 滑回来：恢复，松手照常上屏。
        rule.onNodeWithTag("space").performTouchInput { moveTo(center) }
        settle()
        assertEquals(tree(), listOf("上滑取消"), texts("space-voice-cancel-hint"))
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertFalse("松手后还在听\n${tree()}", listening)
    }
    // ---- 滑到右上角锁定（SpaceVoiceLock） ----
    private var cancelled = false
    private val lock = SpaceVoiceLockState()

    /** 上面一条 100dp 高的「候选栏」，下面是空格；锁定区是候选栏右端一格。 */
    private fun setKeyWithBar(swipeCancel: Boolean) {
        rule.setContent {
            CompositionLocalProvider(
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE,
                    cursorGesture = CursorGestureMode.KEYBOARD, voiceSwipeCancel = swipeCancel),
                LocalKeyboardInputActions provides KeyboardInputActions(
                    onVoiceToggle = { listening = !listening },
                    onVoiceHoldEnd = { listening = false },
                    onVoiceHoldCancel = { listening = false; cancelled = true },
                    isVoiceMode = listening, voiceSticky = listening),
                LocalSpaceVoiceLock provides lock,
            ) {
                androidx.compose.foundation.layout.Column {
                    Box(Modifier.size(240.dp, 100.dp).spaceVoiceLockTarget(lock, Color.Blue).testTag("bar"))
                    Box(Modifier.size(240.dp, 64.dp)) {
                        SpaceKeyButton(onClick = {}, backgroundColor = Color.White, textColor = Color.Black,
                            modifier = Modifier.testTag("space"))
                    }
                }
            }
        }
    }

    /** 空格上写的提示，不管挂在哪个标签上（开了上滑取消时挂在 cancel-hint 上）。 */
    private fun hint(): List<String> = texts("space-voice-hold-hint") + texts("space-voice-cancel-hint")

    private fun state(): String = "listening=$listening cancelled=$cancelled armed=${lock.armed} locked=${lock.locked} " +
        "holding=${lock.holding} bar=${lock.bar}\n${tree()}"

    /**
     * 按住空格到开麦，再分几步滑到 [to]（空格自己的坐标），停稳再看。
     * 电脑上 11-21：时钟停着时一步跳过去，提示没跟上；改成先挪一点、再半路、再到位，最后放开时钟等手势和重组跑完。
     */
    private fun holdAndSlide(to: androidx.compose.ui.test.TouchInjectionScope.() -> androidx.compose.ui.geometry.Offset) {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        assertTrue("space hold did not start the microphone\n${state()}", listening)
        for (f in listOf(0.05f, 0.5f, 1f)) {
            rule.onNodeWithTag("space").performTouchInput { moveTo(center + (to() - center) * f) }
            rule.mainClock.advanceTimeBy(50L)
        }
        settle()
    }

    @Test fun `sliding to the top-right corner and letting go keeps listening until space is tapped`() {
        setKeyWithBar(swipeCancel = false)
        holdAndSlide { androidx.compose.ui.geometry.Offset(width - 4f, -height * 0.4f) }
        assertEquals(state(), listOf("松手锁定"), hint())
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertTrue("letting go in the corner must not end voice\n${state()}", listening && lock.locked)
        assertEquals(state(), listOf("点按结束"), hint())
        assertEquals(state(), 1, rule.onAllNodesWithTag("space-voice-lock-icon", useUnmergedTree = true).fetchSemanticsNodes().size)
        // 点一下空格：结束并上屏，键面恢复。
        rule.onNodeWithTag("space").performTouchInput { down(center); up() }
        settle()
        assertFalse("tapping space ends locked voice\n${state()}", listening || lock.locked)
        assertEquals(state(), emptyList<String>(), hint())
    }

    @Test fun `the corner wins over swipe-up cancel even above the cancel line`() {
        setKeyWithBar(swipeCancel = true)
        // 高过取消线（空格顶边以上一个键高），但在右上角那一格：锁定，不取消。
        holdAndSlide { androidx.compose.ui.geometry.Offset(width - 4f, -height * 1.3f) }
        assertEquals(state(), listOf("松手锁定"), hint())
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertTrue("corner locks instead of cancelling\n${state()}", listening && lock.locked && !cancelled)
    }

    @Test fun `straight up past the line still cancels`() {
        setKeyWithBar(swipeCancel = true)
        holdAndSlide { androidx.compose.ui.geometry.Offset(centerX, -height * 1.3f) }
        assertEquals(state(), listOf("松手取消"), hint())
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertTrue("straight up past the line cancels\n${state()}", cancelled && !listening && !lock.locked)
    }
}
