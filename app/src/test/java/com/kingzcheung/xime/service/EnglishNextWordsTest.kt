package com.kingzcheung.xime.service

import com.kingzcheung.xime.glide.EnglishBigrams
import com.kingzcheung.xime.glide.EnglishContractions
import com.kingzcheung.xime.glide.glideExtraWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #101 英文下一词：用随包的英文滑行双词表，只给 english.txt 里的词。 */
class EnglishNextWordsTest {
    private val contractions = EnglishContractions(File("src/main/assets/english_contractions.txt").readLines().asSequence())
    private val extra = glideExtraWords(File("src/main/assets/english_glide_words.txt").readLines().asSequence())
    private val vocabulary = contractions.vocabulary(File("src/main/assets/english.txt").readLines(), extra)
    private val bigrams = File("src/main/assets/english_bigrams.txt").bufferedReader().useLines { EnglishBigrams(vocabulary, it) }
    private val clean = File("src/main/assets/english.txt").readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()

    private fun next(word: String) = EnglishNextWords.choose(bigrams.next(EnglishNextWords.key(word)!!, EnglishNextWords.LIMIT * 3),
        clean, contractions::display)

    @Test fun commonWordsGetTheWordsThatUsuallyFollow() {
        assertEquals("you", next("thank").first())
        assertTrue(next("looking").take(3).contains("for"))
        assertTrue(next("of").take(3).contains("course"))
        // 首字母大写打的词也一样查。
        assertEquals(next("thank"), next("Thank"))
    }

    @Test fun contractionsAreShownWithTheirApostropheAndIIsCapital() {
        val afterI = next("I")
        assertTrue(afterI.contains("don't"))
        assertFalse(afterI.contains("dont"))
        assertTrue(next("good").contains("I"))
        // 打的是缩写也认（I'm → im）。
        assertTrue(next("I'm").isNotEmpty())
    }

    @Test fun neverSuggestsWordsOutsideTheCleanListOrStrayLetters() {
        for (word in listOf("the", "you", "fuck", "what", "a")) {
            val words = next(word)
            assertTrue(words.size <= EnglishNextWords.LIMIT)
            assertTrue(word, words.all { it == "I" || EnglishNextWords.key(it)!! in clean || contractions.display(EnglishNextWords.key(it)!!) == it })
            assertTrue(word, words.none { it.length == 1 && it != "a" && it != "I" })
            assertFalse(word, words.contains("fuck"))
        }
    }

    @Test fun notAnEnglishWordGivesNothing() {
        assertNull(EnglishNextWords.key("123"))
        assertNull(EnglishNextWords.key("café"))
        assertNull(EnglishNextWords.key(""))
        assertEquals(emptyList<String>(), bigrams.next("zzzzqx", 10))
    }
}
