package com.kingzcheung.xime.clipboard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import com.kingzcheung.xime.plugin.SecureValueCipher
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Native discovery and credentials; transport uses the existing clipboard session scheduler.
 * Several computers (or other CyIME devices) can be saved. A copy is pushed to all of them, and
 * each of them pushes to this device (it registers itself with them), so nothing polls: devices
 * only check in once after the screen comes on or the network changes (用户 11-02 选的做法 A).
 * The one shown as connected is the active one; an older computer that cannot push still gets
 * the old 2-second polling while the keyboard is shown.
 */
object WindowsDevices {
    /** Upper bound on saved devices: home, office, laptop, tablet… */
    const val MAX_SAVED = 8
    const val ID = "builtin.windows.secure"
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _nearby = MutableStateFlow<List<WindowsPeer>>(emptyList())
    val nearby = _nearby.asStateFlow()
    private val _status = MutableStateFlow("正在寻找附近电脑")
    val status = _status.asStateFlow()
    /** The saved device the transport currently talks to. Non-null whenever anything is saved. */
    private val _paired = MutableStateFlow<WindowsPeer?>(null)
    val paired = _paired.asStateFlow()
    /** Every saved device, most recently connected first. */
    private val _saved = MutableStateFlow<List<WindowsPeer>>(emptyList())
    val saved = _saved.asStateFlow()
    /** Saved device id → whether its last connection check succeeded. */
    private val _reachable = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val reachable = _reachable.asStateFlow()
    private val nearbyPairing = WindowsNearbyPairing(scope,
        makeRequest = { proof -> request(UUID.randomUUID().toString(), proof) },
        exchange = { peer, body -> withContext(Dispatchers.IO) { WindowsSecureTransport.request(peer, "/api/pair/nearby", body) } },
        commit = ::commitPairing, status = { _status.value = it }, now = SystemClock::elapsedRealtime)
    val confirmation = nearbyPairing.confirmation
    val transport = WindowsSecureTransport({ _paired.value }, { _saved.value },
        onUnreachable = { scope.launch { discoverBriefly() } })
    /** Saved device id → whether it pushes to this device (false = an older one that must be polled). */
    private val pushReady = mutableMapOf<String, Boolean>()
    private val _needsPolling = MutableStateFlow(false)
    /** Some saved device cannot push to this one, so the keyboard keeps polling it as before. */
    val needsPolling = _needsPolling.asStateFlow()
    /** 亮屏或网络变化后，第一次打开键盘时报到一次。 */
    private var catchUpDue = true
    /** Set by the clipboard bridge: pull once now (the network changed while the keyboard is shown). */
    var onCatchUp: (() -> Unit)? = null
    private var briefDiscovery: Job? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolver: Job? = null
    private var pairing: Job? = null
    private var persisted: List<WindowsPeer>? = null
    private var activeFailures = 0
    private var settingsUsers = 0
    /** 键盘正在显示。收起后不在后台找电脑、查连接。 */
    private var keyboardShown = false
    private var monitor: Job? = null
    private val reconnect = Channel<Unit>(Channel.CONFLATED)
    private var retryDiscovery: Job? = null
    private var discoveryFailures = 0
    private var generation = 0L
    private val isPairing get() = pairing?.isActive == true || confirmation.value != null
    private val prefs get() = context.getSharedPreferences("windows_device_pairing", Context.MODE_PRIVATE)

