package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DictionaryPrefetchTest {
    private val ok = PrefetchPolicy.Conditions()
    private val mb = 1L shl 20
    private val gb = 1L shl 30
    private fun files(resident: Float) = listOf(
        PrefetchPolicy.FileState("prism", 1 * mb, resident),
        PrefetchPolicy.FileState("table", 55 * mb, resident),
        PrefetchPolicy.FileState("model", 203 * mb, resident),
    )

    @Test fun coldFilesAreReadMostValuableFirstWhenMemoryAllows() {
        val policy = PrefetchPolicy()
        assertTrue(policy.shouldCheck(0, ok))
        assertEquals(listOf("prism", "table", "model"), policy.plan(0, files(0f), 4 * gb, 1 * gb))
    }

    @Test fun shortOfMemoryReadsOnlyWhatFitsAboveTheReserve() {
        val policy = PrefetchPolicy()
        // 100 MB above the reserve: prism and table fit, the model does not.
        assertEquals(listOf("prism", "table"), policy.plan(0, files(0f), 1 * gb + 100 * mb, 1 * gb))
        // Nothing above the reserve: read nothing.
        assertEquals(emptyList<String>(), PrefetchPolicy().plan(0, files(0f), 1 * gb, 1 * gb))
    }

    @Test fun warmFilesAreLeftAlone() {
        assertEquals(emptyList<String>(), PrefetchPolicy().plan(0, files(0.95f), 4 * gb, 1 * gb))
    }

    /** 平板 11-13：九键冷启动那一轮在等词组模型读盘；九成在内存不算热，缺的页要补。 */
    @Test fun nineKeyFilesMissingAFewPercentAreStillFilled() {
        val nineKey = listOf(
            PrefetchPolicy.FileState("prism", 1 * mb, 1f, full = true),
            PrefetchPolicy.FileState("table", 55 * mb, 1f, full = true),
            PrefetchPolicy.FileState("model", 105 * mb, 0.93f, full = true),
        )
        assertEquals(listOf("model"), PrefetchPolicy().plan(0, nineKey, 4 * gb, 1 * gb))
        // 几乎全在：不动。
        val warm = nineKey.map { it.copy(resident = 0.995f) }
        assertEquals(emptyList<String>(), PrefetchPolicy().plan(0, warm, 4 * gb, 1 * gb))
        // 26 键的文件照旧九成算热（warmFilesAreLeftAlone），内存不够照旧不读。
        assertEquals(emptyList<String>(), PrefetchPolicy().plan(0, nineKey, 1 * gb, 1 * gb))
    }

    @Test fun anEngineBusyAtShowIsAskedAgainUntilTheKeyboardGoes() = runBlocking {
        var calls = 0
        val found = retryWhileEmpty(listOf(1L, 1L, 1L), { true }) { if (++calls < 3) emptyList() else listOf("model") }
        assertEquals(listOf("model"), found)
        assertEquals(3, calls)
        // 收起了就不再问。
        calls = 0
        var shown = true
        val gone = retryWhileEmpty(listOf(1L, 1L, 1L), { shown }) { calls++; shown = false; emptyList() }
        assertEquals(emptyList<String>(), gone)
        assertEquals(1, calls)
        // 第一次就有：不等。
        calls = 0
        assertEquals(listOf("prism"), retryWhileEmpty(listOf(10_000L), { true }) { calls++; listOf("prism") })
        assertEquals(1, calls)
    }

    /**
     * 平板 84（11-13）：键盘开着从 26 键换到九键时没有补读。弹键盘那次刚补过 26 键的文件，
     * 一分钟间隔不能挡住新方案的模型；内存不够照旧不读。
     */
    @Test fun aSchemaSwitchedToIsFilledInsideTheRefillInterval() {
        val policy = PrefetchPolicy()
        assertEquals(listOf("prism", "table", "model"), policy.plan(0, files(0f), 4 * gb, 1 * gb))
        val model = listOf(PrefetchPolicy.FileState("model", 105 * mb, 0.4f, full = true))
        assertEquals(emptyList<String>(), policy.plan(5_000, model, 4 * gb, 1 * gb))
        assertEquals(listOf("model"), policy.plan(5_000, model, 4 * gb, 1 * gb, switched = true))
        assertEquals(emptyList<String>(), policy.plan(6_000, model, 1 * gb, 1 * gb, switched = true))
    }

    /** 源码检查：引擎换方案成功后通知服务，服务在创建时接上、销毁时摘掉。 */
    @Test fun aSuccessfulSchemaSwitchReachesThePrefetch() {
        fun source(path: String) = listOf("src/main/java/com/kingzcheung/xime/$path",
            "app/src/main/java/com/kingzcheung/xime/$path").map { java.io.File(it) }.first { it.isFile }.readText()
        val engine = source("rime/RimeEngine.kt")
        val switch = engine.substring(engine.indexOf("fun switchSchema(schemaId: String): Boolean {")).substringBefore("private fun switchSchemaLocked")
        assertTrue(switch, switch.contains("if (switched) runCatching { onSchemaSwitched?.invoke(schemaId) }"))
        val service = source("service/XimeInputMethodService.kt")
        assertTrue(service.contains("rimeEngine.onSchemaSwitched = schemaSwitchPrefetch"))
        assertTrue(service.contains("if (rimeEngine.onSchemaSwitched === schemaSwitchPrefetch) rimeEngine.onSchemaSwitched = null"))
        assertTrue(service.contains("DictionaryPrefetch.onSchemaSwitched(this, serviceScope, rimeEngine, schemaId)"))
    }

    @Test fun checksAreSpacedButTypingFaultsBypassTheInterval() {
        val policy = PrefetchPolicy()
        policy.plan(0, files(0f), 4 * gb, 1 * gb)
        assertFalse(policy.shouldCheck(5_000, ok))
        assertTrue(policy.shouldCheck(PrefetchPolicy.MIN_CHECK_GAP_MS, ok))
        // Within the interval a cold check reads nothing ...
        assertEquals(emptyList<String>(), policy.plan(20_000, files(0.6f), 4 * gb, 1 * gb))
        // ... unless the last typing session waited on the disk.
        policy.onSessionEnd(PrefetchPolicy.SESSION_FAULTS)
        assertTrue(policy.shouldCheck(21_000, ok))
        assertEquals(3, policy.plan(21_000, files(0.6f), 4 * gb, 1 * gb).size)
    }

    @Test fun refilledPagesLostAgainSoonWidenTheIntervalOnceAndStableChecksResetIt() {
        val policy = PrefetchPolicy()
        policy.plan(0, files(0f), 4 * gb, 1 * gb)
        policy.plan(60_000, files(0.2f), 4 * gb, 1 * gb)
        assertEquals(2 * policy.minIntervalMs, policy.intervalMs)
        policy.plan(70_000, files(0.2f), 4 * gb, 1 * gb)
        assertEquals(2 * policy.minIntervalMs, policy.intervalMs)
        repeat(PrefetchPolicy.STABLE_CHECKS_TO_RESET) { policy.plan(200_000L + it * 20_000, files(1f), 4 * gb, 1 * gb) }
        assertEquals(policy.minIntervalMs, policy.intervalMs)
    }

    @Test fun memoryPressureHoldsOffAndTheIntervalNeverPassesTheMaximum() {
        val policy = PrefetchPolicy()
        policy.onMemoryPressure(0)
        assertFalse(policy.shouldCheck(PrefetchPolicy.PRESSURE_HOLD_MS - 1, ok))
        assertTrue(policy.shouldCheck(PrefetchPolicy.PRESSURE_HOLD_MS, ok))
        repeat(20) { policy.onMemoryPressure(0) }
        assertEquals(policy.maxIntervalMs, policy.intervalMs)
    }

    @Test fun powerSaveLowMemoryLowRamAndHeatBlock() {
        val policy = PrefetchPolicy()
        assertFalse(policy.shouldCheck(0, PrefetchPolicy.Conditions(powerSave = true)))
        assertFalse(policy.shouldCheck(0, PrefetchPolicy.Conditions(lowMemory = true)))
        assertFalse(policy.shouldCheck(0, PrefetchPolicy.Conditions(lowRamDevice = true)))
        assertFalse(policy.shouldCheck(0, PrefetchPolicy.Conditions(thermalSevere = true)))
    }
}
