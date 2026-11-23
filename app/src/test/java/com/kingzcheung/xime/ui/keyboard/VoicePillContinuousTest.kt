package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 语音悬浮条上的「连续语音」开关（用户 11-22：浮窗里要有连续语音的按钮）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoicePillContinuousTest {
    @get:Rule val rule = createComposeRule()

    private fun setPill(continuous: Boolean?, onChange: (Boolean) -> Unit = {}) {
        rule.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                VoiceCollapsedPill(
                    listening = true, amplitude = { 0f }, languageLabel = "中",
                    languageActions = KeyboardInputActions(), onSwitchLanguage = {}, onShowKeyboard = {},
                    onBackspace = {}, onMic = {},
                    continuous = continuous, onContinuousChange = onChange,
                    backgroundColor = Color.White, contentColor = Color.Black, selectedTextColor = Color.Black,
                    accentColor = Color.Blue, frostedGlass = FrostedGlassConfig.defaults(false, enabled = false),
                    surface = { Modifier }, onBoundsChanged = {},
                )
            }
        }
    }

    @Test fun `tapping the switch flips continuous voice and shows the new state`() {
        val changes = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        rule.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                VoiceCollapsedPill(
                    listening = true, amplitude = { 0f }, languageLabel = "中",
                    languageActions = KeyboardInputActions(), onSwitchLanguage = {}, onShowKeyboard = {},
                    onBackspace = {}, onMic = {},
                    continuous = on, onContinuousChange = { changes += it; on = it },
                    backgroundColor = Color.White, contentColor = Color.Black, selectedTextColor = Color.Black,
                    accentColor = Color.Blue, frostedGlass = FrostedGlassConfig.defaults(false, enabled = false),
                    surface = { Modifier }, onBoundsChanged = {},
                )
            }
        }
        rule.onNodeWithTag("voice-pill-continuous").assertIsNotSelected().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("voice-pill-continuous").assertIsSelected().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("voice-pill-continuous").assertIsNotSelected()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `no switch when the host does not offer one`() {
        setPill(continuous = null)
        rule.onNodeWithTag("voice-pill-continuous").assertDoesNotExist()
        rule.onNodeWithTag("voice-pill-mic").assertExists()
    }
}
