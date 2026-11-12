package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.CandidateCommentMode
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w500dp-h680dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateGlossEdgeTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val words = listOf("后院", "候选", "后援", "猴园")
    private val notes = listOf("backyard", "candidate", "reinforcement and support", "")
    private val visuals = CandidateBarVisuals(Color(0xff292a30), Color.White, Color.Gray)

    @Test fun rightmostWordCanBeTappedBeforeItsWholeGlossFits() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SettingsPreferences.setCandidateCommentMode(app, CandidateCommentMode.SHOW)
        var picked = -1
        var expanded = false
        rule.setContent { MaterialTheme {
            Column(Modifier.testTag("comparison").width(346.dp).background(visuals.backgroundColor)) {
                for (gloss in listOf(false, true)) {
                    Text(if (gloss) "修复后" else "原先的整项隐藏规则", color = Color.LightGray,
                        fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                    CandidateBar(state = CandidateBarState.ChineseCandidates(words, notes, inputText = "houyuan"),
                        callbacks = CandidateBarCallbacks(onCandidateSelect = { picked = it }, onShowMoreCandidates = { expanded = true }),
                        visuals = visuals, showPreeditPreview = false, commentsAreGlosses = gloss,
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("row-$gloss"))
                }
            }
        } }
        fun inRow(gloss: Boolean, tag: String) = rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("row-$gloss")))
        saveComparison()
        // 右边放不下的候选照常显示、被栏边裁掉，不再整项藏起来（用户 11-11）。
        inRow(false, "bar-candidate:2").assertExists()
        val word = inRow(true, "bar-candidate:2")
        word.assertIsDisplayed()
        val edge = inRow(true, "candidate-fixed-strip").fetchSemanticsNode().boundsInWindow.right
        val label = rule.onNode(hasText("后援") and hasAnyAncestor(hasTestTag("row-true")), useUnmergedTree = true)
        assertTrue(label.fetchSemanticsNode().boundsInWindow.right <= edge)
        word.performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(2, picked) }
        inRow(true, "candidate-scroll-row").performScrollToIndex(2)
        rule.waitForIdle()
        val note = rule.onNode(hasText(notes[2]) and hasAnyAncestor(hasTestTag("row-true")), useUnmergedTree = true)
        val bounds = note.fetchSemanticsNode().boundsInWindow
        assertTrue("Full gloss fits after scrolling", bounds.width >= note.fetchSemanticsNode().size.width - 1)
        inRow(true, "candidate-expansion").performClick()
        rule.runOnIdle { assertTrue(expanded) }
    }

    @Test fun splitSegmentsKeepTheirOwnGlossAndOriginalSelectionIndex() {
        val gap = with(rule.density) { 124.dp.toPx() to 168.dp.toPx() }
        var focused by mutableStateOf(0)
        var picked = -1
        rule.setContent { FixedCandidateStrip(words, comments = listOf("a long backyard definition", "", "", ""),
            callbacks = CandidateBarCallbacks(onCandidateSelect = { picked = it }), visuals = visuals, fontSize = 19.sp,
            modifier = Modifier.width(320.dp), gapWindowX = gap, highlightIndex = focused,
            commentsAreGlosses = true, commentMode = CandidateCommentMode.SHOW) }
        val left = rule.onNodeWithTag("bar-candidate:0").fetchSemanticsNode().boundsInWindow
        val right = rule.onNodeWithTag("bar-candidate:1")
        assertTrue(left.right <= gap.first + 1)
        assertTrue(right.fetchSemanticsNode().boundsInWindow.left >= gap.second - 1)
        rule.runOnIdle { focused = 1 }
        right.performClick()
        rule.runOnIdle { assertEquals(1, picked) }
    }

    private fun saveComparison() {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val bounds = rule.onNodeWithTag("comparison").fetchSemanticsNode().boundsInWindow
        rule.runOnIdle { root.draw(Canvas(bitmap)) }
        File(out, "candidate-gloss-edge.png").outputStream().use {
            Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
