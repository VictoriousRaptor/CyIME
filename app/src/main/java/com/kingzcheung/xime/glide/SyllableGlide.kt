package com.kingzcheung.xime.glide

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 中文滑行按音节逐键识别（#294：小鹤双拼划「yb du yi」印度一，词表里没有这个词，整词比对把它挤成别的词，看起来吞字）。
 *
 * 不查词表，只要求划过的键能切成一串合法音节（双拼两键一音节，全拼按音节表），在轨迹上找一串「命中点」：
 * 命中点离键中心越近越好，两个命中点之间的轨迹要贴着两键中心的连线走（拐弯处必须是一个键），
 * 每多认一个键、每多一个音节都要付一点代价（直线路过的键不算），常用音节代价小。
 * 用动态规划（维特比）在 轨迹位置 × 音节前缀 上求总代价最小的几串编码，交给 [GlideDecoder] 与词表结果一起排序，
 * 最后照常交给 Rime 组句。连按同一个键（双拼 ii = chi）在同一处算两次，另付 [REPEAT_COST]。
 *
 * 坐标单位与 [GlideDecoder] 一样是键宽。
 */
class SyllableGlide(private val keyboard: GlideKeyboard, syllables: List<String>) {
    /** 音节前缀树：0 号是根；[char] 为该节点的键，[syllableCost] 非空表示到这里是一个完整音节。 */
    private val char = ArrayList<Char>()
    private val children = ArrayList<HashMap<Char, Int>>()
    private val syllableCost = ArrayList<Float>()

    init {
        char += ' '; children += HashMap(); syllableCost += Float.NaN
        val distinct = syllables.filter { syllable -> syllable.isNotEmpty() && syllable.all { keyboard.centers.containsKey(it) } }.distinct()
        val logMax = ln(distinct.size + 1.0).toFloat()
        // 越靠前越常用：代价从 SYLLABLE_COST 到 SYLLABLE_COST + SYLLABLE_PRIOR。
        distinct.forEachIndexed { rank, syllable ->
            var node = 0
            for (c in syllable) {
                node = children[node].getOrPut(c) {
                    char += c; children += HashMap(); syllableCost += Float.NaN
                    char.size - 1
                }
            }
            if (syllableCost[node].isNaN()) syllableCost[node] = SYLLABLE_COST + SYLLABLE_PRIOR * ln(rank + 2.0).toFloat() / logMax
        }
    }

    private val keys: CharArray = keyboard.centers.keys.toCharArray()
    private val keyX = FloatArray(keys.size) { keyboard.centers.getValue(keys[it]).first / keyboard.keyUnit }
    private val keyY = FloatArray(keys.size) { keyboard.centers.getValue(keys[it]).second / keyboard.keyUnit }

    /** 最多 [limit] 串编码，按代价从小到大；轨迹太短或切不成音节时为空。 */
    fun decode(xs: FloatArray, ys: FloatArray, limit: Int = 3): List<String> {
        if (xs.size < 2 || xs.size != ys.size) return emptyList()
        val unit = keyboard.keyUnit
        val (px, py) = resampleByStep(FloatArray(xs.size) { xs[it] / unit }, FloatArray(ys.size) { ys[it] / unit })
        val n = px.size
        val step = if (n > 1) pathLength(px, py) / (n - 1) else 0f
        if (n < 2) return emptyList()
        // 每个轨迹点附近的键（键号）与命中代价。
        val nearKeys = Array(n) { i ->
            keys.indices.filter { k -> dist(px[i], py[i], keyX[k], keyY[k]) <= HIT_RADIUS }.toIntArray()
        }
        // 从 i 出发轨迹大致还是直线的最远位置：两命中点之间只能是一段直线，拐弯处要另起一键。
        val straightUntil = IntArray(n) { i ->
            var j = i + 1
            while (j < n && maxDeviation(px, py, i, j) <= STRAIGHT_TOLERANCE &&
                (j - i) * step - dist(px[i], py[i], px[j], py[j]) <= STRAIGHT_TOLERANCE) j++
            j - 1
        }
        val nodes = char.size
        val cost = Array(n) { FloatArray(nodes) { Float.POSITIVE_INFINITY } }
        // 回溯：上一个命中点的位置与节点，-1 表示起点。
        val fromI = Array(n) { IntArray(nodes) { -1 } }
        val fromNode = Array(n) { IntArray(nodes) { -1 } }
        for (k in nearKeys[0]) {
            val node = children[0][keys[k]] ?: continue
            cost[0][node] = min(cost[0][node], HIT_WEIGHT * dist(px[0], py[0], keyX[k], keyY[k]))
        }
        for (i in 0 until n) {
            // 连按同一个键：位置不动，再认一次。另起音节时目标是根下的节点（编号更小），先单独做完。
            for (node in 1 until nodes) {
                val here = cost[i][node]
                if (here.isFinite()) relax(cost, fromI, fromNode, i, node, i, keyIndex(char[node]), here + KEY_COST + REPEAT_COST)
            }
            for (node in 1 until nodes) {
                val here = cost[i][node]
                if (!here.isFinite()) continue
                val k = keyIndex(char[node])
                for (j in i + 1..straightUntil[i]) {
                    for (next in nearKeys[j]) {
                        if (next == k) continue
                        val value = here + segmentCost(px, py, i, j, k, next, step) + KEY_COST +
                            HIT_WEIGHT * dist(px[j], py[j], keyX[next], keyY[next])
                        relax(cost, fromI, fromNode, i, node, j, next, value)
                    }
                }
            }
        }
        val last = n - 1
        val ends = (0 until nodes).filter { !syllableCost[it].isNaN() && cost[last][it].isFinite() }
            .sortedBy { cost[last][it] + syllableCost[it] }
        val codes = LinkedHashSet<String>()
        for (end in ends) {
            codes += backtrack(fromI, fromNode, last, end)
            if (codes.size >= limit) break
        }
        return codes.toList()
    }

