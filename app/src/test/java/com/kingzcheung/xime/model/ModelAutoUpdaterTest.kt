package com.kingzcheung.xime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAutoUpdaterTest {
    private fun model(vararg versions: ModelVersion) =
        ModelInfo("m", "M", "", ModelCategory.HANDWRITING, versions = versions.toList())

    private val v3 = ModelVersion(version = "v3")
    private val v2 = ModelVersion(version = "v2")
    private val v1 = ModelVersion(version = "v1")

    @Test fun olderInstalledVersionUpdatesToNewest() {
        assertEquals("v3", ModelAutoUpdater.pendingUpdate(model(v3, v2, v1), v1, null)?.version)
    }

    @Test fun newestOrUnknownOrMissingInstallIsLeftAlone() {
        val m = model(v3, v2, v1)
        assertNull(ModelAutoUpdater.pendingUpdate(m, v3, null))
        assertNull(ModelAutoUpdater.pendingUpdate(m, null, null))
        assertNull(ModelAutoUpdater.pendingUpdate(m, ModelVersion(version = "dev"), null))
    }

    @Test fun versionTheUserChoseIsNotReplaced() {
        val m = model(v3, v2, v1)
        assertNull(ModelAutoUpdater.pendingUpdate(m, v2, "v2"))
        // 固定的是另一版（之后又手动装了别的）：照常更新。
        assertEquals("v3", ModelAutoUpdater.pendingUpdate(m, v1, "v2")?.version)
    }

    @Test fun retiredVersionsAreNeverTargetsButAreUpdatedFrom() {
        val retiredTop = ModelVersion(version = "v4", retired = true)
        val m = model(retiredTop, v3, ModelVersion(version = "v2", retired = true))
        assertEquals("v3", ModelAutoUpdater.pendingUpdate(m, ModelVersion(version = "v2"), null)?.version)
        // 装着退役但更新的那版：不降级。
        assertNull(ModelAutoUpdater.pendingUpdate(m, retiredTop, null))
        // 整个条目都退役：不自动下载。
        assertNull(ModelAutoUpdater.pendingUpdate(model(ModelVersion(version = "a", retired = true),
            ModelVersion(version = "b", retired = true)), ModelVersion(version = "b"), null))
    }

    @Test fun catalogHandwritingAndNgramUpdatesAreFound() {
        val hand = BuiltinModelCatalog.models.first { it.id == "ochwpro" }
        for (old in listOf("v1.0", "v1.1"))
            assertEquals("v1.2", ModelAutoUpdater.pendingUpdate(hand, hand.versions.first { it.version == old }, null)?.version)
        val ngram = CandidateModelCatalog.models.first { it.id == CandidateModelCatalog.NGRAM }
        val latest = ModelAutoUpdater.latestVersion(ngram)!!
        for (old in ngram.versions.drop(1)) assertEquals(latest.version, ModelAutoUpdater.pendingUpdate(ngram, old, null)?.version)
        val grammar = CandidateModelCatalog.models.first { it.id == CandidateModelCatalog.GRAMMAR }
        assertNull(ModelAutoUpdater.pendingUpdate(grammar, grammar.versions.last(), null))
    }

    @Test fun failedAttemptsBackOffAndStopAfterLimit() {
        val now = 10L * ModelAutoUpdater.RETRY_INTERVAL_MS
        assertTrue(ModelAutoUpdater.attemptAllowed(null, "v3", now))
        var record = ModelAutoUpdater.nextAttempt(null, "v3", now)
        assertFalse(ModelAutoUpdater.attemptAllowed(record, "v3", now + 1))
        assertTrue(ModelAutoUpdater.attemptAllowed(record, "v3", now + ModelAutoUpdater.RETRY_INTERVAL_MS))
        // 新的版本重新计数。
        assertTrue(ModelAutoUpdater.attemptAllowed(record, "v4", now + 1))
        repeat(ModelAutoUpdater.MAX_ATTEMPTS - 1) { record = ModelAutoUpdater.nextAttempt(record, "v3", now) }
        assertFalse(ModelAutoUpdater.attemptAllowed(record, "v3", now + 100 * ModelAutoUpdater.RETRY_INTERVAL_MS))
        assertEquals("v4|1|5", ModelAutoUpdater.nextAttempt(record, "v4", 5))
    }

    @Test fun isNewerFollowsCatalogOrder() {
        val m = model(v3, v2, v1)
        assertTrue(ModelAutoUpdater.isNewer(m, v3, v1))
        assertFalse(ModelAutoUpdater.isNewer(m, v1, v3))
        assertFalse(ModelAutoUpdater.isNewer(m, v2, v2))
        assertFalse(ModelAutoUpdater.isNewer(m, ModelVersion(version = "x"), v1))
    }
}
