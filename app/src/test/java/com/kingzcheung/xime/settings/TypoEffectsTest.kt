package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 纠错面板的数只照实测表显示：现在是哪种开关、强度组合就读哪一行，表里没有的组合不显示、不拿别的顶替。 */
class TypoEffectsTest {
    private val sample = """
        {"version": 1, "measuredAt": "2026-11-16", "future_field": 1,
         "fullPinyin": {"cleanCases": 5638, "states": {
           "lead:careful": {"cleanBroken": 12, "kinds": {
             "sub": {"wordFirst": 56.2, "wordTop3": 95.8, "sentenceFirst": 82.5, "sentenceTop3": 94.2},
             "double": {"wordFirst": 10.9, "wordTop3": 73.6, "sentenceFirst": 0.0, "sentenceTop3": 28.3},
             "extra": {"wordFirst": 3.5, "wordTop3": 46.7, "sentenceFirst": 0.8, "sentenceTop3": 20.0},
             "omit": {"wordFirst": 20.7, "wordTop3": 56.2, "sentenceFirst": 15.8, "sentenceTop3": 64.2},
             "swapin": {"wordFirst": 39.8, "wordTop3": 58.2, "sentenceFirst": 32.5, "sentenceTop3": 43.3}}},
           "lead:careful-extra": {"cleanBroken": 11, "kinds": {
             "sub": {"wordFirst": 56.4, "wordTop3": 96.0, "sentenceFirst": 82.5, "sentenceTop3": 94.2},
             "double": {"wordFirst": 0.0, "wordTop3": 4.0, "sentenceFirst": 0.0, "sentenceTop3": 1.7},
             "extra": {"wordFirst": 0.0, "wordTop3": 5.0, "sentenceFirst": 0.0, "sentenceTop3": 2.5},
             "omit": {"wordFirst": 20.7, "wordTop3": 56.4, "sentenceFirst": 15.8, "sentenceTop3": 64.2},
             "swapin": {"wordFirst": 39.8, "wordTop3": 58.4, "sentenceFirst": 32.5, "sentenceTop3": 43.3}}},
           "lead:careful-swap": {"cleanBroken": 12, "kinds": {
             "swapin": {"wordFirst": 0.0, "wordTop3": 9.6, "sentenceFirst": 0.0, "sentenceTop3": 5.8}}},
           "lead:never": {"cleanBroken": 0, "kinds": {
             "sub": {"wordFirst": 29.8, "wordTop3": 96.0, "sentenceFirst": 39.2, "sentenceTop3": 89.2}}},
           "nomap": {"cleanBroken": 0, "kinds": {
             "sub": {"wordFirst": 0.4, "wordTop3": 1.1, "sentenceFirst": 0.0, "sentenceTop3": 0.8},
             "double": {"wordFirst": 0.0, "wordTop3": 2.0, "sentenceFirst": 0.0, "sentenceTop3": 1.0},
             "extra": {"wordFirst": 0.0, "wordTop3": 3.0, "sentenceFirst": 0.0, "sentenceTop3": 2.0},
             "omit": {"wordFirst": 20.0, "wordTop3": 55.0, "sentenceFirst": 15.0, "sentenceTop3": 63.0},
             "swapin": {"wordFirst": 0.0, "wordTop3": 30.0, "sentenceFirst": 0.0, "sentenceTop3": 20.0}}}}},
         "nineKey": {"cleanCases": 670, "states": {
           "digit:standard": {"cleanBroken": 1, "kinds": {
             "sub": {"wordTop3": 40.0, "sentenceTop3": 39.2}, "omit": {"wordTop3": 46.4, "sentenceTop3": 36.7},
             "swapin": {"wordTop3": 56.4, "sentenceTop3": 44.2}}}}}}
    """.trimIndent()

    private val table = checkNotNull(TypoEffects.parse(sample))
    private val all = SpellingPatches.Typo.entries.toSet()

    @Test fun everySettingHasItsOwnRow() {
        assertEquals("lead:careful", TypoEffects.stateKey(true, TypoStrength.Lead.CAREFUL, all))
        assertEquals("lead:careful_more-extra", TypoEffects.stateKey(true, TypoStrength.Lead.CAREFUL_MORE, all - SpellingPatches.Typo.EXTRA))
        assertEquals("lead:never-swap-extra-omit", TypoEffects.stateKey(true, TypoStrength.Lead.NEVER, emptySet()))
        // 邻键纠错关着时强度不起作用：哪一档都是同一行。
        assertEquals("nomap-omit", TypoEffects.stateKey(false, TypoStrength.Lead.EAGER, all - SpellingPatches.Typo.OMISSION))
        assertEquals(TypoEffects.stateKey(false, TypoStrength.Lead.NEVER, all), TypoEffects.stateKey(false, TypoStrength.Lead.EAGER, all))
        // 5 档 × 8 种开关组合，加邻键关着的 8 种，名字都不重。
        val keys = buildSet<String> {
            for (neighbor in listOf(true, false)) for (lead in TypoStrength.Lead.entries) for (bits in 0 until 8)
                add(TypoEffects.stateKey(neighbor, lead, SpellingPatches.Typo.entries.filterIndexedTo(HashSet()) { i, _ -> bits shr i and 1 == 1 }))
        }
        assertEquals(TypoStrength.Lead.entries.size * 8 + 8, keys.size)
    }

