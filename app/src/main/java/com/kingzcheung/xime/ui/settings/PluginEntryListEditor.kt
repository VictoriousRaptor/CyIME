package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kingzcheung.xime.ui.i18n.Text
import com.kingzcheung.xime.ui.i18n.UntranslatedContent
import com.kingzcheung.xime.ui.menubar.reorderOnLongPress
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件设置里 type = "entries" 的字段（11-19，AI 改写的提示词）：一条一张卡片，点一下就在卡片里改名字和内容、
 * 删除（不弹框），长按拖动排序，下面「添加」「恢复默认」。存成 JSON 数组 `[{"name", "text", …}]`，宿主不认识的字段
 * （插件自己的 id）原样带着走。
 */
internal object PluginEntries {
    data class Entry(val uid: Int, val name: String, val text: String, val extras: String = "{}")

    /** 读存的值；不是数组或坏了就当空列表（不抛，设置页照样能开）。 */
    fun parse(raw: String?, firstUid: Int = 0): List<Entry> {
        val array = runCatching { JSONArray(raw.orEmpty().trim().ifEmpty { "[]" }) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapIndexed { i, o ->
            val extras = JSONObject(o.toString()).apply { remove("name"); remove("text") }
            Entry(firstUid + i, o.optString("name"), o.optString("text"), extras.toString())
        }
    }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject(e.extras).put("name", e.name).put("text", e.text))
        }
    }.toString()

    /** 把 [from] 挪到 [to]，其他的顺次让开。 */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> =
        if (from == to || from !in list.indices || to !in list.indices) list
        else list.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
internal fun PluginEntryListEditor(
    label: String,
    helpText: String?,
    placeholder: String?,
    initial: String,
    defaultValue: String?,
    onChange: (String) -> Unit,
) {
    val parsed = remember { PluginEntries.parse(initial) }
    var nextUid by remember { mutableIntStateOf(parsed.size) }
    var entries by remember { mutableStateOf(parsed) }
    fun save(next: List<PluginEntries.Entry>) {
        entries = next
        onChange(PluginEntries.encode(next))
    }

    // 正在卡片里编辑的条目（一次只开一条）：uid = -1 是新加的，编辑框接在列表最后
    var editing by remember { mutableStateOf<PluginEntries.Entry?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // 拖动：长按整张卡片，跨过邻居一半高度就换位（列表不长，不做边缘滚动）
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var beforeDrag by remember { mutableStateOf<List<PluginEntries.Entry>>(emptyList()) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    fun dragBy(delta: Float) {
        val uid = dragging ?: return
        dragOffset += delta
        while (true) {
            val i = entries.indexOfFirst { it.uid == uid }
            val next = entries.getOrNull(i + 1)
            val prev = entries.getOrNull(i - 1)
            if (next != null && dragOffset > 0) {
                val step = (heights[next.uid] ?: break) + gap
                if (dragOffset < step / 2) break
                entries = PluginEntries.move(entries, i, i + 1); dragOffset -= step
            } else if (prev != null && dragOffset < 0) {
                val step = (heights[prev.uid] ?: break) + gap
                if (-dragOffset < step / 2) break
                entries = PluginEntries.move(entries, i, i - 1); dragOffset += step
            } else break
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        helpText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entries.isEmpty()) {
            Text("一条也没有：点下面「添加」", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.forEachIndexed { index, entry -> key(entry.uid) {
            val active = dragging == entry.uid
            if (editing?.uid == entry.uid) EntryEditorCard(
                initial = entry, placeholder = placeholder,
                onSave = { name, text ->
                    save(entries.map { if (it.uid == entry.uid) it.copy(name = name, text = text) else it })
                    editing = null
                },
                onDelete = { save(entries.filter { it.uid != entry.uid }); editing = null },
                onCancel = { editing = null },
            ) else Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
                    .testTag("entry:$index")
                    .onSizeChanged { heights[entry.uid] = it.height }
                    .zIndex(if (active) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (active) dragOffset else 0f
                        shadowElevation = if (active) 8.dp.toPx() else 0f
                        shape = RoundedCornerShape(12.dp)
                        clip = true
                    }
                    .reorderOnLongPress(
                        // 编辑着的时候不拖（编辑框里长按是选字）
                        onStart = { if (editing == null) { beforeDrag = entries; dragging = entry.uid; dragOffset = 0f } },
                        onDrag = ::dragBy,
                        onEnd = {
                            if (dragging != null) {
                                dragging = null; dragOffset = 0f
                                if (entries != beforeDrag) save(entries)
                            }
                        },
                        onCancel = { if (dragging != null) { dragging = null; dragOffset = 0f; entries = beforeDrag } },
                    )
                    .clickable { if (dragging == null && editing == null) editing = entry },
            ) {
                Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        UntranslatedContent {
                            Text(entry.name.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.text.ifBlank { "（提示词空着，面板上不显示）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.Default.DragHandle, contentDescription = "长按拖动排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
            }
        } }
        editing?.takeIf { it.uid < 0 }?.let { adding ->
            EntryEditorCard(
                initial = adding, placeholder = placeholder,
                onSave = { name, text ->
                    save(entries + PluginEntries.Entry(nextUid++, name, text))
                    editing = null
                },
                onDelete = null,
                onCancel = { editing = null },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { editing = PluginEntries.Entry(-1, "", "") }, enabled = editing == null) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
            Spacer(Modifier.weight(1f))
            if (defaultValue != null) TextButton(onClick = { confirmReset = true }) { Text("恢复默认") }
        }
    }

    if (confirmReset && defaultValue != null) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("恢复默认") },
        text = { Text("现在的条目会换成默认的几条，自己加的和改过的都会去掉。") },
        confirmButton = {
            TextButton(onClick = {
                val defaults = PluginEntries.parse(defaultValue, nextUid)
                nextUid += defaults.size
                save(defaults)
                confirmReset = false
            }) { Text("恢复默认") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
    )
}

/** 卡片里的编辑框：名字、提示词，下面删除／取消／保存。提示词空着不能保存。 */
@Composable
private fun EntryEditorCard(
    initial: PluginEntries.Entry,
    placeholder: String?,
    onSave: (name: String, text: String) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by remember(initial.uid) { mutableStateOf(initial.name) }
    var text by remember(initial.uid) { mutableStateOf(initial.text) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().testTag("entry-editor"),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("名字") }, placeholder = { Text("按钮上显示的字") },
                modifier = Modifier.fillMaxWidth().testTag("entry-name"))
            OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, maxLines = 8,
                label = { Text("提示词") }, placeholder = placeholder?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().testTag("entry-text"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = { onSave(name.trim(), text.trim()) }, enabled = text.isNotBlank()) { Text("保存") }
            }
        }
    }
}
