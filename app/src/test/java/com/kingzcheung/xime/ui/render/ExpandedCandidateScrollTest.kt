package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.CandidateEntry
import com.kingzcheung.xime.ui.keyboard.CandidatePage
import com.kingzcheung.xime.ui.keyboard.CandidatePageCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidatePageState
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The same page and batches can be rendered in an isolated display on the tablet.
internal class ExpandedScrollScene {
    private val words = List(128) { CandidateEntry("候选词" + (it + 1), globalIndex = it) }
    var count by mutableIntStateOf(0)
    var revision by mutableStateOf(1L)
    var filtered by mutableStateOf(false)
    var open by mutableStateOf(true)
    var selected = -1

    @Composable fun Content() {
        if (open) CandidatePage(
            state = CandidatePageState(candidates = words.take(count), engineRevision = revision,
                associationCandidates = listOf("联想甲", "联想乙"), singleCharFilter = filtered,
                backgroundColor = Color.White, textColor = Color.Black),
            callbacks = CandidatePageCallbacks(onCandidateSelect = { selected = it.globalIndex }),
            modifier = Modifier.size(360.dp, 210.dp).testTag("panel"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h850dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedCandidateScrollTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun show(scene: ExpandedScrollScene) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppFonts.initialize(app)
        KeyboardThemes.initFromConfig(app)
        rule.setContent { scene.Content() }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val bounds = rule.onNodeWithTag("panel", true).fetchSemanticsNode().boundsInRoot
        val bmp = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bmp)
            canvas.translate(-bounds.left, -bounds.top)
            rule.activity.window.decorView.draw(canvas)
        }
        File(out, name + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun predictionsArriveBeforeFirstCandidateBatch_panelStaysAtStart() {
        val scene = ExpandedScrollScene()
        show(scene)
        rule.onNodeWithText("联想甲").assertIsDisplayed()
        rule.runOnIdle { scene.count = 64 }
        rule.waitForIdle()
        shot("first-batch")
        rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
        rule.onNodeWithTag("expanded-candidate:0", true).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(0, scene.selected) }
    }

    @Test fun browsingSurvivesNextBatch_newQueryFilterAndReopeningStartAtTop() {
        val scene = ExpandedScrollScene().apply { count = 64 }
        show(scene)
        val page = rule.onNodeWithTag("expanded-candidates", true)
        fun firstVisible(): Pair<Int, Float> {
            val top = page.fetchSemanticsNode().boundsInRoot.top
            val bottom = page.fetchSemanticsNode().boundsInRoot.bottom
            val prefix = "expanded-candidate:"
            return rule.onAllNodes(SemanticsMatcher("candidate items") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
            }, true).fetchSemanticsNodes().mapNotNull { node ->
                val i = node.config[SemanticsProperties.TestTag].removePrefix(prefix).toInt()
                val bounds = node.boundsInRoot
                if (bounds.bottom > top && bounds.top < bottom && bounds.height > 0f) i to bounds.top else null
            }.minBy { it.first }
        }
        fun nextPage() {
            rule.onNodeWithTag("expanded-page-next").performClick()
            rule.waitForIdle()
            assertTrue(firstVisible().first > 0)
        }
        nextPage()
        val before = firstVisible()
        rule.runOnIdle { scene.count = 128 }
        assertEquals(before, firstVisible())
        shot("browse-after-append")
        rule.runOnIdle { scene.revision++ }
        rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
        nextPage()
        rule.runOnIdle { scene.filtered = true }
        rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
        nextPage()
        rule.runOnIdle { scene.open = false }
        page.assertDoesNotExist()
        rule.runOnIdle { scene.open = true }
        rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
    }

    @Test fun reopeningWithArrowDuringCloseAnimationStartsAtHead() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppFonts.initialize(app)
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        val model = spy(KeyboardViewModel(app))
        val words = List(96) { RimeCandidate("候选词" + (it + 1), "") }
        val snapshot = mutableStateOf(CandidateState(engineRevision = 12, inputText = "ci", isComposing = true,
            candidates = words.take(8).map { it.text }, hasNextPage = true,
            expandedCandidates = words, expandedCandidatesLoaded = true))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        doReturn(mock<T9InputController>()).whenever(model).bindT9Controller(callbacks)
        rule.setContent {
            KeyboardView(model, KeyboardUiState(currentSchemaId = "rime_ice"), callbacks,
                modifier = Modifier.size(400.dp, 284.dp), candidateState = snapshot, inputSessionManagedByHost = true)
        }
        val arrow = rule.onNodeWithTag("candidate-expansion")
        val previous = rule.onNodeWithTag("expanded-page-previous")
        arrow.performClick()
        rule.onNodeWithTag("expanded-page-next").performClick()
        previous.assertIsEnabled()
        rule.mainClock.autoAdvance = false
        try {
            arrow.performClick()
            rule.mainClock.advanceTimeBy(32)
            assertEquals(false, model.candidatePageExpanded.value)
            rule.onNodeWithTag("expanded-candidates", true).assertExists() // outgoing page is still composed
            arrow.performClick()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        previous.assertIsNotEnabled()
    }
}
