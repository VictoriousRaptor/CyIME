package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
                "长按底部短横条 1 秒锁定，边框会走一圈进度。锁定后不能移动、缩放，底部显示小锁，长按 0.5 秒解锁。",
                checked = rememberFloatingGeometryLocked(),
                onCheckedChange = { SettingsPreferences.setFloatingGeometryLocked(context, it) })
        }
        if (panel == KeyboardPanelLaunch.Panel.ARRANGE) {
            val context = LocalContext.current
            SettingsToggleItem(Icons.TwoTone.Edit, "在菜单里直接编辑",
                "展开菜单后右上角出现编辑按钮：长按拖动调整工具栏和菜单的顺序，点角上的加减号隐藏或加回，点勾号保存。",
                checked = rememberInlineQuickActionEditing(),
                onCheckedChange = { SettingsPreferences.setInlineQuickActionEditing(context, it) })
        }
        Text(
            when (panel) {
                KeyboardPanelLaunch.Panel.RESIZE -> "点「调整尺寸」后，拖动边框或移动手柄。\n调好大小和透明度，点「确认」查看效果。"
                KeyboardPanelLaunch.Panel.ARRANGE -> "点「编辑排列」后，可以拖动工具栏和菜单里的按钮。点勾号回到键盘预览。"
            },
            modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
