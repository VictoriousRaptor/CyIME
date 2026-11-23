package com.kingzcheung.xime.ui.render

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.LocalSplitKeyboardGap
import com.kingzcheung.xime.ui.keyboard.SplitKeyboardGap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 平板上开着「候选滑到头回弹展开」（11-22 他：「平板上我划不到头呢」）：候选栏宽、引擎一页 20 个候选带拼音，
 * 横屏竖屏都可能是分体键盘（候选栏也分到中缝两边）。照手指真实划法（一下一下往左划）要能滑到头、再拉就展开。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1000dp-h700dp-xhdpi")
class CandidatePullTabletTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray)
    private val prefs get() = SettingsPreferences.getPrefsPublic(ApplicationProvider.getApplicationContext())
    private val words = listOf("你好", "你", "拟好", "泥", "呢", "尼", "妮", "倪", "逆", "腻", "匿", "溺", "拟", "昵", "霓",
        "你们好", "你号", "尼豪", "泥浩", "你好啊")
    private val pinyin = words.map { "ni hao" }

    @Before fun swipeExpandOn() { SettingsPreferences.setCandidateSwipeExpand(ApplicationProvider.getApplicationContext(), true) }

    @After fun reset() { prefs.edit().remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND).commit() }

    private fun setBar(gap: SplitKeyboardGap?, opens: () -> Unit) {
        val expanded = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalSplitKeyboardGap provides gap) {
                CandidateBar(state = CandidateBarState.ChineseCandidates(words, pinyin, inputText = "nihao", hasMore = true),
                    candidatePageExpanded = expanded.value, visuals = visuals,
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {},
                        onShowMoreCandidates = { opens(); expanded.value = true },
                        onCollapseCandidates = { expanded.value = false }),
                    showPreeditPreview = false, modifier = Modifier.width(1000.dp))
            }
        }
    }

    /** 手指往左划一下：从 [from] 到 [to]（拉动区宽度的比例），[ms] 毫秒。 */
    private fun swipeLeft(from: Float = 0.9f, to: Float = 0.45f, ms: Long = 150) {
        rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width * from, centerY), Offset(width * to, centerY), durationMillis = ms)
        }
        rule.waitForIdle()
    }

    /** 不分体：一下一下快划，划到头以后再划一下就展开（十下以内）。 */
    @Test fun wideBarFlingsToTheEndThenPullExpands() {
        var opens = 0
        setBar(null) { opens++ }
        var swipes = 0
        while (opens == 0 && swipes < 10) { swipeLeft(); swipes++ }
        rule.runOnIdle { assertEquals("划了 $swipes 下还没展开", 1, opens) }
    }

    /** 不分体：慢慢拖（不甩），拖到头再拉过门槛也展开。 */
    @Test fun wideBarSlowDragsToTheEndThenPullExpands() {
        var opens = 0
        setBar(null) { opens++ }
        var swipes = 0
        while (opens == 0 && swipes < 10) { swipeLeft(ms = 900); swipes++ }
        rule.runOnIdle { assertEquals("划了 $swipes 下还没展开", 1, opens) }
    }

    private fun splitGap(): SplitKeyboardGap {
        val px = rule.density.density
        return SplitKeyboardGap().apply { reportRow(0, Rect(450f * px, 300f * px, 550f * px, 360f * px)) }
    }

    /** 分体：候选栏分到中缝两边，在右半边往左拉一下就展开。 */
    @Test fun splitBarPullOnRightHalfExpands() {
        var opens = 0
        setBar(splitGap()) { opens++ }
        rule.waitForIdle()
        var swipes = 0
        while (opens == 0 && swipes < 10) { swipeLeft(from = 0.95f, to = 0.6f); swipes++ }
        rule.runOnIdle { assertEquals("划了 $swipes 下还没展开", 1, opens) }
    }

    /** 分体：在左半边往左拉一下也展开。 */
    @Test fun splitBarPullOnLeftHalfExpands() {
        var opens = 0
        setBar(splitGap()) { opens++ }
        rule.waitForIdle()
        var swipes = 0
        while (opens == 0 && swipes < 10) { swipeLeft(from = 0.4f, to = 0.05f); swipes++ }
        rule.runOnIdle { assertEquals("划了 $swipes 下还没展开", 1, opens) }
    }
}
