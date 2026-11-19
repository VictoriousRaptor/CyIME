package com.kingzcheung.xime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.settings.InputLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 离线语音识别服务客户端，绑定 [AsrInferenceService]（:asr 独立进程）。
 */
class AsrInferenceClient(context: Context) {
    private val context = context.applicationContext

    companion object {
        private const val TAG = "AsrInferenceClient"
    }

    interface AsrCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }

    @Volatile private var service: IInferenceAsrService? = null
    @Volatile private var bound = false
    @Volatile private var sessionFailure: String? = null

    /** 连接等待锁：一次性 CountDownLatch，countDown 后即失效——
     *  重绑前必须在 [ensureBound] 中重建，否则断线重连后会立即假醒。 */
    @Volatile
    private var connectLatch = CountDownLatch(1)

    private val callbackGeneration = java.util.concurrent.atomic.AtomicLong()
    private data class Session(val generation: Long, val callback: IInferenceAsrCallback)
    @Volatile private var session: Session? = null
    /** 正在 [stopAsr] 里等结果的那一次会话；[abandonStop] 只取消它。 */
    @Volatile private var stopping: Session? = null

    private fun callbackStub(callback: AsrCallback, token: Long) = object : IInferenceAsrCallback.Stub() {
        override fun onPartialResult(text: String) { if (token == callbackGeneration.get()) callback.onPartialResult(text) }
        override fun onFinalResult(text: String) { if (token == callbackGeneration.get()) callback.onFinalResult(text) }
        override fun onError(message: String) {
            if (token == callbackGeneration.get()) {
                sessionFailure = message
                callback.onError(message)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IInferenceAsrService.Stub.asInterface(binder)
            bound = true
            connectLatch.countDown()
            FileLogger.i(TAG, "Connected to AsrInferenceService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            FileLogger.w(TAG, "AsrInferenceService disconnected (crash?)")
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            bound = false
            connectLatch.countDown()
            FileLogger.e(TAG, "AsrInferenceService binding died")
        }
    }

    suspend fun ensureBound(): Boolean {
        if (bound && service != null) return true

        // bindService 必须在主线程调用；等待结果放到 IO 线程避免阻塞 UI
        val boundOk = withContext(Dispatchers.Main) {
            synchronized(this@AsrInferenceClient) {
                if (bound && service != null) {
                    true
                } else {
                    // 服务进程可能崩溃/被回收后重启：latch 是一次性的，重绑前必须重建，
                    // 否则旧 latch 已 countDown 会让 await 立即假醒（实际未连接），
                    // 重绑后的第一次 startAsr 必然失败（同 InferenceClient 的修复）
                    connectLatch = CountDownLatch(1)
                    val intent = Intent(context, AsrInferenceService::class.java)
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }
            }
        }
        if (!boundOk) return false
        return withContext(Dispatchers.IO) {
            connectLatch.await(5, TimeUnit.SECONDS)
            bound && service != null
        }
    }

    fun unbind() {
        callbackGeneration.incrementAndGet()
        session = null
        try {
            context.unbindService(connection)
        } catch (_: Exception) {}
        bound = false
        service = null
    }

    private fun requireService(): IInferenceAsrService {
        return service ?: throw IllegalStateException("AsrInferenceService not bound")
    }

    suspend fun startAsr(modelId: String, callback: AsrCallback, language: InputLanguage? = null): Boolean = withContext(Dispatchers.IO) {
        val token = callbackGeneration.incrementAndGet()
        try {
            if (!ensureBound()) return@withContext false
            if (callbackGeneration.get() != token) return@withContext false
            sessionFailure = null
            val active = Session(token, callbackStub(callback, token))
            session = active
            val remote = requireService()
            val ready = if (language == null) remote.startAsr(modelId, active.callback)
            else remote.startAsrForLanguage(modelId, language.id, active.callback)
            if (callbackGeneration.get() != token) {
                remote.cancelAsr(active.callback.asBinder())
                false
            } else ready
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (e: Exception) {
            FileLogger.e(TAG, "startAsr failed", e)
            callbackGeneration.compareAndSet(token, token + 1)
            false
        }
    }

    fun pushAsrAudio(audioData: ByteArray) {
        sessionFailure?.let { throw IllegalStateException(it) }
        val active = checkNotNull(session) { "语音会话尚未启动" }
        check(active.generation == callbackGeneration.get()) { "语音会话已结束" }
        try {
            requireService().pushAsrAudio(active.callback.asBinder(), audioData)
        } catch (error: Exception) {
            // Propagate to the capture owner's failure path, which releases AudioRecord.
            // Silently dropping PCM leaves a live microphone with no recognizer.
            throw IllegalStateException("语音服务已断开，请重新开始录音", error)
        }
    }

    suspend fun stopAsr(): String = withContext(Dispatchers.IO) {
        val token = callbackGeneration.get()
        val active = session
        stopping = active
        try {
            sessionFailure?.let { throw IllegalStateException(it) }
            active?.let { requireService().stopAsr(it.callback.asBinder()) } ?: ""
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("语音识别未能完成，请重新录音", error)
        } finally {
            stopping = null
            callbackGeneration.compareAndSet(token, token + 1)
        }
    }

    /**
     * 让正在 [stopAsr] 里等结果的那一次不再做整段重识别，尽快返回（:asr 按停顿定稿的文字照旧返回）。
     * 服务按回调认会话：来晚了、那一次已经结束或新会话已经开始时，什么都不会被取消。
     */
    fun abandonStop() {
        val active = stopping ?: return
        try { requireService().cancelAsr(active.callback.asBinder()) } catch (_: Exception) {}
    }

    fun cancelAsr() {
        val active = session
        callbackGeneration.incrementAndGet()
        try {
            active?.let { requireService().cancelAsr(it.callback.asBinder()) }
        } catch (_: Exception) {}
    }

    suspend fun releaseAsr() {
        val active = session
        callbackGeneration.incrementAndGet()
        try {
            active?.let { requireService().releaseAsr(it.callback.asBinder()) }
        } catch (_: Exception) {}
    }

    /** 同步"保持引擎常驻"设置到 :asr 进程（服务端据此决定是否空闲自动释放模型）。 */
    suspend fun setKeepModelAlive(keepAlive: Boolean) {
        try {
            val service = requireService()
            service.setKeepModelAlive(keepAlive)
            // 同一次同步带上内存阶梯，服务端按两级分别计时释放。
            service.setIdleLadder(
                com.kingzcheung.xime.settings.SettingsPreferences.getSttRefinerIdleMinutes(context) * 60,
                if (keepAlive) 0 else com.kingzcheung.xime.settings.SettingsPreferences.getSttEngineIdleMinutes(context) * 60)
        } catch (_: Exception) {}
    }

    /** 键盘收起时告诉 :asr；没绑定（没用过语音）就不绑，免得为此拉起进程。oneway，不阻塞主线程。 */
    fun keyboardHidden() {
        val connected = service ?: return
        try { connected.onKeyboardHidden() } catch (_: Exception) {}
    }

    /** 立刻卸掉空闲的语音模型（设置 › 存储空间 的「释放」）；正在说话、字幕开着或连不上时返回 false。 */
    suspend fun releaseIdleModelsNow(): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound()) return@withContext false
        try { requireService().releaseIdleModelsNow() } catch (_: Exception) { false }
    }

    /** 语音进程现在占多少内存（PSS，kB）；连不上或读不到为 null。 */
    suspend fun memoryKb(): Long? = withContext(Dispatchers.IO) {
        if (!ensureBound()) return@withContext null
        try { requireService().memoryKb().takeIf { it > 0 } } catch (_: Exception) { null }
    }

    /** 同步“保存语音调优录音”设置；服务端只在开启时写入应用自己的目录。 */
    suspend fun setSaveTuningAudio(enabled: Boolean) {
        try {
            requireService().setSaveTuningAudio(enabled)
        } catch (_: Exception) {}
    }

    /** 同步“整句定稿”设置；下一次录音开始时生效。 */
    suspend fun setWholeSentenceFinal(enabled: Boolean) {
        try {
            requireService().setWholeSentenceFinal(enabled)
        } catch (_: Exception) {}
    }
}
