package com.kingzcheung.xime.ui.render

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 候选栏一行不滚动（用户 11-20）：往左拉过门槛松手才展开，拉得不够弹回，拉完照样能点候选；
 * 没有更多候选时拉不开。展开着往右拉收回；没展开往右、展开着往左都不动（11-21，同合并前往左拉展开）。
 * 这些手势只在设置「候选滑到头回弹展开」打开时有，默认关，关着时和 1.15.101 一样只有 ▾/▴（11-21 他：「谁让你直接生效了」）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-hdpi")
class CandidatePullExpandTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray)
    private val prefs get() = SettingsPreferences.getPrefsPublic(ApplicationProvider.getApplicationContext())

    @Before fun swipeExpandOn() { SettingsPreferences.setCandidateSwipeExpand(ApplicationProvider.getApplicationContext(), true) }

    @After fun resetSwipeExpand() {
        prefs.edit().remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND).remove(SettingsPreferences.KEY_CANDIDATE_CLOSE_ON_RIGHT)
            .remove(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON).commit()
    }

    /** 「经典」开了左右滑：右边 ▾ 照常展开、▴ 照常收回，往左拉也能展开、往右划也能收回（11-23）。 */
    @Test fun classicWithSwipeHasBothArrowAndPull() {
        val expanded = mutableStateOf(false)
        rule.setContent {
            CandidateBar(state = CandidateBarState.ChineseCandidates(listOf("你好", "你"), inputText = "nihao", hasMore = true),
                candidatePageExpanded = expanded.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {},
                    onShowMoreCandidates = { expanded.value = true },
                    onCollapseCandidates = { expanded.value = false },
                    onBack = { expanded.value = false }, onHideKeyboard = {}),
                showPreeditPreview = false, modifier = Modifier.width(360.dp))
        }
        rule.onNodeWithTag("candidate-expansion").performClick()
        rule.runOnIdle { assertEquals(true, expanded.value) }
        rule.onNodeWithTag("candidate-expansion").performClick()
        rule.runOnIdle { assertEquals(false, expanded.value) }
        rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true).pullAcross()
        rule.runOnIdle { assertEquals(true, expanded.value) }
        rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true).pullAcross(leftward = false)
        rule.runOnIdle { assertEquals(false, expanded.value) }
    }

    /** 默认关：往左拉、往右拉都不展开不收回，▾ 照常展开、▴ 照常收回（和 1.15.101 一样）。 */
    @Test fun swipeExpandIsOffByDefault() {
        prefs.edit().remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND).commit()
        assertEquals(false, SettingsPreferences.isCandidateSwipeExpand(ApplicationProvider.getApplicationContext()))
        val expanded = mutableStateOf(false)
        var opens = 0
        var closes = 0
        var backs = 0
        rule.setContent {
            CandidateBar(state = CandidateBarState.ChineseCandidates(listOf("你好", "你"), inputText = "nihao", hasMore = true),
                candidatePageExpanded = expanded.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {},
                    onShowMoreCandidates = { opens++; expanded.value = true },
                    onCollapseCandidates = { closes++; expanded.value = false },
                    onBack = { backs++; expanded.value = false }),
                showPreeditPreview = false, modifier = Modifier.width(360.dp))
        }
        val pull = rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true)
        pull.pullAcross()
        rule.runOnIdle { assertEquals(0, opens) }
        rule.onNodeWithTag("candidate-expansion").performClick()
        rule.runOnIdle { assertEquals(1, opens); assertEquals(true, expanded.value) }
        pull.pullAcross(leftward = false)
        rule.runOnIdle { assertEquals(0, closes); assertEquals(true, expanded.value) }
        rule.onNodeWithTag("candidate-expansion").performClick()
        rule.runOnIdle { assertEquals(1, backs); assertEquals(false, expanded.value) }
    }

    /** 「右边一处关」：右边不放 ▾/▴，打字、展开着右边都是关（这里没有取消键，就是收起键盘），展开收起靠滑。 */
    @Test fun rightCloseModeHasNoArrow() {
        SettingsPreferences.setCandidateCloseOnRight(ApplicationProvider.getApplicationContext(), true)
        val expanded = mutableStateOf(false)
        var hides = 0
        rule.setContent {
            CandidateBar(state = CandidateBarState.ChineseCandidates(listOf("你好", "你"), inputText = "nihao", hasMore = true),
                candidatePageExpanded = expanded.value, visuals = visuals,
                // 没有取消回调的宿主：右边那一格直接是收起键盘。
                callbacks = CandidateBarCallbacks(onCandidateSelect = {},
                    onShowMoreCandidates = { expanded.value = true },
                    onCollapseCandidates = { expanded.value = false },
                    onBack = { expanded.value = false }, onHideKeyboard = { hides++ }),
                showPreeditPreview = false, modifier = Modifier.width(360.dp))
        }
        rule.onNodeWithTag("candidate-expansion", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("candidate-hide").performClick()
        rule.runOnIdle { assertEquals(1, hides); assertEquals(false, expanded.value) }
        rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true).pullAcross()
        rule.runOnIdle { assertEquals(true, expanded.value) }
        rule.onNodeWithTag("candidate-expansion", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("candidate-hide").performClick()
        rule.runOnIdle { assertEquals(2, hides) }
        rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true).pullAcross(leftward = false)
        rule.runOnIdle { assertEquals(false, expanded.value) }
    }

    /** 11-24 他：「改成两边滑都可以展开收起」：往左、往右拉过门槛都展开；展开着往左、往右拉都收回。 */
    @Test fun eitherWayOpensAndEitherWayCloses() {
        val state = mutableStateOf<CandidateBarState>(
            CandidateBarState.ChineseCandidates(listOf("你好", "你"), inputText = "nihao", hasMore = true))
        val expanded = mutableStateOf(false)
        var opens = 0
        var closes = 0
        var picked = -1
        rule.setContent {
            CandidateBar(state = state.value, candidatePageExpanded = expanded.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = { picked = it },
                    onShowMoreCandidates = { opens++; expanded.value = true },
                    onCollapseCandidates = { closes++; expanded.value = false }),
                showPreeditPreview = false, modifier = Modifier.width(360.dp))
        }
        val pull = rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true)
        // 一共只拉了 10%：不展开，手指一松回原位，马上点候选照样选上。
        pull.pullAcross(fraction = 0.1f)
        rule.onNodeWithTag("bar-candidate:1", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(0, opens); assertEquals(1, picked) }
        // 往右拉：展开，不把手指下的候选当成点了。
        pull.pullAcross(leftward = false)
        rule.runOnIdle { assertEquals(1, opens); assertEquals(1, picked) }
        // 展开着再往右拉：收回，打的字还在（栏里还是这两个候选）。
        pull.pullAcross(leftward = false)
        rule.runOnIdle { assertEquals(1, closes); assertEquals(false, expanded.value); assertEquals(1, picked) }
        rule.onNodeWithTag("bar-candidate:1", useUnmergedTree = true).assertExists()
        // 往左拉：展开；展开着往左拉：也收回，不会再展开一次。
        pull.pullAcross()
        rule.runOnIdle { assertEquals(2, opens) }
        pull.pullAcross()
        rule.runOnIdle { assertEquals(2, opens); assertEquals(2, closes); assertEquals(false, expanded.value) }
        // 只有一个候选、没有下一页：哪边都拉不开。
        rule.runOnIdle { state.value = CandidateBarState.ChineseCandidates(listOf("你"), inputText = "ni") }
        pull.pullAcross()
        pull.pullAcross(leftward = false)
        rule.runOnIdle { assertEquals(2, opens) }
    }

    /**
     * 候选多、能左右滑时（真机上打字几乎都是这样）：滑到一屏的尾巴再往左拉就展开，尾巴不会越滑越远
     * （11-22 他在手机上开着开关：「展不开」）；滑一下也不收起展开页，收回靠拉。
     * 一屏没排满时会接上展开列表（见 CandidateFirstScreenWideTest），这里用的是默认的假字宽，排没排满量不准，不数接了几次。
     */
    @Test fun manyCandidatesReachTheEndAndExpand() {
        val many = (1..12).map { "候选$it" }
        val expanded = mutableStateOf(false)
        var opens = 0
        var scrolled = 0
        rule.setContent {
            CandidateBar(state = CandidateBarState.ChineseCandidates(many, inputText = "houxuan", hasMore = true),
                candidatePageExpanded = expanded.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {},
                    onShowMoreCandidates = { opens++; expanded.value = true },
                    onCollapseCandidates = { expanded.value = false },
                    onCandidateStripScrolled = { scrolled++ },
                    onCandidateStripNearEnd = {}),
                showPreeditPreview = false, modifier = Modifier.width(360.dp))
        }
        val pull = rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true)
        repeat(6) { if (opens == 0) pull.pullAcross() }
        rule.runOnIdle {
            assertEquals("滑到头再拉就展开", 1, opens)
            assertEquals("开关开着滑一下不收起展开页", 0, scrolled)
        }
    }

    /** 「<」和第一个候选之间那 4dp 空白也在拉动区里（11-21 平板上按在空白处拉不动）：从那里往右拉收回展开页。 */
    @Test fun gapRightOfCancelButtonAlsoPulls() {
        val expanded = mutableStateOf(true)
        var closes = 0
        var cancels = 0
        rule.setContent {
            CandidateBar(state = CandidateBarState.ChineseCandidates(listOf("你好", "你"), inputText = "nihao", hasMore = true),
                candidatePageExpanded = expanded.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = { cancels++ },
                    onShowMoreCandidates = { expanded.value = true },
                    onCollapseCandidates = { closes++; expanded.value = false }),
                showPreeditPreview = false, modifier = Modifier.width(360.dp))
        }
        val cancel = rule.onNodeWithContentDescription("取消输入").fetchSemanticsNode().boundsInRoot
        val pullNode = rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true)
        val pull = pullNode.fetchSemanticsNode().boundsInRoot
        val dp = rule.density.density
        assertTrue("拉动区从「<」右边紧接着开始：${cancel.right} / ${pull.left}", pull.left <= cancel.right + 1f)
        val startX = (cancel.right + 2f * dp) - pull.left
        pullNode.performTouchInput { swipe(Offset(startX, centerY), Offset(startX + 250f * dp, centerY), durationMillis = 300) }
        rule.runOnIdle { assertEquals(1, closes); assertEquals(0, cancels) }
    }
}
