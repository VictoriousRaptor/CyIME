package com.kingzcheung.xime.service

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.util.concurrent.Executors

/**
 * CPU 用量日志：键盘每次收起、弹出时记一笔，看收起的这段时间里输入法到底用了多少 CPU、是哪个线程用的。
 *
 * 用户 11-23：电池页 CyIME 一天 8.1%，大多算在「后台」。系统电池页分不清「在别的应用里打字」和「真在后台偷跑」，
 * 这份日志分得清：收起期间（键盘不在屏幕上）CPU 应接近 0；不是 0 就把最忙的几个线程名写出来，下次一看就知道是谁。
 *
 * 只在收起/弹出这两个时刻读一次 /proc（几毫秒，单独线程），不开定时器、不唤醒手机、不改任何设置。
 * 同一应用的其他进程（:asr、:inference 等）也一起算。正式包和个人包都写（警告级）。
 */
internal object CpuUsageLog {
    private const val TAG = "CpuUsage"
    /** 短于这个的收起/弹出不单独记，免得每切一次输入框都写一行。 */
    internal const val MIN_INTERVAL_LOG_MS = 60_000L
    /** 收起期间每分钟超过这么多 CPU 毫秒，就算后台在跑，写出最忙的线程。 */
    internal const val HIDDEN_BUSY_MS_PER_MIN = 100L
    private const val TOP_THREADS = 6

    data class ThreadTicks(val name: String, val ticks: Long)

    data class Snapshot(
        val at: Long,
        val processCpuMs: Long,
        val threads: Map<Int, ThreadTicks>,
        /** 其他进程：进程名 → (pid, CPU 跳数)。 */
        val others: Map<String, Pair<Int, Long>>,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CpuUsageLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var hiddenSince: Snapshot? = null
    @Volatile private var shownSince: Snapshot? = null

    fun onKeyboardHidden(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val now = snapshot(app)
                shownSince?.let { start -> report("shown", start, now) }
                shownSince = null
                hiddenSince = now
            }
        }
    }

    fun onKeyboardShown(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val now = snapshot(app)
                hiddenSince?.let { start -> report("hidden", start, now) }
                hiddenSince = null
                shownSince = now
            }
        }
    }

    private fun report(phase: String, start: Snapshot, end: Snapshot) {
        val line = describe(phase, start, end, ticksToMs = 1000.0 / clockTicksPerSecond) ?: return
        FileLogger.w(TAG, line)
    }

    /** 一段时间的结论；太短（[MIN_INTERVAL_LOG_MS] 内）且不忙时返回 null，不写日志。 */
    internal fun describe(phase: String, start: Snapshot, end: Snapshot, ticksToMs: Double): String? {
        val spanMs = end.at - start.at
        if (spanMs <= 0) return null
        val cpuMs = (end.processCpuMs - start.processCpuMs).coerceAtLeast(0)
        val perMin = cpuMs * 60_000 / spanMs
        val busyHidden = phase == "hidden" && perMin >= HIDDEN_BUSY_MS_PER_MIN && cpuMs >= 500
        if (spanMs < MIN_INTERVAL_LOG_MS && !busyHidden) return null
        val sb = StringBuilder()
        sb.append(phase).append(' ').append(spanMs / 1000).append(" s: cpu ").append(cpuMs)
            .append(" ms (").append(perMin).append(" ms/min)")
        val others = otherProcessDeltas(start, end, ticksToMs)
        if (others.isNotEmpty()) sb.append("; other ").append(others.joinToString(", ") { "${it.first} ${it.second} ms" })
        if (phase == "shown" || busyHidden) {
            val top = topThreads(start.threads, end.threads, TOP_THREADS)
                .filter { it.second * ticksToMs >= 20 }
            if (top.isNotEmpty()) sb.append("; top ")
                .append(top.joinToString(", ") { "${it.first} ${(it.second * ticksToMs).toLong()} ms" })
        }
        return sb.toString()
    }

    /** 两次快照之间用 CPU 最多的线程（线程名，跳数）。新开的线程从 0 算；中途退出的线程算不上（读不到）。 */
    internal fun topThreads(before: Map<Int, ThreadTicks>, after: Map<Int, ThreadTicks>, limit: Int): List<Pair<String, Long>> =
        after.mapNotNull { (tid, now) ->
            val was = before[tid]?.takeIf { it.name == now.name }?.ticks ?: 0L
            (now.ticks - was).takeIf { it > 0 }?.let { now.name to it }
        }
            // 同名线程（线程池）合在一起算，比一个个列出来好认。
            .groupBy({ it.first }, { it.second }).map { (name, list) -> name to list.sum() }
            .sortedByDescending { it.second }
            .take(limit)

    internal fun otherProcessDeltas(start: Snapshot, end: Snapshot, ticksToMs: Double): List<Pair<String, Long>> =
        end.others.mapNotNull { (name, now) ->
            val was = start.others[name]?.takeIf { it.first == now.first }?.second ?: 0L
            val ms = ((now.second - was).coerceAtLeast(0) * ticksToMs).toLong()
            if (ms >= 20) name to ms else null
        }.sortedByDescending { it.second }

    /** /proc/.../stat 一行 → (线程名, utime+stime 跳数)。名字里可能有空格和括号，按最后一个 ')' 切。 */
    internal fun parseStat(line: String): ThreadTicks? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val name = line.substring(open + 1, close)
        // ')' 之后从第 3 列（state）开始；utime、stime 是第 14、15 列。
        val rest = line.substring(close + 1).trim().split(' ')
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return ThreadTicks(name, utime + stime)
    }

    private val clockTicksPerSecond: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).takeIf { it > 0 } ?: 100L
    }

    private fun snapshot(context: Context): Snapshot {
        val threads = HashMap<Int, ThreadTicks>()
        File("/proc/self/task").listFiles()?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            runCatching { parseStat(File(dir, "stat").readText()) }.getOrNull()?.let { threads[tid] = it }
        }
        val myPid = Process.myPid()
        val others = HashMap<String, Pair<Int, Long>>()
        runCatching {
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses?.forEach { proc ->
                if (proc.pid == myPid) return@forEach
                val ticks = runCatching { parseStat(File("/proc/${proc.pid}/stat").readText())?.ticks }.getOrNull()
                    ?: return@forEach
                others[proc.processName.substringAfter(context.packageName).ifEmpty { proc.processName }] = proc.pid to ticks
            }
        }
        return Snapshot(SystemClock.elapsedRealtime(), Process.getElapsedCpuTime(), threads, others)
    }
}
