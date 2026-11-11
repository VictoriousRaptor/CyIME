package com.kingzcheung.xime.ui.render

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.FLOATING_DRAG_BAR_HEIGHT_DP
import com.kingzcheung.xime.ui.keyboard.FloatingKeyboardContainer
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardDockTarget
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.KeyboardResizePreviewState
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardResizePreviewState
import com.kingzcheung.xime.ui.keyboard.ResizeRect
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 位置提示框：切换悬浮/固定、拖完悬浮键盘后沿键盘外沿亮一圈强调色框，停一会儿淡出；
 * 调节界面里固定键盘往上拉（顶上胶囊或键盘内部空白）过阻力后变悬浮。
 * 设了 CYIME_RENDER_OUT 时另外把几种主题 × 玻璃 × 固定/悬浮的提示框拼成一张图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PositionHighlightRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val hostW = 600
    private val hostH = 420
    private val contentH = 220

    @Composable
    private fun FakeKeys(keyColor: Color = Color.White, textColor: Color = Color.Black) {
        Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().weight(0.8f)) { Text("候选 · 工具栏", color = textColor, fontSize = 13.sp) }
            for (row in listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")) Row(Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (key in row) Box(Modifier.weight(1f).fillMaxSize().background(keyColor, RoundedCornerShape(6.dp))) {
                    Text(key.toString(), color = textColor, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }

    private fun pullUpSwitchesToFloating(tag: String, grabFromTopDp: Float) {
        var floating = false
        val fixedRect = ResizeRect(0f, (hostH - contentH).toFloat(), hostW.toFloat(), hostH.toFloat())
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(hostW.dp, hostH.dp).testTag("host")) {
                    val preview = KeyboardResizePreviewState(rect = fixedRect, initialRect = fixedRect,
                        bounds = ResizeRect(0f, 0f, hostW.toFloat(), hostH.toFloat()))
                    CompositionLocalProvider(LocalKeyboardResizePreviewState provides preview) {
                        KeyboardResizeOverlay(contentH, contentH, 0, false, initialWidthDp = hostW,
                            onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
                            onConfirm = { _, _, _, _ -> }, onCancel = {}, onFloatingModeChange = { floating = it },
                            modeSwitchTarget = { _, w, h -> ResizeRect(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.6f) },
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = rule.activity.resources.displayMetrics.density
        val start = Offset(hostW / 2f * density, (fixedRect.top + grabFromTopDp) * density)
        rule.onNodeWithTag("host").performTouchInput {
            down(start)
            for (step in 1..20) moveTo(start + Offset(0f, -step * 8f * density))
            up()
        }
        rule.waitForIdle()
        assertTrue("$tag: pulling up 160dp should switch to floating", floating)
    }

    @Test fun pullingTheTopMovePillUpMakesItFloating() = pullUpSwitchesToFloating("pill", 42f)

    /** 「拖动移动」胶囊下面、按钮上面的键盘空白处。 */
    @Test fun pullingTheKeyboardBodyUpMakesItFloating() = pullUpSwitchesToFloating("body", 62f)

    @Test fun shortPullStaysFixed() {
        var floating = false
        val fixedRect = ResizeRect(0f, (hostH - contentH).toFloat(), hostW.toFloat(), hostH.toFloat())
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(hostW.dp, hostH.dp).testTag("host")) {
                    CompositionLocalProvider(LocalKeyboardResizePreviewState provides KeyboardResizePreviewState(
                        rect = fixedRect, initialRect = fixedRect, bounds = ResizeRect(0f, 0f, hostW.toFloat(), hostH.toFloat()))) {
                        KeyboardResizeOverlay(contentH, contentH, 0, false, initialWidthDp = hostW,
                            onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
                            onConfirm = { _, _, _, _ -> }, onCancel = {}, onFloatingModeChange = { floating = it },
                            modeSwitchTarget = { _, w, h -> ResizeRect(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.6f) },
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = rule.activity.resources.displayMetrics.density
        val start = Offset(hostW / 2f * density, (fixedRect.top + 62f) * density)
        rule.onNodeWithTag("host").performTouchInput {
            down(start)
            for (step in 1..6) moveTo(start + Offset(0f, -step * 8f * density))
            up()
        }
        rule.waitForIdle()
        assertEquals(false, floating)
    }

    private fun pullDockedHandle(pullDp: Float): Boolean {
        var undocked = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(hostW.dp, contentH.dp).testTag("host")) {
                    FloatingKeyboardContainer(isFloatingMode = false, scaleFactor = 1f, contentHeightDp = contentH,
                        offsetX = 0, offsetY = 0, onDrag = { _, _ -> }, onDragEnd = {}, onUndock = { undocked = true }) {
                        FakeKeys()
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = rule.activity.resources.displayMetrics.density
        val start = Offset(hostW / 2f * density, 6f * density)
        rule.onNodeWithTag("host").performTouchInput {
            down(start)
            val steps = 20
            for (step in 1..steps) moveTo(start + Offset(0f, -pullDp * step / steps * density))
            up()
        }
        rule.waitForIdle()
        return undocked
    }

    @Test fun pullingTheDockedHandleUpMakesItFloating() = assertTrue(pullDockedHandle(160f))

    @Test fun shortPullOnTheDockedHandleStaysDocked() = assertEquals(false, pullDockedHandle(60f))

    @Test fun floatingDragDocksOnADownwardReleaseButNotOnASidewaysMoveAtTheEdge() {
        var docks = 0
        var offsetY by mutableStateOf(20)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(hostW.dp, hostH.dp).testTag("host")) {
                    FloatingKeyboardContainer(isFloatingMode = true, scaleFactor = 0.7f, contentHeightDp = contentH,
                        offsetX = 0, offsetY = offsetY, minOffsetY = 20, onDrag = { _, _ -> }, onDragEnd = {},
                        onDock = { docks++ }, dockTarget = KeyboardDockTarget(hostW, contentH, 0, 0, 20)) { FakeKeys() }
                }
            }
        }
        val bar = rule.onNodeWithTag("floating-drag-bar")
        bar.performTouchInput {
            down(center)
            for (i in 1..8) moveTo(center + Offset(i * 10f, i * 0.5f))
        }
        rule.mainClock.advanceTimeBy(900)
        bar.performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, docks); offsetY = 170 }
        bar.performTouchInput {
            down(center)
            for (i in 1..18) moveTo(center + Offset(0f, i * 10f))
        }
        // 11-10：拖到底要停 0.75 秒、渐变框走完才变固定（不再一碰底就贴）。
        rule.mainClock.advanceTimeBy(900)
        bar.performTouchInput { up() }
        rule.runOnIdle { assertEquals("docks after holding at the bottom", 1, docks) }
    }

    private var floatingMode by mutableStateOf(false)

    @Composable
    private fun Variant(id: String, dark: Boolean, glass: Boolean) {
        val palette = resolveKeyboardPalette(id, dark)
        val glassConfig = FrostedGlassConfig.defaults(dark, enabled = glass)
        CompositionLocalProvider(LocalKeyboardPalette provides palette,
            LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = glassConfig)) {
            MaterialTheme {
                Box(Modifier.requiredSize(hostW.dp, hostH.dp).then(if (glass) Modifier.background(Brush.linearGradient(
                    listOf(Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828), Color(0xFF1565C0))))
                    else Modifier.background(if (dark) Color(0xFF202124) else Color(0xFFE8EAED)))) {
                    Box(Modifier.fillMaxSize()) {
                        FloatingKeyboardContainer(isFloatingMode = floatingMode, scaleFactor = 0.7f, contentHeightDp = contentH,
                            offsetX = 0, offsetY = 60, onDrag = { _, _ -> }, onDragEnd = {}, onUndock = {}) {
                            Box(Modifier.fillMaxSize().keyboardBackground(KeyboardThemes.getRenderingScheme(id).keyboardBackground,
                                dark, palette.background, glassConfig)) { FakeKeys(palette.key, palette.text) }
                        }
                    }
                }
            }
        }
    }

    @Test fun renderSheet() {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        val themes = (System.getenv("CYIME_RENDER_THEMES")
            ?: "soft_blue/light,soft_blue/dark,pure_black/light,pure_black/dark").split(",")
            .map { it.trim().split("/") }.map { it[0] to (it[1] == "dark") }
        val shots = mutableListOf<Pair<String, Bitmap>>()
        var variant by mutableStateOf(Triple(themes[0].first, themes[0].second, false))
        rule.setContent { val (vid, vdark, vglass) = variant; androidx.compose.runtime.key(variant) { Variant(vid, vdark, vglass) } }
        for ((id, dark) in themes) for (glass in listOf(false, true)) for (afterMs in listOf(0L, 700L, 1600L)) {
            rule.runOnIdle { floatingMode = false; variant = Triple(id, dark, glass) }
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            if (afterMs > 0) rule.runOnIdle { floatingMode = true }
            // 先走一帧让切换生效、动画开始计时，再按动画时间截图。
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeBy(afterMs)
            val root = rule.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { root.draw(android.graphics.Canvas(full)) }
            rule.mainClock.autoAdvance = true
            shots += "$id ${if (dark) "深" else "浅"} ${if (glass) "玻璃" else "不透明"} ${if (afterMs == 0L) "固定（顶边把手）" else "切悬浮后 ${afterMs}ms"}" to
                Bitmap.createBitmap(full, 0, 0, minOf(full.width, hostW), minOf(full.height, hostH))
        }
        val cellW = hostW + 12
        val cellH = hostH + 40
        val cols = 3
        val rows = (shots.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cellW * cols + 12, cellH * rows + 12, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(0xFF808080.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 22f }
        shots.forEachIndexed { i, (label, bmp) ->
            val x = 12f + (i % cols) * cellW
            val y = 12f + (i / cols) * cellH
            canvas.drawText(label, x, y + 24f, paint)
            canvas.drawBitmap(bmp, x, y + 34f, null)
        }
        File(out).mkdirs()
        File(out, "${System.getenv("CYIME_RENDER_NAME") ?: "position-highlight"}.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
