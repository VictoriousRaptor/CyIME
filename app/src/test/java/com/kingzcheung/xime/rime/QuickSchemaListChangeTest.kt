package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 11-23 平板：开关一个方案要部署 19–21 秒，期间所有方案都打不了字。只启停了方案时走快路，
 * 只重编方案列表和新开的方案（[RimeConfigHelper.schemaListChange] 判断能不能走）。
 */
class QuickSchemaListChangeTest {
    private val shared = RimeConfigHelper.SHARED_PART
    private val before = mapOf(shared to "s1", "t9_pinyin" to "a", "rime_ice" to "b", "melt_eng" to "c")
    private val none: (String) -> List<String> = { emptyList() }
    private val nothingCompiled: (String) -> Boolean = { false }

    @Test fun turningOneSchemeOnOrOffIsAListChange() {
        val on = before + ("double_pinyin_lxsq17" to "d")
        assertEquals(RimeConfigHelper.SchemaListChange(listOf("double_pinyin_lxsq17"), emptyList()),
            RimeConfigHelper.schemaListChange(before, on, none, nothingCompiled))
        assertEquals(RimeConfigHelper.SchemaListChange(emptyList(), listOf("rime_ice")),
            RimeConfigHelper.schemaListChange(before, before - "rime_ice", none, nothingCompiled))
    }

    @Test fun anyOtherChangeGoesTheOldWay() {
        val on = before + ("double_pinyin_lxsq17" to "d")
        // 词典、default 里的别的设置变了
        assertNull(RimeConfigHelper.schemaListChange(before, on + (shared to "s2"), none, nothingCompiled))
        // 留着的方案自己的文件变了（比如模糊音补丁）
        assertNull(RimeConfigHelper.schemaListChange(before, on + ("t9_pinyin" to "a2"), none, nothingCompiled))
        // 什么都没启停
        assertNull(RimeConfigHelper.schemaListChange(before, before, none, nothingCompiled))
        // 以前没存过
        assertNull(RimeConfigHelper.schemaListChange(emptyMap(), on, none, nothingCompiled))
    }

    @Test fun newSchemeNeedsItsDependenciesCompiled() {
        val on = before + ("double_pinyin_lxsq17" to "d")
        val deps: (String) -> List<String> = { if (it == "double_pinyin_lxsq17") listOf("melt_eng", "radical_pinyin") else emptyList() }
        assertNull(RimeConfigHelper.schemaListChange(before, on, deps, nothingCompiled))
        assertEquals(listOf("double_pinyin_lxsq17"),
            RimeConfigHelper.schemaListChange(before, on, deps) { it == "radical_pinyin" }?.added)
    }

    @Test fun schemaListIsIgnoredButOtherDefaultSettingsCount() {
        val block = "patch:\n  menu:\n    page_size: 7\n  schema_list:\n    - schema: rime_ice\n    - schema: t9_pinyin\n  switcher:\n    caption: x\n"
        val other = "patch:\n  menu:\n    page_size: 7\n  schema_list:\n    - schema: t9_pinyin\n  switcher:\n    caption: x\n"
        assertEquals(RimeConfigHelper.withoutSchemaList(block), RimeConfigHelper.withoutSchemaList(other))
        assertEquals("patch:\n  menu:\n    page_size: 7\n  switcher:\n    caption: x\n", RimeConfigHelper.withoutSchemaList(block))
        assertEquals("{menu: {page_size: 5}}", RimeConfigHelper.withoutSchemaList("{schema_list: [a, b], menu: {page_size: 5}}"))
        // 列表项和键同一缩进的写法
        assertEquals("schema_list_x: 1\nmenu: 1", RimeConfigHelper.withoutSchemaList("schema_list:\n- schema: a\n- schema: b\nschema_list_x: 1\nmenu: 1"))
        assertEquals(RimeConfigHelper.withoutSchemaList(block.replace("page_size: 7", "page_size: 9")) == RimeConfigHelper.withoutSchemaList(block), false)
    }

    @Test fun dependenciesInBothForms() {
        assertEquals(listOf("melt_eng", "radical_pinyin"),
            RimeConfigHelper.schemaDependencies("schema:\n  schema_id: x\n  dependencies:\n    - melt_eng\n    - radical_pinyin\n\nswitches:\n  - name: a\n"))
        assertEquals(listOf("a", "b"), RimeConfigHelper.schemaDependencies("schema:\n  dependencies: [a, \"b\"]\n"))
        assertEquals(emptyList<String>(), RimeConfigHelper.schemaDependencies("schema:\n  schema_id: x\n"))
    }
}
