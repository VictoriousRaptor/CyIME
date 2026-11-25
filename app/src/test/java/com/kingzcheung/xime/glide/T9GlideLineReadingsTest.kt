package com.kingzcheung.xime.glide

import com.kingzcheung.xime.settings.DoublePinyinScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #582：九键滑行打入 94664 时，同一条线上的 xing（9464）也要进左栏。 */
class T9GlideLineReadingsTest {
    @Test
    fun sameLineCodeAddsItsReadings() {
        val readings = T9Glide.lineReadings("94664", listOf("94664", "9464"))
        val xing = readings.first { it.pinyin == "xing" }
        assertEquals("9464", xing.code)
        assertEquals(4, xing.digits)
        // xi、xin 打入的 94664 自己拼得出，左栏本来就有。
        assertFalse(readings.any { it.pinyin == "xi" || it.pinyin == "xin" })
    }

    @Test
    fun otherLinesAddNothing() {
        assertTrue(T9Glide.lineReadings("94664", listOf("9463", "946")).isEmpty())
        assertTrue(T9Glide.lineReadings("94664", emptyList()).isEmpty())
    }

    @Test
    fun longerCodeWorksTheOtherWay() {
        val readings = T9Glide.lineReadings("9464", listOf("94664"))
        assertTrue(readings.any { it.pinyin == "xiong" && it.code == "94664" && it.digits == 5 })
    }

    @Test
    fun doublePinyinUsesSchemeKeys() {
        val flypy = DoublePinyinScheme.FLYPY
        // 两个键在同一个数字上的双拼码（如 33）划出来只有一个 3。
        val (syllable, keys) = DoublePinyinScheme.SYLLABLES.asSequence()
            .mapNotNull { s -> flypy.code(s)?.let { code -> T9Glide.digits(code)?.let { s to it } } }
            .first { (_, keys) -> keys[0] == keys[1] }
        val readings = T9Glide.lineReadings(keys.take(1), listOf(keys), flypy)
        val reading = readings.first { it.pinyin == syllable }
        assertEquals(flypy.code(syllable), reading.spelling)
        assertEquals(2, reading.digits)
    }

    @Test
    fun capped() {
        assertTrue(T9Glide.lineReadings("64426", listOf("6426", "64426", "66426", "644266")).size <= T9Glide.MAX_LINE_READINGS)
    }
}
