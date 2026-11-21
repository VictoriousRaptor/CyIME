package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*

/** The shared owner must publish one backend regardless of which startup path arrives first. */
class AsrSupportStartupTest {
    @Test fun creatingCaptureBackendDoesNotWaitForInProgressWarmup() {
        withSupport { context, backends ->
            val backend = AsrSupport.create(context)
            val entered = java.util.concurrent.CountDownLatch(1)
            val finish = java.util.concurrent.CountDownLatch(1)
            whenever(backends.constructed().single().initialize(any())).thenAnswer {
                entered.countDown()
                check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                true
            }
            val warming = Thread { AsrSupport.warmup(context) }.apply { start() }
            val result = java.util.concurrent.CompletableFuture<AsrBackend?>()
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                Thread { result.complete(AsrSupport.create(context)) }.start()
                assertSame(backend, result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            } finally { finish.countDown(); warming.join(2000) }
        }
    }
    private fun withSupport(test: (Context, MockedConstruction<OfflineAsrBackend>) -> Unit) {
        val context = mock<Context>()
        val application = mock<Context>()
        whenever(context.applicationContext).thenReturn(application)
        val preferences = mock<android.content.SharedPreferences>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.getBoolean(any(), any())).thenReturn(true)
        AsrSupport.releaseModel()
        mockConstruction(OfflineAsrBackend::class.java) { backend, construction ->
            assertSame("The shared backend must retain the application context", application, construction.arguments().first())
            whenever(backend.initialize(any())).thenReturn(true)
        }.use { backends ->
            try {
                test(context, backends)
            } finally {
                // Clear the singleton before the constructor scope ends, including after a failed assertion.
                AsrSupport.releaseModel()
            }
            backends.constructed().forEach { verify(it).releaseModel() }
        }
    }

    @Test fun createThenWarmupThenCreateSharesOneBackendAndDelegatesRepeatedInitialization() {
        withSupport { context, backends ->
            val first = AsrSupport.create(context)
            val backend = backends.constructed().single()
            assertSame(backend, first)
            verifyNoInteractions(backend)

            AsrSupport.warmup(context, InputLanguage.JAPANESE)
            assertSame("Warmup must use the backend already handed to the recording manager", first, AsrSupport.create(context))
            AsrSupport.warmup(context, InputLanguage.JAPANESE)
            assertSame(first, AsrSupport.create(context))

            assertEquals("All startup paths must share a single IPC owner", 1, backends.constructed().size)
            verify(backend, times(2)).initialize(InputLanguage.JAPANESE)
            verify(backend, never()).initialize()
            verify(backend, times(2)).initialize(any())
        }
    }

    @Test fun warmupThenCreateReusesTheWarmBackendAndKeepsEachRequestedLanguageExplicit() {
        withSupport { context, backends ->
            AsrSupport.warmup(context, InputLanguage.ENGLISH)
            val backend = backends.constructed().single()
            assertSame(backend, AsrSupport.create(context))

            AsrSupport.warmup(context, InputLanguage.JAPANESE)
            assertSame(backend, AsrSupport.create(context))
            assertEquals(1, backends.constructed().size)

            val ordered = inOrder(backend)
            ordered.verify(backend).initialize(InputLanguage.ENGLISH)
            ordered.verify(backend).initialize(InputLanguage.JAPANESE)
            verify(backend, times(2)).initialize(any())
            verify(backend, never()).initialize()
        }
    }
}
