package com.kingzcheung.xime.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NgramVariantTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model(id: String) = ModelInfo(id, id, "", ModelCategory.CANDIDATE,
        versions = listOf(ModelVersion("v1", files = listOf(ModelFile("t9_ngram.lm", "", "", 0L)))))
    private val full = model("t9-ngram")
    private val small = model("t9-ngram-small")

    private fun stored(name: String, content: String) = temporary.newFolder(name).apply {
        File(this, "t9_ngram.lm").writeText(content)
    }

    @Test fun runtimeLinkIdentifiesTheVariantInUse() {
        val fullDir = stored("full", "full model")
        val smallDir = stored("small", "small model")
        val runtime = temporary.newFolder("rime")
        Files.createLink(File(runtime, "t9_ngram.lm").toPath(), File(smallDir, "t9_ngram.lm").toPath())
        assertEquals(small, activeVariant(runtime, listOf(full to fullDir, small to smallDir)))
    }

    @Test fun copiedOrMissingRuntimeFileIsNotAttributed() {
        val fullDir = stored("full", "full model")
        val smallDir = stored("small", "small model")
        val runtime = temporary.newFolder("rime")
        assertNull(activeVariant(runtime, listOf(full to fullDir, small to smallDir)))
        // 拷贝安装的内容即使相同也无法确认归属，不能据此删掉另一档。
        File(fullDir, "t9_ngram.lm").copyTo(File(runtime, "t9_ngram.lm"))
        assertNull(activeVariant(runtime, listOf(full to fullDir, small to smallDir)))
    }

    @Test fun bundledCopyIsRecognizedOnlyWhenSizeAndHashMatch() {
        val content = "bundled model"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val manifest = "$hash\t${content.length}\trime_chinese/t9_ngram.lm\tt9_ngram.lm\n"
        val runtime = temporary.newFolder("rime")
        val file = File(runtime, "t9_ngram.lm").apply { writeText(content) }
        assertTrue(isBundledCopy(manifest, "t9_ngram.lm", file))
        file.writeText("bundled modeL") // 同样大小、内容不同：用户自己的文件，不能当成自带的换掉
        assertFalse(isBundledCopy(manifest, "t9_ngram.lm", file))
        assertFalse(isBundledCopy(manifest, "other.lm", file))
    }

    @Test fun earlierBundledCopyIsReplacedButCustomFileIsKept() {
        val bundled = "earlier bundled model"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bundled.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val known = mapOf("t9_ngram.lm" to listOf(hash to bundled.length.toLong()))
        val runtime = temporary.newFolder("rime")
        val file = File(runtime, "t9_ngram.lm").apply { writeText(bundled) }
        assertTrue(isEarlierBundledCopy("t9_ngram.lm", file, known))
        assertFalse(isEarlierBundledCopy("other.lm", file, known))
        file.writeText("earlier bundled modeL") // 同样大小、内容不同：用户自己放的，不能换掉
        assertFalse(isEarlierBundledCopy("t9_ngram.lm", file, known))
        assertFalse(isEarlierBundledCopy("t9_ngram.lm", File(runtime, "missing.lm"), known))
    }

    @Test fun downloadReplacesTheEarlierBundledModelOnly() {
        val bundled = "earlier bundled model"
        val digest = { text: String -> java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) } }
        val known = mapOf("t9_ngram.lm" to listOf(digest(bundled) to bundled.length.toLong()))
        val downloaded = "downloaded model"
        val version = ModelVersion("v1", files = listOf(
            ModelFile("t9_ngram.lm", "", digest(downloaded), downloaded.length.toLong())))
        val source = stored("download", downloaded)
        val runtime = temporary.newFolder("rime")
        val target = File(runtime, "t9_ngram.lm").apply { writeText(bundled) }
        assertTrue(installCandidateModel(source, runtime, version) { name, file -> isEarlierBundledCopy(name, file, known) })
        assertEquals(downloaded, target.readText())
        target.delete(); target.writeText("my own model")
        val kept = runCatching {
            installCandidateModel(source, runtime, version) { name, file -> isEarlierBundledCopy(name, file, known) }
        }
        assertTrue(kept.isFailure)
        assertEquals("my own model", target.readText())
    }

    @Test fun failureTextTellsWhatToDoNext() {
        assertEquals("连不上下载服务器，请检查网络后点重试",
            modelFailureText(java.io.IOException("x", java.net.UnknownHostException("github.com"))))
        assertEquals("下载的文件不完整，请点重试重新下载", modelFailureText(java.io.IOException("模型校验失败：a.onnx")))
        assertEquals("存储空间不够，清理一些空间后点重试", modelFailureText(java.io.IOException("write failed: ENOSPC")))
        assertEquals("输入法目录里已有一份同名的自定义文件，为免覆盖没有安装；移走它后点重试",
            modelFailureText(java.io.IOException("保留已有不同版本的 t9_ngram.lm，未覆盖", java.io.IOException("模型校验失败：t9_ngram.lm"))))
    }
}
