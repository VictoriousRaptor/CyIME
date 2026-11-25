package com.kingzcheung.xime.stats

import android.content.Context
import android.os.SystemClock
import com.kingzcheung.xime.settings.IncognitoState
import com.kingzcheung.xime.stats.TypingStatsData.Combo
import com.kingzcheung.xime.stats.TypingStatsData.Day
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 打字统计的记录入口。只在本机计数，不记录、不保存、不发送打过的任何文字。
 *
 * 打字路径上只做内存里的加法（一把不争用的锁，微秒级），不读写磁盘；
 * 键盘收起时才在后台线程写一次文件。无痕（手动无痕、密码框、不学习的输入框、剪贴板搜索）时什么都不记。
 */
object TypingStats {
    private const val FILE_NAME = "typing_stats.txt"
    const val PREF_ENABLED = "typing_stats_enabled"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "typing-stats").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    @Volatile private var enabled = true
    @Volatile private var loaded = false
    private var data = TypingStatsData()
    private var dirty = false

    // 速度：一段连续打字（间隔不超过 ACTIVE_GAP_MS）的字数和时长，停下来时结算最快纪录。
    private var lastTypedAt = 0L
    private var burstChars = 0L
    private var burstMs = 0L

    // 按输入方式分开记：按键和点选候选发生在上屏之前，先攒着，下一次上屏时记到那次上屏的输入方式上。
    private var pendingKeys = 0L
    private var pendingDeletes = 0L
    private var pendingPicks = 0L
    private var pendingFirstPicks = 0L

    /** 输入法服务和设置页启动时调用；读盘在后台线程。 */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        enabled = prefs(app).getBoolean(PREF_ENABLED, true)
        TouchStatsStore.init(app)
        io.execute { ensureLoaded() }
    }

    private fun prefs(context: Context) =
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)

    internal fun recording(): Boolean = enabled && appContext != null && !IncognitoState.active

    /** 屏幕键盘按下一个键（含删除键）。 */
    fun onKeyDown(key: String) {
        if (!recording()) return
        TouchStats.keyDown(key)
        val today = today()
        synchronized(lock) {
            data.add(today, Day.KEYS, 1)
            pendingKeys++
            if (key == "delete") { data.add(today, Day.DELETES, 1); pendingDeletes++ }
            dirty = true
        }
    }

    /**
     * 文字上屏。[softKeyboard] 为 false（接着实体键盘）时这些字不进码长。
     * 粘贴只记字数，不算打字量、不算速度。[combo] 是这次上屏的「语言 · 方案 · 布局」；语音不传时记成「语音」。
     */
    fun onCommit(text: String, isPaste: Boolean, source: TypingStatsData.Source, softKeyboard: Boolean = true,
        combo: TypingStatsCombo? = null) {
        if (!recording()) return
        if (softKeyboard && !isPaste) TouchStats.committed(combo?.id)
        val chars = TypingStatsText.countChars(text)
        if (chars == 0L) return
        val now = System.currentTimeMillis()
        val today = dayIndex(now)
        val elapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            dirty = true
            if (isPaste) { data.add(today, Day.PASTED, chars); clearPendingLocked(); return }
            data.add(today, Day.CHARS, chars)
            data.hours[hourOf(now)] += chars
            data.sources[source.ordinal] += chars
            val keyed = softKeyboard && source != TypingStatsData.Source.VOICE && source != TypingStatsData.Source.HANDWRITING
            if (keyed) data.add(today, Day.KEY_CHARS, chars)
            val c = (combo ?: if (source == TypingStatsData.Source.VOICE) TypingStatsCombo.VOICE else null)
                ?.let { data.combo(it.id, it.label) }
            if (c != null) {
                c.counts[Combo.CHARS] += chars
                data.markComboDay(c, today)
                if (keyed) {
                    c.counts[Combo.KEY_CHARS] += chars
                    c.counts[Combo.KEYS] += pendingKeys
                    c.counts[Combo.DELETES] += pendingDeletes
                }
                c.counts[Combo.PICKS] += pendingPicks
                c.counts[Combo.FIRST_PICKS] += pendingFirstPicks
            }
            clearPendingLocked()
            // 语音一次上屏一整句，不算打字速度。
            if (source == TypingStatsData.Source.VOICE) return
            val gap = elapsed - lastTypedAt
            if (lastTypedAt > 0 && gap in 0..TypingStatsData.ACTIVE_GAP_MS) {
                data.add(today, Day.ACTIVE_MS, gap)
                data.add(today, Day.ACTIVE_CHARS, chars)
                if (c != null) { c.counts[Combo.ACTIVE_MS] += gap; c.counts[Combo.ACTIVE_CHARS] += chars }
                burstMs += gap
                burstChars += chars
            } else {
                finishBurstLocked()
            }
            lastTypedAt = elapsed
        }
    }

    /** 点选了第 [index] 个候选；[correction] 是这个候选的纠错标记（没纠错为空）。 */
    fun onCandidatePicked(index: Int, correction: String) {
        if (!recording()) return
        TouchStats.picked(correction)
        val today = today()
        synchronized(lock) {
            data.recordPick(today, index, correction)
            pendingPicks++
            if (index == 0) pendingFirstPicks++
            dirty = true
        }
    }

    /** 选了联想词；只记字数。 */
    fun onAssociationPicked(word: String) {
        if (!recording()) return
        val chars = TypingStatsText.countChars(word)
        if (chars == 0L) return
        val today = today()
        synchronized(lock) { data.add(today, Day.ASSOC_CHARS, chars); dirty = true }
    }

    /** 键盘收起：结算这一段速度，后台写盘。 */
    fun flush() {
        TouchStatsStore.flush()
        zone = TimeZone.getDefault()
        synchronized(lock) {
            finishBurstLocked()
            lastTypedAt = 0
            clearPendingLocked()
            if (!dirty) return
        }
        io.execute { save() }
    }

    /** 给统计页：读盘（如还没读）后的一份拷贝。在后台线程调用。 */
    fun snapshot(context: Context): TypingStatsData {
        init(context)
        ensureLoaded()
        synchronized(lock) {
            val copy = data.copy()
            // 正在打的这一段也算进最快纪录，页面上不用等键盘收起。
            TypingStatsData.speedOf(burstChars, burstMs)?.let { if (burstMs >= MIN_BURST_MS && it > copy.bestSpeed) copy.bestSpeed = it }
            return copy
        }
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        enabled = value
        prefs(context).edit().putBoolean(PREF_ENABLED, value).apply()
    }

    /**
     * 清空统计（文件一起删）。只给测试用：页面上不提供重置，统计只增不减，数字才准（用户 11-01 要求）。
     */
    internal fun clear(context: Context) {
        init(context)
        synchronized(lock) {
            data = TypingStatsData()
            dirty = false
            lastTypedAt = 0; burstChars = 0; burstMs = 0
            clearPendingLocked()
        }
        io.execute { runCatching { file()?.delete() } }
    }

    fun today(): Int = dayIndex(System.currentTimeMillis())

    /** TimeZone.getDefault() 每次都复制一份；按键路径上用存下的这份，键盘收起时再换新（换了时区跟着变）。 */
    @Volatile private var zone: TimeZone = TimeZone.getDefault()

    fun dayIndex(millis: Long): Int {
        val local = millis + zone.getOffset(millis)
        return Math.floorDiv(local, 86_400_000L).toInt()
    }

    private fun hourOf(millis: Long): Int {
        val local = millis + zone.getOffset(millis)
        return (Math.floorMod(local, 86_400_000L) / 3_600_000L).toInt()
    }

    private const val MIN_BURST_MS = 20_000L
    private const val MIN_BURST_CHARS = 20L

    private fun clearPendingLocked() {
        pendingKeys = 0; pendingDeletes = 0; pendingPicks = 0; pendingFirstPicks = 0
    }

    private fun finishBurstLocked() {
        if (burstMs >= MIN_BURST_MS && burstChars >= MIN_BURST_CHARS) {
            TypingStatsData.speedOf(burstChars, burstMs)?.let { if (it > data.bestSpeed) data.bestSpeed = it }
        }
        burstChars = 0; burstMs = 0
    }

    private fun file(): File? = appContext?.filesDir?.let { File(it, FILE_NAME) }

    private val loadLock = Any()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val stored = runCatching { file()?.takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let { runCatching { TypingStatsData.decode(it) }.getOrNull() }
            synchronized(lock) {
                if (stored != null) data = stored.plus(data)
                loaded = true
            }
        }
    }

    private fun save() {
        ensureLoaded()
        val text = synchronized(lock) {
            if (!dirty) return
            dirty = false
            data.prune(today())
            data.encode()
        }
        val target = file() ?: return
        runCatching {
            // 先写临时文件再改名，写到一半断电也不会把旧统计弄坏。
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
    }
}
