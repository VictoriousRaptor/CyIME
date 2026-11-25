package com.kingzcheung.xime.service

import android.content.Context
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import java.io.File

/**
 * 打数字出短语（别人请求，11-03；11-24 起默认开）：「我的短语」里编码是数字的短语（邮箱、网址、自己的号码），
 * 打的数字对得上编码开头时放到候选栏，点一下把光标前这串数字换成短语。数字照常先上屏，不点和以前一样。
 * 只看光标前的那串数字和本机的短语表，不联网，不学习。数字键盘、数字行和角标打的数字都算；实体键盘不给（数字键要选候选）。
 */
internal object DigitPhrases {
    /**
     * 设置键。11-24 起默认开：编码是数字的短语不开这个就哪里都打不出来（群里：数字短语不出，文字短语可以）；
     * 没有数字编码的短语时什么都不给，和关着一样。
     */
    const val PREF_KEY = "digit_phrase_candidates"

    /** 读光标前多少个字：够放下一串号码，也不少于联想上下文（25）。 */
    const val CONTEXT_LENGTH = 32

    /** 候选栏最多给几条。 */
    const val LIMIT = 20

    fun isEnabled(context: Context): Boolean =
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).getBoolean(PREF_KEY, true)

    fun setEnabled(context: Context, on: Boolean) {
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).edit().putBoolean(PREF_KEY, on).apply()
    }

    private fun isDigit(c: Char) = c in '0'..'9' || c in '０'..'９'

    /** 光标前最后那串数字（半角或全角，原样）；最后一个字不是数字返回 null。 */
    fun typedDigits(before: CharSequence?): String? {
        if (before.isNullOrEmpty()) return null
        var start = before.length
        while (start > 0 && isDigit(before[start - 1])) start--
        return if (start == before.length) null else before.substring(start)
    }

    /** 全角数字换成半角，和短语表里的编码比。 */
    fun code(digits: String): String =
        buildString(digits.length) { digits.forEach { append(if (it in '０'..'９') '0' + (it - '０') else it) } }

    /** 编码以 [code] 开头的短语：编码短的（正好相同的）在前，同样长的按权重、再按表里的先后；和数字本身一样的不给。 */
    fun matching(code: String, phrases: List<DictEntry>): List<String> {
        if (code.isEmpty()) return emptyList()
        return phrases.withIndex()
            .filter { (_, e) -> e.code.startsWith(code) && e.word.isNotBlank() && e.word != code }
            .sortedWith(compareBy<IndexedValue<DictEntry>>({ it.value.code.length }, { -(it.value.weight ?: 0) }, { it.index }))
            .map { it.value.word }.distinct().take(LIMIT)
    }

    /** 光标前 [before] 最后那串数字对得上的短语；不是数字结尾时为空。 */
    fun suggestions(before: CharSequence?, phrases: List<DictEntry>): List<String> =
        typedDigits(before)?.let { matching(code(it), phrases) }.orEmpty()

    // ---- 短语表 ----

    private data class Stamp(val path: String, val modified: Long, val length: Long)

    @Volatile private var cache: Pair<Stamp, List<DictEntry>>? = null

    /** 编码以数字开头的那些短语。文件没变（修改时间和大小）不重读；只留数字开头的，内存很小。不要在主线程调用。 */
    fun phrases(context: Context): List<DictEntry> = phrases(PersonalDictManager.getCustomPhraseFile(context, null))

    internal fun phrases(file: File): List<DictEntry> {
        val stamp = Stamp(file.path, file.lastModified(), file.length())
        cache?.takeIf { it.first == stamp }?.let { return it.second }
        val entries = if (!file.isFile) emptyList() else runCatching {
            PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList()).filter { it.code.firstOrNull()?.let(::isDigit) == true }
        cache = stamp to entries
        return entries
    }

    /** 上一次读到的短语（主线程退格时用，不碰文件）。 */
    fun cachedPhrases(): List<DictEntry> = cache?.second.orEmpty()
}
