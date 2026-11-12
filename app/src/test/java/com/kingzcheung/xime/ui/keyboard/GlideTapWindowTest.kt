package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import com.kingzcheung.xime.glide.GlideTracker
import com.kingzcheung.xime.glide.shortGlideTapKey
import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 11-11「打两个字第二个字被吞」：开着滑行快打，手指一抖既没成滑行、又不算点按，这一下就没了。
 * 26 键滑行要横竖合起来走满 [GlideTracker.MIN_SPAN_KEYS] 个键才接管；没到这个距离都要算点按。
 * 接管了、却划得很短又认不出词的，按点了起点那个键算。
 */
class GlideTapWindowTest {
    // 手机 26 键的比例：键宽 36、键高 50，触摸阈值 8。
    private val w = 36f
    private val h = 50f
    private val slop = 8f

    @Test fun letterKeyDriftShortOfTheGlideClaimIsATap() {
        // 落在键的上沿，往上抖出键 25（半个键高）：以前出了键又超过 0.6 个短边（21.6），不算点按也不是滑行。
        val down = Offset(18f, 5f)
        val up = Offset(18f, -20f)
        assertFalse("九键的规则不变", glideDriftIsTap(down, up, w, h, slop))
        assertTrue(glideDriftIsTap(down, up, w, h, slop, letterGlide = true))
        // 从键中间往上 36（0.72 个键高）：还没到接管距离。
        assertTrue(glideDriftIsTap(Offset(18f, 25f), Offset(18f, -11f), w, h, slop, letterGlide = true))
        // 走满接管距离（竖着 0.8 个键高）还没成滑行（比如按住后才动）：照旧不出字。
        assertFalse(glideDriftIsTap(Offset(18f, 25f), Offset(18f, -15f), w, h, slop, letterGlide = true))
        // 拖远了（一个多键）：不出字。
        assertFalse(glideDriftIsTap(Offset(18f, 25f), Offset(60f, -30f), w, h, slop, letterGlide = true))
        assertTrue(GlideTracker.MIN_SPAN_KEYS < 0.8f)
    }

    private val keys: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, inset) ->
            letters.forEachIndexed { index, char -> put(char, LetterKeyRect((inset + index) * w, row * h, w, h)) }
        }
    }

    private fun line(from: Offset, to: Offset, steps: Int = 8): Pair<FloatArray, FloatArray> =
        FloatArray(steps + 1) { from.x + (to.x - from.x) * it / steps } to FloatArray(steps + 1) { from.y + (to.y - from.y) * it / steps }

    @Test fun shortUnrecognisedGlideTypesTheKeyItStartedOn() {
        // g 的中间往右上抖进 y/h（不到一个半键）：认不出词时按点了 g 算。
        val g = Offset(4.5f * w + w / 2, h + h / 2)
        val (xs, ys) = line(g, g + Offset(w * 0.8f, -h * 0.5f))
        assertEquals('g', shortGlideTapKey(keys, xs, ys))
        // 划了两个多键：是真的滑行，认不出也不乱出字母。
        val (lx, ly) = line(g, g + Offset(w * 2.5f, 0f))
        assertNull(shortGlideTapKey(keys, lx, ly))
        // 起点不在键上：不出字。
        val (ox, oy) = line(Offset(-20f, -20f), Offset(10f, 10f))
        assertNull(shortGlideTapKey(keys, ox, oy))
        assertNull(shortGlideTapKey(keys, FloatArray(0), FloatArray(0)))
    }
}
