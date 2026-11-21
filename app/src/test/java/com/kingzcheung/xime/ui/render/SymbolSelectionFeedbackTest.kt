package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.SymbolKeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SymbolSelectionFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun tappingASymbolGivesOneKeyFeedbackAndCancellingIsSilent() {
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(rule.activity)
        val feedback = mutableListOf<String>()
        val commits = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                SymbolKeyboardLayout(onSelect = { commits += it }, onBack = {}, onNumber = {},
                    onKeyPressDown = { feedback += it }, onHapticFeedback = { feedback += "extra" },
                    backgroundColor = Color.Black, textColor = Color.White, accentColor = Color.Blue,
                    keyBgColor = Color.Gray, modifier = Modifier.size(360.dp, 260.dp))
            }
        }
        val key = rule.onNodeWithTag("symbol-key:，", useUnmergedTree = true)
        key.performTouchInput { down(center); cancel() }
        assertEquals(emptyList<String>(), feedback)
        key.performTouchInput { down(center); up() }
        assertEquals(listOf("，"), feedback)
        assertEquals(listOf("，"), commits)
    }

    /** 11-21「符号页删除键没有按键声音」：删除键同 26 键删除键，按下就有按键音和振动，长按每一下都有。 */
    @Test fun deleteKeyGivesKeyFeedbackOnPressLikeTheLetterKeyboard() {
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(rule.activity)
        val feedback = mutableListOf<String>()
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                SymbolKeyboardLayout(onSelect = { actions += it }, onBack = {}, onNumber = {},
                    onKeyPressDown = { feedback += it }, onKeyRelease = { feedback += "up:$it" },
                    onHapticFeedback = { feedback += "extra" },
                    backgroundColor = Color.Black, textColor = Color.White, accentColor = Color.Blue,
                    keyBgColor = Color.Gray, modifier = Modifier.size(360.dp, 260.dp))
            }
        }
        rule.mainClock.autoAdvance = false
        val delete = rule.onNodeWithTag("symbol-delete", useUnmergedTree = true)
        delete.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(listOf("delete"), feedback)
        delete.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(listOf("delete", "up:delete"), feedback)
        assertEquals(listOf("delete"), actions)

        feedback.clear(); actions.clear()
        delete.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(750)
        delete.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertTrue(actions.size > 1)
        assertTrue(feedback.count { it == "delete" } >= actions.size)
        assertTrue("extra" !in feedback)
    }
}
