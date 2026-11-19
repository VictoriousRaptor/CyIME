package com.kingzcheung.xime.handwriting

import com.kingzcheung.xime.model.BuiltinModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 轻量版下载的手写模板要和安装包里的（assets/handwriting）逐字节相同；换了模板文件忘了重跑
 * scripts/packs/make_packs.py、改模型目录时这里失败。轻量版不打进包的文件和下载的文件也要是同一组。
 */
class HandwritingPackCatalogTest {
    private val assets = File("src/main/assets/handwriting")
    private val gradle = File("build.gradle.kts").readText()

    private fun files(id: String) =
        BuiltinModelCatalog.models.first { it.id == id }.resolvedVersion()!!.files

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun downloadedTemplatesAreTheAssetFiles() {
        for (id in listOf(BuiltinModelCatalog.HANDWRITING_RARE, BuiltinModelCatalog.JAPANESE_HANDWRITING)) {
            for (f in files(id)) {
                val asset = File(assets, f.name)
                assertTrue("$id ${f.name}", asset.isFile)
                assertEquals("$id ${f.name} size", asset.length(), f.sizeBytes)
                assertEquals("$id ${f.name} sha256", sha256(asset), f.sha256)
            }
        }
    }

    @Test fun lightBuildLeavesOutExactlyTheDownloadedTemplates() {
        val downloaded = (files(BuiltinModelCatalog.HANDWRITING_RARE) + files(BuiltinModelCatalog.JAPANESE_HANDWRITING))
            .map { it.name }.toSet()
        for (name in downloaded) assertTrue(name, gradle.contains("\"$name\""))
        // 贡献页的常用度表、英文字母模板留在包里。
        assertTrue(!gradle.contains("\"japanese_kanji.json\"") && !gradle.contains("\"latin_default_strokes.json\""))
    }
}
