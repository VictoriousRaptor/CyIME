package com.kingzcheung.xime.glide

import java.io.File

/**
 * 九键滑行记住点过的备选（#214）。九键上只差连按同一个键的词划出来是同一条线（行/中、高兴/高中、晚安/早餐），
 * 画法上分不开，词频在这几对上也靠不住；点过一次备选，下次划同一条线就先出点过的那个词，原来的首选退到备选里。
 *
 * 按“这条线”（去掉连按重复键后的数字串）记一个词，后点的覆盖先点的；最多记 [LIMIT] 条，满了丢最久没用的。
 * 存在 filesDir 下的一个小文本文件里（每行「线\t词」），只在本机，不上传。
 */
internal object T9GlideMemory {
    const val LIMIT = 500
    private const val FILE = "t9_glide_choices.txt"

    private var loadedFrom: String? = null
    /** 按最近使用排序（最近的在最后）。 */
    private val choices = LinkedHashMap<String, String>(64, 0.75f, true)

    /** 这条线上次点的词；没有时为空。 */
    @Synchronized fun preferred(dir: File, line: String): String? {
        load(dir)
        return choices[line]
    }

    /** 记下这条线点了 [word]。 */
    @Synchronized fun remember(dir: File, line: String, word: String) {
        if (line.isEmpty() || word.isEmpty() || '\t' in word || '\n' in word) return
        load(dir)
        choices[line] = word
        while (choices.size > LIMIT) choices.remove(choices.keys.first())
        runCatching {
            val file = File(dir, FILE)
            val temp = File(dir, "$FILE.tmp")
            temp.writeText(choices.entries.joinToString("") { "${it.key}\t${it.value}\n" })
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    private fun load(dir: File) {
        val path = File(dir, FILE).absolutePath
        if (loadedFrom == path) return
        loadedFrom = path
        choices.clear()
        runCatching {
            File(path).takeIf { it.isFile }?.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0 && tab < line.length - 1) choices[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
    }

    /**
     * 识别结果前几名的数字串 [codes] 里，把这条线上次点过的词提到最前：在同一条线的编码 [sameLine] 和 [codes] 里找
     * 最常用的词正好是它的编码。找不到（这次的笔画够不着它）时原样返回。
     */
    fun promote(codes: List<String>, preferred: String?, sameLine: List<String>, wordOf: (String) -> String?): List<String> {
        val first = codes.firstOrNull() ?: return codes
        if (preferred == null || wordOf(first) == preferred) return codes
        val code = (sameLine.asSequence() + codes.asSequence()).firstOrNull { wordOf(it) == preferred } ?: return codes
        return listOf(code) + codes.filter { it != code }
    }
}
