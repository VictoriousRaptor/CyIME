package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.rememberSwipeHintsEnabled

/**
 * 两处入口共用原有显示开关；与输入方式、角标方向独立，保留用户已保存的选择。
 * 「上下滑输入符号」关着时符号角标滑不出来，键上也不画（KeyButton 的 upCornerOff），这两项变灰不能点，
 * 存的值不动，功能打开后照原来的显示（用户 11-16）。
 */
@Composable
internal fun CornerHintVisibilitySettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hints = rememberSwipeHintsEnabled()
    val symbolsOn = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().cornerSymbolInput
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @Composable fun toggle(title: String, description: String, checked: Boolean, save: (Boolean) -> Unit) {
            Row(Modifier.settingsSearchAnchor(title).fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = checked, enabled = symbolsOn, role = Role.Switch, onValueChange = save),
                verticalAlignment = Alignment.CenterVertically) {
                val alpha = if (symbolsOn) 1f else 0.38f
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    Text(if (symbolsOn) description else "「上下滑输入符号」关着",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                }
                Switch(checked = checked, onCheckedChange = null, enabled = symbolsOn, colors = settingsSwitchColors())
            }
        }
        toggle("上方角标提示", "平时显示按键右上角的小符号；滑动时照样在键内显示要输入的符号", hints.up) {
            SettingsPreferences.setSwipeUpHintsEnabled(context, it)
        }
        toggle("下方角标提示", "平时显示按键左下角的小符号；滑动时照样在键内显示要输入的符号", hints.down) {
            SettingsPreferences.setSwipeDownHintsEnabled(context, it)
        }
        // 11-11 曾在开着 26 键滑行时不画下角标；现在滑行时长按弹出上下两个角标（withGlideCornerMenu），下角标照这个开关显示。
    }
}
