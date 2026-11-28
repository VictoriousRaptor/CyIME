package com.kingzcheung.xime.settings

import java.io.File
import java.io.InputStream

/**
 * 导入的词库里没写拼音的词（一行一个词的词表），按内置词库已有的读音注上（11-26：导入 1.6 万行只进了 339 个）。
 * 词库里有整个词就用它的读音（银行 yin hang）；没有就拆成词库里有的最少几段，每段取词库里权重最高的读音，
 * 多音字也就按词库常用的读法；有一个字词库里没有就不注，照旧跳过。只在导入时读一遍词库文件，打字时不做任何事。
 */
object PinyinAnnotation {
    /** 拆词时一段最多几个字。 */
    private const val MAX_PIECE = 6

    /** 要查的：每个词和它所有不超过 [MAX_PIECE] 个字的片段（单字也在里面）。 */
    fun wanted(words: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (word in words) for (i in word.indices) for (j in i + 1..minOf(word.length, i + MAX_PIECE)) out += word.substring(i, j)
        return out
    }

    /**
     * [tables] 里（Rime .dict.yaml，`...` 之后每行 `词<TAB>拼音<TAB>权重`）[wanted] 里每段文字权重最高的读音。
     * 读音的音节数要和字数一样；没有权重的算 0。
     */
    fun readings(tables: List<() -> InputStream>, wanted: Set<String>): Map<String, String> {
        val best = HashMap<String, Pair<String, Long>>()
        for (open in tables) open().bufferedReader().useLines { lines ->
            var body = false
            for (line in lines) {
                if (!body) { if (line.trim() == "...") body = true; continue }
                val tab = line.indexOf('\t')
                if (tab <= 0 || line[0] == '#') continue
                val text = line.substring(0, tab)
                if (text !in wanted) continue
                val parts = line.split('\t')
                val code = parts.getOrNull(1)?.trim().orEmpty()
                if (code.isEmpty() || code.split(' ').size != text.length) continue
                val weight = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                val old = best[text]
                if (old == null || weight > old.second) best[text] = code to weight
            }
        }
        return best.mapValues { it.value.first }
    }

    /** [word] 的拼音（音节用空格隔开）：拆成 [readings] 里有的最少几段；拆不出来是 null。 */
    fun annotate(word: String, readings: Map<String, String>): String? {
        val n = word.length
        val best = arrayOfNulls<List<String>>(n + 1)
        best[0] = emptyList()
        for (i in 0 until n) {
            val head = best[i] ?: continue
            for (j in i + 1..minOf(n, i + MAX_PIECE)) {
                val code = readings[word.substring(i, j)] ?: continue
                val pieces = head + code
                if (best[j].let { it == null || pieces.size < it.size }) best[j] = pieces
            }
        }
        return best[n]?.joinToString(" ")
    }

    /** 用这台手机上内置的词库（rime_ice 和它导入的 cn_dicts）注音；没有部署过词库时是 null。 */
    fun fromRimeDir(rimeDir: File): TxtCellDictionary.Annotator? {
        val tables = DictionaryLookup.builtInTables(rimeDir)
        if (tables.isEmpty()) return null
        return TxtCellDictionary.Annotator { wanted -> readings(tables.map { file -> { file.inputStream() } }, wanted) }
    }
}
