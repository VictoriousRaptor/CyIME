package com.kingzcheung.xime.settings

import java.io.File
import java.util.zip.ZipInputStream

/**
 * 「导入词库」里两种要先拆开的文件，拆成每行「词<TAB>拼音」的文本，再走 [TxtCellDictionary]：
 * - Gboard 导出的个人词典：zip 里一个 dictionary.txt（`nihao<TAB>你好<TAB>zh-CN`）。
 * - 微软拼音的 .dctx（XML，`<ns1:InputString>ni3 hao3</ns1:InputString><ns1:OutputString>你好</ns1:OutputString>`），
 *   声调数字去掉。
 * 别的文件返回 null，照原样导入。
 */
object DictionaryImportFormats {
    /** dctx 整个读进内存解析，比文本词库的上限小。 */
    const val MAX_DCTX_BYTES = 32 * 1024 * 1024

    /** [source] 是 zip 或 dctx 时，拆好的文本放在同一个文件夹的临时文件里返回（用完由调用方删）；否则 null。 */
    fun toText(source: File): File? {
        val head = source.inputStream().use { input -> ByteArray(4096).let { it.copyOf(maxOf(0, input.read(it))) } }
        val zip = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()
        val dctx = !zip && String(head, Charsets.UTF_8).contains("microsoft.com/ime/dctx")
        if (!zip && !dctx) return null
        val out = File.createTempFile(".import-", ".txt", source.parentFile)
        try {
            if (zip) unzipText(source, out) else dctxText(source, out)
            return out
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
    }

    /** Gboard 的 dictionary.txt；没有就取第一个 .txt。 */
    private fun unzipText(source: File, out: File) {
        fun open() = ZipInputStream(source.inputStream().buffered())
        val names = open().use { zip -> generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.map { it.name }.toList() }
        val wanted = names.firstOrNull { it.substringAfterLast('/') == "dictionary.txt" }
            ?: names.firstOrNull { it.endsWith(".txt", ignoreCase = true) }
            ?: throw IllegalArgumentException("压缩包里没有词库文本（Gboard 导出的应有 dictionary.txt）")
        open().use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != wanted) continue
                out.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= TxtCellDictionary.MAX_BYTES) { "词库超过 128 MB，请拆分后导入" }
                        output.write(buffer, 0, count)
                    }
                }
                return
            }
        }
    }

    private val dctxEntry = Regex(
        "<(?:\\w+:)?InputString>([^<]*)</(?:\\w+:)?InputString>\\s*<(?:\\w+:)?OutputString>([^<]*)</(?:\\w+:)?OutputString>")

    private fun dctxText(source: File, out: File) {
        require(source.length() <= MAX_DCTX_BYTES) { "微软拼音词库超过 32 MB，请拆分后导入" }
        out.writeText(dctxLines(source.readText(Charsets.UTF_8).removePrefix("﻿")), Charsets.UTF_8)
    }

    /** 每个词条一行「词<TAB>拼音」，拼音里的声调数字去掉。 */
    internal fun dctxLines(xml: String): String = buildString {
        dctxEntry.findAll(xml).forEach { match ->
            val word = unescape(match.groupValues[2]).trim()
            val pinyin = match.groupValues[1].replace(Regex("[0-9]"), "").trim().lowercase()
            if (word.isNotEmpty() && pinyin.isNotEmpty()) append(word).append('\t').append(pinyin).append('\n')
        }
    }

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
