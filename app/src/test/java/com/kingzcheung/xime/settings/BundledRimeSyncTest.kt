package com.kingzcheung.xime.settings

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledRimeSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private fun manifest(text: String, name: String = "core.dict.yaml"): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$hash\t${text.toByteArray().size}\tbundled/$name\t$name\n"
    }
    @Test fun replacesSameSizeOldDictionaryBacksUpAndKeepsCompiledCacheForIncrementalRebuild() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        File(target, "core.dict.yaml").writeText("old")
        File(target, "build").mkdir(); File(target, "build/core.table.bin").writeText("cache")
        listOf("personal.dict.yaml", "core.custom.yaml", "core.userdb").forEach { File(target, it).writeText("personal") }
        assertTrue(BundledRimeSync.install(target, backups, manifest("new")) { "new".byteInputStream() })
        assertEquals("new", File(target, "core.dict.yaml").readText())
        assertEquals("old", backups.walkTopDown().single { it.isFile }.readText())
        // librime rebuilds only the tables whose source checksum changed; the cache is not wiped on upgrade.
        assertEquals("cache", File(target, "build/core.table.bin").readText())
        listOf("personal.dict.yaml", "core.custom.yaml", "core.userdb").forEach { assertEquals("personal", File(target, it).readText()) }
        assertFalse(BundledRimeSync.install(target, backups, manifest("new")) { error("Must not reopen large assets") })
    }
    @Test fun interruptedUpgradeRetriesWithoutOverwritingOriginalBackup() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        File(target, "a.schema.yaml").writeText("old")
        val list = manifest("one", "a.schema.yaml") + manifest("two", "b.dict.yaml")
        try { BundledRimeSync.install(target, backups, list) { if (it.endsWith("b.dict.yaml")) error("interruption") else "one".byteInputStream() }; fail() } catch (_: IllegalStateException) { }
        assertFalse(File(target, ".cyime-bundled-revision").exists())
        assertTrue(BundledRimeSync.install(target, backups, list) { if (it.endsWith("b.dict.yaml")) "two".byteInputStream() else error("already updated") })
        assertEquals("old", backups.walkTopDown().single { it.isFile }.readText())
    }
    @Test fun corruptAssetDoesNotReplaceExistingDictionaryOrCache() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        File(target, "core.dict.yaml").writeText("old"); File(target, "build").mkdir()
        try { BundledRimeSync.install(target, backups, manifest("new")) { "bad".byteInputStream() }; fail() } catch (_: IllegalStateException) { }
        assertEquals("old", File(target, "core.dict.yaml").readText()); assertTrue(File(target, "build").exists())
    }
    @Test fun missingFileRepairedEvenWithCurrentRevision() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        BundledRimeSync.install(target, backups, manifest("new")) { "new".byteInputStream() }
        File(target, "core.dict.yaml").delete()
        assertTrue(BundledRimeSync.install(target, backups, manifest("new")) { "new".byteInputStream() })
    }

    @Test fun lightweightEditionKeepsPreviouslyInstalledEnhancementModels() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        val grammar = "wanxiang-lts-zh-hans.gram"
        val scorer = "t9_sentence.onnx"
        val previous = manifest("schema") + manifest("grammar", grammar) + manifest("scorer", scorer)
        BundledRimeSync.install(target, backups, previous) {
            when (it.substringAfterLast('/')) {
                grammar -> "grammar"
                scorer -> "scorer"
                else -> "schema"
            }.byteInputStream()
        }
        assertTrue(BundledRimeSync.install(target, backups, manifest("updated schema")) {
            "updated schema".byteInputStream()
        })
        assertEquals("grammar", File(target, grammar).readText())
        assertEquals("scorer", File(target, scorer).readText())
        assertEquals("updated schema", File(target, "core.dict.yaml").readText())
    }

    @Test fun cylmFileDroppedByNewPackageIsRemovedButOtherDroppedFilesStay() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        val previous = manifest("schema") + manifest("big", "cylm_model2.lm") + manifest("grammar", "a.gram")
        BundledRimeSync.install(target, backups, previous) {
            when (it.substringAfterLast('/')) { "cylm_model2.lm" -> "big"; "a.gram" -> "grammar"; else -> "schema" }.byteInputStream()
        }
        assertTrue(BundledRimeSync.install(target, backups, manifest("new schema")) { "new schema".byteInputStream() })
        assertFalse(File(target, "cylm_model2.lm").exists())
        assertEquals("grammar", File(target, "a.gram").readText())
    }

    @Test fun retiredJaroomajiDictionariesAreRemovedUnlessEdited() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        val names = listOf("jaroomaji.schema.yaml", "jaroomaji.mozc.dict.yaml", "jaroomaji.dict.yaml",
            "jaroomaji.user.dict.yaml", "cyime_japanese_particles.dict.yaml", "lua/cyime_japanese_completion.lua", "other.dict.yaml")
        BundledRimeSync.install(target, backups, names.joinToString("") { manifest(it, it) }) { it.substringAfter('/').byteInputStream() }
        File(target, "jaroomaji.user.dict.yaml").writeText("my words")
        BundledRimeSync.install(target, backups, manifest("azooKey schema", "jaroomaji.schema.yaml")) { "azooKey schema".byteInputStream() }
        assertEquals("azooKey schema", File(target, "jaroomaji.schema.yaml").readText())
        listOf("jaroomaji.mozc.dict.yaml", "jaroomaji.dict.yaml", "cyime_japanese_particles.dict.yaml",
            "lua/cyime_japanese_completion.lua").forEach { assertFalse(it, File(target, it).exists()) }
        assertEquals("my words", File(target, "jaroomaji.user.dict.yaml").readText())
        assertEquals("other.dict.yaml", File(target, "other.dict.yaml").readText())
    }

    @Test fun userReplacedCylmFileIsKept() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        BundledRimeSync.install(target, backups, manifest("schema") + manifest("big", "cylm_model2.lm")) {
            (if (it.endsWith("cylm_model2.lm")) "big" else "schema").byteInputStream()
        }
        File(target, "cylm_model2.lm").writeText("mine")
        BundledRimeSync.install(target, backups, manifest("new schema")) { "new schema".byteInputStream() }
        assertEquals("mine", File(target, "cylm_model2.lm").readText())
    }

    @Test fun unmodifiedPreviousBundleIsReplacedWithoutBackup() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        BundledRimeSync.install(target, backups, manifest("one")) { "one".byteInputStream() }
        assertTrue(BundledRimeSync.install(target, backups, manifest("two")) { "two".byteInputStream() })
        assertEquals("two", File(target, "core.dict.yaml").readText())
        assertTrue(backups.walkTopDown().none { it.isFile })
    }

    @Test fun userEditedFileIsStillBackedUp() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        BundledRimeSync.install(target, backups, manifest("one")) { "one".byteInputStream() }
        File(target, "core.dict.yaml").writeText("mine")
        assertTrue(BundledRimeSync.install(target, backups, manifest("two")) { "two".byteInputStream() })
        assertEquals("mine", backups.walkTopDown().single { it.isFile }.readText())
    }

    @Test fun onlyNewestUpgradeBackupIsKept() {
        val backups = folder.newFolder("backup")
        val older = File(backups, "a".repeat(64)).apply { mkdirs(); File(this, "x.yaml").writeText("old"); setLastModified(1_000_000L) }
        val newer = File(backups, "b".repeat(64)).apply { mkdirs(); File(this, "x.yaml").writeText("new"); setLastModified(2_000_000L) }
        val other = File(backups, "english-policy").apply { mkdirs(); File(this, "p.yaml").writeText("keep") }
        BundledRimeSync.pruneBackups(backups)
        assertFalse(older.exists())
        assertTrue(newer.exists())
        assertEquals("keep", File(other, "p.yaml").readText())
    }
}
