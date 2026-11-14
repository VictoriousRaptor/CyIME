package com.kingzcheung.xime.service

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * 词库和词组模型的自适应预读。
 *
 * 这些文件按页映射读取，放在系统页缓存里：内存够时系统留着，不够时回收，回收后的页要在打字时从存储读回来
 * （平板九键慢键 50~160 ms 都伴随上百次读盘）。这里不把文件读进程序自己的内存常驻（多占两三百 MB，
 * 输入法更容易被系统杀掉，冷启动更慢），只判断什么时候该把被回收的页补回来、补多少。
 *
 * 不定时轮询，只在事件上判断：
 * - 键盘弹出：抽查各文件在内存的比例（每 64 页查一页），不足九成就按优先级补读缺的部分；
 * - 键盘收起：本次打字期间进程读盘次数多，说明页被回收过，下次弹出不等间隔直接补；
 * - 系统内存紧张（onTrimMemory）：停止补读一段时间，并拉长间隔，让系统回收。
 * 间隔自适应：补读后很快又被回收（内存紧的机器）就加倍，最长 30 分钟；连续几次都留得住就回到 1 分钟。
 * 补读前留出内存余量，余量不够时只补优先级高的（音节表、主词库），词组模型最后补。
 * 省电模式、严重发热、低内存设备不做。
 *
 * 九键、14 键、注音（用词组模型的方案）的文件按「整份」看：抽查到缺页就补，不按九成算热（平板 11-13 实测：
 * 冷启动那一轮在编码长度 6、13、17、24 处卡 70~106 ms，全是等读盘；同样的字预热一遍后 P90 44 ms、零读盘）。
 * 键盘开着时换到这类方案（26 键换九键）也查一次：弹键盘那次查的是 26 键的文件，九键的模型可能刚在收起时放掉。
 */
internal class PrefetchPolicy(
    val minIntervalMs: Long = 60_000L,
    val maxIntervalMs: Long = 30 * 60_000L,
) {
    data class Conditions(
        val powerSave: Boolean = false,
        val lowRamDevice: Boolean = false,
        val lowMemory: Boolean = false,
        val thermalSevere: Boolean = false,
    )

    /**
     * One file's state at a check: [resident] is the sampled fraction in memory, 0..1. [full]: any page
     * may be read on the next key (the nine-key phrase model and its schema's tables), so it counts as warm
     * only when nearly all of it is in memory, not at the 90 % line.
     */
    data class FileState(val path: String, val bytes: Long, val resident: Float, val full: Boolean = false)

    private fun FileState.warm(): Boolean = resident >= if (full) FULL else WARM

    var intervalMs = minIntervalMs
        private set
    private var lastCheckAt: Long? = null
    private var lastPrefetchAt: Long? = null
    private var pressureAt: Long? = null
    private var stableChecks = 0
    private var faultsSeen = false
    private var thrashCounted = false

    fun blocked(now: Long, c: Conditions): Boolean =
        c.powerSave || c.lowRamDevice || c.lowMemory || c.thermalSevere ||
            pressureAt?.let { now - it < PRESSURE_HOLD_MS } == true

    /** Keyboard shown: look at residency now? (The look itself costs about a millisecond.) */
    fun shouldCheck(now: Long, c: Conditions): Boolean =
        !blocked(now, c) && (faultsSeen || lastCheckAt?.let { now - it >= MIN_CHECK_GAP_MS } != false)

    /**
     * Residency was read; returns the files to prefetch, most valuable first, within the memory
     * [headroomBytes] above [reserveBytes]. [switched]: the files of a schema just switched to while the
     * keyboard is up; the refill interval (which spaces out refills of the same files) does not hold them back.
     */
    fun plan(now: Long, files: List<FileState>, headroomBytes: Long, reserveBytes: Long,
             switched: Boolean = false): List<String> {
        lastCheckAt = now
        val total = files.sumOf { it.bytes }.coerceAtLeast(1)
        val resident = files.sumOf { (it.bytes * it.resident).toLong() }.toFloat() / total
        val sinceLast = lastPrefetchAt?.let { now - it }
        if (resident >= WARM && files.all { it.warm() }) {
            if (++stableChecks >= STABLE_CHECKS_TO_RESET) intervalMs = minIntervalMs
            faultsSeen = false
            return emptyList()
        }
        stableChecks = 0
        // Filled not long ago and already mostly gone again: this device is short of memory.
        if (sinceLast != null && sinceLast < THRASH_WINDOW_MS && resident < THRASHED && !thrashCounted) {
            thrashCounted = true
            widen()
        }
        if (!switched && !faultsSeen && sinceLast != null && sinceLast < intervalMs) return emptyList()
        var room = headroomBytes - reserveBytes
        val chosen = mutableListOf<String>()
        for (file in files) {
            val missing = (file.bytes * (1f - file.resident)).toLong()
            if (missing <= 0 || file.warm()) continue
            if (missing > room) break
            room -= missing
            chosen += file.path
        }
        if (chosen.isNotEmpty()) {
            lastPrefetchAt = now
            faultsSeen = false
            thrashCounted = false
        }
        return chosen
    }

    /** Keyboard hidden: [majorFaults] page reads during this typing session. */
    fun onSessionEnd(majorFaults: Long) {
        if (majorFaults >= SESSION_FAULTS) faultsSeen = true
    }

    /** The system asked to free memory. */
    fun onMemoryPressure(now: Long) {
        pressureAt = now
        widen()
    }

    private fun widen() {
        intervalMs = (intervalMs * 2).coerceAtMost(maxIntervalMs)
        stableChecks = 0
    }

    companion object {
        const val WARM = 0.9f
        const val FULL = 0.99f
        const val THRASHED = 0.5f
        const val THRASH_WINDOW_MS = 5 * 60_000L
        const val PRESSURE_HOLD_MS = 5 * 60_000L
        const val MIN_CHECK_GAP_MS = 10_000L
        const val STABLE_CHECKS_TO_RESET = 3
        const val SESSION_FAULTS = 50L
    }
}

