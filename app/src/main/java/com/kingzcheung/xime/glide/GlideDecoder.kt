package com.kingzcheung.xime.glide

import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 英文滑行识别：把手指轨迹与词表中每个词“经过各字母键中心”的折线比较。
 *
 * 坐标统一除以键宽 [GlideKeyboard.keyUnit]，轨迹与模板都按弧长重采样到 [SAMPLES] 点，
 * 先用起点、终点距离筛掉绝大多数词，再对剩余词做带约束的 DTW；
 * 形状代价加上起止点偏差和词频先验（按 english.txt 的频率排名）排序。
 * 相邻重复字母合并（hello 与 helo 轨迹相同），同一轨迹的词按词频区分。
 * 源自 tools/glide_lab 的离线基线，只返回候选，不提交文字。
 */
class GlideKeyboard(val keyUnit: Float, val centers: Map<Char, Pair<Float, Float>>) {
    init {
        require(keyUnit.isFinite() && keyUnit > 0f)
    }

    /** 同一布局（键位置按 1/8 键宽量化）复用模板。 */
    val revision: Int = centers.entries.sortedBy { it.key }.fold(17) { hash, (key, point) ->
        31 * (31 * (31 * hash + key.code) + (point.first / keyUnit * 8f).toInt()) + (point.second / keyUnit * 8f).toInt()
    }
}

/** [cost] 含词频先验，用来排序；[shape] 只算轨迹（形状与起止点），按上下文重排时与双词表相加。 */
data class GlideCandidate(val word: String, val cost: Float, val shape: Float = cost)

/**
 * [maxEndpoint]：起点、终点离词的首末键中心最远多少（单位：键宽）；九键的键大、键少，收紧到不含上下相邻键，
 * 否则每次要比对的词多好几倍。
 */
