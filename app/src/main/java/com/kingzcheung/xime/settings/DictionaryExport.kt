package com.kingzcheung.xime.settings

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「导出词库」：把词写成别的输入法导入时认的文件，免得用户自己拿转换工具转来转去。纯函数，单测见 DictionaryExportTest。
 *
 * 只写全是汉字的 2～32 字词、拼音一字一个音节的（和 CyIME JSON、电脑版导入的要求一样）；整句、单字、带字母数字的、
 * 拼音对不上的跳过并计数。同一个词和拼音只写一次，次数取最大。
 *
 * 各格式的行样子、编码和换行按这些输入法自己导出的文件和公开的格式说明写：
 * - 搜狗拼音 `'ni'hao 你好`，GBK（老版本只认 ANSI），CRLF；GBK 里没有的字整条跳过。
 * - QQ 拼音 `ni'hao 你好 次数`，UTF-16LE 带 BOM，CRLF。
 * - 百度电脑版 `你好<TAB>ni'hao'<TAB>次数`；百度手机版 `你好(ni|hao) 次数`；都是 UTF-16LE 带 BOM，CRLF。
 * - Gboard：zip 里一个 dictionary.txt，首行 `# Gboard Dictionary version:1`，每行 `nihao<TAB>你好<TAB>zh-CN`，UTF-8，LF。
 * 写不了的：搜狗 .bin 备份、搜狗 .scel、微软拼音 .dat 是各家的二进制格式；微软拼音 .dctx 要每个字带声调，词库里没有。
 */
object DictionaryExport {
    enum class Format(val label: String, val note: String, val extension: String) {
        CYIME("CyIME 词库", "手机版、电脑版都能直接导入", "json"),
        TEXT("文本", "每行 词、拼音、次数，Tab 隔开，UTF-8", "txt"),
        RIME("Rime 词典", "小狼毫、鼠须管、同文等 Rime 输入法", "dict.yaml"),
        SOGOU("搜狗拼音", "搜狗输入法 › 词库 › 导入", "txt"),
        QQ("QQ 拼音", "QQ 输入法 › 词库 › 导入", "txt"),
        BAIDU("百度电脑版", "百度输入法电脑版 › 词库 › 导入", "txt"),
        BAIDU_MOBILE("百度手机版", "百度输入法手机版 › 词库 › 导入", "txt"),
        GBOARD("Gboard", "Gboard › 词典 › 个人词典 › 导入", "zip"),
    }

    class Result(val bytes: ByteArray, val written: Int, val skipped: Int)

    /** CyIME JSON 和电脑版导入最多 25 万个词。 */
    const val MAX_JSON_WORDS = 250_000

    private val han = Regex("[㐀-䶿一-鿿]{2,32}")
    private val pinyin = Regex("[a-z]{1,8}( [a-z]{1,8})*")
    private val gbk: Charset by lazy { Charset.forName("GBK") }

