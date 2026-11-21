package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CandidateBarOptionsTest {
    @get:Rule val rule = createComposeRule()
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("candidate_options_$name", mode)
    }
    private val visuals = CandidateBarVisuals(Color(0xFF191C22), Color.White, Color.Gray)
    private val composing = CandidateBarState.ChineseCandidates(listOf("你好", "你"), hasMore = true)

    @Before fun setup() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }
    @After fun teardown() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }

    @Test fun defaultCandidatesShowCancelButtonAndPreferenceUpdatesImmediately() {
        var cancels = 0
        var selects = 0
        // 用户 10-31：默认打开。
        assertTrue(SettingsPreferences.shouldShowCandidateCancelButton(context))
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(composing, visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = { selects++ }, onCancelInput = { cancels++ }),
                        modifier = Modifier.width(320.dp).testTag("candidate-bar"))
                }
            }
        }
        rule.onNodeWithContentDescription("取消输入").assertIsDisplayed()
        val barLeft = rule.onNodeWithTag("candidate-bar").fetchSemanticsNode().boundsInRoot.left
        val enabledLeft = rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left
        val density = context.resources.displayMetrics.density
        rule.runOnIdle { SettingsPreferences.setShowCandidateCancelButton(context, false) }
        rule.onNodeWithContentDescription("取消输入").assertDoesNotExist()
        val originalLeft = rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left
        assertTrue("隐藏按钮不保留40dp占位", originalLeft - barLeft < 20f * density)
        assertEquals(44f * density, enabledLeft - originalLeft, 2f)
        rule.runOnIdle { SettingsPreferences.setShowCandidateCancelButton(context, true) }
        rule.onNodeWithContentDescription("取消输入").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, cancels); assertEquals(0, selects) }
        assertEquals(enabledLeft, rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left, 1f)
        rule.onNodeWithText("你好").performClick()
        rule.runOnIdle { assertEquals(1, selects) }
    }

    @Test fun clipboardRetainsItsOwnReturnButtonWithOptionOff() {
        var dismissed = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(CandidateBarState.ClipboardDisplay(listOf("复制的完整文字")), visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {},
                            onDismissClipboardPreview = { dismissed++ }), modifier = Modifier.width(320.dp))
                }
            }
        }
        rule.onNodeWithContentDescription("取消输入").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回工具栏").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
    }

    /** 用户 11-20：没有展开按钮，候选行横着拉，跟手、越拉越紧；过门槛松手才展开，不够就弹回；拉完马上能点候选。 */
    @Test fun pullingTheRowFollowsTheFingerAndOpensOnlyPastTheThreshold() {
        val expanded = mutableStateOf(false)
        var opens = 0
        var picked = -1
        var hidden = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(composing, candidatePageExpanded = expanded.value, visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = { picked = it },
                            onShowMoreCandidates = { opens++; expanded.value = true },
                            onHideKeyboard = { hidden++ }), modifier = Modifier.width(320.dp))
                }
            }
        }
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        rule.onNodeWithContentDescription("收起键盘").performClick()
        rule.runOnIdle { assertEquals(1, hidden) }
        val dp = rule.density.density
        val pull = rule.onNodeWithTag("candidate-pull-expand")
        val second = rule.onNodeWithTag("bar-candidate:1", useUnmergedTree = true)
        val rest = second.fetchSemanticsNode().boundsInRoot
        // 往右拉一小段：跟手往右挪，松手 150 ms 内回原位，不展开（11-21：往右拉展开，展开着往左拉收回）。
        rule.mainClock.autoAdvance = false
        pull.performTouchInput { down(center); repeat(3) { moveBy(androidx.compose.ui.geometry.Offset(20f * dp, 0f)) } }
        rule.mainClock.advanceTimeByFrame()
        val held = second.fetchSemanticsNode().boundsInRoot
        assertTrue("跟手往右挪：${rest.left} → ${held.left}", held.left > rest.left + 5f * dp)
        assertTrue("越拉越紧：手指走 60dp，栏挪得少", held.left - rest.left < 40f * dp)
        pull.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(com.kingzcheung.xime.ui.keyboard.RELEASE_MS + 50L)
        assertEquals(rest.left, second.fetchSemanticsNode().boundsInRoot.left, 1f)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertEquals(0, opens) }
        // 回弹途中点候选照样选得上。
        pull.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(60f * dp, 0f)); up() }
        second.performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, picked); assertEquals(0, opens) }
        // 拉过门槛松手展开，展开着再往右拉不会再展开一次。
        pull.pullAcross()
        rule.runOnIdle { assertEquals(1, opens); assertTrue(expanded.value) }
        pull.pullAcross()
        rule.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun settingIsInitiallyOnAndSavesExplicitChoice() {
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                // 候选词从首页直接进入。
                MaterialTheme { LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") }
            }
        }
        // 外层设置搜索定位修饰的 testTag 在前，同一节点上前面的生效，两个名字都认。
        val tagged = hasTestTag("candidate-cancel-setting") or hasTestTag("settings-search-anchor:候选栏取消按钮")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(tagged)
        val setting = rule.onNode(tagged)
        setting.assertIsOn().performClick().assertIsOff()
        rule.runOnIdle { assertFalse(SettingsPreferences.shouldShowCandidateCancelButton(context)) }
        setting.performClick().assertIsOn()
        rule.runOnIdle { assertTrue(SettingsPreferences.shouldShowCandidateCancelButton(context)) }
    }

    @Test fun preeditShowsSyllableSeparatorsAtEditorFontSizeAndRemainsClickable() {
        SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
        val showPreview = mutableStateOf(true)
        var edits = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(top = 100.dp)) {
                        CandidateBar(composing.copy(inputText = "nihao", preeditText = "ni hao"), visuals = visuals,
                            callbacks = CandidateBarCallbacks(onCandidateSelect = {}), modifier = Modifier.width(300.dp),
                            onEditPreedit = { edits++ }, showPreeditPreview = showPreview.value)
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(18.sp, layouts.single().layoutInput.style.fontSize)
        rule.onNodeWithTag("candidate-preedit").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, edits); showPreview.value = false }
        rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
    }

    @Test fun longPreeditScrollsWithinScreenAndRefreshesWithoutDuplicating() {
        val text = mutableStateOf("xiu'gai'shu'ru'".repeat(20))
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(start = 36.dp, top = 100.dp)) {
                        CandidateBar(composing.copy(inputText = text.value), visuals = visuals,
                            callbacks = CandidateBarCallbacks(onCandidateSelect = {}), modifier = Modifier.width(280.dp),
                            onEditPreedit = {})
                    }
                }
            }
        }
        val preview = rule.onNodeWithTag("candidate-preedit").fetchSemanticsNode()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue(preview.positionOnScreen.x >= 0f)
        assertTrue(preview.positionOnScreen.x + preview.size.width <= screen.width + 1f)
        assertTrue(preview.positionOnScreen.y >= 0f)
        assertTrue(preview.positionOnScreen.y + preview.size.height <= screen.height + 1f)
        screen.recycle()
        rule.onNodeWithText(text.value, useUnmergedTree = true).performTouchInput { swipeLeft() }
        rule.runOnIdle { text.value = "ni'hao" }
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("candidate-preedit").assertCountEquals(1)
    }
}
