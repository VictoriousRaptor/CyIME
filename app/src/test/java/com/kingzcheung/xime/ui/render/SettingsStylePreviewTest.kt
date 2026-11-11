package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.InputUIState
import com.kingzcheung.xime.service.VoiceKeyboardContainer
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.KeyboardPanelSettingsContent
import com.kingzcheung.xime.ui.settings.HardwareKeyboardSettingsContent
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.settings.LayoutInputCategory
import com.kingzcheung.xime.ui.settings.LayoutInputSettingsContent
import com.kingzcheung.xime.ui.settings.KeyEffectSettingsContent
import com.kingzcheung.xime.ui.settings.StylePreviewEditor
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw390dp-w390dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsStylePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @After fun resetWindow() {
        KeyboardPanelLaunch.previewVisible = false
        KeyboardPanelLaunch.typingRequested = false
        KeyboardPanelLaunch.cancel()
    }

    @Test fun previewEditorHasNoBufferAndConsumesKeysAndEditorActions() {
        val editor = StylePreviewEditor(rule.activity)
        val info = EditorInfo()
        val connection = editor.onCreateInputConnection(info)
        info.packageName = rule.activity.packageName
        assertTrue(KeyboardPanelLaunch.isPreview(info, rule.activity.packageName))
        assertFalse(KeyboardPanelLaunch.isPreview(info, "another.app"))
        connection.setComposingText("nihao", 1)
        connection.commitText("你好", 1)
        connection.deleteSurroundingText(1, 0)
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_NEXT))
        assertTrue(connection.performContextMenuAction(android.R.id.paste))
        assertTrue(editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)))
        assertNull(connection.editable)
    }

    @Test fun aPassiveKeyboardConsumesTheWholeTouchWithoutDispatchingToItsChildren() {
        val host = VoiceKeyboardContainer(rule.activity, { InputUIState() }, {}, {}, {}, {}, {}, { false }, {},
            allowPreviewTouch = { false })
        var received = 0
        val key = View(rule.activity).apply { setOnTouchListener { _, _ -> received++; true } }
        host.addView(key, ViewGroup.LayoutParams(100, 100))
        host.layout(0, 0, 100, 100)
        key.layout(0, 0, 100, 100)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 1L, action, 50f, 50f, 0)
            try { assertTrue(host.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        assertEquals(0, received)
    }

    @Test fun aPreviousPageCannotOpenOrCancelTheNewPagesTool() {
        val old = Any()
        val active = Any()
        KeyboardPanelLaunch.request(KeyboardPanelLaunch.Panel.RESIZE, old)
        KeyboardPanelLaunch.request(KeyboardPanelLaunch.Panel.ARRANGE, active)
        KeyboardPanelLaunch.cancel(old)
        assertNull(KeyboardPanelLaunch.take(KeyboardPanelLaunch.Panel.RESIZE))
        assertEquals(KeyboardPanelLaunch.Panel.ARRANGE, KeyboardPanelLaunch.take(KeyboardPanelLaunch.Panel.ARRANGE))
        assertNull(KeyboardPanelLaunch.take())
    }

    @Test fun layoutExamplesKeepACompatibleLayoutAndDoNotSelectAnotherScheme() {
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        val original = com.kingzcheung.xime.settings.SettingsPreferences.getCurrentSchema(app)
        fun example(which: KeyboardPanelLaunch.Example, id: String) = which.sampleLayout(
            com.kingzcheung.xime.settings.InputProfiles.current(id), id)
        assertEquals("pinyin_14jian", example(KeyboardPanelLaunch.Example.Keypad, "pinyin_14jian"))
        assertEquals("t9_pinyin", example(KeyboardPanelLaunch.Example.Keypad, "rime_ice"))
        assertEquals("rime_ice", example(KeyboardPanelLaunch.Example.Letters, "t9_pinyin"))
        assertEquals("double_pinyin_flypy", example(KeyboardPanelLaunch.Example.DoublePinyin, "rime_ice"))
        assertNull(example(KeyboardPanelLaunch.Example.Keyboard, "t9_pinyin"))
        assertEquals(original, com.kingzcheung.xime.settings.SettingsPreferences.getCurrentSchema(app))
    }

    /**
     * 11-03「显示键盘…两边不能站满 应该做成浮动按钮」：按钮浮在列表上，列表铺到按钮两侧和下面；
     * 滚到底时最后一项整个在按钮上方。
     */
    @Test fun showKeyboardFloatsOverTheListAndTheLastSettingClearsIt() {
        val app = rule.activity.application
        Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            app.packageName + "/com.kingzcheung.xime.service.XimeInputMethodService")
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        rule.setContent {
            XimeTheme(darkTheme = false, themeId = "soft_blue") {
                LayoutDisplaySettingsContent(onBack = {}, initialPage = "gestures")
            }
        }
        rule.onNodeWithText("显示键盘").assertIsDisplayed()
        val list = rule.onNode(hasScrollToIndexAction())
        val button = rule.onNodeWithTag("show-keyboard-button").fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        // 列表一直铺到按钮下面，按钮两侧不是单独一栏。
        assertTrue("列表底边 ${listBounds.bottom}，按钮底边 ${button.bottom}", listBounds.bottom > button.bottom)
        assertTrue(button.width < listBounds.width * 0.6f)
        save("gestures-floating-button")
        repeat(30) { list.performTouchInput { swipeUp() } }
        rule.waitForIdle()
        val last = rule.onNodeWithTag("settings-search-anchor:按键技巧入口").fetchSemanticsNode().boundsInRoot
        assertTrue("最后一项底边 ${last.bottom}，按钮上沿 ${button.top}", last.bottom <= button.top)
        save("gestures-end")
    }

    @Test fun sizePageShowsAndHidesTheReadOnlyKeyboard() {
        val app = rule.activity.application
        Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            app.packageName + "/com.kingzcheung.xime.service.XimeInputMethodService")
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> error("preview typed") }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(vm).bindT9Controller(callbacks)
        vm.dispatch(KeyboardDispatchAction.InputSessionStarted(false, "t9_pinyin"))
        var editing by mutableStateOf(false)
        var effects by mutableStateOf(false)
        var letters by mutableStateOf(false)
        rule.setContent {
            XimeTheme(darkTheme = true, themeId = "pure_black") {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (letters) LayoutInputSettingsContent(LayoutInputCategory.Qwerty, onBack = {})
                        else if (effects) KeyEffectSettingsContent {} else KeyboardPanelSettingsContent(KeyboardPanelLaunch.Panel.RESIZE) {}
                    }
                    // 电脑上的系统不安装 IME。用生产键面绘制窗口，显示信号由测试代替 Android 分发。
                    val current = KeyboardUiState(currentSchemaId = "t9_pinyin")
                    val sample = if (letters) KeyboardPanelLaunch.Example.Letters.sampleLayout(current.inputProfile, current.currentSchemaId) else null
                    if (KeyboardPanelLaunch.previewVisible) KeyboardView(vm, KeyboardUiState(currentSchemaId = sample ?: current.currentSchemaId,
                        themeId = "pure_black", isDarkTheme = true, keyboardHeightDp = 276), callbacks,
                        previewLetterLayout = letters, inputSessionManagedByHost = true, modifier = Modifier.fillMaxWidth().height(276.dp),
                        resizeOverlay = if (!editing) null else ({
                            KeyboardResizeOverlay(276, 276, 0, false, onHeightChange = {}, onBottomPaddingChange = {},
                                onOpacityChange = {}, onReset = {}, onConfirm = { _, _, _, _ -> }, onCancel = {},
                                onFloatingModeChange = {}, onSplitKeyboardChange = {}, onPreviewControlsPositioned = {})
                        }))
                }
            }
        }
        val root = rule.activity.window.decorView as ViewGroup
        val editor = root.descendants.filterIsInstance<StylePreviewEditor>().single()
        rule.runOnIdle {
            assertFalse("entering the page must not pop up the keyboard", editor.isFocused)
            assertNull(KeyboardPanelLaunch.take())
        }
        rule.onNodeWithText("显示键盘").assertIsDisplayed()
        rule.runOnIdle { KeyboardPanelLaunch.previewVisible = true }
        rule.onNodeWithText("收起键盘").assertIsDisplayed()
        save("size-open")
        rule.onNodeWithTag("keyboard-resize-frame", true).assertDoesNotExist()
        rule.onNodeWithText("调整尺寸").performClick()
        rule.runOnIdle { assertEquals(KeyboardPanelLaunch.Panel.RESIZE, KeyboardPanelLaunch.take()); editing = true }
        rule.onNodeWithTag("keyboard-resize-frame", true).assertExists()
        save("size-edit")
        rule.onNodeWithText("收起键盘").performClick()
        rule.runOnIdle {
            assertFalse(editor.isFocused)
            KeyboardPanelLaunch.previewVisible = false
            editing = false
        }
        rule.onNodeWithText("显示键盘").assertIsDisplayed()
        save("size-closed")
        // 「显示键盘」直接打开试打框：焦点在真实输入框上，键盘能打字；只读预览连接让出焦点。
        rule.onNodeWithText("显示键盘").performClick()
        rule.onNodeWithTag("typing-trial").assertExists()
        rule.runOnIdle {
            val field = root.descendants.filterIsInstance<android.widget.EditText>().single { it.tag == "typing-trial-field" }
            assertTrue(field.isFocused)
            assertFalse(editor.isFocused)
            assertFalse(KeyboardPanelLaunch.isPreview(EditorInfo().also { field.onCreateInputConnection(it); it.packageName = app.packageName }, app.packageName))
            assertNull(KeyboardPanelLaunch.take())
        }
        save("typing-trial")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("typing-trial").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.runOnIdle { effects = true; KeyboardPanelLaunch.previewVisible = true }
        rule.onNodeWithText("按键效果").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-resize-frame", true).assertDoesNotExist()
        save("key-effects")
        rule.runOnIdle { letters = true }
        rule.onNodeWithText("26 键").assertIsDisplayed()
        rule.onNodeWithTag("qwerty-key:q", true).assertExists()
        rule.runOnIdle { assertEquals(KeyboardLayoutState.T9Pinyin, vm.keyboardState.value) }
        save("letters-from-keypad")
    }

    @Test fun tappingTheReadOnlyPreviewOpensTheTypingTrial() {
        val app = rule.activity.application
        Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            app.packageName + "/com.kingzcheung.xime.service.XimeInputMethodService")
        rule.setContent { XimeTheme { KeyEffectSettingsContent {} } }
        val root = rule.activity.window.decorView as ViewGroup
        val editor = root.descendants.filterIsInstance<StylePreviewEditor>().single()
        rule.runOnIdle { assertFalse(editor.isFocused); KeyboardPanelLaunch.previewVisible = true }
        rule.onNodeWithTag("typing-trial").assertDoesNotExist()
        // 输入法在只读预览上收到按下时登记这一下。
        rule.runOnIdle { KeyboardPanelLaunch.typingRequested = true }
        rule.onNodeWithTag("typing-trial").assertExists()
        rule.runOnIdle {
            assertFalse(KeyboardPanelLaunch.typingRequested)
            val field = root.descendants.filterIsInstance<android.widget.EditText>().single { it.tag == "typing-trial-field" }
            assertTrue(field.isFocused)
            assertFalse(editor.isFocused)
            assertFalse(editor.isFocusable)
        }
        // 纯效果展示：没有标题、清空、完成这些控件，文字直接显示。
        listOf("试打", "清空", "完成").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.runOnIdle {
            root.descendants.filterIsInstance<android.widget.EditText>().single { it.tag == "typing-trial-field" }.setText("测试一下")
        }
        save("typing-trial-text")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("typing-trial").assertDoesNotExist()
        // 关闭即清空：再打开是空的。
        rule.runOnIdle { KeyboardPanelLaunch.typingRequested = true }
        rule.onNodeWithTag("typing-trial").assertExists()
        rule.runOnIdle {
            val field = root.descendants.filterIsInstance<android.widget.EditText>().single { it.tag == "typing-trial-field" }
            assertEquals("", field.text.toString())
        }
        // 键盘开着时按返回键：输入法之前先关闭，按一次就够。
        rule.runOnIdle {
            val field = root.descendants.filterIsInstance<android.widget.EditText>().single { it.tag == "typing-trial-field" }
            field.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            field.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.onNodeWithTag("typing-trial").assertDoesNotExist()
    }

    @Test fun onlyAnExplicitEditButtonRequestsAToolAcrossSettingsPages() {
        val app = rule.activity.application
        Settings.Secure.putString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            app.packageName + "/com.kingzcheung.xime.service.XimeInputMethodService")
        var page by mutableStateOf("layout")
        rule.setContent {
            XimeTheme { key(page) {
                when (page) {
                    "arrange" -> KeyboardPanelSettingsContent(KeyboardPanelLaunch.Panel.ARRANGE) {}
                    "hardware" -> HardwareKeyboardSettingsContent {}
                    else -> LayoutDisplaySettingsContent(onBack = {}, initialPage = page.takeUnless { it == "layout" })
                }
            } }
        }
        val root = rule.activity.window.decorView as ViewGroup
        listOf("layout", "key_tips", "candidates", "hardware", "arrange").forEach { target ->
            rule.runOnIdle { page = target }
            rule.runOnIdle {
                val editor = root.descendants.filterIsInstance<StylePreviewEditor>().single()
                // 进页面不弹键盘，也不请求任何面板。
                assertFalse(editor.isFocused)
                assertNull(KeyboardPanelLaunch.take())
            }
        }
        rule.onNodeWithText("编辑排列").performClick()
        rule.runOnIdle { assertEquals(KeyboardPanelLaunch.Panel.ARRANGE, KeyboardPanelLaunch.take()) }
    }

    private fun save(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
