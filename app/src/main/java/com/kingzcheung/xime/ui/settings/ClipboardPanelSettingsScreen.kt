package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.PhotoLibrary
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.clipboard.ClipboardGroupStore
import com.kingzcheung.xime.clipboard.ClipboardImages
import com.kingzcheung.xime.clipboard.ImageAccessActivity
import com.kingzcheung.xime.settings.PerformanceCosts
import com.kingzcheung.xime.clipboard.CLIPBOARD_IMAGE_HEIGHT_KEY
import com.kingzcheung.xime.clipboard.CLIPBOARD_SHOW_IN_BAR_KEY
import com.kingzcheung.xime.clipboard.clipboardShowsInCandidateBar
import com.kingzcheung.xime.clipboard.ClipboardImageHeight
import com.kingzcheung.xime.clipboard.clipboardImageHeight
import com.kingzcheung.xime.clipboard.CLIPBOARD_RECENT_IMAGE_COUNT_KEY
import com.kingzcheung.xime.clipboard.ClipboardRecentImageCounts
import com.kingzcheung.xime.clipboard.clipboardRecentImageCount
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_MAX_LINES_KEY
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_CLOSE_AFTER_PASTE_KEY
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_PASTE_TO_TOP_KEY
import com.kingzcheung.xime.ui.menubar.clipboardCardMaxLines
import com.kingzcheung.xime.ui.menubar.clipboardClosesAfterPaste

