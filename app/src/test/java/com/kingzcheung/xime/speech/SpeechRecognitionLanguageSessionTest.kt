package com.kingzcheung.xime.speech

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real recording worker with a fake microphone and explicitly delayed backend shutdown. */
class SpeechRecognitionLanguageSessionTest {
    private data class Callbacks(val final: (String) -> Unit, val partial: ((String) -> Unit)?,
        val state: (RecognitionState) -> Unit, val error: (String) -> Unit)

    private class Backend : AsrBackend {
        override val name = "language session test"
        lateinit var callbacks: Callbacks
        val initializations = LinkedBlockingQueue<InputLanguage>()
        val sessions = LinkedBlockingQueue<Pair<InputLanguage, Callbacks>>()
        val audio = LinkedBlockingQueue<Pair<InputLanguage, ByteArray>>()
        val events = CopyOnWriteArrayList<String>()
        val completedStops = AtomicInteger()
        val startInterruptions = AtomicInteger()
        @Volatile var closing = CountDownLatch(1)
        @Volatile var allowClose = CountDownLatch(1)
        @Volatile var startGate: CountDownLatch? = null
        @Volatile var startSucceeds = true
        /** Reported through onError before a failed start, like OfflineAsrBackend's "model not downloaded". */
        @Volatile var startError: String? = null
        /** Holds on-demand model loading until the test opens it. */
        @Volatile var initGate: CountDownLatch? = null
        var compatibleLanguages = emptySet<InputLanguage>()
        override fun canContinueIn(language: InputLanguage) = language in compatibleLanguages
        @Volatile private var language = InputLanguage.UNSPECIFIED
        override fun setCallbacks(onResult: (String) -> Unit, onPartialResult: ((String) -> Unit)?,
            onStateChange: (RecognitionState) -> Unit, onError: (String) -> Unit) {
            callbacks = Callbacks(onResult, onPartialResult, onStateChange, onError)
        }
        override fun initialize(): Boolean {
            callbacks.final("warmup must not enter editor")
            return true
        }
        override fun initialize(language: InputLanguage): Boolean {
            initializations.put(language)
            initGate?.await(5, TimeUnit.SECONDS)
            return initialize()
        }
        override fun start(): Boolean = error("The product language must reach the backend")
        override fun start(language: InputLanguage): Boolean {
            this.language = language
            events += "start:${language.id}"
            sessions.put(language to callbacks)
            try {
                startGate?.await()
            } catch (error: InterruptedException) {
                startInterruptions.incrementAndGet()
                throw error
            }
            if (!startSucceeds) startError?.let { callbacks.error(it) }
            return startSucceeds
        }
        override fun processAudioChunk(buffer: ByteArray) {
            events += "audio:${language.id}"
            audio.put(language to buffer.copyOf())
        }
        override fun stop() {
            val stoppingLanguage = language
            val permit = allowClose
            events += "stop-begin:${stoppingLanguage.id}"
            closing.countDown()
            check(permit.await(5, TimeUnit.SECONDS))
            events += "stop-end:${stoppingLanguage.id}"
            callbacks.final("closing result")
            completedStops.incrementAndGet()
        }
        fun blockNextStop() {
            closing = CountDownLatch(1)
            allowClose = CountDownLatch(1)
        }
        override fun cancel() = stop() // Deliberately emits even after cancellation: manager must reject it.
        val abandons = AtomicInteger()
        /** Like the :asr service skipping its end-of-input re-read: the blocked stop returns at once. */
        @Volatile var abandonReleasesStop = false
        override fun abandonFinishing() {
            abandons.incrementAndGet()
            if (abandonReleasesStop) allowClose.countDown()
        }
        override fun release() {}
        override fun isAvailable() = true
    }