    /** 从 (i, node) 认到 j 处的 [next] 键：接着当前音节，或当前音节已完整时另起一个音节。 */
    private fun relax(cost: Array<FloatArray>, fromI: Array<IntArray>, fromNode: Array<IntArray>,
        i: Int, node: Int, j: Int, next: Int, value: Float) {
        val c = keys[next]
        children[node][c]?.let { target -> update(cost, fromI, fromNode, i, node, j, target, value) }
        if (!syllableCost[node].isNaN()) children[0][c]?.let { target ->
            update(cost, fromI, fromNode, i, node, j, target, value + syllableCost[node])
        }
    }

    private fun update(cost: Array<FloatArray>, fromI: Array<IntArray>, fromNode: Array<IntArray>,
        i: Int, node: Int, j: Int, target: Int, value: Float) {
        if (value < cost[j][target]) {
            cost[j][target] = value
            fromI[j][target] = i
            fromNode[j][target] = node
        }
    }

    private fun backtrack(fromI: Array<IntArray>, fromNode: Array<IntArray>, last: Int, end: Int): String {
        val letters = StringBuilder()
        var i = last
        var node = end
        while (node > 0) {
            letters.append(char[node])
            val previousI = fromI[i][node]
            val previousNode = fromNode[i][node]
            if (previousI < 0) break
            i = previousI; node = previousNode
        }
        return letters.reverse().toString()
    }

    private fun keyIndex(c: Char): Int = keys.indexOf(c)

    /**
     * i..j 之间各点到两键中心连线的距离按步长累加（偏离的面积），再加上轨迹比弦长多走的路：
     * 沿同一条线折返（y→u→y）离连线不远，只有多走的路看得出来，折返处必须认一个键。
     */
    private fun segmentCost(px: FloatArray, py: FloatArray, i: Int, j: Int, a: Int, b: Int, step: Float): Float {
        var sum = 0f
        for (m in i + 1 until j) sum += pointToSegment(px[m], py[m], keyX[a], keyY[a], keyX[b], keyY[b])
        val detour = ((j - i) * step - dist(px[i], py[i], px[j], py[j])).coerceAtLeast(0f)
        return SEGMENT_WEIGHT * sum * step + DETOUR_WEIGHT * detour
    }

    companion object {
        /** 重采样步长（键宽）；轨迹很长时放大，点数不超过 [MAX_POINTS]。 */
        const val STEP = 0.25f
        const val MAX_POINTS = 120
        /** 命中点离键中心最远多少（键宽）。 */
        const val HIT_RADIUS = 1.0f
        const val HIT_WEIGHT = 1.5f
        const val SEGMENT_WEIGHT = 1.5f
        /** 每认一个键的代价：直线路过的键不该算进来。 */
        const val KEY_COST = 0.45f
        const val REPEAT_COST = 0.6f
        const val DETOUR_WEIGHT = 3.0f
        const val SYLLABLE_COST = 0.2f
        const val SYLLABLE_PRIOR = 0.6f
        /** 两命中点之间轨迹离弦的最大距离（键宽），超过就是拐了弯。 */
        const val STRAIGHT_TOLERANCE = 0.5f

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        private fun pointToSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax; val vy = by - ay
            val length = vx * vx + vy * vy
            val t = if (length <= 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / length).coerceIn(0f, 1f)
            return dist(x, y, ax + t * vx, ay + t * vy)
        }

        private fun pathLength(xs: FloatArray, ys: FloatArray): Float {
            var total = 0f
            for (i in 1 until xs.size) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return total
        }

        private fun maxDeviation(px: FloatArray, py: FloatArray, i: Int, j: Int): Float {
            var worst = 0f
            for (m in i + 1 until j) worst = max(worst, pointToSegment(px[m], py[m], px[i], py[i], px[j], py[j]))
            return worst
        }

        /** 按弧长每 [STEP] 取一点（首尾必取）。 */
        internal fun resampleByStep(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
            val total = pathLength(xs, ys)
            val step = max(STEP, total / (MAX_POINTS - 1))
            val count = max(2, (total / step).toInt() + 1)
            return GlideDecoder.resampleTo(xs, ys, count)
        }
    }
}
