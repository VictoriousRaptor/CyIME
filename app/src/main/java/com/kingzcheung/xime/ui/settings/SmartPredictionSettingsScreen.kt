package com.kingzcheung.xime.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Backspace
import androidx.compose.material.icons.automirrored.twotone.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.twotone.FiberManualRecord
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.kingzcheung.xime.ui.i18n.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.SmartPredictionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPredictionSettingsContent(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SmartPredictionSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportLearningData) }
    val importFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImport = uri }
    pendingImport?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入学习文件或文字") },
            text = { Text("新增：保留已学的，加入文件里的内容。\n替换：用文件内容替换已学的。\n也可以选普通文字文件，当作打过一遍来学。") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pendingImport = null; viewModel.importLearningData(uri, merge = false) }) { Text("替换") }
                    Button(onClick = { pendingImport = null; viewModel.importLearningData(uri, merge = true) }) { Text("新增") }
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            viewModel.showToast(message)
            viewModel.clearToast()
        }
    }

    KeyboardSettingsScaffold("智能联想", onBack, preview = pendingImport == null,
        example = if (uiState.isEnabled) com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Associations
            else com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Keyboard) { modifier ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = modifier.settingsGutterScroll(listState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding(top = 8.dp, bottom = 8.dp)
        ) {
            item {
                SettingsSection(title = "功能开关", content = {
                    Row(
                        modifier = Modifier.settingsSearchAnchor("启用智能联想")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用智能联想",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "上屏后接着给出下一个词；英文直接输入时补全单词",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.PREDICTION)
                        }
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = uiState.isEnabled,
                                onCheckedChange = { viewModel.setEnabled(it) },
                                colors = SwitchDefaults.colors()
                            )
                        }
                    }
                })
            }

            if (uiState.isEnabled) {
                item {
                    var spaceCommitEnabled by remember {
                        mutableStateOf(SettingsPreferences.isSpaceCommitAssociationEnabled(context))
                    }
                    var singleMode by remember {
                        mutableStateOf(SettingsPreferences.isSingleAssociationMode(context))
                    }
                    Column { SettingsSection(title = "联想行为", content = {
                        SettingsToggleItem(
                            icon = Icons.TwoTone.AutoAwesome,
                            title = "空格上屏联想候选",
                            subtitle = "有联想词时，按空格上屏第一个",
                            checked = spaceCommitEnabled,
                            onCheckedChange = {
                                spaceCommitEnabled = it
                                SettingsPreferences.setSpaceCommitAssociationEnabled(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "连续联想",
                            subtitle = "选了联想词后接着出下一批，接不上时自动停",
                            isSelected = !singleMode,
                            onClick = {
                                singleMode = false
                                SettingsPreferences.setAssociationSingleMode(context, false)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "单次联想",
                            subtitle = "选一个联想词就停，下次打字再出",
                            isSelected = singleMode,
                            onClick = {
                                singleMode = true
                                SettingsPreferences.setAssociationSingleMode(context, true)
                            }
                        )
                    })
                    // 「退格直接删字」「删字后给同音字」11-01 搬到 按键与手势 › 删除键，这里只指个路，不放第二个开关。
                    SettingsNote("退格直接删字、删字后给同音字在「按键与手势 › 删除键」。", jumps = listOf("按键与手势 › 删除键"))
                    }
                }
            }

            item {
                // 与键盘菜单「无痕」是同一个开关，两边改动立即互相反映。
                val toggles = com.kingzcheung.xime.ui.keyboard.rememberQuickToggleStates()
                SettingsSection(title = "无痕", content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.VisibilityOff,
                        title = "无痕模式",
                        subtitle = "不学习打过的词，不记录剪贴板",
                        checked = toggles.incognito,
                        onCheckedChange = { SettingsPreferences.setIncognito(context, it) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 删掉刚上屏的词不学习（B站反馈 10-31，默认关）；无痕开着时本来就什么都不学，置灰。
                    var unlearnRejected by remember { mutableStateOf(SettingsPreferences.isUnlearnRejectedCommit(context)) }
                    SettingsToggleItem(
                        icon = Icons.AutoMirrored.TwoTone.Backspace,
                        title = "删掉刚打的词不学习",
                        subtitle = "刚上屏就删掉的词不记进词库",
                        checked = unlearnRejected,
                        onCheckedChange = {
                            unlearnRejected = it
                            SettingsPreferences.setUnlearnRejectedCommit(context, it)
                        },
                        lockMessage = if (toggles.incognito) "无痕模式开着时不需要" else null,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 锁定词序（专业打字手，11-18，默认关）：候选顺序固定，每个位置都能盲打。
                    var lockWordOrder by remember { mutableStateOf(SettingsPreferences.isLockWordOrder(context)) }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Lock,
                        title = "锁定词序",
                        subtitle = "候选顺序固定，不随使用改变，也不记新词",
                        checked = lockWordOrder,
                        onCheckedChange = {
                            lockWordOrder = it
                            SettingsPreferences.setLockWordOrder(context, it)
                        },
                        lockMessage = if (toggles.incognito) "无痕模式开着时不需要" else null,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 自造词角标（用户 11-19 要开关，默认显示，同以前）：关掉只是候选上不画角标，照常学习。角标 11-25 从小铅笔改成实心小圆点（cc17f3c0f），这里的图标和说明跟着改。
                    var showLearnedMark by remember { mutableStateOf(SettingsPreferences.showLearnedMark(context)) }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.FiberManualRecord,
                        title = "显示自造词角标",
                        subtitle = "学到的词在候选右上角显示小圆点",
                        checked = showLearnedMark,
                        onCheckedChange = {
                            showLearnedMark = it
                            SettingsPreferences.setShowLearnedMark(context, it)
                        },
                    )
                })
            }

            item {
                LearningDataControls(
                    unique = uiState.cacheSize, observations = uiState.observations,
                    profileSize = uiState.profileSize, busy = uiState.isSaving,
                    status = uiState.learningStatus,
                    onSave = viewModel::saveUserData, onRefresh = viewModel::refreshCacheSize,
                    onExport = { exportFile.launch("CyIME-personal-learning.json") },
                    // 不按类型筛：md、srt 等在很多手机上没有 text/plain 类型，选了再看内容是不是文字（用户 11-15：md 都选不了）
                    onImport = { importFile.launch(arrayOf("*/*")) }
                )
            }
        }
    }
}

@Composable
private fun AssociationModeRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.settingsSearchAnchor(title)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}

@Composable
internal fun LearningDataControls(
    unique: Int, observations: Long, profileSize: Int, busy: Boolean, status: String,
    onSave: () -> Unit, onRefresh: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
) {
    SettingsSection(title = "用户学习数据") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本机学习：$unique 种组合 · 累计 $observations 次")
            Text("个人语料方案：$profileSize 条联想", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("打字时自动学习，可导入导出。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Stack actions so large fonts and narrow keyboards cannot clip labels.
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出文件") }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入学习文件") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
