package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 11-11 荣耀 WIN 用户：1.15.76 起按 Q 输入法闪退，清数据后好，恢复旧备份又闪退。
 *
 * 按键气泡被挪进屏幕边距（最左边的 Q）或按键比气泡宽时，气泡在自己框里的左边 fullLeft 不是 0；
 * 单字气泡是圆的，展开起点和整条一样宽，起点中心的取值范围两头理论上相等，浮点舍入后右界会比左界小一点点，
 * 原来的 coerceIn 遇到空区间抛 IllegalArgumentException，画按键气泡时整个输入法崩掉。
 * 会不会碰上取决于屏幕宽度、密度、键宽和键盘高度这些具体数，所以换了设置（清数据）就好、恢复旧设置又坏。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeftEdgeBubbleTest {
    private val canvas = Canvas(Bitmap.createBitmap(1600, 700, Bitmap.Config.ARGB_8888))

    private fun pressBubble(left: Float, width: Float, keyboardWidth: Float, density: Float,
        keyHeight: Float = 140f, text: String = "q"): BubbleDrawData = measureSwipeBubble(
        SwipeState(isPressed = true, pressedText = text),
        Rect(left, 400f, left + width, 400f + keyHeight), Color.White, Color.Black, Color.Blue,
        Rect(left, 400f, left + width, 400f + keyHeight).width, keyboardWidth, Density(density), 56f,
        keyLabelTypeface = Typeface.DEFAULT,
    )!!

    @Test fun pressingQAtTheLeftEdgeOfA1264PxPhoneDrawsTheBubble() {
        // 1264 px 宽、密度 2.625、键间距 2dp：Q 键 2.625..123.775 px。按原来的算法这里正好是空区间。
        val data = pressBubble(2.625f, 121.15f, 1264f, 2.625f)
        val startWidth = minOf(data.bodyHeightPx, data.pathBodyWidth)
        val fullLeft = data.pathBodyLeft
        val fullRight = fullLeft + data.pathBodyWidth
        assertTrue("这组数要能复现原来的空区间", fullRight - startWidth / 2f < fullLeft + startWidth / 2f)

        paintSwipeBubble(canvas, data)
        paintSwipeBubble(canvas, data.copy(reveal = 0.5f, alpha = 0.5f))
        val center = bubbleRevealStartCenter(data.pointerLeftInBox + data.keyWidthPx / 2f, fullLeft, fullRight, startWidth)
        assertEquals(fullLeft + startWidth / 2f, center, 0.01f)
    }

    @Test fun pressBubbleNeverThrowsForEdgeOrMiddleKeysAcrossPhonesAndTablets() {
        for (keyboardWidth in listOf(720f, 1080f, 1200f, 1220f, 1264f, 1440f, 1600f)) {
            for (density in listOf(1.5f, 2f, 2.625f, 2.75f, 3f, 3.25f, 3.5f, 4f)) {
                for (gapDp in listOf(0f, 2f, 3f, 4f, 6f)) {
                    for (keyHeightDp in listOf(48f, 60f, 80f)) {
                        val gap = gapDp * density
                        val cell = keyboardWidth / 10f
                        val keyWidth = cell - gap
                        for (column in listOf(0, 1, 4, 9)) for (insetDp in listOf(0f, 0.5f)) {
                            val left = column * cell + gap / 2f + insetDp * density
                            val data = pressBubble(left, keyWidth, keyboardWidth, density, keyHeightDp * density)
                            paintSwipeBubble(canvas, data)
                            paintSwipeBubble(canvas, data.copy(reveal = 0f))
                        }
                    }
                }
            }
        }
    }

    @Test fun startCenterStaysInsideTheBarAndFollowsTheKey() {
        // 选项条比起点宽时照旧跟着按键，只在条里面夹住。
        assertEquals(50f, bubbleRevealStartCenter(50f, 0f, 200f, 40f), 0f)
        assertEquals(20f, bubbleRevealStartCenter(5f, 0f, 200f, 40f), 0f)
        assertEquals(180f, bubbleRevealStartCenter(300f, 0f, 200f, 40f), 0f)
        // 起点和整条一样宽：中心就是整条中点（不抛异常）。
        assertEquals(60.575f, bubbleRevealStartCenter(70f, 8.075001f, 8.075001f + 105f, 105f), 0.001f)
    }
}