    @Test fun summaryIsTheMeasuredRowOfTheCurrentSetting() {
        assertEquals(listOf(
            "按到旁边的键：词 56% 排第一、96% 进前三，句 83% 排第一、94% 进前三",
            "同一个键多按一下：词 4%、句 2% 进前三",
            "多碰到旁边的键：词 5%、句 3% 进前三",
            "少打一个字母：词 56%、句 64% 进前三",
            "两个字母打反：词 58%、句 43% 进前三",
            "没打错的 5638 条少见词句里，11 条的第一个候选被改掉。"),
            TypoEffects.summary(table.fullPinyin, true, TypoStrength.Lead.CAREFUL, all - SpellingPatches.Typo.EXTRA))
        val neighborOff = TypoEffects.summary(table.fullPinyin, false, TypoStrength.Lead.CAREFUL, all)
        assertEquals("按到旁边的键：词 0% 排第一、1% 进前三，句 0% 排第一、1% 进前三", neighborOff.first())
        assertEquals("没打错的 5638 条少见词句，第一个候选都没被改掉。", neighborOff.last())
    }

    @Test fun aSettingThatWasNotMeasuredShowsNothing() {
        // 样表里「不抢第一」只有按到旁边的键：这一档下面那句有数，合起来的几行缺别的错法，整块不显示。
        assertNotNull(TypoEffects.leadText(table.fullPinyin, TypoStrength.Lead.NEVER, all))
        assertTrue(TypoEffects.summary(table.fullPinyin, true, TypoStrength.Lead.NEVER, all).isEmpty())
        assertTrue(TypoEffects.summary(table.fullPinyin, true, TypoStrength.Lead.CAREFUL, emptySet()).isEmpty())
        assertNull(TypoEffects.leadText(table.fullPinyin, TypoStrength.Lead.EAGER, all))
        assertNull(TypoEffects.typoText(table.fullPinyin, SpellingPatches.Typo.EXTRA, true, TypoStrength.Lead.EAGER, all))
        assertNull(TypoEffects.typoText(table.fullPinyin, SpellingPatches.Typo.OMISSION, true, TypoStrength.Lead.CAREFUL, all))
        assertNull(TypoEffects.digitText(table.nineKey, TypoStrength.Digit.STRICT))
        assertNull(TypoEffects.parse("not json"))
    }

    @Test fun textsUnderTheSlidersAndSwitches() {
        assertEquals("实测：按到旁边一个键，词 30% 排第一、96% 进前三，句 39% 排第一、89% 进前三。没打错的 5638 条少见词句，第一个候选都没被改掉。",
            TypoEffects.leadText(table.fullPinyin, TypoStrength.Lead.NEVER, all))
        // 开关关着时也照样写「开着多少、关掉多少」，别的设置按现在的。
        assertEquals("实测：两个字母打反，开着词 58%、句 43% 进前三，关掉 10%、6%。",
            TypoEffects.typoText(table.fullPinyin, SpellingPatches.Typo.TRANSPOSITION, true, TypoStrength.Lead.CAREFUL, all - SpellingPatches.Typo.TRANSPOSITION))
        assertEquals("实测：同一个键多按一下，开着词 74%、句 28% 进前三，关掉 4%、2%；多碰到旁边的键，开着词 47%、句 20% 进前三，关掉 5%、3%。",
            TypoEffects.typoText(table.fullPinyin, SpellingPatches.Typo.EXTRA, true, TypoStrength.Lead.CAREFUL, all))
        assertEquals("实测(停一下以后)：按到上下左右的键 词 40%、句 39% 进前三；少按一个 词 46%、句 37% 进前三；两个按反 词 56%、句 44% 进前三。" +
            "没打错的 670 条里，1 条的前三个候选变了。", TypoEffects.digitText(table.nineKey, TypoStrength.Digit.STANDARD))
    }

    /** 随安装包的表：面板上能调出来的每一种组合、每种错法都有数（少一个，那里就是空的）。 */
    @Test fun bundledTableCoversEverySettingThePanelCanShow() {
        val bundled = checkNotNull(TypoEffects.parse(File("src/main/assets/typo_effects.json").readText()))
        val full = bundled.fullPinyin
        assertTrue(full.cleanCases > 0)
        for (neighbor in listOf(true, false)) for (lead in TypoStrength.Lead.entries) for (bits in 0 until 8) {
            val typos = SpellingPatches.Typo.entries.filterIndexedTo(HashSet()) { i, _ -> bits shr i and 1 == 1 }
            val key = TypoEffects.stateKey(neighbor, lead, typos)
            assertEquals(key, TypoEffects.Kind.entries.size + 1, TypoEffects.summary(full, neighbor, lead, typos).size)
            for (typo in SpellingPatches.Typo.entries) assertNotNull("$key ${typo.key}", TypoEffects.typoText(full, typo, neighbor, lead, typos))
            if (neighbor) assertNotNull(key, TypoEffects.leadText(full, lead, typos))
        }
        for (digit in TypoStrength.Digit.entries) assertNotNull(digit.id, TypoEffects.digitText(bundled.nineKey, digit))
        // 越保守，没打错的被改掉的越少。
        val broken = TypoStrength.Lead.entries.map { checkNotNull(full.states[TypoEffects.stateKey(true, it, all)]).cleanBroken }
        assertEquals(broken.sorted(), broken)
    }
}
