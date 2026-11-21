package com.kingzcheung.xime.ui

import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.kingzcheung.xime.service.HardwareCursorAnchor
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardCandidateBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/** Measures the real Compose card; does not assume the estimated width equals its size. */
class HardwareCandidatePositionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun followsMovingCaretAndFallsBackWithinOffsetHost() {
        val anchor = mutableStateOf<HardwareCursorAnchor?>(null)
        val input = mutableStateOf("nihao")
        val viewLocation = IntArray(2)
        rule.setContent {
            val view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(600.dp, 420.dp).padding(start = 40.dp, top = 30.dp)
                        .onGloballyPositioned { view.getLocationOnScreen(viewLocation) }) {
                        HardwareKeyboardCandidateBar(
                            inputText = input.value, preeditText = input.value, candidates = listOf("你好", "拟好"),
                            hasNextPage = false, hasPrevPage = false, cursorAnchor = anchor.value,
                            highlightIndex = 0, cardBackgroundColor = Color.Black,
                            candidateTextColor = Color.White, activeColor = Color.Blue,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val host = rule.onNodeWithTag("hardware-candidate-host", true).fetchSemanticsNode().boundsInRoot
        fun card() = rule.onNodeWithTag("hardware-candidate-card", true).fetchSemanticsNode().boundsInRoot
        fun move(x: Float, top: Float, bottom: Float) {
            rule.runOnIdle {
                anchor.value = HardwareCursorAnchor(
                    viewLocation[0] + host.left + x, viewLocation[1] + host.top + top,
                    viewLocation[0] + host.left + x, viewLocation[1] + host.top + bottom,
                )
            }
            rule.waitForIdle()
        }
        move(50f, 60f, 80f)
        assertEquals(host.left + 50f, card().left, 1f)
        assertEquals(host.top + 88f, rule.onNodeWithTag("hardware-preedit-card", true).fetchSemanticsNode().boundsInRoot.top, 1f)
        // 同一次输入里横向定在开始打字的地方，纵向跟着光标换行走（用户 11-20：卡片跟着光标左右晃）。
        move(90f, 120f, 140f)
        assertEquals(host.left + 50f, card().left, 1f)
        assertEquals(host.top + 148f, rule.onNodeWithTag("hardware-preedit-card", true).fetchSemanticsNode().boundsInRoot.top, 1f)
        // 这次输入结束（只剩联想）后照常跟光标。
        rule.runOnIdle { input.value = "" }
        move(host.width - 5f, host.height - 30f, host.height - 10f)
        assertEquals(host.right - 8f, card().right, 1f)
        assertTrue(card().bottom <= host.bottom - 38f + 1f)
        rule.runOnIdle { anchor.value = null }
        rule.waitForIdle()
        assertEquals(host.center.x, card().center.x, 1f)
        assertEquals(host.bottom - 8f, card().bottom, 1f)
    }

    @Test fun reportsMeasuredBoundsSelectsIndexAndClearsWhenContentDisappears() {
        val visible = mutableStateOf(true)
        val hasContent = mutableStateOf(true)
        var reportedBounds: Rect? = null
        var selectedIndex: Int? = null
        var underlyingClicks = 0
        var hostWindowBounds = androidx.compose.ui.geometry.Rect.Zero
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(500.dp, 400.dp).padding(start = 30.dp, top = 20.dp)) {
                        Box(Modifier.fillMaxSize().testTag("underlying-editor")
                            .clickable { underlyingClicks++ }
                            .onGloballyPositioned { hostWindowBounds = it.boundsInWindow() })
                        if (visible.value) {
                            HardwareKeyboardCandidateBar(
                                inputText = if (hasContent.value) "nihao" else "", preeditText = "",
                                candidates = if (hasContent.value) listOf("你好", "拟好") else emptyList(),
                                hasNextPage = false, hasPrevPage = false, cursorAnchor = null,
                                highlightIndex = 0, cardBackgroundColor = Color.Black,
                                candidateTextColor = Color.White, activeColor = Color.Blue,
                                onCandidateSelect = { selectedIndex = it },
                                onBoundsChanged = { reportedBounds = it },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val host = rule.onNodeWithTag("hardware-candidate-host", true).fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("hardware-candidate-card", true).fetchSemanticsNode().boundsInRoot
        val offsetX = hostWindowBounds.left - host.left
        val offsetY = hostWindowBounds.top - host.top
        assertEquals(Rect(floor(card.left + offsetX).toInt(), floor(card.top + offsetY).toInt(),
            ceil(card.right + offsetX).toInt(), ceil(card.bottom + offsetY).toInt()), reportedBounds)
        rule.onNodeWithTag("bar-candidate:1", true).performClick()
        rule.runOnIdle { assertEquals(1, selectedIndex) }
        // The full-screen placement host must not absorb touches outside its card.
        rule.onNodeWithTag("underlying-editor", true).performTouchInput { click(Offset(10f, 10f)) }
        rule.runOnIdle {
            assertEquals(1, underlyingClicks)
            hasContent.value = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertNull(reportedBounds); hasContent.value = true }
        rule.waitForIdle()
        rule.runOnIdle { assertNotNull(reportedBounds); visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertNull(reportedBounds) }
    }

    @Test fun fallbackAvoidsWindowSpaceToolbarInsideOffsetHost() {
        val toolbarBounds = mutableStateOf<Rect?>(null)
        var candidateBounds: Rect? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(500.dp, 400.dp).padding(start = 40.dp, top = 30.dp)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            toolbarBounds.value = Rect(bounds.center.x.toInt() - 110,
                                bounds.bottom.toInt() - 60, bounds.center.x.toInt() + 110,
                                bounds.bottom.toInt() - 8)
                        }) {
                        HardwareKeyboardCandidateBar(
                            inputText = "nihao", preeditText = "ni hao", candidates = listOf("你好", "拟好"),
                            hasNextPage = false, hasPrevPage = false, cursorAnchor = null,
                            highlightIndex = 0, cardBackgroundColor = Color.Black,
                            candidateTextColor = Color.White, activeColor = Color.Blue,
                            onBoundsChanged = { candidateBounds = it }, avoidBoundsInWindow = toolbarBounds.value,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val toolbar = requireNotNull(toolbarBounds.value)
            val candidate = requireNotNull(candidateBounds)
            assertTrue(!Rect.intersects(toolbar, candidate))
            assertTrue(candidate.bottom <= toolbar.top - 8)
        }
    }

    @Test fun hardwareCandidatesUseOneSharedRowAndFollowHighlightWindow() {
        val highlight = mutableStateOf(0)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(500.dp, 400.dp)) {
                    HardwareKeyboardCandidateBar("nihao", "ni hao", List(10) { "很长的候选词$it" },
                        true, false, null, highlight.value, Color.Black, Color.White, Color.Blue)
                }
            }
        }
        rule.waitForIdle()
        val first = rule.onNodeWithTag("bar-candidate:0", true).fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("bar-candidate:1", true).fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.bottom, second.bottom, 1f)
        val before = rule.onNodeWithTag("hardware-candidate-card", true).fetchSemanticsNode().boundsInRoot.height
        rule.runOnIdle { highlight.value = 9 }
        rule.waitForIdle()
        val last = rule.onNodeWithTag("bar-candidate:9", true).fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("hardware-candidate-card", true).fetchSemanticsNode().boundsInRoot
        assertTrue(last.left >= card.left && last.right <= card.right)
        assertEquals(before, card.height, 1f)
    }

    @Test fun preeditFitsTextAndRemainsSeparateWhenCandidatesDock() {
        val text = mutableStateOf("ce")
        val docked = mutableStateOf(false)
        var preeditBounds: Rect? = null
        var candidateBounds: Rect? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(600.dp, 420.dp)) {
                    HardwareKeyboardCandidateBar(text.value, text.value, listOf("测试", "侧视"),
                        false, false, null, 0, Color.Black, Color.White, Color.Blue,
                        bottomDocked = docked.value, avoidBoundsInWindow = Rect(8, 356, 592, 412),
                        onBoundsChanged = { candidateBounds = it }, onPreeditBoundsChanged = { preeditBounds = it })
                }
            }
        }
        rule.waitForIdle()
        val short = requireNotNull(preeditBounds).width()
        assertTrue(short < requireNotNull(candidateBounds).width())
        assertTrue(requireNotNull(preeditBounds).bottom < requireNotNull(candidateBounds).top)
        rule.runOnIdle { text.value = "ce shi shu ru" }
        rule.waitForIdle()
        assertTrue(requireNotNull(preeditBounds).width() > short)
        // 贴底时拼音画在浮条里，不再浮在浮条上方压住 App 的输入框（用户 11-20）。
        rule.runOnIdle { docked.value = true }
        rule.waitForIdle()
        rule.onNodeWithTag("hardware-candidate-card", true).assertDoesNotExist()
        rule.onNodeWithTag("hardware-preedit-card", true).assertDoesNotExist()
        assertNull(candidateBounds)
        assertNull(preeditBounds)
        rule.runOnIdle { docked.value = false; text.value = "" }
        rule.waitForIdle()
        assertNull(preeditBounds)
    }
    @Test fun preeditFitsFractionalPaddingAndLargeSystemFontWithoutClipping() {
        val fontScale = mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(
                // Each 8dp side rounds to 10px here; measuring the combined 16dp gives 19px.
                LocalDensity provides Density(1.1875f, fontScale.value),
                LocalTextStyle provides TextStyle(fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp, lineHeight = 30.sp, letterSpacing = 1.1.sp),
            ) {
                Box(Modifier.size(420.dp, 300.dp)) {
                    HardwareKeyboardCandidateBar("jingjiu", "jing jiu", emptyList(),
                        false, false, null, 0, Color.Black, Color.White, Color.Blue)
                }
            }
        }
        for (scale in listOf(1f, 2.2f)) {
            rule.runOnIdle { fontScale.value = scale }
            rule.waitForIdle()
            val label = rule.onNodeWithTag("candidate-preedit-text", true)
            val scroll = label.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val card = rule.onNodeWithTag("hardware-preedit-card", true).fetchSemanticsNode().boundsInRoot
            assertEquals("A short preedit must fit entirely, including rounded padding", 0f, scroll.maxValue(), 0f)
            assertEquals("jing jiu".length, layout.getLineEnd(0))
            assertTrue(!layout.isLineEllipsized(0))
            assertTrue("The preedit surface must grow for large system text", card.height >= layout.size.height)
        }
    }

    @Test fun longPreeditFollowsNewTextAndCanScrollBackToTheBeginning() {
        val text = mutableStateOf("wo men zheng zai ce shi chang pin yin ".repeat(8))
        val width = mutableStateOf(280.dp)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(width.value, 260.dp)) {
                        HardwareKeyboardCandidateBar(text.value, text.value, emptyList(),
                            false, false, null, 0, Color.Black, Color.White, Color.Blue)
                    }
                }
            }
        }
        fun assertTailIsVisible() {
            rule.waitForIdle()
            val label = rule.onNodeWithTag("candidate-preedit-text", true)
            val scroll = label.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(scroll.maxValue() > 0f)
            assertEquals("New syllables must be scrolled into view", scroll.maxValue(), scroll.value(), 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(text.value.length, layouts.single().getLineEnd(0))
            assertTrue(!layouts.single().isLineEllipsized(0))
            val card = rule.onNodeWithTag("hardware-preedit-card", true).fetchSemanticsNode().boundsInRoot
            val host = rule.onNodeWithTag("hardware-candidate-host", true).fetchSemanticsNode().boundsInRoot
            assertTrue(card.left >= host.left + 8f && card.right <= host.right - 8f)
        }
        assertTailIsVisible()
        rule.onNodeWithTag("candidate-preedit-text", true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(-100_000f, 0f) }
        rule.waitForIdle()
        val start = rule.onNodeWithTag("candidate-preedit-text", true)
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals("Earlier syllables must remain reachable", 0f, start.value(), 1f)
        rule.runOnIdle { text.value += "zui hou" }
        assertTailIsVisible()
        rule.runOnIdle { width.value = 200.dp }
        assertTailIsVisible()
        rule.runOnIdle { text.value = "ni hao" }
        rule.waitForIdle()
        val short = rule.onNodeWithTag("candidate-preedit-text", true)
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(0f, short.maxValue(), 0f)
        assertEquals(0f, short.value(), 0f)
        assertTrue(rule.onNodeWithTag("hardware-preedit-card", true).fetchSemanticsNode().boundsInRoot.width < 150f)
    }

}
