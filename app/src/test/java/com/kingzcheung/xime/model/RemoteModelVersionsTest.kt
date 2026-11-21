package com.kingzcheung.xime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网上模型目录补内置模型的新版本（11-19）：重新训练的手写模型不用发新版 App 就能更新。 */
class RemoteModelVersionsTest {
    private val sha = "e0250abf61e7efdf9ae0e4abd64ef7d52d86fc412d9f11a7c2615cdd4fca6d2b"
    private val index = """
        models:
          - id: ochwpro
            name: 手写模型
            category: handwriting
            versions:
              - version: v1.3
                size: 6.7 MB
                files:
                  - name: ochwpro.onnx
                    url: "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/ochwpro-v1.3-ochwpro.onnx"
                    sha256: "$sha"
                    sizeBytes: 7001270
                    mirrors:
                      - "https://github.com/Cyletix/CyIME/releases/download/models/ochwpro-v1.3-ochwpro.onnx"
                  - name: char_index.json
                    url: "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json"
                    sha256: "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176"
                    sizeBytes: 51317
              - version: v1.2
                files:
                  - name: ochwpro.onnx
                    url: "https://example.com/changed-on-the-server.onnx"
                    sha256: "$sha"
                    sizeBytes: 7001270
              - version: unpinned
                files:
                  - name: ochwpro.onnx
                    url: "https://example.com/ochwpro.onnx"
          - id: handwriting-rare
            name: 手写生僻字
            category: other
            versions:
              - version: v13pack
                archive:
                  url: "https://github.com/Cyletix/CyIME/releases/download/models/handwriting-rare-v13pack.tar.bz2"
                  mirrors:
                    - "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/handwriting-rare-v13pack.tar.bz2"
                sha256: "$sha"
                files:
                  - name: hanzi_rare_och.bin
                    sha256: "$sha"
                    sizeBytes: 10
                  - name: hanzi_rare_och.json
                    sha256: "$sha"
                    sizeBytes: 10
          - id: not-in-the-app
            name: 别的
            category: other
            versions:
              - version: "1"
                files:
                  - name: a.bin
                    url: "https://example.com/a.bin"
                    sha256: "$sha"
                    sizeBytes: 1
    """.trimIndent()

    private val merged = RemoteModelVersions.merge(BuiltinModelCatalog.models, ModelIndexLoader.parseModelsIndex(index))

    @Test fun newVersionGoesFirstAndBuiltinVersionsStay() {
        val builtin = BuiltinModelCatalog.models.first { it.id == "ochwpro" }
        val model = merged.first { it.id == "ochwpro" }
        assertEquals(listOf("v1.3") + builtin.versions.map { it.version }, model.versions.map { it.version })
        val file = model.versions.first().files.first { it.name == "ochwpro.onnx" }
        assertEquals(7001270L, file.sizeBytes)
        assertEquals(listOf("https://github.com/Cyletix/CyIME/releases/download/models/ochwpro-v1.3-ochwpro.onnx"), file.mirrors)
        // 自动更新把它当成比装着的 v1.2 新
        assertTrue(ModelAutoUpdater.isNewer(model, model.versions[0], model.versions.first { it.version == "v1.2" }))
        assertEquals("v1.3", ModelAutoUpdater.pendingUpdate(model, model.versions.first { it.version == "v1.2" }, null)?.version)
    }

    @Test fun serverCannotRewriteAVersionTheAppAlreadyHas() {
        val v12 = merged.first { it.id == "ochwpro" }.versions.single { it.version == "v1.2" }
        assertEquals(BuiltinModelCatalog.models.first { it.id == "ochwpro" }.versions.single { it.version == "v1.2" }, v12)
    }

    @Test fun archivePackKeepsItsMirrors() {
        val pack = merged.first { it.id == BuiltinModelCatalog.HANDWRITING_RARE }.versions.first()
        assertEquals("v13pack", pack.version)
        assertEquals(1, pack.archiveMirrors.size)
    }

