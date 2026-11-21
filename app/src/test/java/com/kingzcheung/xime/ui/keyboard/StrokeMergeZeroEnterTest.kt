package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.settings.strokeMergeZeroSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 笔画键盘「去掉 0 键，回车加高」（用户 11-17 转来，默认关，和九键同款）：
 * 关着时右边一列照旧是 删除 / 重输 / 0 / 确定；开了只剩 删除 / 重输 / 占两格的确定，往角标方向滑打 0。
 * 另外默认主键区：* 在第二行末尾（角标 6），分词在第三行开头（角标 7），点按照旧。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xhdpi")
class StrokeMergeZeroEnterTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()

    private fun show(prefs: KeyboardInputPreferences) {
        AppFonts.initialize(rule.activity.application)
        KeysConfigHelper.loadConfig(rule.activity.application)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides prefs) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 260.dp)) {
                        StrokeKeyboardLayout({ keys += it }, Color.DarkGray, Color.White, Color.Gray,
                            modifier = Modifier.requiredSize(360.dp, 260.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, true).performTouchInput { down(center); up() }
        rule.waitForIdle()
    }

    private fun pullEnter(down: Boolean = true) {
        rule.onNodeWithTag("stroke-enter-key", true).performTouchInput {
            down(center)
            val dy = (if (down) 1f else -1f) * 12.dp.toPx()
            for (step in 1..8) moveTo(center + Offset(0f, dy * step))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun defaultKeepsZeroKeyAndTheUsualLayout() {
        show(KeyboardInputPreferences())
        assertTrue("默认还有 0 键", exists("stroke-zero-key"))
        assertTrue("默认确定和删除一样高", abs(bounds("stroke-enter-key").height - bounds("stroke-delete-key").height) < 2f)
        // * 在 乛 右边（第二行），分词在第三行开头。
        val star = bounds("stroke-key:*")
        val separator = bounds("stroke-key:分词")
        assertTrue(abs(star.top - bounds("stroke-key:乛").top) < 2f && star.left > bounds("stroke-key:乛").left)
        assertTrue(separator.top > star.top && abs(separator.left - bounds("stroke-key:丶").left) < 2f)
        tap("stroke-key:*"); tap("stroke-key:分词"); tap("stroke-key:一"); tap("stroke-zero-key")
        assertEquals(listOf("*", "word_separator", "h", "0"), keys)
    }

    @Test fun mergedRemovesZeroKeyAndEnterTakesTwoRows() {
        show(KeyboardInputPreferences(strokeMergeZeroIntoEnter = true))
        assertFalse("开了没有 0 键", exists("stroke-zero-key"))
        val enter = bounds("stroke-enter-key")
        val delete = bounds("stroke-delete-key")
        assertTrue("确定约两个删除键高：${enter.height} vs ${delete.height}", abs(enter.height - delete.height * 2) < 4f)
        assertTrue("确定到底", abs(enter.bottom - bounds("stroke-right-rail").bottom) < 2f)
    }

    @Test fun mergedEnterTapIsEnterAndPullTypesZero() {
        show(KeyboardInputPreferences(strokeMergeZeroIntoEnter = true))
        tap("stroke-enter-key")
        assertEquals(listOf("enter"), keys)
        keys.clear()
        pullEnter()
        assertEquals("下拉打 0", listOf("0"), keys)
    }

    @Test fun reversedDirectionTypesZeroOnPushUp() {
        show(KeyboardInputPreferences(strokeMergeZeroIntoEnter = true, reverseSymbolSwipe = true))
        pullEnter(down = false)
        assertEquals("反转后上滑打 0", listOf("0"), keys)
    }

    @Test fun noZeroWhenCornerSymbolInputOff() {
        show(KeyboardInputPreferences(strokeMergeZeroIntoEnter = true, cornerSymbolInput = false))
        pullEnter()
        assertTrue("关了上下滑输入符号不打 0：$keys", "0" !in keys)
    }

    @Test fun subtitleSaysWhereZeroWent() {
        assertTrue(strokeMergeZeroSubtitle(true, false).contains("回车上下滑输入"))
        assertTrue(strokeMergeZeroSubtitle(true, true).contains("回车上上滑输入"))
        assertTrue(strokeMergeZeroSubtitle(false, false).contains("数字键盘"))
    }
}
