package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** [RimeConfigHelper.missingBuildArtifact]：部署后哪个方案缺了什么，给「部署失败」说清原因。 */
class MissingBuildArtifactTest {
    private fun build(): File = File(System.getProperty("java.io.tmpdir"), "missing-artifact-${System.nanoTime()}").apply { mkdirs() }

    /** librime 编译出的方案（雾凇原包的搜狗双拼）：translator 下按字母序排，dictionary 在 comment_format 列表后面。 */
    private val compiledSogou = """
        |translator:
        |  always_show_comments: true
        |  comment_format:
        |    - "xform/^/［/"
        |    - "xform/$/］/"
        |  dictionary: rime_ice
        |  enable_word_completion: true
        |  prism: double_pinyin_sogou
        |schema:
        |  schema_id: double_pinyin_sogou
        |""".trimMargin()

    private fun table(dir: File, name: String) = File(dir, name).writeBytes("Rime::Table/4.0".toByteArray() + ByteArray(32))
    private fun prism(dir: File, name: String) = File(dir, name).writeBytes("Rime::Prism/4.0".toByteArray() + ByteArray(32))

    @Test
    fun `没编译出方案文件`() {
        val rime = build()
        val dir = File(rime, "build").apply { mkdirs() }
        File(rime, "double_pinyin_sogou.schema.yaml").writeText("schema:\n  schema_id: double_pinyin_sogou\n")
        assertEquals("没有编译出 double_pinyin_sogou.schema.yaml", RimeConfigHelper.missingBuildArtifact(dir, "double_pinyin_sogou"))
        rime.deleteRecursively()
    }

    @Test
    fun `方案文件本身不在时提示重新导入`() {
        val rime = build()
        val dir = File(rime, "build").apply { mkdirs() }
        assertEquals("这台设备上没有 wanxiang.schema.yaml，请重新导入这个方案", RimeConfigHelper.missingBuildArtifact(dir, "wanxiang"))
        rime.deleteRecursively()
    }

    @Test
    fun `方案编译了但共享词库的拼写索引缺了`() {
        val dir = build()
        File(dir, "double_pinyin_sogou.schema.yaml").writeText(compiledSogou)
        table(dir, "rime_ice.table.bin")
        assertEquals("缺少 double_pinyin_sogou.prism.bin", RimeConfigHelper.missingBuildArtifact(dir, "double_pinyin_sogou"))
        prism(dir, "double_pinyin_sogou.prism.bin")
        assertNull(RimeConfigHelper.missingBuildArtifact(dir, "double_pinyin_sogou"))
        dir.deleteRecursively()
    }

    @Test
    fun `词库半成品算缺`() {
        val dir = build()
        File(dir, "double_pinyin_sogou.schema.yaml").writeText(compiledSogou)
        File(dir, "rime_ice.table.bin").writeBytes(ByteArray(0))
        prism(dir, "double_pinyin_sogou.prism.bin")
        assertEquals("缺少 rime_ice.table.bin", RimeConfigHelper.missingBuildArtifact(dir, "double_pinyin_sogou"))
        dir.deleteRecursively()
    }

    @Test
    fun `没有词库的外来方案不算缺，内置中文方案算缺`() {
        val dir = build()
        File(dir, "numbers_ext.schema.yaml").writeText("schema:\n  schema_id: numbers_ext\n")
        assertNull(RimeConfigHelper.missingBuildArtifact(dir, "numbers_ext"))
        File(dir, "rime_ice.schema.yaml").writeText("schema:\n  schema_id: rime_ice\n")
        assertEquals("方案里没有 translator", RimeConfigHelper.missingBuildArtifact(dir, "rime_ice"))
        dir.deleteRecursively()
    }
}
