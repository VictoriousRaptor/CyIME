package com.kingzcheung.xime.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 「记录密码等敏感内容」(#95)：默认关。密码管理器复制的密码系统标成敏感，键盘一律不记；
 * 打开后记进剪贴板历史，但显示成圆点、1 小时后删除、不同步不导出。打开要确认。
 * 关掉只是不再记新的，已经记下的不马上删（用户 11-09：普通网页文字被误当成密码，一关就全删了），照旧 1 小时后删。
 */
@Composable
internal fun ClipboardSensitiveSetting() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsPreferences.isClipboardSensitiveCaptureEnabled(context)) }
    var confirming by remember { mutableStateOf(false) }
    ClipboardSensitiveSettingCard(enabled) { checked ->
        if (checked) confirming = true
        else {
            enabled = false
            SettingsPreferences.setClipboardSensitiveCaptureEnabled(context, false)
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("记录密码等敏感内容？") },
        text = { Text("密码会存在这台手机的剪贴板历史里，别人拿到手机解锁后长按就能看到。") },
        confirmButton = { Button(onClick = {
            confirming = false
            enabled = true
            SettingsPreferences.setClipboardSensitiveCaptureEnabled(context, true)
        }) { Text("打开") } },
        dismissButton = { OutlinedButton(onClick = { confirming = false }) { Text("取消") } },
    )
}

@Composable
internal fun ClipboardSensitiveSettingCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsSection(title = "敏感内容", content = {
        SettingsToggleItem(Icons.TwoTone.Lock, "记录密码等敏感内容",
            "复制的密码也记下来，显示成圆点，1 小时后自动删除",
            enabled, onToggle)
    })
}