    @Test fun unknownModelsAndUnpinnedVersionsAreIgnored() {
        assertEquals(BuiltinModelCatalog.models.map { it.id }, merged.map { it.id })
        assertFalse(merged.first { it.id == "ochwpro" }.versions.any { it.version == "unpinned" })
        val untouched = BuiltinModelCatalog.models.first { it.id == BuiltinModelCatalog.JAPANESE_HANDWRITING }
        assertSame(untouched, merged.first { it.id == untouched.id })
    }

    /** 仓库里发出去的那份目录：每个版本 App 都收，模型都是 App 里有的。 */
    @Test fun publishedIndexIsFullyAccepted() {
        val published = ModelIndexLoader.parseModelsIndex(java.io.File("../store/models/index.yaml").readText())
        assertTrue(published.isNotEmpty())
        val ids = BuiltinModelCatalog.models.map { it.id }.toSet()
        for (model in published) {
            assertTrue(model.id, model.id in ids)
            for (version in model.versions) assertTrue("${model.id} ${version.version}", RemoteModelVersions.pinned(version))
        }
        val all = RemoteModelVersions.merge(BuiltinModelCatalog.models, published)
        for (model in published) assertEquals(model.versions.first().version, all.first { it.id == model.id }.versions.first().version)
    }

    /** #498：网上目录不能换、不能加程序库，带原生库的模型只用 App 里写死的版本。 */
    @Test fun serverCannotSendNativeCode() {
        val lib = ModelFile("libopencv_java4.so", "https://example.com/libopencv_java4.so", sha, 1)
        val data = ModelFile("a.bin", "https://example.com/a.bin", sha, 1)
        assertTrue(RemoteModelVersions.carriesCode(ModelVersion("1", files = listOf(data, lib))))
        assertTrue(RemoteModelVersions.carriesCode(ModelVersion("1", files = listOf(data.copy(name = "x.DEX")))))
        assertFalse(RemoteModelVersions.carriesCode(ModelVersion("1", files = listOf(data))))
        val remote = listOf(
            ModelInfo(BuiltinModelCatalog.OCR_ENGINE, "", "", ModelCategory.OTHER, versions = listOf(ModelVersion("new", files = listOf(lib)))),
            ModelInfo(BuiltinModelCatalog.JAPANESE_ENGINE, "", "", ModelCategory.OTHER, versions = listOf(ModelVersion("new", files = listOf(data)))),
            ModelInfo("ochwpro", "", "", ModelCategory.HANDWRITING, versions = listOf(ModelVersion("evil", files = listOf(data, lib)))),
        )
        val out = RemoteModelVersions.merge(BuiltinModelCatalog.models, remote)
        for (id in listOf(BuiltinModelCatalog.OCR_ENGINE, BuiltinModelCatalog.JAPANESE_ENGINE, "ochwpro"))
            assertSame(id, BuiltinModelCatalog.models.first { it.id == id }, out.first { it.id == id })
        // App 里没有一个模型是从网上目录拿程序库的
        assertFalse(BuiltinModelCatalog.models.any { it.id == "speech-engine" })
    }

    @Test fun pinnedNeedsHttpsAndChecksums() {
        val file = ModelFile("a.bin", "https://example.com/a.bin", sha, 1)
        assertTrue(RemoteModelVersions.pinned(ModelVersion("1", files = listOf(file))))
        assertFalse(RemoteModelVersions.pinned(ModelVersion("1", files = listOf(file.copy(downloadUrl = "http://example.com/a.bin")))))
        assertFalse(RemoteModelVersions.pinned(ModelVersion("1", files = listOf(file.copy(sha256 = "abc")))))
        assertFalse(RemoteModelVersions.pinned(ModelVersion("1", files = listOf(file.copy(sizeBytes = 0)))))
        assertFalse(RemoteModelVersions.pinned(ModelVersion("1", files = listOf(file.copy(mirrors = listOf("http://x/a.bin"))))))
        assertFalse(RemoteModelVersions.pinned(ModelVersion("1", files = listOf(file), archiveUrl = "https://example.com/a.tar.bz2")))
        assertFalse(RemoteModelVersions.pinned(ModelVersion("1")))
    }
}
