package com.kingzcheung.xime.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.glide.ExternalGlideEncodings
import com.kingzcheung.xime.settings.DoublePinyinSchemes
import com.kingzcheung.xime.settings.InputProfiles
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.LayoutKind
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「滑行输入 › 布局」里，已启用的导入方案（自定义编码、字母键盘）各一行：选这个方案滑行时按全拼还是哪套双拼出码（#303）。
 * 默认「不滑行」，和以前一样；没有导入方案时不显示。微软、搜狗双拼的 ing 在分号键上，导入方案的键盘没有分号键，不列。
 */
@Composable
internal fun ExternalGlideSchemeItems(settings: KeyboardInputPreferences, onChange: (KeyboardInputPreferences) -> Unit) {
    val context = LocalContext.current
    val choices by produceState(ExternalGlideChoices(emptyList(), emptyList())) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val schemes = SchemaManager.getEnabledSchemas(context).filter { id ->
                    InputProfiles.describe(id).let { it.scheme == InputScheme.EXTERNAL && it.layout.kind == LayoutKind.ALPHABETIC }
                }.map { id -> id to (SchemaManager.getSchemaDisplayName(context, id) ?: id) }
                val encodings = listOf(ExternalGlideEncodings.PINYIN to "全拼") +
                    DoublePinyinSchemes.all(context).filterNot { it.usesSemicolon }.map { it.id to it.name }
                ExternalGlideChoices(schemes, encodings)
            }.getOrElse { ExternalGlideChoices(emptyList(), emptyList()) }
        }
    }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    for ((id, name) in choices.schemes) {
        HorizontalDivider()
        val current = settings.externalGlideEncodings[id]
        val label = current?.let { encoding -> choices.encodings.firstOrNull { it.first == encoding }?.second } ?: "不滑行"
        SettingsItem(Icons.TwoTone.Gesture, "$name 滑行",
            if (settings.chineseGlideTyping) "导入方案，滑行时按所选编码出码" else "导入方案，需先打开「中文滑行」",
            onClick = { editing = id to name }, showArrow = true, status = label, searchKey = "导入方案滑行 $id")
    }
    editing?.let { (id, name) ->
        var selected by remember(id) { mutableStateOf(settings.externalGlideEncodings[id]) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("$name 滑行") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("这个方案的编码按哪种拼音规则来，滑行就按哪种出码。选错了划出来的字会不对。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExternalGlideChoice("不滑行", selected == null) { selected = null }
                choices.encodings.forEach { (encoding, title) ->
                    ExternalGlideChoice(title, selected == encoding) { selected = encoding }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                onChange(settings.copy(externalGlideEncodings = ExternalGlideEncodings.with(settings.externalGlideEncodings, id, selected)))
                editing = null
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
}

private class ExternalGlideChoices(val schemes: List<Pair<String, String>>, val encodings: List<Pair<String, String>>)

@Composable
private fun ExternalGlideChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
