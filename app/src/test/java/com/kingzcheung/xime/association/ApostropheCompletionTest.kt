package com.kingzcheung.xime.association

import com.kingzcheung.xime.settings.InputLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 11-16：法语、意大利语打 l'eau、dell'anno 时，撇号后面那段照常补全，补出来的词带着 l'。 */
class ApostropheCompletionTest {
    private val words = listOf("eau", "elle", "anno", "acqua", "il")
    private val asked = mutableListOf<String>()
    private fun complete(typed: String, language: InputLanguage) = runBlocking {
        ApostropheCompletion.complete(typed, language) { prefix -> asked += prefix; words.filter { it.startsWith(prefix) } }
    }

    @Test fun elisionCompletesTheWordAfterTheApostrophe() {
        assertEquals(listOf("l'eau", "l'elle"), complete("l'e", InputLanguage.FRENCH))
        assertEquals(listOf("qu'il"), complete("qu'i", InputLanguage.FRENCH))
        assertEquals(listOf("dell'anno", "dell'acqua"), complete("dell'a", InputLanguage.ITALIAN))
        // 只按最后一个撇号后面那段找。
        assertEquals(listOf("e", "i", "a"), asked)
    }

    @Test fun nothingToCompleteRightAfterTheApostropheOrInOtherLanguages() {
        assertEquals(emptyList<String>(), complete("l'", InputLanguage.FRENCH))
        assertEquals(emptyList<String>(), complete("geht's", InputLanguage.GERMAN))
        assertEquals(emptyList<String>(), complete("d'a", InputLanguage.SPANISH))
        assertEquals(emptyList<String>(), asked)
        // 没有撇号：原样按整串找。
        assertEquals(listOf("eau", "elle"), complete("e", InputLanguage.FRENCH))
        assertEquals(listOf("e"), asked)
    }
}
