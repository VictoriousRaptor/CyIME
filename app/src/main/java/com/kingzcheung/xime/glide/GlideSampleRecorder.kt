package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 「贡献滑行轨迹」页用真键盘滑（用户 11-09：出题，然后正常打字，不要另画一个小键盘）。
 * 键盘每确认一笔滑行就交给 [listener]；只有贡献页开着时才有 listener，平时什么都不记、不多花一点功夫。
 * 输入法和设置页在同一进程，直接传对象。坐标是键盘根坐标，[keys] 与轨迹同一坐标系。
 */
object GlideSampleRecorder {
    class Stroke(
        /** "qwerty"（26 键，中英文都是）或 "t9"（九键）。 */
        val layout: String,
        /** 26 键是 a～z，九键是 2～9。 */
        val keys: Map<Char, LetterKeyRect>,
        val xs: FloatArray, val ys: FloatArray, val ts: LongArray,
    )

    @Volatile var listener: ((Stroke) -> Unit)? = null

    fun record(layout: String, keys: Map<Char, LetterKeyRect>, xs: FloatArray, ys: FloatArray, ts: LongArray) {
        val l = listener ?: return
        if (keys.isEmpty() || xs.isEmpty() || xs.size != ys.size || ys.size != ts.size) return
        l(Stroke(layout, keys, xs, ys, ts))
    }

    private const val T9_DIGITS = "22233344455566677778889999"

    /** 题目里的字母在这种键盘上是哪个键。 */
    fun keyFor(layout: String, letter: Char): Char? {
        val c = letter.lowercaseChar()
        if (c !in 'a'..'z') return null
        return if (layout == "t9") T9_DIGITS[c - 'a'] else c
    }

    /** 点落在哪个键；落在缝里取中心最近的键。 */
    fun keyAt(keys: Map<Char, LetterKeyRect>, x: Float, y: Float): Char? =
        keys.entries.firstOrNull { (_, r) -> x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height }?.key
            ?: keys.minByOrNull { (_, r) -> hypot(r.x + r.width / 2 - x, r.y + r.height / 2 - y) }?.key

    /**
     * 这一笔能不能当 [target] 的样本：从题目第一个字母的键起、在最后一个字母的键停（同原来的练习板）。
     * 能的话给出上传的一行 JSON，字段和原来练习板的一样（id、layout、target、aspect、points），
     * 坐标换成相对整个字母区（26 键 a～z、九键 2～9 的外框）的 0～1000，时间从起手算、毫秒、最多 30 秒，点数最多 256。
     */
    fun sample(stroke: Stroke, target: String, id: String = UUID.randomUUID().toString().replace("-", "")): String? {
        if (target.isEmpty()) return null
        val first = keyFor(stroke.layout, target.first()) ?: return null
        val last = keyFor(stroke.layout, target.last()) ?: return null
        val n = stroke.xs.size
        if (keyAt(stroke.keys, stroke.xs[0], stroke.ys[0]) != first) return null
        if (keyAt(stroke.keys, stroke.xs[n - 1], stroke.ys[n - 1]) != last) return null
        val left = stroke.keys.values.minOf { it.x }
        val top = stroke.keys.values.minOf { it.y }
        val width = stroke.keys.values.maxOf { it.x + it.width } - left
        val height = stroke.keys.values.maxOf { it.y + it.height } - top
        if (width <= 0f || height <= 0f) return null
        val points = ArrayList<List<Int>>()
        for (i in 0 until n) {
            if (points.size == MAX_POINTS) {
                val kept = points.filterIndexed { index, _ -> index % 2 == 0 }
                points.clear(); points.addAll(kept)
            }
            val x = ((stroke.xs[i] - left) * 1000 / width).roundToInt().coerceIn(0, 1000)
            val y = ((stroke.ys[i] - top) * 1000 / height).roundToInt().coerceIn(0, 1000)
            val t = (stroke.ts[i] - stroke.ts[0]).coerceIn(0L, MAX_MS).toInt()
            points.add(listOf(x, y, t))
        }
        return JSONObject().put("id", id).put("layout", stroke.layout).put("target", target)
            .put("aspect", width.toDouble() / height).put("points", JSONArray(points)).toString()
    }

    private const val MAX_POINTS = 256
    private const val MAX_MS = 30000L
}
