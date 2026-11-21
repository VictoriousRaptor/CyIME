package com.kingzcheung.xime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 群友 11-19：「方块字」页带框的「有」出现两次。符号面板每一页都不能有重复的格子（不计变体选择符）。 */
class SymbolDataNoDuplicateTest {
    private fun key(s: String) = s.replace("️", "").replace("︎", "")

    @Test fun noCategoryRepeatsASymbol() {
        val repeated = SymbolData.categories.flatMap { category ->
            category.symbols.groupBy(::key).filterValues { it.size > 1 }.keys.map { "${category.id}: $it" }
        }
        assertEquals(emptyList<String>(), repeated)
    }

    @Test fun boxedIdeographsHaveOneYouAndTheDiscountSign() {
        val boxed = SymbolData.categories.first { it.id == "circledChinese" }.symbols
        assertEquals(1, boxed.count { it == "🈶" }) // 🈶 有
        assertTrue(boxed.contains("🈹")) // 🈹 割
    }

    @Test fun emojiCategoriesHaveNoRepeats() {
        val repeated = EmojiData.categories.flatMap { category ->
            category.emojis.groupBy(::key).filterValues { it.size > 1 }.keys.map { "${category.name}: $it" }
        }
        assertEquals(emptyList<String>(), repeated)
    }
}
