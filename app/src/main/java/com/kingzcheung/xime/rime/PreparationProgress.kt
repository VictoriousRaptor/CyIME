package com.kingzcheung.xime.rime

import android.os.SystemClock
import com.kingzcheung.xime.platform.DeviceVendor
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 准备词库走到了哪一步（整个进程共用一份）。设置向导第 2 步以前只有一根不动的进度条和「首次使用可能需要几分钟」，
 * 用户卡住了不知道在干什么、卡在哪（11-20 小米 HyperOS 4 用户截图）。现在每一步、每个文件都报到这里，
 * 向导显示第几步、在做什么、等了多久；很久没有动静时向导提示超时，把这里的记录和正在干活的线程栈写进日志。
 * 应用启动时的预准备和向导用的是同一把锁，谁在干活都报到这一份，向导看到的就是真实在做的事。
 */
object PreparationProgress {
    enum class Step(val label: String) {
        COPY("复制安装包里的词库文件"),
        ENGINE("启动输入引擎"),
        COMPILE("编译词库"),
        DONE("完成"),
    }

    data class State(
        val step: Step? = null,
        /** 这一步里正在做的事，如「复制 rime_ice/41448.dict.yaml」「正在写 rime_ice.table.bin（38 MB）」。 */
        val detail: String = "",
        /** 编译时：已经写完的词典个数 / 这次启用的方案用到的词典个数；不知道时为 0。 */
        val done: Int = 0,
        val total: Int = 0,
        val stepStartedAt: Long = 0L,
        /** 最后一次有动静（换步、换文件、文件变大）的时间，超时按它算。 */
        val lastChangeAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile
    private var worker: Thread? = null

    fun enter(step: Step, detail: String = "") {
        val now = SystemClock.elapsedRealtime()
        worker = if (step == Step.DONE) null else Thread.currentThread()
        _state.value = State(step, detail, stepStartedAt = now, lastChangeAt = now)
        FileLogger.w(TAG, "Step ${step.name} ${detail}".trimEnd())
    }

    /**
     * 同一步里换了件事或有了进展；内容没变不算动静。干活的线程自己报时记下它（协程挂起后可能换了线程），
     * 超时时打它的栈；看 build 目录的观察线程报时 [fromWorker] 为 false。
     */
    fun touch(detail: String, done: Int = _state.value.done, total: Int = _state.value.total, fromWorker: Boolean = true) {
        val current = _state.value
        if (fromWorker && current.step != null && current.step != Step.DONE) worker = Thread.currentThread()
        if (current.detail == detail && current.done == done && current.total == total) return
        _state.value = current.copy(detail = detail, done = done, total = total, lastChangeAt = SystemClock.elapsedRealtime())
    }

    /** 向导判定超时时调用：把停在哪、干活的线程正在执行哪几行写进日志，导出日志后能直接看出卡在哪。 */
    fun logStall(stalledSeconds: Long) {
        val s = _state.value
        val stack = worker?.stackTrace?.take(STACK_FRAMES)?.joinToString("\n    at ").orEmpty()
        FileLogger.w(TAG, "Stalled ${stalledSeconds}s at ${s.step?.name} \"${s.detail}\" ${s.done}/${s.total}" +
            if (stack.isEmpty()) " (no worker thread)" else "\n    at $stack")
    }

    /**
     * 编译时看 build 目录：librime 不报进度，但它一个词典一个词典地写 *.table.bin、*.prism.bin。
     * 返回停止观察的函数。[dictionaries] 是这次启用的方案用到的词典名，算「第几个 / 共几个」。
     */
    fun watchBuild(buildDir: File, dictionaries: Set<String>, startedAt: Long = System.currentTimeMillis()): () -> Unit {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val thread = Thread({
            while (running.get()) {
                runCatching {
                    val (done, total) = buildCount(buildDir, dictionaries, startedAt)
                    touch(buildDetail(buildDir, startedAt), done, total, fromWorker = false)
                }
                try { Thread.sleep(1_000L) } catch (_: InterruptedException) { break }
            }
        }, "prepare-progress").also { it.isDaemon = true; it.start() }
        return { running.set(false); thread.interrupt() }
    }

    private fun buildDetail(buildDir: File, startedAt: Long): String {
        val newest = buildDir.listFiles().orEmpty().filter { it.isFile && it.lastModified() >= startedAt - CLOCK_SLACK_MS }
            .maxByOrNull { it.lastModified() } ?: return "正在检查哪些词库要重新编译"
        return "正在写 ${newest.name}（${sizeText(newest.length())}）"
    }

    private fun buildCount(buildDir: File, dictionaries: Set<String>, startedAt: Long): Pair<Int, Int> {
        if (dictionaries.isEmpty()) return 0 to 0
        val done = dictionaries.count { File(buildDir, "$it.table.bin").let { f -> f.isFile && f.lastModified() >= startedAt - CLOCK_SLACK_MS } }
        return done to dictionaries.size
    }

    /** 纯函数，可单测：方案文件里 translator 用的词典名（`dictionary: rime_ice`）。 */
    internal fun dictionariesIn(schemaYaml: String): Set<String> =
        DICTIONARY.findAll(schemaYaml).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toSet()

    fun enabledDictionaries(rimeDir: File, schemas: List<String>): Set<String> = schemas.flatMap { id ->
        runCatching { dictionariesIn(File(rimeDir, "$id.schema.yaml").readText()) }.getOrDefault(emptySet())
    }.toSet()

    internal fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    /** 纯函数，可单测：整体进度 0–1。编译这步按词典个数走，其他步只算到这一步开头。 */
    internal fun fraction(state: State): Float {
        val step = state.step ?: return 0f
        if (step == Step.DONE) return 1f
        val steps = Step.entries.size - 1
        val within = if (step == Step.COMPILE && state.total > 0) state.done.toFloat() / state.total else 0f
        return ((step.ordinal + within) / steps).coerceIn(0f, 1f)
    }

    /**
     * 纯函数，可单测：超时后告诉用户可以做什么。按停在哪一步和手机厂商给建议；
     * 小米、vivo、OPPO 这类系统会冻结或清理后台应用，向导切到系统设置去启用时准备可能被停住。
     */
    internal fun stallAdvice(step: Step?, vendor: DeviceVendor, lowStorage: Boolean): List<String> = buildList {
        add("别切到别的应用，留在这一页等：切到后台时系统可能会暂停 CyIME，准备就停在那里。")
        if (lowStorage || step == Step.COPY) add("看看手机剩余空间：少于 300 MB 时词库可能写不下，复制、编译都会停住，先清理空间。")
        when (vendor) {
            DeviceVendor.XIAOMI -> add("小米 / 红米（HyperOS、MIUI）：在系统设置 › 应用 › CyIME › 省电策略里选「无限制」，并允许自启动。")
            DeviceVendor.VIVO, DeviceVendor.OPPO, DeviceVendor.HUAWEI, DeviceVendor.HONOR ->
                add("在系统设置 › 应用 › CyIME 里把电池 / 耗电管理设为「允许后台运行」或「不限制」。")
            else -> add("在系统设置 › 应用 › CyIME › 电池里选「不限制」。")
        }
        add("然后从最近任务里划掉 CyIME 再打开，会重新开始准备。")
        add("还是不动，点「导出日志」发给我们，日志里有停在哪一步。")
    }

    private val DICTIONARY = Regex("""(?m)^[ \t]+dictionary:[ \t]*["']?([A-Za-z0-9_.\-]+)""")
    private const val TAG = "PreparationProgress"
    private const val STACK_FRAMES = 20
    /** 有的文件系统修改时间只到秒，开始时间往前放一点。 */
    private const val CLOCK_SLACK_MS = 2_000L
}
