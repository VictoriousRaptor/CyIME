package com.kingzcheung.xime.clipboard.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsNearbyPairingTest {
    private val peer = WindowsPeer("00112233-4455-6677-8899-aabbccddeeff", "测试电脑",
        "https://127.0.0.1:18740", "a".repeat(64))
    private fun reply(p: WindowsPeer = peer) = JSONObject().put("v", 2).put("server_id", p.id)
        .put("device_id", "11223344-5566-7788-99aa-bbccddeeff00").put("access_token", "b".repeat(43))

    private class Harness(val pairing: WindowsNearbyPairing, val saved: MutableList<WindowsPeer>, val messages: MutableList<String>)
    private fun TestScope.harness(
        now: () -> Long = { testScheduler.currentTime },
        exchange: suspend (WindowsPeer, JSONObject) -> Pair<Int, JSONObject?> = { _, _ -> 201 to reply() },
        store: ((WindowsPeer) -> Unit)? = null,
    ): Harness {
        val saved = mutableListOf<WindowsPeer>()
        val messages = mutableListOf<String>()
        return Harness(WindowsNearbyPairing(backgroundScope, { JSONObject().put("secret", it) }, exchange,
            { store?.invoke(it); saved.add(it) }, messages::add, now), saved, messages)
    }

    @Test fun unsolicited201DoesNotSaveTrustOrMakeATransportUsable() = runTest {
        var requests = 0
        val h = harness(exchange = { _, _ -> requests++; 201 to reply() })
        runCurrent()
        assertEquals(0, requests)
        h.pairing.connect(peer)
        runCurrent()
        assertTrue(h.pairing.confirmation.value!!.ready)
        assertTrue(h.saved.isEmpty())
        val transport = WindowsSecureTransport({ _, _, _, _, _ -> error("Unconfirmed peer reached transport") }, { h.saved.lastOrNull() })
        assertNotNull(transport.testConnection())
        assertTrue(h.saved.isEmpty())
    }

    @Test fun explicitConfirmationSavesExactlyTheDisplayedAttemptOnce() = runTest {
        var proof = ""
        val h = harness(exchange = { _, body -> proof = body.getString("secret"); 201 to reply() })
        h.pairing.connect(peer); runCurrent()
        val shown = h.pairing.confirmation.value!!
        assertEquals(WindowsPairingProtocol.code(peer.fingerprint, proof), shown.code)
        assertEquals(peer.name, shown.computerName)
        assertTrue(h.pairing.confirm(shown.requestId))
        assertEquals(listOf(peer.copy(token = "b".repeat(43))), h.saved)
        assertNull(h.pairing.confirmation.value)
        assertFalse(h.pairing.confirm(shown.requestId))
        assertEquals(1, h.saved.size)
    }

    @Test fun pendingRemoteApprovalCannotBeConfirmedAndPollingKeepsTheSameProof() = runTest {
        val proofs = mutableListOf<String>()
        val h = harness(exchange = { _, body -> proofs += body.getString("secret"); if (proofs.size == 1) 202 to null else 201 to reply() })
        h.pairing.connect(peer); runCurrent()
        val id = h.pairing.confirmation.value!!.requestId
        assertFalse(h.pairing.confirm(id))
        advanceTimeBy(8_000); runCurrent()
        assertEquals(2, proofs.size)
        assertEquals(proofs[0], proofs[1])
        assertTrue(h.pairing.confirm(id))
    }

    /** #500：搜得到电脑但连不进去（电脑防火墙挡住），说清原因，不显示英文超时。 */
    @Test fun unreachableComputerSaysWhatToChange() = runTest {
        val h = harness(exchange = { _, _ -> throw java.net.SocketTimeoutException("failed to connect to /192.168.1.5 (port 18740) after 4000ms") })
        h.pairing.connect(peer); runCurrent()
        assertNull(h.pairing.confirmation.value)
        assertEquals(WindowsNearbyPairing.UNREACHABLE, h.messages.last())
        // A changed certificate keeps its own reason.
        assertEquals("电脑证书已变化，请重新配对",
            WindowsNearbyPairing.failureMessage(javax.net.ssl.SSLHandshakeException("电脑证书已变化，请重新配对")))
        assertEquals("电脑未允许连接（403），请开启加密连接和附近发现",
            WindowsNearbyPairing.failureMessage(IllegalStateException("电脑未允许连接（403），请开启加密连接和附近发现")))
    }

    @Test fun cancellationDiscardsUnconfirmedCredentials() = runTest {
        val h = harness()
        h.pairing.connect(peer); runCurrent()
        val id = h.pairing.confirmation.value!!.requestId
        h.pairing.cancel()
        advanceTimeBy(120_000); runCurrent()
        assertNull(h.pairing.confirmation.value)
        assertFalse(h.pairing.confirm(id))
        assertTrue(h.saved.isEmpty())
    }

    @Test fun localApprovalExpiresEvenAfterTheServerHasResponded() = runTest {
        val h = harness()
        h.pairing.connect(peer); runCurrent()
        val id = h.pairing.confirmation.value!!.requestId
        advanceTimeBy(120_000); runCurrent()
        assertNull(h.pairing.confirmation.value)
        assertFalse(h.pairing.confirm(id))
        assertTrue(h.saved.isEmpty())
        assertTrue(h.messages.last().contains("超时"))
    }

    @Test fun wakingFromSleepCannotConfirmBeforeTheTimeoutCallbackRuns() = runTest {
        var elapsed = 1_000L
        val h = harness(now = { elapsed })
        h.pairing.connect(peer); runCurrent()
        val id = h.pairing.confirmation.value!!.requestId
        elapsed += 120_000
        assertFalse(h.pairing.confirm(id))
        assertNull(h.pairing.confirmation.value)
        assertTrue(h.saved.isEmpty())
    }

    @Test fun cancelledLateReplyCannotReplaceANewerAttempt() = runTest {
        var calls = 0
        val h = harness(exchange = { p, _ ->
            if (++calls == 1) withContext(NonCancellable) { delay(10_000) }
            201 to reply(p)
        })
        h.pairing.connect(peer); runCurrent()
        val oldId = h.pairing.confirmation.value!!.requestId
        val next = peer.copy(id = "11223344-5566-7788-99aa-bbccddeeff00", name = "另一台电脑")
        h.pairing.connect(next); runCurrent()
        val newId = h.pairing.confirmation.value!!.requestId
        advanceTimeBy(10_000); runCurrent()
        assertEquals(newId, h.pairing.confirmation.value!!.requestId)
        assertFalse(h.pairing.confirm(oldId))
        assertTrue(h.pairing.confirm(newId))
        assertEquals(next.id, h.saved.single().id)
    }

    @Test fun wakingAfterTheWindowDoesNotSendAnotherPairingRequest() = runTest {
        var elapsed = 0L
        var requests = 0
        val h = harness(now = { elapsed }, exchange = { _, _ -> requests++; 202 to null })
        h.pairing.connect(peer); runCurrent()
        elapsed = 120_000
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1, requests)
        assertNull(h.pairing.confirmation.value)
        assertTrue(h.saved.isEmpty())
    }

    @Test fun cancellingWhileRequestIsInFlightRejectsItsLateSuccess() = runTest {
        val h = harness(exchange = { _, _ -> withContext(NonCancellable) { delay(1_000) }; 201 to reply() })
        h.pairing.connect(peer); runCurrent()
        h.pairing.cancel(); advanceTimeBy(1_000); runCurrent()
        assertNull(h.pairing.confirmation.value)
        assertTrue(h.saved.isEmpty())
    }

    @Test fun malformedSuccessCannotBecomeAConfirmation() = runTest {
        for (json in listOf(reply().put("server_id", "wrong"), reply().put("v", 1),
            reply().put("device_id", "invalid"), reply().put("access_token", "invalid"))) {
            val h = harness(exchange = { _, _ -> 201 to json })
            h.pairing.connect(peer); runCurrent()
            assertNull(h.pairing.confirmation.value)
            assertTrue(h.saved.isEmpty())
        }
    }

    @Test fun storageFailureAllowsLocalRetryButNeverExtendsApprovalDeadline() = runTest {
        var failures = 1
        val h = harness(store = { if (failures-- > 0) error("fixture storage failed") })
        h.pairing.connect(peer); runCurrent()
        val id = h.pairing.confirmation.value!!.requestId
        assertTrue(runCatching { h.pairing.confirm(id) }.isFailure)
        assertTrue(h.saved.isEmpty())
        assertTrue(h.pairing.confirmation.value!!.ready)
        assertTrue(h.pairing.confirm(id))
        assertEquals(1, h.saved.size)
    }

    @Test fun serverErrorsAndNeverApprovedRequestsLeaveNoTrust() = runTest {
        for (status in listOf(202, 401, 403, 500)) {
            val h = harness(exchange = { _, _ -> status to null })
            h.pairing.connect(peer); runCurrent()
            advanceTimeBy(120_000); runCurrent()
            assertNull(h.pairing.confirmation.value)
            assertTrue(h.saved.isEmpty())
        }
    }
}
