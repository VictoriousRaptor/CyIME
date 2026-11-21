package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.CellDictionaryCatalog
import com.kingzcheung.xime.settings.CellDictionaryCost
import com.kingzcheung.xime.settings.CellDictionaryOffer
import com.kingzcheung.xime.settings.DictionaryExport
import com.kingzcheung.xime.settings.InstalledCellDictionary
import com.kingzcheung.xime.viewmodel.CellDictionaryUiState
import com.kingzcheung.xime.viewmodel.CellDictionaryViewModel

@Composable
fun CellDictionarySettingsContent(onBack: () -> Unit, embedded: Boolean = false) {
    val model: CellDictionaryViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::import)
    }
    // 选好导出什么、什么格式后先问存到哪；文件选择器回来再写。
    var pendingExport by remember { mutableStateOf<Pair<String?, DictionaryExport.Format>?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = pendingExport
        pendingExport = null
        if (uri != null && job != null) model.export(uri, job.first, job.second)
    }
    CellDictionarySettingsPanel(state, onBack, model::download, model::select, model::apply,
        onImport = { importer.launch(arrayOf("*/*")) },
        onSource = { url ->
            runCatching { uriHandler.openUri(url) }.onFailure { linkError = "无法打开浏览器" }
        },
        onRemove = model::remove, embedded = embedded, onSelectEnglish = model::selectEnglish,
        // 本机或同型号实测过词库曲线就用实测（dict_cost_bench），否则用电脑上的最坏估计。
        curve = rememberPerformanceCostSource()?.takeIf { it.sameModel }
            ?.let { CellDictionaryCost.Curve.fromDevice(it.device) } ?: CellDictionaryCost.Curve.DESKTOP,
        onCheckWordUpdates = model::checkWordUpdates, onAutoUpdate = model::setAutoUpdate,
        onExport = { source, name, format ->
            pendingExport = source to format
            exporter.launch(exportFileName(name, format))
        })
    linkError?.let { message ->
        AlertDialog(onDismissRequest = { linkError = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { linkError = null }) { Text("知道了") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellDictionarySettingsPanel(
    state: CellDictionaryUiState,
    onBack: () -> Unit,
    onDownload: (CellDictionaryOffer) -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onApply: () -> Unit,
    onImport: () -> Unit,
    onSource: (String) -> Unit,
    onRemove: (String) -> Unit = {},
    /** Inside the 词库 page: no own top bar. */
    embedded: Boolean = false,
    onSelectEnglish: (Boolean) -> Unit = {},
    onCheckWordUpdates: () -> Unit = {},
    onAutoUpdate: (Boolean) -> Unit = {},
    curve: CellDictionaryCost.Curve = CellDictionaryCost.Curve.DESKTOP,
    /** 导出：source 为 null 是学到的词，否则是导入的词库 id；name 用来起文件名。 */
    onExport: (source: String?, name: String, format: DictionaryExport.Format) -> Unit = { _, _, _ -> },
) {
    var confirmLarge by remember { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val dictionaries = state.dictionaries
    val installed = dictionaries.installed.associateBy { it.id }
    val builtinIds = CellDictionaryCatalog.builtins.map { it.id }.toSet()
    val catalogIds = CellDictionaryCatalog.offers.map { it.id }.toSet() + builtinIds
    fun matches(name: String, group: String) =
        query.isBlank() || name.contains(query.trim(), ignoreCase = true) || group.contains(query.trim())
    // One list grouped by category: built-in first, then Sogou, then the user's own imports.
    val rows = buildList {
        CellDictionaryCatalog.builtins.forEach { builtin ->
            installed[builtin.id]?.let { add(DictionaryItem(builtin.id, builtin.name, builtin.category, it, null, builtin.sourceUrl)) }
        }
        CellDictionaryCatalog.offers.forEach { offer ->
            add(DictionaryItem(offer.id, offer.name, offer.category, installed[offer.id], offer, offer.sourceUrl))
        }
        dictionaries.installed.filter { it.id !in catalogIds }.forEach {
            add(DictionaryItem(it.id, it.name, MY_IMPORTS, it, null, ""))
        }
    }.filter { matches(it.name, it.category) }.groupBy { it.category }
    // 每个词库各自让打字慢多少，底部写开着的合计（CellDictionaryCost，全是生僻词时的最坏估计）。
    val selectedEntries = dictionaries.installed.filter { it.id in dictionaries.selected }.sumOf { it.count }
    fun share(id: String, entries: Int): Double {
        val others = selectedEntries - if (id in dictionaries.selected) entries else 0
        return CellDictionaryCost.share(others, entries, curve)
    }
    val total = CellDictionaryCost.slowdown(selectedEntries, curve)
    val totalLevel = CellDictionaryCost.level(total)
    val large = totalLevel == CellDictionaryCost.Level.HEAVY
    Scaffold(topBar = {
        if (!embedded) TopAppBar(title = { Text("导入的词库") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }, bottomBar = {
        if (dictionaries.pending || dictionaries.selected.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dictionaries.selected.isNotEmpty()) Text("开着 ${dictionaries.selected.size} 个词库，${words(selectedEntries)}，" +
                    "打字最多慢 ${CellDictionaryCost.format(total)}" + when (totalLevel) {
                        CellDictionaryCost.Level.NORMAL -> ""
                        CellDictionaryCost.Level.NOTICEABLE -> "，超出正常"
                        CellDictionaryCost.Level.HEAVY -> "，会明显卡顿"
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cell-dictionary-total-cost"),
                    color = costColor(totalLevel))
                if (dictionaries.pending) Button(onClick = { if (large) confirmLarge = true else onApply() },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("应用更改")
                }
            }
        }
    }) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.settingsGutterScroll(listState).fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            // 顶上只留搜索和导入一行，词库列表紧跟着；开关和说明放到列表下面（11-03「占了半个屏幕」）。
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DictionarySearchField(query, { query = it }, "搜索词库", Modifier.weight(1f))
                    TextButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.padding(start = 8.dp)) {
                        Text("导入文件")
                    }
                    TextButton(onClick = { showExport = true }, enabled = !state.busy,
                        modifier = Modifier.testTag("cell-dictionary-export")) {
                        Text("导出")
                    }
                }
            }
            state.message?.let { message -> item(key = "message") {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            } }
            if (rows.isEmpty()) item {
                DictionaryEmptyLine("没有找到词库")
            }
            rows.forEach { (category, items) ->
                item(key = "header-$category") { SettingsGroupTitle(category, Modifier.padding(top = 8.dp)) }
                itemsIndexed(items, key = { _, item -> item.id }) { row, item ->
                    SettingsGroupRow(row, items.size) {
                        DictionaryRow(item, item.id in dictionaries.selected, item.id in dictionaries.applied, state.busy,
                            cost = share(item.id, item.installed?.count ?: item.offer?.entryCount ?: 0),
                            onSelect = { onSelect(item.id, it) }, onDownload = { item.offer?.let(onDownload) },
                            onSource = item.sourceUrl.takeIf { it.isNotBlank() }?.let { url -> { onSource(url) } },
                            onRemove = if (item.id in builtinIds) null else ({ onRemove(item.id) }))
                    }
                }
            }
            item {
                TextButton(onClick = { onSource(CellDictionaryCatalog.SOURCE_URL) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("到搜狗词库网站找更多")
                }
            }
            item(key = "settings-title") { SettingsGroupTitle("设置", Modifier.padding(top = 8.dp)) }
            item(key = "auto-update") {
                SettingsGroupRow(0, 2) {
                    SettingsToggleItem(Icons.Default.Sync, "自动更新词库", "每天查一次新词，只下载不上传",
                        state.autoUpdate, onAutoUpdate)
                }
            }
            item(key = "english") {
                SettingsGroupRow(1, 2) {
                    SettingsToggleItem(Icons.Default.Translate, "分类词库英文候选", "中文键盘里也出导入词库的英文词",
                        dictionaries.selectedEnglish, onSelectEnglish,
                        lockMessage = if (state.busy) "正在处理词库" else null)
                }
            }
            item(key = "check-updates") {
                TextButton(onClick = onCheckWordUpdates, enabled = !state.busy) { Text("检查内置词库更新") }
            }
            // 导入认哪些文件：一行写出扩展名，点开看怎么导入和每种格式的示例。
            item(key = "import-hint") {
                Row(Modifier.fillMaxWidth().clickable { showGuide = true }.padding(start = 4.dp)
                    .testTag("cell-dictionary-import-hint"), verticalAlignment = Alignment.CenterVertically) {
                    Text("支持搜狗、QQ、百度、Gboard、微软拼音、Rime 等格式", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showGuide = true }) { Text("格式与示例") }
                }
            }
        }
    }
    if (showGuide) CellDictionaryImportGuide(onDismiss = { showGuide = false }, onImport = onImport)
    if (showExport) DictionaryExportDialog(dictionaries.installed, onDismiss = { showExport = false }) { source, name, format ->
        showExport = false
        onExport(source, name, format)
    }
    if (confirmLarge) {
        AlertDialog(onDismissRequest = { confirmLarge = false },
            title = { Text("词库较多") },
            text = { Text("开启的词库共${words(selectedEntries)}，打字最多慢 " +
                "${CellDictionaryCost.format(total)}，候选里也会多一些不常用的词。仍要应用吗？") },
            confirmButton = { TextButton(onClick = { confirmLarge = false; onApply() }) { Text("仍然应用") } },
            dismissButton = { TextButton(onClick = { confirmLarge = false }) { Text("取消") } })
    }
}