    /** Each read returns a real 100 ms PCM chunk; release unblocks a pending read. */
    private class Microphone {
        private val input = LinkedBlockingQueue<ShortArray>()
        val starts = AtomicInteger()
        val reads = AtomicInteger()
        val releases = AtomicInteger()
        val released = AtomicBoolean()
        val record = mock<AudioRecord>().also { record ->
            whenever(record.recordingState).thenAnswer {
                if (starts.get() > 0 && !released.get()) AudioRecord.RECORDSTATE_RECORDING
                else AudioRecord.RECORDSTATE_STOPPED
            }
            doAnswer { starts.incrementAndGet(); null }.whenever(record).startRecording()
            whenever(record.read(any<ShortArray>(), any(), any())).thenAnswer {
                val chunk = input.take()
                if (released.get()) -1 else {
                    val target = it.getArgument<ShortArray>(0)
                    val offset = it.getArgument<Int>(1)
                    val length = it.getArgument<Int>(2)
                    check(chunk.size <= length)
                    chunk.copyInto(target, destinationOffset = offset)
                    reads.incrementAndGet()
                    chunk.size
                }
            }
            // Non-blocking read (draining what was captured while the model loaded): 0 once nothing is buffered.
            whenever(record.read(any<ShortArray>(), any(), any(), any())).thenAnswer {
                val chunk = input.poll() ?: return@thenAnswer 0
                if (released.get() || chunk.isEmpty()) -1 else {
                    val target = it.getArgument<ShortArray>(0)
                    val length = it.getArgument<Int>(2)
                    check(chunk.size <= length)
                    chunk.copyInto(target, destinationOffset = it.getArgument<Int>(1))
                    reads.incrementAndGet()
                    chunk.size
                }
            }
            doAnswer {
                releases.incrementAndGet()
                released.set(true)
                input.offer(shortArrayOf())
                null
            }.whenever(record).release()
        }
        fun feed(marker: Int) {
            check(marker in 26..Short.MAX_VALUE.toInt()) // Above VAD threshold; no silence filtering.
            input.put(ShortArray(1600) { marker.toShort() })
        }
    }

