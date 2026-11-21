package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.caption.CaptionLauncherIcon

/**
 * 「字幕」插件页里的字幕设置（11-05 从 语音转文本 › 字幕 整组搬来，一项没删）：
 * 中文识别说明、桌面图标、自动保存、只保留 3 天、叫名字振动、后台浮窗。只在字幕插件的那一页出现。
 */
@Composable
internal fun CaptionSettingsSection() {
    val context = LocalContext.current
    SettingsSection(title = "字幕") {
        // CaptionController.chineseMode：字幕自己挑模型，和语音输入的选择分开，这里写明，免得两处看起来矛盾。
        Text(
            text = "字幕按已下载的模型自动选择识别模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
        )
        var captionIcon by remember { mutableStateOf(CaptionLauncherIcon.isEnabled(context)) }
        Column(Modifier.settingsSearchAnchor("桌面显示“字幕”图标").fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "桌面显示“字幕”图标",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = captionIcon,
                    onCheckedChange = {
                        captionIcon = it
                        CaptionLauncherIcon.setEnabled(context, it)
                    },
                )
            }
            Text(
                text = "在桌面放一个图标，点一下打开字幕",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        var captionAutoSave by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionAutoSave.isOn(context)) }
        Column(Modifier.settingsSearchAnchor("自动保存字幕").fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "自动保存字幕",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = captionAutoSave,
                    onCheckedChange = {
                        captionAutoSave = it
                        com.kingzcheung.xime.ui.caption.CaptionAutoSave.setOn(context, it)
                    },
                )
            }
            Text(
                text = "每次的字幕自动存到“下载/CyIME字幕”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        var keepThreeDays by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionAutoSave.keepsThreeDays(context)) }
        Column(Modifier.settingsSearchAnchor("字幕只保留 3 天").fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "字幕只保留 3 天",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = keepThreeDays,
                    onCheckedChange = {
                        keepThreeDays = it
                        com.kingzcheung.xime.ui.caption.CaptionAutoSave.setKeepsThreeDays(context, it)
                    },
                )
            }
            Text(
                text = "超过 3 天的字幕记录自动删除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        var nameAlert by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionNameAlert.isOn(context)) }
        var alertNames by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionNameAlert.names(context)) }
        Column(Modifier.settingsSearchAnchor("有人叫我名字时振动").fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "有人叫我名字时振动",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = nameAlert,
                    onCheckedChange = {
                        nameAlert = it
                        com.kingzcheung.xime.ui.caption.CaptionNameAlert.setOn(context, it)
                    },
                )
            }
            Text(
                text = "别人说到下面的名字时振动提醒，多个叫法用空格隔开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (nameAlert) androidx.compose.material3.OutlinedTextField(
                value = alertNames,
                onValueChange = {
                    alertNames = it
                    com.kingzcheung.xime.ui.caption.CaptionNameAlert.setNames(context, it)
                },
                label = { Text("我的名字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        var captionFloat by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionFloat.isOn(context)) }
        val callCaptionOn = remember(captionFloat) { com.kingzcheung.xime.ui.caption.CallCaptionService.isEnabled(context) }
        Column(Modifier.settingsSearchAnchor("后台浮窗").fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "后台浮窗",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = captionFloat,
                    onCheckedChange = {
                        captionFloat = it
                        com.kingzcheung.xime.ui.caption.CaptionFloat.setOn(context, it)
                        // The bar lives in the call caption service; without it there is nothing to float in.
                        if (it && !com.kingzcheung.xime.ui.caption.CallCaptionService.isEnabled(context)) runCatching {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
            }
            Text(
                text = "离开字幕页后，字幕继续在屏幕底部显示。" +
                    if (callCaptionOn) "" else "需先在系统无障碍里打开“通话字幕”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
