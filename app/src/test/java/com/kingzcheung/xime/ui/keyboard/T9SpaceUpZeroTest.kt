package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config

/**
 * 九键「空格上滑输入 0」（用户 11-22，默认关）：
 * 关着时空格上滑什么都不做、0 键照旧点一下打 0；开了没在打字时空格上滑打 0，打字时不接；
 * 0 键一直是「→」，没有候选时点它光标右移、左滑左移。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class T9SpaceUpZeroTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()
    private val moves = mutableListOf<Int>()
    private val controller = mock<T9InputController> {
        on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
    }
    private val candidate = mutableStateOf(CandidateState())

    private fun show(prefs: KeyboardInputPreferences) {
        AppFonts.initialize(rule.activity.application)
        KeysConfigHelper.loadConfig(rule.activity.application)
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides prefs,
                LocalKeyboardInputActions provides KeyboardInputActions(onCursorMove = { moves += it })) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 260.dp)) {
                        T9KeyboardLayout({ keys += it }, callbacks, KeyboardUiState(currentSchemaId = "t9_pinyin"),
                            controller, Color.DarkGray, Color.White, Color.Gray,
                            modifier = Modifier.requiredSize(360.dp, 260.dp), candidateState = candidate)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** 在空格上往上滑（[sideways] 每步往旁边多走多少 dp）。 */
    private fun swipeSpaceUp(sideways: Float = 0f) {
        rule.onNodeWithTag("space-key", true).performTouchInput {
            down(center)
            for (step in 1..6) moveTo(center + Offset(sideways.dp.toPx() * step, -8.dp.toPx() * step))
            up()
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, true).performTouchInput { down(center); up() }
        rule.waitForIdle()
    }

    @Test fun offByDefaultSwipeUpDoesNothingAndZeroKeyTypesZero() {
        show(KeyboardInputPreferences())
        swipeSpaceUp()
        assertTrue("默认空格上滑什么都不做：$keys", keys.none { it == "0" || it == "space" })
        tap("t9-zero-key")
        assertTrue("默认 0 键点一下打 0：$keys", "0" in keys)
        assertTrue(moves.isEmpty())
    }

    @Test fun onSwipeUpTypesZeroAndTapIsStillSpace() {
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true))
        swipeSpaceUp()
        assertEquals(listOf("0"), keys.filter { it == "0" || it == "space" })
        keys.clear()
        tap("space-key")
        assertTrue("点空格照旧是空格：$keys", "space" in keys && "0" !in keys)
    }

    @Test fun mostlySidewaysIsNotSwipeUp() {
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true))
        swipeSpaceUp(sideways = 12f)
        assertTrue("往旁边多于往上不打 0：$keys", "0" !in keys)
    }

    @Test fun noZeroWhileTyping() {
        candidate.value = CandidateState(candidates = listOf("你好"), inputText = "64", preeditText = "ni", isComposing = true)
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true))
        swipeSpaceUp()
        assertTrue("打字时空格上滑不打 0：$keys", "0" !in keys)
    }

    @Test fun zeroKeyIsArrowThatMovesCursorWhenIdle() {
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true))
        tap("t9-zero-key")
        assertTrue("开了以后 0 键不再打 0：$keys", "0" !in keys)
        assertEquals(listOf(1), moves)
        rule.onNodeWithTag("t9-zero-key", true).performTouchInput {
            down(center)
            // 横滑门槛 50dp（SwipeableKeyButton）：拖 64dp 才算左滑，48dp 只是点按。
            for (step in 1..8) moveTo(center + Offset(-8.dp.toPx() * step, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals("左滑光标左移", listOf(1, -1), moves)
    }

    /**
     * 11-25 他：方向要和数字键一样、跟「底排标点固定上滑」。关了这个开关时跟数字键的上角标：没反转就是下拉出 0，上滑不出；
     * 反转了就是上滑。滑到一半看得到 0 从右上角移进键中央（和数字键同一个动画）。
     */
    @Test fun directionFollowsBottomRowAndDigitKeys() {
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true, bottomPunctuationSwipeUp = false))
        swipeSpaceUp()
        assertTrue("底排不固定上滑、没反转：上滑不打 0：$keys", "0" !in keys)
        rule.onNodeWithTag("space-key", true).performTouchInput {
            down(center)
            for (step in 1..6) moveTo(center + Offset(0f, 8.dp.toPx() * step))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("key-flick-preview", true).assertExists()
        // 滑到一半的样子存一张，和数字键滑出数字对照。
        val root = rule.activity.window.decorView
        val frame = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(frame)) }
        java.io.File("build/reports/t9-space-zero-swipe.png").apply { parentFile?.mkdirs() }
            .outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithTag("space-key", true).performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("下拉打 0", listOf("0"), keys.filter { it == "0" || it == "space" })
    }

    @Test fun reversedSwipeMakesItUpAgain() {
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true, bottomPunctuationSwipeUp = false, reverseSymbolSwipe = true))
        swipeSpaceUp()
        assertTrue("反转后上滑出上角标：$keys", "0" in keys)
    }

    /** 去掉了 0 键时空格上滑一样打 0（两处都能打，不冲突）。 */
    @Test fun worksTogetherWithMergedZero() {
        show(KeyboardInputPreferences(t9SpaceSwipeUpZero = true, t9MergeZeroIntoEnter = true))
        swipeSpaceUp()
        assertTrue("$keys", "0" in keys)
    }
}
