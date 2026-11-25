package com.kingzcheung.xime.clipboard.sync

import android.content.Context
import android.content.ClipboardManager as AndroidClipboardManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.kingzcheung.xime.clipboard.VerificationCodeSmsReceiver
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.sync.ClipboardSyncSession
import com.kingzcheung.xime.plugin.core.sync.SyncClipboardPort
import com.kingzcheung.xime.plugin.core.sync.SyncClipboardSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Main owns clipboard/state; only plugin HTTP runs on IO. No input-command path is involved. */
class ClipboardSyncBridge(
    context: Context,
    private val clipboardManager: ClipboardManager,
    private val plugin: ClipboardSyncPlugin,
    val pluginId: String = "",
    foregroundPollIntervalMs: Long = 0,
    private val maxTextBytes: Int = 0,
    /** 其他同步服务（WebDAV 等）的自动拉取间隔，用户在设置里选（#45），默认 30 秒。 */
    pullIntervalMs: Long = DEFAULT_PULL_INTERVAL_MS,
    /** 键盘开着时按 [pullIntervalMs] 一直拉（#45），默认关。 */
    keepPulling: Boolean = false,
) {
    private val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
    private val appContext = context.applicationContext
    // Notify once when a healthy connection starts failing; the session backs off silently afterwards.
    private var failing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pollInterval = foregroundPollMs(foregroundPollIntervalMs, pluginId == WindowsDevices.ID, keepPulling, pullIntervalMs)
    private val openThrottle = clampPullInterval(pullIntervalMs)
    private var visible = false
    private var requested = false
    private var worker: Job? = null
    private var pollingWatch: Job? = null
    private var session: ClipboardSyncSession? = null
    private var lastPullAt = Long.MIN_VALUE
    /** 上次和对方成功来往（推出去或拉回来）的时间。 */
    private var lastContactAt = Long.MIN_VALUE
    private var visibilityGeneration = 0L
    private val mesh = pluginId == WindowsDevices.ID
    /** 对端都会推送时不再定时拉取；只有旧版对端还要按老办法每 2 秒拉一次（键盘显示时）。 */
    private val polling get() = pollInterval > 0 && (!mesh || WindowsDevices.needsPolling.value)

    private val port = object : SyncClipboardPort {
        /** 上次读到的那一条。每次拉取要前后比对几次剪贴板：复制时间（只看描述，不算读取）没变就用它，不再读内容（用户 11-01 嫌读剪贴板太多）。 */
        private var lastRead: SyncClipboardSnapshot? = null

        override fun read(): SyncClipboardSnapshot {
            val stamp = try { systemClipboard.primaryClipDescription?.timestamp ?: 0L } catch (_: SecurityException) {
                return SyncClipboardSnapshot(null, 0, sensitive = true)
            }
            lastRead?.takeIf { stamp != 0L && it.revision == stamp }?.let { return it }
            val clip = try { systemClipboard.primaryClip } catch (_: SecurityException) {
                // Switching away from the default IME can revoke clipboard access.
                return SyncClipboardSnapshot(null, 0, sensitive = true)
            }
            val description = clip?.description
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            // A copy made while the keyboard was private (password field, incognito) stays on this phone,
            // like one the system marks sensitive: history may keep it hidden (#95), sync never sends it.
            // 「允许其他设备连接本机」 already follows the same rule.
            return SyncClipboardSnapshot(text, description?.timestamp ?: 0,
                description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true ||
                    description?.label == VerificationCodeSmsReceiver.CLIP_LABEL ||
                    com.kingzcheung.xime.settings.IncognitoState.active ||
                    com.kingzcheung.xime.settings.SettingsPreferences.isIncognito(appContext) ||
                    // 通讯录插件开着时，联系人的名字、号码不发到别的设备。
                    com.kingzcheung.xime.contacts.ContactSuggestions.isContactText(text)).also { lastRead = it }
        }
        override fun writeIfUnchanged(expected: SyncClipboardSnapshot, text: String): Boolean {
            if (read() != expected) return false
            clipboardManager.copyReceivedToSystemClipboard(text)
            return read().text == text
        }
    }
    private val listener = AndroidClipboardManager.OnPrimaryClipChangedListener {
        // Use the system event, not delayed database history inserts or edits.
        val snapshot = port.read()
        noteLocal(snapshot)
        session?.localChanged(snapshot)
        wake.trySend(Unit)
    }

    /** Gives this copy its order number (a received one keeps the sender's). */
    private fun noteLocal(snapshot: SyncClipboardSnapshot) {
        val text = snapshot.text
        if (!mesh || snapshot.sensitive || text.isNullOrBlank()) return
        ClipboardClock.noteLocal(ClipboardProfile.fromText(text).hash, snapshot.revision)
    }

    fun start() {
        if (worker != null) return
        session = ClipboardSyncSession(plugin, port, SystemClock::elapsedRealtime, reportFailure = failure@{ error ->
            if (error is WindowsSecureTransport.SyncPaused) return@failure
            // Do not log clipboard text, URL, credentials or exception payloads.
            val transfer = error as? WindowsSecureTransport.TransferFailure
            Log.w("ClipboardSync", "[$pluginId] sync attempt failed (${error.javaClass.simpleName}${transfer?.let { "/HTTP-${it.status}" }.orEmpty()})")
            if (pluginId == WindowsDevices.ID && !failing) {
                Toast.makeText(appContext, transfer?.message ?: "手机与电脑剪贴板同步失败，之后会逐渐拉长间隔自动重试。", Toast.LENGTH_LONG).show()
            }
            failing = true
        }, maxTextBytes = maxTextBytes.takeIf { it > 0 } ?: Int.MAX_VALUE,
            allowPush = { (plugin as? WindowsSecureTransport)?.let { it.sendAllowed || it.hasOthers } != false },
            reportSuccess = { failing = false; lastContactAt = SystemClock.elapsedRealtime() },
            // 推送失败只在 30 秒、2 分钟、10 分钟后各重试一次，之后等下次亮屏打开键盘或复制新内容。
            retryDelaysMs = if (mesh) longArrayOf(30_000, 120_000, 600_000) else null)
        systemClipboard.addPrimaryClipChangedListener(listener)
        if (mesh) {
            noteLocal(port.read())
            // 收起键盘时也要能收到对方推来的内容。
            DeviceHost.setReceiving(true)
            WindowsDevices.onCatchUp = { pullOnce() }
            pollingWatch = scope.launch { WindowsDevices.needsPolling.collect { wake.trySend(Unit) } }
        }
        worker = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val periodic = visible && polling && (lastPullAt == Long.MIN_VALUE || now - lastPullAt >= pollInterval)
                val pull = visible && (requested || periodic)
                val requestVisibility = visibilityGeneration
                requested = false
                if (pull) lastPullAt = now
                session?.pump { pull && visible && visibilityGeneration == requestVisibility &&
                    (plugin as? WindowsSecureTransport)?.receiveAllowed != false }
                val backoff = session?.retryInMs ?: 0L
                val wait = when {
                    backoff > MAX_TIMER_MS -> null
                    visible && polling -> maxOf(pollInterval, backoff)
                    session?.hasPendingPush == true -> maxOf(5_000L, backoff)
                    else -> null
                }
                if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    fun setKeyboardVisible(shown: Boolean) {
        if (visible == shown) return
        visible = shown
        visibilityGeneration++
        if (shown) {
            val now = SystemClock.elapsedRealtime()
            // 键盘收起时系统常把输入法冻住，复制的监听可能没送到（#360「平时不咋同步」）。
            // 打开键盘时补看一眼：只看复制时间，换了才读内容；已经看过的同一条不会重复推。
            port.read().let { noteLocal(it); session?.localChanged(it) }
            if (mesh) {
                // 亮屏或换网络后第一次打开键盘报到一次：拉一次最新，没推出去的再推一次。平时靠对方推送。
                // 另外上次联系上已过一分钟也报到一次：电脑推来时手机正被冻着就会失败，重试几次后不再推。
                val due = WindowsDevices.setKeyboardShown(true)
                if (meshCatchUpDue(due, failing, lastContactAt, now)) { session?.retryNow(); requested = true }
            }
            // 其他同步服务（WebDAV 等）：打开键盘时距上次拉取满所选间隔（默认 30 秒）才再拉。
            else if (pollInterval > 0 || lastPullAt == Long.MIN_VALUE || now - lastPullAt >= openThrottle) requested = true
        } else if (mesh) WindowsDevices.setKeyboardShown(false)
        wake.trySend(Unit)
    }

    /** Explicit refresh bypasses the automatic visibility throttle. */
    fun pullOnce() { session?.retryNow(); requested = true; wake.trySend(Unit) }

    fun usesPlugin(instance: ClipboardSyncPlugin): Boolean = plugin === instance

    fun stop() {
        // 同步关掉或重建：键盘显示的标记一并清掉，免得之后在后台一直找电脑。
        if (visible && mesh) { visible = false; WindowsDevices.setKeyboardShown(false) }
        if (mesh && worker != null) { WindowsDevices.onCatchUp = null; DeviceHost.setReceiving(false) }
        pollingWatch?.cancel()
        pollingWatch = null
        session?.close()
        session = null
        worker?.cancel()
        worker = null
        systemClipboard.removePrimaryClipChangedListener(listener)
    }

    fun release() { stop(); scope.cancel() }

    private companion object {
        /** A wait this long means the session waits for an event, not a timer. */
        const val MAX_TIMER_MS = 24 * 60 * 60 * 1_000L
    }
}

/** 其他同步服务的自动拉取间隔可选值（秒，#45）；默认 30 秒和以前一样。 */
internal val PULL_INTERVAL_CHOICES_S = intArrayOf(5, 10, 30, 60)
internal const val DEFAULT_PULL_INTERVAL_MS = 30_000L

internal fun clampPullInterval(ms: Long): Long = ms.coerceIn(5_000, 60_000)

/**
 * 键盘开着时定时拉取的间隔，0 表示不定时拉。插件自己声明了间隔（电脑同步固定 2 秒）照旧用它；
 * 其他服务只有用户打开「键盘开着时一直拉取」才按所选间隔拉。电脑同步不受这两个设置影响。
 */
internal fun foregroundPollMs(pluginPollMs: Long, mesh: Boolean, keepPulling: Boolean, pullIntervalMs: Long): Long = when {
    pluginPollMs > 0 -> pluginPollMs.coerceIn(2_000, 60_000)
    !mesh && keepPulling -> clampPullInterval(pullIntervalMs)
    else -> 0
}

/** 距上次联系上多久，打开键盘时再找对方补一次。 */
internal const val MESH_QUIET_CATCH_UP_MS = 60_000L

/**
 * 打开键盘时要不要找对方补一次（拉最新、补推没推出去的）。亮屏、换网络后照旧补（[eventDue]）；
 * 另外上次是通的、但已经 [MESH_QUIET_CATCH_UP_MS] 没联系，也补一次。上次就没连上（多半不在同一网络）
 * 时不补，等亮屏或换网络，免得每次打开键盘都白连一次。
 */
internal fun meshCatchUpDue(eventDue: Boolean, failing: Boolean, lastContactAt: Long, now: Long): Boolean =
    eventDue || (!failing && (lastContactAt == Long.MIN_VALUE || now - lastContactAt >= MESH_QUIET_CATCH_UP_MS))
