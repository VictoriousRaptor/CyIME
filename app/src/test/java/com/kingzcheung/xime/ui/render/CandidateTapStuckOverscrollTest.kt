package com.kingzcheung.xime.ui.render

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 用户录屏（11-05，九键 wang'zi）：打着字候选突然点不上，怎么点都不上屏；每点一下，栏尾被藏起来的半截候选
 * 按下时露出来、抬手又消失；把候选栏横滑一下才恢复。
 *
 * 原因：横滑列表在边缘拉伸回弹还没收完时，会把按下直接当成「接着滑」吞掉（Compose 的 scrollable 在
 * overscroll 进行中立即开始拖动），候选收不到点击；拖动期间列表算「正在滑」，栏尾藏起来的候选照常画出来。
 * 有的手机回弹一直收不完（distance 停在一个很小的非零值），于是每一下都被吞。
 * 这里用一直「回弹中」的效果模拟那种手机：候选栏里的横滑行必须照样能点。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateTapStuckOverscrollTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** 录屏里那一组：一行放不下，最后一个只露半截。 */
    private val words = listOf("王子", "样子", "网站", "网址", "网易", "王者", "扬州", "养殖", "往", "望", "网")

    @Before fun setUp() {
        KeyboardThemes.initFromConfig(context)
    }

    /** 一直停在回弹中的边缘效果。 */
    @OptIn(ExperimentalFoundationApi::class)
    private class StuckOverscroll : OverscrollEffect {
        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset =
            performScroll(delta)
        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            performFling(velocity)
        }
        override val isInProgress: Boolean get() = true
    }

    @OptIn(ExperimentalFoundationApi::class)
    private data class StuckFactory(val effect: OverscrollEffect = StuckOverscroll()) : OverscrollFactory {
        override fun createOverscrollEffect(): OverscrollEffect = effect
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Stuck(content: @Composable () -> Unit) {
        val factory = remember { StuckFactory() }
        CompositionLocalProvider(LocalOverscrollFactory provides factory) { content() }
    }

    @Composable
    private fun Bar(state: CandidateBarState, callbacks: CandidateBarCallbacks) {
        Box(Modifier.width(400.dp).height(44.dp)) {
            CandidateBar(
                state = state,
                visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                callbacks = callbacks,
                showPreeditPreview = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** 对照：默认带回弹的横滑行在同样的条件下确实吞掉点按，证明上面的模拟能复现录屏里的现象。 */
    @Test fun stuckOverscrollSwallowsTapsInADefaultRow() {
        var tapped = -1
        rule.setContent {
            Stuck {
                LazyRow(Modifier.width(200.dp).height(44.dp)) {
                    items(words.size) { index ->
                        Text(words[index], Modifier.testTag("plain:$index").clickable { tapped = index }.padding(8.dp))
                    }
                }
            }
        }
        rule.onNodeWithTag("plain:1").performClick()
        rule.runOnIdle { assertEquals(-1, tapped) }
    }

    @Test fun composingCandidatesStayTappableWhileOverscrollIsStuck() {
        val picked = mutableListOf<Int>()
        rule.setContent {
            Stuck {
                Bar(CandidateBarState.ChineseCandidates(words, inputText = "wang'zi", hasMore = true),
                    CandidateBarCallbacks(onCandidateSelect = { picked += it }))
            }
        }
        rule.onNodeWithTag("bar-candidate:1", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("bar-candidate:3", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(listOf(1, 3), picked) }
    }

    @Test fun tapsAlongFirstCandidatesLowerLeftEdgeDoNotCancelComposition() {
        val events = mutableListOf<String>()
        rule.setContent {
            Box(Modifier.testTag("strip-host")) {
                Bar(CandidateBarState.ChineseCandidates(listOf("这", "着", "者", "折"), inputText = "zhe"),
                    CandidateBarCallbacks(onCandidateSelect = { events += "pick:$it" }, onCancelInput = { events += "cancel" }))
            }
        }
        val bar = rule.onNodeWithTag("strip-host")
        val origin = bar.fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("bar-candidate:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cancel = rule.onNodeWithContentDescription("取消输入", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // #274: locations relative to the arrow and first word in the three filmed taps.
        for ((fraction, drop) in listOf(.58f to .34f, .58f to .41f, .72f to 0f)) {
            val x = cancel.center.x + fraction * (first.center.x - cancel.center.x)
            bar.performTouchInput { click(Offset(x - origin.left, first.center.y + drop * origin.height - origin.top)) }
        }
        bar.performTouchInput { click(cancel.center - origin.topLeft) }
        rule.runOnIdle { assertEquals(listOf("pick:0", "pick:0", "pick:0", "cancel"), events) }
    }

    @Test fun associationsStayTappableWhileOverscrollIsStuck() {
        val picked = mutableListOf<Int>()
        rule.setContent {
            Stuck {
                Bar(CandidateBarState.AssociationOnly(words),
                    CandidateBarCallbacks(onCandidateSelect = {}, onAssociationSelect = { picked += it }))
            }
        }
        rule.onNodeWithTag("bar-association:1", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(listOf(1), picked) }
    }
}
