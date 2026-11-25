package com.kingzcheung.xime.handwriting

import com.kingzcheung.xime.settings.InputLanguage

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking
import java.io.File

data class HandwritingCandidate(
    val char: String,
    val score: Float,
)

/** 手写模型的原始输出：字、模型里的类序号、概率（未按汉字过滤）。 */
data class HandwritingRawCandidate(
    val char: String,
    val index: Int,
    val score: Float,
)

/**
 * 手写识别客户端（主进程）：只负责模型生命周期管理与 IPC 代理。
 * 笔画预处理、ONNX 推理与 idx→汉字映射全部在 :inference 进程完成
 * （见 [HandwritingInference]），主进程不加载 ONNX 运行库、不常驻词表。
 */
object HandwritingEngine {
    private const val TAG = "HandwritingEngine"
    private const val DEFAULT_TOP_K = 10
    /** 自愈重试节流：:inference 进程回收后重载失败时，短时间内不重复 bind 空转。 */
    private const val REINIT_RETRY_INTERVAL_MS = 5_000L

    @Volatile
    private var initialized = false
    private var inferenceClient: InferenceClient? = null
    private var appContext: Context? = null

    @Volatile
    private var lastReinitAttemptMs = 0L

    /**
     * 加载只能一个一个来：键盘弹出/落笔时的预热（[warmUp]）和识别时的自愈（[ensureEngineReady]）
     * 以前会同时 initialize，后来的那个先 release 掉正在连的 client 再从头连一遍，
     * 慢的时候（横屏游戏里 :inference 进程被回收后冷启动）一个字要等两次加载。
     * [release] 不拿这把锁：它在主线程调用，不能等后台加载。
     */
    private val loadLock = Any()

    /** 每次 [release] 加一：加载途中被 release 的那次加载作废，不把已经放掉的 client 标成可用。 */
    @Volatile
    private var generation = 0

    fun initialize(context: Context, language: InputLanguage = InputLanguage.CHINESE): Boolean {
        if (!HandwritingLanguages.supports(language)) return false
        if (initialized && inferenceClient?.isBound() == true) return true
        synchronized(loadLock) { return initializeLocked(context, language) }
    }

    /**
     * 预热：模型没在就在后台先加载，加载好了直接返回。在后台线程调用。
     * 键盘收起会卸载模型（见输入法服务的 onFinishInputView），再弹出来时手写板还在、不会重新 initialize，
     * 以前要等第一个字写完、停顿到了才在识别里现加载；游戏占着内存时 :inference 进程已被回收，
     * 冷启动 + 读模型要几秒，就是「写完迟迟不出字」。现在键盘一弹出、一落笔就先加载，和书写同时进行。
     * 不受 [REINIT_RETRY_INTERVAL_MS] 节流，也不改节流时间：预热失败不影响识别时再试。
     */
    fun warmUp(context: Context, language: InputLanguage = InputLanguage.CHINESE) {
        if (!HandwritingLanguages.supports(language)) return
        if (initialized && inferenceClient?.isBound() == true) return
        synchronized(loadLock) {
            if (initialized && inferenceClient?.isBound() == true) return
            initializeLocked(context, language)
        }
    }

