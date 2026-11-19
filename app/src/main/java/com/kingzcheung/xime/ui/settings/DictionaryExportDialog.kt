package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.DictionaryExport
import com.kingzcheung.xime.settings.InstalledCellDictionary

/**
 * 「导出」：先选导出哪些词（当前方案学到的词，或一个导入的词库），再选写成哪家输入法的格式；点「导出」后选存到哪。
 * [onExport] 的 source 为 null 表示学到的词。
 */
@Composable
internal fun DictionaryExportDialog(
    installed: List<InstalledCellDictionary>,
    onDismiss: () -> Unit,
    onExport: (source: String?, name: String, format: DictionaryExport.Format) -> Unit,
) {
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf(DictionaryExport.Format.CYIME) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导出词库") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("dictionary-export-dialog"),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("导出哪些词", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ExportChoice("我学到的词", "当前输入方案打字时记下的词", source == null, "dictionary-export-source:user") {
                source = null
            }
            installed.forEach { pack ->
                ExportChoice(pack.name, "导入的词库，${pack.count} 个词", source == pack.id,
                    "dictionary-export-source:${pack.id}") { source = pack.id }
            }
            Text("导出成", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp))
            DictionaryExport.Format.entries.forEach { option ->
                ExportChoice("${option.label} (.${option.extension})", option.note, format == option,
                    "dictionary-export-format:${option.name}") { format = option }
            }
            Text("只导出两个字以上、带拼音的词。搜狗 .bin、.scel 和微软拼音 .dat 是各家自己的二进制格式，写不出来。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = {
        TextButton(onClick = {
            val name = if (source == null) "我学到的词" else installed.firstOrNull { it.id == source }?.name ?: "词库"
            onExport(source, name, format)
        }) { Text("导出") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ExportChoice(title: String, note: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
