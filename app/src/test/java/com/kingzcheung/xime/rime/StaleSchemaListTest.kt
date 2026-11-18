package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-15 首道双拼一直「不在部署结果的方案列表里」：编译过了，编译出的方案列表却没有它。
 * [RimeConfigHelper.compiledSchemaList] 读 librime 写出的 build/default.yaml，[RimeConfigHelper.missingFromCompiledList]
 * 判断要不要删掉列表重编。
 */
class StaleSchemaListTest {
    /** librime 写出的 build/default.yaml（结构照 librime 1.10 实际输出）。 */
    private val compiled = """
        |__build_info:
        |  rime_version: 1.10.0
        |  timestamps:
        |    default: 1794755978
        |    default.custom: 1794755978
        |config_version: 1
        |menu:
        |  page_size: 20
        |schema_list:
        |  - schema: rime_ice
        |  - schema: double_pinyin_flypy
        |    case: [mode/flypy]
        |  - {schema: t9_pinyin}
        |  - schema: "pinyin_14jian"
        |switcher:
        |  caption: x
        |""".trimMargin()

    @Test
    fun `读出编译出的方案列表`() {
        assertEquals(listOf("rime_ice", "double_pinyin_flypy", "t9_pinyin", "pinyin_14jian"),
            RimeConfigHelper.compiledSchemaList(compiled))
    }

    @Test
    fun `没有列表时不判`() {
        assertNull(RimeConfigHelper.compiledSchemaList("config_version: 1\n"))
        assertEquals(emptyList<String>(), RimeConfigHelper.missingFromCompiledList(listOf("a"), null) { true })
    }

    @Test
    fun `编译过却不在列表里的才算列表旧了`() {
        val list = RimeConfigHelper.compiledSchemaList(compiled)
        val enabled = listOf("rime_ice", "double_pinyin_shoudao", "double_pinyin_shoudao_14jian", "brand_new")
        val built = setOf("rime_ice", "double_pinyin_shoudao", "double_pinyin_shoudao_14jian")
        assertEquals(listOf("double_pinyin_shoudao", "double_pinyin_shoudao_14jian"),
            RimeConfigHelper.missingFromCompiledList(enabled, list) { it in built })
        // 刚打开、还没编译过的交给增量部署，不为它整个重来。
        assertTrue(RimeConfigHelper.missingFromCompiledList(listOf("brand_new"), list) { it in built }.isEmpty())
    }
}
