package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock

data class RimeCandidate(
    val text: String,
    val comment: String,
    /** 纠错候选逐音节的“实际敲的=正确拼音”，非纠错为空；见 [CorrectionMarks]。 */
    val correction: String = "",
)

/**
 * 批量查询当前 composition 状态。
 *
 * 通过 JNI 一次性返回 input/preedit/commit/candidates/paging/ascii_mode，
 * 避免 updateUI 中多次独立 JNI 调用带来的固定开销。
 */
data class RimeComposition @JvmOverloads constructor(
    val input: String,
    val preedit: String,
    val committedText: String,
    val candidates: Array<RimeCandidate>,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    val isAsciiMode: Boolean,
    /** Engine composition revision; zero denotes no valid snapshot. */
    val engineRevision: Long = 0L,
) {
    internal var traceEventId: Int = 0
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeComposition) return false
        return input == other.input &&
                preedit == other.preedit &&
                committedText == other.committedText &&
                candidates.contentEquals(other.candidates) &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                engineRevision == other.engineRevision &&
                isAsciiMode == other.isAsciiMode
    }

    override fun hashCode(): Int {
        var result = input.hashCode()
        result = 31 * result + preedit.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + isAsciiMode.hashCode()
        return 31 * result + engineRevision.hashCode()
    }
}

data class RimeProcessResult @JvmOverloads constructor(
    val processed: Boolean,
    val committedText: String,
    val inputText: String,
    val preeditText: String,
    val candidates: Array<RimeCandidate>,
    val isAsciiMode: Boolean,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    /**
     * T9 左侧面板状态（格式 STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）。
     * 由 JNI 在 T9 会话活跃时填充，非 T9 场景为空字符串。
     */
    val t9PanelState: String = "",
    /**
     * T9 首音节候选（"pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1"）。
     * 由 JNI 一次计算，避免 Kotlin 侧重复取数。
     */
    val t9SyllableOptions: String = "",
    val engineRevision: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeProcessResult) return false
        return processed == other.processed &&
                committedText == other.committedText &&
                inputText == other.inputText &&
                preeditText == other.preeditText &&
                candidates.contentEquals(other.candidates) &&
                isAsciiMode == other.isAsciiMode &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                t9PanelState == other.t9PanelState &&
                t9SyllableOptions == other.t9SyllableOptions && engineRevision == other.engineRevision
    }

    override fun hashCode(): Int {
        var result = processed.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + inputText.hashCode()
        result = 31 * result + preeditText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + isAsciiMode.hashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + t9PanelState.hashCode()
        result = 31 * result + t9SyllableOptions.hashCode()
        return 31 * result + engineRevision.hashCode()
    }
}

/** 将一次按键得到的完整结果转为 RimeComposition，供 T9 控制器复用，避免重复 JNI。 */
fun RimeProcessResult.toComposition(): RimeComposition {
    return RimeComposition(
        input = inputText,
        preedit = preeditText,
        committedText = committedText,
        candidates = candidates,
        hasNextPage = hasNextPage,
        hasPrevPage = hasPrevPage,
        isAsciiMode = isAsciiMode,
        engineRevision = engineRevision,
    )
}

class RimeEngine {

    companion object {
        private const val TAG = "RimeEngine"
        private var instance: RimeEngine? = null
        private var deploymentCallback: ((Boolean, String) -> Unit)? = null

        /** 全局 Rime 引擎锁 — 所有 native 调用必须通过此锁同步 */
        val rimeLock = ReentrantLock(true)

        init {
            System.loadLibrary("rime_jni")
        }

        fun getInstance(): RimeEngine {
            return instance ?: synchronized(this) {
                instance ?: RimeEngine().also { instance = it }
            }
        }

        fun isInitialized(): Boolean = instance?.isInitialized ?: false

        /**
         * 检查指定的 Rime 模块是否已注册（用于验证插件集成）
         */
        fun isModuleRegistered(moduleName: String): Boolean {
            val engine = instance ?: return false
            if (!engine.isInitialized) return false
            return engine.nativeIsModuleRegistered(moduleName)
        }

        fun setDeploymentCallback(callback: (isDeploying: Boolean, message: String) -> Unit) {
            deploymentCallback = callback
        }
    }

    @Volatile
    private var isInitialized = false
    private val initLock = Any()

    /**
     * 会话被换掉（新建会话、部署、维护后重载、初始化/销毁）就加一。[schemaAppliedGeneration] 等于它，
     * 说明眼下这个会话已经完整切过一次方案、之后没被换过：输入法服务重建时不必再切一遍同一方案。
     * 重切同一方案会重建翻译器和词典，内存紧、页缓存被回收时要 20 多秒（平板 11-05..11-08 日志）。
     */
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var schemaAppliedGeneration = -1L
    private fun sessionReplaced() { sessionGeneration.incrementAndGet() }

    /** 当前会话已完整切过方案时返回它的代号；没有会话、或会话之后被换过时返回 null。 */
    internal fun liveSchemaGeneration(): Long? {
        val generation = sessionGeneration.get()
        return generation.takeIf { isInitialized && nativeHasSession() && schemaAppliedGeneration == it }
    }

    private fun createSession(): Boolean = nativeCreateSession().also { if (it) sessionReplaced() }
    @Volatile
    private var userDataDir: String = ""
    private var sharedDataDir: String = ""
    @Volatile private var mergedAlphabet: Pair<String, String>? = null

    private fun mergedSpellerAlphabet(): String {
        val schema = nativeGetCurrentSchema().orEmpty()
        return mergedAlphabet?.takeIf { it.first == schema }?.second ?: run {
            nativeGetSchemaString(schema, "speller/alphabet").orEmpty().also { mergedAlphabet = schema to it }
        }
    }

    /** 按键队列里先读一次当前方案的字母表，屏幕键盘线程随后可以不加锁地查 [spellerAlphabetHas]。 */
    internal fun warmSpellerAlphabet() { locked { mergedSpellerAlphabet() } }

    /**
     * 不加锁：当前方案（[schemaId]）的 speller/alphabet 里有没有 [c]。还没读过（刚部署、刚重载）时返回 false，
     * 照旧按标点处理；按键队列下一次 [warmSpellerAlphabet] 或切换方案后就有了（#87）。
     */
    internal fun spellerAlphabetHas(schemaId: String, c: Char): Boolean =
        mergedAlphabet?.takeIf { it.first == schemaId }?.second?.contains(c) == true

    /** 不加锁：[schemaId] 的字母表已经读过（读到空串也算）。 */
    internal fun spellerAlphabetKnown(schemaId: String): Boolean = mergedAlphabet?.first == schemaId

    internal fun mergedLetterKeyCode(letter: Char): Int = locked {
        // An intentionally customized old schema must remain usable and must not be overwritten.
        if (letter.uppercaseChar() in mergedSpellerAlphabet()) letter.uppercaseChar().code else letter.lowercaseChar().code
    }

    /**
     * 管理路径（初始化/部署/会话创建/方案切换等）：阻塞等待锁。
     * 这类调用在后台线程执行，等待部署/编译完成是预期行为。
     */
    private inline fun <T> locked(block: () -> T): T {
        val requested = System.nanoTime()
        rimeLock.lock()
        val acquired = System.nanoTime()
        try {
            RimeCommandContext.check()
            return block()
        } finally {
            // 可重入锁嵌套时只记最外层，避免同一段原生时间重复计入。
            val outermost = rimeLock.holdCount == 1
            rimeLock.unlock()
            val heldNanos = System.nanoTime() - acquired
            if (outermost) EngineTimeMeter.add(heldNanos)
            val waitMs = (acquired - requested) / 1_000_000
            val heldMs = heldNanos / 1_000_000
            if (waitMs >= 100 || heldMs >= 100) {
                FileLogger.w("InputLatency", "engine_wait_ms=$waitMs engine_held_ms=$heldMs")
            }
        }
    }

