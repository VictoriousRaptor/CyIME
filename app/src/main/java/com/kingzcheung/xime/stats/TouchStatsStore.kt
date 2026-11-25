package com.kingzcheung.xime.stats

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import com.kingzcheung.xime.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * 按键落点统计（[TouchStats]）在这台设备上的开关和存取：只有「装到我的设备」的包会打开，
 * 计数存在应用自己目录里的 touch_stats.txt（只有数字，见 [TouchStatsData]），键盘收起时在后台线程写一次。
 * 不上传；电脑要看时用开发者广播 `op=touch_stats` 导出（[export]），那个广播只有 adb 能发。
 */
internal object TouchStatsStore {
    private const val FILE_NAME = "touch_stats.txt"

    private val io by lazy { Executors.newSingleThreadExecutor { Thread(it, "touch-stats").apply { isDaemon = true } } }
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    private val loadLock = Any()

    /** [TypingStats.init] 里调。公开的包里什么都不做，[TouchStats] 一直是关的。 */
    fun init(context: Context) {
        if (!BuildConfig.DEV_TOOLS || appContext != null) return
        val app = context.applicationContext
        appContext = app
        TouchStats.clock = SystemClock::uptimeMillis
        // 横屏的键大小、手的姿势都不一样，分开记。
        TouchStats.boardSuffix = {
            if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "/land" else ""
        }
        // 跟着「打字统计」的开关，无痕（手动无痕、密码框、不学习的输入框）时不记。
        TouchStats.gate = TypingStats::recording
        io.execute { ensureLoaded() }
    }

    /** 键盘收起（[TypingStats.flush] 里调）：有新计数就在后台写盘。 */
    fun flush() {
        if (appContext == null) return
        if (TouchStats.hidden()) io.execute { save() }
    }

    /**
     * 把现在的计数（含还没写盘的）、打字统计、屏幕密度写到 [dir]，给电脑 adb pull。只有计数和内置的键名、输入方式 ID，
     * 没有打过的任何文字。返回一行结果。
     */
    fun export(context: Context, dir: File): String {
        if (!BuildConfig.DEV_TOOLS) return "touch_stats: off in this build"
        TypingStats.init(context)
        ensureLoaded()
        check(dir.isDirectory || dir.mkdirs()) { "cannot create ${dir.path}" }
        val touch = TouchStats.snapshot()
        File(dir, FILE_NAME).writeText(touch.encode())
        File(dir, "typing_stats.txt").writeText(TypingStats.snapshot(context).encode())
        val metrics = context.resources.displayMetrics
        File(dir, "device.txt").writeText(buildString {
            append("model=").append(Build.MODEL).append('\n')
            append("sdk=").append(com.kingzcheung.xime.platform.AndroidPlatform.sdk).append('\n')
            append("version=").append(BuildConfig.VERSION_NAME).append('\n')
            append("version_code=").append(BuildConfig.VERSION_CODE).append('\n')
            append("density=").append(metrics.density).append('\n')
            append("xdpi=").append(metrics.xdpi).append('\n')
            append("ydpi=").append(metrics.ydpi).append('\n')
            append("width_px=").append(metrics.widthPixels).append('\n')
            append("height_px=").append(metrics.heightPixels).append('\n')
            append("typing_stats_enabled=").append(TypingStats.isEnabled(context)).append('\n')
            append("exported_at_ms=").append(System.currentTimeMillis()).append('\n')
        })
        val presses = touch.boards.values.sumOf { it.counts[TouchStatsData.Count.CHAR_PRESSES] }
        return "touch_stats: ${dir.path} boards=${touch.boards.size} presses=$presses"
    }

    private fun file(): File? = appContext?.filesDir?.let { File(it, FILE_NAME) }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val stored = runCatching { file()?.takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let { runCatching { TouchStatsData.decode(it) }.getOrNull() }
            if (stored != null) TouchStats.merge(stored)
            loaded = true
        }
    }

    private fun save() {
        ensureLoaded()
        val text = TouchStats.takeText() ?: return
        val target = file() ?: return
        runCatching {
            // 先写临时文件再改名，写到一半断电也不会把旧统计弄坏。
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
    }
}
