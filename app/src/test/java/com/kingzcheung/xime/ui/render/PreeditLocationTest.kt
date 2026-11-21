package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.service.*
import com.kingzcheung.xime.rime.PinyinEditSession
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreeditLocationTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun oneCompositionMovesBetweenEditorAndCandidatePreviewWithoutLosingText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        AppFonts.initialize(app)
        KeyboardThemes.initFromConfig(app)
        val editor = EditText(rule.activity)
        editor.setText("正文")
        editor.setSelection(editor.length())
        val connection = editor.onCreateInputConnection(EditorInfo())!!
        val code = "hou yuan"
        val snapshot = CandidateState(inputText = "4689826", preeditText = code, candidates = listOf("后院", "候选", "后援"), isComposing = true)
        val service = mock<XimeInputMethodService>()
        whenever(service.getSharedPreferences(any(), any())).thenReturn(SettingsPreferences.getPrefsPublic(app))
        doReturn(connection).whenever(service).currentInputConnection
        val candidates = mutableStateOf(snapshot)
        doReturn(candidates).whenever(service).candidateState
        val ui = mutableStateOf(InputUIState())
        doReturn(ui).whenever(service).uiState
        for ((name, value) in mapOf("sessionController" to ImeSessionController(service),
                "inlineComposition" to InlineCompositionTracker(), "uiState" to ui)) {
            XimeInputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        doCallRealMethod().whenever(service).syncInlinePinyinLocation()
        doCallRealMethod().whenever(service).markInputBoxComposing(anyOrNull())
        doCallRealMethod().whenever(service).endComposingInputBox()
        doCallRealMethod().whenever(service).showInlineEditCaret(any())
        doCallRealMethod().whenever(service).keepInlineEditCaret(any())
        var hardware by mutableStateOf(false)
        var edits = 0
        val edit = PinyinEditSession(1, "t9_pinyin", snapshot.inputText, "", "", "hou'yuan", 3, true)
        val vm = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> },
            onCandidateSelect = { connection.commitText(snapshot.candidates[it], 1) },
            onOpenPreeditEditor = { reply -> edits++; reply(edit) })
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.INPUT
            on { firstOptions } doReturn listOf("gou", "hou", "g", "h").map { T9InputController.SyllableOption(it, it.length) }
        }
        doReturn(t9).whenever(vm).bindT9Controller(callbacks)
        vm.dispatch(KeyboardDispatchAction.InputSessionStarted(false, "t9_pinyin"))
        rule.setContent {
            val prefs = rememberKeyboardInputPreferences()
            Column(Modifier.fillMaxSize()) {
                AndroidView(factory = { editor }, modifier = Modifier.fillMaxWidth().height(48.dp))
                Spacer(Modifier.height(52.dp))
                CompositionLocalProvider(LocalKeyboardInputPreferences provides prefs) {
                    if (hardware) HardwareKeyboardCandidateBar(inputText = snapshot.inputText, preeditText = code,
                        candidates = snapshot.candidates, hasNextPage = false, hasPrevPage = false, cursorAnchor = null,
                        highlightIndex = 0, cardBackgroundColor = Color.White, candidateTextColor = Color.Black,
                        activeColor = Color.Blue, showPreedit = prefs.showCodeInCandidateBar)
                    else KeyboardView(vm, KeyboardUiState(currentSchemaId = "t9_pinyin", keyboardHeightDp = 268),
                        callbacks, Modifier.fillMaxWidth().height(268.dp).testTag("keyboard"),
                        candidateState = candidates, inputSessionManagedByHost = true)
                }
            }
        }
        fun location(value: String) = rule.runOnIdle {
            SettingsPreferences.setInputTextLocation(app, value)
            service.syncInlinePinyinLocation()
        }
        fun assertCodeInEditor() {
            rule.onNodeWithTag("candidate-preedit", true).assertDoesNotExist()
            rule.onNodeWithTag("inline-preedit", true).assertDoesNotExist()
            rule.onNodeWithTag("hardware-preedit-card", true).assertDoesNotExist()
            rule.onNodeWithTag("hardware-docked-preedit", true).assertDoesNotExist()
            rule.runOnIdle { assertEquals("正文$code", editor.text.toString()) }
        }
        // 实体键盘贴底时拼音画在浮条里（hardware-docked-preedit），不贴底时是浮卡（用户 11-20）。
        fun assertHardwarePreeditShown() = org.junit.Assert.assertTrue("实体键盘拼音要显示",
            rule.onAllNodesWithTag("hardware-preedit-card", true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("hardware-docked-preedit", true).fetchSemanticsNodes().isNotEmpty())
        location(SettingsPreferences.INPUT_TEXT_INPUT_BOX)
        assertCodeInEditor()
        rule.onNodeWithText("后院").assertExists()
        shot("editor")
        rule.runOnIdle {
            service.showInlineEditCaret(edit)
            assertEquals("正文".length + 3, editor.selectionStart)
            assertEquals("正文$code", editor.text.toString())
            service.syncInlinePinyinLocation()
            assertEquals("刷新候选不能把正在编辑的光标拉回末尾", "正文".length + 3, editor.selectionStart)
        }
        location(SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
        // 默认照原来浮气泡；选了「紧贴候选上方」后拼音是贴在键盘边框正上方的小签（11-20），不浮气泡、不进键盘。
        rule.onNodeWithTag("candidate-preedit", true).assertExists()
        rule.onNodeWithTag("preedit-tab", true).assertDoesNotExist()
        rule.runOnIdle { SettingsPreferences.setPreeditStyle(app, SettingsPreferences.PREEDIT_STYLE_LINE) }
        rule.onNodeWithTag("preedit-tab-text", true).assertExists()
        rule.onNodeWithTag("candidate-preedit", true).assertDoesNotExist()
        rule.onNodeWithTag("inline-preedit", true).assertDoesNotExist()
        run {
            // 小签、编辑条都是单独的弹窗：自己的 boundsInWindow 是弹窗里的坐标，用它们在键盘窗口里的位置比。
            val tab = rule.onNodeWithTag("preedit-tab", true).fetchSemanticsNode()
                .config[com.kingzcheung.xime.ui.keyboard.PreeditPopupBoundsKey].let { androidx.compose.ui.geometry.Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            val keyboard = rule.onNodeWithTag("keyboard", true).fetchSemanticsNode().boundsInWindow
            val slack = with(rule.density) { 8.dp.toPx() }
            org.junit.Assert.assertTrue("小签在键盘外面（上方）", tab.top < keyboard.top)
            org.junit.Assert.assertTrue("小签底边贴着键盘顶，不隔开", tab.bottom >= keyboard.top - 1f && tab.bottom <= keyboard.top + slack)
            org.junit.Assert.assertTrue("小签高到能点（≥26dp）", tab.height >= with(rule.density) { 26.dp.toPx() } - 1f)
            assertEquals("默认靠左、贴死键盘左边缘（11-21）", keyboard.left, tab.left, 1f)
        }
        shot("candidate-tab")
        // 点开编辑：和气泡点开的是同一条原来的编辑条（11-21 他要求恢复原样、不缩小、不居中），浮在键盘上方，不跑进候选栏。
        rule.onNodeWithTag("preedit-tab", true).performClick()
        run {
            val code = rule.onNodeWithTag("preedit-editor", true).fetchSemanticsNode()
                .config[com.kingzcheung.xime.ui.keyboard.PreeditPopupBoundsKey]
            val keyboard = rule.onNodeWithTag("keyboard", true).fetchSemanticsNode().boundsInWindow
            org.junit.Assert.assertTrue("编辑条浮在键盘上方", code.bottom <= keyboard.top + 1f)
            org.junit.Assert.assertTrue("编辑条是原来的 44dp 高，不缩小",
                code.height >= with(rule.density) { 44.dp.toPx() } - 1f)
            org.junit.Assert.assertTrue("编辑条和原来一样从左边起、铺满宽度，不居中",
                code.width >= keyboard.width - with(rule.density) { 2.dp.toPx() })
        }
        shot("candidate-tab-edit")
        rule.onNodeWithTag("preedit-editor-close", true).performClick()
        rule.runOnIdle { edits = 0 }
        rule.runOnIdle { SettingsPreferences.setPreeditStyle(app, SettingsPreferences.PREEDIT_STYLE_BUBBLE) }
        rule.onNodeWithTag("candidate-preedit", true).assertExists()
        rule.onNodeWithTag("preedit-tab", true).assertDoesNotExist()
        rule.runOnIdle { assertEquals("正文", editor.text.toString()) }
        // 「候选栏左侧」去掉了（11-20）：气泡点开编辑，编辑条浮在键盘上方原处。
        rule.onNodeWithTag("candidate-preedit", true).performClick()
        assertEquals(1, edits)
        rule.onNodeWithTag("candidate-preedit", true).assertDoesNotExist()
        rule.onNodeWithTag("inline-preedit", true).assertDoesNotExist()
        val bounds = rule.onNodeWithTag("preedit-editor", true).fetchSemanticsNode()
            .config[com.kingzcheung.xime.ui.keyboard.PreeditPopupBoundsKey]
        val keyboard = rule.onNodeWithTag("keyboard", true).fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertTrue("气泡的编辑条浮在键盘上方", bounds.bottom <= keyboard.top + 1f)
        shot("candidate")
        location(SettingsPreferences.INPUT_TEXT_INPUT_BOX)
        assertCodeInEditor()
        rule.runOnIdle { hardware = true }
        assertCodeInEditor()
        rule.onNodeWithTag("hardware-candidate-card", true).assertExists()
        location(SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
        assertHardwarePreeditShown()
        rule.runOnIdle { assertEquals("正文", editor.text.toString()) }
        try {
            rule.runOnIdle { TerminalEditor.active = true }
            location(SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            assertHardwarePreeditShown()
            rule.runOnIdle { assertEquals("正文", editor.text.toString()) }
        } finally {
            rule.runOnIdle { TerminalEditor.active = false }
        }
        rule.runOnIdle { hardware = false }
        location(SettingsPreferences.INPUT_TEXT_INPUT_BOX)
        // 显示在输入框：编码只在输入框里，候选栏上方不另浮气泡。
        rule.onNodeWithTag("candidate-preedit", true).assertDoesNotExist()
        rule.onNodeWithText("后院").performClick()
        rule.runOnIdle { assertEquals("正文后院", editor.text.toString()) }
    }

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out, "preedit-location-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
