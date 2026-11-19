package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.service.AsrInferenceClient
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking

/**
 * 基于 :asr 独立进程的流式离线 ASR 后端。
 *
 * 模型加载与推理全部在 [com.kingzcheung.xime.service.AsrInferenceService]（:asr 进程）中完成，
 * 主进程仅负责音频采集与结果回调，不占用输入法进程内存。
 */
class OfflineAsrBackend(private val context: Context) : AsrBackend {

    companion object {
        private const val TAG = "OfflineAsrBackend"
    }

    override val name: String get() = "本地离线语音"
    @Volatile override var finishTimeoutMs: Long = 8000L
        private set

    private val client = AsrInferenceClient(context)
    @Volatile private var initialized = false
    @Volatile private var retired = false
    @Volatile private var activeMode: String? = null
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong()

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
    }

    private val asrCallback = object : AsrInferenceClient.AsrCallback {
        override fun onPartialResult(text: String) {
            partialResultCallback?.invoke(text)
        }

        override fun onFinalResult(text: String) {
            resultCallback?.invoke(text)
        }

        override fun onError(message: String) {
            errorCallback?.invoke(message)
        }
    }

    override fun initialize(): Boolean = initialize(InputLanguage.CHINESE)

    override fun prepareForRecording(language: InputLanguage): Boolean {
        // Binding/loading happens in start(), on the audio delivery worker. The
        // capture owner can already collect and queue the beginning of speech.
        if (retired) return false
        initialized = true
        return true
    }

    @Synchronized
    override fun initialize(language: InputLanguage): Boolean {
        if (retired) return false
        return try {
            val ok = runBlocking { client.ensureBound() }
            if (!ok) {
                FileLogger.e(TAG, "Failed to bind AsrInferenceService")
                return false
            }
            syncKeepAlive()
            // AsrSupport may hand the manager an already-warmed backend. Re-registering it
            // must not start/stop another session or replace its language-specific model.
            if (initialized) return true
            initialized = true
            // 预热模型：绑定后立即创建模型句柄并驻留，避免首次语音时
            // 1s 模型加载导致开头音频（如"你觉得"）在录音缓冲中被丢弃
            try {
                val modelManager = AsrModelManager(context)
                val selection = modelManager.selectionForLanguage(language)
                if (runBlocking { client.startAsr(selection.mode, asrCallback, language) }) {
                    runBlocking { client.stopAsr() }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "model warmup skipped: ${e.message}")
            }
            FileLogger.i(TAG, "initialize result=true")
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "initialize failed", e)
            false
        }
    }

    override fun start(): Boolean = start(InputLanguage.CHINESE)

    @Synchronized
    override fun start(language: InputLanguage): Boolean {
        if (!initialized || retired) return false
        sessionGeneration.incrementAndGet()
        return try {
            val modelManager = AsrModelManager(context)
            val selection = modelManager.selectionForLanguage(language)
            activeMode = selection.mode
            val wholeSentence = SettingsPreferences.isSttWholeSentence(context)
            finishTimeoutMs = if (SpeechModelCatalog.isJapaneseZipformer(selection.mode))
                JapaneseZipformerDecoding.backendTimeoutMs
                else if (wholeSentence) WHOLE_SENTENCE_DECODE_TIMEOUT_MS + WHOLE_SESSION_BUDGET_MS + 3000L else 8000L
            // 每次会话开始都重新 startAsr：服务端会重置语音会话并重设回调，
            // 否则 preload 预热时 stop() 清空的 callback 会导致 partial 结果丢失
            runBlocking {
                if (!client.ensureBound()) return@runBlocking false
                client.setKeepModelAlive(SettingsPreferences.isSttKeepEngineAlive(context))
                client.setSaveTuningAudio(SettingsPreferences.isSttDebugRecord(context))
                client.setWholeSentenceFinal(wholeSentence)
                client.startAsr(selection.mode, asrCallback, language)
            }
        } catch (e: InterruptedException) {
            // 快速取消竞态：stopRecognition/cancelRecognition 会 interrupt 录音线程，
            // 中断正好落在等待 :asr 绑定/模型加载的 runBlocking 上——属正常取消，不算错误，
            // 不打 E 级日志（避免"启动引擎失败"误报污染排查）
            Thread.currentThread().interrupt()
            FileLogger.d(TAG, "start aborted: recording cancelled before engine started")
            false
        } catch (e: Exception) {
            FileLogger.e(TAG, "start failed", e)
            errorCallback?.invoke(e.message ?: "离线语音启动失败")
            false
        }
    }

    override fun processAudioChunk(buffer: ByteArray) {
        if (!initialized) return
        client.pushAsrAudio(buffer)
    }

    override fun canContinueIn(language: InputLanguage): Boolean {
        val mode = activeMode ?: return false
        return SpeechLanguages.supports(mode, language) &&
            AsrModelManager(context).getSelectedModelId(language) == mode
    }

    override fun stop() {
        if (!initialized) return
        val token = sessionGeneration.get()
        val text = try {
            runBlocking { client.stopAsr() }
        } catch (e: InterruptedException) {
            // 录音线程被取消 interrupt 时可能正阻塞在此（同 start 的取消竞态），属正常路径
            Thread.currentThread().interrupt()
            FileLogger.d(TAG, "stop aborted: recording cancelled")
            ""
        } catch (e: Exception) {
            FileLogger.e(TAG, "stop failed", e)
            throw e
        }
        if (token == sessionGeneration.get()) {
            resultCallback?.invoke(text)
            stateCallback?.invoke(RecognitionState.IDLE)
        }
    }

    /** 只取消正在结束的那一次（按它的回调认），不会碰到之后开始的新会话。 */
    override fun abandonFinishing() = client.abandonStop()

    override fun cancel() {
        sessionGeneration.incrementAndGet()
        if (initialized) {
            client.cancelAsr()
        }
    }

    override fun release() {
        // Managers stop/cancel on their owning delivery worker before releasing.
        // AsrSupport shares this resident backend with a replacement IME manager:
        // a delayed release from the old manager must not stop/reset its new session.
        // Idle model eviction belongs to the service; explicit off uses releaseModel.
    }

    /** 完全释放：卸载模型并解绑服务。仅在关闭"本地识别"开关时调用。 */
    @Synchronized
    fun releaseModel() {
        retired = true
        initialized = false
        try {
            runBlocking { client.releaseAsr() }
        } catch (e: Exception) {
            FileLogger.e(TAG, "releaseAsr failed", e)
        }
        client.unbind()
    }

    override fun isAvailable(): Boolean = true

    /** 键盘收起：:asr 在系统缺内存时卸掉空闲模型（见 AsrMemoryPressure）。 */
    fun keyboardHidden() = client.keyboardHidden()

    /**
     * 把"保持引擎常驻"设置同步到 :asr 服务。每次绑定/会话开始都同步一次：
     * 用户中途切换设置后，下一个会话立即按新设置决定是否空闲释放模型。
     */
    internal fun syncKeepAlive() {
        try {
            runBlocking {
                client.setKeepModelAlive(
                    com.kingzcheung.xime.settings.SettingsPreferences.isSttKeepEngineAlive(context)
                )
            }
        } catch (_: Exception) {
        }
    }
}
