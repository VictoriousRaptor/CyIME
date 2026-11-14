package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.InputProfiles
import org.junit.Assert.*
import org.junit.Test

class QwertyBottomRowConfigTest {
    @Test fun movementCrossesSpaceAndKeepsRequiredKeys() {
        val moved = QwertyBottomRowConfig.Default.move(QwertyBottomKey.COMMA, 1)
        assertEquals("symbols,numbers,space,comma,period,language", moved.encode())
        assertEquals(moved, moved.remove(QwertyBottomKey.LANGUAGE))
        assertEquals(moved, moved.move(QwertyBottomKey.SPACE, -1))
        assertEquals(moved, moved.move(QwertyBottomKey.ENTER, -1))
        val hidden = moved.remove(QwertyBottomKey.COMMA).remove(QwertyBottomKey.PERIOD)
        assertEquals("symbols,numbers,space,language", hidden.encode())
        assertEquals(1, hidden.add(QwertyBottomKey.COMMA).add(QwertyBottomKey.COMMA).keys.count { it == QwertyBottomKey.COMMA })
        assertEquals(moved, QwertyBottomRowConfig.parse(moved.encode()))
    }

    @Test fun cycleHidesNumbersWithoutOverwritingTheirSavedPosition() {
        val config = QwertyBottomRowConfig.parse("language,numbers,comma,space,symbols,period")
        assertEquals(listOf("language", "comma", "space", "symbols", "period", "enter"), config.visible(true).map { it.id })
        val moved = config.move(QwertyBottomKey.LANGUAGE, 1, cycle = true)
        assertEquals("comma,numbers,language,space,symbols,period", moved.encode())
        assertEquals(QwertyBottomKey.NUMBERS, moved.visible(false)[1])
    }

    @Test fun invalidSavedLayoutsRecoverRequiredKeysAndDefaultPunctuation() {
        for (raw in listOf(null, "", "space", "symbols,numbers,space,space,language", "symbols,numbers,space,language,unknown")) {
            assertEquals("symbols,numbers,comma,space,period,language", QwertyBottomRowConfig.parse(raw).encode())
        }
        assertEquals("symbols,numbers,space,language", QwertyBottomRowConfig.parse("symbols,numbers,space,language").encode())
    }

    @Test fun colorsFollowVisibleEdgesInsteadOfFunctions() {
        val config = QwertyBottomRowConfig.parse("comma,language,symbols,space,numbers,period")
        val slots = config.slots(cycle = false, split = false)
        assertEquals(listOf(true, true, false, false, false, true, true), slots.map { it.special })
        assertEquals(listOf(true, true, false, false, true, true), config.slots(true, false).map { it.special })
        val four = config.remove(QwertyBottomKey.COMMA).remove(QwertyBottomKey.PERIOD).slots(true, false)
        assertEquals(4, four.size)
        assertTrue(four.all { it.special })
        assertEquals(10f, four.sumOf { it.weight.toDouble() }.toFloat(), .0001f)
    }

    @Test fun allPermutationsKeepSplitKeysWholeAndBothHalvesAligned() {
        fun permutations(keys: List<QwertyBottomKey>): List<List<QwertyBottomKey>> =
            if (keys.isEmpty()) listOf(emptyList()) else keys.flatMap { key -> permutations(keys - key).map { listOf(key) + it } }
        val defaults = QwertyBottomRowConfig.Default
        val variants = listOf(defaults, defaults.remove(QwertyBottomKey.COMMA), defaults.remove(QwertyBottomKey.PERIOD),
            defaults.remove(QwertyBottomKey.COMMA).remove(QwertyBottomKey.PERIOD))
        for (variant in variants) for (keys in permutations(variant.keys)) for (cycle in listOf(false, true)) {
            val config = QwertyBottomRowConfig.parse(keys.joinToString(",") { it.id })
            val slots = config.slots(cycle, split = true)
            val gap = slots.indexOfFirst { it.key == null }
            assertTrue(gap > 0 && gap < slots.lastIndex)
            assertEquals(5f, slots.take(gap).sumOf { it.weight.toDouble() }.toFloat(), .0001f)
            assertEquals(5f, slots.drop(gap + 1).sumOf { it.weight.toDouble() }.toFloat(), .0001f)
            assertTrue(slots.filter { it.key != null }.all { it.weight > 0f })
            assertEquals(config.visible(cycle), slots.mapNotNull { it.key }.distinct())
            for (key in config.visible(cycle).filter { it != QwertyBottomKey.SPACE }) {
                assertEquals(1, slots.count { it.key == key })
            }
            val unsplit = config.slots(cycle, false).associate { it.key to it.special }
            assertTrue(slots.filter { it.key != null }.all { it.special == unsplit[it.key] })
        }
    }

    @Test fun onlyActualThreeRow26KeyLayoutsUseTheNewConfig() {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { it.map(Char::toString) }
        assertTrue(usesCustom26BottomRow(InputProfiles.describe("rime_ice"), rows))
        assertFalse(usesCustom26BottomRow(InputProfiles.describe("pinyin_14jian"), rows))
        assertFalse(usesCustom26BottomRow(InputProfiles.describe("t9_pinyin"), rows))
        assertFalse(usesCustom26BottomRow(InputProfiles.describe("rime_ice"), listOf(listOf("qw", "er"))))
    }
}
