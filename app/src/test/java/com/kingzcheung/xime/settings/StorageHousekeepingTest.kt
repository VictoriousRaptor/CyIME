package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageHousekeepingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test fun `只认出中断留下的临时文件`() {
        val cache = tmp.newFolder("cache")
        val models = tmp.newFolder("models")
        val rime = tmp.newFolder("rime")
        val stale = listOf(
            File(cache, "model-download-$id").apply { mkdirs(); File(this, "encoder.onnx").writeText("partial") },
            File(cache, "model-import-$id").apply { mkdirs() },
            File(cache, "schema-install-$id").apply { mkdirs() },
            File(cache, "-1234567.tar.bz2").apply { writeText("archive") },
            File(cache, "ocr-frames/$id.png").apply { parentFile!!.mkdirs(); writeText("png") },
            File(models, ".t9-ngram-$id.previous").apply { mkdirs() },
            File(models, ".download-t9-ngram-small-$id").apply { mkdirs(); File(this, "t9_ngram.lm").writeText("partial") },
            File(models, ".import-ochwpro-$id").apply { mkdirs() },
            File(models, ".bundle-paraformer-zh-en-int8-$id").apply { mkdirs() },
            // 不再使用的智能联想模型
            File(models, "predictive-text-base").apply { mkdirs(); File(this, "model_int8_dynamic.onnx").writeText("old") },
            File(models, "predictive-text-small").apply { mkdirs() },
            File(rime, ".t9_ngram.lm.$id.installing").apply { writeText("link") },
        )
        val kept = listOf(
            File(cache, "coil_cache").apply { mkdirs() },
            File(cache, "clipboard_images").apply { mkdirs() },
            File(cache, "model-download-notauuid").apply { mkdirs() },
            File(models, "t9-ngram").apply { mkdirs() },
            File(models, "download-t9-ngram-$id").apply { mkdirs() },
            File(rime, "t9_ngram.lm").apply { writeText("model") },
            File(rime, "t9_pinyin.schema.yaml.installing").apply { writeText("schema") },
        )
        val found = StorageHousekeeping.findLeftovers(cache, models, rime).toSet()
        assertEquals(stale.toSet(), found)
        assertTrue(kept.none { it in found })
    }

    @Test fun `删除残留并报告腾出的空间`() {
        val cache = tmp.newFolder("cache")
        val partial = File(cache, "model-download-$id").apply { mkdirs(); File(this, "a.onnx").writeText("12345") }
        val freed = StorageHousekeeping.removeLeftovers(listOf(partial))
        assertEquals(5L, freed)
        assertFalse(partial.exists())
    }

    @Test fun `替换中途被杀时把挪开的模型放回原处`() {
        val models = tmp.newFolder("models")
        val previous = File(models, ".paraformer-zh-en-int8-$id.previous").apply {
            mkdirs(); File(this, "model.onnx").writeText("weights")
        }
        StorageHousekeeping.removeLeftovers(listOf(previous))
        assertFalse(previous.exists())
        assertEquals("weights", File(models, "paraformer-zh-en-int8/model.onnx").readText())
    }

    @Test fun `新模型已装好时删掉挪开的旧模型`() {
        val models = tmp.newFolder("models")
        File(models, "paraformer-zh-en-int8").apply { mkdirs(); File(this, "model.onnx").writeText("new") }
        val previous = File(models, ".paraformer-zh-en-int8-$id.previous").apply {
            mkdirs(); File(this, "model.onnx").writeText("old")
        }
        StorageHousekeeping.removeLeftovers(listOf(previous))
        assertFalse(previous.exists())
        assertEquals("new", File(models, "paraformer-zh-en-int8/model.onnx").readText())
    }

    @Test fun `过期缓存：发出去一天的图片副本和三天没动的临时文件，自己管大小的不碰`() {
        val cache = tmp.newFolder("cache")
        val now = 30L * 24 * 60 * 60 * 1000
        fun File.age(hours: Long) = apply { setLastModified(now - hours * 60 * 60 * 1000) }
        val oldCopy = File(cache, "emoji_cache/a.png").apply { parentFile!!.mkdirs(); writeText("png") }.age(25)
        val freshCopy = File(cache, "emoji_cache/b.png").apply { writeText("png") }.age(2)
        val oldExport = File(cache, "dict_export_userdb.txt").apply { writeText("x") }.age(80)
        val oldTestDir = File(cache, "learning-test-1").apply { mkdirs() }
        File(oldTestDir, "a.json").apply { writeText("{}") }.age(100)
        oldTestDir.age(100)
        val busyDir = File(cache, "schema-import-busy").apply { mkdirs() }
        File(busyDir, "part").apply { writeText("x") }.age(1)
        busyDir.age(100)
        val freshTemp = File(cache, "market-1.xipk").apply { writeText("x") }.age(5)
        val managed = listOf("coil_cache", "clipboard_images", "updates", "stats_share", "WebView").map {
            File(cache, it).apply { mkdirs(); File(this, "f").apply { writeText("x") }.age(500) }.age(500)
        }
        val found = StorageHousekeeping.findStaleCache(cache, now).toSet()
        assertEquals(setOf(oldCopy, oldExport, oldTestDir), found)
        assertTrue((managed + freshCopy + busyDir + freshTemp).none { it in found })
    }

    @Test fun `改回缓存组只走数据目录，跳过缓存目录`() {
        val data = tmp.newFolder("data")
        val cache = File(data, "cache").apply { mkdirs(); File(this, "c.bin").writeText("cache") }
        val model = File(data, "files/models/sv/model.onnx").apply { parentFile!!.mkdirs(); writeText("12345") }
        val visited = mutableListOf<String>()
        val bytes = StorageHousekeeping.regroup(data.toPath(), setOf(cache.toPath())) { path ->
            visited += data.toPath().relativize(path).toString().replace('\\', '/')
            if (path == model.toPath()) 5L else -1L
        }
        assertEquals(5L, bytes)
        assertTrue("files/models/sv/model.onnx" in visited)
        assertTrue(visited.none { it.startsWith("cache") })
    }

    @Test fun `缓存目录不大时不写明细`() {
        val cache = tmp.newFolder("cache")
        File(cache, "small").writeText("x")
        assertEquals(null, StorageHousekeeping.cacheBreakdown(cache))
    }
}
