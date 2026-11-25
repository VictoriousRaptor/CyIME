package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutScreen
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config

/**
 * 用户 11-23：开了「按键抬起震动」，九键松手不震，14/26 键正常。九键、笔画、数字键盘的键以前只接了按下（onKeyPressDown），
 * 没接松手（onKeyRelease）。现在每个按下有反馈的键，点一下都要正好一次按下、一次松手；26 键的 Shift、符号键也一样。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h700dp-mdpi")
class KeyReleaseFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val downs = mutableListOf<String>()
    private val ups = mutableListOf<String>()

    private fun show(state: KeyboardLayoutState, schema: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app); KeyboardThemes.reload(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onKeyPressDown = { downs += it }, onKeyRelease = { ups += it })
        rule.setContent {
            XimeTheme(darkTheme = false, themeId = "soft_blue") {
                Box(Modifier.requiredSize(360.dp, 250.dp)) {
                    KeyboardLayoutScreen(keyboardState = state,
                        uiState = KeyboardUiState(currentSchemaId = schema, themeId = "soft_blue"),
                        viewModel = vm, callbacks = callbacks, onKeyPress = {},
                        modifier = Modifier.requiredSize(360.dp, 250.dp), t9Controller = t9)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tag(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    /** 点一下：正好一次按下反馈、一次松手反馈。 */
    private fun assertTapGivesOneReleaseFeedback(name: String, node: SemanticsNodeInteraction) {
        val before = downs.size to ups.size
        node.performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals("$name 按下", 1, downs.size - before.first)
        assertEquals("$name 松手", 1, ups.size - before.second)
    }

    @Test fun everyNineKeyKeyGivesReleaseFeedback() {
        show(KeyboardLayoutState.T9Pinyin, "t9_pinyin")
        assertTapGivesOneReleaseFeedback("ABC", rule.onNodeWithText("ABC", ignoreCase = true, useUnmergedTree = true))
        assertTapGivesOneReleaseFeedback("删除", tag("t9-delete-key"))
        assertTapGivesOneReleaseFeedback("重输", rule.onNodeWithContentDescription("重输", useUnmergedTree = true))
        assertTapGivesOneReleaseFeedback("0", tag("t9-zero-key"))
        assertTapGivesOneReleaseFeedback("确定", tag("t9-enter-key"))
        assertTapGivesOneReleaseFeedback("!@#", tag("mode-slot-1"))
        assertTapGivesOneReleaseFeedback("123", tag("mode-slot-2"))
        assertTapGivesOneReleaseFeedback("左栏符号", tag("t9-pinyin-option:0"))
    }

    @Test fun strokeKeysGiveReleaseFeedback() {
        show(KeyboardLayoutState.Stroke, "stroke")
        assertTapGivesOneReleaseFeedback("删除", tag("stroke-delete-key"))
        assertTapGivesOneReleaseFeedback("0", tag("stroke-zero-key"))
        assertTapGivesOneReleaseFeedback("确定", tag("stroke-enter-key"))
        assertTapGivesOneReleaseFeedback("左栏符号", tag("stroke-side-symbol:0"))
    }

    @Test fun numberKeysGiveReleaseFeedback() {
        show(KeyboardLayoutState.Number, "pinyin")
        assertTapGivesOneReleaseFeedback("删除", tag("number-delete-key"))
        assertTapGivesOneReleaseFeedback("0", tag("number-zero"))
        assertTapGivesOneReleaseFeedback(".", tag("number-dot-key"))
        assertTapGivesOneReleaseFeedback("左栏符号", tag("number-symbol:0"))
    }

    @Test fun qwertyShiftAndModeKeysGiveReleaseFeedbackLikeLetters() {
        show(KeyboardLayoutState.Chinese, "pinyin")
        assertTapGivesOneReleaseFeedback("Shift", tag("shift-key"))
        assertTapGivesOneReleaseFeedback("符号键", tag("mode-slot-1"))
    }
}
