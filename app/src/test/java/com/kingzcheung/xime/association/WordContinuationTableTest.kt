package com.kingzcheung.xime.association

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WordContinuationTableTest {
    private val header = WordContinuationTable.HEADER + "\n"
    private fun table() = WordContinuationTable.read((header +
        "厄尔尼诺\t现象\t1248\t1400\n厄尔尼诺\t事件\t83\t1400\n尼诺\t曼\t10\t100\n诺贝尔\t奖\t50\t100\n").reader())

    /** 用户反馈（11-16）：打完「厄尔尼诺」联想只跟「诺」字（曼、斯），不跟整词（现象）。 */
    @Test fun theLongestWordTheContextEndsWithIsUsed() {
        val table = table()
        assertEquals(3, table.contextCount)
        val whole = table.lookup("今年的厄尔尼诺")!!
        assertEquals(4, whole.contextLength)
        assertEquals(listOf("现象", "事件"), whole.candidates.map { it.text })
        assertEquals(1248f / 1400f, whole.candidates.first().score, 1e-6f)
        assertEquals(listOf("现象"), table.lookup("厄尔尼诺", 1)!!.candidates.map { it.text })
        // 整词不在表里时退到它末尾更短的词。
        val shorter = table.lookup("帕尼诺")!!
        assertEquals(2, shorter.contextLength)
        assertEquals(listOf("曼"), shorter.candidates.map { it.text })
        assertEquals(3, table.lookup("诺贝尔")!!.contextLength)
        // 一个字的上文不归这张表；标点、字母断开上文。
        assertNull(table.lookup("他说诺"))
        assertNull(table.lookup("厄尔尼诺。"))
        assertNull(table.lookup("hello"))
        assertNull(table.lookup(""))
        assertNull(table.lookup("厄尔尼诺", 0))
    }

    @Test fun onlyAWordLongerThanTheCorpusContextJoins() {
        val words = table().lookup("厄尔尼诺")!!
        // 联想表只认得最后一个字「诺」：整词的接续并进来，把握大的排前面，原来的还在。
        val scores = mutableMapOf("一" to 0.07f, "曼" to 0.02f)
        mergeWordContinuations(scores, 1, words, 0.7f)
        assertEquals(listOf("现象", "一", "事件", "曼"), scores.entries.sortedByDescending { it.value }.map { it.key })
        assertEquals(0.7f * 1248f / 1400f, scores.getValue("现象"), 1e-6f)
        // 联想表认得的上文一样长或更长（「尼诺」两个字都认得）时不并。
        val two = table().lookup("尼诺")!!
        val unchanged = mutableMapOf("先生" to 0.3f)
        mergeWordContinuations(unchanged, 2, two, 0.7f)
        assertEquals(mapOf("先生" to 0.3f), unchanged)
        mergeWordContinuations(unchanged, 1, null, 0.7f)
        assertEquals(mapOf("先生" to 0.3f), unchanged)
        // 两边都有的候选取分数大的，不相加。
        val both = mutableMapOf("现象" to 0.9f, "事件" to 0.01f)
        mergeWordContinuations(both, 0, words, 0.7f)
        assertEquals(0.9f, both.getValue("现象"), 1e-6f)
        assertEquals(0.7f * 83f / 1400f, both.getValue("事件"), 1e-6f)
    }

    private fun rejected(rows: String, withHeader: Boolean = true): Boolean = try {
        WordContinuationTable.read(((if (withHeader) header else "") + rows).reader())
        false
    } catch (expected: IllegalArgumentException) { true }

    @Test fun malformedFilesAreRejected() {
        assertTrue(rejected("厄尔尼诺\t现象\t1248\t1400\n", withHeader = false))
        // 上文没按顺序、同一个上文分成两段。
        assertTrue(rejected("诺贝尔\t奖\t50\t100\n尼诺\t曼\t10\t100\n"))
        assertTrue(rejected("尼诺\t曼\t10\t100\n诺贝尔\t奖\t50\t100\n尼诺\t斯\t5\t100\n"))
        // 同一个上文没按词频从大到小、超过四行。
        assertTrue(rejected("尼诺\t曼\t10\t100\n尼诺\t斯\t20\t100\n"))
        assertTrue(rejected("尼诺\t甲\t9\t100\n尼诺\t乙\t8\t100\n尼诺\t丙\t7\t100\n尼诺\t丁\t6\t100\n尼诺\t戊\t5\t100\n"))
        // 一个字的上文、带字母的上文、词频比总数大。
        assertTrue(rejected("诺\t曼\t10\t100\n"))
        assertTrue(rejected("a诺\t曼\t10\t100\n"))
        assertTrue(rejected("尼诺\t曼\t101\t100\n"))
        assertFalse(rejected("尼诺\t曼\t10\t100\n\n诺贝尔\t奖\t50\t100\n"))
    }

    /** 打包生成的那张表（跑过 prepareWordAssociations 才有）和内置联想表一起：厄尔尼诺后面第一个是「现象」。 */
    @Test fun generatedTableFollowsTheWholeWord() {
        val generated = java.io.File("build/generated/word-association-assets/association/dict-words-v1.gz")
        assumeTrue(generated.isFile)
        fun gz(file: java.io.File) = java.util.zip.GZIPInputStream(file.inputStream()).reader(Charsets.UTF_8)
        val words = gz(generated).use(WordContinuationTable::read)
        assertTrue(words.contextCount.toString(), words.contextCount in 50_000..250_000)
        val dir = java.io.File("src/main/assets/association")
        val base = gz(java.io.File(dir, "lccc-base-v1.bin")).use { table ->
            BaseAssociationModel.readWithWordCompletions(table,
                gz(java.io.File(dir, "lccc-base-v1.completions.gz")).use(BaseAssociationModel::readWordCompletions))
        }
        fun top(context: String, afterWholeWord: Boolean): List<String> {
            val scores = mutableMapOf<String, Float>()
            base.predict(context, BaseAssociationModel.MAX_PER_CONTEXT, afterWholeWord).forEach {
                scores[it.text] = NgramFusionEngine.BASE_WEIGHT * it.score
            }
            mergeWordContinuations(scores, base.contextLength(context), words.lookup(context), NgramFusionEngine.BASE_WEIGHT)
            return scores.entries.sortedByDescending { it.value }.map { it.key }
        }
        // 联想表里没有「尼诺」，只认得「诺」。
        assertEquals(1, base.contextLength("厄尔尼诺"))
        assertEquals("现象", top("厄尔尼诺", true).first())
        // 一个字一个字上屏的也一样（最后上屏的是「诺」）。
        assertEquals("现象", top("厄尔尼诺", false).first())
        assertEquals("奖", top("诺贝尔", true).first())
        // 联想表自己认得的两个字的词（晚上、谢谢）不进这张表，联想照旧。
        for (context in listOf("晚上", "谢谢")) {
            assertEquals(2, base.contextLength(context))
            assertNull(words.lookup(context))
            assertEquals(base.predict(context, BaseAssociationModel.MAX_PER_CONTEXT, true).map { it.text }, top(context, true))
        }
    }
}
