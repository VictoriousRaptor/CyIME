package com.kingzcheung.xime.service

import android.content.Context
import com.kingzcheung.xime.glide.GlideLexicon
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * #101 英文下一词（默认关）：英文打完一个词按空格后，候选栏给出常跟在它后面的词。
 * 数据是英文滑行已经在用的双词表（english_bigrams.txt），不另带数据；
 * 只给 english.txt 里的词（去掉了脏话），滑行补的 fuck 之类不出。
 */
internal object EnglishNextWords {
    const val PREF_KEY = "english_next_word"
    const val LIMIT = 10
    private const val FILE = "english.txt"
    @Volatile private var clean: Set<String>? = null

    fun isEnabled(context: Context): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(PREF_KEY, false)

    /** 打完的词 → 双词表里的写法（小写、去撇号）；不是英文字母的词为 null。 */
    internal fun key(word: String): String? = word.lowercase().replace("'", "").replace("’", "")
        .takeIf { it.isNotEmpty() && it.all { char -> char in 'a'..'z' } }

    /** 须在后台线程调用（第一次要读双词表）。 */
    fun predict(context: Context, previous: String): List<String> {
        val key = key(previous) ?: return emptyList()
        return choose(GlideLexicon.bigrams(context).next(key, LIMIT * 3), cleanWords(context),
            GlideLexicon.contractions(context)::display)
    }

    /**
     * 双词表给的词里只留 english.txt 有的，单个字母只留 a 和 I；去撇号的写法换回缩写（im → I'm），i 写成 I。
     */
    internal fun choose(next: List<String>, allowed: Set<String>, display: (String) -> String): List<String> =
        next.filter { it in allowed && (it.length > 1 || it == "a" || it == "i") }
            .map { if (it == "i") "I" else display(it) }.distinct().take(LIMIT)

    private fun cleanWords(context: Context): Set<String> = clean ?: synchronized(this) {
        clean ?: context.applicationContext.assets.open(FILE).bufferedReader()
            .useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet() }
            .also { clean = it }
    }
}
