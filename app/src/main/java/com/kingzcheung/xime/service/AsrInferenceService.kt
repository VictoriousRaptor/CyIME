package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.kingzcheung.xime.speech.AsrMemoryPressure
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.LocalSpeechSession
import com.kingzcheung.xime.speech.CaptionDictationArbiter
import com.kingzcheung.xime.speech.ResidentSpeechSessionOwner
import com.kingzcheung.xime.speech.SherpaSpeechEngine
import com.kingzcheung.xime.speech.SpeechBoundary
import com.kingzcheung.xime.speech.SpeechModelSelection
import com.kingzcheung.xime.speech.SpeechTuningRecorder
import com.kingzcheung.xime.speech.caption.KnownVoices
import com.kingzcheung.xime.speech.caption.CaptionSpeechSession
import com.kingzcheung.xime.speech.caption.EmbeddingSource
import com.kingzcheung.xime.speech.caption.SpeakerTracker
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.settings.InputLanguage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.kingzcheung.xime.speech.SherpaLibrary

/** Model weights and both decode workers live in :asr, never on the keyboard UI thread. */
class AsrInferenceService : Service() {
    companion object {
        /** Bind with this action to get the live-caption contract instead of dictation. */
        const val ACTION_CAPTION = "com.kingzcheung.xime.service.CAPTION"
    }
    private val lock = Any()
    private val idleHandler = Handler(Looper.getMainLooper())
    private val sessions = ResidentSpeechSessionOwner()
    private var keepAlive = false
    // 语音内存阶梯（毫秒，0 = 永不）：先卸 SenseVoice 第二遍，再卸全部。默认等于原来的 60 秒全部释放。
    private var refinerIdleMs = 60_000L
    private var engineIdleMs = 60_000L
    @Volatile private var saveTuningAudio = false
    @Volatile private var wholeSentenceFinal = false
    /** 最近一次语音输入用的模型；结束后据此在后台准备 Zipformer 的快速加载副本。 */
    @Volatile private var lastDictation: AsrModelManager.Selection? = null
    // Opt-in only; keyed by the client's session binder, like pushed PCM.
    private val recorders = ConcurrentHashMap<IBinder, SpeechTuningRecorder>()
    private val generation = AtomicLong()
    // Captions and dictation share one engine slot; the keyboard mic wins, automatic captions wait.
    private val arbiter = CaptionDictationArbiter<ICaptionCallback>(sessions) { it.asBinder() }
    private val captionOwner get() = arbiter.captionOwner
    private val captionGeneration = AtomicLong()
    private val speakerTracker = SpeakerTracker()
    private val embeddingSource = EmbeddingSource { samples -> embed(samples) }
    private var speakerExtractor: com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor? = null
    private var speakerModelPath: String? = null
    private val idleRelease = Runnable {
        synchronized(lock) {
            if (!keepAlive && captionOwner == null) {
                sessions.release(onlyIfIdle = true)
                if (sessions.isIdle) releaseSpeakerModel()
            }
        }
    }
    // 字幕模式开着时自己要用 SenseVoice，不在阶梯里卸掉。
    private val refinerRelease = Runnable {
        synchronized(lock) {
            if (captionOwner == null) sessions.releaseRefinementIfIdle {
                FileLogger.i("AsrInferenceService", "idle ladder: second pass released, streaming model kept")
            }
        }
    }

    private fun cancelIdleRelease() {
        idleHandler.removeCallbacks(idleRelease)
        idleHandler.removeCallbacks(refinerRelease)
    }

    private fun embed(samples: FloatArray): FloatArray? {
        val extractor = synchronized(lock) { speakerExtractor } ?: return null
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally { stream.release() }
    }

    private fun seedKnownVoices() {
        for (voice in KnownVoices.list(this)) KnownVoices.print(this, voice.id)?.let { speakerTracker.seedKnown(voice.id, it) }
    }

