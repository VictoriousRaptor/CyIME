package com.kingzcheung.xime.handwriting

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 内置手写模型字表外的生僻字（犇、垚、婍、䴓…）。模型只认 7185 个汉字，字表外的字靠笔顺模板：
 * 把模型输出（概率最高的 [QUERY_TOP] 类开方归一化）当特征，和每个生僻字的模板比余弦，与日语字表外漢字同一做法。
 *
 * 数据：assets/handwriting/hanzi_rare_och.{bin,json}，由 tools/handwriting_training/build_rare_hanzi.py 生成。
 * 每个模板 = 字序号 + 特征里最大的 [n] 项（类序号、数值×255），第一项是模板的首位类；查询时只比
 * 首位类落在查询前 [BUCKET_TOP] 类里的模板（平均约 400 个），不逐个比四万多个。
 */
internal class HanziRareTemplates private constructor(
    private val labels: IntArray,
    private val labelOf: IntArray,
    private val cls: ShortArray,
    private val vals: ByteArray,
    private val n: Int,
    private val bucketStart: IntArray,
    private val bucketItems: IntArray,
) {
    val templateCount: Int get() = labelOf.size

    /**
     * 和查询最像的生僻字：[raw] 为模型原始输出（类序号、概率，从高到低，取前 [QUERY_TOP]）。
     * 返回（字、余弦相似度），同一个字取几个模板里最像的，相似度从高到低，最多 [limit] 个。
     */
    fun match(raw: List<Pair<Int, Float>>, limit: Int, minSimilarity: Float): List<Pair<String, Float>> {
        if (raw.isEmpty() || limit <= 0) return emptyList()
        val top = raw.asSequence().take(QUERY_TOP).filter { it.second > 0f && it.second.isFinite() && it.first >= 0 }.toList()
        if (top.isEmpty()) return emptyList()
        val q = FloatArray(maxOf(bucketStart.size - 1, top.maxOf { it.first } + 1))
        var norm = 0f
        for ((c, p) in top) { val v = sqrt(p); q[c] = v; norm += v * v }
        norm = sqrt(norm).coerceAtLeast(1e-9f)
        val best = HashMap<Int, Float>()
        for ((c, _) in top.take(BUCKET_TOP)) {
            if (c + 1 >= bucketStart.size) continue
            for (b in bucketStart[c] until bucketStart[c + 1]) {
                val t = bucketItems[b]
                var d = 0f
                val base = t * n
                for (k in 0 until n) {
                    val ci = cls[base + k].toInt() and 0xffff
                    if (ci < q.size) d += q[ci] * (vals[base + k].toInt() and 0xff)
                }
                val s = d / (255f * norm)
                val label = labelOf[t]
                if (s >= minSimilarity && s > (best[label] ?: -1f)) best[label] = s
            }
        }
        return best.entries.sortedByDescending { it.value }.take(limit).map { String(Character.toChars(labels[it.key])) to it.value }
    }

    companion object {
        /** 查询特征取模型概率最高的类数（与生成脚本一致）。 */
        const val QUERY_TOP = 320
        /** 只比首位类落在查询前这么多类里的模板。 */
        const val BUCKET_TOP = 32

        /**
         * 模板是不是按 [modelSha] 这个模型算的：model_sha256 相同，或写在 compatible_model_sha256 里
         * （同一模型微调的旧版本，实测换用模板准确率不变，例如 ochwpro v1.0 与 v1.1）。
         */
        internal fun modelMatches(meta: JSONObject, modelSha: String): Boolean {
            if (meta.optString("model_sha256").equals(modelSha, ignoreCase = true)) return true
            val compatible = meta.optJSONArray("compatible_model_sha256") ?: return false
            return (0 until compatible.length()).any { compatible.optString(it).equals(modelSha, ignoreCase = true) }
        }

        /** [meta] 与 [modelSha] 对不上（模型换了，见 [modelMatches]）或文件长度不对时返回 null；[modelSha] 为 null 时不核对。 */
        fun decode(meta: String, bin: ByteArray, modelSha: String?): HanziRareTemplates? {
            val o = JSONObject(meta)
            if (modelSha != null && !modelMatches(o, modelSha)) return null
            val n = o.getInt("n")
            val count = o.getInt("count")
            val labels = o.getString("labels").codePoints().toArray()
            val record = 2 + n * 3
            if (n <= 0 || count <= 0 || bin.size != count * record) return null
            val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            val labelOf = IntArray(count)
            val cls = ShortArray(count * n)
            val vals = ByteArray(count * n)
            var maxClass = 0
            for (t in 0 until count) {
                labelOf[t] = buf.short.toInt() and 0xffff
                if (labelOf[t] >= labels.size) return null
                for (k in 0 until n) {
                    cls[t * n + k] = buf.short
                    vals[t * n + k] = buf.get()
                    maxClass = maxOf(maxClass, cls[t * n + k].toInt() and 0xffff)
                }
            }
            // 按首位类分桶（CSR）
            val bucketStart = IntArray(maxClass + 2)
            for (t in 0 until count) bucketStart[(cls[t * n].toInt() and 0xffff) + 1]++
            for (c in 1 until bucketStart.size) bucketStart[c] += bucketStart[c - 1]
            val fill = bucketStart.copyOf()
            val bucketItems = IntArray(count)
            for (t in 0 until count) bucketItems[fill[cls[t * n].toInt() and 0xffff]++] = t
            return HanziRareTemplates(labels, labelOf, cls, vals, n, bucketStart, bucketItems)
        }
    }
}
