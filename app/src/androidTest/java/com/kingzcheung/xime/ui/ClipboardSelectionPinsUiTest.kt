package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.clipboard.*
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the actual board; no database, native input engine or launcher is involved. */
class ClipboardSelectionPinsUiTest {
    @get:Rule val rule = createComposeRule()

    private var pins by mutableStateOf(emptySet<String>())
    private var pasted = ""
    private var removed = emptyList<ClipboardCard>()
    private val keyboard = ClipboardKeyboardNavigation {}

    private fun show(
        items: List<ClipboardItem>,
        width: Int = 360,
        height: Int = 360,
        dark: Boolean = false,
        keyboardActive: Boolean = false,
        fontScale: Float = 1f,
    ) {
        pins = items.filter { it.isPinned }.mapTo(mutableSetOf()) { "text:${it.id}" }
        if (keyboardActive) keyboard.open()
        rule.setContent {
            // Test tablet-width layout on a phone runner without extending off its display.
            CompositionLocalProvider(
                LocalDensity provides Density(.4f, fontScale),
                LocalClipboardKeyboardNavigation provides keyboard,
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
                        ClipboardBoardView(
                            textItems = items,
                            images = emptyList(),
                            initialImages = false,
                            expanded = false,
                            pins = pins,
                            photoAccess = false,
                            failure = null,
                            onBack = {},
                            onQuickSend = {},
                            onSelectText = { pasted = it },
                            onSelectImage = {},
                            onSplit = { _, _ -> },
                            onAddQuick = {},
                            onPinsChange = { pins = it },
                            onRemove = { removed = it },
                            onPhotoAccess = {},
                            onPick = {},
                            onSystemPaste = {},
                            onShare = { _, _ -> },
                            onPullRemote = null,
                        )
                    }
                }
            }
        }
    }

    private fun selectByLongPress(id: Long) {
        rule.onNodeWithTag("clipboard-card:text:$id").performTouchInput { longClick() }
        rule.onNodeWithText("多选").performScrollTo().performClick()
    }

    @Test fun longPressSelectionShowsBothCheckedAndUncheckedCardsWithoutPasting() {
        show(listOf(ClipboardItem(1, "第一条", timestamp = 2), ClipboardItem(2, "第二条", timestamp = 1)))
        selectByLongPress(1)
        rule.onNodeWithTag("clipboard-exit-selection").assertIsDisplayed()
        rule.onNodeWithTag("clipboard-selection:text:1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("clipboard-card:text:1")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        rule.onNodeWithTag("clipboard-selection:text:2", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("clipboard-card:text:2").assertIsNotSelected()
        rule.onNodeWithTag("clipboard-card:text:2").performClick()
        rule.onNodeWithTag("clipboard-card:text:2").assertIsSelected()
        rule.runOnIdle { assertEquals("", pasted) }
        rule.onNodeWithTag("clipboard-exit-selection").performClick()
        rule.onNodeWithTag("clipboard-selection:text:1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun selectedPinnedCardCanBeUnpinnedWithoutAffectingOtherPins() {
        show(listOf(
            ClipboardItem(1, "固定一", timestamp = 2, isPinned = true),
            ClipboardItem(2, "固定二", timestamp = 1, isPinned = true),
            ClipboardItem(3, "最近", timestamp = 3),
        ))
        selectByLongPress(1)
        rule.onNodeWithTag("clipboard-pin").assertContentDescriptionEquals("取消固定所选").performClick()
        rule.runOnIdle {
            assertEquals(setOf("text:2"), pins)
            assertEquals("", pasted)
            assertTrue(removed.isEmpty())
        }
    }

    @Test fun selectAllRevealsCollapsedPinsAndCanBeClearedWithoutDeleting() {
        show(pinnedAndRecent(pinCount = 8))
        selectByLongPress(1)
        // Each section has its own select-all row (user 11-11); the fixed one opens folded pins.
        rule.onNodeWithTag("clipboard-select-fixed").performClick()
        rule.onNodeWithText("已选 8").assertExists()
        rule.onNodeWithTag("clipboard-records").performScrollToNode(hasTestTag("clipboard-card:text:8"))
        rule.onNodeWithTag("clipboard-card:text:8").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("clipboard-records").performScrollToNode(hasTestTag("clipboard-select-recent"))
        rule.onNodeWithTag("clipboard-select-recent").performClick()
        rule.onNodeWithText("已选 9").assertExists()
        // Clearing one section leaves the other.
        rule.onNodeWithTag("clipboard-select-recent").performClick()
        rule.onNodeWithText("已选 8").assertExists()
        rule.onNodeWithTag("clipboard-records").performScrollToNode(hasTestTag("clipboard-select-fixed"))
        rule.onNodeWithTag("clipboard-select-fixed").performClick()
        rule.onNodeWithText("已选 0").assertExists()
        rule.onNodeWithTag("clipboard-pin").assertIsNotEnabled()
        rule.onNodeWithTag("clipboard-delete").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(removed.isEmpty()) }
    }

    @Test fun multiDeleteOnlyRemovesCheckedRecordsAfterConfirmation() {
        show(listOf(
            ClipboardItem(1, "第一条", timestamp = 3),
            ClipboardItem(2, "第二条", timestamp = 2),
            ClipboardItem(3, "保留这一条", timestamp = 1),
        ))
        selectByLongPress(1)
        rule.onNodeWithTag("clipboard-card:text:2").performClick()
        rule.onNodeWithTag("clipboard-delete").performClick()
        rule.runOnIdle { assertTrue(removed.isEmpty()) }
        rule.onNodeWithTag("clipboard-confirm-delete").performClick()
        rule.runOnIdle {
            assertEquals(listOf("text:1", "text:2"), removed.map { it.key })
            assertEquals("", pasted)
        }
    }

    @Test fun phoneWidthKeepsRecentCopyVisibleBelowOnePinnedRow() = assertCollapsedLayout(360, 320)

    @Test fun narrowShortPanelKeepsRecentCopyVisibleBelowOnePinnedRow() = assertCollapsedLayout(240, 180)

    @Test fun tabletWidthKeepsRecentCopyVisibleBelowOnePinnedRow() = assertCollapsedLayout(1000, 320, dark = true)

    @Test fun narrowPanelWithLargeFontsKeepsAllSelectionControlsAndCountInsideTheRow() {
        show(pinnedAndRecent(pinCount = 3), width = 240, height = 180, fontScale = 2f)
        selectByLongPress(1)
        val controls = rule.onNodeWithTag("clipboard-controls").fetchSemanticsNode().boundsInRoot
        val board = rule.onNodeWithTag("clipboard-board").fetchSemanticsNode().boundsInRoot
        val tags = listOf("clipboard-exit-selection", "clipboard-selected-count", "clipboard-pin", "clipboard-delete")
        for (tag in tags) {
            val item = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag starts within controls", item.left >= controls.left - 1f && item.top >= controls.top - 1f)
            assertTrue("$tag ends within controls", item.right <= controls.right + 1f && item.bottom <= controls.bottom + 1f)
        }
        val count = rule.onNodeWithText("已选 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Selection count remains visible", count.width > 0f && count.left >= controls.left && count.right <= controls.right)
        assertTrue("Control row cannot grow wider than the panel", controls.left >= board.left && controls.right <= board.right)
    }

    @Test fun selectingAllWithOnlyOnePinDoesNotLeaveAnUnboundedPinnedPreview() {
        show(pinnedAndRecent(pinCount = 1), width = 360, height = 240)
        val before = rule.onNodeWithTag("clipboard-card:text:1").fetchSemanticsNode().boundsInRoot.height
        selectByLongPress(1)
        rule.onNodeWithTag("clipboard-select-fixed").performClick()
        rule.onNodeWithTag("clipboard-select-recent").performClick()
        rule.onNodeWithTag("clipboard-exit-selection").performClick()
        val after = rule.onNodeWithTag("clipboard-card:text:1").fetchSemanticsNode().boundsInRoot.height
        val region = rule.onNodeWithTag("clipboard-records-region").fetchSemanticsNode().boundsInRoot
        assertEquals("Selection state must not expand a single-row fixed area", before, after, 1f)
        assertTrue(after <= region.height * .35f + 1f)
        rule.onNodeWithTag("clipboard-card:text:1000").assertIsDisplayed()
        rule.onNodeWithTag("clipboard-pins-toggle").assertDoesNotExist()
    }

    private fun assertCollapsedLayout(width: Int, height: Int, dark: Boolean = false) {
        show(pinnedAndRecent(pinCount = 100), width = width, height = height, dark = dark)
        val columns = clipboardColumnCount(width.toFloat())
        val region = rule.onNodeWithTag("clipboard-records-region").fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("clipboard-card:text:1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("clipboard-card:text:$columns").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val recent = rule.onNodeWithTag("clipboard-card:text:1000").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Collapsed pins must occupy one row", first.top, last.top, 1f)
        assertTrue("Recent item must start after the pinned row", recent.top >= first.bottom)
        assertTrue("Recent copy must remain in the viewport", recent.top < region.bottom)
        assertTrue("Pinned preview must be bounded", first.height <= region.height * .4f)
        rule.onNodeWithTag("clipboard-card:text:${columns + 1}").assertDoesNotExist()
        rule.onNodeWithTag("clipboard-pins-toggle").assertIsDisplayed()
        // Pinned records are one line high, and the toolbar uses the same pill as the divider.
        assertEquals("pinned record is one line high", 40f * .4f, first.height, 1f)
        rule.onNodeWithText(if (width >= 360) "固定 100" else "100", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("展开 ${100 - columns} 条", useUnmergedTree = true).assertExists()
    }

    @Test fun collapsingPinsRemovesHiddenRecordsFromKeyboardNavigation() {
        show(pinnedAndRecent(pinCount = 8), keyboardActive = true)
        rule.onNodeWithTag("clipboard-pins-toggle").performClick()
        rule.runOnIdle { keyboard.dispatch(ClipboardNavigation.DOWN) }
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-card:text:3").assertIsFocused()
        rule.onNodeWithTag("clipboard-pins-toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-card:text:3").assertDoesNotExist()
        rule.runOnIdle { keyboard.dispatch(ClipboardNavigation.DOWN) }
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-card:text:1000").assertIsFocused()
        rule.runOnIdle { keyboard.dispatch(ClipboardNavigation.CONFIRM) }
        rule.runOnIdle { assertEquals("刚复制的内容", pasted) }
    }

    private fun pinnedAndRecent(pinCount: Int): List<ClipboardItem> =
        List(pinCount) { index ->
            ClipboardItem(
                (index + 1).toLong(),
                "固定内容 ${index + 1}\n很长的第二行\n第三行\n第四行\n第五行",
                timestamp = (pinCount - index).toLong(),
                isPinned = true,
            )
        } + ClipboardItem(1000, "刚复制的内容", timestamp = 1000)
}
