package com.kingzcheung.xime.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Devices
import androidx.compose.material.icons.twotone.Sms
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.settings.SettingsPreferences

@Composable
internal fun VerificationCodeSettings() {
    val context = LocalContext.current
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    var enabled by remember { mutableStateOf(SettingsPreferences.isSmsCodeEnabled(context) && hasPermission()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    fun setEnabled(value: Boolean) {
        SettingsPreferences.setSmsCodeEnabled(context, value)
        enabled = value
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        setEnabled(granted)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = hasPermission()
        if (denied && granted) {
            // The user requested this feature, then granted access in system settings.
            denied = false
            setEnabled(true)
        } else {
            enabled = granted && SettingsPreferences.isSmsCodeEnabled(context)
        }
    }
    // 「同步剪贴板」在上面的设备面板里开关，这里跟着变。
    fun syncOn() = SettingsPreferences.isClipboardSyncEnabled(context) &&
        SettingsPreferences.getClipboardSyncPluginId(context) == com.kingzcheung.xime.clipboard.sync.WindowsDevices.ID
    var syncEnabled by remember { mutableStateOf(syncOn()) }
    var sendToDevices by remember { mutableStateOf(SettingsPreferences.isSmsCodeSyncEnabled(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncEnabled = syncOn() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    VerificationCodeSettingsCard(enabled, denied, onToggle = { checked ->
        denied = false
        if (checked && !hasPermission()) launcher.launch(Manifest.permission.RECEIVE_SMS)
        else setEnabled(checked)
    }, sendToDevices = sendToDevices, sendLock = verificationCodeSendLock(enabled, syncEnabled), onSendToDevices = {
        SettingsPreferences.setSmsCodeSyncEnabled(context, it)
        sendToDevices = it
    })
}

/** 「验证码发到其他设备」要先有验证码、先连着设备同步；缺哪个就锁住并说明。 */
internal fun verificationCodeSendLock(smsCodeOn: Boolean, syncOn: Boolean): String? = when {
    !smsCodeOn -> "需先打开「新短信验证码自动复制」"
    !syncOn -> "需先打开上面的「同步剪贴板」"
    else -> null
}

@Composable
internal fun VerificationCodeSettingsCard(enabled: Boolean, denied: Boolean,
    sendToDevices: Boolean = false, sendLock: String? = null, onSendToDevices: (Boolean) -> Unit = {},
    onToggle: (Boolean) -> Unit) {
    SettingsSection(title = "验证码", content = {
        Column {
            SettingsToggleItem(Icons.TwoTone.Sms, "新短信验证码自动复制",
                "需授权接收短信。新短信到了只把验证码数字复制到剪贴板，不读取历史短信，不加入历史，10 分钟后自动删除。",
                enabled, onToggle)
            if (denied) Text("未获得短信权限。请在系统设置 → 应用 → CyIME → 权限中允许接收短信，然后返回此页。如果没有短信权限选项，可以手动复制验证码数字。",
                modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            SettingsToggleItem(Icons.TwoTone.Devices, "验证码发到其他设备",
                "只发给已配对的电脑、手机、平板，不进历史，3 分钟后自动清除",
                sendToDevices && sendLock == null, onSendToDevices, lockMessage = sendLock)
        }
    })
}