    private fun initializeLocked(context: Context, language: InputLanguage): Boolean {
        if (initialized && inferenceClient?.isBound() == true) return true
        val startedAt = SystemClock.elapsedRealtime()
        val token = generation

        val ctx = context.applicationContext
        val modelDir = ModelStorage.getModelDir(ctx, "ochwpro")
        // 兼容旧版：旧版手写模型在 filesDir 根目录
        ModelStorage.migrateLegacyForModel(ctx, "ochwpro")
        // 电脑上用自己的笔迹微调过的模型优先；字表沿用内置模型（微调不改字表）
        val modelFile = personalModelFile(ctx).takeIf { it.isFile } ?: File(modelDir, "ochwpro.onnx")
        val charIndexFile = File(modelDir, "char_index.json")

        if (!modelFile.exists() || !charIndexFile.exists()) {
            Log.w(TAG, "Model files not found: $modelFile, $charIndexFile")
            return false
        }

        try {
            val client = InferenceClient(ctx)
            // 先释放旧 client 的绑定，避免重复 initialize 泄漏 ServiceConnection
            inferenceClient?.unbind()
            inferenceClient = client
            appContext = ctx
            client.onDisconnected = {
                // 进程被回收后系统 AUTO_CREATE 自动重启只恢复 binder，模型不会自己回来：
                // 仅软重置本地"已加载"标志（不 unbind，保留自动重启），下次落笔由
                // ensureEngineReady 检测到未初始化后重载，避免"重启抢先→静默空结果"
                initialized = false
            }

            // 调用方均在后台线程（键盘 LaunchedEffect(IO)/切方案 Thread），runBlocking 桥接 IPC
            // 更新后的首次进程启动可能超过 3 秒；后台等待真实连接，连接完成立即返回。
            val bound = runBlocking { client.ensureBound(timeoutMs = 10_000L) }
            if (!bound) {
                Log.e(TAG, "Failed to bind InferenceService for handwriting")
                return false
            }
            val loaded = runBlocking {
                client.loadModel(
                    InferenceClient.MODEL_HANDWRITING,
                    modelFile.absolutePath,
                    charIndexFile.absolutePath
                )
            }
            if (!loaded) {
                Log.e(TAG, "Failed to load handwriting model in inference process")
                return false
            }
            // 加载途中键盘收起、换了方案等已经 release：这个 client 不再是当前的，放掉，不标成已加载
            if (token != generation || inferenceClient !== client) {
                client.unbind()
                return false
            }

            initialized = true
            val tookMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(TAG, "Handwriting engine initialized via IPC: ${modelFile.name} ms=$tookMs")
            // 记进日志文件：横屏游戏里「迟迟不出字」时能看出是不是在等加载（PC 拉日志对照）
            FileLogger.i(TAG, "handwriting model loaded ms=$tookMs")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            FileLogger.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            return false
        }
    }

    fun isInitialized(): Boolean = initialized

