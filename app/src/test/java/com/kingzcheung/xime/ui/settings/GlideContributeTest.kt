package com.kingzcheung.xime.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.glide.GlideSampleRecorder
import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 贡献滑行轨迹（用户 11-09：删掉另画的小键盘，出题后用真键盘正常滑，顶上选键盘）。
 * 键盘那边交来的一笔用 [GlideSampleRecorder.record] 模拟。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlideContributeTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @After fun clear() { GlideSampleRecorder.listener = null }

    // 26 键：每键 100×60，第二行缩进半键、第三行缩进一键半。
    private val qwerty: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f).forEachIndexed { row, (letters, indent) ->
            letters.forEachIndexed { col, c -> put(c, LetterKeyRect(indent + col * 100f, row * 60f, 100f, 60f)) }
        }
    }
    // 九键 2～9：3×3，1 键那格空着。
    private val nine: Map<Char, LetterKeyRect> = buildMap {
        for (d in '2'..'9') { val i = d - '1'; put(d, LetterKeyRect(i % 3 * 120f, i / 3 * 80f, 120f, 80f)) }
    }
    private fun center(keys: Map<Char, LetterKeyRect>, c: Char) = keys.getValue(c).let { it.x + it.width / 2 to it.y + it.height / 2 }

    private fun stroke(layout: String, keys: Map<Char, LetterKeyRect>, from: Char, to: Char, steps: Int = 8): GlideSampleRecorder.Stroke {
        val (x0, y0) = center(keys, from); val (x1, y1) = center(keys, to)
        val xs = FloatArray(steps + 1) { x0 + (x1 - x0) * it / steps }
        val ys = FloatArray(steps + 1) { y0 + (y1 - y0) * it / steps }
        val ts = LongArray(steps + 1) { 5000L + it * 16L }
        return GlideSampleRecorder.Stroke(layout, keys, xs, ys, ts)
    }
    private fun send(s: GlideSampleRecorder.Stroke) = rule.runOnIdle { GlideSampleRecorder.record(s.layout, s.keys, s.xs, s.ys, s.ts) }

    @Test fun sampleKeepsTheOldFormatAndChecksStartAndEndKeys() {
        val ok = JSONObject(GlideSampleRecorder.sample(stroke("qwerty", qwerty, 'h', 'o'), "hello", id = "x")!!)
        assertEquals(setOf("target", "layout", "id", "points", "aspect"), ok.keys().asSequence().toSet())
        assertEquals("hello", ok.getString("target")); assertEquals("qwerty", ok.getString("layout"))
        assertEquals(1000.0 / 180.0, ok.getDouble("aspect"), 1e-6)
        val points = ok.getJSONArray("points")
        assertEquals(0, points.getJSONArray(0).getInt(2))
        for (i in 0 until points.length()) points.getJSONArray(i).let { p ->
            assertTrue(p.getInt(0) in 0..1000 && p.getInt(1) in 0..1000)
        }
        assertNull("起点不在 h", GlideSampleRecorder.sample(stroke("qwerty", qwerty, 'g', 'o'), "hello"))
        assertNull("终点不在 o", GlideSampleRecorder.sample(stroke("qwerty", qwerty, 'h', 'p'), "hello"))
        // 九键按字母所在的数字键：women = 9…6。
        assertNotNull(GlideSampleRecorder.sample(stroke("t9", nine, '9', '6'), "women"))
        assertNull(GlideSampleRecorder.sample(stroke("t9", nine, '5', '6'), "women"))
        // 点很多时减半，最多 256 个。
        val long = JSONObject(GlideSampleRecorder.sample(stroke("qwerty", qwerty, 'h', 'o', steps = 900), "hello")!!)
        assertTrue(long.getJSONArray("points").length() <= 256)
    }

    @Test fun nothingIsRecordedWithoutThePage() {
        GlideSampleRecorder.listener = null
        GlideSampleRecorder.record("qwerty", qwerty, floatArrayOf(1f), floatArrayOf(1f), longArrayOf(1L))
        assertNull(GlideSampleRecorder.listener)
    }

    @Test fun realKeyboardStrokesAdvanceAndUploadOnlyAfterConsent() {
        val sent = mutableListOf<String>()
        rule.setContent { MaterialTheme { GlideContributeScreen(onBack = {}, upload = { _, rows -> sent.addAll(rows) }) } }
        rule.onNodeWithTag("glide-contribute-field").performClick()
        rule.onNodeWithText("请滑出 hello").assertExists()
        // 滑错：题目不换，写原因。
        send(stroke("qwerty", qwerty, 'g', 'o'))
        rule.onNodeWithText("请滑出 hello").assertExists()
        rule.onNodeWithText("没算上：请从 h 滑到 o。").assertExists()
        // 键盘不对：九键的轨迹不算英文 26 键的题。
        send(stroke("t9", nine, '4', '6'))
        rule.onNodeWithText("现在是九键，这一页要英文 26 键。").assertExists()
        // 滑对：换下一题；没开贡献不上传。
        send(stroke("qwerty", qwerty, 'h', 'o'))
        rule.onNodeWithText("请滑出 world").assertExists()
        rule.mainClock.advanceTimeBy(2000)
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
        // 开贡献要先同意协议；不同意就不开。
        rule.onNodeWithText("自动贡献").performClick()
        rule.onNodeWithText("暂不贡献").performClick()
        rule.onNodeWithText("已上传 0 条，待传 0 条").assertDoesNotExist()
        rule.onNodeWithText("自动贡献").performClick()
        rule.onNodeWithText("同意协议").performClick()
        rule.onNodeWithText("已上传 0 条，待传 0 条").assertExists()
        rule.onNodeWithTag("glide-contribute-field").performClick()
        send(stroke("qwerty", qwerty, 'w', 'd'))
        rule.onNodeWithText("请滑出 good").assertExists()
        rule.waitUntil(8000) {
            rule.mainClock.advanceTimeBy(100)
            sent.isNotEmpty()
        }
        assertEquals(1, sent.size)
        assertEquals("world", JSONObject(sent.single()).getString("target"))
        System.getenv("CYIME_RENDER_OUT")?.let { dir ->
            val view = rule.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { view.draw(Canvas(image)) }
            File(dir, "glide-contribute.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // 关掉贡献后滑的不再上传。
        rule.onNodeWithText("自动贡献").performClick()
        rule.onNodeWithTag("glide-contribute-field").performClick()
        send(stroke("qwerty", qwerty, 'g', 'd'))
        rule.mainClock.advanceTimeBy(2500)
        rule.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun modeMenuSwitchesToNineKeyPrompts() {
        rule.setContent { MaterialTheme { GlideContributeScreen(onBack = {}, upload = { _, _ -> }) } }
        rule.onNodeWithTag("glide-contribute-mode").performClick()
        rule.onNodeWithText("九键").performClick()
        rule.onNodeWithText("请滑出 nihao").assertExists()
        rule.onNodeWithTag("glide-contribute-field").performClick()
        send(stroke("t9", nine, '6', '6'))  // n、o 都在 6
        rule.onNodeWithText("请滑出 shuru").assertExists()
        GlideSampleRecorder.listener?.let { } ?: fail("页面开着时要有人收轨迹")
    }
}
