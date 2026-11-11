package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.CyMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 统一过渡动效第一批：长按气泡和按键气泡（用户 11-11：对比 Gboard 很多突变，要过渡，但不能拖慢超高速打字）。
 * 验两件事：画面上确实有过渡；过渡期间点按照常立即生效，不等动画、不被吞。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- 动效参数 ----

    @Test fun motionSpecsAreShortAndOnlySlightlyBouncy() {
        // 弹簧：阻尼比 0.7 回弹约 5%，0.8 约 2%；刚度够大，约 150 ms 内基本到位。
        assertTrue(CyMotion.MOVE_DAMPING in 0.65f..0.85f)
        assertTrue(CyMotion.POP_DAMPING in CyMotion.MOVE_DAMPING..0.9f)
        assertTrue(CyMotion.MOVE_STIFFNESS >= 700f && CyMotion.POP_STIFFNESS >= 700f)
        // 松手淡出比出现还短，不拖尾。
        assertTrue(CyMotion.EXIT_FAST in 1..CyMotion.SHORT)
    }

    // ---- 气泡画法 ----

    private val bubbleBg = Color(0xff37b9bf)

    private fun longPressData(index: Int): BubbleDrawData = measureSwipeBubble(
        SwipeState(isPressed = true, isLongPress = true, longPressItems = listOf(" ", " ", " "),
            selectedLongPressIndex = index),
        Rect(180f, 120f, 220f, 170f), bubbleBg, Color.White, Color.White, 40f, 400f, Density(1f), 56f,
        keyLabelTypeface = Typeface.DEFAULT,
    )!!.copy(boxLeft = 0f, boxTop = 10f)

    private fun paint(data: BubbleDrawData): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        paintSwipeBubble(Canvas(bitmap), data)
        return bitmap
    }

    /** 选项条底色的左右边界（列里有底色像素就算）。 */
    private fun bodyColumns(bitmap: Bitmap): IntRange {
        val bg = bubbleBg.toArgb()
        val columns = (0 until bitmap.width).filter { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == bg } }
        return columns.first()..columns.last()
    }

    /** 高亮圆片（比底色亮的像素）的横向重心；选项都是空格，亮的只有高亮。 */
    private fun highlightCenter(bitmap: Bitmap): Double {
        val bg = bubbleBg.toArgb()
        var sum = 0.0
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(p) == 255 && android.graphics.Color.red(p) > android.graphics.Color.red(bg) + 20) {
                sum += x; count++
            }
        }
        assertTrue("没画出高亮", count > 20)
        return sum / count
    }

    @Test fun longPressChoicesGrowOutOfTheKeyBubble() {
        val data = longPressData(0)
        val closed = bodyColumns(paint(data.copy(reveal = 0f)))
        val half = bodyColumns(paint(data.copy(reveal = 0.5f)))
        val open = bodyColumns(paint(data))
        val closedWidth = closed.last - closed.first
        val openWidth = open.last - open.first
        // 刚到点时是按键上方的圆气泡，宽度约等于气泡高度，正对着按键。
        assertEquals(data.bodyHeightPx.toDouble(), closedWidth.toDouble(), 3.0)
        assertEquals((data.pointerLeftInBox + data.keyWidthPx / 2f).toDouble(), (closed.first + closed.last) / 2.0, 3.0)
        assertTrue("展开一半应在中间 ($closedWidth < ${half.last - half.first} < $openWidth)",
            half.last - half.first in (closedWidth + 5) until (openWidth - 5))
        assertEquals(data.pathBodyWidth.toDouble(), openWidth.toDouble(), 3.0)
    }

    @Test fun highlightSlidesBetweenChoices() {
        val first = highlightCenter(paint(longPressData(0)))
        val second = highlightCenter(paint(longPressData(1)))
        val between = highlightCenter(paint(longPressData(0).copy(selectedPosition = 0.5f)))
        assertTrue(second > first + 10)
        // 动画中途的高亮在两项正中间，不是一下跳过去。
        assertEquals((first + second) / 2, between, 2.0)
    }

    @Test fun restingFrameIsTheSelectedItemAndFullyTransparentDrawsNothing() {
        // 静止时高亮就在所选项上：动画值缺省等于选中项，静止画面与原来一致。
        assertEquals(1f, longPressData(1).selectedPosition)
        assertEquals(1f, longPressData(1).reveal)
        assertEquals(1f, longPressData(1).alpha)
        val blank = paint(longPressData(1).copy(alpha = 0f))
        assertFalse((0 until blank.width).any { x -> (0 until blank.height).any { y -> blank.getPixel(x, y) != 0 } })
    }

    // ---- 按键气泡：只有松手停留走完才淡出 ----

    @Test fun onlyTheReleaseTimeoutCountsAsAFadeOut() = runTest {
        val controller = SwipeBubbleController(this, showPressBubble = true)
        val bounds = Rect(10f, 20f, 70f, 80f)
        val cleared = mutableListOf<Boolean>()
        controller.onPreviewChanged = { state, _ -> if (state == SwipeState()) cleared += controller.clearedAfterRelease }
        controller.update(SwipeState(isPressed = true, pressedText = "q"), bounds)
        controller.update(SwipeState(), bounds)
        advanceTimeBy(100)
        runCurrent()
        // 拂动要立即清掉，不淡出。
        controller.update(SwipeState(isPressed = true, pressedText = "q"), bounds)
        controller.update(SwipeState(keyFlick = KeyFlickPreview("1", 0.2f, true)), bounds)
        assertEquals(listOf(true, false), cleared)
        assertFalse(controller.clearedAfterRelease)
        controller.dispose()
    }

    // ---- 长按选项窗口：松手淡出，淡出中照常打字 ----

    private lateinit var controller: SwipeBubbleController
    private val typed = StringBuilder()
    private val keyA = Rect(40f, 120f, 80f, 170f)
    private val keyB = Rect(200f, 120f, 240f, 170f)

    private fun showChoices() {
        KeysConfigHelper.loadConfig(rule.activity)
        AppFonts.initialize(rule.activity)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(showPressBubble = false)) {
                Box(Modifier.requiredSize(400.dp, 200.dp)) {
                    controller = rememberSwipeBubbleController()
                    KeyboardSwipeBubbleOverlay(controller, bubbleBg, Color.White, Color.White, 400f)
                    Row(Modifier.fillMaxWidth()) {
                        KeyButton("b", { typed.append("b") }, Color.DarkGray, Color.White,
                            Modifier.size(60.dp, 50.dp).testTag("motion-key:b"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun choose(bounds: Rect, index: Int) = rule.runOnUiThread {
        controller.update(SwipeState(isPressed = true, isLongPress = true, longPressItems = listOf("1", "2", "3"),
            selectedLongPressIndex = index), bounds)
        // 测试线程外改的状态，暂停时钟时要自己通知，下一帧才会重组（自动推进时由测试规则代办）。
        Snapshot.sendApplyNotifications()
    }

    private fun popupWindows() = rule.onAllNodes(isPopup()).fetchSemanticsNodes().size

    @Test fun choicesFadeOutAfterReleaseAndTheWindowGoesAfterwards() {
        showChoices()
        rule.mainClock.autoAdvance = false
        choose(keyA, 0)
        rule.mainClock.advanceTimeByFrame()
        choose(keyA, 2)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("key-choice-popup", useUnmergedTree = true).assertExists()
        rule.runOnUiThread { controller.update(SwipeState(), keyA); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        // 松手后已不是可选的菜单，但还在淡出。
        rule.onNodeWithTag("key-choice-popup", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, popupWindows())
        rule.mainClock.advanceTimeBy(CyMotion.EXIT_FAST + 100L)
        assertEquals(0, popupWindows())
        rule.mainClock.autoAdvance = true
    }

    @Test fun aNewLongPressDuringTheFadeShowsAtOnceInItsOwnWindow() {
        showChoices()
        rule.mainClock.autoAdvance = false
        choose(keyA, 1)
        rule.mainClock.advanceTimeByFrame()
        rule.runOnUiThread { controller.update(SwipeState(), keyA); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        choose(keyB, 0)
        rule.mainClock.advanceTimeByFrame()
        // 不等上一个淡完：新的立即出现，旧窗口直接换掉，只有一个窗口。
        rule.onNodeWithTag("key-choice-popup", useUnmergedTree = true).assertExists()
        assertEquals(1, popupWindows())
        rule.mainClock.autoAdvance = true
    }

    @Test fun tapsDuringTheFadeTypeImmediately() {
        showChoices()
        rule.mainClock.autoAdvance = false
        choose(keyA, 0)
        rule.mainClock.advanceTimeByFrame()
        rule.runOnUiThread { controller.update(SwipeState(), keyA); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        // 淡出还在走（时钟停着），连点三下都立即上屏，不等淡出。
        assertEquals(1, popupWindows())
        repeat(3) { rule.onNodeWithTag("motion-key:b").performTouchInput { down(center); up() } }
        assertEquals("bbb", typed.toString())
        rule.mainClock.autoAdvance = true
    }
}
