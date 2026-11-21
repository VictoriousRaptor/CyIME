package com.kingzcheung.xime.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.CoroutineTestRule
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AsrInferenceBindingTest {
    @get:Rule val main = CoroutineTestRule()
    private class Host : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val registered = mutableListOf<ServiceConnection>()
        val removed = mutableListOf<ServiceConnection>()
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            registered += conn
            return true
        }
        override fun unbindService(conn: ServiceConnection) { removed += conn }
    }

    @Test fun concurrentStartsShareOneRegistrationAndReconnectAfterProcessLoss() = runTest {
        val host = Host()
        val client = AsrInferenceClient(host)
        val name = ComponentName(host, AsrInferenceService::class.java)
        val first = async { client.ensureBound() }
        val second = async { client.ensureBound() }
        runCurrent()
        val registration = host.registered.single()
        registration.onServiceConnected(name, Binder())
        assertTrue(first.await() && second.await())

        registration.onServiceDisconnected(name)
        val reconnect = async { client.ensureBound() }
        runCurrent()
        assertFalse(reconnect.isCompleted)
        // #336: a lost voice process is bound again on the next start instead of waiting for the system
        // to restart it (MIUI may not, until the app comes to the foreground).
        assertEquals(listOf(registration), host.removed)
        val fresh = host.registered.last()
        assertNotSame(registration, fresh)
        registration.onServiceConnected(name, Binder()) // the dropped binding's late callback is ignored
        assertFalse(reconnect.isCompleted)
        fresh.onServiceConnected(name, Binder())
        assertTrue(reconnect.await())
        fresh.onBindingDied(name)
        assertEquals(listOf(registration, fresh), host.removed)

        val retry = async { client.ensureBound() }
        runCurrent()
        fresh.onServiceConnected(name, Binder())
        assertFalse(retry.isCompleted)
        host.registered.last().onServiceConnected(name, Binder())
        assertTrue(retry.await())
        client.unbind()
        assertEquals(host.registered, host.removed)
    }

    @Test fun stalledOrNullBindingCanBeRetriedWithoutClearingAnything() = runTest {
        val host = Host()
        val client = AsrInferenceClient(host)
        val name = ComponentName(host, AsrInferenceService::class.java)
        assertFalse(client.ensureBound())
        assertEquals(host.registered, host.removed)
        val nullResult = async { client.ensureBound() }
        runCurrent()
        host.registered.last().onNullBinding(name)
        assertFalse(nullResult.await())
        val retry = async { client.ensureBound() }
        runCurrent()
        host.registered.last().onServiceConnected(name, Binder())
        assertTrue(retry.await())
        client.unbind()
        assertEquals(3, host.registered.size)
        assertEquals(host.registered, host.removed)
    }
}
