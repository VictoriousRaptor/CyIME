package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 用户 11-19：自造词的编辑角标要一个开关。默认显示（同以前），在设置里关掉后键盘马上不画。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LearnedMarkSwitchTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun shownByDefaultAndFollowsTheSwitchAtOnce() {
        val context = rule.activity
        assertTrue(SettingsPreferences.showLearnedMark(context))
        rule.setContent { Text(if (rememberLearnedMarkVisible()) "mark on" else "mark off") }
        rule.onNodeWithText("mark on").assertExists()
        rule.runOnIdle { SettingsPreferences.setShowLearnedMark(context, false) }
        rule.waitForIdle()
        rule.onNodeWithText("mark off").assertExists()
        rule.runOnIdle { SettingsPreferences.setShowLearnedMark(context, true) }
        rule.waitForIdle()
        rule.onNodeWithText("mark on").assertExists()
    }
}
