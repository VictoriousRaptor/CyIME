package com.kingzcheung.xime.speech

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.service.AsrInferenceClient
import com.kingzcheung.xime.settings.InputLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*

/** Exercises the real backend lifecycle; only model storage and the remote IPC client are replaced. */
class OfflineAsrStartupTest {
    @Test fun timeoutTracksTheActualSessionModelAndReturnsToTheNormalLimit() {
        withBackend({ language -> selection(if (language == InputLanguage.JAPANESE) SpeechModelCatalog.ZIPFORMER_JA else SpeechModelCatalog.PARAFORMER) }) { backend, _, _ ->
            assertTrue(backend.prepareForRecording(InputLanguage.JAPANESE))
            assertTrue(backend.start(InputLanguage.JAPANESE))
            assertEquals(18000L, backend.finishTimeoutMs)
            assertTrue(backend.start(InputLanguage.CHINESE))
            assertEquals(8000L, backend.finishTimeoutMs)
        }
    }
    @Test fun delayedManagerReleaseCannotStopOrDisableSharedBackend() {
        withBackend({ selection(SpeechModelCatalog.PARAFORMER) }) { backend, client, _ ->
            assertTrue(backend.prepareForRecording(InputLanguage.CHINESE))
            assertTrue(backend.start(InputLanguage.CHINESE))
            clearInvocations(client)
            backend.release()
            val pcm = ByteArray(3200)
            backend.processAudioChunk(pcm)
            verify(client).pushAsrAudio(pcm)
            runBlocking { verify(client, never()).stopAsr() }
            backend.stop()
            runBlocking { verify(client).stopAsr() }
        }
    }

    @Test fun unloadedBackendCannotResurrectAfterLocalSpeechIsDisabled() {
        withBackend({ selection(SpeechModelCatalog.PARAFORMER) }) { backend, client, _ ->
            backend.releaseModel()
            assertFalse(backend.prepareForRecording(InputLanguage.CHINESE))
            assertFalse(backend.initialize(InputLanguage.CHINESE))
            assertFalse(backend.start(InputLanguage.CHINESE))
            runBlocking { verify(client, never()).startAsr(any(), any(), anyOrNull()) }
        }
    }
    @Test fun onDemandPreparationDoesNotBindOrWarmBeforeMicrophoneCapture() {
        withBackend({ selection(SpeechModelCatalog.PARAFORMER) }) { backend, client, managers ->
            assertTrue(backend.prepareForRecording(InputLanguage.CHINESE))
            assertTrue(managers.constructed().isEmpty())
            verifyNoInteractions(client)
            assertTrue(backend.start(InputLanguage.CHINESE))
            runBlocking {
                verify(client).ensureBound()
                verify(client).startAsr(eq(SpeechModelCatalog.PARAFORMER), any(), eq(InputLanguage.CHINESE))
                verify(client, never()).stopAsr()
            }
        }
    }
    private fun selection(mode: String): AsrModelManager.Selection = mock {
        on { this.mode } doReturn mode
    }

    /** 手动计时器：记下排了哪个任务、延迟多久，测试里自己决定什么时候跑。 */
    private class ManualTimer : OfflineAsrBackend.IdleUnbindTimer {
        var task: Runnable? = null
        var delayMs: Long? = null
        override fun schedule(delayMs: Long, task: Runnable) { this.task = task; this.delayMs = delayMs }
        override fun cancel(task: Runnable) { if (this.task === task) { this.task = null; delayMs = null } }
        fun fire() { task?.also { task = null; delayMs = null }?.run() }
    }

    @Test fun idleUnbindWaitsForTheUnloadTimeAndNeverHappensWhenResident() {
        assertEquals(5 * 60_000L + OfflineAsrBackend.UNBIND_AFTER_RELEASE_MS, OfflineAsrBackend.idleUnbindDelayMs(5))
        assertNull(OfflineAsrBackend.idleUnbindDelayMs(0))
    }