    /**
     * 输入/查询路径：非阻塞获取锁。拿不到锁（如全量部署持锁中）立即返回
     * [defaultValue] 而不等待，避免主线程 UI（updateUI → getComposition 等）
     * 被 20+ 秒的部署阻塞导致 ANR。拿不到锁时不进入 native，并发安全。
     */
    private inline fun <T> tryLocked(defaultValue: T, block: () -> T): T {
        // Queued input must wait for ownership rather than interpreting 'busy'
        // as an empty result. Unqueued UI probes keep their nonblocking contract.
        if (RimeCommandContext.validity.get() != null &&
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return locked(block)
        if (!rimeLock.tryLock()) return defaultValue
        try {
            RimeCommandContext.check()
            return block()
        } finally {
            rimeLock.unlock()
        }
    }

    private val candidateRevision = java.util.concurrent.atomic.AtomicLong(1L)

    /**
     * 长按整句点词重选时挪到词后面的光标位置和当时的编码；没在重选时为 -1。
     * 只在锁内读写。见 [resumeCaretAfterPartialReselect]。
     */
    private var reselectCaret = -1
    private var reselectInput = ""
    internal fun currentCandidateRevision(): Long = candidateRevision.get()

    internal fun glidePreviewRevision(): Long = candidateRevision.get()

    /** Preview a glide in an isolated Rime session without mutating the active composition. */
    internal fun glidePreview(code: String, revision: Long): RimeProcessResult? = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsAsciiMode() || candidateRevision.get() != revision) return@locked null
        val values = nativeGlidePreview(code)
        if (values.size < 3 || candidateRevision.get() != revision) return@locked null
        val candidates = values.drop(3).chunked(2).mapNotNull { pair ->
            if (pair.size == 2) RimeCandidate(pair[0], pair[1]) else null
        }.toTypedArray()
        RimeProcessResult(true, "", values[0], displayPreedit(values[0], values[1], candidates.firstOrNull()),
            candidates, false, values[2] == "1", false, engineRevision = revision)
    }
    private external fun nativeGlidePreview(code: String): Array<String>

    /** A successful lock acquisition starts a mutation, invalidating previously published candidates. */
    private inline fun <T> mutateLocked(block: () -> T): T = locked {
        candidateRevision.incrementAndGet()
        block()
    }

    private inline fun <T> tryMutating(defaultValue: T, block: () -> T): T = tryLocked(defaultValue) {
        candidateRevision.incrementAndGet()
        block()
    }

    private fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        deploymentCallback?.invoke(isDeploying, message)
    }

    fun initialize(userDataDir: String, sharedDataDir: String) {
        if (!isInitialized) {
            synchronized(initLock) {
                if (!isInitialized) {
                    try {
                        candidateRevision.incrementAndGet()
                        phraseReadings = null
                        mergedEditReading = null
                        this.userDataDir = userDataDir
                        this.sharedDataDir = sharedDataDir
                        nativeInstallSignalHandler(userDataDir)
                        notifyDeploymentStatus(true, "正在加载输入法引擎...")
                        nativeInitialize(userDataDir, sharedDataDir)
                        mergedAlphabet = null
                        sessionReplaced()
                        isInitialized = true

                        // 部署后主动触发：为已部署目录中所有 T9 方案打九键特有补丁
                        // （t9 四要素 + 个人词库 packs 兜底），保证第三方九键方案
                        // 首次使用前 custom.yaml 健康、用户词典生效。
                        ensureT9SchemaPatchesForDeployedSchemas(userDataDir)

                        // startup 只初始化引擎，不创建 session
                        // session 在第一次使用时延迟创建（ensureSession）
                        // 部署在后台异步运行，不阻塞

                        notifyDeploymentStatus(false, "")
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Error during Rime initialization", e)
                        notifyDeploymentStatus(false, "初始化失败")
                    }
                }
            }
        }
    }

    fun ensureSession(timeoutMs: Long = 60000L): Boolean {
        if (!isInitialized) return false

        // Quick check without lock — native calls are thread-safe reads
        if (nativeHasSession() && getAvailableSchemas().isNotEmpty()) return true

        // 等待编译完成（不持有 rimeLock，避免阻塞主线程 UI 操作）
        var waited = 0L
        while (nativeIsMaintaining() && waited < timeoutMs) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return false
            }
            waited += 1000
        }

        locked {
            waited = 0L
            while (waited < timeoutMs) {
                if (!nativeHasSession()) {
                    createSession()
                }
                if (getAvailableSchemas().isNotEmpty()) {
                    return true
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return false
                }
                waited += 1000
            }
            FileLogger.w(TAG, "ensureSession: schemas not available after ${timeoutMs}ms, deployment may still be running")
            return false
        }
    }

    fun isMaintaining(): Boolean {
        return nativeIsMaintaining()
    }

    /** 引擎是否繁忙（维护中或 rimeLock 被占用）：非阻塞探测，供 UI 立即反馈使用。 */
    fun isEngineBusy(): Boolean {
        return nativeIsMaintaining() || rimeLock.isLocked
    }

    fun getCurrentSchema(): String {
        if (!nativeHasSession()) return ""
        return tryLocked("") {
            nativeGetCurrentSchema() ?: ""
        }
    }

    fun processKey(keycode: Int, mask: Int): Boolean {
        if (!isInitialized) return false
        return tryMutating(false) {
            if (!nativeHasSession() && !createSession()) return@tryMutating false
            enforceAsciiWidth()
            nativeProcessKey(keycode, mask)
        }
    }

    fun processKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        return tryMutating(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) {
            if (!nativeHasSession() && !createSession())
                return@tryMutating RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
            enforceAsciiWidth()
            withDisplayPreedit(nativeProcessKeyAndGetResult(keycode, mask))
        }
    }

    /** 后台按键队列必须等待引擎锁，不能把短暂繁忙伪装成未处理并直接上屏字母。 */
    @androidx.annotation.WorkerThread
    fun processQueuedKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult = locked {
        processKeyAndGetResult(keycode, mask)
    }

    /** Return the state after all deletion cleanup, with the revision that can actually be published. */
    internal fun deleteCompositionAndGetResult(): RimeProcessResult = locked { deleteCompositionKey() }

    /**
     * [deleteCompositionAndGetResult] for the keyboard's own fields (clipboard search, tool panel, new pin), which
     * route keys on Main: skipped while the engine is busy, like [clearComposition].
     */
    internal fun tryDeleteCompositionAndGetResult(): RimeProcessResult =
        tryLocked(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) { deleteCompositionKey() }

    private fun deleteCompositionKey(): RimeProcessResult = backspaceComposition(
        backspace = { processKeyAndGetResult(0xff08, 0) },
        clear = ::clearComposition,
        reread = { getProcessResult(it.processed) },
    )

    /** Called on the serial input executor; properties affect the next native query only. */
    internal fun setNeighborMap(encoded: String) = locked { nativeSetNeighborMap(encoded) }

    fun getProcessResult(processed: Boolean): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        // 必须持 rimeLock：nativeGetProcessResult 内部 RimeGetContext → Menu::Prepare
        // 会惰性生成候选页（遍历 translations），与 t9FlushRimeInput（set_input → Reset，
        // 释放旧 translations）并发会导致悬空 → ScriptTranslation::Peek 空指针崩溃。
        // 部署/编译持锁期间拿不到锁直接返回空结果（不进 native），避免阻塞调用线程。
        return tryLocked(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) {
            withDisplayPreedit(nativeGetProcessResult(processed))
        }
    }

    fun getCandidates(): Array<String> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            nativeGetCandidates() ?: emptyArray()
        }
    }

    fun getCandidatesWithComments(): Array<RimeCandidate> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            val rawCandidates = nativeGetCandidatesWithComments() ?: emptyArray()
            rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
        }
    }

    /**
     * 跨页获取整个候选列表（与引擎分页无关），供候选展开页本地分页使用。
     * @param maxCount 收集上限，防御超大列表
     */
    fun getAllCandidates(maxCount: Int = 500): Array<RimeCandidate> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            val rawCandidates = nativeGetAllCandidates(maxCount) ?: emptyArray()
            rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
        }
    }

    /** Explicit Debug diagnostics only; never called by the keyboard render path. */
    internal fun inspectCandidates(limit: Int = 100): Array<Array<String>> = locked {
        check(com.kingzcheung.xime.BuildConfig.DEBUG)
        if (!nativeHasSession()) emptyArray() else nativeInspectCandidates(limit.coerceIn(0, 500))
    }
    private external fun nativeInspectCandidates(limit: Int): Array<Array<String>>

    internal fun conversionPreview(): String = locked { nativeConversionPreview() }
    internal fun highlightCandidate(index: Int): Boolean = locked { nativeHighlightCandidate(index) }
    private external fun nativeConversionPreview(): String
    private external fun nativeHighlightCandidate(index: Int): Boolean

    private var phraseReadings: Map<Pair<String, String>, String>? = null
    private var mergedEditReading: Pair<String, String>? = null

    private fun displayPreedit(input: String, preedit: String, top: RimeCandidate?): String {
        val spelling = top?.comment.orEmpty()
        val schema = nativeGetCurrentSchema().orEmpty()
        val profile = com.kingzcheung.xime.settings.InputProfiles.describe(schema)
        if (profile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.T9 &&
            profile.scheme == com.kingzcheung.xime.settings.InputScheme.PINYIN &&
            top != null && input.isNotEmpty() && preedit == input && spelling == input && input.all { it in '2'..'9' }) {
            val readings = phraseReadings ?: com.kingzcheung.xime.settings.PersonalDictManager
                .t9PhraseReadings(File(userDataDir)).also { phraseReadings = it }
            readings[top.text to input]?.let { return it }
        }
        // A nine-key caret inside the code leaves the raw keys after it in Rime's preedit.
        t9CaretReading?.let { (code, reading) ->
            if (code == input && reading.isNotEmpty() && preedit != reading &&
                (nativePinyinEditSnapshot().getOrNull(1)?.toIntOrNull() ?: input.length) < input.length) return reading
        }
        if (doublePinyinKeysPreedit && com.kingzcheung.xime.settings.DoublePinyinSchemes.isT9(schema)) return t9DoublePinyinPreedit(input, preedit, spelling, schema)
        val groups = if (profile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14) merged14Groups else
            com.kingzcheung.xime.settings.CustomKeyboardLayouts.forSchema(schema)?.mergedGroups.orEmpty()
        if (groups.none { it.length > 1 } || nativeIsAsciiMode()) return preedit
        val snapshot = nativePinyinEditSnapshot()
        val confirmed = snapshot.getOrNull(2)?.toIntOrNull()?.coerceIn(0, input.length) ?: 0
        val prefix = snapshot.getOrNull(3).orEmpty()
        // 14 键双拼的首选读音是全拼，换成这个方案的双拼键才能和合并编码一一对上。
        val reading = if (profile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN) doublePinyinReading(spelling, schema) else spelling
        val caret = snapshot.getOrNull(1)?.toIntOrNull() ?: input.length
        // At an interior caret Rime translates the prefix and echoes its physical keys for the tail.
        if (mergedEditReading?.first != input || caret !in confirmed until input.length) mergedEditReading = null
        val editReading = mergedEditReading?.second
        return prefix + merged14Preedit(input.drop(confirmed), preedit.removePrefix(prefix), editReading ?: reading, groups)
    }

    /**
     * 「双拼编码显示全拼」关闭时显示双拼键：输入法服务按设置写入。
     * 双拼九键在这里把显示的读音换成双拼键（[t9DoublePinyinKeys]）；26 键由方案补丁清空编码显示规则，不经过这里。
     */
    @Volatile var doublePinyinKeysPreedit = false

    /** 已选定的中文原样留着，后面的数字按首选读音换成双拼键；换不了时照原来显示。 */
    private fun t9DoublePinyinPreedit(input: String, preedit: String, spelling: String, schema: String): String {
        val scheme = com.kingzcheung.xime.settings.DoublePinyinSchemes.find(schema) ?: return preedit
        if (nativeIsAsciiMode()) return preedit
        val snapshot = nativePinyinEditSnapshot()
        val confirmed = snapshot.getOrNull(2)?.toIntOrNull()?.coerceIn(0, input.length) ?: 0
        val prefix = snapshot.getOrNull(3).orEmpty()
        if (!preedit.startsWith(prefix) || preedit.removePrefix(prefix).any { it.code > 0x7f }) return preedit
        return t9DoublePinyinKeys(input.drop(confirmed), spelling, scheme)?.let { prefix + it } ?: preedit
    }

    private fun withDisplayPreedit(result: RimeProcessResult): RimeProcessResult = result.copy(
        preeditText = displayPreedit(result.inputText, result.preeditText, result.candidates.firstOrNull()),
        engineRevision = candidateRevision.get()).also { noteCommit(it.committedText) }

    /** For destructive routing: null means busy/unavailable, never an empty composition. */
    internal fun compositionActiveForDeletion(): Boolean? {
        if (!isInitialized) return null
        return tryLocked<Boolean?>(null) {
            if (nativeIsMaintaining()) null
            else if (!nativeHasSession()) false
            else !nativeGetInput().isNullOrEmpty() || !nativeT9GetRemainingDigits().isNullOrEmpty()
        }
    }

    fun getInput(): String {
        return tryLocked("") {
            nativeGetInput() ?: ""
        }
    }

    /**
     * 批量查询当前 composition 全部信息。
     *
     * 一次 JNI 调用返回 input、preedit、committedText、candidates、分页和 ascii_mode，
     * 是 T9 路径 updateUI 的首选查询接口，可替代多次独立 JNI 调用。
     */
    fun getComposition(): RimeComposition {
        return tryLocked(RimeComposition("", "", "", emptyArray(), false, false, false)) {
            nativeGetComposition().let { result -> result.copy(preedit = displayPreedit(
                result.input, result.preedit, result.candidates.firstOrNull()), engineRevision = candidateRevision.get()) }
                .also { noteCommit(it.committedText) }
        }
    }

    fun selectCandidate(index: Int): Boolean {
        return tryMutating(false) {
            if (!nativeHasSession()) return@tryMutating false
            nativeSelectCandidate(index)
        }
    }

    /**
     * 按候选列表全局索引选词（跨页，与 getAllCandidates 遍历顺序一致）。
     * 候选展开页本地分页点选走此接口：本地页内索引 + 页偏移 = 全局索引。
     */
    fun selectCandidateByGlobalIndex(index: Int): Boolean {
        return tryMutating(false) {
            if (!nativeHasSession()) return@tryMutating false
            nativeSelectCandidateByGlobalIndex(index)
        }
    }

    /**
     * 删除当前页候选（长按候选栏删除自造词）。
     * 标准 C API delete_candidate_on_current_page：librime 对用户词典词条
     * 执行 tombstone 标记（UpdateEntry -1），键盘无关（T9/全键盘通用）。
     * @param index 当前页内的候选索引
     */
    fun deleteCandidateOnCurrentPage(index: Int): Boolean {
        return tryMutating(false) {
            if (!nativeHasSession()) return@tryMutating false
            nativeDeleteCandidateOnCurrentPage(index)
        }
    }

    /**
     * 按候选列表全局索引删除（跨页，与 getAllCandidates 遍历顺序一致），
     * 供候选展开页本地分页长按删除自造词。
     */
    fun deleteCandidateByGlobalIndex(index: Int): Boolean {
        return tryMutating(false) {
            if (!nativeHasSession()) return@tryMutating false
            nativeDeleteCandidateByGlobalIndex(index)
        }
    }

    fun pageDown(): Boolean {
        return tryMutating(false) {
            if (!nativeHasSession()) return@tryMutating false
            nativePageDown()
        }
    }

    fun pageUp(): Boolean {
        return tryMutating(false) {
            if (!nativeHasSession()) return@tryMutating false
            nativePageUp()
        }
    }

    fun hasNextPage(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeHasNextPage()
        }
    }

    fun hasPrevPage(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeHasPrevPage()
        }
    }

    fun commit(): String {
        return tryMutating("") {
            (nativeCommit() ?: "").also(::noteCommit)
        }
    }

    /** 用户取消输入从后台按键队列调用：等待引擎锁，不能把清空操作静默丢掉。 */
    internal fun clearQueuedComposition() = mutateLocked {
        if (nativeHasSession()) nativeClearComposition()
    }

    fun clearComposition() {
        if (!nativeHasSession()) return
        tryMutating(Unit) {
            nativeClearComposition()
        }
    }

    /**
     * Ends the composition from the input FIFO. T9 keeps its own digit buffer and segments beside
     * Rime's input, and after a partial choice it no longer resets them from an empty Rime input:
     * clearing only Rime brought the old digits back with the next key (至少 chosen from
     * zhishaoyao, Enter, then 64 showed zhi'qian'yao'ni). [t9] clears that state too.
     */
    internal fun endQueuedComposition(t9: Boolean) = mutateLocked {
        if (!nativeHasSession()) return@mutateLocked
        if (t9 && isInitialized) nativeT9ClearComposition(1)
        nativeClearComposition()
    }

    /** [endQueuedComposition] for Main-thread callers: skipped, like [clearComposition], while the engine is busy. */
    fun endComposition(t9: Boolean) {
        if (!nativeHasSession()) return
        tryMutating(Unit) {
            if (t9 && isInitialized) nativeT9ClearComposition(1)
            nativeClearComposition()
        }
    }

    /**
     * 设置 RIME 引擎的输入字符串。
     *
     * 一次 JNI 调用完成整个输入设置，替代逐字符 processKey。
     * 调用后引擎会重新执行完整的处理管线（Speller → Segmentor → Translator）。
     * 支持分隔符 '，如 setInput("ji'he") 会告知 RIME 音节边界。
     *
     * @param input 拼音或数字字符串，如 "zhongguo" 或 "54482"
     * @return 是否设置成功
     */
    fun setInput(input: String): Boolean {
        if (!isInitialized) return false
        return tryMutating(false) {
            if (!nativeHasSession() && !createSession()) return@tryMutating false
            val japanese = !nativeIsAsciiMode() && com.kingzcheung.xime.settings.InputProfiles
                .describe(nativeGetCurrentSchema().orEmpty()).capabilities.japaneseConversion
            nativeSetInput(if (japanese) canonicalJapaneseRomaji(input) else input)
        }
    }

    /** Snapshot/apply run on the existing input FIFO, under one engine lock. */
    internal fun readPinyinEditSnapshot(): Array<String> = locked {
        if (nativeHasSession()) nativePinyinEditSnapshot().also { snapshot ->
            if (snapshot.size > 4) snapshot[4] = displayPreedit(snapshot[0], snapshot[4],
                nativeGetCandidatesWithComments()?.firstOrNull()?.let { RimeCandidate(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) })
        } else emptyArray()
    }

    /** A layout binding doesn't tell us whether the active processor owns a separate digit buffer. */
    internal fun readDeleteDragInput(schema: String): Pair<Array<String>, String>? = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining() || nativeIsAsciiMode() ||
            nativeGetCurrentSchema() != schema) return@locked null
        nativePinyinEditSnapshot() to nativeT9GetRemainingDigits().orEmpty()
    }

    /** Walk the raw nine-key tail with real keys so the next ordinary Backspace retains its undo history. */
    internal fun moveDeleteDragDigits(expected: String, target: String, schema: String, current: () -> Boolean): Boolean =
        mutateLocked {
            if (!current() || !nativeHasSession() || nativeIsAsciiMode() || nativeGetCurrentSchema() != schema ||
                nativeGetInput().orEmpty() != expected || nativeT9GetRemainingDigits().orEmpty() != expected ||
                !(expected.startsWith(target) || target.startsWith(expected))) return@mutateLocked false
            var length = expected.length
            while (length != target.length) {
                val key = if (length > target.length) { length--; 0xff08 } else target[length++].code
                if (!current() || !nativeProcessKey(key, 0)) return@mutateLocked false
                nativeT9FlushRimeInput()
            }
            nativeGetInput().orEmpty() == target && nativeT9GetRemainingDigits().orEmpty() == target
        }

    /** Queried only while the expanded reading rail is visible, off the UI thread. */
    internal fun readPinyinSyllableChoices(expectedRevision: Long, merged14: Boolean,
        isStillCurrent: () -> Boolean = { true }): PinyinSyllableChoices? = locked {
        if (!isInitialized || !nativeHasSession() || !isStillCurrent() || nativeIsAsciiMode() ||
            candidateRevision.get() != expectedRevision) return@locked null
        val snapshot = nativePinyinEditSnapshot()
        val input = snapshot.getOrNull(0).orEmpty()
        val confirmed = snapshot.getOrNull(2)?.toIntOrNull() ?: return@locked null
        if (input.isEmpty() || confirmed !in 0..input.length) return@locked null
        val group = if (merged14)
            MergedLetterInput.singleKeyGroup(input, snapshot.getOrNull(1)?.toIntOrNull(), confirmed)
                ?.takeIf { pair ->
                    val alphabet = mergedSpellerAlphabet()
                    pair.first().uppercaseChar() in alphabet && pair.all { it in alphabet }
                }
        else null
        val options = if (group == null) parsePinyinSyllableOptions(nativePinyinSyllableOptions(), input, confirmed)
            else emptyList()
        if (group == null && options.isEmpty()) return@locked null
        PinyinSyllableChoices(0L, nativeGetCurrentSchema().orEmpty(), expectedRevision, input,
            confirmed, snapshot.getOrNull(3).orEmpty(), options, group,
            chosen = if (group == null) nativePinyinSyllablesChosen() else 0)
    }

    /** Takes back the last chosen syllable; null when none is chosen or the choices are stale. */
    internal fun undoPinyinSyllable(owner: PinyinSyllableChoices,
        isStillCurrent: () -> Boolean = { true }): RimeProcessResult? = locked {
        if (!isInitialized || !nativeHasSession() || !isStillCurrent() || nativeIsAsciiMode() ||
            owner.letterGroup != null) return@locked null
        val before = nativePinyinEditSnapshot()
        if (!owner.matches(nativeGetCurrentSchema().orEmpty(), candidateRevision.get(),
                before.getOrNull(0).orEmpty(), before.getOrNull(2)?.toIntOrNull() ?: -1,
                before.getOrNull(3).orEmpty())) return@locked null
        editPinyinAndReadState {
            if (!nativeUndoPinyinSyllable(owner.inputText, owner.schemaId)) return@editPinyinAndReadState false
            candidateRevision.incrementAndGet()
            true
        }
    }

    /** Native graph restriction handles shuangpin collisions and xian versus xi+an. */
    internal fun selectPinyinSyllable(owner: PinyinSyllableChoices, index: Int,
        isStillCurrent: () -> Boolean = { true }): RimeProcessResult? = locked {
        if (!isInitialized || !nativeHasSession() || !isStillCurrent() || nativeIsAsciiMode()) return@locked null
        val before = nativePinyinEditSnapshot()
        if (!owner.matches(nativeGetCurrentSchema().orEmpty(), candidateRevision.get(),
                before.getOrNull(0).orEmpty(), before.getOrNull(2)?.toIntOrNull() ?: -1,
                before.getOrNull(3).orEmpty())) return@locked null
        val group = owner.letterGroup
        editPinyinAndReadState {
            if (group != null) {
                if (MergedLetterInput.singleKeyGroup(owner.inputText, before.getOrNull(1)?.toIntOrNull(),
                        owner.protectedLength) != group) return@editPinyinAndReadState false
                val letter = group.getOrNull(index)?.toString() ?: return@editPinyinAndReadState false
                if (letter == owner.inputText) return@editPinyinAndReadState true
                if (!nativeSetInput(letter)) return@editPinyinAndReadState false
            } else {
                val option = owner.options.getOrNull(index) ?: return@editPinyinAndReadState false
                if (!nativeSelectPinyinSyllable(owner.inputText, owner.schemaId, option.start, option.end,
                        option.syllableId)) return@editPinyinAndReadState false
            }
            candidateRevision.incrementAndGet()
            true
        }
    }

    internal fun applyPinyinEdit(expectedInput: String, newInput: String, caret: Int,
        protectedLength: Int = 0, protectedText: String = "", expectedSchema: String? = null,
        visibleReading: String? = null,
        isStillCurrent: () -> Boolean = { true },
    ): Boolean = mutateLocked {
        if (!nativeHasSession() || !isStillCurrent() || nativeIsAsciiMode() ||
            (expectedSchema != null && nativeGetCurrentSchema() != expectedSchema) ||
            nativeGetInput().orEmpty() != expectedInput) return@mutateLocked false
        val snapshot = nativePinyinEditSnapshot()
        if (snapshot.getOrNull(2)?.toIntOrNull() != protectedLength || snapshot.getOrNull(3).orEmpty() != protectedText) return@mutateLocked false
        // A cursor step must not rebuild unchanged code or discard confirmed segments.
        if (newInput != expectedInput && !nativeSetInput(newInput)) return@mutateLocked false
        nativeSetCaret(caret.coerceIn(0, newInput.length))
        mergedEditReading = visibleReading?.takeIf { caret < newInput.length }?.let { newInput to it }
        true
    }

    /**
     * Nine-key code caret after [keys] code keys: candidates then cover only the code before it,
     * as with full pinyin. Only the caret moves, so no ambiguous reading is locked; the reading of
     * the whole code is kept for display, since Rime shows the keys after the caret as digits.
     */
    internal fun placeT9CodeCaret(keys: Int, expectedInput: String? = null, expectedSchema: String? = null,
        isStillCurrent: () -> Boolean = { true },
    ): Boolean = mutateLocked {
        if (!nativeHasSession() || !isStillCurrent() || nativeIsAsciiMode() ||
            (expectedSchema != null && nativeGetCurrentSchema() != expectedSchema)) return@mutateLocked false
        val input = nativeGetInput().orEmpty()
        if (expectedInput != null && input != expectedInput) return@mutateLocked false
        val snapshot = nativePinyinEditSnapshot()
        val caret = snapshot.getOrNull(1)?.toIntOrNull() ?: input.length
        if (caret >= input.length) t9CaretReading = input to snapshot.getOrNull(4).orEmpty()
        val target = offsetAfterCodeKeys(input, keys)
        if (target != caret) {
            candidateRevision.incrementAndGet()
            nativeSetCaret(target)
        }
        true
    }

    /** Keys left before the code caret after a partial nine-key choice; -1 when it is at the end. */
    @Volatile private var t9CaretKeysAfterChoice = -1

    /** After the remaining keys of a partial choice are set, put back the caret the choice did not reach. */
    internal fun restoreT9CaretAfterChoice(remaining: String) {
        val keys = t9CaretKeysAfterChoice
        val reading = t9ReadingAfterChoice
        t9CaretKeysAfterChoice = -1
        t9ReadingAfterChoice = ""
        if (keys > 0 && keys < codeKeysBefore(remaining, remaining.length) && placeT9CodeCaret(keys, remaining) &&
            reading.count { it != ' ' && it != '\'' } == codeKeysBefore(remaining, remaining.length))
            t9CaretReading = remaining to reading
    }

    @Volatile private var t9ReadingAfterChoice = ""

    /** Whole-code reading of the nine-key code whose caret [placeT9CodeCaret] moved. */
    @Volatile private var t9CaretReading: Pair<String, String>? = null

    /** 实体键盘左右键：编码内移动一个字母；已在该方向的尽头时返回 null，不绕回。 */
    internal fun moveCompositionCaret(delta: Int): RimeProcessResult? = editPinyinAndReadState {
        if (!isInitialized || !nativeHasSession() || nativeIsAsciiMode()) return@editPinyinAndReadState false
        val snapshot = nativePinyinEditSnapshot()
        val target = compositionCaretStep(snapshot.getOrNull(0).orEmpty(),
            snapshot.getOrNull(1)?.toIntOrNull() ?: return@editPinyinAndReadState false,
            snapshot.getOrNull(2)?.toIntOrNull() ?: 0, delta) ?: return@editPinyinAndReadState false
        candidateRevision.incrementAndGet()
        nativeSetCaret(target)
        true
    }

    /** The editor changes composition only: snapshot it in the same lock, never consume a commit. */
    internal fun editPinyinAndReadState(apply: () -> Boolean): RimeProcessResult? = locked {
        if (!apply()) return@locked null
        val snapshot = nativePinyinEditSnapshot()
        val candidates = nativeGetCandidatesWithComments().orEmpty().map { pair ->
            RimeCandidate(pair.getOrElse(0) { "" }, pair.getOrElse(1) { "" })
        }.toTypedArray()
        RimeProcessResult(
            processed = true, committedText = "", inputText = snapshot.getOrNull(0).orEmpty(),
            preeditText = displayPreedit(snapshot.getOrNull(0).orEmpty(), snapshot.getOrNull(4).orEmpty(), candidates.firstOrNull()), candidates = candidates,
            isAsciiMode = nativeIsAsciiMode(), hasNextPage = nativeHasNextPage(), hasPrevPage = nativeHasPrevPage(),
            engineRevision = candidateRevision.get(),
        )
    }

    /** Rebuild both the nine-key buffer and undo model; setInput alone leaves stale digits. */
    internal fun applyT9PinyinEdit(expectedInput: String, input: String, expectedRemainingDigits: String? = null,
        expectedSchema: String? = null, isStillCurrent: () -> Boolean = { true },
    ): Boolean = mutateLocked {
        if (!nativeHasSession() || !isStillCurrent() || nativeIsAsciiMode() ||
            (expectedSchema != null && nativeGetCurrentSchema() != expectedSchema) ||
            nativeGetInput().orEmpty() != expectedInput) return@mutateLocked false
        if (expectedRemainingDigits != null && nativeT9GetRemainingDigits().orEmpty() != expectedRemainingDigits) return@mutateLocked false
        if (!nativeT9ReplaceEditableSuffix(input)) return@mutateLocked false
        nativeT9FlushRimeInput()
        true
    }

    private external fun nativeT9ReplaceEditableSuffix(input: String): Boolean

    private external fun nativePinyinEditSnapshot(): Array<String>
    private external fun nativePinyinSyllableOptions(): Array<String>
    private external fun nativeSelectPinyinSyllable(expectedInput: String, expectedSchema: String,
        start: Int, end: Int, syllableId: Int): Boolean
    private external fun nativePinyinSyllablesChosen(): Int
    private external fun nativeUndoPinyinSyllable(expectedInput: String, expectedSchema: String): Boolean
    private external fun nativeSetCaret(caret: Int)

    // English stays half-width even after restoring full_shape from a Chinese schema.
    // Called under the engine lock; deliberate Chinese full-width input is unchanged.
    private fun enforceAsciiWidth() {
        if (nativeIsAsciiMode() && nativeGetOption("full_shape")) nativeSetOption("full_shape", false)
    }

    fun toggleAsciiMode(): Boolean {
        // 用户显式切换操作：阻塞等待锁（部署/维护持锁时排队，完成后自动切换），
        // 不静默失败；调用方保证不在主线程执行（ImeKeyRouter 的 key-process 线程）。
        return mutateLocked {
            if (!nativeHasSession() && !createSession()) return@mutateLocked false
            nativeToggleAsciiMode().also { enforceAsciiWidth() }
        }
    }

    fun isAsciiMode(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession() && !createSession()) return@tryLocked false
            nativeIsAsciiMode()
        }
    }

    @androidx.annotation.WorkerThread
    fun setOption(option: String, value: Boolean): Boolean = mutateLocked {
        if (!isInitialized || !nativeHasSession()) return@mutateLocked false
        nativeSetOption(option, value)
        true
    }

    /** 无痕：引擎不从上屏学习；会话重建后由原生层自动补设。 */
    fun setIncognito(on: Boolean) = locked {
        learningOff = on
        nativeSetIncognito(on)
    }

    @Volatile private var learningOff = false

    /** 最近一次上屏，供「删掉刚上屏的词不学习」判断（见 [RejectedCommit]）。 */
    internal val rejectedCommit = RejectedCommit()

    /** 每段 Rime 上屏文字只从原生层取出一次，在这里记下；没有上屏时不动。 */
    private fun noteCommit(text: String) {
        if (text.isNotEmpty()) rejectedCommit.committed(text, learned = !learningOff)
    }

    fun getOption(option: String): Boolean {
        if (!nativeHasSession()) return false
        return nativeGetOption(option)
    }

    fun setPageSize(schemaId: String, pageSize: Int) {
        // onStartInput may run while the worker is switching/deploying a schema.
        // Do not mutate shared native configuration concurrently or block the UI on deployment.
        tryMutating(Unit) {
            if (isInitialized && !nativeIsMaintaining()) nativeSetPageSize(schemaId, pageSize)
        }
    }

    /**
     * 换方案成功后通知（锁已放开，在调用 switchSchema 的线程上）。服务拿它在键盘开着换到九键时补读词组模型；
     * 只放一个，服务创建时设、销毁时清。
     */
    @Volatile var onSchemaSwitched: ((String) -> Unit)? = null

    fun switchSchema(schemaId: String): Boolean {
        // 部署/全量编译进行中（30s+）不阻塞等待：直接返回 false，避免主线程
        // onStartInput/selectSchema 等路径 ANR。部署完成后的 initRimeEngine
        // 流程会重新切换方案。非部署场景保持阻塞锁语义，保证切换可靠。
        if (isMaintaining()) {
            FileLogger.w(TAG, "switchSchema($schemaId) skipped: deployment in progress")
            return false
        }
        val switched = switchSchemaLocked(schemaId)
        if (switched) runCatching { onSchemaSwitched?.invoke(schemaId) }
        return switched
    }

    private fun switchSchemaLocked(schemaId: String): Boolean {
        mutateLocked {
            if (!nativeHasSession()) {
                FileLogger.w(TAG, "switchSchema($schemaId) failed: no rime session")
                return false
            }
            // 在切换方案前，确保 T9 方案的 schema 补丁已注入
            // 这会在 user_data_dir 中创建 {schemaId}.custom.yaml 文件，
            // RIME 引擎加载方案时会自动应用 custom.yaml 中的 patch 补丁
            nativeEnsureT9SchemaPatches(schemaId)
            phraseReadings = null
            mergedEditReading = null
            val switched = nativeSwitchSchema(schemaId)
            mergedAlphabet = null
            if (switched) schemaAppliedGeneration = sessionGeneration.get()
            if (switched) mergedSpellerAlphabet()
            if (!switched) {
                // 常见于方案未部署（不在 schema_list，如老版本升级残留）：
                // 留证便于反馈日志定位（用户症状：键盘已切换但按键无候选）
                FileLogger.w(TAG, "switchSchema($schemaId) failed: schema not available, current=${getCurrentSchema()}")
            }
            return switched
        }
    }

    /**
     * Re-selects the current schema so its translators re-read phrase files saved since: an open
     * session never sees a phrase added to custom_phrase.txt (host engine probe, 2026-10-23).
     * Input FIFO only; any unfinished composition is dropped, like a schema switch.
     */
    internal fun reloadQueuedSchema(): Boolean = mutateLocked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@mutateLocked false
        val current = nativeGetCurrentSchema().orEmpty()
        mergedAlphabet = null
        phraseReadings = null
        mergedEditReading = null
        (current.isNotEmpty() && nativeSwitchSchema(current)).also {
            if (it) schemaAppliedGeneration = sessionGeneration.get()
        }
    }

    fun startMaintenance(full: Boolean): Boolean {
        if (!isInitialized) return false
        mutateLocked {
            sessionReplaced()
            return nativeStartMaintenance(full)
        }
    }

    fun deploy(): Boolean {
        if (!isInitialized) return false
        mutateLocked {
            // 部署前确保 T9 补丁（含个人词库 packs 确定性名）已写入，
            // 部署时 librime 才会编译对应的 user_<schemaId>.table.bin。
            // 幂等：补丁已就位时 native 侧直接 skip，开销极小。
            ensureT9SchemaPatchesForDeployedSchemas(userDataDir)
            try { return nativeDeploy() } finally { sessionReplaced() }
        }
    }

    /**
     * 增量维护部署：librime 根据文件时间戳只编译变更的 schema/dict，
     * 避免配置小幅变化（如 custom.yaml 补丁）触发 60MB 词库全量重编译。
     * 与 [deploy] 一样持 rimeLock 并等待维护完成，保证维护期间其他
     * native 调用不会并发进入引擎。
     */
    fun deployIncremental(): Boolean {
        if (!isInitialized) return false
        mutateLocked {
            ensureT9SchemaPatchesForDeployedSchemas(userDataDir)
            if (!nativeStartMaintenance(false)) {
                FileLogger.w(TAG, "deployIncremental: startMaintenance returned false, falling back to full deploy")
                return false
            }
            // A slow native worker still owns build/. Wait before reloading or falling back.
            nativeJoinMaintenance()
            // 维护完成后更新 last_build_time，避免下次启动增量检测误判需重编译
            nativeUpdateLastBuildTime()
            // Existing sessions retain compiled ConfigData through strong references.
            // New files alone do not refresh their translators or candidate caches.
            try { return nativeReloadSessionAfterMaintenance() } finally { sessionReplaced() }
        }
    }

    /** Read only the active translator's codes, keeping pronunciation dictionaries out of shape-code hints. */
    internal fun currentSchemeCodes(schema: String, candidates: List<String>): List<String> {
        if (!isInitialized) return emptyList()
        return locked {
            if (!nativeHasSession() || schema != nativeGetCurrentSchema()) emptyList()
            else candidates.map { nativeLookupText(it, true) }
        }
    }

    fun lookupText(text: String): String {
        if (!isInitialized || text.isEmpty()) return ""
        return tryLocked("") {
            if (!nativeHasSession()) return@tryLocked ""
            nativeLookupText(text, false)
        }
    }

    @Volatile private var traditionalConfig: String? = null
    @Volatile private var simplifiedConfig: String? = null

    private fun openccConfig(name: String): String? = listOf(userDataDir, sharedDataDir).filter { it.isNotEmpty() }
        .map { File(it, "opencc/$name") }.firstOrNull { it.isFile }?.path

    /**
     * 简体转台湾正体，给语音这类不经 Rime 候选的文字用：和方案 traditionalization 是同一份 opencc/cyime_s2tw.json，
     * 跟打字出的字一致。不碰 Rime 会话、不拿 rimeLock（部署中也能转）；表还没装好或转换失败时原样返回。
     */
    fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        val config = traditionalConfig ?: openccConfig("cyime_s2tw.json")?.also { traditionalConfig = it } ?: return text
        return runCatching { nativeOpenccConvert(config, text) }.getOrNull() ?: text
    }

    /**
     * 繁体转简体（opencc/cyime_t2s.json，仓颉、注音转简体用的同一份）：繁体输出时联想拿上文去查简体联想表、
     * 个人学习也记成简体。其余同 [toTraditional]。
     */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        val config = simplifiedConfig ?: openccConfig("cyime_t2s.json")?.also { simplifiedConfig = it } ?: return text
        return runCatching { nativeOpenccConvert(config, text) }.getOrNull() ?: text
    }

    fun getAvailableSchemas(): Array<String> {
        return nativeGetAvailableSchemas() ?: emptyArray()
    }

    /** 读取方案配置字符串项（librime 解析，含 custom.yaml patch 合并后的最终值）。 */
    fun getSchemaString(schemaId: String, key: String): String? {
        if (!isInitialized) return null
        return nativeGetSchemaString(schemaId, key)
    }

    /** 读取方案配置列表项（librime 解析，含 custom.yaml patch 合并后的最终值）。 */
    fun getSchemaList(schemaId: String, key: String): List<String> {
        if (!isInitialized) return emptyList()
        return nativeGetSchemaList(schemaId, key)?.toList() ?: emptyList()
    }

    /** 读取方案自带 translator.packs 中声明的个人词库名。 */
    fun getSchemaPacks(schemaId: String): List<String> =
        getSchemaList(schemaId, "translator/packs")

    /**
     * 当前方案打字时按页读取的文件：主词库、个人词库包、音节表和词组模型。
     * 只列出存在的文件；先找用户目录的部署结果，没有再找共享目录。
     */
    fun prefetchCandidates(schemaId: String): List<String> {
        if (!isInitialized || schemaId.isEmpty()) return emptyList()
        // Skipped while the engine is busy (typing, deploying): the next keyboard show tries again.
        var usesModel = false
        val names = tryLocked(emptyList<String>()) {
            val dicts = listOfNotNull(getSchemaDictionary(schemaId)) + getSchemaPacks(schemaId)
            val prism = getSchemaString(schemaId, "translator/prism") ?: schemaId
            // 词组模型只给配了 t9/ngram_model 的方案（九键、14 键、注音）；26 键全拼不读它，不再每次弹键盘把一两百 MB 读回内存。
            usesModel = !getSchemaString(schemaId, "t9/ngram_model").isNullOrEmpty()
            // Most valuable first: every key reads the syllable prism and the main table.
            listOf("$prism.prism.bin") + dicts.map { "$it.table.bin" }
        }
        val files = names.mapNotNull { name ->
            listOf(userDataDir, sharedDataDir).filter { it.isNotEmpty() }
                .map { java.io.File(it, "build/$name") }.firstOrNull { it.isFile }?.path
        }
        val model = java.io.File(userDataDir, "t9_ngram.lm").takeIf { usesModel && userDataDir.isNotEmpty() && it.isFile }
        return (files + listOfNotNull(model?.path)).distinct()
    }

    /**
     * 当前方案不用九键词组模型（26 键全拼等）时，把模型已读进本进程的页放掉：九键打过字再换回 26 键，
     * 这些页（平板实测十几 MB，打得多可到上百 MB）一直算在输入法头上。文件页还在系统缓存，再用九键时取回。
     * 引擎正忙就跳过，下次收起再试。在 IO 线程调用。
     */
    @androidx.annotation.WorkerThread
    fun releaseT9ModelIfUnused(schemaId: String) {
        if (!isInitialized || schemaId.isEmpty()) return
        val usesModel = tryLocked(true) { !getSchemaString(schemaId, "t9/ngram_model").isNullOrEmpty() }
        if (!usesModel) nativeReleaseT9ModelPages()
    }

    /** 抽查文件在内存里的比例（每 [stride] 页查一页），0~1；打不开为 -1。在 IO 线程调用。 */
    @androidx.annotation.WorkerThread
    fun residentFraction(path: String, stride: Int = 64): Float = nativeResidentFraction(path, stride)

    /** 把文件中还不在内存的页交给内核异步读入；返回请求读入的 KB 数，打不开为 -1。在 IO 线程调用。 */
    @androidx.annotation.WorkerThread
    fun prefetchFile(path: String): Long = nativePrefetchFile(path)

    /** 读取方案 translator.dictionary 主词典名。 */
    fun getSchemaDictionary(schemaId: String): String? =
        getSchemaString(schemaId, "translator/dictionary")

    /** 读取方案 engine/translators 翻译器列表。 */
    fun getSchemaTranslators(schemaId: String): List<String> =
        getSchemaList(schemaId, "engine/translators")

    /** 方案是否有 speller.algebra（固定音节表/自动造词规则）。 */
    fun hasSpellerAlgebra(schemaId: String): Boolean =
        getSchemaList(schemaId, "speller/algebra").isNotEmpty()

    /** 读取方案 custom_phrase.user_dict（自定义短语词典名）。 */
    fun getCustomPhraseDictName(schemaId: String): String? =
        getSchemaString(schemaId, "custom_phrase/user_dict")

    // ── user.yaml 用户状态 ──

    /** 读取 user.yaml 用户状态字符串（如 var/previously_selected_schema）。 */
    fun getUserConfigString(key: String): String? {
        if (!isInitialized) return null
        return nativeGetUserConfigString(key)
    }

    /** 读取 user.yaml 用户状态布尔值（如 var/option/ascii_mode）。 */
    fun getUserConfigBool(key: String): Boolean {
        if (!isInitialized) return false
        return nativeGetUserConfigBool(key)
    }

    /** 写 user.yaml 用户状态字符串（auto_save，自动落盘）。 */
    fun setUserConfigString(key: String, value: String) {
        if (!isInitialized) return
        nativeSetUserConfigString(key, value)
    }

    /** 写 user.yaml 用户状态布尔值（auto_save，自动落盘）。 */
    fun setUserConfigBool(key: String, value: Boolean) {
        if (!isInitialized) return
        nativeSetUserConfigBool(key, value)
    }

    /** 运行时切换 JNI verbose 日志（仅 Debug 构建生效，Release 为空操作）。 */
    fun setVerboseLogging(enabled: Boolean) {
        locked {
            nativeSetVerboseLogging(enabled)
        }
    }

    fun destroy() {
        if (isInitialized) {
            mutateLocked {
                nativeDestroy()
                sessionReplaced()
                isInitialized = false
            }
        }
    }

    /** Explicit restore only: never overwrite an open LevelDB or discard an active composition. */
    internal fun restoreUserData(restore: () -> Result<Unit>): Result<Unit> = locked {
        if (isInitialized && (nativeIsMaintaining() || nativeGetInput().orEmpty().isNotEmpty())) {
            return@locked Result.failure(IllegalStateException("请先结束当前输入，等待词库准备完成后再恢复"))
        }
        val restart = isInitialized
        if (restart) {
            notifyDeploymentStatus(true, "正在恢复备份…")
            nativeDestroy()
            sessionReplaced()
            isInitialized = false
            candidateRevision.incrementAndGet()
        }
        try { restore() }
        finally {
            if (restart) initialize(userDataDir, sharedDataDir)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T9 Processor 公共 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 右选候选：根据候选拼音注释、候选文本与文本长度执行右侧选词（消费计算）。
     * 委托给 T9RightCommitHandler 三层消费算法，判断 full/partial commit。
     * 调频不在此进行——由 Kotlin 在 full commit 上屏后经 [t9Memorize] 单独调用。
     * @param pinyin 候选词的拼音注释（如 "li hua"）
     * @param text 候选词文本（如 "丽华"）；用于 (comment, text) 双条件精确定位，
     *   避免同注释歧义（如 几股/击鼓 均 "ji gu"）时捕获他词的调频码
     * @param textLength 候选词字数（如 "丽华" 为 2）
     */
    fun t9SelectCandidate(pinyin: String, text: String?, textLength: Int): Boolean {
        if (!isInitialized) return false
        return tryMutating(false) {
            if (!nativeHasSession() && !createSession()) return@tryMutating false
            nativeT9SelectCandidate(pinyin, text, textLength)
        }
    }

    /** 用户词典写入/回滚公共实现：memorize=true → commits=+1；false → commits=-1。 */
    private fun t9DictOp(text: String, pinyin: String, memorize: Boolean): Boolean {
        if (!isInitialized || text.isEmpty() || pinyin.isEmpty()) return false
        return tryMutating(false) {
            if (!nativeHasSession() && !createSession()) return@tryMutating false
            if (memorize) nativeT9Memorize(text, pinyin) else nativeT9Forget(text, pinyin)
        }
    }

    /**
     * 用户词典调频写入：在 full commit（含 partial 拼接）上屏后调用，
     * text 为上屏完整文本，pinyin 为空格分隔的拼音音节串（如 "ji hu a"）。
     * C++ 构造 RIME 原生 DictEntry 写入（key 由 RIME 生成）。
     */
    fun t9Memorize(text: String, pinyin: String): Boolean = t9DictOp(text, pinyin, true)

    /** Learn exactly the selected native syllable codes; never infer them from display comments. */
    fun t9MemorizeSelection(text: String): Boolean = tryMutating(false) {
        nativeT9Memorize(text, "")
    }

    /** 用户词典调频回滚（undo 联动）：撤销 right commit 段时调用，commits=-1。 */
    fun t9Forget(text: String, pinyin: String): Boolean = t9DictOp(text, pinyin, false)

    // Native 方法声明
    private external fun nativeInitialize(userDataDir: String, sharedDataDir: String)
    private external fun nativeInstallSignalHandler(userDataDir: String)
    private external fun nativeSetVerboseLogging(enabled: Boolean)
    private external fun nativeCreateSession(): Boolean
    private external fun nativeHasSession(): Boolean
    private external fun nativeIsMaintaining(): Boolean
    private external fun nativePrefetchFile(path: String): Long
    private external fun nativeResidentFraction(path: String, stride: Int): Float
    private external fun nativeReleaseT9ModelPages()
    private external fun nativeGetCurrentSchema(): String?
    private external fun nativeProcessKey(keycode: Int, mask: Int): Boolean
    private external fun nativeProcessKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult
    private external fun nativeGetProcessResult(processed: Boolean): RimeProcessResult
    private external fun nativeGetCandidates(): Array<String>?
    private external fun nativeGetCandidatesWithComments(): Array<Array<String>>?
    private external fun nativeGetAllCandidates(maxCount: Int): Array<Array<String>>?
    private external fun nativeGetInput(): String?
    private external fun nativeGetComposition(): RimeComposition
    private external fun nativeSelectCandidate(index: Int): Boolean
    private external fun nativeSelectCandidateByGlobalIndex(index: Int): Boolean
    private external fun nativeDeleteCandidateOnCurrentPage(index: Int): Boolean
    private external fun nativeDeleteCandidateByGlobalIndex(index: Int): Boolean
    private external fun nativeForgetCandidate(index: Int, global: Boolean): Int
    private external fun nativeCandidatePinState(text: String): Int
    private external fun nativeSetCandidatePinned(text: String, pinned: Boolean): Boolean
    private external fun nativeCandidatePinInfo(text: String): IntArray?
    private external fun nativeSetCandidatePinnedAt(text: String, place: Int): Boolean
    private external fun nativePinCode(code: String, text: String, place: Int): Boolean
    private external fun nativeUnpinCode(code: String, text: String): Boolean
    private external fun nativeListPins(): Array<String>
    private external fun nativeRestoreSuppressedCandidate(text: String): Boolean
    private external fun nativeGetSuppressedCandidates(): Array<String>
    private external fun nativeGetHiddenCandidates(): Array<String>
    private external fun nativeRestoreHiddenCandidate(text: String): Boolean
    private external fun nativeT9ScoringStatus(): String
    private external fun nativePageDown(): Boolean
    private external fun nativePageUp(): Boolean
    private external fun nativeHasNextPage(): Boolean
    private external fun nativeHasPrevPage(): Boolean
    private external fun nativeCommit(): String?
    private external fun nativeClearComposition()
    private external fun nativeSetInput(input: String): Boolean
    private external fun nativeToggleAsciiMode(): Boolean
    private external fun nativeIsAsciiMode(): Boolean
    private external fun nativeSetOption(option: String, value: Boolean)
    private external fun nativeSetNeighborMap(encoded: String)
    private external fun nativeSetT9LeftContext(text: String?)
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeSwitchSchema(schemaId: String): Boolean
    private external fun nativeEnsureT9SchemaPatches(schemaId: String): Boolean

    /**
     * 部署后主动触发：遍历已部署 schema 目录，对每个方案调用 nativeEnsureT9SchemaPatches。
     * native 内部 Phase 1 会判定并跳过非 T9 方案（幂等无害）；
     * 未启用方案仅写 custom.yaml 无副作用（后续切换到它时即生效）。
     *
     * 注意：此处【不】触发部署。历史上曾根据 native 返回值（词库未编译）在此调用
     * nativeDeploy，导致 initialize 的 initLock 内同步执行大部署，与引擎初始化/
     * 首次部署时序冲突引发 SIGSEGV（新装引导页场景）。词库编译统一由
     * 引导页/设置页的部署流程（build 缺失或用户显式部署）负责。
     */
    private fun ensureT9SchemaPatchesForDeployedSchemas(userDataDir: String) {
        val schemas = File(userDataDir).listFiles { f ->
            f.isFile && f.name.endsWith(".schema.yaml")
        }?.map { it.name.removeSuffix(".schema.yaml") } ?: return
        for (schemaId in schemas) {
            try {
                nativeEnsureT9SchemaPatches(schemaId)
            } catch (_: Throwable) {
                // 单个方案补丁失败不阻断引擎初始化
            }
        }
    }
    private external fun nativeStartMaintenance(full: Boolean): Boolean
    private external fun nativeJoinMaintenance()
    private external fun nativeDeploy(): Boolean
    private external fun nativeDeploySchema(schemaId: String): Boolean
    private external fun nativeLookupText(text: String, primaryOnly: Boolean): String
    private external fun nativeOpenccConvert(configPath: String, text: String): String
    private external fun nativeGetAvailableSchemas(): Array<String>?
    private external fun nativeGetSchemaList(schemaId: String, key: String): Array<String>?
    private external fun nativeGetSchemaString(schemaId: String, key: String): String?
    private external fun nativeGetUserConfigString(key: String): String?
    private external fun nativeGetUserConfigBool(key: String): Boolean
    private external fun nativeSetUserConfigString(key: String, value: String): Boolean
    private external fun nativeSetUserConfigBool(key: String, value: Boolean): Boolean
    private external fun nativeIsModuleRegistered(moduleName: String): Boolean
    private external fun nativeUpdateLastBuildTime()
    private external fun nativeSetPageSize(schemaId: String, pageSize: Int)
    private external fun nativeDestroy()
    private external fun nativeT9SelectCandidate(pinyin: String, text: String?, textLength: Int): Boolean
    private external fun nativeT9Memorize(text: String, pinyin: String): Boolean
    private external fun nativeSetIncognito(on: Boolean)
    private external fun nativeT9Forget(text: String, pinyin: String): Boolean
    private external fun nativeUnlearn(rows: String): Int
    private external fun nativeLearn(rows: String): Int
    private external fun nativeExportUserDict(file: String): Boolean
    private external fun nativeRestoreUserDict(file: String): Int
    private external fun nativeT9SelectPinyinDirect(pinyin: String, digitLength: Int): Boolean
    private external fun nativeT9FixLetter(letter: String): Boolean
    private external fun nativeT9GetLeftPanelState(): String?
    private external fun nativeT9ClearComposition(mode: Int)
    private external fun nativeT9FlushRimeInput()
    private external fun nativeT9GetAndConsumeUndoneRightCommitCount(): Int
    private external fun nativeT9GetRemainingDigits(): String?
    private external fun nativeT9GetFirstSyllableOptions(digits: String, maxResults: Int): String?

    /**
     * 直接选择拼音：传入拼音和对应数字长度，t9_processor 替换 buffer。
     */
    fun t9SelectPinyinDirect(pinyin: String, digitLength: Int): Boolean {
        if (!isInitialized) return false
        return tryMutating(false) {
            if (!nativeHasSession() && !createSession()) return@tryMutating false
            nativeT9SelectPinyinDirect(pinyin, digitLength)
        }
    }

    /**
     * 获取 partial commit 后 t9_processor 中剩余的数字串。
     */
    fun t9GetRemainingDigits(): String {
        if (!isInitialized) return ""
        return tryLocked("") {
            nativeT9GetRemainingDigits() ?: ""
        }
    }

    /**
     * 获取左侧面板状态字符串（格式：STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）。
     * 用于 Kotlin 侧同步 C++ 的选中拼音和面板数字。
     */
    fun t9GetLeftPanelState(): String {
        if (!isInitialized) return "IDLE;;;;;0"
        return tryLocked("IDLE;;;;;0") {
            nativeT9GetLeftPanelState() ?: "IDLE;;;;;0"
        }
    }

    /**
     * 获取并消费 P1 撤销 RightCommit 的计数。
     * 每次 P1 撤销自增 1，每次查询后自减，避免重复消费。
     */
    fun t9GetAndConsumeUndoneRightCommitCount(): Int {
        if (!isInitialized) return 0
        return tryLocked(0) {
            nativeT9GetAndConsumeUndoneRightCommitCount()
        }
    }

    /**
     * 获取首音节候选列表（P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions）。
     * 返回格式 "pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1,k|1,l|1"。
     */
    fun t9GetFirstSyllableOptions(digits: String, maxResults: Int = 20): List<Pair<String, Int>> {
        if (!isInitialized || digits.isEmpty()) return emptyList()
        return tryLocked(emptyList()) {
            val raw = nativeT9GetFirstSyllableOptions(digits, maxResults) ?: return@tryLocked emptyList()
            if (raw.isEmpty()) return@tryLocked emptyList()
            raw.split(",").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size == 2) {
                    Pair(parts[0], parts[1].toIntOrNull() ?: 1)
                } else null
            }
        }
    }

    /**
     * 清空 T9Processor 全部状态（buffer + undo + state machine）+ RIME composition。
     * @param mode 0=仅清 composition（保留 local state），1=全清（clearAll 场景）
     */
    /** T9 FIFO 内提交字面数字后的全清，不允许锁竞争时丢弃清空。 */
    internal fun clearQueuedT9Composition() = mutateLocked {
        if (isInitialized && nativeHasSession()) nativeT9ClearComposition(1)
    }

    fun t9ClearComposition(mode: Int) {
        if (!isInitialized) return
        tryMutating(Unit) {
            nativeT9ClearComposition(mode)
        }
    }

    /** A queued T9 key must wait for readers and flush as one transaction, never drop a key on tryLock. */
    @androidx.annotation.WorkerThread
    internal fun processQueuedT9Key(keycode: Int): Boolean = locked {
        val processed = processKey(keycode, 0)
        if (isInitialized && nativeHasSession()) nativeT9FlushRimeInput()
        processed
    }

    /** Text committed before the current T9 composition plus its partial choices; null = unknown. */
    private var t9LeftContext: String? = null

    internal data class T9KeyResult(
        val state: RimeProcessResult,
        val composingBefore: Boolean,
        val undoneSegments: Int,
    )

    @androidx.annotation.WorkerThread
    internal fun processQueuedT9KeyAndGetResult(keycode: Int, leftContext: String? = null): T9KeyResult? = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked null
        // Only backspace needs the pre-key composition to decide whether the host may delete.
        val before = keycode == 0xff08 &&
            (!nativeGetInput().isNullOrEmpty() || !nativeT9GetRemainingDigits().isNullOrEmpty())
        if (keycode != 0xff08 && nativeGetInput().isNullOrEmpty() && nativeT9GetRemainingDigits().isNullOrEmpty()) {
            // A new composition: committed text before it is the phrase model's history.
            t9LeftContext = leftContext
            nativeSetT9LeftContext(leftContext)
        }
        val processed = processQueuedT9Key(keycode)
        val undone = if (keycode == 0xff08) nativeT9GetAndConsumeUndoneRightCommitCount() else 0
        if (undone > 0 && t9LeftContext != null) {
            // An undone partial commit is no longer history; fall back to an unknown context.
            t9LeftContext = null
            nativeSetT9LeftContext(null)
        }
        T9KeyResult(withDisplayPreedit(nativeGetProcessResult(processed)), before, undone)
    }

    /** 拂动固定字母：并入当前数字串；数字串为空时返回 null，由上层按普通字母输入处理。 */
    @androidx.annotation.WorkerThread
    internal fun fixQueuedT9Letter(letter: String): RimeProcessResult? = mutateLocked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@mutateLocked null
        if (!nativeT9FixLetter(letter)) return@mutateLocked null
        nativeT9FlushRimeInput()
        withDisplayPreedit(nativeGetProcessResult(true))
    }

    @androidx.annotation.WorkerThread
    internal fun selectQueuedT9Pinyin(pinyin: String, digitLength: Int): RimeProcessResult? = mutateLocked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@mutateLocked null
        val processed = nativeT9SelectPinyinDirect(pinyin, digitLength)
        nativeT9FlushRimeInput()
        withDisplayPreedit(nativeGetProcessResult(processed))
    }

    @androidx.annotation.WorkerThread
    internal fun selectQueuedT9Candidate(pinyin: String, text: String?, length: Int, revision: Long): Pair<Boolean, RimeProcessResult>? = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked null
        candidateRevision.incrementAndGet()
        // A partial choice becomes history for the remaining digits (ignored after a full commit).
        if (text != null) t9LeftContext?.let { t9LeftContext = it + text; nativeSetT9LeftContext(it + text) }
        val before = nativeGetInput().orEmpty()
        val caretKeys = nativePinyinEditSnapshot().getOrNull(1)?.toIntOrNull()
            ?.takeIf { it < before.length }?.let { codeKeysBefore(before, it) }
        val full = nativeT9SelectCandidate(pinyin, text, length)
        // A caret inside the code still bounds what is left of it, as in full pinyin.
        val remaining = nativeT9GetRemainingDigits().orEmpty()
        val consumed = codeKeysBefore(before, before.length) - codeKeysBefore(remaining, remaining.length)
        t9CaretKeysAfterChoice = if (full || caretKeys == null) -1 else caretKeys - consumed
        // The keys left keep the reading the user saw, not a fresh decode of them alone.
        t9ReadingAfterChoice = t9CaretReading?.takeIf { t9CaretKeysAfterChoice > 0 && it.first == before }
            ?.let { dropReadingKeys(it.second, consumed) }.orEmpty()
        nativeT9FlushRimeInput()
        full to withDisplayPreedit(nativeGetProcessResult(true))
    }

    /** Validate identity and consume this selection's commit while holding the same lock. */
    @androidx.annotation.WorkerThread
    internal fun selectCandidateAtRevision(index: Int, revision: Long, global: Boolean = false): RimeProcessResult? = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked null
        candidateRevision.incrementAndGet()
        val caret = reselectCaret.also { reselectCaret = -1 }
        val selected = if (global) nativeSelectCandidateByGlobalIndex(index) else nativeSelectCandidate(index)
        if (!selected) return@locked null
        if (caret > 0) resumeCaretAfterPartialReselect(caret, reselectInput)
        withDisplayPreedit(nativeGetProcessResult(true))
    }

    @androidx.annotation.WorkerThread
    internal fun deleteCandidateAtRevision(index: Int, revision: Long, global: Boolean = false): Boolean = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        if (global) nativeDeleteCandidateByGlobalIndex(index) else nativeDeleteCandidateOnCurrentPage(index)
    }

    /** Developer tool: the current schema's user dictionary as a Rime text snapshot (.userdb.txt). */
    @androidx.annotation.WorkerThread
    fun exportUserDict(file: String): Boolean = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked false
        nativeExportUserDict(file)
    }

    /** Developer tool: puts the user dictionary back to an [exportUserDict] snapshot; entries changed, -1 on failure. */
    @androidx.annotation.WorkerThread
    fun restoreUserDict(file: String): Int = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked -1
        nativeRestoreUserDict(file)
    }

    /**
     * Takes back learned commits (rows "text<TAB>commits") from the current schema's user dictionary: the developer
     * tool, and「删掉刚上屏的词不学习」 (one commit of the word just deleted).
     */
    @androidx.annotation.WorkerThread
    fun unlearn(rows: String): Int = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked -1
        candidateRevision.incrementAndGet()
        nativeUnlearn(rows)
    }

    /** Developer tool: teaches words the user wrote elsewhere (rows "text<TAB>pin yin<TAB>commits") to the current schema's user dictionary. */
    @androidx.annotation.WorkerThread
    fun learn(rows: String): Int = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked -1
        candidateRevision.incrementAndGet()
        nativeLearn(rows)
    }

    /**
     * 长按删除 = 忘掉这条候选的学习记录：词库里本来有的词回到原位，自造词消失，整句忘掉句里学过的词。
     * 返回 0 失败（含候选已过期）、1 已忘掉、2 没有学习记录（排序不变）。
     */
    @androidx.annotation.WorkerThread
    internal fun forgetCandidateAtRevision(index: Int, revision: Long, global: Boolean = false): Int = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked 0
        candidateRevision.incrementAndGet()
        nativeForgetCandidate(index, global)
    }

    /**
     * 长按卡片用：[text] 在当前编码下是否已固定；null＝不能固定（没在组词，或引擎正忙）。
     * 主线程调用，拿不到锁不等待。
     */
    internal fun candidatePinState(text: String): Boolean? = tryLocked(null) {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@tryLocked null
        when (nativeCandidatePinState(text)) { 1 -> true; 0 -> false; else -> null }
    }

    /** 长按卡片的固定信息：已固定时 [pinnedPlace] 是固定的位置（0 排第一）；[menuPlace] 是现在排第几（0 没找到）。 */
    internal class PinInfo(val pinned: Boolean, val pinnedPlace: Int, val menuPlace: Int)

    /** 同 [candidatePinState]，再带上位置；null＝不能固定。主线程调用，拿不到锁不等待。 */
    internal fun candidatePinInfo(text: String): PinInfo? = tryLocked(null) {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@tryLocked null
        val info = nativeCandidatePinInfo(text) ?: return@tryLocked null
        when (info.getOrNull(0)) {
            1 -> PinInfo(true, info.getOrElse(1) { 0 }, info.getOrElse(2) { 0 })
            0 -> PinInfo(false, 0, info.getOrElse(2) { 0 })
            else -> null
        }
    }

    /**
     * 长按首选时拆出的词：首选是引擎组出来的整句（两个词以上）才有，否则 null。
     * 主线程调用，拿不到锁不等待。
     */
    internal fun firstSentenceWords(shown: String): List<String>? = tryLocked(null) {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining() || nativeIsAsciiMode()) return@tryLocked null
        val row = nativeInspectCandidates(1).firstOrNull() ?: return@tryLocked null
        if (row.getOrNull(0) != shown) return@tryLocked null
        splitSentenceWords(shown, row.getOrNull(9).orEmpty())
    }

    /** 当前候选里第一个文字是 [text] 的：全局序号、注释、在编码里的结束位置。须在锁内调用。 */
    private fun candidateSpan(text: String, limit: Int = SENTENCE_WORD_SEARCH): Triple<Int, String, Int>? =
        nativeInspectCandidates(limit).firstOrNull { it.getOrNull(0) == text }?.let { row ->
            val index = row.getOrNull(4)?.toIntOrNull() ?: return null
            val end = row.getOrNull(6)?.toIntOrNull() ?: return null
            Triple(index, row.getOrNull(1).orEmpty(), end)
        }

    /** 九键：当前候选里 [text] 的注释（拼音），重选整句里的词时交给九键选词。 */
    internal fun candidateReading(text: String): String? = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked null
        candidateSpan(text)?.second
    }

    /**
     * 长按首选整句、点了第 [target] 个词（全拼、双拼等非九键方案）：前面的词照原样逐个选定，
     * 光标放到这个词后面，候选就只剩这个词的其它写法；选好后 Rime 把光标送回末尾，后面照常组句。
     * 首选已经变了、光标不在末尾时不做；中途哪个词找不到就停在那里（只是选定了前几个词，没有上屏）。
     */
    internal fun reselectSentenceWord(words: List<String>, target: Int, revision: Long): RimeProcessResult? =
        editPinyinAndReadState {
            if (target !in words.indices || words.size < 2 || !isCandidateRevisionCurrent(revision) || !isInitialized ||
                !nativeHasSession() || nativeIsMaintaining() || nativeIsAsciiMode()) return@editPinyinAndReadState false
            val input = nativeGetInput().orEmpty()
            if (nativePinyinEditSnapshot().getOrNull(1)?.toIntOrNull() != input.length) return@editPinyinAndReadState false
            if (nativeInspectCandidates(1).firstOrNull()?.getOrNull(0) != words.joinToString("")) return@editPinyinAndReadState false
            candidateRevision.incrementAndGet()
            reselectCaret = -1
            for (i in 0 until target) {
                val span = candidateSpan(words[i]) ?: return@editPinyinAndReadState true
                if (!nativeSelectCandidateByGlobalIndex(span.first)) return@editPinyinAndReadState true
            }
            placeCaretAfterWord(words[target], nativeGetInput().orEmpty().length) { span -> span.third }
            true
        }

    /**
     * 九键：前面的词已由九键选词选定后，把光标放到第一个词 [word] 后面（[word] 的拼音字母数＝数字键数）。
     * 双拼九键的注释是全拼、和数字键数对不上，不挪光标。
     */
    internal fun placeT9CaretAfterWord(word: String, doublePinyin: Boolean): RimeProcessResult? = editPinyinAndReadState {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining() || nativeIsAsciiMode() || doublePinyin)
            return@editPinyinAndReadState false
        val input = nativeGetInput().orEmpty()
        val keys = t9KeysOfReading(candidateSpan(word)?.second ?: return@editPinyinAndReadState true)
        if (keys <= 0 || keys >= codeKeysBefore(input, input.length)) return@editPinyinAndReadState true
        if (placeT9CodeCaret(keys, input) && candidateSpan(word) == null) nativeSetCaret(input.length)
        true
    }

    /** 光标挪到 [word] 后面；挪过去后候选里没有 [word]（对不上）就放回末尾 [inputEnd]。须在锁内调用。 */
    private inline fun placeCaretAfterWord(word: String, inputEnd: Int, end: (Triple<Int, String, Int>) -> Int) {
        val span = candidateSpan(word) ?: return
        val caret = end(span)
        if (caret <= 0 || caret >= inputEnd) return
        candidateRevision.incrementAndGet()
        nativeSetCaret(caret)
        if (candidateSpan(word) == null) { nativeSetCaret(inputEnd); return }
        reselectCaret = caret
        reselectInput = nativeGetInput().orEmpty()
    }

    /**
     * 重选的这个词只选了一部分（wo'men 这段点了「我么」，只吃掉 wome）：Rime 只在选到光标处才把光标送回末尾，
     * 没选到就停在原处，剩下的 n 单独成段、只出单字。这时把光标送回末尾，剩下的拼音和后面一起照常组句。
     * 编码已经变了（中间打字、删字）或光标已经不在原处时不动。须在锁内、选词之后调用。
     */
    private fun resumeCaretAfterPartialReselect(caret: Int, input: String) {
        val now = nativeGetInput().orEmpty()
        if (now != input || caret >= now.length) return
        if (nativePinyinEditSnapshot().getOrNull(1)?.toIntOrNull() != caret) return
        nativeSetCaret(now.length)
    }

    /** 固定：当前编码下排第一，固定多个按先后排；取消固定回到原位。候选过期时不做。 */
    @androidx.annotation.WorkerThread
    internal fun setCandidatePinnedAtRevision(text: String, pinned: Boolean, revision: Long): Boolean = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        nativeSetCandidatePinned(text, pinned)
    }

    /** 固定在第 [place] 位：当前编码下锁在这个位置，没固定的词照常学习。候选过期时不做。 */
    @androidx.annotation.WorkerThread
    internal fun setCandidatePinnedAtPlace(text: String, place: Int, revision: Long): Boolean = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        nativeSetCandidatePinnedAt(text, place)
    }

    /** 一条固定：编码（九键是数字）、词、第几位（0 排第一）。 */
    internal data class Pin(val code: String, val text: String, val place: Int)

    /** 设置页（我的短语）列出的固定。 */
    @androidx.annotation.WorkerThread
    internal fun listPins(): List<Pin> = locked {
        if (!isInitialized || nativeIsMaintaining()) emptyList()
        else nativeListPins().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) null else Pin(parts[0], parts[1], parts[2].toIntOrNull() ?: 0)
        }
    }

    /** 设置页按编码固定（[place] 0 排第一）。下次打这个编码时生效。 */
    @androidx.annotation.WorkerThread
    internal fun pinCode(code: String, text: String, place: Int): Boolean = locked {
        if (!isInitialized || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        nativePinCode(code, text, place)
    }

    @androidx.annotation.WorkerThread
    internal fun unpinCode(code: String, text: String): Boolean = locked {
        if (!isInitialized || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        nativeUnpinCode(code, text)
    }

    @androidx.annotation.WorkerThread
    internal fun restoreSuppressedCandidate(text: String): Boolean = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        nativeRestoreSuppressedCandidate(text)
    }

    @androidx.annotation.WorkerThread
    internal fun getSuppressedCandidates(): List<String> = locked {
        if (!isInitialized || nativeIsMaintaining()) emptyList()
        else nativeGetSuppressedCandidates().toList()
    }

    /** 旧版本 26 键长按屏蔽的候选，只供设置页恢复。 */
    @androidx.annotation.WorkerThread
    internal fun getHiddenCandidates(): List<String> = locked {
        if (!isInitialized || nativeIsMaintaining()) emptyList()
        else nativeGetHiddenCandidates().toList()
    }

    @androidx.annotation.WorkerThread
    internal fun restoreHiddenCandidate(text: String): Boolean = locked {
        if (!isInitialized || nativeIsMaintaining()) return@locked false
        candidateRevision.incrementAndGet()
        nativeRestoreHiddenCandidate(text)
    }

    internal fun t9ScoringStatus(): String = locked {
        if (isInitialized) nativeT9ScoringStatus() else "uninitialized"
    }

    /** 0: no model work, 1: pending, 2: ready. Inference never holds rimeLock. */
    internal fun t9RefinementState(): Int = locked {
        if (!isInitialized || !nativeHasSession()) 0 else nativeT9RefinementState()
    }

    internal fun refineQueuedT9Result(revision: Long): RimeProcessResult? = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked null
        val before = nativeGetProcessResult(true)
        if (!nativeRefineT9Sentences()) return@locked null
        val after = nativeGetProcessResult(true)
        // 重排没有改变候选与编码时不升版本、不重新发布：此前看到的候选仍然有效，点击不会被当作过期丢弃。
        if (before.preeditText == after.preeditText && before.candidates.contentEquals(after.candidates)) return@locked null
        candidateRevision.incrementAndGet()
        withDisplayPreedit(after)
    }
    private external fun nativeReloadSessionAfterMaintenance(): Boolean
    private external fun nativeT9RefinementState(): Int
    private external fun nativeRefineT9Sentences(): Boolean
    private external fun nativeRefineOmission(): Boolean

    internal fun refineOmission(revision: Long): Boolean = locked {
        if (!isCandidateRevisionCurrent(revision) || !isInitialized || !nativeHasSession() || nativeIsMaintaining())
            return@locked false
        if (!nativeRefineOmission()) return@locked false
        candidateRevision.incrementAndGet()
        true
    }

    @androidx.annotation.WorkerThread
    internal fun readQueuedResult(): RimeProcessResult? = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) null
        else withDisplayPreedit(nativeGetProcessResult(true))
    }

    @androidx.annotation.WorkerThread
    internal fun readQueuedComposition(): RimeComposition? = locked {
        if (!isInitialized || !nativeHasSession() || nativeIsMaintaining()) return@locked null
        val result = nativeGetComposition()
        noteCommit(result.committedText)
        result.copy(preedit = displayPreedit(result.input, result.preedit, result.candidates.firstOrNull()),
            engineRevision = candidateRevision.get())
    }

    internal fun isCandidateRevisionCurrent(revision: Long): Boolean =
        revision != 0L && revision == candidateRevision.get()

    /** Null means stale/unavailable; an empty list means this valid snapshot has no more candidates. */
    @androidx.annotation.WorkerThread
    internal fun readCandidateBatch(revision: Long, offset: Int, count: Int): List<RimeCandidate>? = locked {
        require(offset >= 0 && count in 1..64)
        if (revision == 0L || revision != candidateRevision.get() || !isInitialized ||
            !nativeHasSession() || nativeIsMaintaining()) return@locked null
        nativeGetCandidateBatch(offset, count).map { pair -> RimeCandidate(pair[0], pair[1]) }
    }

    private external fun nativeGetCandidateBatch(offset: Int, count: Int): Array<Array<String>>

    /**
     * 执行 T9Processor 累积的待发送引擎动作（set_input → compose / clear 等）。
     *
     * 异步 flush 模型：T9 处理器在 processKey 内只标记待发送内容（SendToRime），
     * 真正触发引擎 compose 的调用延迟到此处执行。必须由调用方保证在
     * processKey 之后的**后台线程**调用，避免引擎 compose（2-23ms）阻塞 UI 线程。
     */
    fun t9FlushRimeInput() {
        if (!isInitialized) return
        tryMutating(Unit) {
            nativeT9FlushRimeInput()
        }
    }

    fun deploySchema(schemaId: String): Boolean {
        if (!isInitialized) return false
        try { return nativeDeploySchema(schemaId) } finally { sessionReplaced() }
    }

    fun updateLastBuildTime() {
        if (!isInitialized) return
        nativeUpdateLastBuildTime()
    }
}

