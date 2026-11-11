package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 11-11：滑行锁成「长按输入」后字母键只能长按出上角标，下滑用来滑词，下角标打不出来。
 * 设置页跟着写清：长按卡不再画下角标、不列下滑那一行，锁定说明写明下角标不显示，下方角标开关下注明；
 * 「下方角标提示」存的值不动（14 键等没滑行的键还听它的），关掉滑行全部恢复。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w420dp-h1000dp-xhdpi")
class GlideLowerCornerSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val longPressCard = hasAnyAncestor(hasTestTag("corner-input:LONG_PRESS"))

    private fun count(text: String, substring: Boolean = false, inLongPressCard: Boolean = false) =
        rule.onAllNodes(if (inLongPressCard) longPressCard and hasText(text, substring) else hasText(text, substring), true)

    @Test fun glideLockDropsTheLowerCornerFromTheLongPressCardAndKeepsTheStoredSwitch() {
        AppFonts.initialize(rule.activity)
        SettingsPreferences.setSwipeUpHintsEnabled(rule.activity, true)
        SettingsPreferences.setSwipeDownHintsEnabled(rule.activity, true)
        // 他的设置：反转上下滑方向开着，下角标原来是「↓下滑 → 1」。
        KeyboardInputPreferences(glideTyping = true, reverseSymbolSwipe = true).save(rule.activity)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.requiredSize(400.dp, 1600.dp)) {
                    LayoutDisplaySettingsContent(onBack = {}, initialPage = "gestures")
                }
            }
        }
        rule.waitForIdle()
        count("长按", inLongPressCard = true).assertCountEquals(1)
        count("@", inLongPressCard = true).assertCountEquals(2)
        count("↓下滑", inLongPressCard = true).assertCountEquals(0)
        count("1", inLongPressCard = true).assertCountEquals(0)
        count("只出上角标，下角标不再显示", substring = true).assertCountEquals(1)
        count("滑行时字母键下滑用来滑词，不显示下角标", substring = true).assertCountEquals(1)
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsOn()

        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(glideTyping = false).save(rule.activity) }
        rule.waitForIdle()
        count("↓下滑", inLongPressCard = true).assertCountEquals(1)
        count("1", inLongPressCard = true).assertCountEquals(2)
        count("不显示下角标", substring = true).assertCountEquals(0)
        count("下角标不再显示", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("settings-search-anchor:下方角标提示").assertIsOn()
        assertTrue("存的开关没被改", SettingsPreferences.isSwipeDownHintsEnabled(rule.activity))
    }
}
