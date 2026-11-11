package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 键盘调节几何：**拖边固定对边**、命中与绘制共用同一个矩形、矩形与渲染状态互为逆映射。
 *
 * 这几个纯函数是修复的核心：旧实现以「宽度 + 中心」为主状态，拖左右边等于改宽度，
 * 两边一起动（看起来像整体缩放），而且调节框由屏幕宽度和偏移另算，和真实卡片边界对不上。
 */
class KeyboardResizeGeometryTest {
    @Test fun resizeFrameThicknessScalesWithScreenAndStaysInsideItsInset() {
        val phone = resizeFrameStyle(400)
        val tablet = resizeFrameStyle(800)
        assertEquals(5f, phone.handleThicknessDp, 0.01f)
        assertEquals(4f, phone.bracketThicknessDp, 0.01f)
        assertEquals(20f, phone.cornerLegDp, 0.01f)
        // 粗细与屏幕最短边成正比：两倍屏宽即两倍粗。
        assertEquals(2f, tablet.handleThicknessDp / phone.handleThicknessDp, 0.01f)
        assertEquals(2f, tablet.strokeDp / phone.strokeDp, 0.01f)
        assertEquals(2f, tablet.bracketThicknessDp / phone.bracketThicknessDp, 0.01f)
        assertEquals(2f, tablet.cornerLegDp / phone.cornerLegDp, 0.01f)
        for (width in listOf(320, 411, 800, 934, 4000)) {
            val style = resizeFrameStyle(width)
            // 把手骑在贴边的线上：放大后整条仍在边带里，括号两臂不超出角区太多。
            assertTrue(style.strokeDp < style.handleThicknessDp)
            assertTrue(style.handleThicknessDp * RESIZE_HANDLE_ACTIVE_SCALE <= RESIZE_EDGE_HIT_DP)
            assertTrue(style.bracketThicknessDp * RESIZE_HANDLE_ACTIVE_SCALE <= RESIZE_EDGE_HIT_DP)
            assertTrue(style.cornerLegDp <= RESIZE_CORNER_HIT_DP * RESIZE_HANDLE_ACTIVE_SCALE)
        }
    }

    @Test fun floatingControlsLeaveTheWholeBottomBlankFreeAtLargeHeightsAndPadding() {
        for (density in listOf(1f, 1.875f, 3f)) for (height in listOf(256f, 448f, 900f))
            for (padding in listOf(0, 18, 100)) {
                val metrics = keyboardGridMetrics(KeyVisualPolicy.Qwerty, 360f, height - 72f - padding,
                    columns = 10f, growthSpacing = null to null, allowShrink = true)
                val keyBlank = 8f + metrics.bottomSpaceDp + metrics.extraInsetY + (metrics.insetY ?: 0f)
                val area = floatingDragAreaHeightDp(padding, keyBlank, height)
                val frame = ResizeRect(0f, 0f, 360f * density, height * density)
                val controls = resizeControlsRect(frame, density, true, area * density)
                assertTrue("controls overlap the bottom movement area", controls.bottom <= frame.bottom - area * density)
                assertTrue("controls must remain usable", controls.height > 40f * density)
            }
    }

    @Test fun largerCornerTargetsDoNotStealStraightEdgesOrMiddle() {
        for (density in listOf(1f, 1.875f, 3f)) for (floating in listOf(false, true)) {
            val frame = ResizeRect(50f * density, 80f * density, 410f * density, 420f * density)
            fun hit(x: Float, y: Float) = resizeHandleAt(frame, Offset(x * density, y * density),
                RESIZE_EDGE_HIT_DP * density, floating, RESIZE_CORNER_HIT_DP * density)
            assertEquals(ResizeHandle.TOP_LEFT, hit(82f, 112f))
            assertEquals(ResizeHandle.TOP_RIGHT, hit(378f, 112f))
            assertEquals(ResizeHandle.BOTTOM_LEFT, hit(82f, 388f))
            assertEquals(ResizeHandle.BOTTOM_RIGHT, hit(378f, 388f))
            assertEquals(ResizeHandle.TOP_LEFT, hit(20f, 50f))
            assertEquals(ResizeHandle.TOP, hit(230f, 84f))
            assertEquals(ResizeHandle.LEFT, hit(54f, 250f))
            assertEquals(ResizeHandle.NONE, hit(82f, 250f))
            assertEquals(ResizeHandle.NONE, hit(230f, 112f))
            assertEquals(ResizeHandle.NONE, hit(230f, 250f))
            assertEquals(if (floating) ResizeHandle.NONE else ResizeHandle.BOTTOM, hit(230f, 416f))
        }
    }

