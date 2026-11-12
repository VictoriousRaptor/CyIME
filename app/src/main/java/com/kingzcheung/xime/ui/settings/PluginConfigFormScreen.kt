package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.kingzcheung.xime.ui.common.CompactMenuStyle
import com.kingzcheung.xime.ui.common.CompactMenuRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.plugin.PluginNetworkAuthHelper
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.lua.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginConfigFormScreen(
    pluginId: String,
    plugin: IPluginConfigurable,
    pluginName: String,
    schema: List<UiNode> = emptyList(),
    onBack: () -> Unit,
    embedded: Boolean = false,
    /** 页面顶上的一句提示（插件没加载上、没有设置项时）。 */
    note: String? = null
) {
    val context = LocalContext.current
    val configStore = remember(pluginId) {
        PluginConfigStoreImpl(context.applicationContext as android.app.Application, pluginId)
    }
    val fields = remember(schema, plugin) {
        if (schema.isNotEmpty()) schema else {
            runCatching { plugin.getSettingsSchema() }.getOrElse { emptyList() }
        }
    }
    val groupedFields = remember(fields) { fields.groupBy { it.section.orEmpty() } }

    val dynamicOptions = remember(plugin) { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(plugin, fields) {
        fields.filter { it.type == UiNodeType.SELECT || it.type == UiNodeType.MULTI_SELECT }
            .filter { it.key != null }
            .forEach { field ->
                val opts = withContext(Dispatchers.IO) {
                    runCatching { plugin.getOptions(field.key!!) }.getOrNull()
                }
                if (opts != null) dynamicOptions[field.key!!] = opts
            }
    }

    // 插件声明 allowCustomHosts 时，配置中填写的 HTTP(S) 服务器域名自动获得联网授权，
    // 兑现 manifest helpText「域名将自动获得联网授权」的承诺，无需用户再手动寻找授权入口。
    fun autoAuthorizeConfiguredHosts() {
        val allowCustom = PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
            ?.allowCustomHosts ?: false
        if (!allowCustom) return
        configStore.keys().forEach { key ->
            configStore.get(key)?.let { value ->
                NetworkPolicy.extractHttpHost(value)?.let { host ->
                    SettingsPreferences.authorizePluginHost(context, pluginId, host)
                }
            }
        }
    }

    @Composable
    fun sectionContent(section: String, sectionFields: List<UiNode>) {
        SettingsSection(
            title = section.ifBlank { "插件配置" },
            content = {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sectionFields.forEach { field ->
                        UiNodeEditor(
                            field = field,
                            configStore = configStore,
                            options = field.options.ifEmpty {
                                field.key?.let { dynamicOptions[it].orEmpty() } ?: emptyList()
                            },
                            plugin = plugin,
                            // 点测试前先把填的接口地址授权，不用先点「保存」
                            beforeAction = { autoAuthorizeConfiguredHosts() }
                        )
                    }
                }
            }
        )
    }

    @Composable
    fun saveButton() {
        Button(
            onClick = {
                var allValid = true
                fields.forEach { field ->
                    if (field.type == UiNodeType.SECRET && field.required && field.key != null) {
                        val current = configStore.get(field.key!!)
                        if (current.isNullOrBlank()) allValid = false
                    }
                }
                if (!allValid) {
                    Toast.makeText(context, "请填写必填配置", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                autoAuthorizeConfiguredHosts()
                Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                if (!embedded) onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("保存")
        }
    }

    // 顶上的「启用」和入口：停用着的插件在这页就能打开，打开后告诉他去键盘哪里点。
    val installedInfo = remember(pluginId) { PluginManager.getAllInstallPlugins().firstOrNull { it.id == pluginId } }
    var pluginEnabled by remember(pluginId) { mutableStateOf(SettingsPreferences.isPluginEnabled(context, pluginId)) }
    var placeTick by remember { mutableStateOf(0) }
    val entryHint = remember(installedInfo, pluginEnabled, placeTick) {
        installedInfo?.let { pluginEntryHint(context, it, pluginEnabled) }
    }
    val toggleScope = rememberCoroutineScope()
    val contactsGate = rememberContactsPermissionGate(pluginId)

    // 没有「保存」：字段边打边存，离开这页时把填的地址授权（文件导入的插件用；市场装的和内置的本来就放行）。
    var uninstalled by remember(pluginId) { mutableStateOf(false) }
    if (!embedded) DisposableEffect(pluginId) {
        onDispose { if (!uninstalled) autoAuthorizeConfiguredHosts() }
    }
    var confirmUninstall by remember { mutableStateOf(false) }
    if (confirmUninstall && installedInfo != null) AlertDialog(
        onDismissRequest = { confirmUninstall = false },
        title = { Text("卸载插件") },
        text = { Text("确定要卸载「${installedInfo.name.ifEmpty { installedInfo.id }}」吗？插件文件和配置会删掉。") },
        confirmButton = {
            TextButton(onClick = {
                confirmUninstall = false
                uninstalled = true
                toggleScope.launch {
                    uninstallPluginCompletely(context, pluginId)
                    onBack()
                }
            }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("取消") } },
    )

    if (embedded) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedFields.forEach { (section, sectionFields) ->
                sectionContent(section, sectionFields)
            }
            saveButton()
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(pluginName) },
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
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.settingsGutterScroll(listState)
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                if (installedInfo != null) item {
                    val about = listOfNotNull(
                        installedInfo.description.takeIf { it.isNotBlank() },
                        installedInfo.versionName.takeIf { it.isNotBlank() }?.let { "版本 $it" },
                    ).joinToString("\n")
                    if (about.isNotEmpty()) SettingsNote(about)
                }
                if (note != null) item { SettingsNote(note) }
                if (installedInfo != null && canEnableFromSettings(installedInfo)) item {
                    SettingsSection(title = "插件") {
                        SettingsToggleItem(
                            icon = Icons.Default.Extension,
                            title = "启用",
                            subtitle = entryHint.orEmpty(),
                            checked = pluginEnabled,
                            onCheckedChange = { on ->
                                contactsGate(on) {
                                    pluginEnabled = on
                                    toggleScope.launch {
                                        setToolPluginEnabled(context, pluginId, on)
                                        placeTick++
                                    }
                                }
                            },
                        )
                    }
                } else if (entryHint != null) item { SettingsNote(entryHint) }
                // 字幕插件：字幕的设置在宿主里（11-05 从 语音转文本 › 字幕 整组搬来），插件开着才显示。
                if (pluginId == com.kingzcheung.xime.ui.caption.CaptionPlugin.ID && pluginEnabled) item { CaptionSettingsSection() }
                // 日语插件：词库和转换引擎下载到哪一步了。
                if (pluginId == com.kingzcheung.xime.settings.JapanesePlugin.ID && pluginEnabled) item {
                    JapanesePackStatus(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), showReady = true)
                }
                groupedFields.forEach { (section, sectionFields) ->
                    item {
                        sectionContent(section, sectionFields)
                    }
                }

                if (installedInfo != null) {
                    val trusted = PluginNetworkAuthHelper.trustsConfiguredHosts(installedInfo)
                    // 市场装的和内置的插件：填的地址直接放行，这里只说一句；要授权的域名照旧列出来。
                    val hosts = if (trusted) installedInfo.declaredHosts.distinct() else (installedInfo.declaredHosts +
                        ExtensionManager.getConfiguredNetworkHosts(context, pluginId) +
                        SettingsPreferences.getPluginPendingHosts(context, pluginId)).distinct()
                    if (trusted || hosts.isNotEmpty()) item {
                        SettingsSection(title = "联网") {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (trusted) Text("只连你在上面填的接口地址",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (hosts.isNotEmpty()) NetworkAccessSection(
                                    pluginId = pluginId,
                                    pluginName = installedInfo.name.ifEmpty { pluginId },
                                    hosts = hosts,
                                    showTitle = false,
                                )
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                if (installedInfo.source != PluginSource.SYSTEM) confirmUninstall = true
                                // 独立安装包形式的插件：到系统的应用信息里卸载（原来「已安装」里的做法）
                                else runCatching {
                                    context.startActivity(android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:$pluginId"),
                                    ))
                                    Toast.makeText(context, "请在应用信息页面卸载", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("卸载", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UiNodeEditor(
    field: UiNode,
    configStore: PluginConfigStore,
    options: List<String>,
    plugin: IPluginConfigurable,
    beforeAction: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 表单字段必须绑定 configStore key；无 key 的节点（展示型误入表单）不渲染
    val key = field.key ?: return
    var buttonBusy by remember(key) { mutableStateOf(false) }
    // 按钮结果写在按钮下面：null 没点过；"" 成功；其他是错误原因
    var buttonResult by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var value by rememberSaveable(key) {
        mutableStateOf(configStore.get(key) ?: field.defaultValue ?: "")
    }
    var showSecret by rememberSaveable(key) { mutableStateOf(false) }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }

    fun persist() {
        configStore.set(key, value)
    }

    when (field.type) {
        // 展示型节点（SECTION/METRIC/DIVIDER）在设置表单中忽略
        UiNodeType.SECTION, UiNodeType.METRIC, UiNodeType.DIVIDER -> Unit
        UiNodeType.TEXTAREA -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label ?: "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                // 按内容长高：一行字就一行，最多 8 行再滚动（11-03「每一行都是一个大框…自适应高度不行吗」）。
                minLines = 1,
                maxLines = 8,
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        UiNodeType.TEXT,
        UiNodeType.NUMBER -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label ?: "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = if (field.type == UiNodeType.NUMBER) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        UiNodeType.SECRET -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label ?: "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                visualTransformation = if (showSecret) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSecret) "隐藏" else "显示"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        UiNodeType.SWITCH -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = field.label ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    field.helpText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = value == "true",
                    onCheckedChange = {
                        value = it.toString()
                        persist()
                    },
                    colors = SwitchDefaults.colors()
                )
            }
        }

        UiNodeType.SELECT -> {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(field.label ?: "") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    supportingText = if (options.isEmpty() && field.options.isEmpty())
                        { { Text("暂无可用选项") } }
                    else
                        field.helpText?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = CompactMenuStyle.Shape,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = CompactMenuStyle.Elevation,
                ) {
                    options.forEach { option ->
                        CompactMenuRow(
                            label = option, selected = value == option,
                            onClick = {
                                value = option
                                persist()
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        UiNodeType.MULTI_SELECT -> {
            val selected = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = field.label ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                field.helpText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (options.isEmpty()) {
                    Text(
                        text = "暂无可用选项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option in selected,
                                onClick = {
                                    val next = if (option in selected) {
                                        selected - option
                                    } else {
                                        selected + option
                                    }
                                    value = next.sorted().joinToString(",")
                                    persist()
                                },
                                label = { Text(option) }
                            )
                        }
                    }
                }
            }
        }

        UiNodeType.BUTTON -> {
            Button(
                onClick = {
                    val action = key
                    if (action.isNullOrBlank()) {
                        Toast.makeText(context, "未配置动作", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    runCatching { beforeAction() }
                    buttonBusy = true
                    buttonResult = null
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            runCatching { plugin.onAction(action.toString()) }
                                .getOrElse { e -> "出错：${e.message ?: e.javaClass.simpleName}" }
                        }
                        buttonBusy = false
                        buttonResult = error.orEmpty()
                    }
                },
                enabled = !buttonBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (buttonBusy) "处理中…" else field.label ?: "")
            }
            val result = buttonResult
            if (!buttonBusy && result != null) {
                Text(
                    text = if (result.isEmpty()) "结果：成功" else "结果：$result",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.isEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 插件自己没有设置项、设置由宿主画在插件页里的插件（字幕的开关、日语的下载状态）。 */
internal fun hasHostSettings(pluginId: String): Boolean =
    pluginId == com.kingzcheung.xime.ui.caption.CaptionPlugin.ID || pluginId == com.kingzcheung.xime.settings.JapanesePlugin.ID
