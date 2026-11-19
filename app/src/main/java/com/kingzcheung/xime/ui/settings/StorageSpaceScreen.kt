package com.kingzcheung.xime.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Archive
import androidx.compose.material.icons.twotone.CloudDownload
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Memory
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.MemoryStats
import com.kingzcheung.xime.settings.StorageStats
import com.kingzcheung.xime.viewmodel.StorageSpaceViewModel

/**
 * 存储空间（设置 → 关于 → 存储空间）：分类统计数据目录占用并提供分类清除，
 * 低风险数据（缓存/日志/编译缓存/模型/剪贴板）就地清理，需谨慎操作的数据
 * （插件/方案词库）只统计并引导到对应管理页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSpaceScreen(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToPerformance: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: StorageSpaceViewModel = viewModel()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val releasingVoice by viewModel.releasingVoice.collectAsStateWithLifecycle()
    // 从性能与省电、模型管理回来时重新量一次。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshMemory() }
    val cleaningId by viewModel.cleaningId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var pendingClear by remember { mutableStateOf<StorageStats.Category?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            com.kingzcheung.xime.ui.i18n.UiToast.makeText(context, message.orEmpty(), android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    pendingClear?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("清理${target.title}") },
            text = { Text(clearConfirmText(target.id)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear(target.id, target.title)
                        pendingClear = null
                    }
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("存储空间") },
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
                )
            )
        }
    ) { paddingValues ->
        val list = categories
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState)
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                MemorySection(memory, releasingVoice, onRelease = viewModel::releaseVoice,
                    onNavigateToPerformance = onNavigateToPerformance)
            }
            item {
                SettingsSection(title = "数据目录占用") {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (list == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "正在扫描…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = Formatter.formatFileSize(context, StorageStats.totalSize(list)),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "仅统计应用数据目录，不含应用本体",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            memory?.apkBytes?.let { apk ->
                                Text(
                                    text = "安装包本身另占 ${Formatter.formatFileSize(context, apk)}(系统管理，删不掉)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (list != null) {
                item {
                    SettingsSection(title = "分类占用") {
                        list.forEachIndexed { index, category ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (category.navigable) Modifier.clickable {
                                        if (category.id == StorageStats.ID_MODELS) onNavigateToModels() else onNavigateToPlugins()
                                    } else Modifier)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = categoryIcon(category.id),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = category.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = category.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = Formatter.formatFileSize(context, category.sizeBytes),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (category.clearable) {
                                            TextButton(
                                                enabled = cleaningId == null,
                                                onClick = { pendingClear = category }
                                            ) {
                                                if (cleaningId == category.id) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Text("清理")
                                                }
                                            }
                                        }
                                    }
                                }
                                if (category.navigable) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 运行内存（用户 11-04「更多对内存的控制和指示」）：CyIME 每个进程占多少、本机还剩多少；
 * 语音模型在内存里时可以立刻释放。系统不足时会自己先收回后台的语音进程。
 */
@Composable
private fun MemorySection(
    memory: MemoryStats.Snapshot?,
    releasing: Boolean,
    onRelease: () -> Unit,
    onNavigateToPerformance: () -> Unit,
) {
    val context = LocalContext.current
    SettingsSection(title = "运行内存") {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (memory == null) {
                Text("正在读取…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                text = Formatter.formatFileSize(context, memory.totalPss),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            if (memory.availBytes != null && memory.totalBytes != null) Text(
                text = "本机可用 ${Formatter.formatFileSize(context, memory.availBytes)} / 共 ${Formatter.formatFileSize(context, memory.totalBytes)}" +
                    if (memory.lowMemory) "，内存不足" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (memory.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            memory.processes.forEach { process ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(process.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        if (process.pssBytes > 0) Formatter.formatFileSize(context, process.pssBytes) else "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (process.isVoice) TextButton(enabled = !releasing, onClick = onRelease) {
                        if (releasing) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Text("释放")
                    }
                }
            }
            if (memory.voice == null) Text(
                text = "语音模型没在内存里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToPerformance).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("性能与省电", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "语音模型空闲多久从内存卸掉",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun categoryIcon(id: String): ImageVector = when (id) {
    StorageStats.ID_MODELS -> Icons.TwoTone.Memory
    StorageStats.ID_SCHEMA_CACHE -> Icons.TwoTone.CloudDownload
    StorageStats.ID_SCHEMAS -> Icons.TwoTone.Description
    StorageStats.ID_MARKET_PACKAGES -> Icons.TwoTone.Archive
    StorageStats.ID_PLUGINS -> Icons.TwoTone.Extension
    StorageStats.ID_CLIPBOARD -> Icons.TwoTone.Widgets
    StorageStats.ID_LOGS -> Icons.TwoTone.Schedule
    StorageStats.ID_CACHE -> Icons.TwoTone.Folder
    else -> Icons.TwoTone.DataObject
}

private fun clearConfirmText(id: String): String = when (id) {
    StorageStats.ID_MODELS ->
        "将删除所有已下载的离线模型(手写 / 语音识别)，对应功能在重新下载模型前不可用。确定继续吗？"
    StorageStats.ID_SCHEMA_CACHE ->
        "将删除输入方案的编译缓存与编译日志，不会影响词库和自定义配置；下次启动输入法时会自动重新编译。确定继续吗？"
    StorageStats.ID_MARKET_PACKAGES ->
        "将删除方案市场中已下载的安装包(内置方案除外)。已安装的方案不受影响，但重新安装时需要重新下载。确定继续吗？"
    StorageStats.ID_CLIPBOARD ->
        "将清空本地剪贴板历史记录(保留固定内容)，且不可恢复。确定继续吗？"
    StorageStats.ID_LOGS ->
        "将删除历史运行日志(保留当日日志)。确定继续吗？"
    StorageStats.ID_CACHE ->
        "将删除图片与临时文件缓存，清理后首次加载会稍慢。确定继续吗？"
    else -> "确定清理吗？"
}
