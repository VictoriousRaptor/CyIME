package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DoublePinyinOldIndexTest {
    @get:Rule val temp = TemporaryFolder()

    private fun schema(dir: File, id: String, name: String) = File(dir, "$id.schema.yaml").apply {
        writeText("schema:\n  schema_id: $id\n  name: $name\n  version: '1'\ntranslator:\n  prism: $id\n")
    }

    @Test
    fun oldDoublePinyinCorrectionIndexesAreRemovedWithTheirBuildFiles() {
        val rime = temp.newFolder("rime")
        val build = File(rime, "build").apply { mkdirs() }
        val old = schema(rime, "cyime_edit_double_pinyin_mspy", "双拼纠错索引")
        val oldBuilt = File(build, "cyime_edit_double_pinyin_mspy.prism.bin").apply { writeText("x") }
        val oldCompiled = File(build, "cyime_edit_double_pinyin_mspy.schema.yaml").apply { writeText("x") }

        DoublePinyinSchemes.retireOldCorrectionIndexes(rime)

        assertFalse(old.exists())
        assertFalse(oldBuilt.exists())
        assertFalse(oldCompiled.exists())
    }

    @Test
    fun keepsCurrentIndexesAndFilesThatOnlyShareThePrefix() {
        val rime = temp.newFolder("rime")
        val build = File(rime, "build").apply { mkdirs() }
        // 用户自己的方案碰巧也叫 cyime_edit_ 开头，名字不同：不动。
        val own = schema(rime, "cyime_edit_mine", "我的方案")
        val pause = schema(rime, "cyime_pause_double_pinyin_mspy", "双拼停顿纠错索引")
        val ownBuilt = File(build, "cyime_edit_mine.prism.bin").apply { writeText("x") }

        DoublePinyinSchemes.retireOldCorrectionIndexes(rime)

        assertTrue(own.exists())
        assertTrue(pause.exists())
        assertTrue(ownBuilt.exists())
        assertEquals(2, rime.listFiles { f -> f.name.endsWith(".schema.yaml") }!!.size)
    }

    /** 手动启用过的旧索引：文件删掉后从启用列表去掉；还在的同前缀方案和别的方案不动。 */
    @Test
    fun enabledOldIndexesWhoseFilesAreGoneAreDropped() {
        val rime = temp.newFolder("rime")
        schema(rime, "cyime_edit_double_pinyin_flypy", "双拼纠错索引")
        schema(rime, "cyime_edit_mine", "我的方案")
        DoublePinyinSchemes.retireOldCorrectionIndexes(rime)
        val enabled = listOf("rime_ice", "cyime_edit_double_pinyin_flypy", "cyime_edit_double_pinyin_mspy", "cyime_edit_mine")
        assertEquals(setOf("cyime_edit_double_pinyin_flypy", "cyime_edit_double_pinyin_mspy"),
            DoublePinyinSchemes.staleOldIndexIds(enabled, rime))
    }

    @Test
    fun missingRimeDirectoryIsFine() {
        DoublePinyinSchemes.retireOldCorrectionIndexes(File(temp.root, "absent"))
    }
}
