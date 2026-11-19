package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.theme.*
import com.kingzcheung.xime.util.FileLogger

internal fun changeAppearance(context: Context, onChanged: () -> Unit, change: () -> Unit) {
    try { change(); onChanged() } catch (e: RuntimeException) {
        FileLogger.e("Appearance", "Appearance change failed", e)
        com.kingzcheung.xime.ui.i18n.UiToast.makeText(context, "图标切换失败，未保存此次选择，请重试", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconSettingsContent(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = {
        TopAppBar(title = { Text("图标设置") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).settingsVerticalScroll().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 11-01：原「同步图标与视觉样式」「桌面图标跟随主题色」两个开关合成三选一，存的值不变（DesktopIconChoice）。
            val choice = DesktopIconChoice.of(IconAppearance.followTheme, IconAppearance.linked)
            SettingsSection(title = "桌面图标") {
                Column(Modifier.padding(16.dp).settingsSearchAnchor("桌面图标"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().choiceCardRowHeight(124.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopIconChoice.entries.forEach { option ->
                            IllustratedChoiceCard(option.label, choice == option,
                                onClick = { changeAppearance(context, onChanged) { IconAppearance.setChoice(context, option) } },
                                modifier = Modifier.weight(1f), tag = "desktop-icon:${option.name}", titleMaxLines = 2) {
                                when (option) {
                                    DesktopIconChoice.THEME -> ThemeLauncherIconPreview(
                                        com.kingzcheung.xime.settings.SettingsPreferences.getKeyboardTheme(context), Modifier.size(52.dp))
                                    DesktopIconChoice.VISUAL_STYLE -> LauncherIconPreview(VisualStyles.current, IconAppearance.framed, Modifier.size(52.dp))
                                    DesktopIconChoice.CUSTOM -> LauncherIconPreview(IconAppearance.selected, IconAppearance.framed, Modifier.size(52.dp))
                                }
                            }
                        }
                    }
                    Text(when (choice) {
                        DesktopIconChoice.THEME -> "换键盘主题时，桌面图标换成同色的图标。"
                        DesktopIconChoice.VISUAL_STYLE -> "桌面图标和键盘的视觉样式一起换；视觉样式在「主题与外观」里选。"
                        DesktopIconChoice.CUSTOM -> "在下面挑图标，键盘外观不跟着变。"
                    }, style = MaterialTheme.typography.bodySmall)
                    Text("桌面可能要过一会儿才刷新。三星等桌面每换一次图标会先移除再添加，图标可能跑到应用列表最后，要重新拖回原位。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 字幕的桌面图标开关在字幕插件页（和字幕的其他设置在一起），这里只指路，不放第二个开关；没装字幕插件不提。
                    if (com.kingzcheung.xime.ui.caption.CaptionPlugin.isActive(androidx.compose.ui.platform.LocalContext.current))
                        SettingsNote("字幕的桌面图标在「字幕 › 桌面显示“字幕”图标」。",
                            jumps = listOf("字幕 › 桌面显示“字幕”图标"))
                }
            }
            // 跟随主题色时是固定的带底板图标，底板和样式不起作用，那两组就不显示。
            if (choice != DesktopIconChoice.THEME) SettingsSection(title = "桌面图标底板") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(true, false).forEach { framed ->
                            val selected = IconAppearance.framed == framed
                            val shape = RoundedCornerShape(12.dp)
                            Column(Modifier.weight(1f).testTag("icon-frame-$framed")
                                .semantics { this.selected = selected }
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                                .border(if (selected) 2.dp else 1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 1f else .25f), shape)
                                .clickable { changeAppearance(context, onChanged) { IconAppearance.setFramed(context, framed) } }
                                .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LauncherIconPreview(IconAppearance.effective, framed, Modifier.size(64.dp))
                                Text(if (framed) "带底板" else "无底板", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Text("带底板和别的桌面图标一样大、外框统一；无底板图案更突出。有的桌面仍会自动加底板。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (choice == DesktopIconChoice.CUSTOM) SettingsSection(title = "图标样式") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VisualStyle.entries.filter { it != VisualStyle.ORIGINAL }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { style ->
                                val selected = IconAppearance.effective == style
                                val palette = MaterialTheme.colorScheme
                                val shape = RoundedCornerShape(12.dp)
                                Column(Modifier.weight(1f).testTag("icon-style-${style.id}").semantics { this.selected = selected }
                                    .background(palette.surfaceContainerHigh, shape)
                                    .border(if (selected) 2.dp else 1.dp, palette.primary.copy(alpha = if (selected) 1f else .25f), shape)
                                    .clickable { changeAppearance(context, onChanged) { IconAppearance.setIconStyle(context, style) } }
                                    .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    LauncherIconPreview(style, IconAppearance.framed, Modifier.size(72.dp))
                                    Text(style.title + if (selected) " · 已选" else "", color = palette.onSurface,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { changeAppearance(context, onChanged) { IconAppearance.setIconStyle(context, VisualStyle.ORIGINAL) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("icon-style-original")) {
                        Text(if (IconAppearance.effective == VisualStyle.ORIGINAL) "原有图标 · 已选" else "恢复原有图标")
                    }
                }
            }
        }
    }
}
