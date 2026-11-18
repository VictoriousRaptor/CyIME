package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「已安装的方案」每个开着的方案那一行写的应用结果（11-15：生效了要看得出来，没生效写原因，不弹框）。 */
class SchemaApplyStatusTest {
    @Test
    fun `每一行的应用结果`() {
        val deployed = setOf("rime_ice")
        val problems = mapOf("double_pinyin_shoudao" to "部署结果里没有它")
        assertNull(SchemaApplyStatus.of("x", enabled = false, applying = false, deployed, problems))
        assertEquals(SchemaApplyStatus.Applied, SchemaApplyStatus.of("rime_ice", true, applying = false, deployed, problems))
        assertEquals(SchemaApplyStatus.Applied, SchemaApplyStatus.of("rime_ice", true, applying = true, deployed, problems))
        assertEquals(SchemaApplyStatus.Applying, SchemaApplyStatus.of("double_pinyin_shoudao", true, applying = true, deployed, problems))
        val failed = SchemaApplyStatus.of("double_pinyin_shoudao", true, applying = false, deployed, problems)!!
        assertEquals("没生效：部署结果里没有它", failed.text)
        assertTrue(failed.isError)
        assertEquals(SchemaApplyStatus.Pending, SchemaApplyStatus.of("other", true, applying = false, deployed, problems))
        assertEquals(SchemaApplyStatus.Pending, SchemaApplyStatus.of("other", true, applying = false, null, problems))
    }
}
