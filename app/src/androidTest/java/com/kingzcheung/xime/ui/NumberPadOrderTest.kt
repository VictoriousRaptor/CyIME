package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 「数字位置」：数字键盘和假名九键数字页，选 7 8 9 在上时 7 在 1 的上面，默认反过来；0 不动。 */
class NumberPadOrderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun numberPadFollowsTheSetting() {
        KeysConfigHelper.loadConfig(ApplicationProvider.getApplicationContext())
        var top789 by mutableStateOf(false)
        var kana by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(numberPadTop789 = top789)) {
                MaterialTheme {
                    NumberKeyboardLayout({}, Color.Gray, Color.White, Color.DarkGray,
                        modifier = Modifier.requiredSize(360.dp, 260.dp), isJapaneseKana = kana)
                }
            }
        }
        fun top(tag: String) = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot.top
        for (isKana in listOf(false, true)) for (flip in listOf(false, true)) {
            rule.runOnIdle { kana = isKana; top789 = flip }
            val prefix = if (isKana) "kana-digit:" else "number-digit:"
            val one = top("${prefix}1"); val seven = top("${prefix}7")
            val zero = top(if (isKana) "kana-digit:0" else "number-zero")
            assertTrue("kana=$isKana top789=$flip", if (flip) seven < one else one < seven)
            assertTrue("0 stays on the bottom row", zero > maxOf(one, seven))
        }
    }
}
