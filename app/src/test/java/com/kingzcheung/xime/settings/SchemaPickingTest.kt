package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 用户 11-22：「语言与方案」里有仓颉、注音，键盘的方案面板里没有。两边必须列同一份方案。 */
class SchemaPickingTest {
    private fun info(id: String) = SchemaInfo(id, id, "", "", "")
    private val enabled = listOf("rime_ice", "t9_pinyin", "double_pinyin_flypy")
    private val installed = (enabled + listOf("wubi86", "cyime_cangjie", "cyime_quick", "cyime_zhuyin", "stroke", "my_import"))
        .map(::info)

    @Before fun builtInLayouts() {
        KeysConfigHelper.clearSchemaBindingsForTest()
        InputProfiles.updateInstalled(emptyList())
    }

    @Test fun bundledSchemesAreListedWhileOffButOtherImportsAreNot() {
        val ids = SchemaPicking.offered(installed, enabled).map { it.schemaId }
        assertTrue(ids.containsAll(listOf("wubi86", "cyime_cangjie", "cyime_quick", "cyime_zhuyin", "stroke")))
        assertFalse("关着的导入方案仍只在「已安装的方案」里", "my_import" in ids)
    }

    @Test fun keyboardPanelAndSettingsShowTheSameSchemeRow() {
        // 设置页：装着的全部方案 + 启用列表。键盘：服务给的启用方案，另读装着的和启用列表。
        val settings = SchemaPicking.offered(installed, enabled)
        val service = installed.filter { it.schemaId in enabled }
        val keyboard = SchemaPicking.offered((service + installed).distinctBy { it.schemaId }, enabled + service.map { it.schemaId })
        for (current in listOf("rime_ice", "t9_pinyin", "double_pinyin_flypy")) {
            assertEquals(current, SchemaPicking.schemeRow(settings, info(current)).map { it.schemaId },
                SchemaPicking.schemeRow(keyboard, info(current)).map { it.schemaId })
        }
        // 26 键上也列出别的布局的方案（注音用大千、笔画用笔画键盘），和设置页一样。
        val row = SchemaPicking.schemeRow(settings, info("rime_ice")).map { it.schemaId }
        assertTrue(row.toString(), row.containsAll(listOf("rime_ice", "double_pinyin_flypy", "wubi86", "cyime_cangjie", "cyime_zhuyin", "stroke")))
    }
}
