package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val bubbleColor = Color(0xff37b9bf)

internal class PressBubbleScene {
    val typed = StringBuilder()
    var enabled by mutableStateOf(true)
    var windowControl = false
    var rotation = 0f
    var firstKeyCenter = Offset.Zero

    @Composable
    fun Content() {
        CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(showPressBubble = enabled),
            LocalFloatingKeyboardRotation provides rotation) {
            Box(Modifier.fillMaxSize().background(Color(0xffedf1f4)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(240.dp, 110.dp).graphicsLayer { rotationZ = rotation }.clip(RoundedCornerShape(12.dp))
                    .background(Color(0xff273242)).testTag("frame-card")) {
                    val controller = rememberSwipeBubbleController()
                    val density = LocalDensity.current
                    val width = with(density) { 240.dp.toPx() }
                    if (windowControl) {
                        SwipeBubbleOverlay(rememberSwipeBubbleDrawData(controller.state, controller.keyBounds,
                            bubbleColor, Color.White, Color.White, controller.keyBounds.width, width))
                    } else {
                        KeyboardSwipeBubbleOverlay(controller, bubbleColor, Color.White, Color.White, width)
                    }
                    Row(Modifier.padding(start = 16.dp, top = 4.dp)) {
                        for ((i, label) in listOf("A", "B").withIndex()) {
                            val key = with(density) {
                                val left = (16 + 64 * i).dp.toPx()
                                Rect(left, 4.dp.toPx(), left + 64.dp.toPx(), 60.dp.toPx())
                            }
                            KeyButton(label, { typed.append(label) }, Color.DarkGray, Color.White,
                                Modifier.size(64.dp, 56.dp).onGloballyPositioned {
                                    if (i == 0) firstKeyCenter = it.localToWindow(Offset(it.size.width / 2f, it.size.height / 2f))
                                }.testTag("frame-key:$label"),
                                onSwipeStateChange = { controller.update(it, key) })
                        }
                    }
                }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PressBubbleFrameRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun open(scene: PressBubbleScene) {
        KeysConfigHelper.loadConfig(rule.activity)
        AppFonts.initialize(rule.activity)
        rule.setContent { scene.Content() }
        rule.waitForIdle()
    }

    private fun shot(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        System.getenv("CYIME_RENDER_OUT")?.let { dir ->
            java.io.File(dir, "press-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    @Test fun firstFrameIsFullyPaintedAboveClippedKeysAndHasNoPopupWindow() {
        val scene = PressBubbleScene()
        open(scene)
        val key = rule.onNodeWithTag("frame-key:A")
        val top = rule.onNodeWithTag("frame-card").fetchSemanticsNode().positionOnScreen.y.toInt()
        fun paintedAboveCard(name: String): Int {
            val bitmap = shot(name)
            val pixels = IntArray(bitmap.width * top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, top)
            return pixels.count { it == bubbleColor.toArgb() }
        }
        assertEquals(0, paintedAboveCard("idle"))
        val idle = shot("idle")
        rule.mainClock.autoAdvance = false
        key.performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        val first = paintedAboveCard("first")
        rule.mainClock.advanceTimeBy(96)
        val held = paintedAboveCard("held")
        rule.onNodeWithTag("key-choice-popup", useUnmergedTree = true).assertExists()
        rule.onAllNodes(isPopup()).assertCountEquals(0)
        assertTrue("No opaque preview above the card on the first frame ($first, held $held)", first > 100)
        assertEquals(first, held)
        key.performTouchInput { up() }
        assertEquals("松手当下就上屏，不等气泡", "A", scene.typed.toString())
        // 松手停留 60 ms 后淡出（CyMotion.EXIT_FAST）：淡出中不再是不透明的气泡色，但还看得见；淡完什么都不留。
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, paintedAboveCard("released"))
        assertTrue("淡出中应还有半透明的气泡", changedAboveCard(idle, top) > 100)
        rule.mainClock.advanceTimeBy(com.kingzcheung.xime.ui.theme.CyMotion.EXIT_FAST + 64L)
        assertEquals(0, changedAboveCard(idle, top))
        assertEquals("A", scene.typed.toString())
    }

    /** 卡片上方和没按键时不一样的像素（淡出中的半透明气泡和它的阴影）。 */
    private fun changedAboveCard(idle: Bitmap, top: Int): Int {
        val bitmap = shot("fading")
        val now = IntArray(bitmap.width * top)
        val before = IntArray(bitmap.width * top)
        bitmap.getPixels(now, 0, bitmap.width, 0, 0, bitmap.width, top)
        idle.getPixels(before, 0, idle.width, 0, 0, idle.width, top)
        return now.indices.count { now[it] != before[it] }
    }

    @Test fun rotatedPreviewStaysCenteredAboveItsKey() {
        val scene = PressBubbleScene().apply { rotation = 24f }
        open(scene)
        val key = rule.onNodeWithTag("frame-key:A")
        key.performTouchInput { down(center) }
        val bitmap = shot("rotated")
        var count = 0
        var sumX = 0L
        var sumY = 0L
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == bubbleColor.toArgb()) { count++; sumX += x; sumY += y }
        }
        assertTrue(count > 100)
        val dx = sumX.toDouble() / count - scene.firstKeyCenter.x
        val dy = sumY.toDouble() / count - scene.firstKeyCenter.y
        val angle = Math.toRadians(scene.rotation.toDouble())
        assertEquals("aligned along the key's rotated vertical axis", 0.0,
            dx * Math.cos(angle) + dy * Math.sin(angle), 3.0)
        assertTrue("above the key", dx * Math.sin(angle) - dy * Math.cos(angle) > 20.0)
        key.performTouchInput { up() }
    }
}