/**
 * 编码内光标走一步后的位置：[confirmed] 之前已选定不可进入，到两端返回 null（不绕回）。
 * 手打的音节分隔符 ' 不是字母，跨过它时连带多走一格。
 */
internal fun compositionCaretStep(input: String, caret: Int, confirmed: Int, delta: Int): Int? {
    val low = confirmed.coerceIn(0, input.length)
    if (delta == 0 || caret !in low..input.length) return null
    var target = caret
    if (delta > 0) {
        if (target >= input.length) return null
        target++
        while (target < input.length && input[target - 1] == '\'') target++
    } else {
        if (target <= low) return null
        target--
        while (target > low && input[target] == '\'') target--
    }
    return target
}

/**
 * One Backspace in a composition. When it empties the input the composition is cleared, and the clear starts a
 * new candidate revision, so the result to publish is read after it. The one read before the clear is stale:
 * publishing drops it and the last letter stays on screen (clipboard search, 11-03).
 */
internal inline fun backspaceComposition(
    backspace: () -> RimeProcessResult,
    clear: () -> Unit,
    reread: (RimeProcessResult) -> RimeProcessResult,
): RimeProcessResult {
    val result = backspace()
    if (result.inputText.isNotEmpty()) return result
    clear()
    return reread(result)
}

/** 重选整句里的词时，在前多少个候选里找这个词。 */
private const val SENTENCE_WORD_SEARCH = 200
