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

    /** 240×164dp 的「键区」，空格在最下面 64dp；锁按钮那一层铺满键区，两把锁在左右上角、取消按钮在中间。 */
    private fun setKeyWithBar(swipeCancel: Boolean, micStarts: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE,
                    cursorGesture = CursorGestureMode.KEYBOARD, voiceSwipeCancel = swipeCancel),
                LocalKeyboardInputActions provides KeyboardInputActions(
                    onVoiceToggle = { if (micStarts) listening = !listening },
                    onVoiceHoldEnd = { listening = false },
                    onVoiceHoldCancel = { listening = false; cancelled = true },
                    isVoiceMode = listening, voiceSticky = listening),
                LocalSpaceVoiceLock provides lock,
            ) {
                Box(Modifier.size(240.dp, 164.dp)) {
                    androidx.compose.foundation.layout.Column {
                        Box(Modifier.size(240.dp, 100.dp).testTag("keys"))
                        Box(Modifier.size(240.dp, 64.dp)) {
                            SpaceKeyButton(onClick = {}, backgroundColor = Color.White, textColor = Color.Black,
                                modifier = Modifier.testTag("space"))
                        }
                    }
                    SpaceVoiceLockLayer(lock, Color.Blue, Color.White, Modifier.matchParentSize())
                }
            }
        }
    }

    /** 空格上写的提示，不管挂在哪个标签上（开了上滑取消时挂在 cancel-hint 上）。 */
    private fun hint(): List<String> = texts("space-voice-hold-hint") + texts("space-voice-cancel-hint")

    private fun state(): String = "listening=$listening cancelled=$cancelled armed=${lock.armed} side=${lock.side} locked=${lock.locked} " +
        "holding=${lock.holding} host=${lock.host} space=${lock.space}\n${tree()}"

    /**
     * 按住空格到开麦，再分几步滑到 [to]（空格自己的坐标），停稳再看。
     * 电脑上 11-21：时钟停着时一步跳过去，提示没跟上；改成先挪一点、再半路、再到位，最后放开时钟等手势和重组跑完。
     */
    private fun holdAndSlide(expectMic: Boolean = true, to: androidx.compose.ui.test.TouchInjectionScope.() -> androidx.compose.ui.geometry.Offset) {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        assertTrue("space hold did not start the microphone\n${state()}", listening || !expectMic)
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
        // 按住说话时锁按钮浮出来，松手后收起（锁定后空格自己带锁）。
        assertEquals(state(), 1, rule.onAllNodesWithTag("space-voice-lock-button", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("the button looks armed, so letting go must lock\n${state()}", lock.lockReady)
        // 松手那一刻就锁上，外框闪一下（停住时钟看闪的那一下）。
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { up() }
        // 时钟停着时手势里改的状态要自己通知，下一帧才会重组（自动推进时由测试规则代办）。
        rule.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(100L)
        assertTrue("locked the moment the finger lifted\n${state()}", listening && lock.locked)
        assertEquals(state(), 1, rule.onAllNodesWithTag("space-voice-lock-flash", useUnmergedTree = true).fetchSemanticsNodes().size)
        settle()
        assertEquals(state(), 0, rule.onAllNodesWithTag("space-voice-lock-flash", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("letting go in the corner must not end voice\n${state()}", listening && lock.locked)
        assertEquals(state(), 0, rule.onAllNodesWithTag("space-voice-lock-button", useUnmergedTree = true).fetchSemanticsNodes().size)
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

    // 用户 11-22：右上一把锁不对称，左右各一把、中间取消。
    @Test fun `sliding to the left lock locks just like the right one`() {
        setKeyWithBar(swipeCancel = true)
        holdAndSlide { androidx.compose.ui.geometry.Offset(4f, -height * 0.4f) }
        assertEquals(state(), listOf("松手锁定"), hint())
        assertEquals(state(), -1, lock.side)
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertTrue("left lock locks\n${state()}", listening && lock.locked && !cancelled)
        assertEquals(state(), -1, lock.lockedSide)
        rule.onNodeWithTag("space").performTouchInput { down(center); up() }
        settle()
        assertFalse("tapping space ends locked voice\n${state()}", listening || lock.locked)
    }

    @Test fun `the cancel button floats up between the locks only with swipe-up cancel on`() {
        setKeyWithBar(swipeCancel = true)
        holdAndSlide { androidx.compose.ui.geometry.Offset(centerX, -height * 0.1f) }
        assertEquals(state(), 1, rule.onAllNodesWithTag("space-voice-cancel-button", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(state(), 1, rule.onAllNodesWithTag("space-voice-lock-button", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertTrue("letting go below the line just ends\n${state()}", !listening && !cancelled && !lock.locked)
        assertEquals(state(), 0, rule.onAllNodesWithTag("space-voice-cancel-button", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `without swipe-up cancel there are only the two locks`() {
        setKeyWithBar(swipeCancel = false)
        holdAndSlide { androidx.compose.ui.geometry.Offset(centerX, -height * 1.3f) }
        assertEquals(state(), 0, rule.onAllNodesWithTag("space-voice-cancel-button", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(state(), 1, rule.onAllNodesWithTag("space-voice-lock-button", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertTrue("the middle without cancel just ends\n${state()}", !listening && !cancelled && !lock.locked)
    }

    @Test fun `no lock button and no lock when the microphone did not start`() {
        setKeyWithBar(swipeCancel = false, micStarts = false)
        holdAndSlide(expectMic = false) { androidx.compose.ui.geometry.Offset(width - 4f, -height * 0.4f) }
        assertEquals(state(), 0, rule.onAllNodesWithTag("space-voice-lock-button", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(state(), lock.lockReady)
        rule.onNodeWithTag("space").performTouchInput { up() }
        settle()
        assertFalse("nothing to lock\n${state()}", lock.locked || listening)
    }
}