/**
 * 剪贴板面板 (用户 11-03：剪贴板的几个小菜单要有一个实体的设置页)：⋯ 菜单里的两个开关 (最近截图、
 * 上屏后自动关闭剪贴板)、分组，和原来在「剪贴板与同步」里的卡片行数、历史记录放在一页。
 * 和面板菜单是同一个值，哪边改都一样。多选、新增、添加图片、拍照识别、同步、清空未固定是对眼前记录的操作，留在菜单里。
 *
 * 用户 11-15：这页改名「剪贴板」，从设置首页直接进；验证码、敏感内容、导出导入从原「剪贴板与同步」挪来，
 * 同步本身是插件，这里只放一个跳到「剪贴板同步」的入口。设置项名字和值都没变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardPanelSettingsContent(onBack: () -> Unit, onNavigateToGroups: () -> Unit, onNavigateToSync: () -> Unit = {}) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).settingsVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClipboardPanelDisplaySettings(onNavigateToGroups)
            ClipboardPasteSettings()
            ClipboardHistoryLimitsSetting()
            // 下面几项原来在「剪贴板与同步」页，原样挪来。
            VerificationCodeSettings()
            ClipboardSensitiveSetting()
            SettingsSection(title = "同步", content = {
                SettingsItem(
                    icon = Icons.TwoTone.Sync,
                    title = "剪贴板同步",
                    subtitle = "扫码连接电脑，和其他手机、平板互相同步",
                    onClick = onNavigateToSync,
                    showArrow = true,
                )
            })
            ClipboardExportSetting()
            ClipboardImportSetting()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ClipboardPanelDisplaySettings(onNavigateToGroups: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE) }
    var lines by remember { mutableStateOf(clipboardCardMaxLines(prefs.getInt(CLIPBOARD_CARD_MAX_LINES_KEY, 4))) }
    var textPercent by remember { mutableStateOf(com.kingzcheung.xime.ui.menubar.clipboardCardTextPercent(
        prefs.getInt(com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_TEXT_SIZE_KEY, 100))) }
    var imageHeight by remember { mutableStateOf(clipboardImageHeight(prefs.getString(CLIPBOARD_IMAGE_HEIGHT_KEY, null))) }
    var imageCount by remember { mutableStateOf(clipboardRecentImageCount(prefs.getInt(CLIPBOARD_RECENT_IMAGE_COUNT_KEY, 4))) }
    val groups by remember(context) { ClipboardGroupStore.getInstance(context) }.state.collectAsState()
    val images = remember(context) { ClipboardImages.getInstance(context) }
    // Same value as 启用/关闭最近截图 in the clipboard's ⋯ menu; the permission answer comes back on resume.
    val screenshotsOn by images.photoAccess.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { images.refresh() }
    SettingsSection(title = "显示", content = {
        SettingsItem(
            icon = Icons.TwoTone.Folder,
            title = "剪贴板分组",
            subtitle = "分类和自建分组",
            onClick = onNavigateToGroups,
            showArrow = true,
            status = groups.groups.size.takeIf { it > 0 }?.let { "$it 个" },
        )
        Column(Modifier.settingsSearchAnchor("卡片显示行数").fillMaxWidth().padding(16.dp)) {
            Text("卡片显示行数", style = MaterialTheme.typography.titleSmall)
            Text("每条记录最多显示几行文字", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_LINE_CHOICES.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("clipboard-card-lines:$option")
                    .selectable(lines == option, role = Role.RadioButton) {
                        lines = option
                        prefs.edit().putInt(CLIPBOARD_CARD_MAX_LINES_KEY, option).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = lines == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("$option 行", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        // #497：给老人用时卡片的字太小。只放大卡片里的字，候选、按键的字不变。
        Column(Modifier.settingsSearchAnchor("卡片文字大小").fillMaxWidth().padding(16.dp)) {
            Text("卡片文字大小", style = MaterialTheme.typography.titleSmall)
            Text("只改剪贴板记录里的字，候选和按键不变", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_TEXT_SIZE_CHOICES.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("clipboard-card-text:$option")
                    .selectable(textPercent == option, role = Role.RadioButton) {
                        textPercent = option
                        prefs.edit().putInt(com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_TEXT_SIZE_KEY, option).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = textPercent == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(if (option == 100) "标准" else "$option%", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        // 用户 11-09：图片卡片太矮。固定的图片照旧只露一条。
        Column(Modifier.settingsSearchAnchor("图片高度").fillMaxWidth().padding(16.dp)) {
            Text("图片高度", style = MaterialTheme.typography.titleSmall)
            Text("剪贴板里图片的显示高度", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ClipboardImageHeight.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("clipboard-image-height:${option.key}")
                    .selectable(imageHeight == option, role = Role.RadioButton) {
                        imageHeight = option
                        prefs.edit().putString(CLIPBOARD_IMAGE_HEIGHT_KEY, option.key).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = imageHeight == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.titleSmall)
                        Text(option.caption, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 用户 11-17：一打开剪贴板全是图片。「全部」只放最新几张，其余在「图片」里。
        Column(Modifier.settingsSearchAnchor("显示几张图片").fillMaxWidth().padding(16.dp)) {
            Text("显示几张图片", style = MaterialTheme.typography.titleSmall)
            Text("「全部」里显示几张，其余在「图片」里", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ClipboardRecentImageCounts.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("clipboard-image-count:$option")
                    .selectable(imageCount == option, role = Role.RadioButton) {
                        imageCount = option
                        prefs.edit().putInt(CLIPBOARD_RECENT_IMAGE_COUNT_KEY, option).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = imageCount == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(if (option == 0) "不限" else "$option 张", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        SettingsToggleItem(
            icon = Icons.TwoTone.PhotoLibrary,
            title = "最近截图",
            subtitle = "在剪贴板里显示最近的截图",
            checked = screenshotsOn,
            onCheckedChange = { on ->
                if (!on) images.enableRecentScreenshots(false)
                else if (images.hasPhotoAccess()) images.enableRecentScreenshots(true)
                // Same permission screen as the menu; it turns the switch on when access is granted.
                else context.startActivity(Intent(context, ImageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            costItem = PerformanceCosts.Item.RECENT_SCREENSHOTS,
        )
        // 用户 11-14：不只截图，相册最近几张也要。默认关：别人用键盘时不突然露出自己的照片。
        var photosToo by remember { mutableStateOf(images.recentPhotosWanted()) }
        SettingsToggleItem(
            icon = Icons.TwoTone.PhotoLibrary,
            title = "也显示相册照片",
            subtitle = "也显示相册里最新的照片",
            checked = photosToo && screenshotsOn,
            onCheckedChange = { on ->
                photosToo = on
                images.includeRecentPhotos(on)
            },
            lockMessage = if (screenshotsOn) null else "先打开「最近截图」",
        )
    })
}

@Composable
private fun ClipboardPasteSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE) }
    // Same switch as the clipboard's own ⋯ menu (same preference).
    var closeAfterPaste by remember { mutableStateOf(clipboardClosesAfterPaste(prefs)) }
    var showInBar by remember { mutableStateOf(clipboardShowsInCandidateBar(context)) }
    var pasteToTop by remember { mutableStateOf(prefs.getBoolean(CLIPBOARD_PASTE_TO_TOP_KEY, false)) }
    SettingsSection(title = "粘贴", content = {
        // 用户 11-20 转来的反馈：最新复制的内容要能选不显示。默认开，和以前一样。
        SettingsToggleItem(
            icon = Icons.TwoTone.ContentPaste,
            title = "候选栏显示刚复制的内容",
            subtitle = "复制后打开键盘，候选栏先给出刚复制的内容",
            checked = showInBar,
            onCheckedChange = {
                showInBar = it
                prefs.edit().putBoolean(CLIPBOARD_SHOW_IN_BAR_KEY, it).apply()
            },
        )
        SettingsToggleItem(
            icon = Icons.TwoTone.ContentPaste,
            title = "上屏后自动关闭剪贴板",
            subtitle = "关掉后可以连续粘贴多条",
            checked = closeAfterPaste,
            onCheckedChange = {
                closeAfterPaste = it
                prefs.edit().putBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, it).apply()
            },
        )
        // #299：点选上屏后这条排回最前。默认关，顺序照复制时间。
        SettingsToggleItem(
            icon = Icons.TwoTone.ContentPaste,
            title = "粘贴后移到最前",
            subtitle = "点一条记录上屏后，这条排到最前面",
            checked = pasteToTop,
            onCheckedChange = {
                pasteToTop = it
                prefs.edit().putBoolean(CLIPBOARD_PASTE_TO_TOP_KEY, it).apply()
            },
        )
    })
}