    @Test fun resizeControlsLeaveExpandedCornersFreeOnPhonesAndTablets() {
        for (density in listOf(1f, 1.875f, 3f)) for (floating in listOf(false, true))
            for (width in listOf(280f, 360f, 700f, 1400f)) {
                val frame = ResizeRect(0f, 0f, width * density, 228f * density)
                val controls = resizeControlsRect(frame, density, floating, 28f * density)
                assertTrue(controls.top >= RESIZE_CORNER_HIT_DP * density)
                assertTrue(frame.bottom - controls.bottom >= RESIZE_CORNER_HIT_DP * density)
                assertTrue(controls.width > 0f && controls.height >= 96f * density)
            }
    }

    @Test fun fixedSideAndCornerEdgesSnapToHalfScreenWithoutMovingOppositeEdge() {
        for (density in listOf(1f, 1.875f, 3f)) {
            val bounds = ResizeRect(0f, 0f, 1000f * density, 800f * density)
            for (handle in listOf(ResizeHandle.RIGHT, ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_RIGHT)) {
                val raw = ResizeRect(0f, 200f * density, 492f * density, 700f * density)
                val snapped = raw.snapFixedEdgeToCenter(handle, bounds, 280f * density, 12f * density)
                assertEquals(bounds.centerX, snapped.right, .001f)
                assertEquals(raw.left, snapped.left, 0f)
                assertEquals(raw.top, snapped.top, 0f)
                assertEquals(raw.bottom, snapped.bottom, 0f)
            }
            val raw = ResizeRect(508f * density, 200f * density, bounds.right, 700f * density)
            val snapped = raw.snapFixedEdgeToCenter(ResizeHandle.LEFT, bounds, 280f * density, 12f * density)
            assertEquals(bounds.centerX, snapped.left, .001f)
            assertEquals(raw.right, snapped.right, 0f)
        }
    }

    @Test fun centerEdgeSnapRespectsMinimumWidthAndOnlyChangesDraggedAxis() {
        val bounds = ResizeRect(0f, 0f, 500f, 800f)
        val raw = ResizeRect(0f, 200f, 258f, 700f)
        assertEquals(raw, raw.snapFixedEdgeToCenter(ResizeHandle.RIGHT, bounds, 280f, 12f))
        assertEquals(raw, raw.snapFixedEdgeToCenter(ResizeHandle.TOP, bounds, 100f, 12f))
        val outside = raw.copy(right = 263f)
        assertEquals(outside, outside.snapFixedEdgeToCenter(ResizeHandle.RIGHT, bounds, 100f, 12f))
    }

    @Test fun smallResizeDeltasCanLeaveCenterSnapUsingRawAccumulator() {
        val bounds = ResizeRect(0f, 0f, 1000f, 800f)
        var raw = ResizeRect(0f, 200f, 500f, 700f)
        repeat(24) {
            raw = raw.dragBy(ResizeHandle.RIGHT, .5f, 0f, bounds, 280f, 200f)
            assertEquals(500f, raw.snapFixedEdgeToCenter(ResizeHandle.RIGHT, bounds, 280f, 12f).right, 0f)
        }
        raw = raw.dragBy(ResizeHandle.RIGHT, .5f, 0f, bounds, 280f, 200f)
        assertEquals(512.5f, raw.snapFixedEdgeToCenter(ResizeHandle.RIGHT, bounds, 280f, 12f).right, 0f)
    }


    @Test fun fixedMoveSnapsFromEitherSideWithoutChangingSizeOrVerticalPosition() {
        for (density in listOf(1f, 2.75f)) for (side in listOf(-1, 1)) {
            val bounds = ResizeRect(0f, 0f, 800f * density, 600f * density)
            val initial = ResizeRect(200f * density, 200f * density, 600f * density, 550f * density)
                .translated(side * 60f * density, 0f)
            val move = FixedKeyboardMoveGesture(initial, bounds, 12f * density)
            val snapped = move.move(-side * 50f * density)
            assertEquals(bounds.centerX, snapped.centerX, 0.001f)
            assertEquals(initial.width, snapped.width, 0.001f)
            assertEquals(initial.top, snapped.top, 0f)
            assertEquals(initial.bottom, snapped.bottom, 0f)
            assertEquals(0, snapped.toGeometry(bounds.width, bounds.height, 0f, density).horizontalOffsetDp)
        }
    }

