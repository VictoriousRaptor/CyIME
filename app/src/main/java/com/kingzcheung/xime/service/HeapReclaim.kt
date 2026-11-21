package com.kingzcheung.xime.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import kotlin.concurrent.thread

/**
 * 键盘收起、设置页也都关了 30 秒后做一次完整回收，把已经不用的 Java 堆页还给系统。
 *
 * 普通 App 退到后台时系统会替它收紧 Java 堆；输入法进程一直算“正在用”，设置页又和输入法在同一个进程，
 * 开过设置页或者处理过一大段内容以后，用完的堆页就一直占着。平板 11-19 实测：键盘收起时 Java 堆脏页 89 MB，
 * 堆里实际只用了 4.4 MB。
 *
 * 两次至少隔 10 分钟；30 秒内键盘或设置页又出来就不做。在单独线程里跑，不碰主线程，不改任何设置。
 */
internal object HeapReclaim {
    private const val TAG = "HeapReclaim"
    internal const val DELAY_MS = 30_000L
    internal const val MIN_INTERVAL_MS = 10 * 60_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var keyboardShown = false
    private var startedActivities = 0
    private var lastRunAt = Long.MIN_VALUE
    private val pending = Runnable { runIfDue() }

    // 以下都在主线程调用（输入法窗口回调、Activity 生命周期回调）。
    fun onKeyboardShown() { keyboardShown = true; handler.removeCallbacks(pending) }
    fun onKeyboardHidden() { keyboardShown = false; schedule() }
    fun onActivityStarted() { startedActivities++; handler.removeCallbacks(pending) }
    fun onActivityStopped() { startedActivities = (startedActivities - 1).coerceAtLeast(0); schedule() }

    /** 现在该不该回收：键盘收着、没有设置页在前面、离上次够久。 */
    internal fun due(keyboardShown: Boolean, startedActivities: Int, lastRunAt: Long, now: Long): Boolean =
        !keyboardShown && startedActivities == 0 &&
            (lastRunAt == Long.MIN_VALUE || now - lastRunAt >= MIN_INTERVAL_MS)

    private fun schedule() {
        if (keyboardShown || startedActivities > 0) return
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, DELAY_MS)
    }

    private fun runIfDue() {
        val now = SystemClock.uptimeMillis()
        if (!due(keyboardShown, startedActivities, lastRunAt, now)) return
        lastRunAt = now
        thread(name = TAG, isDaemon = true) {
            val before = residentMb()
            Runtime.getRuntime().gc()
            // 警告级：正式包和个人包也写进日志，才能看出回收跑过、还回去多少。
            FileLogger.w(TAG, "keyboard hidden: rss ${before} -> ${residentMb()} MB")
        }
    }

    /** 进程常驻内存（MB），读 /proc/self/statm 的第二列；读不到返回 -1。 */
    private fun residentMb(): Long = runCatching {
        val pages = File("/proc/self/statm").readText().trim().split(' ')[1].toLong()
        pages * Os.sysconf(OsConstants._SC_PAGESIZE) / (1024 * 1024)
    }.getOrDefault(-1L)
}