    private class Fixture(preload: Boolean = true, initialKeepAlive: Boolean = true, duckMedia: Boolean = false) : AutoCloseable {
        val queue = LinkedBlockingQueue<Runnable>()
        val delayed = CopyOnWriteArrayList<Pair<Runnable, Long>>()
        val backend = Backend()
        val onlineBackend = Backend()
        val anotherOnlineBackend = Backend()
        @Volatile var useLocal = true
        @Volatile var pluginId = ""
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val microphones = CopyOnWriteArrayList<Microphone>()
        val mediaVolumes = CopyOnWriteArrayList<Int>()
        val allocations: Int get() = microphones.size
        @Volatile var keepAlive = initialKeepAlive
        val manager: SpeechRecognitionManager
        init {
            val context = mock<Context>()
            val prefs = mock<SharedPreferences>()
            whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
            whenever(prefs.getBoolean(any(), any())).thenAnswer { it.getArgument(1) }
            whenever(prefs.getBoolean(eq(SettingsPreferences.KEY_STT_USE_LOCAL), any())).thenAnswer { useLocal }
            whenever(prefs.getString(eq(SettingsPreferences.KEY_STT_ONLINE_PLUGIN_ID), any())).thenAnswer { pluginId }
            whenever(prefs.getBoolean(eq(SettingsPreferences.KEY_STT_KEEP_ENGINE_ALIVE), any())).thenAnswer { keepAlive }
            // Residency is now read as idle minutes (0 = keep), defaulting from the old switch; an unstubbed getInt was 0 = always resident.
            whenever(prefs.getInt(any(), any())).thenAnswer { it.getArgument(1) }
            if (duckMedia) {
                val audio = mock<AudioManager>()
                whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
                whenever(audio.isMusicActive).thenReturn(true)
                whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { mediaVolumes.lastOrNull() ?: 10 }
                doAnswer { mediaVolumes += it.getArgument<Int>(1); null }.whenever(audio)
                    .setStreamVolume(eq(AudioManager.STREAM_MUSIC), any(), eq(0))
                whenever(prefs.getString(eq(SettingsPreferences.KEY_STT_OTHER_AUDIO), isNull())).thenReturn("duck")
                val editor = mock<SharedPreferences.Editor>()
                whenever(prefs.edit()).thenReturn(editor)
                whenever(editor.putInt(any(), any())).thenReturn(editor)
            }
            val handler = mock<Handler>()
            whenever(handler.post(any())).thenAnswer { queue.put(it.getArgument(0)); true }
            whenever(handler.postDelayed(any(), any())).thenAnswer {
                delayed += it.getArgument<Runnable>(0) to it.getArgument<Long>(1)
                true
            }
            doAnswer {
                val runnable = it.getArgument<Runnable>(0)
                delayed.removeAll { scheduled -> scheduled.first === runnable }
                null
            }.whenever(handler).removeCallbacks(any())
            manager = SpeechRecognitionManager(context, {
                if (useLocal) backend else if (pluginId == "online-c") anotherOnlineBackend else onlineBackend
            }, handler) {
                Microphone().also { microphones += it }.record
            }
            manager.setCallbacks(onResult = { results += it }, onPartialResult = { results += it },
                onStateChange = {}, onError = { text, _ -> errors += text })
            if (preload) assertTrue(manager.preload())
        }
        fun drain() { while (true) (queue.poll() ?: return).run() }
        fun await(timeoutSeconds: Long = 5, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!condition() && System.nanoTime() < deadline) queue.poll(20, TimeUnit.MILLISECONDS)?.run()
            drain()
            assertTrue("Timed out waiting for recording lifecycle", condition())
        }
        fun session(): Pair<InputLanguage, Callbacks> {
            await { backend.sessions.isNotEmpty() }
            return backend.sessions.remove()
        }
        override fun close() {
            manager.cancelRecognition()
            backend.startGate?.countDown()
            backend.allowClose.countDown()
            onlineBackend.allowClose.countDown()
            anotherOnlineBackend.allowClose.countDown()
            manager.release()
            microphones.filterNot { it.released.get() }.forEach { it.record.release() }
            drain()
        }
    }

    @Test fun changingFromResidentLocalToOnlineReplacesTheActualBackend() = Fixture().use { f ->
        f.manager.startRecognition()
        f.session()
        f.manager.stopRecognition()
        f.backend.allowClose.countDown()
        f.await { f.backend.completedStops.get() == 1 }
        f.useLocal = false
        f.pluginId = "online-b"
        f.manager.startRecognition()
        f.await { f.onlineBackend.sessions.isNotEmpty() }
        assertEquals(1, f.backend.events.count { it.startsWith("start:") })
        assertEquals(1, f.onlineBackend.events.count { it.startsWith("start:") })
        assertTrue(f.errors.isEmpty())
    }

    @Test fun changingOnlinePluginReplacesBackendBeforeNextRecording() = Fixture(preload = false).use { f ->
        f.useLocal = false
        f.pluginId = "online-a"
        f.manager.startRecognition()
        f.await { f.onlineBackend.sessions.isNotEmpty() }
        f.manager.stopRecognition()
        f.onlineBackend.allowClose.countDown()
        f.await { f.onlineBackend.completedStops.get() == 1 }

        f.pluginId = "online-c"
        f.manager.startRecognition()
        f.await { f.anotherOnlineBackend.sessions.isNotEmpty() }
        assertEquals(1, f.onlineBackend.events.count { it.startsWith("start:") })
        assertEquals(1, f.anotherOnlineBackend.events.count { it.startsWith("start:") })
        assertTrue(f.errors.isEmpty())
    }

    @Test fun changingOnlineToLocalUsesNewLocalBackend() = Fixture(preload = false).use { f ->
        f.useLocal = false
        f.pluginId = "online-a"
        f.manager.startRecognition()
        f.await { f.onlineBackend.sessions.isNotEmpty() }
        f.manager.stopRecognition()
        f.onlineBackend.allowClose.countDown()
        f.await { f.onlineBackend.completedStops.get() == 1 }

        f.useLocal = true
        f.manager.startRecognition()
        f.session()
        assertEquals(1, f.onlineBackend.events.count { it.startsWith("start:") })
        assertEquals(1, f.backend.events.count { it.startsWith("start:") })
        assertTrue(f.errors.isEmpty())
    }

    @Test fun coldJapaneseRecordingInitializesInJapaneseAndDiscardsWarmupCallbacks() = Fixture(preload = false).use { f ->
        assertTrue(f.backend.initializations.isEmpty())
        f.manager.startRecognition(InputLanguage.JAPANESE)
        val active = f.session()
        f.await { f.manager.getState() == RecognitionState.LISTENING }

        assertEquals(listOf(InputLanguage.JAPANESE), f.backend.initializations.toList())
        assertEquals(InputLanguage.JAPANESE, active.first)
        assertTrue("Warmup results must never enter the editor", f.results.isEmpty())
        assertTrue(f.errors.isEmpty())
        active.second.final("日本語")
        f.drain()
        assertEquals(listOf("日本語"), f.results)
    }

    // 压低音量 = 原音量的三分之一（11-24 改，原来五分之一）：10 → 3。
    // 2026-10-31 tablet user: right after the keyboard opens, the first short phrase gave no text.
    // Releasing (or tapping stop) while the model still loaded threw the captured audio away.
    @Test fun stoppingWhileTheModelLoadsStillRecognizesWhatWasSaid() = Fixture(preload = false, duckMedia = true).use { f ->
        f.backend.initGate = CountDownLatch(1)
        f.manager.startRecognition()
        f.manager.startPreStart()
        val mic = f.microphones.single()
        assertEquals(listOf(3), f.mediaVolumes.toList())
        mic.feed(1000)
        mic.feed(1001)
        // The keyboard ends the voice session the same way endVoiceSession does: pre-start first, then stop.
        f.manager.cancelPreStart()
        f.manager.stopRecognition()
        assertEquals(listOf(3, 10), f.mediaVolumes.toList())
        assertFalse("The captured audio must survive until the model is ready", mic.released.get())

        f.backend.initGate?.countDown()
        f.session()
        f.backend.allowClose.countDown()
        f.await { f.backend.completedStops.get() == 1 }
        assertEquals(listOf(1000.toShort(), 1001.toShort()), f.backend.audio.map { (_, pcm) -> pcm.toShortSample() })
        assertEquals(listOf("closing result"), f.results)
        f.await { mic.released.get() }
        assertEquals("Decoding saved audio must not lower volume again", listOf(3, 10), f.mediaVolumes.toList())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun cancellingWhileTheModelLoadsStillDiscardsTheAudio() = Fixture(preload = false, duckMedia = true).use { f ->
        f.backend.initGate = CountDownLatch(1)
        f.manager.startRecognition()
        f.manager.startPreStart()
        val mic = f.microphones.single()
        mic.feed(1000)
        f.manager.cancelPreStart()
        f.manager.cancelRecognition()
        assertTrue(mic.released.get())
        assertEquals(listOf(3, 10), f.mediaVolumes.toList())

        f.backend.initGate?.countDown()
        f.await { f.manager.getState() == RecognitionState.IDLE }
        assertTrue(f.backend.sessions.isEmpty())
        assertTrue(f.backend.audio.isEmpty())
        assertTrue(f.results.isEmpty())
    }

    @Test fun confirmedPreStartKeepsMediaLowAcrossHandoffToLiveRecording() = Fixture(preload = false, duckMedia = true).use { f ->
        f.backend.initGate = CountDownLatch(1)
        f.manager.startPreStart()
        assertTrue("A pending gesture alone must leave media alone", f.mediaVolumes.isEmpty())
        f.manager.startRecognition()
        assertEquals(listOf(3), f.mediaVolumes.toList())
        f.backend.initGate?.countDown()
        f.session()
        assertEquals(listOf(3), f.mediaVolumes.toList())
        f.manager.stopRecognition()
        assertEquals(listOf(3, 10), f.mediaVolumes.toList())
    }

    private fun ByteArray.toShortSample(): Short = ((this[1].toInt() shl 8) or (this[0].toInt() and 0xFF)).toShort()

    @Test fun cancelledLanguageCannotWriteIntoQueuedNewLanguage() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.CHINESE)
        val old = f.session()
        assertEquals(InputLanguage.CHINESE, old.first)
        old.second.partial?.invoke("queued old partial")
        f.manager.cancelRecognition()
        assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))
        f.manager.startRecognition(InputLanguage.JAPANESE)
        f.drain()
        assertTrue("Cannot start while old backend is closing", f.backend.sessions.isEmpty())
        assertTrue(f.results.isEmpty())
        f.backend.allowClose.countDown()
        val next = f.session()
        assertEquals(InputLanguage.JAPANESE, next.first)
        f.await { f.manager.getState() == RecognitionState.LISTENING }
        old.second.final("late old final")
        old.second.error("late old error")
        old.second.state(RecognitionState.ERROR)
        next.second.partial?.invoke("日本語")
        f.drain()
        assertEquals(listOf("日本語"), f.results)
        assertTrue(f.errors.isEmpty())
        assertEquals(RecognitionState.LISTENING, f.manager.getState())
    }

    @Test fun restartingDuringNormalStopDiscardsPreviousResultAndWaitsForShutdown() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.ENGLISH)
        f.session()
        f.manager.stopRecognition()
        assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))
        f.manager.startRecognition(InputLanguage.JAPANESE)
        assertTrue(f.backend.sessions.isEmpty())
        f.backend.allowClose.countDown()
        assertEquals(InputLanguage.JAPANESE, f.session().first)
        assertTrue(f.results.isEmpty())
    }

    @Test fun restartingDuringAStopTellsTheBackendToStopWaitingForTheDiscardedFinal() = Fixture().use { f ->
        // 2026-11-16：连按时新会话要等上一次的整段重识别做完，首字慢；那份结果反正会被作废。
        f.backend.abandonReleasesStop = true
        f.manager.startRecognition(InputLanguage.ENGLISH)
        f.session()
        f.manager.stopRecognition()
        assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))
        f.manager.startRecognition(InputLanguage.JAPANESE)
        assertEquals("the new session starts without the test releasing the old stop", InputLanguage.JAPANESE, f.session().first)
        assertEquals(1, f.backend.abandons.get())
        f.drain()
        assertTrue("the old session's final never reaches the editor", f.results.isEmpty())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun cancelledQueuedStartReleasesItsMicrophoneWithoutStartingAnotherBackendSession() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.ENGLISH)
        f.session()
        f.manager.cancelRecognition()
        assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))
        f.manager.startRecognition(InputLanguage.JAPANESE)
        f.await { f.microphones.size == 2 && f.microphones[1].starts.get() == 1 }
        val waitingMicrophone = f.microphones[1]
        waitingMicrophone.feed(251)
        f.await { waitingMicrophone.reads.get() == 1 }
        f.manager.cancelRecognition()
        f.await { waitingMicrophone.released.get() }
        f.backend.allowClose.countDown()
        f.await { f.manager.getState() == RecognitionState.IDLE }
        assertTrue(f.backend.sessions.isEmpty())
        assertEquals(2, f.allocations)
        assertEquals(1, waitingMicrophone.releases.get())
        assertTrue("Cancelled buffered audio must not reach the old or a new backend session", f.backend.audio.isEmpty())
        assertTrue(f.results.isEmpty())
    }

    @Test fun repeatedRestartCapturesFirstAudioWhileOldStopBlocksThenDeliversItInOrder() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.ENGLISH)
        f.session()
        var previousLanguage = InputLanguage.ENGLISH

        listOf(InputLanguage.JAPANESE, InputLanguage.CHINESE).forEachIndexed { index, nextLanguage ->
            if (index > 0) f.backend.blockNextStop()
            f.manager.stopRecognition()
            assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))
            f.manager.startRecognition(nextLanguage)
            f.await { f.microphones.size == index + 2 && f.microphones[index + 1].starts.get() == 1 }
            val newMicrophone = f.microphones[index + 1]
            val markers = listOf(251 + index * 10, 252 + index * 10, 253 + index * 10)
            markers.forEach(newMicrophone::feed)
            f.await { newMicrophone.reads.get() == markers.size }
            f.await { f.manager.getState() == RecognitionState.LISTENING }
            assertTrue("Capture must not reuse the backend before its previous stop returns", f.backend.sessions.isEmpty())
            assertTrue("New audio must be buffered, not sent to the previous recognizer session", f.backend.audio.isEmpty())

            f.backend.allowClose.countDown()
            assertEquals(nextLanguage, f.session().first)
            f.await { f.backend.audio.size == markers.size }
            val delivered = markers.map { f.backend.audio.remove() }
            assertEquals(List(markers.size) { nextLanguage }, delivered.map { it.first })
            delivered.zip(markers).forEach { (chunk, marker) ->
                val expectedPcm = ByteArray(3200) { offset ->
                    (if (offset % 2 == 0) marker and 0xff else marker ushr 8).toByte()
                }
                assertArrayEquals("The first spoken chunks must survive startup unchanged and in order", expectedPcm, chunk.second)
            }
            val stopEnd = f.backend.events.indexOf("stop-end:${previousLanguage.id}")
            val nextStart = f.backend.events.indexOf("start:${nextLanguage.id}")
            assertTrue("Backend start must follow the previous backend stop", stopEnd >= 0 && nextStart > stopEnd)
            assertTrue("Previous session's final callback must not enter the new editor session", f.results.isEmpty())
            previousLanguage = nextLanguage
        }
        assertTrue(f.errors.isEmpty())
    }

    @Test fun backendStartupFailureReleasesTheAlreadyListeningMicrophone() = Fixture().use { f ->
        f.backend.startSucceeds = false
        f.backend.allowClose.countDown()
        f.manager.startRecognition(InputLanguage.CHINESE)
        f.session()
        f.await { f.errors.isNotEmpty() && f.microphones.single().released.get() }

        assertEquals("Microphone capture must start before waiting for the recognizer", 1, f.microphones.single().starts.get())
        assertEquals(1, f.microphones.single().releases.get())
        assertTrue("Partial backend initialization must be cancelled", f.backend.closing.await(3, TimeUnit.SECONDS))
        f.await { f.backend.completedStops.get() == 1 }
        assertEquals(RecognitionState.ERROR, f.manager.getState())
        assertTrue(f.backend.audio.isEmpty())
        assertTrue(f.results.isEmpty())
    }

    @Test fun backendStartupFailureKeepsTheBackendsReasonVisible() = Fixture().use { f ->
        val reason = "所选中文语音模型尚未完整下载，请在模型管理中下载"
        f.backend.startSucceeds = false
        f.backend.startError = reason
        f.backend.allowClose.countDown()
        f.manager.startRecognition(InputLanguage.CHINESE)
        f.session()
        f.await { f.errors.any { it.startsWith("录音已停止") } && f.microphones.single().released.get() }

        // The toast and keyboard notice show the last error; it must still say why, not only "启动语音引擎失败".
        assertTrue(f.errors.toString(), f.errors.last().contains(reason))
        assertTrue(f.errors.toString(), f.errors.none { it.contains("启动语音引擎失败") })
    }

    @Test fun cancellingQueuedRecordingDoesNotLetThirdRecordingBypassTheOriginalBackendStop() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.ENGLISH)
        val original = f.session()
        f.manager.stopRecognition()
        assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))

        f.manager.startRecognition(InputLanguage.JAPANESE)
        f.await { f.microphones.size == 2 && f.microphones[1].starts.get() == 1 }
        val cancelledMicrophone = f.microphones[1]
        cancelledMicrophone.feed(301)
        f.await { cancelledMicrophone.reads.get() == 1 }
        f.manager.cancelRecognition()
        f.await { cancelledMicrophone.released.get() }

        f.manager.startRecognition(InputLanguage.CHINESE)
        f.await { f.microphones.size == 3 && f.microphones[2].starts.get() == 1 }
        val finalMicrophone = f.microphones[2]
        finalMicrophone.feed(401)
        f.await { finalMicrophone.reads.get() == 1 }
        f.await { f.manager.getState() == RecognitionState.LISTENING }
        assertNull("Neither queued session may start while the original stop owns the backend",
            f.backend.sessions.poll(150, TimeUnit.MILLISECONDS))
        assertTrue(f.backend.audio.isEmpty())

        f.backend.allowClose.countDown()
        assertEquals(InputLanguage.CHINESE, f.session().first)
        f.await { f.backend.audio.size == 1 }
        val delivered = f.backend.audio.remove()
        assertEquals(InputLanguage.CHINESE, delivered.first)
        assertArrayEquals(ByteArray(3200) { if (it % 2 == 0) 0x91.toByte() else 0x01.toByte() }, delivered.second)
        assertEquals(listOf("start:en", "start:zh"), f.backend.events.filter { it.startsWith("start:") })
        assertTrue(f.backend.events.indexOf("stop-end:en") < f.backend.events.indexOf("start:zh"))
        original.second.final("old final after third start")
        f.drain()
        assertTrue(f.results.isEmpty())
        assertTrue(f.errors.isEmpty())
        assertFalse(finalMicrophone.released.get())
    }

    @Test fun startupAudioBufferOverflowReportsFailureAndStopsCaptureInsteadOfDroppingSpeech() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.ENGLISH)
        f.session()
        f.manager.stopRecognition()
        assertTrue(f.backend.closing.await(3, TimeUnit.SECONDS))
        f.manager.startRecognition(InputLanguage.JAPANESE)
        f.await { f.microphones.size == 2 && f.microphones[1].starts.get() == 1 }
        val waitingMicrophone = f.microphones[1]
        repeat(650) { waitingMicrophone.feed(300 + it) } // 60 s queue (600 chunks) must overflow
        f.await { f.errors.isNotEmpty() && waitingMicrophone.released.get() }

        assertTrue(f.errors.single().contains("准备时间过长"))
        assertTrue(f.errors.single().contains("已停止录音"))
        assertTrue("Overflow must not start another recognizer session", f.backend.sessions.isEmpty())
        assertTrue("Failed buffered speech must not leak into the previous session", f.backend.audio.isEmpty())
        f.backend.allowClose.countDown()
        f.await { f.backend.completedStops.get() == 1 }
        f.drain()
        assertEquals(RecognitionState.ERROR, f.manager.getState())
        assertTrue(f.backend.sessions.isEmpty())
        assertTrue(f.results.isEmpty())
    }

    @Test fun enablingKeepAliveBeforePendingReleaseRetainsTheInitializedBackend() = Fixture(initialKeepAlive = false).use { f ->
        f.backend.allowClose.countDown()
        f.manager.startRecognition(InputLanguage.ENGLISH)
        f.session()
        f.manager.stopRecognition()
        f.await { f.manager.getState() == RecognitionState.IDLE && f.delayed.any { it.second == 3500L } }
        val initializationCount = f.backend.initializations.size
        f.keepAlive = true
        f.delayed.filter { it.second == 3500L }.map { it.first }.distinct().forEach(Runnable::run)

        assertTrue(f.manager.preload())
        assertEquals("An already queued idle release must respect the newly enabled residency setting",
            initializationCount, f.backend.initializations.size)
        f.manager.startRecognition(InputLanguage.ENGLISH)
        assertEquals(InputLanguage.ENGLISH, f.session().first)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun overflowInterruptsBlockedBackendStartupWithoutUiStopAndAllowsTheNextRecording() = Fixture().use { f ->
        val blockedStart = CountDownLatch(1)
        f.backend.startGate = blockedStart
        f.backend.allowClose.countDown()
        val requestedAt = System.nanoTime()
        f.manager.startRecognition(InputLanguage.CHINESE)
        val failedSession = f.session()
        f.await { f.microphones.single().starts.get() == 1 }
        val failedMicrophone = f.microphones.single()
        repeat(650) { failedMicrophone.feed(500 + it) } // past the 60 s queue
        f.await { f.errors.isNotEmpty() && failedMicrophone.released.get() }
        assertTrue(f.errors.single().contains("准备时间过长"))
        assertEquals("The test must not unblock startup or issue a UI stop", 1L, blockedStart.count)
        assertTrue(f.backend.audio.isEmpty())

        // Exercise the real 8-second worker watchdog, without advancing a fake timer.
        f.await(timeoutSeconds = 12) { f.backend.startInterruptions.get() == 1 && f.backend.completedStops.get() == 1 }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt)
        assertTrue("Backend startup must be interrupted by the 8-second shutdown watchdog, elapsed=$elapsedMs ms",
            elapsedMs in 8000L..12000L)
        assertEquals(RecognitionState.ERROR, f.manager.getState())
        assertEquals(1, failedMicrophone.releases.get())
        assertTrue(f.results.isEmpty())

        f.backend.startGate = null
        f.manager.startRecognition(InputLanguage.ENGLISH)
        val next = f.session()
        assertEquals(InputLanguage.ENGLISH, next.first)
        f.await { f.microphones.size == 2 && f.microphones[1].starts.get() == 1 }
        f.microphones[1].feed(701)
        f.await { f.backend.audio.size == 1 && f.manager.getState() == RecognitionState.LISTENING }
        assertEquals(InputLanguage.ENGLISH, f.backend.audio.remove().first)
        failedSession.second.final("failed startup must not write into the next recording")
        next.second.final("recovered")
        f.drain()
        assertEquals(listOf("recovered"), f.results)
        assertEquals("The watchdog must not add a second cancellation error", 1, f.errors.size)
    }

    @Test fun backendStartupFailureAfterNormalStopStillReportsTheFailure() = Fixture().use { f ->
        val blockedStart = CountDownLatch(1)
        f.backend.startGate = blockedStart
        f.backend.startSucceeds = false
        f.backend.allowClose.countDown()
        f.manager.startRecognition(InputLanguage.CHINESE)
        f.session()
        f.await { f.microphones.single().starts.get() == 1 }
        f.microphones.single().feed(801)
        f.await { f.microphones.single().reads.get() == 1 }
        f.manager.stopRecognition()
        assertTrue(f.microphones.single().released.get())
        blockedStart.countDown()
        f.await { f.errors.isNotEmpty() && f.backend.completedStops.get() == 1 }

        assertTrue(f.errors.single().contains("启动语音引擎失败"))
        assertEquals(RecognitionState.ERROR, f.manager.getState())
        assertEquals(1, f.microphones.single().releases.get())
        assertTrue(f.backend.audio.isEmpty())
        assertTrue(f.results.isEmpty())
    }

    @Test fun activeSessionKeepsItsLanguageAndWarmupNeverWritesText() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.ENGLISH)
        val active = f.session()
        f.manager.startRecognition(InputLanguage.JAPANESE)
        active.second.final("English")
        f.drain()
        assertEquals(InputLanguage.ENGLISH, active.first)
        assertTrue(f.backend.sessions.isEmpty())
        assertEquals(listOf("English"), f.results)
    }

    @Test fun japaneseEnglishJapaneseSwitchKeepsOneMicrophoneAndPreservesCompletedText() = Fixture().use { f ->
        f.backend.allowClose.countDown()
        f.manager.startRecognition(InputLanguage.JAPANESE)
        val japanese = f.session()
        f.await { f.microphones.single().starts.get() == 1 }
        f.microphones.single().feed(101)
        f.await { f.backend.audio.isNotEmpty() }
        assertEquals(InputLanguage.JAPANESE, f.backend.audio.remove().first)
        f.manager.switchRecognitionLanguage(InputLanguage.ENGLISH)
        f.microphones.single().feed(102)
        val english = f.session()
        f.await { f.backend.audio.isNotEmpty() }
        assertEquals(InputLanguage.ENGLISH, english.first)
        assertEquals(InputLanguage.ENGLISH, f.backend.audio.remove().first)
        english.second.partial!!.invoke("hello world")
        f.drain()
        assertEquals("closing result hello world", f.results.last())
        japanese.second.final("stale Japanese")
        f.drain()
        assertFalse(f.results.any { it.contains("stale Japanese") })
        f.manager.switchRecognitionLanguage(InputLanguage.JAPANESE)
        f.microphones.single().feed(103)
        assertEquals(InputLanguage.JAPANESE, f.session().first)
        f.await { f.backend.audio.isNotEmpty() }
        assertEquals(InputLanguage.JAPANESE, f.backend.audio.remove().first)
        assertEquals(1, f.allocations)
        assertEquals(0, f.microphones.single().releases.get())
        assertTrue(f.errors.toString(), f.errors.isEmpty())
    }

    @Test fun compatibleBilingualModelDoesNotRestartWhenKeyboardLanguageChanges() = Fixture().use { f ->
        f.backend.compatibleLanguages = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        f.manager.startRecognition(InputLanguage.CHINESE)
        f.session()
        f.await { f.microphones.single().starts.get() == 1 }
        f.manager.switchRecognitionLanguage(InputLanguage.ENGLISH)
        f.microphones.single().feed(104)
        f.await { f.backend.audio.isNotEmpty() }
        assertTrue(f.backend.sessions.isEmpty())
        assertEquals(0, f.backend.completedStops.get())
        assertEquals(1, f.allocations)
    }

    @Test fun unknownLanguageIsRejectedBeforeMicrophoneAllocation() = Fixture().use { f ->
        f.manager.startRecognition(InputLanguage.UNSPECIFIED)
        f.drain()
        assertEquals(0, f.allocations)
        assertTrue(f.backend.sessions.isEmpty())
        assertEquals(RecognitionState.ERROR, f.manager.getState())
        assertTrue(f.errors.single().contains("未标注语言"))
        assertTrue(f.results.isEmpty())
    }
}
