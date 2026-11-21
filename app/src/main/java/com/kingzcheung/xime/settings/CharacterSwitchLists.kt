package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences

/**
 * 日文汉字、韩文汉字、粤语字三张表（构建时生成在 assets 的 rime_ice/ 下，Rime 的 lua 读同一份）。
 * 手写中文时，开关关着的那类字不出候选（他 11-20：手写、双拼、仓颉、注音都要一样）；日语手写不经过这里。
 */
object CharacterSwitchLists {
    private val FILES = listOf(
        KeyboardInputPreferences.JAPANESE_KANJI_KEY to "rime_ice/cyime_japanese.txt",
        KeyboardInputPreferences.KOREAN_HANJA_KEY to "rime_ice/cyime_korean.txt",
        KeyboardInputPreferences.CANTONESE_CHARACTERS_KEY to "rime_ice/cyime_cantonese.txt",
    )

    @Volatile
    private var sets: Map<String, Set<String>>? = null

    /** 一张表里的单字（「字<TAB>读音」，词组不要：手写一段一个字）。 */
    internal fun parse(text: String): Set<String> = text.lineSequence().mapNotNull { line ->
        val word = line.substringBefore('\t', "")
        word.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }
    }.toHashSet()

    private fun sets(context: Context): Map<String, Set<String>> = sets ?: FILES.associate { (key, asset) ->
        key to runCatching { context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) } }
            .getOrDefault(emptySet())
    }.also { sets = it }

    /** 去掉开关关着的那几类字（文字里有一个就去掉）；三个都开着时原样返回。 */
    fun <T> withoutHidden(context: Context, items: List<T>, text: (T) -> String): List<T> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val hidden = sets(context).filterKeys { !KeyboardInputPreferences.splitFromRare(prefs, it) }.values
        if (hidden.isEmpty() || items.isEmpty()) return items
        return items.filter { item ->
            val value = text(item)
            var i = 0
            var keep = true
            while (keep && i < value.length) {
                val cp = value.codePointAt(i)
                val ch = String(Character.toChars(cp))
                if (hidden.any { ch in it }) keep = false
                i += Character.charCount(cp)
            }
            keep
        }
    }
}
