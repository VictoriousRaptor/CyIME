package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 11-26：一行一个词、没写拼音的词表，导入时按内置词库注音。 */
class PinyinAnnotationTest {
    private val table = """
        ---
        name: t
        ...
        银	yin	900
        行	xing	1000
        行	hang	300
        长	chang	800
        长	zhang	700
        江	jiang	500
        大	da	900
        桥	qiao	400
        银行	yin hang	50
        长江	chang jiang	80
    """.trimIndent()

    private fun readings(words: Collection<String>) =
        PinyinAnnotation.readings(listOf({ table.byteInputStream() }), PinyinAnnotation.wanted(words))

    @Test fun aWordInTheDictionaryKeepsItsOwnReading() =
        assertEquals("yin hang", PinyinAnnotation.annotate("银行", readings(listOf("银行"))))

    @Test fun aNewWordIsSplitIntoKnownPiecesThenCharacters() {
        // 长江 整段有读音（chang jiang），大桥 按字取词库里权重最高的读音。
        assertEquals("chang jiang da qiao", PinyinAnnotation.annotate("长江大桥", readings(listOf("长江大桥"))))
    }

    @Test fun aPolyphoneAloneTakesTheMostUsedReading() =
        assertEquals("xing", readings(listOf("行"))["行"])

    @Test fun aCharacterMissingFromTheDictionaryLeavesTheWordUnread() =
        assertNull(PinyinAnnotation.annotate("银犇", readings(listOf("银犇"))))

    @Test fun importReadsBareWordsAndSkipsKnownSingleCharacters() {
        val annotator = TxtCellDictionary.Annotator { wanted ->
            PinyinAnnotation.readings(listOf({ table.byteInputStream() }), wanted)
        }
        val words = ArrayList<String>()
        val text = "银行\n长江大桥\n大\n犇 ben\n犇犇\n世界 shi jie\n"
        val summary = TxtCellDictionary.read({ text.byteInputStream() }, annotator = annotator) { words += it.word + "|" + it.code }
        assertEquals(listOf("世界|shi jie", "银行|yin hang", "长江大桥|chang jiang da qiao", "犇|ben"), words)
        assertEquals(2, summary.annotated)
        assertEquals(1, summary.known)
        assertEquals(1, summary.skipped)
    }

    @Test fun withoutAnAnnotatorBareWordsAreSkippedAsBefore() {
        val summary = TxtCellDictionary.read({ "银行\n世界 shi jie\n".byteInputStream() }) { }
        assertEquals(1, summary.count)
        assertEquals(1, summary.skipped)
    }
}
