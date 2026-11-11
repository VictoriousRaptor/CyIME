package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test

class LanguageSwitchPreferencesTest {
    private val zh = InputLanguage.CHINESE
    private val en = InputLanguage.ENGLISH
    private val ja = InputLanguage.JAPANESE
    private val all = linkedSetOf(zh, ja, en)

    @Test fun defaultKeepsCurrentNativeLanguageAndEnglishRegardlessOfLanguageOrder() {
        val options = LanguageSwitchOptions()
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, listOf(ja, zh, en), all))
        assertEquals(zh, LanguageSwitchPreferences.target(en, zh, options, listOf(ja, zh, en), all))
        assertEquals(en, LanguageSwitchPreferences.target(ja, ja, options, listOf(zh, en, ja), all))
        assertEquals(ja, LanguageSwitchPreferences.target(en, ja, options, listOf(zh, en, ja), all))
    }

    @Test fun defaultDoesNotEnableADisabledNativeLanguage() {
        assertNull(LanguageSwitchPreferences.target(en, zh, LanguageSwitchOptions(), listOf(en), setOf(en)))
    }

    @Test fun cycleUsesTheSavedLanguageOrderAndWraps() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.CYCLE)
        val order = listOf(en, ja, zh)
        assertEquals(ja, LanguageSwitchPreferences.target(en, zh, options, order, all))
        assertEquals(zh, LanguageSwitchPreferences.target(ja, ja, options, order, all))
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, order, all))
    }

    @Test fun cycleSkipsDisabledOrUnavailableLanguagesAndDuplicateEntries() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.CYCLE)
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, listOf(zh, ja, ja, en), setOf(zh, en)))
        assertNull(LanguageSwitchPreferences.target(en, zh, options, listOf(en), setOf(en)))
    }

    @Test fun specificChineseAndEnglishDoesNotDependOnTheEngineFallback() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, zh)
        assertEquals(zh, LanguageSwitchPreferences.target(en, ja, options, all.toList(), all))
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, all.toList(), all))
        assertEquals(zh, LanguageSwitchPreferences.target(ja, ja, options, all.toList(), all))
    }

    @Test fun specificJapaneseAndEnglishRetainsAnExplicitChoice() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, ja)
        assertEquals(ja, LanguageSwitchPreferences.target(zh, zh, options, all.toList(), all))
        assertEquals(en, LanguageSwitchPreferences.target(ja, ja, options, all.toList(), all))
        assertEquals(ja, LanguageSwitchPreferences.target(en, zh, options, all.toList(), all))
    }

    @Test fun invalidSpecificChoiceFallsBackToAnAvailableNativeLanguage() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, ja)
        assertEquals(zh, LanguageSwitchPreferences.target(en, zh, options, listOf(en, zh), setOf(en, zh)))
        assertNull(LanguageSwitchPreferences.target(en, zh, options, listOf(en), setOf(en)))
    }

    @Test fun specificChoicesListEveryNonEnglishLanguageNotOnlyChineseAndJapanese() {
        val choices = LanguageSwitchPreferences.specificChoices(listOf(zh, en))
        assertFalse(en in choices)
        assertEquals(zh, choices.first())
        assertEquals(InputLanguage.supported.filterNot { it == en }.toSet(), choices.toSet())
        if (InputLanguage.SPANISH in InputLanguage.supported) assertTrue(InputLanguage.SPANISH in choices)
    }

    @Test fun specificSpanishAndEnglishSwitchesBothWays() {
        val es = InputLanguage.SPANISH
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, es)
        val available = setOf(zh, en, es)
        assertEquals(es, LanguageSwitchPreferences.target(zh, zh, options, listOf(zh, en, es), available))
        assertEquals(en, LanguageSwitchPreferences.target(es, es, options, listOf(zh, en, es), available))
        assertEquals(es, LanguageSwitchPreferences.target(en, zh, options, listOf(zh, en, es), available))
    }

    @Test fun specificPairSwitchesBetweenTwoLanguagesWithoutEnglish() {
        val ita = InputLanguage.ITALIAN
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, ita)
        val available = setOf(zh, en, ita)
        val order = listOf(zh, en, ita)
        assertEquals(ita, LanguageSwitchPreferences.target(zh, zh, options, order, available))
        assertEquals(zh, LanguageSwitchPreferences.target(ita, ita, options, order, available))
        // 在英文里按语言键：回到第一种语言。
        assertEquals(zh, LanguageSwitchPreferences.target(en, zh, options, order, available))
    }

    @Test fun specificPairFallsBackToEnglishWhenThePartnerIsMissing() {
        val ita = InputLanguage.ITALIAN
        // 没选另一种、另一种没开、或和第一种相同：照「指定语言与英文切换」。
        for (partner in listOf(null, ita, zh)) {
            val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, partner)
            assertEquals("$partner", en, LanguageSwitchPreferences.target(zh, zh, options, listOf(zh, en), setOf(zh, en)))
            assertEquals("$partner", zh, LanguageSwitchPreferences.target(en, zh, options, listOf(zh, en), setOf(zh, en)))
        }
        assertNull(LanguageSwitchPreferences.target(en, zh, LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, ita),
            listOf(en), setOf(en)))
    }

    @Test fun pairChoiceKeepsTheOldStoredModes() {
        val ita = InputLanguage.ITALIAN
        // 「当前语言 ↔ 英文」就是原来的默认，存的值不变。
        assertEquals(LanguageSwitchOptions(), LanguageSwitchPreferences.pair(null, en))
        assertEquals(LanguageSwitchOptions(), LanguageSwitchPreferences.pair(null, ita))
        assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, ja), LanguageSwitchPreferences.pair(ja, en))
        assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, ja), LanguageSwitchPreferences.pair(ja, ja))
        assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, ita), LanguageSwitchPreferences.pair(zh, ita))
    }

    @Test fun switchingLanguageDoesNotOverrideSelectedSchemeOrLayout() {
        val entries = listOf("rime_ice", "t9_pinyin", "japanese", "japanese_kana").map { SchemaInfo(it, it, "", "", "") }
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, ja)
        val target = LanguageSwitchPreferences.target(en, zh, options, all.toList(), all)!!
        assertEquals("japanese_kana", InputProfileSelection.preferred(entries, target, "japanese_kana", "t9_pinyin")?.schemaId)
    }
}
