package com.kingzcheung.xime.association

import com.kingzcheung.xime.settings.InputLanguage

/**
 * 打的词里有撇号时，字母语言（西、德、法、意）的补全按什么找。补全词表里没有带撇号的词。
 * - 法语、意大利语：撇号是省音（l'eau、dell'anno、qu'il），撇号后面是另一个词：按最后一个撇号后面那段找，
 *   补出来的词前面接回撇号和它前面那段（l'e → l'eau），选了照旧替换整个词。
 * - 西班牙语、德语（geht's）：撇号后面没有可补的词，不给补全，字母照打。
 * 英文的撇号是缩写（what's），在 [AssociationService] 里从缩写表找。
 */
internal object ApostropheCompletion {
    const val APOSTROPHE = '\''

    fun elides(language: InputLanguage): Boolean = language == InputLanguage.FRENCH || language == InputLanguage.ITALIAN

    suspend fun complete(typed: String, language: InputLanguage, lookup: suspend (String) -> List<String>): List<String> {
        if (APOSTROPHE !in typed) return lookup(typed)
        if (!elides(language)) return emptyList()
        val tail = typed.substringAfterLast(APOSTROPHE)
        if (tail.isEmpty()) return emptyList()
        val head = typed.substring(0, typed.length - tail.length)
        return lookup(tail).map { head + it }
    }
}
