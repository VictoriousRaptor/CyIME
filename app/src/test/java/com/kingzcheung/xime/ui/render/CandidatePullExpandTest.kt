package com.kingzcheung.xime.ui.render

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 候选栏一行不滚动（用户 11-20）：往右拉过门槛松手才展开，拉得不够弹回，拉完照样能点候选；
 * 没有更多候选时拉不开。展开着往左拉收回；没展开往左、展开着往右都不动（用户 11-21）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-hdpi")
class CandidatePullExpandTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray)

    @Test fun rightPullOpensLeftPullCloses() {
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
        // 没展开往左拉：不展开，也不把手指下的候选当成点了。
        pull.pullAcross(leftward = true)
        rule.runOnIdle { assertEquals(0, opens); assertEquals(1, picked) }
        pull.pullAcross()
        rule.runOnIdle { assertEquals(1, opens) }
        // 展开着往右拉：不再展开一次，也不收回。
        pull.pullAcross()
        rule.runOnIdle { assertEquals(1, opens); assertEquals(0, closes) }
        // 展开着往左拉：收回，打的字还在（栏里还是这两个候选）。
        pull.pullAcross(leftward = true)
        rule.runOnIdle { assertEquals(1, closes); assertEquals(false, expanded.value); assertEquals(1, picked) }
        rule.onNodeWithTag("bar-candidate:1", useUnmergedTree = true).assertExists()
        pull.pullAcross()
        rule.runOnIdle { assertEquals(2, opens) }
        // 只有一个候选、没有下一页：拉不开。
        rule.runOnIdle { expanded.value = false; state.value = CandidateBarState.ChineseCandidates(listOf("你"), inputText = "ni") }
        pull.pullAcross()
        rule.runOnIdle { assertEquals(2, opens) }
    }
}
