package com.kingzcheung.xime.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import com.kingzcheung.xime.model.DownloadRegion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.twotone.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.ModelItemState
import com.kingzcheung.xime.viewmodel.ModelManagementViewModel

/** 本地模型管理：列出已下载模型并提供删除入口（对齐本地方案页）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLocalContent(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ModelManagementViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            com.kingzcheung.xime.ui.i18n.UiToast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    var pendingDelete by remember { mutableStateOf<ModelItemState?>(null) }
    var autoUpdate by remember { mutableStateOf(com.kingzcheung.xime.model.ModelAutoUpdater.isEnabled(context)) }

    pendingDelete?.let { model ->
        ModelDeleteDialog(
            item = model,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteModel(model.model.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        val localModels = uiState.models.filter { it.isDownloaded }
        // 带模型的安装包内置拍照识别模型（没有删除按钮）；轻量版下载后和其他模型一样列在下面。
        val builtinOcr = remember(localModels) {
            com.kingzcheung.xime.ocr.OcrModelFiles.bundled(context) &&
                localModels.none { it.model.id == com.kingzcheung.xime.model.BuiltinModelCatalog.OCR_MODEL }
        }
        // 三段都用设置页统一的分组样式：标题 + 一个圆角面板，行样式一致，说明文字只放在所属分组里。
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState)
                .fillMaxSize()
                .padding(padding)
                .testTag("model-local-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "models") {
                SettingsSection(title = "已安装模型(${localModels.size + if (builtinOcr) 1 else 0})") {
                    if (builtinOcr) BuiltinOcrModelRow()
                    localModels.forEachIndexed { index, item ->
                        if (builtinOcr || index > 0) ModelRowDivider()
                        ModelLocalRow(item = item, autoUpdate = autoUpdate, onDelete = { pendingDelete = item })
                    }
                    if (localModels.isEmpty() && uiState.isLoading) {
                        ModelRowDivider()
                        ModelSectionNote("正在检查已下载的模型…")
                    }
                    ModelRowDivider()
                    ModelSectionNote("本地模型在手机上运行，会占用 CPU、增加耗电，可能让手机发热。")
                }
            }
            item(key = "model-auto-update") {
                SettingsSection(title = "自动更新") {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Update,
                        title = "自动更新模型",
                        subtitle = "有新版本时连着 Wi-Fi 自动更新",
                        checked = autoUpdate,
                        onCheckedChange = { enabled ->
                            autoUpdate = enabled
                            com.kingzcheung.xime.model.ModelAutoUpdater.setEnabled(context, enabled)
                        },
                    )
                }
            }
            item(key = "model-source") {
                DownloadRegionSection(
                    region = uiState.downloadRegion,
                    detectedChina = uiState.detectedChina,
                    onRegion = viewModel::setDownloadRegion,
                )
            }
            item(key = "model-import") {
                ModelImportSection(
                    isImporting = uiState.isImporting,
                    onImportFiles = viewModel::importDocuments,
                    onImportFolder = viewModel::importTree,
                )
            }
        }
    }
}

/** 删除模型的确认框：模型管理页和模型详情页共用。 */
@Composable
internal fun ModelDeleteDialog(
    item: ModelItemState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val note = remember(item.model.id) { modelDeleteNote(context, item.model.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除模型") },
        text = {
            Text("确定删除「${item.model.name}」？模型文件将被删除，此操作不可撤销。" +
                (note?.let { "\n$it" } ?: ""))
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("model-delete-confirm"), onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 补全词表跟着语言走：语言开着时，打这门语言发现词表不在就会在后台重新下载。
 * 删之前说清楚，免得以为没删掉；语言没开时不提示。
 */
internal fun modelDeleteNote(context: android.content.Context, modelId: String): String? {
    val letters = com.kingzcheung.xime.settings.LetterLanguage.entries
        .firstOrNull { it.wordsModel == modelId } ?: return null
    if (letters.language !in com.kingzcheung.xime.settings.LanguagePreferences.enabled(context)) return null
    val name = letters.language.displayName
    return "${name}还开着，打${name}时会重新下载。"
}

/** 下载线路：网络慢或无法访问时换线路。 */
@Composable
private fun DownloadRegionSection(
    region: DownloadRegion,
    detectedChina: Boolean,
    onRegion: (DownloadRegion) -> Unit,
) {
    SettingsSection(title = "下载线路") {
        Column(Modifier.selectableGroup().testTag("model-source")) {
            DownloadRegion.entries.forEachIndexed { index, option ->
                if (index > 0) ModelRowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("model-source:${option.name}")
                    .selectable(region == option, role = Role.RadioButton) { onRegion(option) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (option == DownloadRegion.AUTO)
                            "${option.label}(当前判断：${if (detectedChina) "中国大陆" else "海外"})" else option.label,
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(option.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    RadioButton(selected = region == option, onClick = null)
                }
            }
            ModelRowDivider()
            ModelSectionNote("下载失败会自动换线路。")
        }
    }
}

/** 从本机导入：把自己下好的文件导进来。 */
@Composable
private fun ModelImportSection(
    isImporting: Boolean,
    onImportFiles: (List<Uri>) -> Unit,
    onImportFolder: (Uri) -> Unit,
) {
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImportFiles(uris)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onImportFolder)
    }
    SettingsSection(title = "从本机导入") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选模型文件、压缩包或文件夹，自动识别是哪个模型。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { filesLauncher.launch(arrayOf("*/*")) }, enabled = !isImporting,
                    modifier = Modifier.testTag("model-import-files")) { Text("选择文件") }
                OutlinedButton(onClick = { folderLauncher.launch(null) }, enabled = !isImporting,
                    modifier = Modifier.testTag("model-import-folder")) { Text("选择文件夹") }
                if (isImporting) Text("正在导入…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 内置 OCR 与下载的模型用同一种行样式，只是没有删除按钮。 */
@Composable
private fun BuiltinOcrModelRow() {
    ModelRow(
        name = "拍照识别模型",
        tag = "文字识别",
        detail = "中文、英文 · 完整版内置，无需下载",
        modifier = Modifier.testTag("builtin-ocr-model"),
    )
}

@Composable
private fun ModelLocalRow(
    item: ModelItemState,
    autoUpdate: Boolean,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val updateNote = remember(item.model.id, item.installedVersion, autoUpdate) {
        com.kingzcheung.xime.model.ModelAutoUpdater.updateNote(context, item.model, item.installedVersion)
    }
    val detail = buildString {
        item.installedVersion?.let { append("版本 $it") }
        if (item.diskSize > 0) {
            if (isNotEmpty()) append(" · ")
            append(modelFormatSize(item.diskSize))
        }
        updateNote?.let { append(" · $it") }
    }
    ModelRow(
        name = item.model.name,
        tag = modelCategoryLabel(item.model.category),
        detail = detail,
    ) {
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除${item.model.name}",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ModelRow(
    name: String,
    tag: String,
    detail: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TagText(tag, MaterialTheme.colorScheme.primary)
            }
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

@Composable
private fun ModelSectionNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun ModelRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}


@Composable
private fun TagText(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
