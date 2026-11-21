package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test

class InputModesTest {
    private val modes = listOf("t9_pinyin", "rime_ice", "double_pinyin_flypy", "japanese", "japanese_kana")
        .map { SchemaInfo(it, it, "", "", "") }

    @Test fun chineseModesIncludeShuangpinButNeverJapaneseOrEnglish() {
        assertEquals(listOf("t9_pinyin", "rime_ice", "double_pinyin_flypy"),
            InputModes.inCurrentLanguage(modes, "double_pinyin_flypy").map { it.schemaId })
    }
    @Test fun japaneseAndEnglishPanelsOnlyShowTheirOwnModes() {
        assertEquals(listOf("japanese", "japanese_kana"),
            InputModes.inCurrentLanguage(modes, "japanese_kana").map { it.schemaId })
        assertEquals(listOf(InputModes.ENGLISH),
            InputModes.inCurrentLanguage(modes, InputModes.ENGLISH).map { it.schemaId })
    }
    @Test fun languageMenuRestoresEachLanguageModeFromEnglish() {
        val choices = InputModes.languageChoices(modes, InputModes.ENGLISH,
            mapOf(InputLanguage.CHINESE to "double_pinyin_flypy", InputLanguage.JAPANESE to "japanese_kana"))
        assertEquals(listOf("中文", "英文", "日语"), choices.map { it.name })
        assertEquals(listOf("double_pinyin_flypy", InputModes.ENGLISH, "japanese_kana"), choices.map { it.schemaId })
    }
    @Test fun addressFieldRemembersItsChineseLayoutWithoutChangingTheSavedProfile() {
        val saved = mapOf(InputLanguage.CHINESE to "rime_ice")
        val local = InputModes.languageChoices(modes, InputModes.ENGLISH, emptyMap(),
            selectedProfiles = saved, fieldNativeModeId = "t9_pinyin")
        assertEquals("t9_pinyin", local.first { it.language == InputLanguage.CHINESE }.schemaId)
        val ordinary = InputModes.languageChoices(modes, InputModes.ENGLISH, emptyMap(), selectedProfiles = saved)
        assertEquals("rime_ice", ordinary.first { it.language == InputLanguage.CHINESE }.schemaId)
    }
    @Test fun currentModeWinsAndRemovedModeFallsBackWithinItsLanguage() {
        val choices = InputModes.languageChoices(modes, "rime_ice",
            mapOf(InputLanguage.CHINESE to "t9_pinyin", InputLanguage.JAPANESE to "removed"))
        // 语言菜单和别处同一个先后：中文、英文、日语（6eddc7c4）。
        assertEquals(listOf("rime_ice", InputModes.ENGLISH, "japanese"), choices.map { it.schemaId })
    }
    @Test fun newJapaneseModesCanDeclareTheirLanguageWithoutChangingTheMenu() {
        val extra = SchemaInfo("kana_flick", "假名滑行", "", "", "", language = InputLanguage.JAPANESE)
        assertTrue(InputModes.inCurrentLanguage(modes + extra, "japanese").contains(extra))
    }
    @Test fun reorderingChineseModesPreservesJapaneseAndEnglishSlots() {
        val current = listOf("t9_pinyin", "japanese", "rime_ice", InputModes.ENGLISH, "japanese_kana")
        assertEquals(listOf("rime_ice", "japanese", "t9_pinyin", InputModes.ENGLISH, "japanese_kana"),
            InputModes.mergeOrder(current, listOf("rime_ice", "t9_pinyin")))
    }

    @Test fun independentLanguageOrderKeepsTheRememberedInputMode() {
        // 这是所有语言的排位（含未开启的），显示时 languageOrder(context) 再只留已开启的。
        val order = InputModes.languageOrder(listOf("en", "ja", "ja", "unknown"))
        assertEquals(listOf(InputLanguage.ENGLISH, InputLanguage.JAPANESE, InputLanguage.CHINESE) +
            LetterLanguage.entries.map { it.language }, order)
        val choices = InputModes.languageChoices(modes, "rime_ice", mapOf(InputLanguage.JAPANESE to "japanese_kana"), order)
        assertEquals(listOf(InputModes.ENGLISH, "japanese_kana", "rime_ice"), choices.map { it.schemaId })
        assertEquals(listOf("rime_ice", "t9_pinyin"), InputModes.available(modes, InputModes.defaultModeOrder).take(2).map { it.schemaId })
        assertEquals("t9_pinyin", InputModes.available(modes, listOf("t9_pinyin", "rime_ice")).first().schemaId)
    }

