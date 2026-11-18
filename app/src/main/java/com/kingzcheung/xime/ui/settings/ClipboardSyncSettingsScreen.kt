package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.PushPin
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material.icons.twotone.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.plugin.core.runtime.PluginManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncSettingsContent(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(SettingsPreferences.isClipboardSyncEnabled(context))
    }
    val syncPlugins = remember { ExtensionManager.getEnabledClipboardSyncPlugins(context) }
    // 当前选中的插件实例（切换时在 IO 协程里 launch 完成后更新，驱动表单立即渲染新插件）；
    // 偏好未设置或不可用时显示第一个已启用的插件，与服务端实际使用的一致
    var activePlugin by remember {
        mutableStateOf(effectiveClipboardSyncPlugin(syncPlugins, SettingsPreferences.getClipboardSyncPluginId(context)))
    }
    var selectedPluginId by remember { mutableStateOf(activePlugin?.first.orEmpty()) }
    var installedPlugins by remember { mutableStateOf(ExtensionManager.getAllInstalledPlugins()) }
    val clipboardPlugins = remember(installedPlugins) { installedPlugins.filter { it.category == PluginCategory.CLIPBOARD_SYNC } }

    var selecting by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val pluginInstances by PluginManager.pluginInstancesFlow.collectAsState()
    val windowsPeer by com.kingzcheung.xime.clipboard.sync.WindowsDevices.paired.collectAsState()
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = SettingsPreferences.isClipboardSyncEnabled(context)
            val available = ExtensionManager.getEnabledClipboardSyncPlugins(context)
            activePlugin = effectiveClipboardSyncPlugin(available, SettingsPreferences.getClipboardSyncPluginId(context))
            selectedPluginId = activePlugin?.first.orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(pluginInstances, windowsPeer) {
        enabled = SettingsPreferences.isClipboardSyncEnabled(context)
        installedPlugins = ExtensionManager.getAllInstalledPlugins()
        val available = ExtensionManager.getEnabledClipboardSyncPlugins(context)
        activePlugin = effectiveClipboardSyncPlugin(available, SettingsPreferences.getClipboardSyncPluginId(context))
        selectedPluginId = activePlugin?.first.orEmpty()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installedPlugins = ExtensionManager.getAllInstalledPlugins()
        val available = ExtensionManager.getEnabledClipboardSyncPlugins(context)
        selectedPluginId = SettingsPreferences.getClipboardSyncPluginId(context)
        activePlugin = available.firstOrNull { it.first == selectedPluginId }
        enabled = SettingsPreferences.isClipboardSyncEnabled(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .settingsVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WindowsDevicesPanel()
            androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起手动连接与其他服务" else "备用：手动连接与其他服务") }
            if (advanced) {
            Text("连接 Windows 优先使用上方的“扫码连接电脑”。下方手动填写仅供旧版兼容或其他同步服务使用。",
                style = MaterialTheme.typography.bodyMedium)
            SettingsSection(title = "同步服务", content = {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (clipboardPlugins.isEmpty()) {
                        Text("正在准备内置同步服务；若持续未出现，请重新打开应用并在插件管理中检查状态。")
                    }
                    clipboardPlugins.forEach { plugin ->
                        val isActive = plugin.id == selectedPluginId
                        PluginServiceChoice(plugin.name, plugin.description, isActive, !selecting) {
                            if (!isActive) {
                                selecting = true
                                selectionError = null
                                scope.launch {
                                    try {
                                        val instance = withContext(Dispatchers.IO) {
                                            clipboardPlugins.filter { it.id != plugin.id }.forEach {
                                                SettingsPreferences.setPluginEnabled(context, it.id, false)
                                                PluginManager.unloadPlugin(it.id)
                                            }
                                            SettingsPreferences.setClipboardSyncPluginId(context, plugin.id)
                                            SettingsPreferences.setPluginEnabled(context, plugin.id, true)
                                            PluginManager.launchPlugin(plugin.id)
                                            ExtensionManager.getEnabledClipboardSyncPlugins(context).firstOrNull { it.first == plugin.id }
                                        }
                                        selectedPluginId = plugin.id
                                        activePlugin = instance
                                        if (instance == null) selectionError = "插件未能启动，请在已安装插件中检查状态。"
                                    } catch (error: Exception) {
                                        activePlugin = null
                                        selectionError = "无法启用服务：${error.message}"
                                    } finally { selecting = false }
                                }
                            }
                        }
                    }
                    selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            })
            activePlugin?.takeUnless { it.first == com.kingzcheung.xime.clipboard.sync.WindowsDevices.ID }?.let { selected ->
                PluginConfigFormScreen(
                    pluginId = selected.first,
                    plugin = selected.second,
                    pluginName = installedPlugins.find { it.id == selected.first }?.name ?: selected.first,
                    onBack = {}, embedded = true
                )
            }
            Button(onClick = onNavigateToPlugins) { Text("管理其他同步服务") }
            if (activePlugin != null && activePlugin?.first != com.kingzcheung.xime.clipboard.sync.WindowsDevices.ID) SettingsSection(title = "同步开关", content = {
                SettingsToggleItem(
                    icon = Icons.TwoTone.Sync,
                    title = "启用所选兼容服务",
                    subtitle = "先选择服务并完成配置，再开启双向同步。开启后会向该服务传输剪贴板文本。",
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked && activePlugin == null) {
                            selectionError = "请先选择同步服务并完成连接配置。"
                        } else {
                            enabled = checked
                            SettingsPreferences.setClipboardSyncEnabled(context, checked)
                        }
                    }
                )
            })
            }
            // 用户 11-15：验证码、敏感内容、导出导入挪到「剪贴板」页，这页只管同步。
        }
    }
}

