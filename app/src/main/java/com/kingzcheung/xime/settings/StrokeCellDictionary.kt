package com.kingzcheung.xime.settings

import java.io.File

/**
 * 导入的词库给笔画方案用（11-19 贡献者：笔画不能导入词库，常用词只能逐字组）。
 * 导入的词库按拼音存；笔画分词打词按每个字的第一笔查词（lua/cyime_stroke_phrase.lua），所以应用词库时
 * 用笔画码表把每个词换成每字首笔，写成 [NAME] 词表，笔画方案补丁把它列进依赖一起编译（[CellDictionaryRime]）。
 * 只在「应用更改」时算一次，打字时不多做事。
 */
object StrokeCellDictionary {
    const val NAME = "stroke_cells"
    /** 分词打词最多查四个字的词（cyime_stroke_phrase.lua 的 MAX_WORD_PHRASE）。 */
    private val word = Regex("[㐀-䶿一-鿿]{2,4}")
    /** 个别字有两种笔顺，一个词最多记 4 种首笔编码。 */
    private const val MAX_CODES = 4
    /** 内置词表最低词频 1000；导入的词排在内置词后面。 */
    private const val WEIGHT = 1

    internal val schema = """
        |# Internal compilation dependency, not a user-visible input mode.
        |# 用户导入的词库换成笔画首笔的词表（stroke_cells.dict.yaml，应用词库时生成）；只由 lua/cyime_stroke_phrase.lua 查。
        |schema:
        |  schema_id: $NAME
        |  name: 笔画导入词库依赖
        |  version: "1"
        |engine:
        |  processors: [speller, selector, express_editor]
        |  segmentors: [abc_segmentor]
        |  translators: [table_translator]
        |speller:
        |  alphabet: "hspnz"
        |  algebra: []
        |translator:
        |  dictionary: $NAME
        |  enable_completion: false
        |  enable_user_dict: false
        |  enable_sentence: false
        |""".trimMargin()

    /** 字 → 它各种笔顺的第一笔（码表 stroke.dict.yaml 正文每行「字<TAB>笔画<TAB>权重」）。 */
    internal fun firstStrokes(lines: Sequence<String>): Map<String, String> {
        val map = HashMap<String, String>(1 shl 16)
        var body = false
        for (line in lines) {
            if (!body) { body = line == "..."; continue }
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val first = line.getOrNull(tab + 1)?.takeIf { it in "hspnz" } ?: continue
            val text = line.substring(0, tab)
            val old = map[text]
            if (old == null) map[text] = first.toString() else if (first !in old) map[text] = old + first
        }
        return map
    }

    /** 一个词的每字首笔编码；有字不在码表里时为空。 */
    internal fun codes(text: String, firsts: Map<String, String>): List<String> {
        var codes = listOf("")
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += Character.charCount(cp)
            val options = firsts[ch] ?: return emptyList()
            codes = codes.flatMap { prefix -> options.map { prefix + it } }.take(MAX_CODES)
        }
        return codes
    }

    /**
     * 把 [sources] 里两到四个字的词换成首笔编码，写到 [target]：导入词库的 cell_*.dict.yaml（正文每行「词<TAB>拼音<TAB>权重」），
     * 和导入的笔画词库 *.stroke.txt（一行一个词）。同一个词只写一次。返回写了几个词；来源和码表都没变（每次启动都会走到这里）时不重写，返回 null。
     */
    fun write(target: File, table: File, sources: List<File>): Int? {
        val signature = "# sources: " + CellDictionaryStore.digest((sources + table).joinToString("\n") {
            "${it.name}:${it.length()}:${it.lastModified()}"
        }.toByteArray())
        if (target.isFile && target.bufferedReader().use { it.readLine() } == signature) return null
        val firsts = table.bufferedReader().useLines(::firstStrokes)
        val seen = TxtCellDictionary.LongSet()
        var count = 0
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.bufferedWriter().use { out ->
            out.write("$signature\n# 导入词库的笔画首笔词表，由 CyIME 应用词库时生成，不要手改。\n---\n" +
                "name: $NAME\nversion: \"1\"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n")
            for (source in sources) source.bufferedReader().useLines { lines ->
                // 导入的笔画词库（*.stroke.txt）一行一个词，没有文件头。
                var body = !source.name.endsWith(".dict.yaml")
                for (line in lines) {
                    if (!body) { body = line == "..."; continue }
                    val text = line.substringBefore('\t')
                    if (!word.matches(text) || !seen.add(key(text))) continue
                    val codes = codes(text, firsts)
                    if (codes.isEmpty()) continue
                    codes.forEach { out.append(text).append('\t').append(it).append('\t').append(WEIGHT.toString()).append('\n') }
                    count++
                }
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return count
    }

    /** FNV-1a，和 [TxtCellDictionary] 去重同一办法：两百万个词也只占十几 MB。 */
    private fun key(text: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (c in text) hash = (hash xor c.code.toLong()) * 0x100000001b3L
        return hash
    }
}
