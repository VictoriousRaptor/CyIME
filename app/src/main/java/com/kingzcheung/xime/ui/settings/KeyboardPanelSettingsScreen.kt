package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.rememberFloatingGeometryLocked
import com.kingzcheung.xime.ui.keyboard.rememberInlineQuickActionEditing

internal fun keyboardPanelTitle(panel: KeyboardPanelLaunch.Panel) = when (panel) {
    KeyboardPanelLaunch.Panel.RESIZE -> "键盘尺寸与位置"
    KeyboardPanelLaunch.Panel.ARRANGE -> "工具栏与菜单排列"
}

@Composable
internal fun KeyboardPanelSettingsContent(panel: KeyboardPanelLaunch.Panel, onBack: () -> Unit) {
    KeyboardSettingsScaffold(keyboardPanelTitle(panel), onBack, preview = true, editPanel = panel) { modifier ->
        if (panel == KeyboardPanelLaunch.Panel.RESIZE) {
            val context = LocalContext.current
            SettingsToggleItem(Icons.TwoTone.Lock, "锁定悬浮键盘位置和大小",
                "锁定后不能移动或缩放，长按底部横条解锁",
                checked = rememberFloatingGeometryLocked(),
                onCheckedChange = { SettingsPreferences.setFloatingGeometryLocked(context, it) })
        }
        if (panel == KeyboardPanelLaunch.Panel.ARRANGE) {
            val context = LocalContext.current
            SettingsToggleItem(Icons.TwoTone.Edit, "在菜单里直接编辑",
                "在展开的菜单里直接调整按钮顺序和显示",
                checked = rememberInlineQuickActionEditing(),
                onCheckedChange = { SettingsPreferences.setInlineQuickActionEditing(context, it) })
        }
        Text(
            when (panel) {
                KeyboardPanelLaunch.Panel.RESIZE -> "点「调整尺寸」后拖动边框，调好点「确认」。"
                KeyboardPanelLaunch.Panel.ARRANGE -> "点「编辑排列」后拖动按钮，点勾号保存。"
            },
            modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