    fun initialize(value: Context) {
        if (::context.isInitialized) return
        context = value.applicationContext
        DeviceHost.initialize(context)
        try {
            val list = prefs.getString("peers", null)?.let { stored ->
                val array = JSONArray(requireNotNull(SecureValueCipher.decrypt(stored)))
                (0 until array.length()).map { peerFrom(array.getJSONObject(it)) }
            } ?: prefs.getString("peer", null)?.let { legacy ->
                // Single-computer pairing from before multi-device support.
                listOf(peerFrom(JSONObject(requireNotNull(SecureValueCipher.decrypt(legacy)))))
            } ?: emptyList()
            _saved.value = list.distinctBy { it.id }.take(MAX_SAVED)
            _paired.value = _saved.value.firstOrNull()
            persisted = _saved.value
            if (_saved.value.isNotEmpty()) _status.value = "已配对 ${savedNames()}，同网自动重连"
        } catch (_: Exception) { _status.value = "配对凭据无法读取，请重新配对；原记录已保留" }
        // 只记一个“该报到了”，不连网：等键盘弹出时再连。
        runCatching {
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) { catchUpDue = true }
            }, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch { networkChanged() } }
            })
        }
    }
    private fun networkChanged() {
        catchUpDue = true
        if (!keyboardShown) return
        catchUpDue = false
        startMonitor(); reconnect.trySend(Unit); onCatchUp?.invoke()
    }
    private fun peerFrom(json: JSONObject) = WindowsPeer(json.getString("id"), json.getString("name"),
        WindowsPairingProtocol.localOrigin(json.getString("origin")),
        WindowsPairingProtocol.fingerprint(json.getString("fingerprint")), json.getString("token"), json.optString("client_id"))
    private fun savedNames() = _saved.value.joinToString("、") { it.name }
    /** Replace one saved record in place (address refresh or rename) without changing trust. */
    private fun updateSaved(peer: WindowsPeer) {
        _saved.value = _saved.value.map { if (it.id == peer.id) peer else it }
        if (_paired.value?.id == peer.id) _paired.value = peer
    }

    /** Settings page: look for nearby devices and check the saved ones now. */
    fun start() { startDiscovery(); startMonitor(); reconnect.trySend(Unit) }

    /** mDNS runs only on the settings page, or for 10 s after a saved device stopped answering. */
    private fun discoverBriefly() {
        if (!::context.isInitialized || listener != null) return
        startDiscovery()
        briefDiscovery?.cancel()
        briefDiscovery = scope.launch { delay(10_000); if (settingsUsers == 0) stopDiscovery() }
    }

    private fun startDiscovery() {
        if (listener != null) return
        val epoch = ++generation
        val nsd = context.getSystemService(NsdManager::class.java)
        val queue = Channel<NsdServiceInfo>(32)
        resolver = scope.launch {
            for (service in queue) {
                val result = withTimeoutOrNull(5000) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (continuation.isActive) continuation.resume(null) }
                            override fun onServiceResolved(info: NsdServiceInfo) { if (continuation.isActive) continuation.resume(info) }
                        })
                    }
                }
                if (epoch != generation) break
                if (result == null) {
                    if (!isPairing && _paired.value == null)
                        _status.value = "发现电脑但无法解析地址，请更新电脑端或使用扫码连接"
                    continue
                }
                runCatching {
                    fun attr(key: String) = result.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
                    val kind = attr("kind")
                    if (attr("v") != "2" || (kind != "windows" && kind != "android")) return@runCatching
                    val id = UUID.fromString(attr("id")).toString()
                    val address = result.host?.hostAddress ?: return@runCatching
                    val host = if (address.contains(':')) "[$address]" else address
                    val origin = WindowsPairingProtocol.localOrigin("https://$host:${result.port}")
                    val known = _saved.value.firstOrNull { it.id == id }
                    if (known != null) {
                        // Discovery only supplies an address. Keep the previously trusted pin/token.
                        val moved = known.copy(origin = origin)
                        updateSaved(moved)
                        val active = _paired.value
                        if (active?.id == id || activeFailures > 0) {
                            // The active device is unreachable: a saved one announcing itself here takes over.
                            if (active?.id != id) { _paired.value = moved; activeFailures = 0 }
                            _status.value = "正在连接 ${known.name}…"
                            reconnect.trySend(Unit)
                        }
                    }
                    // Other CyIME phones/tablets pair only by scanning their QR code.
                    if (kind == "android") return@runCatching
                    val pin = attr("fp").ifEmpty { known?.fingerprint.orEmpty() }
                    val peer = WindowsPeer(id, attr("name").filterNot { it.isISOControl() }.take(64), origin, WindowsPairingProtocol.fingerprint(pin))
                    _nearby.value = (_nearby.value.filterNot { it.id == id } + peer).take(16)
                    // An unauthenticated discovery announcement never starts or approves pairing.
                }
            }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                scope.launch { if (epoch == generation) discoveryFailed("附近设备发现失败（$code），正在重试；也可以扫码连接") }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                val id = info.serviceName.removePrefix("CyIME-").replace("-", "")
                scope.launch { if(epoch == generation) _nearby.value = _nearby.value.filterNot { it.id.replace("-", "") == id } }
            }
        }
        listener = discovery
        try { nsd.discoverServices("_cyime-clip._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: Exception) { discoveryFailed("无法开始发现，正在重试；请检查局域网连接"); return }
    }

    /**
     * Checks the saved devices when woken (settings opened, check-in after screen-on or a network
     * change, a device picked). No timer runs while things work; a failed check is retried after
     * 30 s, 2 min and 10 min, then waits for the next event.
     */
    private fun startMonitor() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            var failures = 0
            while (isActive) {
                val wait = if (failures == 0) null else CHECK_RETRIES.getOrNull(failures - 1)
                if (wait == null) reconnect.receive() else withTimeoutOrNull(wait) { reconnect.receive() }
                checkOthers()
                val peer = _paired.value
                if (peer != null) {
                    val failure = transport.testConnection()
                    if (_paired.value == peer) {
                        failures = if (failure == null) 0 else failures + 1
                        activeFailures = failures
                        _reachable.value = _reachable.value + (peer.id to (failure == null))
                        if (failure == null) discoveryFailures = 0
                        _status.value = if (failure == null) "已连接 ${peer.name}" + when {
                            !transport.sendAllowed && !transport.receiveAllowed -> " · 电脑已暂停同步"
                            !transport.sendAllowed -> " · 电脑仅允许电脑 → 手机"
                            !transport.receiveAllowed -> " · 电脑仅允许手机 → 电脑"
                            else -> " · 电脑允许双向同步"
                        } else "${peer.name} 暂未连通 · $failure"
                        if (failure == null) runCatching { markConnected(peer) }.onFailure {
                            _status.value = "已连接 ${peer.name}，新地址保存失败；稍后自动重试"
                        }
                        if (failure == null) evaluate(peer, transport.lastStatus(peer)) else discoverBriefly()
                        // On another network the active device is gone: try the other saved ones.
                        if (failure != null) switchToReachable(peer)?.let { other ->
                            failures = 0; activeFailures = 0
                            _status.value = "${peer.name} 不在当前网络，已切换到 ${other.name}"
                            reconnect.trySend(Unit)
                        }
                    }
                } else if (_nearby.value.isEmpty() && !isPairing) {
                    _status.value = "正在寻找附近电脑 · 请在 Windows CyIME 开启手机互联，并连接同一 Wi-Fi"
                }
                if (failures > CHECK_RETRIES.size) failures = 0 // Gave up: wait for the next event.
            }
        }
    }

    /** The saved devices other than the active one: are they there, and do they push to this one. */
    private suspend fun checkOthers() {
        val active = _paired.value?.id
        val others = _saved.value.filter { it.id != active }
        if (others.isEmpty()) return
        val results = coroutineScope { others.map { peer -> async { peer to transport.status(peer) } }.awaitAll() }
        for ((peer, status) in results) {
            _reachable.value = _reachable.value + (peer.id to (status != null))
            if (status != null) evaluate(peer, status)
        }
        runCatching { persist(_saved.value) } // Keep addresses found by discovery.
    }

    /** Make sure [peer] pushes to this device; an older one that cannot is polled instead. */
    private suspend fun evaluate(peer: WindowsPeer, status: JSONObject?) {
        if (status == null) return
        val ready = status.optBoolean("push") && registerIfNeeded(peer, status) && !status.optBoolean("push_failed")
        pushReady[peer.id] = ready
        updatePolling()
    }

    private fun updatePolling() {
        _needsPolling.value = _saved.value.any { pushReady[it.id] == false }
    }

    /**
     * Tells [peer] where this device listens and gives it a key for this device, so it can push here
     * (`POST /api/peer`). Sent again only when what it holds no longer matches.
     */
    private suspend fun registerIfNeeded(peer: WindowsPeer, status: JSONObject): Boolean {
        val (port, pin) = DeviceHost.receiver() ?: return false
        if (status.optString("push_pin") == pin && status.optInt("push_port") == port) return true
        val token = DeviceHost.grantFor(peer.id, peer.name) ?: return false
        val body = JSONObject().put("v", 2).put("id", DeviceHost.hostId()).put("client_id", clientId())
            .put("name", DeviceHost.deviceName()).put("port", port).put("fingerprint", pin).put("token", token)
        return transport.register(peer, body)
    }

    /**
     * A device that paired with this one (scanned its code) asks to be pushed to as well. Only a new
     * device or the same one at a new address is accepted; a saved device's key is never replaced
     * by someone else's.
     */
    fun addRegisteredPeer(peer: WindowsPeer): Boolean {
        val known = _saved.value.firstOrNull { it.id == peer.id }
        if (known != null && known.fingerprint != peer.fingerprint) return false
        if (known != null && known.clientId.isNotEmpty() && known.clientId != peer.clientId) return false
        if (known == null && _saved.value.size >= MAX_SAVED) return false
        val updated = if (known == null) _saved.value + peer
            else _saved.value.map { if (it.id == peer.id) it.copy(origin = peer.origin, token = peer.token, clientId = it.clientId.ifEmpty { peer.clientId }) else it }
        persist(updated)
        _saved.value = updated
        if (_paired.value == null || _paired.value?.id == peer.id) _paired.value = updated.first { it.id == peer.id }
        pushReady.remove(peer.id)
        updatePolling()
        return true
    }

    /**
     * A device both trust (usually the computer) introduced [peer], so this one need not scan its
     * code. Already saved: kept as it is. The next check-in registers this device with it.
     */
    fun addIntroducedPeer(peer: WindowsPeer): Boolean {
        val known = _saved.value.firstOrNull { it.id == peer.id }
        if (known != null) return known.fingerprint == peer.fingerprint
        if (_saved.value.size >= MAX_SAVED) return false
        val updated = _saved.value + peer
        persist(updated)
        _saved.value = updated
        if (_paired.value == null) _paired.value = peer
        catchUpDue = true
        if (keyboardShown) { catchUpDue = false; reconnect.trySend(Unit) }
        return true
    }

    /** The saved device that registered itself here under the grant of [clientId]. */
    fun registeredBy(clientId: String): WindowsPeer? = _saved.value.firstOrNull { it.clientId == clientId }

    /** The host list dropped [clientId]: forget the device it had registered here. */
    fun forgetRegistered(clientId: String) {
        _saved.value.filter { it.clientId == clientId }.forEach { forget(it) }
    }

    private val CHECK_RETRIES = longArrayOf(30_000, 120_000, 600_000)

    fun enterSettings() { settingsUsers++; start() }
    fun leaveSettings() {
        settingsUsers = (settingsUsers - 1).coerceAtLeast(0)
        if (settingsUsers == 0) cancelPairing()
        stopIfUnused()
    }
    fun stopIfUnused() {
        val inUse = keyboardShown && SettingsPreferences.isClipboardSyncEnabled(context) &&
            SettingsPreferences.getClipboardSyncPluginId(context) == ID
        if (settingsUsers == 0 && !inUse) stop()
    }
    fun retryDiscovery() { stop(); _status.value = "正在重新寻找附近电脑…"; start() }
    /**
     * 键盘弹出时不再每次找电脑、查连接：只有亮屏或换网络后的第一次才报到（返回 true，键盘随后拉一次最新）。
     * 旧版电脑不会推送，仍在键盘显示时照旧查询。收起（设置页也没开）就全部停掉。
     */
    fun setKeyboardShown(shown: Boolean): Boolean {
        keyboardShown = shown
        if (!shown) { stopIfUnused(); return false }
        val due = catchUpDue || _needsPolling.value
        catchUpDue = false
        startMonitor()
        if (due) reconnect.trySend(Unit)
        return due
    }
    fun connectNow() { startMonitor(); reconnect.trySend(Unit) }
    private fun discoveryFailed(message: String) {
        stopDiscovery(); _status.value = message
        val wait = longArrayOf(1000, 3000, 8000, 15000, 30000, 60000)[discoveryFailures.coerceAtMost(5)]
        discoveryFailures++
        retryDiscovery = scope.launch {
            delay(wait)
            if (settingsUsers > 0) startDiscovery()
        }
    }

    private fun stopDiscovery() {
        generation++; briefDiscovery?.cancel(); briefDiscovery = null
        listener?.let { runCatching { context.getSystemService(NsdManager::class.java).stopServiceDiscovery(it) } }
        listener = null; resolver?.cancel(); resolver = null
    }

    fun stop() {
        retryDiscovery?.cancel(); retryDiscovery = null
        stopDiscovery()
        monitor?.cancel(); monitor = null
        cancelPairing(); _nearby.value = emptyList()
    }
    private fun clientId(): String = prefs.getString("client", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("client", it).commit())
    }
    private fun request(id: String, secret: String) = JSONObject().put("v", 2).put("pairing_id", id)
        .put("secret", secret).put("client_id", clientId()).put("client_name", Build.MODEL.take(64)).put("client_kind", "android")
    fun requestNearby(peer: WindowsPeer) {
        if (settingsUsers == 0 || _saved.value.any { it.id == peer.id }) return
        cancelPairing()
        nearbyPairing.connect(peer)
    }
    fun cancelPairing() {
        val pending = isPairing
        pairing?.cancel(); pairing = null
        nearbyPairing.cancel()
        if (pending) _status.value = "已取消连接，可重新选择电脑或扫码连接"
    }
    fun pairQr(text: String) {
        cancelPairing()
        pairing = scope.launch {
            try {
                val data = WindowsPairingProtocol.offer(text)
                require(data.optLong("expires_at", Long.MAX_VALUE) >= System.currentTimeMillis() / 1000 - 10) { "二维码已过期，请刷新电脑二维码" }
                // 电脑一答应就作废这台设备原来的凭据，所以存不下的情况要在连电脑之前就拦住。
                check(_saved.value.any { it.id == data.getString("server_id") } || _saved.value.size < MAX_SAVED) {
                    "最多保存 $MAX_SAVED 台设备，请先取消一台再添加"
                }
                _status.value = "正在连接二维码中的电脑…"
                val endpoints = data.getJSONArray("endpoints")
                var failure: Exception? = null
                for (i in 0 until endpoints.length()) {
                    val peer = WindowsPeer(data.getString("server_id"), data.getString("name").take(64), endpoints.getString(i), data.getString("certificate_sha256"))
                    try {
                        val response = withContext(Dispatchers.IO) { WindowsSecureTransport.request(peer, "/api/pair", request(data.getString("pairing_id"), data.getString("secret"))) }
                        check(response.first == 201) { "二维码过期或配对失败（${response.first}），请刷新电脑二维码" }
                        // Scanning the user's computer supplies the pin and secret out of band.
                        commitPairing(WindowsPairingProtocol.received(peer, requireNotNull(response.second)))
                        return@launch
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
                }
                throw requireNotNull(failure)
            } catch (e: CancellationException) { throw e }
            // 扫码同样要手机连进电脑，被防火墙挡住时说一样的原因（#500）。
            catch (e: Exception) { _status.value = WindowsNearbyPairing.failureMessage(e, "二维码无效") }
        }
    }
    fun confirmConnection(requestId: Long) = nearbyPairing.confirm(requestId)
    private fun commitPairing(peer: WindowsPeer) {
        // A new device is added beside the saved ones; re-pairing the same id refreshes it.
        val others = _saved.value.filterNot { it.id == peer.id }
        check(others.size < MAX_SAVED) { "最多保存 $MAX_SAVED 台设备，请先取消一台再添加" }
        persist(listOf(peer) + others)
        _saved.value = listOf(peer) + others
        _paired.value = peer
        activeFailures = 0
        SettingsPreferences.setClipboardSyncPluginId(context, ID)
        SettingsPreferences.setClipboardSyncEnabled(context, true)
        _status.value = "已配对 ${peer.name}，正在检查连接…"
        reconnect.trySend(Unit)
    }
    private fun persist(list: List<WindowsPeer>) {
        if (persisted == list) return
        val array = JSONArray()
        list.forEach { peer ->
            array.put(JSONObject().put("id", peer.id).put("name", peer.name).put("origin", peer.origin)
                .put("fingerprint", peer.fingerprint).put("token", peer.token).put("client_id", peer.clientId))
        }
        check(prefs.edit().putString("peers", SecureValueCipher.encrypt(array.toString())).remove("peer").commit()) { "配对保存失败" }
        persisted = list
    }
    /** The last connected device moves to the front, so it is tried first next time. */
    private fun markConnected(peer: WindowsPeer) {
        val ordered = listOf(peer) + _saved.value.filterNot { it.id == peer.id }
        persist(ordered)
        _saved.value = ordered
    }
    /** Test the other saved devices once; the first that answers becomes the active one. */
    private suspend fun switchToReachable(failed: WindowsPeer): WindowsPeer? {
        for (other in _saved.value.filterNot { it.id == failed.id }) {
            val ok = runCatching {
                val (code, body) = withContext(Dispatchers.IO) { WindowsSecureTransport.request(other, "/api/status", null, true) }
                code == 200 && body?.optInt("v") == 2
            }.getOrDefault(false)
            _reachable.value = _reachable.value + (other.id to ok)
            if (ok && _paired.value == failed) {
                _paired.value = _saved.value.firstOrNull { it.id == other.id } ?: other
                return other
            }
        }
        return null
    }
    /** Use this saved device now (the user picked it in the list). */
    fun use(peer: WindowsPeer) {
        val chosen = _saved.value.firstOrNull { it.id == peer.id } ?: return
        _paired.value = chosen; activeFailures = 0
        _status.value = "正在连接 ${chosen.name}…"
        connectNow()
    }
    /** Forget one saved device; with none left, sync is turned off as before. */
    fun forget(peer: WindowsPeer) {
        revokeGrants(peer)
        val remaining = _saved.value.filterNot { it.id == peer.id }
        if (remaining.isEmpty()) { forget(); return }
        persist(remaining)
        _saved.value = remaining
        _reachable.value = _reachable.value - peer.id
        pushReady.remove(peer.id); updatePolling()
        if (_paired.value?.id == peer.id) { _paired.value = remaining.first(); activeFailures = 0; reconnect.trySend(Unit) }
        _status.value = "已取消 ${peer.name}，请在对方设备列表移除此手机以撤销授权"
    }
    /** It can no longer push here, and a device that registered here can no longer reach this one. */
    private fun revokeGrants(peer: WindowsPeer) {
        DeviceHost.revokeClient(peer.id)
        if (peer.clientId.isNotEmpty()) DeviceHost.revokeClient(peer.clientId)
    }
    fun forget() {
        _saved.value.forEach(::revokeGrants)
        SettingsPreferences.setClipboardSyncEnabled(context, false)
        stop(); _paired.value = null; _saved.value = emptyList(); _reachable.value = emptyMap(); persisted = null
        pushReady.clear(); updatePolling()
        prefs.edit().remove("peer").remove("peers").apply()
        _status.value = "已断开，请在对方设备列表移除此手机以撤销授权"
        if (settingsUsers > 0) start()
    }
}
