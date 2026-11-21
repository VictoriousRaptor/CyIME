package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #214：九键上 高兴 4269464 和 高中 42694664、行 9464 和 中 94664 划出来是同一条线，首选只能按词频猜，猜错的一直错。
 * 点过一次备选就记住，下次划同一条线先出点过的词。
 */
class T9GlideMemoryTest {
    private val w = 120f
    private val h = 90f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        for (digit in 2..9) {
            val index = digit - 1
            put('0' + digit, LetterKeyRect(index % 3 * (w + 6f), index / 3 * (h + 6f), w, h))
        }
    }
    private val entries = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.trim().split(' ') }
    private val words = File("src/main/assets/glide_pinyin_words.txt").readLines()
    private val codeEntries = T9Glide.codeEntries(entries)
    private val index = codeEntries.toMap()
    private val wordOf: (String) -> String? = { code -> index[code]?.let { words.getOrNull(it) } }
    private val lines = T9Glide.lines(codeEntries.map { it.first })
    private val decoder = GlideDecoder(T9Glide.keyboard(keys), codeEntries.map { it.first }, T9Glide.MAX_ENDPOINT)

    private fun center(digit: Char) = keys.getValue(digit).let { (it.x + it.width / 2) to (it.y + it.height / 2) }

    /** 依次经过各键中心的折线（连按的键只经过一次）。 */
    private fun trace(digits: String): Pair<FloatArray, FloatArray> {
        val points = GlideDecoder.collapseRepeats(digits).map { center(it) }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        points.zipWithNext().forEach { (a, b) ->
            for (step in 0 until 16) {
                val t = step / 16f
                xs += a.first + (b.first - a.first) * t; ys += a.second + (b.second - a.second) * t
            }
        }
        xs += points.last().first; ys += points.last().second
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun glide(digits: String, preferred: String?): Pair<String, List<String>> {
        val (xs, ys) = trace(digits)
        val found = decoder.decode(xs, ys, limit = 3).map { it.word }
        val line = GlideDecoder.collapseRepeats(found.first())
        val ranked = T9GlideMemory.promote(found, preferred, lines[line].orEmpty(), wordOf)
        return ranked.first() to T9Glide.alternatives(ranked, { lines[it].orEmpty() }, wordOf).take(2)
    }

    @Test fun aChosenAlternativeComesFirstNextTime() {
        // 不记时：高兴 的线首选是 高中，高兴 在备选里。
        val (before, beforeAlternatives) = glide("4269464", null)
        assertEquals("高中", wordOf(before))
        assertTrue(beforeAlternatives.toString(), "高兴" in beforeAlternatives)
        // 点过 高兴 以后：首选是 高兴，高中 退到备选。
        val (after, afterAlternatives) = glide("4269464", "高兴")
        assertEquals("4269464", after)
        assertTrue(afterAlternatives.toString(), "高中" in afterAlternatives)
        // 行 和 中 同理。
        assertEquals("9464", glide("9464", "行").first)
    }

    @Test fun unknownOrUnreachableChoiceChangesNothing() {
        val (xs, ys) = trace("4269464")
        val found = decoder.decode(xs, ys, limit = 3).map { it.word }
        val line = lines[GlideDecoder.collapseRepeats(found.first())].orEmpty()
        assertEquals(found, T9GlideMemory.promote(found, null, line, wordOf))
        assertEquals(found, T9GlideMemory.promote(found, "不在这条线上", line, wordOf))
        assertEquals(found, T9GlideMemory.promote(found, wordOf(found.first()), line, wordOf))
    }

    @Test fun choicesPersistAndOldOnesAreDropped() {
        val dir = Files.createTempDirectory("t9glide").toFile()
        try {
            T9GlideMemory.remember(dir, "4269464", "高兴")
            T9GlideMemory.remember(dir, "4269464", "高中")
            assertEquals("高中", T9GlideMemory.preferred(dir, "4269464"))
            assertNull(T9GlideMemory.preferred(dir, "9464"))
            // 换一个目录再换回来，从文件重新读。
            val other = Files.createTempDirectory("t9glide2").toFile()
            assertNull(T9GlideMemory.preferred(other, "4269464"))
            assertEquals("高中", T9GlideMemory.preferred(dir, "4269464"))
            for (i in 0..T9GlideMemory.LIMIT) T9GlideMemory.remember(dir, "2$i", "词")
            assertNull(T9GlideMemory.preferred(dir, "4269464"))
            assertEquals("词", T9GlideMemory.preferred(dir, "2${T9GlideMemory.LIMIT}"))
            other.deleteRecursively()
        } finally { dir.deleteRecursively() }
    }
}
