package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 键缝动效的键缝网和最短路（GapLightNet.kt）。坐标是像素，一个键 100×50。 */
class GapLightNetTest {
    /**
     * 上排三个键，中排错开半格的两个键，下排一个通栏键，最后一个是还没排出来的键。
     * 中排第二个键故意偏了不到 1 像素：相邻两个键各自取整后公共边常常差这么一点。
     */
    private val frames = floatArrayOf(
        0f, 0f, 100f, 50f, 100f, 0f, 200f, 50f, 200f, 0f, 300f, 50f,
        50f, 50f, 150f, 100f, 150.6f, 50.4f, 250f, 100f,
        0f, 100f, 300f, 150f,
        0f, 0f, 0f, 0f,
    )
    private val net = GapNet.build(frames, 2f, 2f)

    private fun near(a: Float, b: Float) = abs(a - b) < .5f

    @Test fun `a key that has no size yet stays out of the net`() {
        assertEquals(7, net.keyCount)
        assertTrue(net.has(0))
        assertFalse(net.has(6))
        assertFalse(net.has(-1))
        assertFalse(net.has(7))
        assertTrue(net.routes(6, 100f).isEmpty())
        assertTrue(net.routes(99, 100f).isEmpty())
        assertTrue(near(50f, net.unit))
    }

    @Test fun `edges that differ by a pixel become one gutter`() {
        // 上排下边和中排上边：50 和 50.4 是同一条横缝；中排两个键之间 150 和 150.6 是同一条竖缝。
        assertTrue(near(net.bottom(1), net.top(4)))
        assertEquals(net.bottom(1), net.top(4), 0f)
        assertEquals(net.right(3), net.left(4), 0f)
        val between = net.routes(1, 60f).filter { it.ax == it.bx && near(it.ax, 150.3f) }
        assertEquals(1, between.size)
    }

    @Test fun `light leaves the pressed key in every direction`() {
        val routes = net.routes(1, 60f)
        // 自己那一圈不在里面（单独画成框）。
        assertTrue(routes.none { it.da == 0f && it.db == 0f })
        // 没有哪一段是从范围外面开始的。
        assertTrue(routes.all { minOf(it.da, it.db) < 60f })
        // 往左、往右沿最上面那条缝。
        assertTrue(routes.any { it.ay == it.by && near(it.ay, 0f) && near(minOf(it.ax, it.bx), 0f) })
        assertTrue(routes.any { it.ay == it.by && near(it.ay, 0f) && near(maxOf(it.ax, it.bx), 300f) })
        // 往下：错开的中排，三条竖缝（x = 50、150、250）都走到。
        for (x in listOf(50f, 150.3f, 250f)) {
            assertTrue("down at $x", routes.any { it.ax == it.bx && near(it.ax, x) && near(maxOf(it.ay, it.by), 100f) })
        }
    }

    @Test fun `distance is the shortest way along the gutters`() {
        // 从左上角的键到右下角 (300, 150)：沿键缝最短是先横着 200 再竖着 100。
        val far = net.routes(0, 1000f)
        val corner = far.flatMap { listOf(Triple(it.ax, it.ay, it.da), Triple(it.bx, it.by, it.db)) }
            .filter { near(it.first, 300f) && near(it.second, 150f) }
        assertTrue(corner.isNotEmpty())
        assertTrue(corner.all { near(it.third, 300f) })
        // 范围放得再大，段数也不超过整张网减去自己那一圈。
        assertTrue(far.size < net.edgeCount)
    }

    @Test fun `range limits how far it goes and zero gives nothing`() {
        assertTrue(net.routes(1, 0f).isEmpty())
        val short = net.routes(1, 20f)
        val long = net.routes(1, 200f)
        assertTrue(short.isNotEmpty())
        assertTrue(short.size < long.size)
        // 范围 20：只有紧挨着这个键的那几段。
        assertTrue(short.all { minOf(it.da, it.db) == 0f })
    }