    @Test fun japaneseComesAfterEnglishByDefaultAndWhenSwitchedOnLater() {
        assertEquals(listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.JAPANESE) +
            LetterLanguage.entries.map { it.language }, InputModes.languageOrder(emptyList()))
        // A saved order from older versions put Japanese before English; switching Japanese on moves it last.
        // 未开启的西班牙、德、法、俄、韩语只占排位，开启时会再排到最后。
        val letters = LetterLanguage.entries.map { it.language.id }
        assertEquals(listOf("zh", "en") + letters + "ja", InputModes.orderWithLanguageLast(listOf("zh", "ja", "en"), InputLanguage.JAPANESE))
        // 德语后开启，同样排到已开启语言后面。
        assertEquals(listOf("zh", "en", "ja") + (letters - "de") + "de",
            InputModes.orderWithLanguageLast(listOf("zh", "en", "ja"), InputLanguage.GERMAN))
    }

    /** 没排过序时字母排列（Colemak、Cyletix10、Typewise、QWJRTK、自定义）排在其他方案后面，排过的不动。 */
    @Test fun letterArrangementsGoLastUnlessTheUserOrderedThem() {
        val ids = listOf("pinyin_colemak", "rime_ice", QwjrtkLayout.ID, "cyime_cangjie", "custom_pinyin_0123",
            "t9_pinyin", "pinyin_typewise", "japanese_kana", "japanese")
        val schemas = ids.map { SchemaInfo(it, it, "", "", "") }
        assertEquals(listOf("rime_ice", "t9_pinyin", "japanese", "japanese_kana", InputModes.ENGLISH, "cyime_cangjie",
            "pinyin_colemak", QwjrtkLayout.ID, "custom_pinyin_0123", "pinyin_typewise"),
            InputModes.available(schemas, InputModes.defaultModeOrder).map { it.schemaId })
        assertEquals(ids + InputModes.ENGLISH, InputModes.available(schemas, ids).map { it.schemaId })
    }

    @Test fun englishIsAvailableWithNoEnabledSchemas() {
        assertEquals(listOf(InputModes.english), InputModes.available(emptyList()))
    }
    @Test fun repeatedNormalizationKeepsOnePermanentEnglishEntry() {
        val kana = SchemaInfo("japanese_kana", "日语九键", "", "", "")
        val available = InputModes.available(listOf(kana, InputModes.english, kana))
        assertEquals(listOf(kana, InputModes.english), InputModes.available(available))
    }
    @Test fun tapAndMenuUseTheSameEnglishSelectionIdentity() {
        assertEquals(InputModes.ENGLISH, InputModes.selectedId("japanese", true))
        assertEquals("japanese", InputModes.selectedId("japanese", false))
    }
    @Test fun customOrderSurvivesNormalizationAndNewSchemasAppend() {
        val kana = SchemaInfo("japanese", "日语", "", "", "")
        val chinese = SchemaInfo("t9", "拼音", "", "", "")
        val modes = InputModes.available(listOf(chinese, kana, chinese), listOf(InputModes.ENGLISH, "japanese", "missing"))
        assertEquals(listOf(InputModes.ENGLISH, "japanese", "t9"), modes.map { it.schemaId })
        assertEquals(modes, InputModes.available(modes))
    }

    /** 用户 11-19：有的页面日语排在英语上面。各页列语言用同一个先后：拖过的顺序优先，没排过的是中文、英文、日语。 */
    @Test fun everyLanguageListSharesOneOrder() {
        val zh = InputLanguage.CHINESE; val en = InputLanguage.ENGLISH; val ja = InputLanguage.JAPANESE
        assertEquals(listOf(zh, en, ja), InputLanguage.supported.take(3))
        assertEquals(listOf(zh, en, ja), InputLanguage.speech)
        val fresh = InputModes.languageRank(emptyList())
        assertEquals(listOf(zh, en, ja, InputLanguage.SPANISH, InputLanguage.UNSPECIFIED),
            listOf(InputLanguage.UNSPECIFIED, ja, InputLanguage.SPANISH, en, zh).sortedBy(fresh))
        val dragged = InputModes.languageRank(listOf("ja", "zh"))
        assertEquals(listOf(ja, zh, en), listOf(en, zh, ja).sortedBy(dragged))
        assertEquals(listOf("中文", "英文", "日语"), InputModes.languageChoices(modes, "rime_ice", emptyMap()).map { it.name })
    }
}