    /**
     * 个人手写模型：用训练模式的样本在电脑上微调，放到外部私有目录（adb 可直接推送，不随安装包分发）。
     * 文件在就用它；停用时改名为 `.disabled`，换回内置模型。
     */
    fun personalModelFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "handwriting_models/ochwpro-personal.onnx")

    fun disabledPersonalModelFile(context: Context): File =
        File(personalModelFile(context).path + ".disabled")

    /** 启用或停用个人模型并让下次识别重新加载；返回切换后是否在用个人模型。 */
    fun setPersonalModelEnabled(context: Context, enabled: Boolean): Boolean {
        val active = personalModelFile(context)
        val disabled = disabledPersonalModelFile(context)
        if (enabled && disabled.isFile && !active.exists()) disabled.renameTo(active)
        if (!enabled && active.isFile) active.renameTo(disabled)
        release()
        // 日语假名模板用的是这个模型的输出，换模型后立刻按新模型重来（不用重启进程）
        HandwritingTemplates.onRecognizerChanged(context)
        appContext = context.applicationContext
        return active.isFile
    }

    /** 手写模型是否可用（含旧版 filesDir 根目录模型迁移后检查）。 */
    fun hasModel(context: Context, language: InputLanguage = InputLanguage.CHINESE): Boolean {
        if (!HandwritingLanguages.supports(language)) return false
        ModelStorage.migrateLegacyForModel(context, "ochwpro")
        val modelDir = ModelStorage.getModelDir(context, "ochwpro")
        return File(modelDir, "ochwpro.onnx").exists() && File(modelDir, "char_index.json").exists()
    }

    fun predict(
        strokes: List<List<Pair<Float, Float>>>,
        topK: Int = DEFAULT_TOP_K,
        language: InputLanguage = InputLanguage.CHINESE,
    ): List<HandwritingCandidate> {
        if (!HandwritingLanguages.supports(language)) return emptyList()
        if (strokes.isEmpty()) return emptyList()
        when (language) {
            // 英文：同一个模型里的英文字母和数字（汉字过滤会把它们全部去掉）
            InputLanguage.ENGLISH -> return HandwritingLanguages.latinCandidates(
                predictRaw(strokes, HandwritingTemplates.OCH_TOP_N).map { HandwritingCandidate(it.char, it.score) }, topK)
            // 日语假名由模板比对（HandwritingTemplates），这里不出汉字
            InputLanguage.JAPANESE -> return emptyList()
            else -> {}
        }
        if (!ensureEngineReady()) return emptyList()
        val client = inferenceClient ?: return emptyList()

        // 原始笔画扁平化：points = [x0,y0,x1,y1,...]，counts = 每笔画点数
        var totalPoints = 0
        for (stroke in strokes) totalPoints += stroke.size
        val points = FloatArray(totalPoints * 2)
        val counts = IntArray(strokes.size)
        var pi = 0
        strokes.forEachIndexed { i, stroke ->
            counts[i] = stroke.size
            for (pt in stroke) {
                points[pi++] = pt.first
                points[pi++] = pt.second
            }
        }

        val candidates = runBlocking { client.recognizeHandwriting(points, counts, topK) }

        // IPC 期间服务进程被回收（客户端已吞 DeadObjectException 并重置绑定）：
        // 重置本地状态，下次 predict 由 ensureEngineReady 自愈重载
        if (!client.isBound()) {
            release()
            return emptyList()
        }
        return candidates
    }

    /** 模型原始输出（概率最高的 [n] 类，不过滤汉字）。在后台线程调用。 */
    fun predictRaw(strokes: List<List<Pair<Float, Float>>>, n: Int): List<HandwritingRawCandidate> {
        if (strokes.isEmpty() || n <= 0) return emptyList()
        if (!ensureEngineReady()) return emptyList()
        val client = inferenceClient ?: return emptyList()
        var total = 0
        for (stroke in strokes) total += stroke.size
        val points = FloatArray(total * 2)
        val counts = IntArray(strokes.size)
        var pi = 0
        strokes.forEachIndexed { i, stroke ->
            counts[i] = stroke.size
            for (pt in stroke) { points[pi++] = pt.first; points[pi++] = pt.second }
        }
        val raw = runBlocking { client.recognizeHandwritingRaw(points, counts, n) }
        if (!client.isBound()) { release(); return emptyList() }
        return raw
    }

    /**
     * 预测前确保引擎可用。:inference 进程被系统回收（设计内行为）
     * 后在此按需重载（重新 bind + loadModel）实现自愈。从未初始化过时直接返回
     * false，保持"无模型时 predict 返回空"的既有语义，不自动拉起服务。
     */
    private fun ensureEngineReady(): Boolean {
        val ctx = appContext ?: return false
        if (initialized && inferenceClient?.isBound() == true) return true

        // 预热正在加载时在这里等它加载完，直接用，不 release 掉它再重来
        synchronized(loadLock) {
            if (initialized && inferenceClient?.isBound() == true) return true
            release()
            val now = SystemClock.elapsedRealtime()
            if (now - lastReinitAttemptMs < REINIT_RETRY_INTERVAL_MS) return false
            lastReinitAttemptMs = now
            return initializeLocked(ctx, InputLanguage.CHINESE)
        }
    }

    fun release() {
        generation++
        if (!initialized && inferenceClient == null) return
        initialized = false
        inferenceClient?.apply {
            try {
                runBlocking { unloadModel(InferenceClient.MODEL_HANDWRITING) }
            } catch (_: Exception) {
            }
            unbind()
        }
        inferenceClient = null
    }
}
