package com.kingzcheung.xime.association

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLexiconTest {
    /** 商店里各语言补全词表的源文件（tools/wordlists，原样上传）。 */
    private fun words(language: String): WordLexicon {
        val path = "tools/wordlists/cyime-words-$language-v1.txt"
        return WordLexicon(listOf(File(path), File("../$path")).first { it.isFile }.readLines().filter { it.isNotBlank() })
    }
    private val lexicon by lazy { words("es") }

    @Test fun `accents and ñ do not have to be typed`() {
        assertEquals("canción", lexicon.complete("cancion", 5).first())
        assertEquals("año", lexicon.complete("ano", 5).first())
        assertEquals("mañana", lexicon.complete("manana", 5).first())
        assertEquals("también", lexicon.complete("tambien", 5).first())
        assertEquals("español", lexicon.complete("espanol", 5).first())
        // 去掉重音后整词相同的排在补全前面：esta → está，que → qué。
        assertEquals("está", lexicon.complete("esta", 5).first())
        assertEquals("qué", lexicon.complete("que", 5).first())
    }

    @Test fun `a typed accent or ñ only matches that letter`() {
        for (typed in listOf("añ", "cé", "pú")) {
            val result = lexicon.complete(typed, 20)
            assertTrue(typed, result.isNotEmpty())
            for (word in result) assertEquals("$typed → $word", typed.last(), word.lowercase()[typed.length - 1])
        }
    }

    @Test fun `completions follow frequency and never repeat the typed word`() {
        val result = lexicon.complete("graci", 3)
        assertEquals(listOf("gracias", "gracioso", "gracia"), result)
        assertFalse("tambien" in lexicon.complete("tambien", 20))
        assertTrue(lexicon.complete("", 5).isEmpty())
        assertTrue(lexicon.complete("zzzz", 5).isEmpty())
    }

    @Test fun `case follows what was typed and proper nouns stay capitalized`() {
        assertEquals(listOf("Canción", "Canciones"), lexicon.complete("Cancion", 5))
        assertEquals(listOf("CANCIÓN", "CANCIONES"), lexicon.complete("CANCION", 5))
        assertEquals("Madrid", lexicon.complete("madrid", 5).first())
    }

    @Test fun `exact accent variants come before longer words, each group by rank`() {
        val small = WordLexicon(listOf("de", "dé", "del", "decir"))
        assertEquals(listOf("dé", "del", "decir"), small.complete("de", 5))
        // 打了 é 就只认 é：dé 本身已在候选栏第一个，没有别的词。
        assertEquals(emptyList<String>(), small.complete("dé", 5))
    }

    @Test fun `german umlauts and ß do not have to be typed, nouns stay capitalized`() {
        val german = words("de")
        assertEquals("über", german.complete("uber", 5).first())
        assertEquals("schön", german.complete("schon", 5).first())
        assertEquals("für", german.complete("fur", 5).first())
        // ß 算两位（ss）：strasse 补出 Straße，打了 ß 也认。
        assertEquals("Straße", german.complete("strasse", 5).first())
        assertEquals("Straße", german.complete("straß", 5).first())
        assertEquals("Haus", german.complete("haus", 5).first())
        for (word in german.complete("stra", 20)) assertTrue(word, word.lowercase().startsWith("stra"))
    }

    @Test fun `french accents, ç and œ do not have to be typed`() {
        val french = words("fr")
        assertEquals("très", french.complete("tres", 5).first())
        assertEquals("français", french.complete("francais", 5).first())
        assertEquals("être", french.complete("etre", 5).first())
        assertEquals("cœur", french.complete("coeur", 5).first())
        // 打了 é 就只认 é，不给 è。
        for (word in french.complete("pré", 20)) assertEquals(word, 'é', word.lowercase()[2])
    }

    @Test fun `russian ё does not have to be typed`() {
        val russian = words("ru")
        assertEquals("ещё", russian.complete("еще", 5).first())
        assertEquals("всё", russian.complete("все", 5).first())
        assertTrue("привет" in russian.complete("прив", 10))
        assertTrue("Привет" in russian.complete("Прив", 10))
    }

    /** 用户 11-16 要的越南语：不打声调也能补全；打了变音还没打声调时带声调的也算，打了声调就只认这个声调。 */
    @Test fun `vietnamese tones do not have to be typed`() {
        val vietnamese = words("vi")
        assertTrue(vietnamese.complete("nguoi", 5).contains("người"))
        assertTrue(vietnamese.complete("duoc", 5).contains("được"))
        assertTrue(vietnamese.complete("viet", 5).contains("việt"))
        assertTrue(vietnamese.complete("viê", 10).contains("việt"))
        for (word in vietnamese.complete("việ", 10)) assertTrue(word, word.startsWith("việ"))
        // 词表统一成键盘打出来的老写法：只有 hòa，没有 hoà。
        assertTrue(vietnamese.complete("hoa", 20).contains("hòa"))
        assertFalse(vietnamese.complete("hoa", 20).contains("hoà"))
    }

    @Test fun `malay list keeps malay words and chat particles`() {
        val malay = words("ms")
        assertEquals("terima", malay.complete("teri", 5).first())
        assertTrue(malay.complete("bol", 5).contains("boleh"))
        assertTrue(malay.complete("la", 20).contains("lah"))
        // 印尼拼法和片名人名不在里面。
        assertFalse(malay.complete("karen", 20).contains("karena"))
        assertFalse(malay.complete("katnis", 20).contains("katniss"))
    }
}
