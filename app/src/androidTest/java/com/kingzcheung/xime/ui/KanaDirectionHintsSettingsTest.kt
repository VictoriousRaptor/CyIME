package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.JapaneseKanaKeyboardLayout
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.settings.InputExperienceSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class KanaDirectionHintsSettingsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun visibleSwitchUpdatesTheKeysAndPersistsWithoutChangingGestureSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 假名方向提示只在开了日语时显示（11-22）。
        withJapaneseOn(context) { kanaSwitch(context) }
    }

    @Test fun hiddenWithoutJapanese() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.getStringSet(com.kingzcheung.xime.settings.LanguagePreferences.KEY, null)?.toSet()
        if (saved != null && "ja" in saved) return
        rule.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { InputExperienceSettings() } } }
        rule.onNodeWithTag("kana-direction-hints").assertDoesNotExist()
        rule.onNodeWithTag("flick-direction-disc").performScrollTo().assertIsDisplayed()
    }

    private fun kanaSwitch(context: android.content.Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val affectedKeys = listOf("kana_direction_hints", "space_hold_action", "cursor_gesture_mode",
            "cursor_hold_seconds", "neighbor_correction", "fourteen_letter_swipe", "fourteen_letter_swipe_dp",
            "symbol_input_mode", "reverse_symbol_swipe", "cursor_step_dp", "key_text_scale",
            "handwriting_pause_seconds_v2", "handwriting_pause_seconds", "fixed_symbols")
        val original = affectedKeys.associateWith { prefs.all[it] }
        try {
            prefs.edit().remove("kana_direction_hints").commit()
            val before = KeyboardInputPreferences.read(context)
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { InputExperienceSettings() }
                        CompositionLocalProvider(LocalKeyboardInputPreferences provides rememberKeyboardInputPreferences()) {
                            JapaneseKanaKeyboardLayout(onKanaAction = {}, onKeyPress = {},
                                keyBackgroundColor = Color.DarkGray, keyTextColor = Color.White,
                                specialKeyBackgroundColor = Color.Gray, shadowEnabled = false,
                                modifier = Modifier.fillMaxWidth().height(220.dp))
                        }
                    }
                }
            }
            val toggle = rule.onNodeWithTag("kana-direction-hints")
            toggle.performScrollTo().assertIsOff()
            rule.onNodeWithTag("kana-key-hint:a:UP", useUnmergedTree = true).assertDoesNotExist()
            toggle.performClick().assertIsOn()
            for (direction in listOf("LEFT", "UP", "RIGHT", "DOWN")) {
                rule.onNodeWithTag("kana-key-hint:a:$direction", useUnmergedTree = true).assertIsDisplayed()
            }
            rule.runOnIdle {
                assertEquals(true, prefs.getBoolean("kana_direction_hints", false))
                assertEquals(before, KeyboardInputPreferences.read(context).copy(kanaDirectionHints = false))
            }
            toggle.performClick().assertIsOff()
            rule.onNodeWithTag("kana-key-hint:a:UP", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            prefs.edit().also { editor -> original.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    else -> editor.remove(key)
                }
            } }.commit()
        }
    }
}
