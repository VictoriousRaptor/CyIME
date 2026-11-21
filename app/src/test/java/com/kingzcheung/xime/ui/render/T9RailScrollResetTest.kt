package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config

/**
 * 用户 11-19：九键打 hui'dao'shang'fang，选第一段的词前把左栏拼音拉到底，选完后第二段的拼音还停在下面。
 * 左栏的读音一换就回到第一个；读音没换（只是候选变了）时停在用户拉到的位置。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w440dp-h700dp-hdpi")
class T9RailScrollResetTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun options(vararg pinyin: String) = pinyin.map { T9InputController.SyllableOption(it, it.length) }

    @Test fun railReturnsToTheTopWhenTheReadingsChange() {
        AppFonts.initialize(rule.activity.application)
        val first = options("hui", "gui", "hu", "gu", "hun", "gun", "h", "g", "i")
        val readings = mutableStateOf(first)
        val controller = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.INPUT
            on { firstOptions } doAnswer { readings.value }
        }
        val state = mutableStateOf(CandidateState(inputText = "484", candidates = listOf("回到", "会", "灰"), isComposing = true))
        rule.setContent {
            Column(Modifier.width(384.dp)) {
                T9KeyboardLayout({}, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                    KeyboardUiState(currentSchemaId = "t9_pinyin"), controller, Color.DarkGray, Color.White, Color.Gray,
                    modifier = Modifier.height(260.dp), candidateState = state)
            }
        }
        rule.onNodeWithTag("t9-pinyin-options").performScrollToNode(hasText("i"))
        rule.onNodeWithTag("t9-pinyin-option:0", useUnmergedTree = true).assertDoesNotExist()

        // 只换候选、读音不变：不动。
        rule.runOnIdle { state.value = state.value.copy(candidates = listOf("会", "回到")) }
        rule.waitForIdle()
        rule.onNodeWithTag("t9-pinyin-option:0", useUnmergedTree = true).assertDoesNotExist()

        // 选了第一段，左栏换成下一段的读音：回到第一个。
        rule.runOnIdle { readings.value = options("dao", "fao", "dan", "fan", "da", "fa", "d", "e", "f") }
        rule.waitForIdle()
        rule.onNodeWithTag("t9-pinyin-option:0", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("dao", useUnmergedTree = true).assertIsDisplayed()
    }
}
