package com.kingzcheung.xime.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.clipboard.sync.WindowsDevices
import com.kingzcheung.xime.clipboard.sync.WindowsPeer
import com.kingzcheung.xime.clipboard.sync.WindowsPairingConfirmation
import com.kingzcheung.xime.settings.SettingsPreferences

@Composable
fun WindowsDevicesPanel() {
    val context = LocalContext.current
    val devices by WindowsDevices.nearby.collectAsState()
    val status by WindowsDevices.status.collectAsState()
    val paired by WindowsDevices.paired.collectAsState()
    val saved by WindowsDevices.saved.collectAsState()
    val reachable by WindowsDevices.reachable.collectAsState()
    val confirmation by WindowsDevices.confirmation.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun isEnabled() = SettingsPreferences.isClipboardSyncEnabled(context) &&
        SettingsPreferences.getClipboardSyncPluginId(context) == WindowsDevices.ID
    var enabled by remember { mutableStateOf(isEnabled()) }
    DisposableEffect(context) {
        WindowsDevices.initialize(context)
        WindowsDevices.enterSettings()
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enabled = isEnabled() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); WindowsDevices.leaveSettings() }
    }
    if (scanning) WindowsPairingScanner(onDismiss = { scanning = false }) { code ->
        scanning = false; error = null; WindowsDevices.pairQr(code)
    }
    WindowsDevicesPanelContent(status, paired, devices, enabled, confirmation, error,
        onScan = { WindowsDevices.cancelPairing(); error = null; scanning = true },
        onRetry = { error = null; WindowsDevices.retryDiscovery() },
        onConnect = { error = null; WindowsDevices.requestNearby(it) }, onForget = { WindowsDevices.forget(it) },
        onEnabled = {
            if (it) SettingsPreferences.setClipboardSyncPluginId(context, WindowsDevices.ID)
            SettingsPreferences.setClipboardSyncEnabled(context, it)
            enabled = it
        }, onConfirm = { requestId ->
            error = null
            runCatching { WindowsDevices.confirmConnection(requestId) }
                .onFailure { error = it.message ?: "连接保存失败，请重试" }
        }, onCancel = { error = null; WindowsDevices.cancelPairing() },
        saved = saved, reachable = reachable, onUse = { error = null; WindowsDevices.use(it) })
    DeviceHostPanel()
}

/**
 * Keep status, saved devices and connection actions distinct; scanning is never gated by pairing
 * state. Several devices can be saved: scanning another adds it instead of replacing the first.
 */
@Composable
internal fun WindowsDevicesPanelContent(
    status: String, paired: WindowsPeer?, devices: List<WindowsPeer>, enabled: Boolean, confirmation: WindowsPairingConfirmation?, error: String?,
    onScan: () -> Unit, onRetry: () -> Unit, onConnect: (WindowsPeer) -> Unit,
    onForget: (WindowsPeer) -> Unit, onEnabled: (Boolean) -> Unit, onConfirm: (Long) -> Unit, onCancel: () -> Unit = {},
    saved: List<WindowsPeer> = listOfNotNull(paired), reachable: Map<String, Boolean> = emptyMap(),
    onUse: (WindowsPeer) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SettingsSection(title = "连接状态", content = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium)
            Text(if (paired == null) "同一 Wi-Fi 自动查找电脑；首次请点选电脑并核对连接码，或扫描自己电脑的二维码。"
                else if (!enabled) "已记住 ${saved.size} 台设备；本机同步已暂停，可在下方开启。"
                else if (saved.size > 1) "已记住 ${saved.size} 台设备，自动连接当前网络里能连上的那台。"
                else "已记住 ${paired.name}，设备可用时自动重连。", style = MaterialTheme.typography.bodyMedium)
            confirmation?.let { pending ->
                Text("${pending.computerName} · 连接码", style = MaterialTheme.typography.bodyMedium)
                Text(pending.code, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("windows-pairing-code"))
                Text("请对照自己电脑上显示的连接码；一致才确认。两分钟内未确认会自动取消。",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pending.ready) Button(onClick = { onConfirm(pending.requestId) },
                        modifier = Modifier.weight(1f).testTag("windows-confirm-pairing")) { Text("我已核对，连接并同步") }
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("windows-cancel-pairing")) { Text("取消") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    })
    if (paired != null) SettingsSection(title = "我的设备", content = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            saved.forEach { peer ->
                val active = peer.id == paired.id
                Row(Modifier.fillMaxWidth().testTag("windows-saved:${peer.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            active && reachable[peer.id] == true -> "正在同步"
                            active -> "当前选用 · 暂未连通"
                            reachable[peer.id] == true -> "可连接"
                            reachable[peer.id] == false -> "不在当前网络"
                            else -> "已记住"
                        }, style = MaterialTheme.typography.bodySmall,
                            color = if (active && reachable[peer.id] == true) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!active) TextButton(onClick = { onUse(peer) }) { Text("改用") }
                    TextButton(onClick = { onForget(peer) }) { Text("取消配对") }
                }
            }
            HorizontalDivider()
            Row(Modifier.settingsSearchAnchor("同步剪贴板", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("同步剪贴板")
                    Text("自动同步复制的文字；图片暂不支持跨设备同步。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            TextButton(onClick = onRetry) { Text("重新连接") }
        }
    })
    SettingsSection(title = if (paired == null) "连接电脑" else "添加设备", content = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().testTag("windows-scan-connect")) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (paired == null) "扫码连接电脑" else "扫码添加另一台设备")
            }
            SettingsJumpText(if (paired == null) "未能自动连接？在电脑“手机与电脑互联”中展开“连接新手机 / 无法自动连接？”，点击“使用二维码连接”，再用这里扫码。"
                else "已配对的设备都会保留。扫另一台电脑的二维码，或另一部手机/平板在「让其他手机或平板连接本机」里显示的二维码。",
                jumps = listOf("让其他手机或平板连接本机"),
                style = MaterialTheme.typography.bodyMedium)
            val unpaired = devices.filterNot { peer -> saved.any { it.id == peer.id } }
            unpaired.forEach { peer ->
                OutlinedButton(onClick = { onConnect(peer) }) { Text("连接 ${peer.name}") }
            }
            if (paired == null) TextButton(onClick = onRetry) { Text("重新查找附近电脑") }
        }
    })
    }
}
