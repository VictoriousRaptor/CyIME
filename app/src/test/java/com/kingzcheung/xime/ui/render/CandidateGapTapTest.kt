package com.kingzcheung.xime.ui.render

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
 * #274（vivo X Fold3 Pro，26 键打「这」）：点在候选上下的留白、两个候选之间、「<」和第一个候选之间，
 * 都算点最近的候选；正点在「<」上照旧取消，离候选远的空白、拖动都不选。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateGapTapTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        KeyboardThemes.initFromConfig(context)
        rule.setContent {
            Box(Modifier.testTag("strip-host").width(400.dp).height(44.dp)) {
                CandidateBar(
                    state = CandidateBarState.ChineseCandidates(listOf("这", "着", "者", "折"), inputText = "zhe"),
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = { events += "pick:$it" },
                        onCancelInput = { events += "cancel" }),
                    showPreeditPreview = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private val host get() = rule.onNodeWithTag("strip-host")
    private val origin get() = host.fetchSemanticsNode().boundsInRoot
    private fun candidate(index: Int) =
        rule.onNodeWithTag("bar-candidate:$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun tapAt(point: Offset) = host.performTouchInput { click(point - origin.topLeft) }

    @Test fun belowAndAboveACandidatePickIt() {
        val first = candidate(0)
        tapAt(Offset(first.center.x, origin.bottom - 1f))
        tapAt(Offset(first.center.x, origin.top + 1f))
        rule.runOnIdle { assertEquals(listOf("pick:0", "pick:0"), events) }
    }

    @Test fun betweenTheArrowAndTheFirstCandidatePicksTheCandidate() {
        val first = candidate(0)
        tapAt(Offset(first.left - 2f, origin.bottom - 3f))
        rule.runOnIdle { assertEquals(listOf("pick:0"), events) }
    }

    @Test fun theGapBetweenTwoCandidatesPicksTheNearerOne() {
        val first = candidate(0)
        val second = candidate(1)
        tapAt(Offset(first.right + 0.5f, origin.bottom - 2f))
        tapAt(Offset(second.left - 0.5f, origin.bottom - 2f))
        rule.runOnIdle { assertEquals(listOf("pick:0", "pick:1"), events) }
    }

    @Test fun theArrowItselfStillCancels() {
        val cancel = rule.onNodeWithContentDescription("取消输入", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        tapAt(cancel.center)
        rule.runOnIdle { assertEquals(listOf("cancel"), events) }
    }

    @Test fun dragAlongTheBottomEdgeSelectsNothing() {
        val first = candidate(0)
        host.performTouchInput {
            swipe(Offset(first.center.x, origin.bottom - 2f) - origin.topLeft,
                Offset(first.center.x + 120f, origin.bottom - 2f) - origin.topLeft, 200L)
        }
        rule.runOnIdle { assertEquals(emptyList<String>(), events) }
    }
}
