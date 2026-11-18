package com.kingzcheung.xime.association

import org.json.JSONArray
import org.json.JSONObject

data class LearnedSequence(val tokens: List<String>, val count: Int)
data class PersonalContinuation(val context: String, val text: String, val count: Int)

/** 选进来的文件根本不是学习文件 (词库、剪贴板备份等)，和学习文件内容有错分开报。 */
class NotLearningFileException : IllegalArgumentException("不是学习文件")

/** Portable personal data. A corpus prior is distinct from actual keyboard observations. */
data class PersonalLearningData(
    val bigrams: List<LearnedSequence> = emptyList(),
    val trigrams: List<LearnedSequence> = emptyList(),
    val recentInputs: List<String> = emptyList(),
    val profileName: String = "",
    val continuations: List<PersonalContinuation> = emptyList(),
) {
    val uniqueSequences get() = bigrams.size + trigrams.size
    val observations get() = (bigrams + trigrams).sumOf { it.count.toLong() }

    /** [profileReference]: the observation file names its immutable corpus file instead of embedding it. */
    fun encode(profileReference: String? = null): String {
        fun sequences(rows: List<LearnedSequence>) = JSONArray().apply {
            rows.forEach { put(JSONObject().put("tokens", JSONArray(it.tokens)).put("count", it.count)) }
        }
        return JSONObject().put("format", "cyime-personal-learning").put("version", 1)
            .put("bigrams", sequences(bigrams)).put("trigrams", sequences(trigrams))
            .put("recentInputs", JSONArray(recentInputs))
            .put("profile", JSONObject().put("name", profileName).put("entries", JSONArray().apply {
                continuations.forEach { put(JSONObject().put("context", it.context).put("text", it.text).put("count", it.count)) }
            })).apply { if (profileReference != null) put("profileReference", profileReference) }.toString()
    }

    /**
     * 导出给人在电脑上看、删改的版本：内容与 [encode] 相同，但每个条目单独一行。
     * 读回仍用 [decode]；改坏的文件整份拒绝，不会只导入一半。
     */
    fun encodeForEditing(): String {
        fun lines(rows: List<String>, indent: String) =
            if (rows.isEmpty()) "[]" else rows.joinToString(",\n$indent  ", "[\n$indent  ", "\n$indent]")
        fun sequences(rows: List<LearnedSequence>) = lines(rows.map {
            JSONObject().put("tokens", JSONArray(it.tokens)).put("count", it.count).toString()
        }, "  ")
        val entries = lines(continuations.map {
            JSONObject().put("context", it.context).put("text", it.text).put("count", it.count).toString()
        }, "    ")
        return buildString {
            append("{\n  \"format\": \"cyime-personal-learning\",\n  \"version\": 1,\n")
            append("  \"bigrams\": ").append(sequences(bigrams)).append(",\n")
            append("  \"trigrams\": ").append(sequences(trigrams)).append(",\n")
            append("  \"recentInputs\": ").append(JSONArray(recentInputs).toString()).append(",\n")
            append("  \"profile\": {\n    \"name\": ").append(JSONObject.quote(profileName)).append(",\n")
            append("    \"entries\": ").append(entries).append("\n  }\n}\n")
        }
    }

    /**
     * 「新增」导入：保留本机已学的，加入 [imported] 里没有的条目；同一条取两边较大的次数，
     * 所以同一个文件重复导入不会把次数越叠越大。最近输入保留本机的。结果按存储上限裁剪。
     */
    fun mergedWith(imported: PersonalLearningData): PersonalLearningData {
        fun merge(local: List<LearnedSequence>, other: List<LearnedSequence>): List<LearnedSequence> {
            if (other.isEmpty()) return local
            val counts = LinkedHashMap<List<String>, Int>()
            local.forEach { counts[it.tokens] = it.count }
            other.forEach { counts.merge(it.tokens, it.count, ::maxOf) }
            return counts.map { LearnedSequence(it.key, it.value) }
        }
        val profile = if (imported.continuations.isEmpty()) continuations else {
            val counts = LinkedHashMap<Pair<String, String>, Int>()
            continuations.forEach { counts[it.context to it.text] = it.count }
            imported.continuations.forEach { counts.merge(it.context to it.text, it.count, ::maxOf) }
            val rows = counts.map { PersonalContinuation(it.key.first, it.key.second, it.value) }
            if (rows.size > MAX_PROFILE_ENTRIES) rows.sortedByDescending { it.count }.take(MAX_PROFILE_ENTRIES) else rows
        }
        return copy(bigrams = merge(bigrams, imported.bigrams), trigrams = merge(trigrams, imported.trigrams),
            profileName = profileName.ifEmpty { imported.profileName }, continuations = profile).boundedForStorage()
    }

    /**
     * 本机学习数据的存储上限：超过 [STORED_HIGH_WATER] 条时只留次数最多的 [STORED_KEPT] 条，
     * 任一次数超过 [MAX_COUNT] 时全部减半。这样保存的文件永远能被 [decode] 读回，
     * 不会因长期使用越界而让学习静默停止。没有越界时原样返回同一对象。
     */
    fun boundedForStorage(): PersonalLearningData {
        fun bound(rows: List<LearnedSequence>): List<LearnedSequence> {
            var kept = if (rows.size > STORED_HIGH_WATER) rows.sortedByDescending { it.count }.take(STORED_KEPT) else rows
            while (kept.any { it.count > MAX_COUNT }) kept = kept.map { it.copy(count = maxOf(1, it.count / 2)) }
            return kept
        }
        val b = bound(bigrams)
        val t = bound(trigrams)
        val r = if (recentInputs.size > MAX_RECENT) recentInputs.takeLast(MAX_RECENT) else recentInputs
        return if (b === bigrams && t === trigrams && r === recentInputs) this
        else copy(bigrams = b, trigrams = t, recentInputs = r)
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_SEQUENCES = 100_000
        const val MAX_COUNT = 1_000_000
        const val MAX_RECENT = 100
        const val MAX_PROFILE_ENTRIES = 100_000
        const val STORED_HIGH_WATER = MAX_SEQUENCES
        const val STORED_KEPT = 90_000

        /** 和键盘上屏时学的同一套切法（UserNgramCache.tokenize）：字母数字和常用中文标点各算一个。 */
        private const val TOKEN_PUNCTUATION = "，。！？、；：\"' "
        private val SRT_TIME = Regex("""^\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""")
        /** 一份文字最多学这么多组（每种），合进本机后不把自己平时打字学到的挤掉太多。 */
        const val TEXT_KEPT = 50_000
        /** 网址和 Markdown 链接目标不是打出来的字。 */
        private val LINK = Regex("""https?://\S+|www\.\S+|\]\([^)]*\)""")

        /**
         * 「喂自己语料」：任意文字文件（txt、md、csv、json、srt、字幕、聊天记录…）按「这些字都打过一遍」来学，
         * 得到和平时打字一样的搭配次数，用「新增」合进本机学习。按行学，不跨行连；字幕的序号行和时间行跳过，
         * Markdown、表格的符号本来就不算字，自动去掉。
         */
        fun fromText(text: String): PersonalLearningData {
            val bigrams = HashMap<List<String>, Int>()
            val trigrams = HashMap<List<String>, Int>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.all { it.isDigit() } || SRT_TIME.containsMatchIn(line)) continue
                val tokens = line.replace(LINK, " ").replace(Regex("\\s+"), " ")
                    .filter { it.isLetterOrDigit() || it in TOKEN_PUNCTUATION }.trim().map { it.toString() }
                tokens.windowed(2).forEach { bigrams.merge(it, 1, Int::plus) }
                tokens.windowed(3).forEach { trigrams.merge(it, 1, Int::plus) }
            }
            fun rows(counts: Map<List<String>, Int>) = counts.entries.sortedByDescending { it.value }.take(TEXT_KEPT)
                .map { LearnedSequence(it.key, it.value.coerceAtMost(MAX_COUNT)) }
            return PersonalLearningData(rows(bigrams), rows(trigrams))
        }

        /**
         * 选的文件当文字读：UTF-8（可带 BOM），不是就按 GB18030（Windows 记事本存的中文）。
         * 有 NUL 或大量控制字符的当成二进制文件（图片、压缩包、词库），返回 null。
         */
        fun textOf(bytes: ByteArray): String? {
            fun decode(charset: java.nio.charset.Charset) = runCatching {
                charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            val text = (decode(Charsets.UTF_8) ?: decode(java.nio.charset.Charset.forName("GB18030")))
                ?.removePrefix("\uFEFF") ?: return null
            if (text.isEmpty() || '\u0000' in text) return null
            val control = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
            return text.takeIf { control * 100 <= it.length }
        }

        /**
         * [storedLocally] 读本机自己的学习文件：条目数、次数和最近输入超限时先读入、再由
         * [boundedForStorage] 裁剪；格式错误仍然拒绝，避免覆盖损坏前的数据。导入外部文件保持严格。
         */
        fun decode(text: String, storedLocally: Boolean = false): PersonalLearningData {
            if (storedLocally) return decodeChecked(text, lenient = true).boundedForStorage()
            return decodeChecked(text, lenient = false)
        }

        private fun decodeChecked(text: String, lenient: Boolean): PersonalLearningData {
            require(lenient || text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "学习文件超过 32 MB" }
            val root = JSONObject(text)
            if (root.has("format")) {
                if (root.optString("format") != "cyime-personal-learning") throw NotLearningFileException()
                require(root.getInt("version") == 1) { "不支持的学习文件格式" }
            }
            if (!root.has("bigrams") || !root.has("trigrams")) throw NotLearningFileException()
            fun count(row: JSONObject): Int {
                val value = row.getLong("count")
                require(value >= 1 && (lenient || value <= MAX_COUNT)) { "次数必须在 1～1000000 之间" }
                require(row.getDouble("count") == value.toDouble()) { "次数必须是整数" }
                return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            fun sequences(key: String, size: Int): List<LearnedSequence> {
                val array = root.getJSONArray(key)
                require(lenient || array.length() <= MAX_SEQUENCES) { "学习条目过多" }
                val seen = hashSetOf<List<String>>()
                return List(array.length()) { index ->
                    val row = array.getJSONObject(index)
                    val tokens = row.getJSONArray("tokens")
                    require(tokens.length() == size) { "无效的 $key 长度" }
                    val values = List(size) { tokens.getString(it).also { token -> require(token.length in 1..2) } }
                    require(seen.add(values)) { "重复的学习条目" }
                    LearnedSequence(values, count(row))
                }
            }
            val profile = root.optJSONObject("profile")
            val entries = profile?.getJSONArray("entries") ?: JSONArray()
            require(entries.length() <= MAX_PROFILE_ENTRIES) { "个人方案超过 100000 条" }
            val seen = hashSetOf<Pair<String, String>>()
            val continuations = List(entries.length()) { index ->
                val row = entries.getJSONObject(index)
                val prefix = row.getString("context")
                val next = row.getString("text")
                require(prefix.length in 1..8 && next.length in 1..24 && prefix.isNotBlank() && next.isNotBlank()) { "无效的联想条目" }
                require(seen.add(prefix to next)) { "重复的联想条目" }
                PersonalContinuation(prefix, next, count(row))
            }
            val recent = root.optJSONArray("recentInputs") ?: JSONArray()
            require(lenient || recent.length() <= MAX_RECENT)
            return PersonalLearningData(sequences("bigrams", 2), sequences("trigrams", 3),
                List(recent.length()) { recent.getString(it).also { value -> require(value.length in 1..2) } },
                profile?.optString("name").orEmpty().also { require(it.length <= 100) }, continuations)
        }
    }
}

/** Query only matching suffix groups; corpus size never adds a full scan to each keystroke. */
class PersonalContinuationIndex(rows: List<PersonalContinuation>) {
    private val groups = rows.groupBy { it.context }.mapValues { (_, group) ->
        val total = group.sumOf { it.count.toLong() }.toFloat()
        group.sortedByDescending { it.count }.map { AssociationCandidate(it.text, 0.4f * it.count / total) }
    }
    fun predict(context: String, topK: Int = 10): List<AssociationCandidate> {
        if (topK <= 0) return emptyList()
        for (size in minOf(context.length, 8) downTo 1) groups[context.takeLast(size)]?.let { return it.take(topK) }
        return emptyList()
    }
}