    @Test fun finishedSessionUnbindsTheVoiceProcessAfterTheUnloadTime() {
        val timer = ManualTimer()
        withBackend({ selection(SpeechModelCatalog.PARAFORMER) }, engineIdleMinutes = 5, timer = timer) { backend, client, _ ->
            assertTrue(backend.prepareForRecording(InputLanguage.CHINESE))
            assertTrue(backend.start(InputLanguage.CHINESE))
            assertNull("No unbind is pending while the user is speaking", timer.task)
            backend.stop()
            assertEquals(5 * 60_000L + OfflineAsrBackend.UNBIND_AFTER_RELEASE_MS, timer.delayMs)
            timer.fire()
            verify(client).unbind()
            // 下一次按话筒重新绑定。
            assertTrue(backend.start(InputLanguage.CHINESE))
            runBlocking { verify(client, times(2)).ensureBound() }
        }
    }

    @Test fun newSessionCancelsAPendingUnbind() {
        val timer = ManualTimer()
        withBackend({ selection(SpeechModelCatalog.PARAFORMER) }, engineIdleMinutes = 5, timer = timer) { backend, client, _ ->
            assertTrue(backend.prepareForRecording(InputLanguage.CHINESE))
            assertTrue(backend.start(InputLanguage.CHINESE))
            backend.cancel()
            val pending = checkNotNull(timer.task)
            assertTrue(backend.start(InputLanguage.CHINESE))
            assertNull(timer.task)
            pending.run() // 计时器已经拿出来的任务晚到了：正在说话，不解绑。
            verify(client, never()).unbind()
        }
    }

    @Test fun residentVoiceModelKeepsTheProcessBound() {
        val timer = ManualTimer()
        withBackend({ selection(SpeechModelCatalog.PARAFORMER) }, engineIdleMinutes = 0, timer = timer) { backend, client, _ ->
            assertTrue(backend.prepareForRecording(InputLanguage.CHINESE))
            assertTrue(backend.start(InputLanguage.CHINESE))
            backend.stop()
            assertNull(timer.task)
            verify(client, never()).unbind()
        }
    }

    private fun withBackend(
        select: (InputLanguage) -> AsrModelManager.Selection,
        engineIdleMinutes: Int? = null,
        timer: OfflineAsrBackend.IdleUnbindTimer = ManualTimer(),
        test: (OfflineAsrBackend, AsrInferenceClient, MockedConstruction<AsrModelManager>) -> Unit,
    ) {
        val context = mock<Context>()
        val preferences = mock<SharedPreferences>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.getBoolean(any(), any())).thenReturn(true)
        if (engineIdleMinutes != null) whenever(preferences.getInt(
            eq(com.kingzcheung.xime.settings.SettingsPreferences.KEY_STT_ENGINE_IDLE_MINUTES), any())).thenReturn(engineIdleMinutes)
        // 整句定稿 keeps its own default (off) here; this test covers the normal finish limit.
        whenever(preferences.getBoolean(eq(com.kingzcheung.xime.settings.SettingsPreferences.KEY_STT_WHOLE_SENTENCE), any())).thenReturn(false)

