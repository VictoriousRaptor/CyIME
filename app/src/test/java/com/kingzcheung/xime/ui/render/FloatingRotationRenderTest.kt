package com.kingzcheung.xime.ui.render

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.floatingFooterHeightDp
import com.kingzcheung.xime.ui.keyboard.FLOATING_ROTATE_HANDLE_GAP_DP
import com.kingzcheung.xime.ui.keyboard.FLOATING_ROTATE_HANDLE_SIZE_DP
import com.kingzcheung.xime.ui.keyboard.FloatingKeyboardContainer
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.KeyboardResizePreviewState
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardResizePreviewState
import com.kingzcheung.xime.ui.keyboard.ResizeRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 悬浮键盘绕中心旋转：平时按保存的角度显示、点到的键；调节界面里按住旋转手柄拖动的角度、确认保存。
 * 设了 CYIME_RENDER_OUT 时另外把几种角度/位置和调节界面拼成一张图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingRotationRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val hostW = 400
    private val hostH = 600
    private val contentH = 220
    private val cardH = contentH + floatingFooterHeightDp(hostW)
    private var rotation by mutableFloatStateOf(0f)
    private var offsetX by mutableIntStateOf(0)
    private var offsetY by mutableIntStateOf(120)
    private var resizing by mutableStateOf(false)
    private val resizeRotation = mutableFloatStateOf(0f)
    private var resizeRect by mutableStateOf(ResizeRect(40f, 600f - 120f - cardH, 360f, 600f - 120f))
    private var tapped = ""
    private var confirmed = Float.NaN

    @Composable
    private fun FakeKeys() {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFFCBD5E1)), Alignment.Center) {
                Text("候选 · 工具栏", fontSize = 14.sp)
            }
            for (row in listOf("QWERT", "ASDFG", "ZXCVB")) Row(Modifier.weight(1f).padding(2.dp)) {
                for (key in row) Box(Modifier.weight(1f).fillMaxSize().padding(2.dp)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .clickable { tapped = key.toString() }, Alignment.Center) { Text(key.toString()) }
            }
        }
    }

    private fun show() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(hostW.dp, hostH.dp).background(Color(0xFFDDE3EA)).testTag("host")) {
                    val preview = KeyboardResizePreviewState(rect = resizeRect, initialRect = resizeRect,
                        onRectChange = { resizeRect = it }, bounds = ResizeRect(0f, 0f, hostW.toFloat(), hostH.toFloat()),
                        rotation = resizeRotation)
                    FloatingKeyboardContainer(
                        isFloatingMode = true, scaleFactor = 0.8f, contentHeightDp = contentH,
                        offsetX = offsetX, offsetY = offsetY, backgroundColor = Color(0xFFF3F4F6),
                        dragIndicatorColor = Color(0xFF8A8F98),
                        rotationDegrees = rotation,
                        onCornerResize = { tapped = "resized" },
                        previewRectState = if (resizing) androidx.compose.runtime.rememberUpdatedState(resizeRect) else null,
                        previewRotationState = if (resizing) resizeRotation else null,
                        onDrag = { dx, dy -> offsetX += dx.toInt(); offsetY += dy.toInt() }, onDragEnd = {},
                    ) { FakeKeys() }
                    if (resizing) FloatingTestResizeEditor(preview, contentH, { confirmed = it }, { resizing = false })
                }
            }
        }
    }

    /** 卡片（未旋转）在宿主中的中心，单位 dp。 */
    private fun cardCenter(): Pair<Float, Float> = (hostW / 2f + offsetX) to (hostH - offsetY - cardH / 2f)

    private fun rotatedPoint(vx: Float, vy: Float, deg: Float): Offset {
        val (cx, cy) = cardCenter()
        val r = Math.toRadians(deg.toDouble())
        return Offset(cx + vx * cos(r).toFloat() - vy * sin(r).toFloat(), cy + vx * sin(r).toFloat() + vy * cos(r).toFloat())
    }

    @Test fun tapOnRotatedKeyHitsThatKey() {
        rotation = 45f
        show()
        val density = rule.activity.resources.displayMetrics.density
        val cardW = hostW * 0.8f
        // 第二行 A 键的中心（未旋转时相对卡片中心），按 45° 转到屏幕上去点。
        val keyW = (cardW - 4f) / 5f
        val rowH = (contentH - 44f) / 3f
        val local = Offset(-cardW / 2f + 2f + keyW / 2f, -cardH / 2f + 44f + rowH * 1.5f)
        val target = rotatedPoint(local.x, local.y, 45f)
        rule.onNodeWithTag("host").performTouchInput { click(Offset(target.x * density, target.y * density)) }
        rule.waitForIdle()
        assertEquals("A", tapped)
    }

    @Test fun normalFloatingKeyboardHasNoRotateHandle() {
        show()
        assertTrue(rule.onNodeWithTag("keyboard-resize-rotate-handle").isNotDisplayedOrMissing())
    }

    /**
     * 用户 11-09：不要锁定按钮，长按底部短横条锁定；锁定后不能移动缩放，底部小锁长按 0.5 秒解锁，没到时间松手不变。
     * 锁了再解、反复两次之后照样能拖（11-09 反馈锁一次再解锁就动不了）。
     */
    @Test fun holdingTheBarLocksAndUnlocksAndDraggingStillWorksAfterwards() {
        val context = rule.activity
        val prefs = com.kingzcheung.xime.settings.SettingsPreferences
        show()
        val bar = rule.onNodeWithTag("floating-drag-bar")
        val host = rule.onNodeWithTag("host")
        val card = rule.onNodeWithTag("floating-keyboard-card")
        val indicator = rule.onNodeWithTag("floating-drag-indicator")
        fun hold(ms: Long) = indicator.performTouchInput { down(center); advanceEventTime(ms); up() }
        fun drag(dx: Float, dy: Float) {
            // 每次从拖动条当前位置开始拖：上一轮拖过，条已经跟着卡片挪了。
            val local = bar.fetchSemanticsNode().boundsInRoot.center - host.fetchSemanticsNode().boundsInRoot.topLeft
            host.performTouchInput { down(local); moveTo(local + Offset(dx / 3f, dy / 3f)); moveTo(local + Offset(dx, dy)); up() }
        }
        repeat(2) { round ->
            hold(600)
            rule.runOnIdle { assertTrue("round $round: 0.6 s is not long enough to lock", !prefs.floatingGeometryLocked(context)) }
            hold(1150)
            rule.runOnIdle { assertTrue("round $round: locked", prefs.floatingGeometryLocked(context)) }
            val locked = card.fetchSemanticsNode().boundsInRoot
            drag(44f, -40f)
            assertEquals(locked, card.fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("floating-corner-handle-top_left").assertDoesNotExist()
            hold(350)
            rule.runOnIdle { assertTrue("round $round: still locked", prefs.floatingGeometryLocked(context)) }
            hold(560)
            rule.runOnIdle { assertTrue("round $round: unlocked", !prefs.floatingGeometryLocked(context)) }
            val unlocked = card.fetchSemanticsNode().boundsInRoot
            drag(30f, -28f)
            assertTrue("round $round: moves again after unlocking", card.fetchSemanticsNode().boundsInRoot != unlocked)
        }
    }

    /** 调节界面里不再有锁定按钮；长按底部拖动条锁定，按当前样子确认并关掉调节界面。 */
    @Test fun editorHasNoLockButtonAndHoldingItsBarLocks() {
        val prefs = com.kingzcheung.xime.settings.SettingsPreferences
        resizing = true
        resizeRotation.floatValue = 32f
        show()
        rule.onNodeWithTag("keyboard-resize-lock-button").assertDoesNotExist()
        rule.onNodeWithTag("keyboard-resize-move-bar").performTouchInput { down(center); advanceEventTime(1150); up() }
        rule.runOnIdle { assertTrue(prefs.floatingGeometryLocked(rule.activity)) }
        rule.onNodeWithTag("keyboard-resize-rotate-handle").assertDoesNotExist()
        rule.onNodeWithTag("floating-drag-bar").assertExists()
        assertEquals(32f, confirmed, .01f)
    }

    /** 按住时进度沿卡片左右两边往上走（还没到顶边中点），没满松手原路退回、不锁定。 */
    @Test fun holdingDrawsTheSweepUpTheSidesAndAnEarlyReleaseRetractsIt() {
        val prefs = com.kingzcheung.xime.settings.SettingsPreferences
        show()
        val card = rule.onNodeWithTag("floating-keyboard-card")
        val indicator = rule.onNodeWithTag("floating-drag-indicator")
        fun cardShot(): Bitmap {
            val bounds = card.fetchSemanticsNode().boundsInRoot
            val root = rule.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { root.draw(android.graphics.Canvas(full)) }
            return Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        }
        fun leftSide(b: Bitmap) = b.getPixel(1, b.height * 3 / 4)
        fun rightSide(b: Bitmap) = b.getPixel(b.width - 2, b.height * 3 / 4)
        fun topCenter(b: Bitmap) = b.getPixel(b.width / 2, 1)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        val before = cardShot()
        indicator.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(700)
        val holding = cardShot()
        assertTrue("left side lit", leftSide(holding) != leftSide(before))
        assertTrue("right side lit", rightSide(holding) != rightSide(before))
        assertEquals("not at the top yet", topCenter(before), topCenter(holding))
        indicator.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(600)
        val released = cardShot()
        assertEquals("retracted", leftSide(before), leftSide(released))
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertTrue(!prefs.floatingGeometryLocked(rule.activity)) }
    }

    @Test fun aMovedHoldOnTheLockedBarDoesNotUnlock() {
        val prefs = com.kingzcheung.xime.settings.SettingsPreferences
        prefs.setFloatingGeometryLocked(rule.activity, true)
        show()
        val bar = rule.onNodeWithTag("floating-drag-bar")
        bar.performTouchInput {
            down(center)
            moveBy(Offset(18f, -16f))
            advanceEventTime(700)
            up()
        }
        rule.runOnIdle { assertTrue(prefs.floatingGeometryLocked(rule.activity)) }
    }

    /**
     * 用户 11-10：锁定后拖底部拖动条，卡片不动也不知道为什么。拖过触摸阈值时整圈边框亮一下（小锁同时抖），
     * 过一会儿淡回平时的淡描边；卡片不动、仍然锁着。
     */
    @Test fun draggingTheLockedBarFlashesTheBorderAndKeepsTheCard() {
        val prefs = com.kingzcheung.xime.settings.SettingsPreferences
        prefs.setFloatingGeometryLocked(rule.activity, true)
        show()
        val card = rule.onNodeWithTag("floating-keyboard-card")
        val bar = rule.onNodeWithTag("floating-drag-bar")
        fun leftSide(): Int {
            val bounds = card.fetchSemanticsNode().boundsInRoot
            val root = rule.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { root.draw(android.graphics.Canvas(full)) }
            return full.getPixel(bounds.left.toInt() + 1, (bounds.top + bounds.height * 3 / 4).toInt())
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        val before = leftSide()
        val placed = card.fetchSemanticsNode().boundsInRoot
        bar.performTouchInput { down(center); moveBy(Offset(30f, -20f)) }
        // The flash starts in a LaunchedEffect: let the main looper run it before sampling the border.
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(80)
        rule.waitForIdle()
        assertTrue("border lit", leftSide() != before)
        assertEquals(placed, card.fetchSemanticsNode().boundsInRoot)
        bar.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1200)
        assertEquals("faded back", before, leftSide())
        rule.mainClock.autoAdvance = true
        assertEquals(placed, card.fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertTrue(prefs.floatingGeometryLocked(rule.activity)) }
    }

    @Test fun rotatedFooterAtExtremeOffsetsCanUnlock() {
        rotation = -90f
        offsetX = -400
        offsetY = 0
        val prefs = com.kingzcheung.xime.settings.SettingsPreferences
        prefs.setFloatingGeometryLocked(rule.activity, true)
        show()
        val bar = rule.onNodeWithTag("floating-drag-indicator")
        bar.performTouchInput { down(center); advanceEventTime(560); up() }
        rule.runOnIdle { assertTrue(!prefs.floatingGeometryLocked(rule.activity)) }
    }

    /** 上方放不下时，旋转手柄挪到卡片下方。 */
    @Test fun rotateHandleMovesBelowWhenTheTopIsOffScreen() {
        val viewport = ResizeRect(0f, 0f, 400f, 600f)
        val card = ResizeRect(40f, 10f, 360f, 300f)
        val rotate = com.kingzcheung.xime.ui.keyboard.floatingRotateHandleRect(card, 0f, viewport, 1f)
        assertTrue(rotate.top >= card.bottom)
        val high = ResizeRect(40f, 200f, 360f, 480f)
        val above = com.kingzcheung.xime.ui.keyboard.floatingRotateHandleRect(high, 0f, viewport, 1f)
        assertTrue(above.bottom <= high.top)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isNotDisplayedOrMissing(): Boolean =
        runCatching { fetchSemanticsNode() }.isFailure

    @Test fun resizeHandleTurnsAroundTheCenterAndConfirmSaves() {
        resizing = true
        show()
        val density = rule.activity.resources.displayMetrics.density
        val cx = resizeRect.centerX
        val cy = resizeRect.centerY
        val radius = cardH / 2f + FLOATING_ROTATE_HANDLE_GAP_DP + FLOATING_ROTATE_HANDLE_SIZE_DP / 2f
        rule.onNodeWithTag("host").performTouchInput {
            fun at(deg: Double) = Offset((cx + radius * sin(Math.toRadians(deg))).toFloat() * density,
                (cy - radius * cos(Math.toRadians(deg))).toFloat() * density)
            down(at(0.0))
            for (step in 1..10) moveTo(at(step * 3.0))
            up()
        }
        rule.waitForIdle()
        assertTrue("rotation ${resizeRotation.floatValue}", resizeRotation.floatValue in 28f..32f)
        rule.onAllNodes(hasContentDescription("确认"))[0].performClick()
        rule.waitForIdle()
        assertEquals(resizeRotation.floatValue, confirmed, 0.01f)
    }

    @Test fun renderSheet() {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        show()
        data class Case(val label: String, val deg: Float, val x: Int, val y: Int, val resize: Boolean = false)
        val cases = listOf(
            Case("0°", 0f, 0, 120), Case("30°", 30f, 0, 120), Case("-45°", -45f, 0, 120),
            Case("90°", 90f, 0, 120), Case("20° 拖到最低", 20f, 0, 0), Case("-90° 拖到最左", -90f, -400, 120),
            Case("调节界面 0°", 0f, 0, 120, true), Case("调节界面 30°", 30f, 0, 120, true),
            Case("调节界面 -60°", -60f, 0, 120, true),
        )
        val shots = cases.map { c ->
            rule.runOnIdle {
                rotation = c.deg; offsetX = c.x; offsetY = c.y; resizing = c.resize
                resizeRotation.floatValue = c.deg
                resizeRect = ResizeRect(40f, hostH - c.y - cardH.toFloat(), 360f, hostH - c.y.toFloat())
            }
            rule.waitForIdle()
            val host = rule.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
            val root = rule.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(full)) }
            val left = host.left.toInt().coerceAtLeast(0)
            val top = host.top.toInt().coerceAtLeast(0)
            c.label to Bitmap.createBitmap(full, left, top, minOf(full.width - left, hostW), minOf(full.height - top, hostH))
        }
        val cellW = hostW + 12
        val cellH = hostH + 40
        val rows = (shots.size + 2) / 3
        val sheet = Bitmap.createBitmap(cellW * 3 + 12, cellH * rows + 12, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(0xFF808080.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 24f }
        shots.forEachIndexed { i, (label, bmp) ->
            val x = 12f + (i % 3) * cellW
            val y = 12f + (i / 3) * cellH
            canvas.drawText(label, x, y + 24f, paint)
            canvas.drawBitmap(bmp, x, y + 34f, null)
        }
        File(out).mkdirs()
        File(out, "${System.getenv("CYIME_RENDER_NAME") ?: "floating-rotation"}.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

@Composable
internal fun FloatingTestResizeEditor(preview: KeyboardResizePreviewState, contentHeight: Int, saveAngle: (Float) -> Unit,
    close: () -> Unit) {
    CompositionLocalProvider(LocalKeyboardResizePreviewState provides preview) {
        KeyboardResizeOverlay(contentHeight, contentHeight, 0, true, initialWidthDp = 320,
            onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
            onConfirm = { _, _, _, _ -> close() }, onCancel = close, onFloatingModeChange = {},
            onRotationConfirm = saveAngle, modifier = Modifier.fillMaxSize())
    }
}
