package com.kingzcheung.xime.service

import android.content.Context
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import java.io.File

/**
 * 英文出短语（别人请求 #269，11-09，默认关）：英文状态下打的这串字母正好是「我的短语」里某条的编码时，
 * 候选栏紧跟在原词后面给出这条短语，点一下把这串字母换成短语。和中文拼音里一样只认完整编码，不按开头匹配。
 * 只看本机的短语表，不联网，不学习。多行短语不给（英文补全按一行替换，换不了行）。
 */
internal object EnglishPhrases {
    /** 设置键，默认关。 */
    const val PREF_KEY = "english_phrase_candidates"

    /** 候选栏最多给几条。 */
    const val LIMIT = 5

    fun isEnabled(context: Context): Boolean =
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).getBoolean(PREF_KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).edit().putBoolean(PREF_KEY, on).apply()
    }

    private fun isCodeLetter(c: Char) = c in 'a'..'z'

    /** 编码正好是 [typed]（不分大小写）的短语：按权重、再按表里的先后；和打的字母本身一样的不给。 */
    fun matching(typed: String, phrases: List<DictEntry>): List<String> {
        if (typed.isEmpty() || typed.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return emptyList()
        val code = typed.lowercase()
        return phrases.withIndex()
            .filter { (_, e) -> e.code == code && e.word.isNotBlank() && e.word != typed }
            .sortedWith(compareBy<IndexedValue<DictEntry>>({ -(it.value.weight ?: 0) }, { it.index }))
            .map { it.value.word }.distinct().take(LIMIT)
    }

    /** 短语放在补全词前面（候选栏里原词仍是第一个）；重复的只留短语那条。 */
    fun merge(phrases: List<String>, completions: List<String>, limit: Int): List<String> =
        if (phrases.isEmpty()) completions else (phrases + completions).distinct().take(maxOf(limit, phrases.size))

    // ---- 短语表 ----

    private data class Stamp(val path: String, val modified: Long, val length: Long)

    @Volatile private var cache: Pair<Stamp, List<DictEntry>>? = null

    /** 编码全是字母的单行短语。文件没变（修改时间和大小）不重读。不要在主线程调用。 */
    fun phrases(context: Context): List<DictEntry> = phrases(PersonalDictManager.getCustomPhraseFile(context, null))

    internal fun phrases(file: File): List<DictEntry> {
        val stamp = Stamp(file.path, file.lastModified(), file.length())
        cache?.takeIf { it.first == stamp }?.let { return it.second }
        val entries = if (!file.isFile) emptyList() else runCatching {
            PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList()).filter { e ->
            e.code.isNotEmpty() && e.code.all(::isCodeLetter) && com.kingzcheung.xime.settings.PhraseLineBreaks.STORED !in e.word
        }
        cache = stamp to entries
        return entries
    }
}
