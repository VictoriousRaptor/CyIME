package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.rememberSwipeHintsEnabled

/** 两处入口共用原有显示开关；与输入方式、角标方向独立，保留用户已保存的选择。 */
@Composable
internal fun CornerHintVisibilitySettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hints = rememberSwipeHintsEnabled()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @Composable fun toggle(title: String, description: String, checked: Boolean, save: (Boolean) -> Unit) {
            Row(Modifier.settingsSearchAnchor(title).fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = save),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = null, colors = settingsSwitchColors())
            }
        }
        toggle("上方角标提示", "显示按键右上角的小符号", hints.up) {
            SettingsPreferences.setSwipeUpHintsEnabled(context, it)
        }
        toggle("下方角标提示", "显示按键左下角的小符号", hints.down) {
            SettingsPreferences.setSwipeDownHintsEnabled(context, it)
        }
        // 11-11 曾在开着 26 键滑行时不画下角标；现在滑行时长按弹出上下两个角标（withGlideCornerMenu），下角标照这个开关显示。
    }
}