private const val MY_IMPORTS = "我导入的"

private data class DictionaryItem(
    val id: String, val name: String, val category: String,
    val installed: InstalledCellDictionary?, val offer: CellDictionaryOffer?, val sourceUrl: String,
)

/** 正常灰字；超出正常用橙色，很慢用红色。橙色按底色深浅取明度，浅色、深色和玻璃底上都看得清。 */
@Composable
private fun costColor(level: CellDictionaryCost.Level): Color = when (level) {
    CellDictionaryCost.Level.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
    CellDictionaryCost.Level.NOTICEABLE ->
        if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFFFFB74D) else Color(0xFFB45F00)
    CellDictionaryCost.Level.HEAVY -> MaterialTheme.colorScheme.error
}

/** Number of words in plain terms: 1600 → 约 1600 词, 22029 → 约 2.2 万词. */
private fun words(count: Int): String =
    if (count >= 10_000) "约 ${"%.1f".format(count / 10_000.0).removeSuffix(".0")} 万词" else "约 $count 词"

@Composable
private fun DictionaryRow(
    item: DictionaryItem, selected: Boolean, applied: Boolean, busy: Boolean,
    /** 这个词库让打字最多慢多少（[CellDictionaryCost.share]），单个词库就超出正常时这一行也变色。 */
    cost: Double,
    onSelect: (Boolean) -> Unit, onDownload: () -> Unit, onSource: (() -> Unit)?, onRemove: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val installed = item.installed
    val detail = when {
        installed == null -> "${words(item.offer?.entryCount ?: 0)} · 最多慢 ${CellDictionaryCost.format(cost)} · 需下载"
        selected != applied -> "${words(installed.count + installed.english + installed.stroke)} · 最多慢 ${CellDictionaryCost.format(cost)} · 应用后生效"
        else -> "${words(installed.count + installed.english + installed.stroke)} · 最多慢 ${CellDictionaryCost.format(cost)}"
    }
    val level = CellDictionaryCost.level(cost)
    // Delete only what is not in use: switch it off and apply first.
    val removable = installed != null && onRemove != null && !selected && !applied && !busy
    SettingsListRow(item.name, detail,
        detailColor = if (level == CellDictionaryCost.Level.NORMAL) Color.Unspecified else costColor(level)) {
        if (installed != null) Switch(checked = selected, onCheckedChange = onSelect, enabled = !busy,
            colors = settingsSwitchColors(),
            modifier = Modifier.semantics { contentDescription = "启用${item.name}" })
        else TextButton(onClick = onDownload, enabled = !busy) { Text("下载") }
        if (onSource != null || installed != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${item.name}的更多操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onSource != null) DropdownMenuItem(text = { Text("查看来源") }, onClick = { menu = false; onSource() })
                if (installed != null) {
                    val label = when {
                        onRemove == null -> "内置词库随安装包提供，可关闭"
                        selected -> "删除前请关闭词库并应用更改"
                        applied -> "应用更改后可删除"
                        else -> "删除词库"
                    }
                    DropdownMenuItem(text = { Text(label, color = if (removable)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }, enabled = removable,
                        onClick = { menu = false; onRemove?.invoke() })
                }
            }
        } else Spacer(Modifier.width(48.dp))
    }
}

/** 「CyIME词库-名字-20261116.txt」；名字里不能进文件名的字符换成 _。 */
internal fun exportFileName(name: String, format: DictionaryExport.Format, date: java.util.Date = java.util.Date()): String {
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(date)
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40).ifBlank { "词库" }
    return "CyIME词库-$safe-$day.${format.extension}"
}