    /** The holder's voiceprint: 192 floats in app-private storage, never backed up or uploaded. */
    private fun selfPrintFile() = java.io.File(java.io.File(filesDir, "speaker").apply { mkdirs() }, "self.f32")
    private fun readSelfPrint(): FloatArray? = runCatching {
        val bytes = selfPrintFile().takeIf { it.isFile }?.readBytes() ?: return null
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.size / 4) { buffer.float }
    }.getOrNull()
    private fun writeSelfPrint(print: FloatArray) {
        val buffer = java.nio.ByteBuffer.allocate(print.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        print.forEach(buffer::putFloat)
        val file = selfPrintFile()
        val temp = java.io.File(file.parentFile, "self.f32.tmp")
        temp.writeBytes(buffer.array())
        temp.renameTo(file)
    }

    private fun loadSpeakerModel(path: String) = synchronized(lock) {
        if (speakerModelPath == path && speakerExtractor != null) return@synchronized
        releaseSpeakerModel()
        // No AssetManager: the model lives in filesDir, like the recognizers.
        speakerExtractor = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(
            config = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig(model = path, numThreads = 1))
        speakerModelPath = path
    }

    private fun releaseSpeakerModel() = synchronized(lock) {
        try { speakerExtractor?.release() } catch (_: Exception) { }
        speakerExtractor = null
        speakerModelPath = null
    }

    private fun scheduleRelease() {
        synchronized(lock) {
            cancelIdleRelease()
            if (!sessions.isIdle) return
            val engineMs = if (keepAlive) 0L else engineIdleMs
            // 第一级只有早于全部释放时才有意义。
            if (refinerIdleMs > 0 && (engineMs == 0L || refinerIdleMs < engineMs))
                idleHandler.postDelayed(refinerRelease, refinerIdleMs)
            if (engineMs > 0) idleHandler.postDelayed(idleRelease, engineMs)
        }
    }

    private val binder = object : IInferenceAsrService.Stub() {
        override fun startAsr(modelId: String, callback: IInferenceAsrCallback): Boolean =
            startSession(modelId, null, callback)
        override fun startAsrForLanguage(modelId: String, languageId: String, callback: IInferenceAsrCallback): Boolean {
            val language = InputLanguage.fromId(languageId)
            if (language == null || language == InputLanguage.UNSPECIFIED) {
                callback.onError("当前方案未标注可用的语音语言")
                return false
            }
            return startSession(modelId, language, callback)
        }
        private fun startSession(modelId: String, language: InputLanguage?, callback: IInferenceAsrCallback): Boolean {
            arbiter.dictationStarting()?.let { yielded ->
                captionGeneration.incrementAndGet()
                sessions.cancel(yielded.asBinder())
                try { yielded.onHold(true) } catch (_: Exception) { }
            }
            try { return startDictation(modelId, language, callback) }
            finally { arbiter.dictationStartDone(); resumeHeldCaption() }
        }
        private fun startDictation(modelId: String, language: InputLanguage?, callback: IInferenceAsrCallback): Boolean {
            cancelIdleRelease()
            val token = generation.incrementAndGet()
            val startedAt = SystemClock.elapsedRealtime()
            var constructed = false
            var modelLoadMs = 0L
            recorders.remove(callback.asBinder())?.close("replaced", "")
            return try {
                val manager = AsrModelManager(this@AsrInferenceService)
                val chosen = manager.selection(modelId, language)
                check(chosen.ready) { "所选语音模型尚未完整下载" }
                check(SherpaLibrary.available(this@AsrInferenceService)) { SherpaLibrary.MISSING }
                // 整句定稿只用于中文（规则见 SpeechModelSelection.wholeSentenceApplies，设置页同一条）：
                // X-ASR 已下载时用它定稿（代替 SenseVoice），没下载照旧用 SenseVoice。
                val wholeSentence = wholeSentenceFinal &&
                    SpeechModelSelection.wholeSentenceApplies(chosen.mode, chosen.recognitionLanguage == "zh")
                val selection = if (wholeSentence) manager.wholeSentenceFinalizer(chosen) else chosen
                val recorder = if (saveTuningAudio) SpeechTuningRecorder(
                    java.io.File(getExternalFilesDir(null) ?: filesDir, "speech_tuning"), mapOf(
                        "mode" to selection.mode, "language" to (language?.id ?: ""),
                        "device" to com.kingzcheung.xime.platform.AndroidPlatform.deviceLabel,
                        "app_version" to com.kingzcheung.xime.BuildConfig.VERSION_NAME,
                        "whole_sentence" to wholeSentence,
                        "finalizer" to (selection.finalizerDir?.name ?: ""),
                    )).also { recorders[callback.asBinder()] = it } else null
                val selectedAt = SystemClock.elapsedRealtime()
                val firstText = java.util.concurrent.atomic.AtomicBoolean(false)
                val ready = sessions.start(selection.key,
                    createEngine = {
                        constructed = true
                        val loadingAt = SystemClock.elapsedRealtime()
                        try { SherpaSpeechEngine(this@AsrInferenceService, selection) }
                        finally { modelLoadMs = SystemClock.elapsedRealtime() - loadingAt }
                    },
                    createSession = { engine -> LocalSpeechSession(engine,
                        onText = { text ->
                            // Release builds too: `adb logcat -s CyIME-AsrTiming` measures the delay to the first words.
                            if (text.isNotEmpty() && firstText.compareAndSet(false, true)) android.util.Log.i("CyIME-AsrTiming",
                                "first text ${SystemClock.elapsedRealtime() - startedAt} ms after start mode=${selection.mode}")
                            recorder?.text(text)
                            if (generation.get() == token) try { callback.onPartialResult(text) } catch (_: Exception) { }
                        },
                        onError = { message ->
                            recorder?.error(message)
                            if (generation.get() == token) try { callback.onError(message) } catch (_: Exception) { }
                        },
                        onRegion = { id, samples, boundary -> recorder?.region(id, samples, boundary) },
                        wholeSentence = wholeSentence,
                        // 中文流式 + 二次校正：第二遍改的字要过九键词组模型（没装模型时照旧整句采用第二遍）。
                        gate = if (selection.first != null && selection.secondDir != null && selection.recognitionLanguage == "zh")
                            com.kingzcheung.xime.speech.SecondPassGate.forFiles(filesDir) else null) },
                    owner = callback.asBinder(), isActive = { generation.get() == token })
                val readyAt = SystemClock.elapsedRealtime()
                val modelState = if (constructed) "loaded" else if (ready) "resident" else "superseded"
                android.util.Log.i("CyIME-AsrTiming", "start ready=$ready model=$modelState modelMs=$modelLoadMs " +
                    "totalMs=${readyAt - startedAt} mode=${selection.mode}")
                FileLogger.i("AsrInferenceService", "start ready=$ready model=$modelState " +
                    "selectionMs=${selectedAt - startedAt} modelMs=$modelLoadMs " +
                    "sessionWaitMs=${readyAt - selectedAt - modelLoadMs} totalMs=${readyAt - startedAt}")
                if (!ready) { scheduleRelease(); recorders.remove(callback.asBinder())?.close("not_started", "") }
                else lastDictation = selection
                ready
            } catch (e: Exception) {
                FileLogger.e("AsrInferenceService", "startAsr failed", e)
                recorders.remove(callback.asBinder())?.close("start_failed", "")
                if (generation.get() == token) callback.onError(e.message ?: "离线语音模型加载失败")
                scheduleRelease()
                false
            } catch (e: LinkageError) {
                if (generation.get() == token) callback.onError("识别运行库无法加载：${e.message}")
                scheduleRelease()
                false
            }
        }
        override fun pushAsrAudio(session: IBinder, audioData: ByteArray) {
            sessions.acceptPcm(audioData, session)
            recorders[session]?.audio(audioData)
        }
        override fun stopAsr(session: IBinder): String {
            val token = generation.get()
            FileLogger.d("AsrInferenceService", "stop generation=$token idle=${sessions.isIdle}")
            val text = sessions.finish(session) {
                generation.compareAndSet(token, token + 1)
                scheduleRelease()
                lastDictation?.let { com.kingzcheung.xime.speech.OptimizedEncoder.prepareLater(this@AsrInferenceService, it) }
            } ?: ""
            recorders.remove(session)?.close("stop", text)
            resumeHeldCaption()
            return text
        }
        override fun cancelAsr(session: IBinder) {
            FileLogger.d("AsrInferenceService", "cancel generation=${generation.get()} idle=${sessions.isIdle}")
            val token = generation.get()
            if (sessions.cancel(session)) generation.compareAndSet(token, token + 1)
            recorders.remove(session)?.close("cancel", "")
            scheduleRelease()
            resumeHeldCaption()
        }
        override fun releaseAsr(session: IBinder) {
            FileLogger.d("AsrInferenceService", "release generation=${generation.get()} idle=${sessions.isIdle}")
            val token = generation.get()
            if (sessions.release(owner = session)) {
                generation.compareAndSet(token, token + 1)
                cancelIdleRelease()
            }
            resumeHeldCaption()
        }
        override fun setSaveTuningAudio(enabled: Boolean) {
            saveTuningAudio = enabled
        }
        override fun setWholeSentenceFinal(enabled: Boolean) {
            wholeSentenceFinal = enabled
        }
        override fun onKeyboardHidden() {
            val memory = android.app.ActivityManager.MemoryInfo()
            try { getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory) ?: return }
            catch (_: Exception) { return }
            if (!AsrMemoryPressure.isTight(memory.availMem, memory.threshold, memory.totalMem, memory.lowMemory)) return
            synchronized(lock) {
                if (captionOwner != null || !sessions.isIdle) return
                FileLogger.w("AsrInferenceService", "keyboard hidden, memory tight (avail=${memory.availMem shr 20} MB " +
                    "threshold=${memory.threshold shr 20} MB total=${memory.totalMem shr 20} MB): releasing voice models")
                releaseIdleModels()
            }
        }
        override fun releaseIdleModelsNow(): Boolean = synchronized(lock) {
            if (captionOwner != null || !sessions.isIdle) return false
            FileLogger.i("AsrInferenceService", "release requested from settings: releasing voice models")
            releaseIdleModels()
            true
        }
        override fun memoryKb(): Long = com.kingzcheung.xime.settings.MemoryStats.selfPssKb() ?: 0L
        override fun setKeepModelAlive(keep: Boolean) = synchronized(lock) {
            keepAlive = keep
            cancelIdleRelease()
            if (sessions.isIdle) scheduleRelease()
        }
        override fun setIdleLadder(refinerIdleSeconds: Int, engineIdleSeconds: Int) = synchronized(lock) {
            refinerIdleMs = refinerIdleSeconds.coerceAtLeast(0) * 1000L
            engineIdleMs = engineIdleSeconds.coerceAtLeast(0) * 1000L
            keepAlive = engineIdleMs == 0L
            cancelIdleRelease()
            if (sessions.isIdle) scheduleRelease()
        }
    }
    /** Dictation is over: captions put on hold for it start again (they restart themselves). */
    private fun resumeHeldCaption() {
        val held = arbiter.takeResumable() ?: return
        try { held.onHold(false) } catch (_: Exception) { }
    }

    private val captionBinder = object : ICaptionService.Stub() {
        override fun start(modelId: String, speakerModelPath: String, callback: ICaptionCallback): Boolean =
            startLanguage(modelId, "zh", speakerModelPath, callback)
        override fun startLanguage(modelId: String, languageId: String, speakerModelPath: String, callback: ICaptionCallback): Boolean {
            cancelIdleRelease()
            val token = captionGeneration.incrementAndGet()
            val owner = callback.asBinder()
            // Claimed before the model load, so dictation sees captions from the start.
            if (!arbiter.claimCaption(callback)) {
                try { callback.onHold(true) } catch (_: Exception) { }
                // Dictation may have ended between the check and the hold.
                resumeHeldCaption()
                return false
            }
            return try {
                // The language is the user's choice on the caption page, never auto-detected: detection
                // turned accented Mandarin into Japanese. Japanese is ReazonSpeech Zipformer alone (user
                // 2026-10-21: no SenseVoice pass, it still wrote other languages); English is SenseVoice in English.
                val manager = AsrModelManager(this@AsrInferenceService)
                val base = manager.selection(modelId, null)
                val selection = when (languageId) {
                    "ja" -> base.copy(secondDir = null, recognitionLanguage = "ja")
                    "en" -> base.copy(recognitionLanguage = "en")
                    else -> base.copy(recognitionLanguage = "zh")
                }
                check(selection.ready) { "所选语音模型尚未完整下载" }
                check(SherpaLibrary.available(this@AsrInferenceService)) { SherpaLibrary.MISSING }
                val tracker = if (speakerModelPath.isNotEmpty() && java.io.File(speakerModelPath).isFile) {
                    loadSpeakerModel(speakerModelPath)
                    readSelfPrint()?.let(speakerTracker::seedSelf)
                    seedKnownVoices()
                    speakerTracker
                } else null
                val listener = object : CaptionSpeechSession.Listener {
                    override fun onSegment(id: Long, text: String, startMs: Long, endMs: Long, boundary: SpeechBoundary, confirmed: Boolean, event: String) {
                        if (captionGeneration.get() == token) try { callback.onSegment(id, text, startMs, endMs, boundary.ordinal, confirmed, event) } catch (_: Exception) { }
                    }
                    override fun onSpeaker(id: Long, speakerId: Int, confident: Boolean) {
                        if (captionGeneration.get() == token) try { callback.onSpeaker(id, speakerId, confident) } catch (_: Exception) { }
                    }
                    override fun onError(message: String) {
                        if (captionGeneration.get() == token) try { callback.onError(message) } catch (_: Exception) { }
                    }
                }
                val startedAt = SystemClock.elapsedRealtime()
                val ready = sessions.start(selection.key,
                    createEngine = { SherpaSpeechEngine(this@AsrInferenceService, selection) },
                    createSession = { engine -> CaptionSpeechSession(engine, tracker, tracker?.let { embeddingSource }, listener) },
                    owner = owner, isActive = { captionGeneration.get() == token })
                FileLogger.i("AsrInferenceService", "caption start ready=$ready speaker=${tracker != null} totalMs=${SystemClock.elapsedRealtime() - startedAt}")
                if (ready) {
                    try { owner.linkToDeath({ if (captionOwner == owner) { arbiter.dropCaption(owner); sessions.cancel(owner); scheduleRelease() } }, 0) } catch (_: Exception) { }
                } else { releaseCaptionClaim(owner, token); scheduleRelease() }
                ready
            } catch (e: Exception) {
                releaseCaptionClaim(owner, token)
                FileLogger.e("AsrInferenceService", "caption start failed", e)
                if (captionGeneration.get() == token) try { callback.onError(e.message ?: "离线语音模型加载失败") } catch (_: Exception) { }
                scheduleRelease()
                false
            } catch (e: LinkageError) {
                releaseCaptionClaim(owner, token)
                if (captionGeneration.get() == token) try { callback.onError("识别运行库无法加载：${e.message}") } catch (_: Exception) { }
                scheduleRelease()
                false
            }
        }
        private fun dropCaption(session: IBinder) = arbiter.dropCaption(session)
        private fun releaseCaptionClaim(owner: IBinder, token: Long) = arbiter.releaseClaim(owner) { captionGeneration.get() == token }
        override fun push(session: IBinder, audioData: ByteArray) = sessions.acceptPcm(audioData, session)
        override fun isHeld(session: IBinder): Boolean = arbiter.isHeld(session)
        override fun stop(session: IBinder) {
            dropCaption(session)
            sessions.finish(session) { scheduleRelease() }
        }
        override fun cancel(session: IBinder) {
            if (sessions.cancel(session)) captionGeneration.incrementAndGet()
            dropCaption(session)
            scheduleRelease()
        }
        override fun mergeSpeakers(from: Int, into: Int): Boolean = speakerTracker.merge(from, into).isNotEmpty() || from in speakerTracker.speakerIds
        override fun reassignSegment(segmentId: Long, speakerId: Int) = speakerTracker.reassign(segmentId, speakerId)
        override fun setSpeakerIgnored(speakerId: Int, ignored: Boolean) = speakerTracker.setIgnored(speakerId, ignored)
        override fun setSpeakerNamed(speakerId: Int, named: Boolean) = speakerTracker.setNamed(speakerId, named)
        override fun setStrictness(level: Int) = speakerTracker.setStrictness(level)
        override fun resetSpeakers() { speakerTracker.reset(); readSelfPrint()?.let(speakerTracker::seedSelf); seedKnownVoices() }
        override fun rememberSpeaker(speakerId: Int, name: String): Int {
            val context = this@AsrInferenceService
            val id = if (KnownVoices.isKnown(speakerId)) speakerId else KnownVoices.nextId(context)
            val print = speakerTracker.remember(speakerId, id) ?: return -1
            KnownVoices.save(context, id, name, print)
            return id
        }
        override fun forgetSpeaker(knownId: Int) { speakerTracker.forget(knownId); KnownVoices.remove(this@AsrInferenceService, knownId) }
        override fun markSelf(speakerId: Int) { speakerTracker.markSelf(speakerId)?.let(::writeSelfPrint) }
        override fun forgetSelf() { speakerTracker.forgetSelf(); selfPrintFile().delete() }
    }
    /**
     * 系统缺内存时按阶梯提前释放：先卸 SenseVoice 第二遍，内存很紧或进程进了后台列表再全部卸掉。
     * 正在说话或字幕模式开着时一律不动。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        synchronized(lock) {
            if (captionOwner != null || !sessions.isIdle) return
            val all = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            val refiner = !all && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            // 写明卸了什么：以前只记级别，看日志分不清模型是不是被这一下卸掉的。
            FileLogger.w("AsrInferenceService", "trim level=$level release=${if (all) "all" else if (refiner) "second pass" else "none"}")
            if (refiner) sessions.releaseRefinementIfIdle()
            if (all) releaseIdleModels()
        }
    }

    /** 调用方持有 [lock]，且已确认没在说话、字幕没开。 */
    private fun releaseIdleModels() {
        cancelIdleRelease()
        sessions.release(onlyIfIdle = true)
        releaseSpeakerModel()
    }

    override fun onBind(intent: Intent): IBinder = if (intent.action == ACTION_CAPTION) captionBinder else binder
    override fun onCreate() {
        super.onCreate()
        // 轻量版的语音引擎是下载的：sherpa 的类加载库前要知道去哪找（speech/SherpaLibrary.kt）。
        SherpaLibrary.attach(this)
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        captionGeneration.incrementAndGet()
        arbiter.clear()
        cancelIdleRelease()
        sessions.release()
        releaseSpeakerModel()
        super.onDestroy()
    }
}
