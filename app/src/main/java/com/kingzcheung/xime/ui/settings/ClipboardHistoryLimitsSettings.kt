package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.clipboard.CLIPBOARD_IMAGE_CACHE_MB_OPTIONS
import com.kingzcheung.xime.clipboard.ClipboardHistoryLimits
import com.kingzcheung.xime.clipboard.ClipboardImages
import com.kingzcheung.xime.clipboard.clipboardImageCacheMbLabel
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.ClipboardRetention
import kotlinx.coroutines.launch

/**
 * 剪贴板历史的「数量上限」和「保留时间」(用户 11-03)：选项和叫法同 Windows 版，另加「5000 条」「不限」给拿剪贴板存文档的人。
 * 固定的和分组里的不计入、不删除，图片另有「图片占用上限」。改小后会删记录时先说删几条，确认后才删。
 */
@Composable
internal fun ClipboardHistoryLimitsSetting() {
    val context = LocalContext.current
    val manager = remember(context) { ClipboardManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    var limits by remember { mutableStateOf<ClipboardHistoryLimits?>(null) }
    var pending by remember { mutableStateOf<Pair<ClipboardHistoryLimits, Int>?>(null) }
    LaunchedEffect(manager) { limits = manager.historyLimits() }
    fun save(next: ClipboardHistoryLimits) {
        limits = next
        manager.setHistoryLimits(next)
    }
    fun choose(next: ClipboardHistoryLimits) {
        scope.launch {
            val removed = manager.removedBy(next)
            if (removed > 0) pending = next to removed else save(next)
        }
    }
    val shown = limits ?: ClipboardHistoryLimits(ClipboardRetention.DEFAULT_COUNT, ClipboardRetention.DEFAULT_DAYS)
    SettingsSection(title = "历史记录", content = {
        HistoryLimitChoices("数量上限", "超出时删除最早的，固定和分组的不算", ClipboardRetention.COUNT_OPTIONS,
            shown.maxCount, ClipboardRetention::countLabel, "clipboard-max-count", enabled = limits != null) {
            choose(shown.copy(maxCount = it))
        }
        HistoryLimitChoices("保留时间", "到期自动删除，固定和分组的保留", ClipboardRetention.DAY_OPTIONS,
            shown.keepDays, ClipboardRetention::daysLabel, "clipboard-keep-days", enabled = limits != null) {
            choose(shown.copy(keepDays = it))
        }
        // 用户 11-17：剪贴板图片限制大小，默认 200 MB，可以调。
        val images = remember(context) { ClipboardImages.getInstance(context) }
        var imageMb by remember { mutableStateOf(images.cacheLimitMb()) }
        HistoryLimitChoices("图片占用上限", "超出时删除最早的图片，固定的保留", CLIPBOARD_IMAGE_CACHE_MB_OPTIONS,
            imageMb, ::clipboardImageCacheMbLabel, "clipboard-image-cache", enabled = true) {
            imageMb = it
            images.setCacheLimitMb(it)
        }
    })
    pending?.let { (next, removed) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("删掉 $removed 条记录？") },
            text = { Text("按新设置，较早的 $removed 条历史记录会被删掉，固定和分组的保留。") },
            confirmButton = { Button(onClick = { pending = null; save(next) }) { Text("删除") } },
            dismissButton = { OutlinedButton(onClick = { pending = null }) { Text("取消") } },
        )
    }
}

/** 单选样式同本页「卡片显示行数」。 */
@Composable
private fun HistoryLimitChoices(
    title: String,
    subtitle: String,
    options: List<Int>,
    selected: Int,
    label: (Int) -> String,
    tag: String,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.settingsSearchAnchor(title).fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.forEach { option ->
            Row(Modifier.fillMaxWidth().testTag("$tag:$option")
                .selectable(selected == option, enabled = enabled, role = Role.RadioButton) {
                    if (option != selected) onSelect(option)
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == option, onClick = null, enabled = enabled)
                Spacer(Modifier.width(12.dp))
                Text(label(option), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
