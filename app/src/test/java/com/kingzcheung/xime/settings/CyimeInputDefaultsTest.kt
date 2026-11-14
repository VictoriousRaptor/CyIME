package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test

class CyimeInputDefaultsTest {
    @Test fun modesHaveOneEntryPerLayoutWithOneEnglish() {
        val ids = listOf("pinyin_simp", "rime_ice", "t9", "t9_pinyin", "melt_eng", "jaroomaji", "japanese", "japanese_kana", "pinyin_14jian")
        assertEquals(listOf("rime_ice", "t9_pinyin", "japanese", "japanese_kana", "pinyin_14jian"), CyimeInputDefaults.canonicalIds(ids, ids.toSet()))
        val modes = InputModes.available(ids.map { SchemaInfo(it, it, "", "", "") })
        assertEquals(1, modes.count { it.schemaId == InputModes.ENGLISH })
        assertFalse(modes.any { it.schemaId in setOf("melt_eng", "pinyin_simp", "jaroomaji", "t9") })
    }
    @Test fun marketOnlyAndUnknownSchemasKeepTheirIdsAndOrder() {
        val ids = listOf("my_personal", "pinyin_simp", "t9", "jaroomaji", "wanxiang")
        assertEquals(ids, CyimeInputDefaults.canonicalIds(ids, ids.toSet()))
    }
    @Test fun defaultChineseModesShareOneOfflineDictionaryFamily() {
        assertEquals(listOf("rime_ice", "t9_pinyin"), CyimeInputDefaults.recommended)
        assertFalse(CyimeInputDefaults.recommended.any { it in CyimeInputDefaults.dependencies })
    }
    @Test fun freshInstallAlsoShowsFourteenKeysButUpgradesKeepTheirList() {
        assertEquals(listOf("rime_ice", "double_pinyin_flypy", "t9_pinyin", "pinyin_14jian"),
            CyimeInputDefaults.firstDefaults(freshInstall = true))
        assertEquals(CyimeInputDefaults.recommended, CyimeInputDefaults.firstDefaults(freshInstall = false))
        // 关着的 14 键在「已安装的方案」里直接列出，不藏进高级。
        assertTrue("pinyin_14jian" in CyimeInputDefaults.listedWhenOff)
        assertTrue(CyimeInputDefaults.listedWhenOff.containsAll(CyimeInputDefaults.doublePinyin))
        // 形码、笔画、注音关着也直接列出，但不进默认启用。
        assertTrue(CyimeInputDefaults.listedWhenOff.containsAll(listOf("wubi86", "cyime_cangjie", "cyime_quick", "stroke", "cyime_zhuyin")))
        assertTrue(CyimeInputDefaults.firstDefaults(freshInstall = true).none { it in CyimeInputDefaults.shapeAndStroke })
        // 新装：14 键和双拼都开着时，各双拼的 14 键版本跟着出现（组合配置），不单独占一项。
        val available = setOf("rime_ice", "t9_pinyin", "pinyin_14jian", "double_pinyin_flypy", "double_pinyin_flypy_14jian")
        assertEquals(listOf("rime_ice", "double_pinyin_flypy", "t9_pinyin", "pinyin_14jian", "double_pinyin_flypy_14jian"),
            CyimeInputDefaults.withCompanions(listOf("rime_ice", "double_pinyin_flypy", "t9_pinyin", "pinyin_14jian"), available))
    }
    @Test fun builtinDoublePinyinGoesRightAfterFullPinyinWithoutMovingOtherChoices() {
        val dp = CyimeInputDefaults.doublePinyin
        assertEquals(listOf("double_pinyin_flypy", "double_pinyin_shoudao", "double_pinyin_ziranma",
            "double_pinyin_znabc", "double_pinyin_jiajia", "double_pinyin_microsoft"), dp)
        val available = setOf("rime_ice", "t9_pinyin", "japanese") + dp
        assertEquals(listOf("rime_ice") + dp + listOf("t9_pinyin", "japanese"),
            CyimeInputDefaults.withDoublePinyin(listOf("rime_ice", "t9_pinyin", "japanese"), available))
        // 已经开着小鹤的不重复、不挪位置；没有中文26键时排在最后。
        assertEquals(listOf("t9_pinyin", "double_pinyin_flypy") + dp.drop(1),
            CyimeInputDefaults.withDoublePinyin(listOf("t9_pinyin", "double_pinyin_flypy"), available))
        assertEquals(listOf("rime_ice") + dp + "t9_pinyin",
            CyimeInputDefaults.withDoublePinyin(listOf("rime_ice", "double_pinyin_flypy", "t9_pinyin"), available))
    }
    @Test fun freshInstallLeavesDoublePinyinToTheGuideQuestion() {
        // 新装、接着会出「按设备推荐配置」：那一页问用哪种双拼，只打开选中的；向导第 2 步不先列出 6 种。
        assertFalse(CyimeInputDefaults.addsDoublePinyinToBar(freshInstall = true, guideWillAsk = true))
        // 升级的人、老人包（没有那一页）照旧补进方案栏。
        assertTrue(CyimeInputDefaults.addsDoublePinyinToBar(freshInstall = false, guideWillAsk = true))
        assertTrue(CyimeInputDefaults.addsDoublePinyinToBar(freshInstall = true, guideWillAsk = false))
        // 关着的内置双拼在「已安装的方案」里直接列出，跳过引导的人也找得到。
        assertTrue(CyimeInputDefaults.listedWhenOff.containsAll(CyimeInputDefaults.doublePinyin))
    }
    @Test fun doublePinyinWaitsUntilItsSchemasAreGeneratedAndBringsFourteenKeyCompanions() {
        val dp = CyimeInputDefaults.doublePinyin
        assertNull(CyimeInputDefaults.withDoublePinyin(listOf("rime_ice"), setOf("rime_ice", "double_pinyin_flypy")))
        // 微软双拼没有 14 键版本（没有分号键），文件不存在就不补。
        val k14 = dp.filterNot { it == DoublePinyinScheme.MICROSOFT_ID }.map { DoublePinyinScheme.k14Id(it) }
        val available = setOf("rime_ice", "pinyin_14jian") + dp + k14
        assertEquals(listOf("rime_ice") + dp + listOf("pinyin_14jian") + k14,
            CyimeInputDefaults.withDoublePinyin(listOf("rime_ice", "pinyin_14jian"), available))
    }
}