    @Test fun smallDragStepsCanLeaveCenterAndReturnWithoutSticking() {
        val bounds = ResizeRect(0f, 0f, 800f, 600f)
        val initial = ResizeRect(200f, 200f, 600f, 550f)
        val move = FixedKeyboardMoveGesture(initial, bounds, 12f)
        repeat(24) { assertEquals(400f, move.move(0.5f).centerX, 0f) }
        assertEquals(412.5f, move.move(0.5f).centerX, 0f)
        assertEquals(400f, move.move(-0.5f).centerX, 0f)
        assertEquals(400f, move.move(-24f).centerX, 0f)
        assertEquals(387.5f, move.move(-0.5f).centerX, 0f)
    }

    @Test fun freshDragStartsAtVisibleCenterWithoutPreviousHiddenOffset() {
        val bounds = ResizeRect(0f, 0f, 800f, 600f)
        val first = FixedKeyboardMoveGesture(ResizeRect(150f, 200f, 550f, 550f), bounds, 12f)
        val snapped = first.move(40f)
        val next = FixedKeyboardMoveGesture(snapped, bounds, 12f)
        assertEquals(420f, next.move(20f).centerX, 0f)
    }

    @Test fun nearlyFullWidthStillReachesBothEdgesAndFullWidthStaysCentered() {
        val bounds = ResizeRect(0f, 0f, 360f, 600f)
        for (width in listOf(280f, 350f, 360f)) {
            val move = FixedKeyboardMoveGesture(ResizeRect(0f, 200f, width, 550f), bounds, 12f)
            assertEquals(0f, move.move(-1000f).left, 0f)
            assertEquals(360f, move.move(2000f).right, 0f)
            assertEquals(180f, move.move(-(360f - width) / 2f).centerX, 0f)
        }
    }

    @Test fun fixedWidthAndPositionPreserveBottomInsetsAcrossHostHeights() {
        val normal = fixedKeyboardRect(1000f, 400f, 300f, 20f, 24f, 400f, 300f)
        val preview = fixedKeyboardRect(1000f, 900f, 300f, 20f, 24f, 400f, 300f)
        assertEquals(600f, normal.left, 0.01f)
        assertEquals(1000f, normal.right, 0.01f)
        assertEquals(normal.width, preview.width, 0.01f)
        assertEquals(normal.height, preview.height, 0.01f)
        assertEquals(24f, 900f - preview.bottom, 0.01f)
        assertEquals(0f, fixedKeyboardRect(360f, 400f, 300f, 0f, 24f, 800f, -300f).left, 0.01f)
    }

    @Test fun fixedWidthWithinOneDpOfTheScreenFillsItAndCannotMove() {
        // 1080px / 2.625 = 411.43dp，存成 411dp = 1078.9px：差不到 1dp 就是整宽，调节时左右挪不动。
        val density = 2.625f
        val rect = fixedKeyboardRect(1080f, 2200f, 300f * density, 0f, 48f, 411f * density, density = density)
        assertEquals(0f, rect.left, 0f)
        assertEquals(1080f, rect.right, 0f)
        val move = FixedKeyboardMoveGesture(rect, ResizeRect(0f, 0f, 1080f, 2152f), 12f * density)
        assertEquals(0f, move.move(30f).left, 0f)
        assertEquals(0f, move.move(-30f).left, 0f)
        // 真正窄了 1dp 以上的照旧可以移动。
        val narrow = fixedKeyboardRect(1080f, 2200f, 300f * density, 0f, 48f, 400f * density, density = density)
        assertEquals((1080f - 400f * density) / 2f, narrow.left, 0.01f)
    }

