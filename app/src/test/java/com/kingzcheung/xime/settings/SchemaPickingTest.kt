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

    /** 用户 11-23：关了小鹤双拼键盘里还显示；多出用不到的五笔、仓颉。关着的方案（随包的也一样）不列。 */
    @Test fun onlyEnabledSchemesAreListed() {
        val ids = SchemaPicking.offered(installed, enabled).map { it.schemaId }
        assertEquals(enabled.sorted(), ids.sorted())
        val turnedOff = SchemaPicking.offered(installed, enabled - "double_pinyin_flypy").map { it.schemaId }
        assertFalse("关掉的小鹤双拼不该再列", "double_pinyin_flypy" in turnedOff)
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
    }

    /** 用户 11-23：九键下面列出五笔，点了打不了字。方案一行只列这个布局上真能打字的。 */
    @Test fun schemeRowOnlyListsSchemesTypableOnTheCurrentLayout() {
        val offered = SchemaPicking.offered(installed, installed.map { it.schemaId } - "my_import")
        // 26 键：拼音、双拼、五笔、仓颉都能打；注音（大千）、笔画（笔画键盘）、九键拼音不在这一行，从布局一行进。
        val row = SchemaPicking.schemeRow(offered, info("rime_ice")).map { it.schemaId }
        assertTrue(row.toString(), row.containsAll(listOf("rime_ice", "double_pinyin_flypy", "wubi86", "cyime_cangjie", "cyime_quick")))
        assertTrue(row.toString(), row.none { it in listOf("cyime_zhuyin", "stroke", "t9_pinyin") })
        assertEquals(listOf("t9_pinyin"), SchemaPicking.schemeRow(offered, info("t9_pinyin")).map { it.schemaId })
        assertEquals(listOf("cyime_zhuyin"), SchemaPicking.schemeRow(offered, info("cyime_zhuyin")).map { it.schemaId })
        assertEquals(listOf("stroke"), SchemaPicking.schemeRow(offered, info("stroke")).map { it.schemaId })
        // 每一张卡片都能在当前布局上打：卡片对应的布局里有当前布局。
        for (current in offered) {
            val layoutId = current.profile.layout.id
            for (entry in SchemaPicking.schemeRow(offered, current))
                assertTrue("${current.schemaId} 的方案行里 ${entry.schemaId} 不能在 $layoutId 上打",
                    entry.schemaId == current.schemaId || InputProfileSelection.offers(entry, layoutId, offered))
        }
    }

    /** 平板 11-23：新打开的五笔86、仓颉速成插到导入的小鹤音形前面。新打开的排在已打开的最后，别的先后不动。 */
    @Test fun newlyEnabledSchemesGoAfterTheOnesAlreadyOn() {
        val full = listOf("rime_ice", "wubi86", "t9_pinyin", "cyime_quick", "flypy", "stroke")
        val before = listOf("rime_ice", "t9_pinyin", "flypy")
        assertEquals(listOf("rime_ice", "t9_pinyin", "flypy", "wubi86", "cyime_quick", "stroke"),
            InputModes.orderWithNewLast(InputModes.orderWithNewLast(full, before, listOf("wubi86")),
                before + "wubi86", listOf("cyime_quick")))
        assertEquals("已经打开的不动", full, InputModes.orderWithNewLast(full, before, listOf("flypy")))
    }
}