class GlideDecoder(
    private val keyboard: GlideKeyboard,
    words: List<String>,
    private val maxEndpoint: Float = MAX_ENDPOINT,
    /** 中文：另按音节逐键认一遍（[SyllableGlide]），词表里没有的连划（如「印度一」）也能出整串编码。 */
    syllables: List<String>? = null,
) {
    private val syllableGlide = syllables?.let { SyllableGlide(keyboard, it) }
    /** [shape] 是合并相邻重复字母后的写法，形状相同的词只比一次。 */
    private class Template(val word: String, val shape: String, val prior: Float, val xs: FloatArray, val ys: FloatArray)

    /** 起止点够近的词：[endpoints] 是起止点偏差的代价，[least] 是加上词频和形状下界后，这个词代价至少多少。 */
    private class Reachable(val template: Template, val endpoints: Float, val least: Float)

    private val templates: List<Template>
    /** 各词首末键中心另存成连续数组：每一笔都要扫一遍全部词，绝大多数词在这一步就筛掉，不必去碰各词的模板。 */
    private val known: Set<String>
    private val startX: FloatArray
    private val startY: FloatArray
    private val endX: FloatArray
    private val endY: FloatArray
    val revision: Int get() = keyboard.revision

    init {
        val count = words.size.coerceAtLeast(1)
        val logMax = ln(count + 1.0).toFloat()
        templates = words.withIndex().mapNotNull { (rank, word) ->
            if (word.length !in 1..MAX_WORD || !word.all { keyboard.centers.containsKey(it) }) return@mapNotNull null
            val code = collapseRepeats(word)
            val px = FloatArray(code.length) { keyboard.centers.getValue(code[it]).first / keyboard.keyUnit }
            val py = FloatArray(code.length) { keyboard.centers.getValue(code[it]).second / keyboard.keyUnit }
            val (xs, ys) = resample(px, py)
            Template(word, code, ln(rank + 2.0).toFloat() / logMax, xs, ys)
        }
        known = templates.mapTo(HashSet()) { it.word }
        startX = FloatArray(templates.size) { templates[it].xs[0] }
        startY = FloatArray(templates.size) { templates[it].ys[0] }
        endX = FloatArray(templates.size) { templates[it].xs[SAMPLES - 1] }
        endY = FloatArray(templates.size) { templates[it].ys[SAMPLES - 1] }
    }

    val size: Int get() = templates.size

    /** [xs]/[ys] 为与键中心同一坐标系的轨迹点；轨迹过短或没有足够接近的词时返回空表。 */
    fun decode(xs: FloatArray, ys: FloatArray, limit: Int = 6): List<GlideCandidate> {
        if (xs.size < 2 || xs.size != ys.size) return emptyList()
        val unit = keyboard.keyUnit
        val qx = FloatArray(xs.size) { xs[it] / unit }
        val qy = FloatArray(ys.size) { ys[it] / unit }
        if (pathLength(qx, qy) < MIN_PATH_KEYS) return emptyList()
        val (sx, sy) = resample(qx, qy)
        val envelope = Envelope(sx, sy)
        val last = SAMPLES - 1
        // 先比平方（略放宽）筛掉远的词，留下的再照原样开方比较，结果与直接开方一样。
        val near = maxEndpoint * maxEndpoint * 1.001f
        val reachable = ArrayList<Reachable>()
        for (index in templates.indices) {
            val ax = sx[0] - startX[index]; val ay = sy[0] - startY[index]
            if (ax * ax + ay * ay > near) continue
            val bx = sx[last] - endX[index]; val by = sy[last] - endY[index]
            if (bx * bx + by * by > near) continue
            val template = templates[index]
            val start = hypot(sx[0] - template.xs[0], sy[0] - template.ys[0])
            if (start > maxEndpoint) continue
            val end = hypot(sx[last] - template.xs[last], sy[last] - template.ys[last])
            if (end > maxEndpoint) continue
            // 下界（几十次加法）已超过形状上限的词，DTW 算完只会更大。
            val lower = envelope.lowerBound(template.xs, template.ys, start, end)
            if (lower > MAX_SHAPE + BOUND_SLACK) continue
            val endpoints = (start + end) / 2f * ENDPOINT_WEIGHT
            reachable += Reachable(template, endpoints, endpoints + template.prior * PRIOR_WEIGHT + lower)
        }
        // 按“代价至少多少”从小到大算 DTW：前几名很快凑齐，后面的词下界一超过第 limit 名就全部不用算。
        // 形状相同的词起止点一样、只差词频，仍按词频先后算到，与逐个算的结果一样。
        reachable.sortBy { it.least }
        val results = ArrayList<GlideCandidate>()
        val shapes = HashMap<String, Float>()
        // 已有 limit 个候选后，第 limit 好的代价；比它差的词不必算完。
        val kept = java.util.PriorityQueue<Float>(limit.coerceAtLeast(1), reverseOrder())
        for (item in reachable) {
            if (kept.size >= limit && item.least > kept.peek() + BOUND_SLACK) break
            val template = item.template
            val fixed = item.endpoints + template.prior * PRIOR_WEIGHT
            val bound = if (kept.size < limit) MAX_SHAPE else min(MAX_SHAPE, kept.peek() - fixed)
            val shape = shapes.getOrPut(template.shape) { dtw(sx, sy, template.xs, template.ys, bound) }
            if (shape > MAX_SHAPE) continue
            val geometry = shape + item.endpoints
            val cost = geometry + template.prior * PRIOR_WEIGHT
            results += GlideCandidate(template.word, cost, geometry)
            if (kept.size < limit) kept += cost else if (cost < kept.peek()) { kept.poll(); kept += cost }
        }
        // 按音节认出的编码不在词表里时，与词表结果用同一把尺子（形状 + 起止点）比，词频按比词表最后一名还差算。
        // 直线路过的键加不加，形状几乎一样，按音节识别自己的名次（[SYLLABLE_RANK_COST]）分先后。
        syllableGlide?.decode(xs, ys)?.forEachIndexed { rank, code ->
            if (code in known || code.length > MAX_WORD) return@forEachIndexed
            val shape = collapseRepeats(code)
            // 末尾多按几下同一个键（naer / naerrr）划出来一模一样，只留名次靠前的那串。
            if (results.any { collapseRepeats(it.word) == shape }) return@forEachIndexed
            val px = FloatArray(shape.length) { keyboard.centers.getValue(shape[it]).first / unit }
            val py = FloatArray(shape.length) { keyboard.centers.getValue(shape[it]).second / unit }
            val (tx, ty) = resample(px, py)
            val start = hypot(sx[0] - tx[0], sy[0] - ty[0])
            val end = hypot(sx[last] - tx[last], sy[last] - ty[last])
            if (start > maxEndpoint || end > maxEndpoint) return@forEachIndexed
            val dtw = shapes.getOrPut(shape) { dtw(sx, sy, tx, ty) }
            if (dtw > MAX_SHAPE) return@forEachIndexed
            val geometry = dtw + (start + end) / 2f * ENDPOINT_WEIGHT
            results += GlideCandidate(code, geometry + UNLISTED_PRIOR * PRIOR_WEIGHT + rank * SYLLABLE_RANK_COST, geometry)
        }
        return results.sortedWith(compareBy<GlideCandidate> { it.cost }.thenBy { it.word }).take(limit)
    }

    companion object {
        const val SAMPLES = 32
        const val MAX_WORD = 20
        const val MIN_PATH_KEYS = 0.75f
        const val MAX_ENDPOINT = 1.0f
        const val MAX_SHAPE = 1.0f
        const val ENDPOINT_WEIGHT = 0.35f
        /** 下界与 DTW 求和顺序不同，浮点误差留一点余量，保证筛掉的词 DTW 算完也一定超过 bound。 */
        private const val BOUND_SLACK = 1e-4f
        /** 常用词与罕见词之间最多差这么多形状代价（单位：键宽）。 */
        const val PRIOR_WEIGHT = 0.2f
        /** 词表外、按音节认出的编码的词频先验（词表内 0～1）。 */
        const val UNLISTED_PRIOR = 1.5f
        const val SYLLABLE_RANK_COST = 0.05f

        internal fun collapseRepeats(word: String): String = buildString {
            for (char in word) if (isEmpty() || last() != char) append(char)
        }

        private fun pathLength(xs: FloatArray, ys: FloatArray): Float {
            var length = 0f
            for (i in 1 until xs.size) length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            return length
        }

        /** 按弧长等距取样；停顿不增加样本。单点（单字母词）重复同一点。 */
        internal fun resample(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> = resampleTo(xs, ys, SAMPLES)

        /** 按弧长等距取 [count] 点。 */
        internal fun resampleTo(xs: FloatArray, ys: FloatArray, count: Int): Pair<FloatArray, FloatArray> {
            val outX = FloatArray(count)
            val outY = FloatArray(count)
            val cumulative = FloatArray(xs.size)
            for (i in 1 until xs.size) cumulative[i] = cumulative[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            val total = cumulative.last()
            if (total <= 0f) {
                outX.fill(xs[0]); outY.fill(ys[0])
                return outX to outY
            }
            var segment = 1
            for (sample in 0 until count) {
                val target = total * sample / (count - 1)
                while (segment < xs.lastIndex && cumulative[segment] < target) segment++
                val length = cumulative[segment] - cumulative[segment - 1]
                val fraction = if (length <= 0f) 0f else ((target - cumulative[segment - 1]) / length).coerceIn(0f, 1f)
                outX[sample] = xs[segment - 1] + fraction * (xs[segment] - xs[segment - 1])
                outY[sample] = ys[segment - 1] + fraction * (ys[segment] - ys[segment - 1])
            }
            return outX to outY
        }

        /** 两点距离；比 kotlin.math.hypot（走 Math.hypot，防溢出但慢很多）快，坐标都是几个键宽，不会溢出。 */
        private fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

        /** DTW 的带宽：第 i 个样本只与对方第 i±[BAND] 个样本对齐。 */
        private const val BAND = SAMPLES / 4

        /**
         * 轨迹每个样本附近（±[BAND]）的外接矩形。DTW 路径上模板的每个样本至少对齐一个带宽内的轨迹样本，
         * 所以“模板各样本到对应矩形的距离”之和不超过 DTW 代价，可作下界（LB_Keogh），每个词只算 [SAMPLES] 次距离。
         */
        private class Envelope(xs: FloatArray, ys: FloatArray) {
            private val minX = FloatArray(SAMPLES); private val maxX = FloatArray(SAMPLES)
            private val minY = FloatArray(SAMPLES); private val maxY = FloatArray(SAMPLES)

            init {
                for (j in 0 until SAMPLES) {
                    var x0 = Float.POSITIVE_INFINITY; var x1 = Float.NEGATIVE_INFINITY
                    var y0 = Float.POSITIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
                    for (i in max(0, j - BAND)..min(SAMPLES - 1, j + BAND)) {
                        x0 = min(x0, xs[i]); x1 = max(x1, xs[i]); y0 = min(y0, ys[i]); y1 = max(y1, ys[i])
                    }
                    minX[j] = x0; maxX[j] = x1; minY[j] = y0; maxY[j] = y1
                }
            }

            /**
             * 与 [dtw] 同一单位（按样本数归一）。DTW 首尾固定，首样本、末样本的那一格必在路径上，
             * 直接用已算好的起点距离 [start]、终点距离 [end]。
             */
            fun lowerBound(bx: FloatArray, by: FloatArray, start: Float, end: Float): Float {
                var sum = start + end
                for (j in 1 until SAMPLES - 1) {
                    val dx = if (bx[j] < minX[j]) minX[j] - bx[j] else if (bx[j] > maxX[j]) bx[j] - maxX[j] else 0f
                    val dy = if (by[j] < minY[j]) minY[j] - by[j] else if (by[j] > maxY[j]) by[j] - maxY[j] else 0f
                    if (dx != 0f || dy != 0f) sum += hypot(dx, dy)
                }
                return sum / SAMPLES
            }
        }

        /**
         * 首尾固定、带宽为四分之一的 DTW，按样本数归一。
         * 一整行都超过 [bound]（不超过 [MAX_SHAPE]）时提前结束、当作筛掉：累计代价逐行只增不减，
         * 最后的结果只会更大，这个词反正进不了前几名，排出的候选与算完一样。
         */
        private fun dtw(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray, bound: Float = MAX_SHAPE): Float {
            val n = SAMPLES
            val band = max(1, BAND)
            var previous = FloatArray(n + 1) { Float.POSITIVE_INFINITY }
            var current = FloatArray(n + 1) { Float.POSITIVE_INFINITY }
            previous[0] = 0f
            for (i in 1..n) {
                current.fill(Float.POSITIVE_INFINITY)
                var rowMin = Float.POSITIVE_INFINITY
                for (j in max(1, i - band)..min(n, i + band)) {
                    current[j] = hypot(ax[i - 1] - bx[j - 1], ay[i - 1] - by[j - 1]) +
                        min(previous[j - 1], min(previous[j], current[j - 1]))
                    if (current[j] < rowMin) rowMin = current[j]
                }
                if (rowMin > bound * n) return Float.POSITIVE_INFINITY
                val swap = previous
                previous = current
                current = swap
            }
            return previous[n] / n
        }
    }
}
