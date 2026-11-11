package com.kingzcheung.xime.settings

import com.kingzcheung.xime.model.BuiltinModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 轻量版下载的日语词库和引擎，要和安装包里打的（tasks-japanese.gradle.kts）是同一份。 */
class JapanesePackCatalogTest {
    private val gradle = File("build-logic/tasks-japanese.gradle.kts").readText()
    private val catalog = File("src/main/java/com/kingzcheung/xime/model/BuiltinModelCatalog.kt").readText()

    @Test fun downloadedDictionaryIsTheOneTheApkCarried() {
        assertTrue(gradle.contains("val azooKeyMergedDictionaryHash = \"${JapanesePack.DICTIONARY_VERSION}\""))
        val dictionary = BuiltinModelCatalog.models.first { it.id == BuiltinModelCatalog.JAPANESE_DICTIONARY }.resolvedVersion()!!
        assertEquals(JapanesePack.DICTIONARY_VERSION.take(8), dictionary.version)
        assertEquals(listOf("dictionary.zip"), dictionary.files.map { it.name })
    }

    @Test fun everyEngineIsAPinnedAzooKeyLibrary() {
        val pinned = Regex("\"([a-z0-9_-]+)\" to \"([0-9a-f]{64})\"").findAll(gradle.substringAfter("val azooKeyLibraryHashes"))
            .take(3).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64"), pinned.keys)
        for ((abi, hash) in pinned) {
            assertTrue(abi, catalog.contains("japanese-engine-azookey-${hash.take(8)}-$abi.tar.bz2"))
            assertTrue(abi, catalog.contains("ModelFile(\"libCyAzooKey.so\", \"\", \"$hash\""))
        }
    }
}