/**
 * 实际在用的同步插件：与输入法服务相同，偏好的插件不可用（或没选）时用第一个已启用的。
 * 设置页按它显示，免得页面写“未选”而后台其实在用第一个。
 */
internal fun <T> effectiveClipboardSyncPlugin(available: List<Pair<String, T>>, preferredId: String): Pair<String, T>? =
    available.firstOrNull { it.first == preferredId } ?: available.firstOrNull()

/**
 * 把剪贴板文字记录导出成用户自己保存的文件（TXT / Markdown / JSON），经系统的保存窗口；
 * 图片不导出。固定内容默认包含，可关掉。只在用户点「导出」时读一次数据库。
 */
@Composable
internal fun ClipboardExportSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(com.kingzcheung.xime.clipboard.ClipboardExportFormat.TXT) }
    var includePinned by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun export(uri: android.net.Uri?) {
        if (uri == null) return
        busy = true; message = null
        scope.launch {
            val chosen = format
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val items = com.kingzcheung.xime.clipboard.ClipboardExport.order(
                        com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(context).exportItems(), includePinned)
                    val groups = com.kingzcheung.xime.clipboard.ClipboardGroupStore.getInstance(context).state.value
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                        com.kingzcheung.xime.clipboard.ClipboardExport.write(items, chosen, it, now) { item ->
                            groups.groupOf("text:${item.id}")?.name
                        }
                    }
                    items.size
                }
            }
            busy = false
            message = result.fold({ "已导出 $it 条" }, { "导出失败：${it.message ?: "未知错误"}" })
        }
    }
    val launchers = com.kingzcheung.xime.clipboard.ClipboardExportFormat.entries.associateWith { f ->
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(f.mimeType)) { uri -> export(uri) }
    }
    SettingsSection(title = "导出剪贴板", content = {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("导出格式", style = MaterialTheme.typography.titleSmall)
            Text("只导出文字记录，不含图片", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.kingzcheung.xime.clipboard.ClipboardExportFormat.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("clipboard-export-format:${option.extension}")
                    .selectable(format == option, role = androidx.compose.ui.semantics.Role.RadioButton) { format = option }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = format == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("${option.label}(.${option.extension})", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        SettingsToggleItem(
            icon = Icons.TwoTone.PushPin,
            title = "包含固定内容",
            subtitle = "固定内容常是地址、账号等，导出的文件请自己妥善保管",
            checked = includePinned,
            onCheckedChange = { includePinned = it },
        )
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = {
                launchers.getValue(format).launch(com.kingzcheung.xime.clipboard.ClipboardExport.fileName(format, System.currentTimeMillis()))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("clipboard-export")) {
                Icon(Icons.TwoTone.Upload, contentDescription = null)
                Text("导出", modifier = Modifier.padding(start = 6.dp))
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    })
}

/**
 * 把导出的剪贴板文件（TXT / Markdown / JSON）导回来，经系统的打开窗口；只在用户点按钮时读一次文件。
 * 已有的记录不重复加；JSON 和 Markdown 保留固定、编码和分组，TXT 只有文字，导成历史记录。
 */
@Composable
internal fun ClipboardImportSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            check(out.size() <= com.kingzcheung.xime.clipboard.ClipboardImport.MAX_FILE_BYTES) { "文件太大" }
                        }
                        out.toByteArray()
                    }
                    val clips = com.kingzcheung.xime.clipboard.ClipboardImport.parse(bytes.decodeToString())
                    com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(context).importItems(clips)
                }
            }
            busy = false
            message = result.fold({ it.message }, { "导入失败：${it.message ?: "未知错误"}" })
        }
    }
    SettingsSection(title = "导入剪贴板", content = {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("从导出的文件导入", style = MaterialTheme.typography.titleSmall)
            Text("选上面导出的 TXT、Markdown 或 JSON 文件，已有的记录不会重复。JSON 和 Markdown 保留固定内容、编码和分组；TXT 只有文字，导入成历史记录",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                launcher.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("clipboard-import")) {
                Icon(Icons.TwoTone.Download, contentDescription = null)
                Text("选择文件导入", modifier = Modifier.padding(start = 6.dp))
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    })
}
