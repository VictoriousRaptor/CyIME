package com.kingzcheung.xime.ui.menubar

import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.InputProfileSelection
import com.kingzcheung.xime.settings.InputProfiles
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.LayoutKind
import com.kingzcheung.xime.settings.SchemaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 布局面板点一下布局就切换，不用再点方案；布局没有当前方案时照样切换，但要标出来并提示。 */
class SchemaForLayoutTest {
    private val entries = listOf("rime_ice", "double_pinyin_flypy", "double_pinyin_flypy_14jian", "pinyin_14jian", "t9_pinyin")
        .map { SchemaInfo(it, it, "", "", "") }
    private fun entry(id: String) = (entries + extra).first { it.schemaId == id }
    private val extra = listOf("double_pinyin_mspy", "wubi86").map { SchemaInfo(it, it, "", "", "") }

    /** 布局按内置方案描述判断；前面的测试类加载过的布局绑定和方案元数据不能带进来。 */
    @Before fun builtInLayouts() {
        KeysConfigHelper.clearSchemaBindingsForTest()
        InputProfiles.updateInstalled(emptyList())
    }

    private fun switch(id: String, layoutId: String, list: List<SchemaInfo> = entries, all: List<SchemaInfo> = list) =
        InputProfileSelection.switchLayout(list, entry(id), layoutId, all)

    @Test fun keepsCurrentSchemeWhenTheLayoutHasIt() {
        assertEquals("pinyin_14jian", switch("rime_ice", "qwerty_14")?.entry?.schemaId)
        assertEquals(true, switch("rime_ice", "qwerty_14")?.keepsScheme)
        // 小鹤双拼的 14 键条目是同一方案。
        assertEquals("double_pinyin_flypy_14jian", switch("double_pinyin_flypy", "qwerty_14")?.entry?.schemaId)
        assertEquals(true, switch("double_pinyin_flypy", "qwerty_14")?.keepsScheme)
        assertEquals("double_pinyin_flypy", switch("double_pinyin_flypy_14jian", InputLayout.QWERTY.id)?.entry?.schemaId)
    }

    /** 用户反馈：先选小鹤双拼再选九键，九键只有拼音，不能悄悄换。 */
    @Test fun layoutWithoutTheSchemeSwitchesButSaysSo() {
        val result = switch("double_pinyin_flypy", "t9")!!
        assertEquals("t9_pinyin", result.entry.schemaId)
        assertFalse(result.keepsScheme)
        assertEquals("九键没有double_pinyin_flypy，已换成拼音",
            InputProfileSelection.switchHint(InputLayout.T9, entry("double_pinyin_flypy"), result.entry))
    }

    /** #160：自建合并键布局没有导入的方案时说明原因，别的布局不加。 */
    @Test fun mergedCustomLayoutSaysWhichSchemesItHas() {
        val layout = InputLayout("custom_pinyin_0123456789abcdef0123456789abcdef", "我的布局", LayoutKind.MERGED)
        val imported = SchemaInfo("wanxiang", "万象拼音", "", "", "")
        assertEquals("我的布局没有万象拼音，已换成double_pinyin_flypy\n${InputProfileSelection.MERGED_LAYOUT_NOTE}",
            InputProfileSelection.switchHint(layout, imported, entry("double_pinyin_flypy"), mergedCustom = true))
        assertFalse(InputProfileSelection.switchHint(InputLayout.T9, imported, entry("t9_pinyin"), mergedCustom = false)
            .contains(InputProfileSelection.MERGED_LAYOUT_NOTE))
    }

    /** 微软双拼换到 14 键只有小鹤双拼 14 键：编码同类但变体不同，也要提示。 */
    @Test fun otherDoublePinyinVariantIsNotTheSameScheme() {
        val list = entries + extra
        val result = InputProfileSelection.switchLayout(list, entry("double_pinyin_mspy"), "qwerty_14")!!
        assertEquals("double_pinyin_flypy_14jian", result.entry.schemaId)
        assertFalse(result.keepsScheme)
        assertFalse(InputProfileSelection.switchLayout(list, entry("wubi86"), "t9")!!.keepsScheme)
    }

    @Test fun languageLayoutsListEveryLayoutOfTheLanguage() {
        val layouts = InputProfileSelection.languageLayouts(entries, entry("double_pinyin_flypy")).map { it.id }
        assertTrue("t9" in layouts && "qwerty_14" in layouts && InputLayout.QWERTY.id in layouts)
    }

    /** English and Japanese romaji stay on their own entry when a Chinese letter layout is tapped. */
    @Test fun letterLayoutKeepsEnglishAndRomajiEntries() {
        val all = entries + listOf("pinyin_cyletix10", "pinyin_colemak", "japanese").map { SchemaInfo(it, it, "", "", "") } +
            InputModes.english
        val english = InputProfileSelection.switchLayout(listOf(InputModes.english), InputModes.english, "pinyin_cyletix10", all)
        assertEquals(InputModes.ENGLISH, english?.entry?.schemaId)
        assertEquals(true, english?.keepsScheme)
        val japanese = all.first { it.schemaId == "japanese" }
        assertEquals("japanese", InputProfileSelection.switchLayout(listOf(japanese), japanese, "pinyin_colemak", all)?.entry?.schemaId)
        val chinese = all.filter { it.language == InputLanguage.CHINESE }
        assertEquals("pinyin_cyletix10",
            InputProfileSelection.switchLayout(chinese, all.first { it.schemaId == "rime_ice" }, "pinyin_cyletix10", all)?.entry?.schemaId)
    }

    /** 用户 10-31：假名九键跟中文九键一样排在 26 键后面；Colemak 这类借来的字母排列排最后。 */
    @Test fun layoutRowPutsEveryLanguagesOwnLayoutsBeforeLetterArrangements() {
        val rare = listOf("pinyin_cyletix10", "pinyin_colemak").map { SchemaInfo(it, it, "", "", "") }
        val japanese = listOf("japanese", "japanese_kana").map { SchemaInfo(it, it, "", "", "") }
        val all = InputModes.available(entries + japanese + rare, InputModes.defaultModeOrder)
        fun row(language: InputLanguage) =
            InputProfileSelection.layoutRow(all.filter { it.language == language }, all).map { it.id }
        assertEquals(listOf(InputLayout.QWERTY.id, InputLayout.T9.id, InputLayout.MERGED14.id, "pinyin_cyletix10", "pinyin_colemak"),
            row(InputLanguage.CHINESE))
        assertEquals(listOf(InputLayout.QWERTY.id, InputLayout.KANA.id, "pinyin_cyletix10", "pinyin_colemak"),
            row(InputLanguage.JAPANESE))
        assertEquals(listOf(InputLayout.QWERTY.id, "pinyin_cyletix10", "pinyin_colemak"), row(InputLanguage.ENGLISH))
        assertEquals(row(InputLanguage.JAPANESE), InputProfileSelection.languageLayouts(all, all.first { it.schemaId == "japanese" }).map { it.id })
        // 自己排过的顺序照旧：Colemak 拖到最前，布局栏也在最前。
        val saved = InputModes.available(entries + rare, listOf("pinyin_colemak") + entries.map { it.schemaId })
        assertEquals("pinyin_colemak",
            InputProfileSelection.layoutRow(saved.filter { it.language == InputLanguage.CHINESE }, saved).first().id)
    }

    @Test fun unknownLayoutSwitchesNothing() {
        assertNull(switch("rime_ice", "japanese_kana"))
    }
}
