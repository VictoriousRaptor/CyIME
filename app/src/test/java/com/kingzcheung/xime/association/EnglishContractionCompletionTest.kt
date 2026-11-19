package com.kingzcheung.xime.association

import com.kingzcheung.xime.glide.EnglishContractions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EnglishContractionCompletionTest {
    private val table = EnglishContractions(listOf(
        File("src/main/assets/english_contractions.txt"),
        File("app/src/main/assets/english_contractions.txt")
    ).first { it.isFile }.readLines().asSequence())

    private fun apply(typed: String, completions: List<String>, limit: Int = 20) =
        EnglishContractionCompletion.apply(typed, completions, limit) { word ->
            table.display(word).takeIf { it != word } ?: table.offer(word)
        }

    @Test fun fullSpellingPutsTheContractionFirst() {
        assertEquals(listOf("I'm", "image", "important", "impact"), apply("im", listOf("image", "important", "im", "impact")))
        assertEquals(listOf("what's", "whatsoever"), apply("whats", listOf("whats", "whatsoever")))
        assertEquals(listOf("don't"), apply("dont", listOf("dont")))
        // 词表里没有去撇号写法的（youre）也给。
        assertEquals(listOf("you're"), apply("youre", emptyList()))
    }

    @Test fun realWordsStayFirstAndTheContractionComesSecond() {
        // 候选栏最前面是打的原词，补全里不再重复它，缩写紧跟在后面。
        assertEquals(listOf("it's", "itself"), apply("its", listOf("its", "itself")))
        assertEquals(listOf("we're", "werewolf"), apply("were", listOf("were", "werewolf")))
        assertEquals(listOf("I'll", "illness", "illegal"), apply("ill", listOf("ill", "illness", "illegal")))
        assertEquals(listOf("we'll", "wellness"), apply("well", listOf("well", "wellness")))
        assertEquals(listOf("he'll", "hello"), apply("hell", listOf("hell", "hello")))
        assertEquals(listOf("she'll", "shells"), apply("shell", listOf("shell", "shells")))
    }

    @Test fun prefixCompletionsSwapOnlyNonWords() {
        assertEquals(listOf("do", "don't", "done", "doing"), apply("do", listOf("do", "dont", "done", "doing")))
        assertEquals(listOf("its", "itself"), apply("it", listOf("its", "itself")))
        assertEquals(listOf("we", "were", "well"), apply("we", listOf("we", "were", "well")))
    }

    @Test fun followsTypedCase() {
        assertEquals(listOf("I'm"), apply("Im", listOf("Im")))
        assertEquals(listOf("I'M"), apply("IM", listOf("IM")))
        assertEquals(listOf("Don't", "Done"), apply("Dont", listOf("Dont", "Done")))
        assertEquals(listOf("DON'T"), apply("DONT", listOf("DONT")))
        assertEquals(listOf("Don't", "Done"), apply("Do", listOf("Dont", "Done")))
    }

    @Test fun otherWordsAreUntouched() {
        val words = listOf("hello", "help", "helpful")
        assertSame(words, apply("hel", words))
        assertEquals(20, apply("im", List(20) { "im$it" }).size)
    }

    /** 打了撇号（what'、don'）：从缩写表里给对得上的，跟着大小写；和打的一样的不重复。 */
    @Test fun typedApostropheOffersMatchingContractions() {
        fun typed(text: String) = EnglishContractionCompletion.withApostrophe(text, table.forms(), 20)
        assertEquals(listOf("what's", "what're", "what'd"), typed("what'"))
        assertEquals(listOf("don't"), typed("don'"))
        assertEquals(listOf("What's", "What're", "What'd"), typed("What'"))
        assertEquals(listOf("DON'T"), typed("DON'"))
        assertEquals(listOf("I'm", "I've", "I'll", "I'd"), typed("i'"))
        assertEquals(listOf("I'm", "I've", "I'll", "I'd"), typed("I'"))
        assertEquals(listOf("you're", "you've", "you'll", "you'd"), typed("you'"))
        // 已经打全了：候选栏最前面就是它，不再重复。
        assertEquals(emptyList<String>(), typed("what's"))
        // 表里没有的写法不硬凑。
        assertEquals(emptyList<String>(), typed("xyz'"))
    }
}
