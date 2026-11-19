package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [SchemaManifestManager.removeBuildArtifacts]：卸载导入方案后 build 里它的编译产物一起删，别人的不动。 */
class RemoveBuildArtifactsTest {
    private fun rime(): File = File(System.getProperty("java.io.tmpdir"), "build-cleanup-${System.nanoTime()}").apply {
        File(this, "build").mkdirs()
    }

    @Test
    fun `卸载后删掉方案和词库的编译产物`() {
        val rime = rime()
        val build = File(rime, "build")
        listOf("wanxiang.schema.yaml", "wanxiang.prism.bin", "wanxiang.table.bin", "wanxiang.reverse.bin",
            "wanxiang_reverse.schema.yaml", "rime_ice.schema.yaml", "rime_ice.table.bin").forEach { File(build, it).writeText("x") }
        File(rime, "rime_ice.schema.yaml").writeText("x")
        val removed = SchemaManifestManager.removeBuildArtifacts(rime,
            listOf("wanxiang.schema.yaml", "dicts/wanxiang.dict.yaml", "wanxiang_reverse.schema.yaml", "lua/wanxiang.lua"))
        assertEquals(5, removed)
        assertFalse(File(build, "wanxiang.table.bin").exists())
        assertFalse(File(build, "wanxiang_reverse.schema.yaml").exists())
        assertTrue(File(build, "rime_ice.schema.yaml").exists())
        assertTrue(File(build, "rime_ice.table.bin").exists())
        rime.deleteRecursively()
    }

    @Test
    fun `同名源文件还在时不删`() {
        val rime = rime()
        val build = File(rime, "build")
        File(build, "shared.table.bin").writeText("x")
        File(rime, "shared.dict.yaml").writeText("x")
        assertEquals(0, SchemaManifestManager.removeBuildArtifacts(rime, listOf("shared.schema.yaml", "my.custom.yaml")))
        assertTrue(File(build, "shared.table.bin").exists())
        rime.deleteRecursively()
    }
}