    @Test fun `two fronts meet inside a segment`() {
        // 两头离出发键 10 和 30、长 100 的一段：碰头点离 a 头 60，两边走的路程都是 70。
        val both = GapRoute(0f, 0f, 100f, 0f, 10f, 30f, 100f)
        assertEquals(60f, both.meet, 0f)
        // 只有一头有光：整段都归那一头。
        assertEquals(100f, GapRoute(0f, 0f, 100f, 0f, 0f, 100f, 100f).meet, 0f)
        assertEquals(0f, GapRoute(0f, 0f, 100f, 0f, 100f, 0f, 100f).meet, 0f)
    }

    @Test fun `curves start fast and cut off sharply`() {
        assertEquals(0f, gapLightEase(0f), 0f)
        assertEquals(.75f, gapLightEase(.5f), 0f)
        assertEquals(1f, gapLightEase(1f), 0f)
        assertEquals(1f, gapLightEase(2f), 0f)
        // 到 hold 之前一直是满的，之后才收，结束时正好到 0。
        assertEquals(1f, gapLightCut(.2f, .35f), 0f)
        assertEquals(1f, gapLightCut(.34f, .35f), 0f)
        assertTrue(gapLightCut(.6f, .35f) in .01f..0.99f)
        assertEquals(0f, gapLightCut(1f, .35f), 0f)
    }

    @Test fun `a full keyboard stays small`() {
        // 四排每排十个键、隔排错半格（108×150 像素）：节点和边都是一百来个，一次按键只走附近的几十段。
        val big = FloatArray(40 * 4) { i ->
            val key = i / 4
            val row = key / 10
            val column = key % 10
            val shift = if (row % 2 == 1) 54f else 0f
            when (i % 4) {
                0 -> shift + column * 108f
                1 -> row * 150f
                2 -> shift + column * 108f + 108f
                else -> row * 150f + 150f
            }
        }
        val keyboard = GapNet.build(big, 3f, 3f)
        assertEquals(108f, keyboard.unit, 0f)
        assertTrue(keyboard.nodeCount in 60..120)
        assertTrue(keyboard.edgeCount in 100..160)
        val routes = keyboard.routes(15, 216f)
        assertTrue(routes.size in 20..60)
        // 四个方向都有：比这个键更左、更右、更上、更下的地方都有线。
        val left = keyboard.left(15); val right = keyboard.right(15); val top = keyboard.top(15); val bottom = keyboard.bottom(15)
        assertTrue(routes.any { minOf(it.ax, it.bx) < left })
        assertTrue(routes.any { maxOf(it.ax, it.bx) > right })
        assertTrue(routes.any { minOf(it.ay, it.by) < top })
        assertTrue(routes.any { maxOf(it.ay, it.by) > bottom })
    }

    @Test fun `voice bars sit in the gutters between rows, newest at the bottom`() {
        // 三排键（每排高 100）：两条排间键缝，从下往上；按键区最上最下的外沿不算。第三个键没进网（NaN），不参与。
        val grid = floatArrayOf(
            0f, 0f, 100f, 100f,
            0f, 100f, 100f, 200f,
            Float.NaN, 50f, 0f, 150f,
            0f, 200f, 100f, 300f,
            // 跨两排的高键：上下边都和别的键对齐，不多出线。
            100f, 100f, 200f, 300f,
        )
        assertEquals(listOf(200f, 100f), gapLightRowGutters(grid, 5).toList())
        // 只有一排：没有排间键缝。
        assertEquals(0, gapLightRowGutters(floatArrayOf(0f, 0f, 100f, 100f, 100f, 0f, 200f, 100f), 2).size)
        assertEquals(0, gapLightRowGutters(FloatArray(0), 0).size)
    }

    @Test fun `voice level is quantized and clamped`() {
        assertEquals(0, gapLightVoiceLevel(0f))
        assertEquals(0, gapLightVoiceLevel(-1f))
        // 很小的音量量化成 0 级：不画，也不引起重画。
        assertEquals(0, gapLightVoiceLevel(.02f))
        assertEquals(GAP_LIGHT_VOICE_LEVELS / 2, gapLightVoiceLevel(.5f))
        assertEquals(GAP_LIGHT_VOICE_LEVELS, gapLightVoiceLevel(1f))
        assertEquals(GAP_LIGHT_VOICE_LEVELS, gapLightVoiceLevel(3f))
    }
}
