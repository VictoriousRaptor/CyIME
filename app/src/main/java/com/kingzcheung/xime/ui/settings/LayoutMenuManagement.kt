package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kingzcheung.xime.service.settingsActivityIntent
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.menubar.dragOrderItem
import com.kingzcheung.xime.ui.menubar.rememberDragOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@Composable
internal fun LayoutMenuManagement(initialLanguage: InputLanguage?, layoutStatus: String, modifier: Modifier,
    editors: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var entries by remember { mutableStateOf<List<SchemaInfo>?>(null) }
    var selected by rememberSaveable(initialLanguage) { mutableStateOf(initialLanguage) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context, owner, layoutStatus) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SchemaManager.enabledSchemasChanged.onStart { emit(Unit) }.collectLatest {
                try {
                    val (available, current) = withContext(Dispatchers.IO) {
                        if (!KeysConfigHelper.hasLoadedLayoutBindings) KeysConfigHelper.loadConfig(context)
                        val available = SchemaManager.keyboardMenuSchemas(context).filter { it.profile.mode == InputMode.KEYBOARD }
                        available to InputModes.languageOf(SettingsPreferences.getCurrentSchema(context), available)
                    }
                    if (selected == null) selected = current
                    entries = available
                    failure = null
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { failure = "无法读取可用布局：${e.localizedMessage.orEmpty()}" }
            }
        }
    }
    val language = selected ?: InputLanguage.CHINESE
    val all = entries.orEmpty()
    val group = all.filter { it.profile.language == language }
    val choices = LayoutMenuPreferences.observe(language)
    val layouts = choices.ordered(InputProfileSelection.layoutRow(group, all))
    val shown = choices.shown(layouts)
    // 语言菜单和别处同一个先后：中文、英文、日语…（以前跟着方案顺序，日语会排到英文前面）。
    val languageRank = remember(entries) { InputModes.languageRank(context) }
    // 没开日语时语言菜单里不列日语（用户 11-22）；正在看的语言总在。
    val japaneseOn = remember(entries) { com.kingzcheung.xime.settings.LanguagePreferences.japaneseOn(context) }
    val languages = (all.map { it.profile.language }.filter { it != InputLanguage.JAPANESE || japaneseOn } + language)
        .distinct().sortedBy(languageRank)
    key(language) {
        val drag = rememberDragOrder(layouts.map { it.id }) { LayoutMenuPreferences.reorder(context, language, it) }
        val byId = layouts.associateBy { it.id }
        val rows = (drag.order + byId.keys).distinct().mapNotNull(byId::get)
        LazyColumn(modifier.settingsGutterScroll(drag.list).testTag("layout-management-list"), state = drag.list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SettingsGroupTitle("键盘切换栏", Modifier.weight(1f))
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }, enabled = languages.size > 1) {
                            Text(language.displayName)
                            if (languages.size > 1) Icon(Icons.Default.ArrowDropDown, "切换语言")
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            languages.forEach { target ->
                                DropdownMenuItem(text = { Text(target.displayName) }, onClick = { selected = target; expanded = false })
                            }
                        }
                    }
                }
            }
            itemsIndexed(rows, key = { _, layout -> layout.id }) { index, layout ->
                val checked = layout in shown
                val canChange = !checked || shown.size > 1
                val schemes = group.filter { InputProfileSelection.offers(it, layout.id, all) }
                    .map { it.schemeLabel }.distinct().joinToString("、")
                SettingsGroupRow(index, rows.size, dragOrderItem(drag, layout.id, rows.size > 1)) {
                    SettingsListRow(layout.displayName, if (canChange) schemes else "至少保留一个布局",
                        Modifier.testTag("layout-menu:${language.id}:${layout.id}")
                            .toggleable(checked, enabled = canChange, role = Role.Switch) { show ->
                                if (drag.dragging == null) LayoutMenuPreferences.setVisible(context, language, layouts, layout.id, show)
                            }) {
                        Switch(checked, onCheckedChange = null, enabled = canChange,
                            modifier = Modifier.padding(end = 12.dp), colors = settingsSwitchColors())
                    }
                }
            }
            item {
                SettingsNote(failure ?: when {
                    entries == null -> "正在读取布局…"
                    rows.isEmpty() -> "启用这个语言的方案后，布局会显示在这里。"
                    else -> "开着的布局显示在切换栏，长按拖动排序。"
                })
                TextButton(onClick = { context.startActivity(settingsActivityIntent(context, SettingsRoutes.Schema)) }) {
                    Text("已安装的方案")
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = editors)
            }
        }
    }
}