internal object DictionaryPrefetch {
    private const val TAG = "DictionaryPrefetch"
    private val policy = PrefetchPolicy()
    @Volatile private var faultsAtShow = -1L
    /** Bumped on every show and hide: a retry from an earlier show stops once the keyboard has gone. */
    @Volatile private var showSerial = 0L
    /** 九键词组模型的文件名（RimeEngine.prefetchCandidates 只给用它的方案列出）。 */
    private const val T9_MODEL_FILE = "t9_ngram.lm"
    /**
     * 弹键盘这一刻引擎常在忙（刚准备输入框、冷启动还在加载）：读不到方案就隔一会儿再试，共约 5 秒，
     * 键盘收起就停。以前这一下读不到就整次跳过，冷启动后头一轮打字全在等读盘。
     */
    private val RETRY_DELAYS_MS = listOf(300L, 700L, 1_500L, 3_000L)

    /** Keyboard up (between onShow and onHide): a schema switch now is typed on right away. */
    @Volatile private var visible = false

    fun onShow(context: Context, scope: CoroutineScope, engine: RimeEngine) {
        val now = SystemClock.elapsedRealtime()
        faultsAtShow = majorFaults()
        val (conditions, memory) = readConditions(context)
        val serial = ++showSerial
        visible = true
        if (!synchronized(policy) { policy.shouldCheck(now, conditions) }) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                var schema = ""
                val candidates = retryWhileEmpty(RETRY_DELAYS_MS, { showSerial == serial }) {
                    schema = engine.getCurrentSchema()
                    engine.prefetchCandidates(schema)
                }
                fill(engine, schema, candidates, now, memory, switched = false)
            }.onFailure { FileLogger.w(TAG, "prefetch skipped: ${it.message}") }
        }
    }

    /**
     * 键盘开着时换了方案（如 26 键换九键）：弹键盘那次查的是旧方案的文件，九键词组模型还可能在上次收起时放掉了，
     * 换过来头几键全在等读盘。只管用词组模型的方案（九键、14 键、注音）；26 键照旧只在弹键盘时查。
     * 省电、发热、内存紧这些条件照旧挡着。在哪个线程调都行，活都在 IO 线程做。
     */
    fun onSchemaSwitched(context: Context, scope: CoroutineScope, engine: RimeEngine, schemaId: String) {
        if (!visible || schemaId.isEmpty()) return
        val serial = showSerial
        scope.launch(Dispatchers.IO) {
            runCatching {
                val now = SystemClock.elapsedRealtime()
                val (conditions, memory) = readConditions(context)
                if (synchronized(policy) { policy.blocked(now, conditions) }) return@runCatching
                val candidates = retryWhileEmpty(RETRY_DELAYS_MS, { showSerial == serial }) {
                    engine.prefetchCandidates(schemaId)
                }
                if (candidates.none { File(it).name == T9_MODEL_FILE }) return@runCatching
                fill(engine, schemaId, candidates, now, memory, switched = true)
            }.onFailure { FileLogger.w(TAG, "prefetch on switch skipped: ${it.message}") }
        }
    }

    private fun readConditions(context: Context): Pair<PrefetchPolicy.Conditions, ActivityManager.MemoryInfo> {
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { info -> runCatching { activity?.getMemoryInfo(info) } }
        val power = context.getSystemService(PowerManager::class.java)
        return PrefetchPolicy.Conditions(
            powerSave = power?.isPowerSaveMode == true,
            lowRamDevice = activity?.isLowRamDevice == true,
            lowMemory = memory.lowMemory,
            thermalSevere = AndroidPlatform.thermalStatus &&
                (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE,
        ) to memory
    }

    private fun fill(engine: RimeEngine, schema: String, candidates: List<String>, now: Long,
                     memory: ActivityManager.MemoryInfo, switched: Boolean) {
        val full = candidates.any { File(it).name == T9_MODEL_FILE }
        val files = candidates.mapNotNull { path ->
            val fraction = engine.residentFraction(path)
            if (fraction < 0) null else PrefetchPolicy.FileState(path, File(path).length(), fraction, full)
        }
        if (files.isEmpty()) return
        // Leave the system its low-memory threshold plus an eighth of RAM after reading.
        val reserve = maxOf(memory.threshold, memory.totalMem / 8)
        val chosen = synchronized(policy) { policy.plan(now, files, memory.availMem, reserve, switched) }
        val requested = chosen.sumOf { engine.prefetchFile(it).coerceAtLeast(0) }
        if (requested > 0) FileLogger.i(TAG, "schema=$schema requested=${requested}KB files=${chosen.size} " +
            "interval=${policy.intervalMs / 1000}s" + if (switched) " switched" else "")
    }

    fun onHide() {
        visible = false
        showSerial++
        val start = faultsAtShow
        val end = majorFaults()
        if (start >= 0 && end >= start) synchronized(policy) { policy.onSessionEnd(end - start) }
        faultsAtShow = -1
    }

    fun onTrimMemory(level: Int) {
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (pressure) synchronized(policy) { policy.onMemoryPressure(SystemClock.elapsedRealtime()) }
    }

    /** Major page faults of this process so far (field 12 of /proc/self/stat); -1 if unreadable. */
    private fun majorFaults(): Long = runCatching {
        val stat = File("/proc/self/stat").readText()
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[9].toLong()
    }.getOrDefault(-1L)
}

/** Reads again after each delay while the result is empty and [stillWanted]; the last result otherwise. */
internal suspend fun retryWhileEmpty(delaysMs: List<Long>, stillWanted: () -> Boolean, read: () -> List<String>): List<String> {
    var result = read()
    for (wait in delaysMs) {
        if (result.isNotEmpty() || !stillWanted()) break
        delay(wait)
        if (!stillWanted()) break
        result = read()
    }
    return result
}