        mockConstruction(AsrInferenceClient::class.java) { client, _ ->
            runBlocking {
                whenever(client.ensureBound()).thenReturn(true)
                whenever(client.startAsr(any(), any(), anyOrNull())).thenReturn(true)
                whenever(client.stopAsr()).thenReturn("")
            }
        }.use { clients ->
            mockConstruction(AsrModelManager::class.java) { manager, _ ->
                whenever(manager.selectionForLanguage(any())).thenAnswer { select(it.getArgument(0)) }
            }.use { managers ->
                val backend = OfflineAsrBackend(context, timer)
                test(backend, clients.constructed().single(), managers)
            }
        }
    }

    @Test fun japaneseWarmupUsesJapaneseAndRepeatedInitializationOnlyRebinds() {
        withBackend({ language ->
            assertEquals(InputLanguage.JAPANESE, language)
            selection(SpeechModelCatalog.SENSEVOICE)
        }) { backend, client, managers ->
            assertTrue(backend.initialize(InputLanguage.JAPANESE))
            assertTrue(backend.initialize(InputLanguage.JAPANESE))

            assertEquals("An initialized backend must not select or warm a model again", 1, managers.constructed().size)
            verify(managers.constructed().single()).selectionForLanguage(InputLanguage.JAPANESE)
            runBlocking {
                verify(client, times(2)).ensureBound()
                verify(client, times(2)).setKeepModelAlive(true)
                verify(client).startAsr(eq(SpeechModelCatalog.SENSEVOICE), any(), eq(InputLanguage.JAPANESE))
                verify(client).stopAsr()
                verify(client, times(1)).startAsr(any(), any(), anyOrNull())
                verify(client, never()).startAsr(any(), any(), isNull())
            }
        }
    }

    @Test fun defaultWarmupAndActualChineseSessionShareAnExplicitLanguageAndCallback() {
        withBackend({ language ->
            assertEquals(InputLanguage.CHINESE, language)
            selection(SpeechModelCatalog.ZIPFORMER_TWO_PASS)
        }) { backend, client, managers ->
            assertTrue(backend.initialize())
            assertTrue(backend.start(InputLanguage.CHINESE))

            assertEquals(2, managers.constructed().size)
            managers.constructed().forEach { verify(it).selectionForLanguage(InputLanguage.CHINESE) }
            val callbacks = argumentCaptor<AsrInferenceClient.AsrCallback>()
            runBlocking {
                verify(client, times(2)).startAsr(eq(SpeechModelCatalog.ZIPFORMER_TWO_PASS), callbacks.capture(), eq(InputLanguage.CHINESE))
                verify(client, times(2)).startAsr(any(), any(), anyOrNull())
                verify(client, never()).startAsr(any(), any(), isNull())
                verify(client).stopAsr()
            }
            assertSame("The recording start reinstalls the callback cleared by warmup stop", callbacks.firstValue, callbacks.secondValue)
        }
    }

    @Test fun eachSessionReselectsForItsLanguageAndTheCurrentModelPreference() {
        var preferredModel = SpeechModelCatalog.ZIPFORMER_TWO_PASS
        val selectedLanguages = mutableListOf<InputLanguage>()
        withBackend({ language ->
            selectedLanguages += language
            // Retain the real compatibility policy: a Chinese two-pass model cannot recognize Japanese.
            selection(SpeechLanguages.select(language, preferredModel) { true })
        }) { backend, client, managers ->
            assertTrue(backend.initialize(InputLanguage.CHINESE))
            assertTrue(backend.start(InputLanguage.JAPANESE))
            preferredModel = SpeechModelCatalog.PARAFORMER
            assertTrue(backend.start(InputLanguage.CHINESE))

            assertEquals(listOf(InputLanguage.CHINESE, InputLanguage.JAPANESE, InputLanguage.CHINESE), selectedLanguages)
            assertEquals(3, managers.constructed().size)
            managers.constructed().zip(selectedLanguages).forEach { (manager, language) ->
                verify(manager).selectionForLanguage(language)
            }
            val ordered = inOrder(client)
            runBlocking {
                ordered.verify(client).startAsr(eq(SpeechModelCatalog.ZIPFORMER_TWO_PASS), any(), eq(InputLanguage.CHINESE))
                ordered.verify(client).startAsr(eq(SpeechModelCatalog.SENSEVOICE), any(), eq(InputLanguage.JAPANESE))
                ordered.verify(client).startAsr(eq(SpeechModelCatalog.PARAFORMER), any(), eq(InputLanguage.CHINESE))
                verify(client, times(3)).startAsr(any(), any(), anyOrNull())
                verify(client, never()).startAsr(any(), any(), isNull())
                verify(client).stopAsr()
            }
        }
    }

    @Test fun unavailableWarmupResourcesDoNotPreventARealStartFromRetryingSelection() {
        var attempts = 0
        val errors = mutableListOf<String>()
        withBackend({ language ->
            assertEquals(InputLanguage.JAPANESE, language)
            attempts++
            if (attempts == 1) error("日语模型尚未完整下载")
            selection(SpeechModelCatalog.SENSEVOICE)
        }) { backend, client, managers ->
            backend.setCallbacks({}, null, {}, { errors += it })
            assertTrue("Binding succeeds even when optional model warmup is unavailable", backend.initialize(InputLanguage.JAPANESE))
            runBlocking { verify(client, never()).startAsr(any(), any(), anyOrNull()) }
            assertTrue(backend.start(InputLanguage.JAPANESE))

            assertEquals(2, attempts)
            assertEquals(2, managers.constructed().size)
            assertTrue("A successful retry must not report the earlier warmup as a recognition failure", errors.isEmpty())
            runBlocking {
                verify(client).startAsr(eq(SpeechModelCatalog.SENSEVOICE), any(), eq(InputLanguage.JAPANESE))
                verify(client, never()).startAsr(any(), any(), isNull())
            }
        }
    }
}
