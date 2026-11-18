package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 11-15「数字行角标就不用显示了，只在长按模式下直接弹出菜单 1/2 1/3 1/4 …… 1/8」：
 * 数字行键上不画 ! @ # 角标；数字键长按弹出 ½ 到 ⅛，松手输入选中的那个；点按照旧输入数字。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h800dp-mdpi")
class NumberRowLongPressTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val typed = mutableListOf<String>()
    private val menus = mutableListOf<List<String>>()

    private fun show() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(numberRow = true)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 60.dp)) {
                        NumberKeyRow(
                            keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "@"),
                            splitAt = -1, splitGap = 0.dp,
                            keyBackgroundColor = Color.White, keyTextColor = Color.Black,
                            shadowEnabled = false, shadowElevation = 0.dp, shadowShapeRadius = 0.dp,
                            onInput = { typed += it },
                            onKeyPressDown = null, onKeyRelease = null,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onSwipeStateChange = { state, _ -> if (state.isLongPress) menus += state.longPressItems },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun `digit keys pop the fraction menu and only those`() {
        assertEquals(listOf("½", "⅓", "¼", "⅕", "⅙", "⅐", "⅛"), NUMBER_ROW_FRACTIONS)
        for (digit in "1234567890") assertEquals(NUMBER_ROW_FRACTIONS, numberRowLongPressItems(digit.toString()))
        // 自己加进数字行的符号、短语不弹。
        assertNull(numberRowLongPressItems("@"))
        assertNull(numberRowLongPressItems("，"))
    }

    @Test fun `number row draws no corner symbols`() {
        show()
        for (symbol in "!@#$%^&*()".map(Char::toString).filter { it != "@" })
            assertEquals("数字行不画 $symbol", 0, rule.onAllNodesWithText(symbol, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `long press enters a fraction and a tap still enters the digit`() {
        show()
        rule.onNodeWithTag("number-row-key:2", useUnmergedTree = true).performTouchInput { longClick(durationMillis = 900L) }
        rule.waitForIdle()
        assertTrue("弹出了分数菜单 $menus", menus.any { it == NUMBER_ROW_FRACTIONS })
        assertEquals(1, typed.size)
        assertTrue("长按输入的是分数：$typed", typed.single() in NUMBER_ROW_FRACTIONS)
        typed.clear()
        rule.onNodeWithTag("number-row-key:2", useUnmergedTree = true).performTouchInput { down(center); advanceEventTime(60L); up() }
        rule.waitForIdle()
        assertEquals(listOf("3"), typed)
    }
}