    /** [words]：词、空格分开的拼音、次数（0 表示不知道）。按给的顺序写。 */
    fun write(format: Format, name: String, words: List<DictEntry>): Result {
        val kept = LinkedHashMap<Pair<String, String>, Int>()
        var skipped = 0
        for (entry in words) {
            val code = entry.code.trim().replace(Regex(" +"), " ")
            val ok = han.matches(entry.word) && pinyin.matches(code) && code.count { it == ' ' } + 1 == entry.word.length &&
                (format != Format.SOGOU || gbk.newEncoder().canEncode(entry.word))
            if (!ok) { skipped++; continue }
            val key = entry.word to code
            kept[key] = maxOf(kept[key] ?: 0, (entry.weight ?: 0).coerceAtLeast(0))
        }
        var rows = kept.entries.map { Triple(it.key.first, it.key.second.split(' '), it.value.coerceAtLeast(1)) }
        if (format == Format.CYIME && rows.size > MAX_JSON_WORDS) {
            skipped += rows.size - MAX_JSON_WORDS
            rows = rows.take(MAX_JSON_WORDS)
        }
        val bytes = when (format) {
            Format.CYIME -> json(name, rows).toByteArray(Charsets.UTF_8)
            Format.TEXT -> lines(rows, "\n") { (w, p, c) -> "$w\t${p.joinToString(" ")}\t$c" }.toByteArray(Charsets.UTF_8)
            Format.RIME -> (rimeHeader() + lines(rows, "\n") { (w, p, c) -> "$w\t${p.joinToString(" ")}\t$c" })
                .toByteArray(Charsets.UTF_8)
            Format.SOGOU -> lines(rows, "\r\n") { (w, p, _) -> "'${p.joinToString("'")} $w" }.toByteArray(gbk)
            Format.QQ -> utf16(lines(rows, "\r\n") { (w, p, c) -> "${p.joinToString("'")} $w $c" })
            Format.BAIDU -> utf16(lines(rows, "\r\n") { (w, p, c) -> "$w\t${p.joinToString("'")}'\t$c" })
            Format.BAIDU_MOBILE -> utf16(lines(rows, "\r\n") { (w, p, c) -> "$w(${p.joinToString("|")}) $c" })
            Format.GBOARD -> zip("dictionary.txt", ("# Gboard Dictionary version:1\n" +
                lines(rows, "\n") { (w, p, _) -> "${p.joinToString("")}\t$w\tzh-CN" }).toByteArray(Charsets.UTF_8))
        }
        return Result(bytes, rows.size, skipped)
    }

    private inline fun lines(rows: List<Triple<String, List<String>, Int>>, end: String,
                             line: (Triple<String, List<String>, Int>) -> String): String =
        buildString { rows.forEach { append(line(it)).append(end) } }

    private fun utf16(text: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun rimeHeader() = "# CyIME 导出的词库\n---\nname: cyime_export\nversion: \"1\"\nsort: by_weight\n...\n"

    /** cyime.dictionary.v1：手机「导入词库」和电脑版「词库管理 › 导入词库」都认。 */
    private fun json(name: String, rows: List<Triple<String, List<String>, Int>>): String = buildString {
        val title = name.filterNot { it.isISOControl() }.trim().take(100).ifBlank { "CyIME 词库" }
        append("{\"format\":\"cyime.dictionary.v1\",\"name\":").append(quote(title))
        append(",\"language\":\"zh\",\"scheme\":\"pinyin\",\"entries\":[")
        rows.forEachIndexed { index, (w, p, _) ->
            if (index > 0) append(',')
            append("\n{\"word\":").append(quote(w)).append(",\"code\":").append(quote(p.joinToString(" "))).append('}')
        }
        append("\n]}\n")
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun zip(entry: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(bytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    /**
     * 用户词库（Rime .userdb.txt 快照，每行 `拼音码<TAB>文字<TAB>c=次数 d=… t=…`）里学到的词，次数多的在前。
     * 删掉的词（次数小于 1）不要；同一个词和拼音的次数相加。
     */
    fun fromUserdb(rows: Sequence<String>): List<DictEntry> {
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        for (line in rows) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 3) continue
            val commits = DictContributionRules.commitsOf(cols[2]) ?: continue
            if (commits <= 0 || cols[1].isEmpty()) continue
            counts.merge(cols[1] to cols[0].trim().replace(Regex(" +"), " "), commits, Int::plus)
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
            .thenBy { it.key.first }).map { DictEntry(it.key.first, it.key.second, it.value) }
    }

    /** 导入的词库在手机里存成 Rime 词典（`...` 之后每行 `词<TAB>拼音<TAB>权重`）。 */
    fun fromRimeDictionary(rows: Sequence<String>): List<DictEntry> {
        var body = false
        val out = ArrayList<DictEntry>()
        for (line in rows) {
            if (!body) { body = line == "..."; continue }
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 2) continue
            out += DictEntry(cols[0], cols[1], cols.getOrNull(2)?.trim()?.toIntOrNull() ?: 0)
        }
        return out
    }
}
