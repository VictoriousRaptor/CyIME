package com.kingzcheung.xime.clipboard.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** Public UI state intentionally contains neither the proof nor the untrusted access token. */
data class WindowsPairingConfirmation(
    val requestId: Long,
    val computerName: String,
    val code: String,
    val ready: Boolean = false,
)

/** A nearby HTTP response can supply a candidate, but only local confirmation can commit it. */
internal class WindowsNearbyPairing(
    private val scope: CoroutineScope,
    private val makeRequest: (String) -> JSONObject,
    private val exchange: suspend (WindowsPeer, JSONObject) -> Pair<Int, JSONObject?>,
    private val commit: (WindowsPeer) -> Unit,
    private val status: (String) -> Unit,
    private val now: () -> Long,
) {
    private class Attempt(val id: Long, val deadline: Long) {
        var candidate: WindowsPeer? = null
    }
    private val _confirmation = MutableStateFlow<WindowsPairingConfirmation?>(null)
    val confirmation = _confirmation.asStateFlow()
    private var nextId = 0L
    private var attempt: Attempt? = null
    private var job: Job? = null

    fun connect(peer: WindowsPeer) {
        cancel()
        val proof = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val code = WindowsPairingProtocol.code(peer.fingerprint, proof)
        val current = Attempt(++nextId, now() + 120_000)
        attempt = current
        _confirmation.value = WindowsPairingConfirmation(current.id, peer.name, code)
        status("等待电脑响应，请核对自己电脑上的连接码")
        job = scope.launch {
            try {
                withTimeout(120_000) {
                    val body = makeRequest(proof)
                    while (true) {
                        if (attempt !== current) return@withTimeout
                        if (now() >= current.deadline) { expire(current); return@withTimeout }
                        val (httpStatus, json) = exchange(peer, body)
                        ensureActive()
                        if (attempt !== current) return@withTimeout
                        if (now() >= current.deadline) { expire(current); return@withTimeout }
                        if (httpStatus == 201) {
                            current.candidate = WindowsPairingProtocol.received(peer, requireNotNull(json))
                            _confirmation.value = _confirmation.value?.copy(ready = true)
                            status("对方已响应；请核对自己电脑上显示的连接码，再在此确认")
                            // The response is not proof of consent. Keep the original deadline
                            // running while waiting for an explicit action on this device.
                            awaitCancellation()
                        }
                        check(httpStatus == 202) { "电脑未允许连接（$httpStatus），请开启加密连接和附近发现" }
                        delay(8_000)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                expire(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt === current) {
                    clear(current)
                    status(failureMessage(e))
                }
            }
        }
    }

    fun confirm(requestId: Long): Boolean {
        val current = attempt ?: return false
        if (current.id != requestId) return false
        // elapsedRealtime also advances during device sleep, unlike a delayed UI callback.
        if (now() >= current.deadline) { expire(current); job?.cancel(); return false }
        val peer = current.candidate ?: return false
        commit(peer)
        cancel()
        return true
    }

    fun cancel() {
        attempt?.let { clear(it) }
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * 搜得到电脑、连不上（#500）：发现靠电脑往外发的广播，连接是手机连进电脑，电脑的网络是「公用网络」时
         * Windows 防火墙只挡后者，手机原来只显示 OkHttp 的英文超时。证书不对（SSLException）照旧说原因。
         */
        const val UNREACHABLE = "连不上这台电脑，多半是电脑的防火墙挡住了手机：请在电脑上把当前网络设为「专用网络」，再重试"

        fun failureMessage(e: Exception, fallback: String = "无法连接电脑"): String =
            if (e is java.io.IOException && e !is javax.net.ssl.SSLException) UNREACHABLE else e.message ?: fallback
    }

    private fun clear(current: Attempt) {
        current.candidate = null
        if (attempt === current) { attempt = null; _confirmation.value = null }
    }

    private fun expire(current: Attempt) {
        if (attempt === current) {
            clear(current)
            status("连接确认已超时，请重新选择电脑或扫码连接")
        }
    }
}