    @Test fun floatingFloorFollowsTheScreenAndIsTheSameForEveryResizePath() {
        // 下限按屏幕短边：手机 170×270dp（比原来横屏 244×400 小约 30%），平板 SM-X800（短边 934dp）300×480dp。
        for (shortSide in listOf(360, 384, 411)) {
            assertEquals(170, floatingResizeFloorContentDp(shortSide, 2000))
            assertEquals(270, floatingResizeFloorWidthDp(shortSide, 2000))
        }
        assertEquals(200, floatingResizeFloorContentDp(480, 2000))
        assertEquals(320, floatingResizeFloorWidthDp(480, 2000))
        assertEquals(250, floatingResizeFloorContentDp(600, 2000))
        assertEquals(400, floatingResizeFloorWidthDp(600, 2000))
        for (shortSide in listOf(824, 934, 1200)) {
            assertEquals(300, floatingResizeFloorContentDp(shortSide, 2000))
            assertEquals(480, floatingResizeFloorWidthDp(shortSide, 2000))
        }
        // 横竖屏同一个值（只看短边）；可用高度、宽度不够时取可用的。
        // 底部拖动条手机 16dp、平板 28dp（11-08 起，floatingFooterHeightDp）。
        assertEquals(150 - floatingFooterHeightDp(411), floatingResizeFloorContentDp(411, 150))
        assertEquals(250, floatingResizeFloorWidthDp(411, 250))

        // 点亮四角直接拖和调节界面同一组下限：手机竖屏、手机横屏、平板横屏。
        data class Screen(val widthDp: Int, val heightDp: Int, val density: Float)
        for (screen in listOf(Screen(411, 891, 2.625f), Screen(891, 411, 2.625f), Screen(1493, 934, 1.875f))) {
            val d = screen.density
            val shortSide = minOf(screen.widthDp, screen.heightDp)
            val card = ResizeRect(0f, (screen.heightDp - 340f) * d, minOf(screen.widthDp, 600) * d, screen.heightDp * d)
            val limits = floatingCornerResizeLimits(card, screen.widthDp * d, screen.heightDp * d, 0f, screen.widthDp,
                screen.widthDp > screen.heightDp, d, shortSide)
            val availableHeightDp = (limits.bounds.height / d).roundToInt()
            val availableWidthDp = (limits.bounds.width / d).roundToInt()
            assertEquals("$screen", (floatingResizeFloorContentDp(shortSide, availableHeightDp) + floatingFooterHeightDp(shortSide)) * d,
                limits.minHeight, 0.5f)
            assertEquals("$screen", floatingResizeFloorWidthDp(shortSide, availableWidthDp) * d, limits.minWidth, 0.5f)
        }

        // 手机 26 键方形点（约 202dp）又在下限之上，吸得到。
        val density = 2.75f
        val geometry = KeyboardSquareSnap(10f, false, false)
        val bounds = ResizeRect(0f, 0f, 384f * density, 900f * density)
        val card = ResizeRect(0f, bounds.bottom - 300f * density, 384f * density, bounds.bottom)
        val squareContentDp = geometry.squareTotalHeightPx(card, density, FLOATING_DRAG_BAR_HEIGHT_DP * density, true,
            null to null)!! / density - FLOATING_DRAG_BAR_HEIGHT_DP
        assertTrue("square content $squareContentDp", squareContentDp > floatingResizeFloorContentDp(384, 900))
        // 已存高度范围和默认范围不变：旧尺寸照旧读回，默认尺寸不动。
        assertEquals(FLOATING_RESIZE_ABSOLUTE_MIN_HEIGHT_DP, floatingStoredHeightBounds(900, false).first)
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(900, false).first)
    }

    @Test fun fixedAndFloatingShareMinimumPanelSizeOnTablet() {
        for (landscape in listOf(false, true)) {
            for (height in listOf(800, 1400)) {
                assertEquals(floatingResizeHeightBounds(height, landscape).first,
                    keyboardHeightBounds(height, landscape).first)
            }
        }
        for (width in listOf(320, 800, 1400)) {
            assertEquals(280.coerceIn(keyboardWidthBounds(width)), resolvedFixedKeyboardWidth(width, 280))
            assertEquals(width, resolvedFixedKeyboardWidth(width, 0))
        }
    }

    @Test fun qwertyPunctuationFitsUnderXAndMatchesLanguageKey() {
        val row = QwertyBottomRowWeights.Standard
        val old = QwertyBottomRowWeights.Legacy
        assertEquals(row.punctuation, row.language, 0f)
        assertTrue((2 * row.mode + row.punctuation) / row.total <= (1.4f + 2f) / 9.8f)
        assertTrue(row.mode / row.total > old.mode / old.total)
        assertEquals(1f / 10f, row.punctuation / row.total, 0.00001f)
        assertEquals(1f / 10f, row.language / row.total, 0.00001f)
    }

    private val bounds = ResizeRect(0f, 0f, 1000f, 800f)
    private val start = ResizeRect(100f, 100f, 700f, 500f)

    private fun drag(handle: ResizeHandle, dx: Float, dy: Float): ResizeRect =
        start.dragBy(handle, dx, dy, bounds, minWidth = 260f, minHeight = 200f)

    @Test
    fun `拖左边时右边不动`() {
        val r = drag(ResizeHandle.LEFT, 50f, 0f)
        assertEquals(150f, r.left, 0.01f)
        assertEquals(700f, r.right, 0.01f)
        assertEquals(100f, r.top, 0.01f)
        assertEquals(500f, r.bottom, 0.01f)
    }

    @Test
    fun `拖右边时左边不动`() {
        val r = drag(ResizeHandle.RIGHT, -50f, 0f)
        assertEquals(100f, r.left, 0.01f)
        assertEquals(650f, r.right, 0.01f)
        assertEquals(500f, r.bottom, 0.01f)
    }

    @Test
    fun `拖上边时下边不动`() {
        val r = drag(ResizeHandle.TOP, 0f, 50f)
        assertEquals(150f, r.top, 0.01f)
        assertEquals(500f, r.bottom, 0.01f)
        assertEquals(100f, r.left, 0.01f)
    }

    @Test
    fun `拖下边时上边不动`() {
        val r = drag(ResizeHandle.BOTTOM, 0f, -50f)
        assertEquals(100f, r.top, 0.01f)
        assertEquals(450f, r.bottom, 0.01f)
        assertEquals(700f, r.right, 0.01f)
    }

    @Test
    fun `四角同时移动两条边 对侧边不动`() {
        val topLeft = drag(ResizeHandle.TOP_LEFT, 50f, 50f)
        assertEquals(150f, topLeft.left, 0.01f)
        assertEquals(150f, topLeft.top, 0.01f)
        assertEquals(700f, topLeft.right, 0.01f)
        assertEquals(500f, topLeft.bottom, 0.01f)

        val topRight = drag(ResizeHandle.TOP_RIGHT, -50f, 50f)
        assertEquals(100f, topRight.left, 0.01f)
        assertEquals(150f, topRight.top, 0.01f)
        assertEquals(650f, topRight.right, 0.01f)
        assertEquals(500f, topRight.bottom, 0.01f)

        val bottomLeft = drag(ResizeHandle.BOTTOM_LEFT, 50f, -50f)
        assertEquals(150f, bottomLeft.left, 0.01f)
        assertEquals(100f, bottomLeft.top, 0.01f)
        assertEquals(700f, bottomLeft.right, 0.01f)
        assertEquals(450f, bottomLeft.bottom, 0.01f)

        val bottomRight = drag(ResizeHandle.BOTTOM_RIGHT, -50f, -50f)
        assertEquals(100f, bottomRight.left, 0.01f)
        assertEquals(100f, bottomRight.top, 0.01f)
        assertEquals(650f, bottomRight.right, 0.01f)
        assertEquals(450f, bottomRight.bottom, 0.01f)
    }

    @Test
    fun `夹紧既不越过对边也不越出可拖范围`() {
        // 拖左边过头 → 停在 right - minWidth
        assertEquals(440f, drag(ResizeHandle.LEFT, 9999f, 0f).left, 0.01f)
        // 拖左边出界 → 停在可拖范围左边缘
        assertEquals(0f, drag(ResizeHandle.LEFT, -9999f, 0f).left, 0.01f)
        // 拖下边过头 → 停在 bottom 边界，上边仍然不动
        val bottomOver = drag(ResizeHandle.BOTTOM, 0f, 9999f)
        assertEquals(800f, bottomOver.bottom, 0.01f)
        assertEquals(100f, bottomOver.top, 0.01f)
        // 拖上边过头 → 停在 top + minHeight
        assertEquals(300f, drag(ResizeHandle.TOP, 0f, 9999f).top, 0.01f)
    }

    @Test
    fun `中间拖动整体平移 宽高不变`() {
        val r = drag(ResizeHandle.NONE, 50f, 30f)
        assertEquals(150f, r.left, 0.01f)
        assertEquals(130f, r.top, 0.01f)
        assertEquals(750f, r.right, 0.01f)
        assertEquals(530f, r.bottom, 0.01f)
        assertEquals(start.width, r.width, 0.01f)
        assertEquals(start.height, r.height, 0.01f)
    }

    @Test
    fun `命中只认正在绘制的那个矩形`() {
        val card = ResizeRect(150f, 0f, 850f, 400f)
        assertEquals(ResizeHandle.LEFT, resizeHandleAt(card, Offset(160f, 200f), 28f, true))
        assertEquals(ResizeHandle.RIGHT, resizeHandleAt(card, Offset(840f, 200f), 28f, true))
        assertEquals(ResizeHandle.TOP_LEFT, resizeHandleAt(card, Offset(154f, 4f), 28f, true))
        assertEquals(ResizeHandle.BOTTOM_RIGHT, resizeHandleAt(card, Offset(846f, 396f), 28f, true))
        assertEquals(ResizeHandle.BOTTOM_LEFT, resizeHandleAt(card, Offset(154f, 396f), 28f, true))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(card, Offset(500f, 396f), 28f, true))
        // 卡片外不是手柄：留给"拖动移动位置"
        assertEquals(ResizeHandle.NONE, resizeHandleAt(card, Offset(20f, 200f), 28f, true))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(card, Offset(600f, 200f), 28f, true))

        // 固定键盘也支持宽度与角手柄，中间仍不是缩放手柄
        val full = ResizeRect(0f, 0f, 1000f, 400f)
        assertEquals(ResizeHandle.TOP_LEFT, resizeHandleAt(full, Offset(4f, 4f), 28f, false))
        assertEquals(ResizeHandle.BOTTOM_RIGHT, resizeHandleAt(full, Offset(996f, 396f), 28f, false))
        assertEquals(ResizeHandle.LEFT, resizeHandleAt(full, Offset(2f, 200f), 28f, false))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(full, Offset(500f, 200f), 28f, false))
    }

    @Test
    fun `固定键盘底边把手避开系统底部手势区`() {
        // 键盘 1000x800，底边 y=2200；屏幕底 2344，手势区上沿 2200（手势区 144px），离手势区留 36px。
        val fixed = ResizeRect(0f, 1400f, 1000f, 2200f)
        // 三键导航 / 读不到：把手就在底边，和原来一样。
        assertEquals(2200f, fixedBottomHandleY(fixed, null, 36f), 0.01f)
        // 离手势区够远：不动。
        assertEquals(2200f, fixedBottomHandleY(fixed, 2300f, 36f), 0.01f)
        // 底边贴着或落进手势区：抬到手势区上沿之上 36px。
        assertEquals(2164f, fixedBottomHandleY(fixed, 2200f, 36f), 0.01f)
        assertEquals(2104f, fixedBottomHandleY(fixed, 2140f, 36f), 0.01f)
        // 手势区很高时把手继续上移，到键盘顶边为止。
        assertEquals(1464f, fixedBottomHandleY(fixed, 1500f, 36f), 0.01f)
        assertEquals(fixed.top, fixedBottomHandleY(fixed, 1350f, 36f), 0.01f)

        // 命中跟着抬起的把手：把手往上 hit 以内按住就是调底部留白，不是左右移动。
        val handle = fixedBottomHandleY(fixed, 2200f, 36f)
        assertEquals(ResizeHandle.BOTTOM, resizeHandleAt(fixed, Offset(500f, handle - 20f), 28f, false, 36f, handle))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(fixed, Offset(500f, handle - 20f), 28f, false, 36f))
        // 把手在底边时原来的命中不变。
        assertEquals(ResizeHandle.BOTTOM, resizeHandleAt(fixed, Offset(500f, 2190f), 28f, false, 36f, 2200f))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(fixed, Offset(500f, 2150f), 28f, false, 36f, 2200f))
    }

    @Test
    fun `四角圆弧与卡片圆角同心`() {
        val card = ResizeRect(100f, 0f, 900f, 400f)
        val arcs = resizeCornerArcs(card, 16f)
        assertEquals(4, arcs.size)
        assertEquals(Offset(100f, 0f), arcs[0].topLeft)
        assertEquals(180f, arcs[0].startAngle, 0.01f)
        assertEquals(Offset(868f, 0f), arcs[1].topLeft)
        assertEquals(270f, arcs[1].startAngle, 0.01f)
        assertEquals(Offset(100f, 368f), arcs[2].topLeft)
        assertEquals(90f, arcs[2].startAngle, 0.01f)
        assertEquals(Offset(868f, 368f), arcs[3].topLeft)
        assertEquals(0f, arcs[3].startAngle, 0.01f)
    }

    @Test
    fun `矩形与渲染状态互为逆映射 含偏移`() {
        val viewW = 1000f
        val viewH = 800f
        val dragBar = 28f
        val density = 2f
        val rect = ResizeRect(150f, 120f, 850f, 600f)
        val geometry = rect.toGeometry(viewW, viewH, dragBar, density)
        val back = geometryToResizeRect(
            geometry.widthDp, geometry.heightDp,
            geometry.horizontalOffsetDp, geometry.bottomOffsetDp,
            viewW, viewH, dragBar, density,
        )
        // dp 取整只允许 1px 级误差：按状态渲染出来的卡片就是调节框本身
        assertEquals(rect.left, back.left, 1.5f)
        assertEquals(rect.top, back.top, 1.5f)
        assertEquals(rect.right, back.right, 1.5f)
        assertEquals(rect.bottom, back.bottom, 1.5f)
    }

    @Test
    fun `offset 就是矩形中心与底边 移动后仍与矩形一致`() {
        val viewW = 1000f
        val viewH = 800f
        val dragBar = 0f
        val density = 1f
        val centered = ResizeRect(200f, 300f, 800f, 800f)
        val base = centered.toGeometry(viewW, viewH, dragBar, density)
        assertEquals(0, base.horizontalOffsetDp)
        assertEquals(0, base.bottomOffsetDp)

        // 往左 100、往上 50：offset 必须完整反映位移，并能逆映射回同一个矩形
        val moved = centered.dragBy(
            ResizeHandle.NONE, -100f, -50f,
            ResizeRect(0f, 0f, viewW, viewH), 260f, 200f,
        )
        val geometry = moved.toGeometry(viewW, viewH, dragBar, density)
        assertEquals(-100, geometry.horizontalOffsetDp)
        assertEquals(50, geometry.bottomOffsetDp)
        val back = geometryToResizeRect(
            geometry.widthDp, geometry.heightDp,
            geometry.horizontalOffsetDp, geometry.bottomOffsetDp,
            viewW, viewH, dragBar, density,
        )
        assertEquals(moved.left, back.left, 0.01f)
        assertEquals(moved.top, back.top, 0.01f)
        assertEquals(moved.right, back.right, 0.01f)
        assertEquals(moved.bottom, back.bottom, 0.01f)
    }

    @Test
    fun `悬浮宽度：设置值优先 未设置按高度推导`() {
        assertEquals(FLOATING_RESIZE_MIN_WIDTH_DP, keyboardWidthBounds(800).first)
        assertEquals(800, keyboardWidthBounds(800).last)
        // 屏宽小于下限时上限跟着收紧，不产生空区间
        assertEquals(240, keyboardWidthBounds(240).first)
        assertEquals(240, keyboardWidthBounds(240).last)
        assertEquals(700, resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 700))
        assertEquals(800, resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 9999))
        assertEquals(FLOATING_RESIZE_MIN_WIDTH_DP, resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 10))
        // 0 = 未设置 → 与旧的高度推导完全一致
        assertEquals(
            floatingKeyboardWidth(800, 600, 300, 300, false),
            resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 0),
        )
    }

    @Test
    fun `最小尺寸：手机基准与可用区域取小`() {
        // 最低面板高度为手机基准 244dp（四行各 48dp），各设备与显示模式一致。
        assertEquals(400, FLOATING_RESIZE_MIN_WIDTH_DP)
        assertEquals(244, FLOATING_RESIZE_MIN_HEIGHT_DP)
        assertTrue(FLOATING_RESIZE_MIN_HEIGHT_DP >= 220)
        assertEquals(FLOATING_RESIZE_MIN_WIDTH_DP, floatingResizeMinWidthDp(1000))
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeMinHeightDp(800))
        // 可用区比基准还小时取可用区，不产生越界初值
        assertEquals(260, floatingResizeMinWidthDp(280))
        assertEquals(180, floatingResizeMinHeightDp(180))

    }

    @Test
    fun `横屏高度下限不再随方向放宽`() {
        // 旧实现横屏下限 130，比竖屏还小；现在两个方向共用同一手机基准
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(360, landscape = true).first)
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(900, landscape = false).first)
        // 上限仍按屏幕比例收窄，但不低于下限
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(200, landscape = true).last)
    }

    @Test fun phoneFloatingWidthCanShrinkWhileTabletKeepsUsableMinimum() {
        for (width in listOf(320, 360, 393, 412)) {
            val bounds = keyboardWidthBounds(width)
            assertTrue(bounds.first <= width * 0.82f)
            assertEquals(bounds.first, resolvedFloatingWidth(width, 800, 260, 260, false, 1))
            assertEquals(width, bounds.last)
        }
        assertEquals(400, keyboardWidthBounds(1200).first)
        assertEquals(1200, keyboardWidthBounds(1200).last)
        assertTrue(floatingResizeHeightBounds(800, true).last >= 520)
        assertEquals(244, floatingResizeHeightBounds(800, true).first)
    }

    /** 用户 2026-10-30：调节界面的滑条和按钮要跟着键盘大小走，小键盘不再整片盖住按键，平板大键盘不再缩成一小块。 */
    @Test fun controlsKeepTheirSizeOnAPhoneAndFollowSmallerOrBiggerKeyboards() {
        assertEquals(1f, resizeControlScale(RESIZE_CONTROLS_REFERENCE_WIDTH_DP, RESIZE_CONTROLS_REFERENCE_HEIGHT_DP), 0.001f)
        // 小悬浮键盘：控件一起缩小；平板大键盘：一起放大，但有上限。
        assertTrue(resizeControlScale(220f, 160f) < 0.9f)
        assertEquals(0.7f, resizeControlScale(100f, 60f), 0.001f)
        assertTrue(resizeControlScale(900f, 300f) > 1.3f)
        assertEquals(1.5f, resizeControlScale(1200f, 500f), 0.001f)
        // 宽高按小的那边算：宽屏但很矮时不放大。
        assertTrue(resizeControlScale(1200f, 180f) < 1f)
    }

    /** 11-03 手机 1.15.51 闪退：矩形伸出可拖范围时拖边，范围上限小于下限。不闪退，被拖的边停在边界上。 */
    @Test fun dragDoesNotCrashWhenTheRectSticksOutOfBounds() {
        val bounds = ResizeRect(0f, 0f, 1000f, 1876f)
        // 下边已在边界外：top + minH = 1977.2 > 1876。
        val low = ResizeRect(100f, 1577.2f, 900f, 2000f)
        for (handle in listOf(ResizeHandle.BOTTOM, ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT)) {
            val moved = low.dragBy(handle, 0f, 10f, bounds, minWidth = 300f, minHeight = 400f)
            assertEquals(1876f, moved.bottom, 0.01f)
            assertEquals(low.top, moved.top, 0.01f)
        }
        // 上边在边界外。
        val high = ResizeRect(100f, -300f, 900f, 200f)
        for (handle in listOf(ResizeHandle.TOP, ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT)) {
            val moved = high.dragBy(handle, 0f, -10f, bounds, minWidth = 300f, minHeight = 400f)
            assertEquals(0f, moved.top, 0.01f)
            assertEquals(high.bottom, moved.bottom, 0.01f)
        }
        // 左右伸出去，宽度下限放不下。
        val wide = ResizeRect(800f, 100f, 1100f, 600f)
        assertEquals(1000f, wide.dragBy(ResizeHandle.RIGHT, 5f, 0f, bounds, 300f, 200f).right, 0.01f)
        val left = ResizeRect(-200f, 100f, 100f, 600f)
        assertEquals(0f, left.dragBy(ResizeHandle.LEFT, -5f, 0f, bounds, 300f, 200f).left, 0.01f)
        // 传入的上限比下限小也不抛。
        val inside = ResizeRect(100f, 1000f, 900f, 1800f)
        val capped = inside.dragBy(ResizeHandle.TOP, 0f, -50f, bounds, minWidth = 300f, minHeight = 1977f, maxHeight = 1876f)
        assertTrue(capped.top >= bounds.top && capped.bottom == inside.bottom)
    }
}
