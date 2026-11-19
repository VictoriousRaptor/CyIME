package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.HardwareKeyboardPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareKeyboardSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(HardwareKeyboardPreferences.read(context)) }
    fun save(key: String, value: Boolean) {
        HardwareKeyboardPreferences.set(context, key, value)
        options = HardwareKeyboardPreferences.read(context)
    }
    KeyboardSettingsScaffold("物理键盘", onBack, preview = true,
        example = com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Hardware) { modifier ->
        Column(modifier.settingsVerticalScroll()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("语言切换", style = MaterialTheme.typography.titleMedium)
            HardwareOption("Ctrl + 空格", "按已启用语言的顺序循环切换，不输入空格", options.ctrlSpace) { save("ctrl_space", it) }
            HardwareOption("单独轻按 Shift", "当前非英语语言与英语互切；半秒内松开，与其他按键组合时不切换", options.shiftTap) { save("shift_tap", it) }
            Text("工具快捷键", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.settingsSearchAnchor("工具快捷键", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
            HardwareOption("Ctrl / Alt 组合键", "关闭后下面这些组合键交给应用", options.toolShortcuts) { save("tool_shortcuts", it) }
            Text("Ctrl + .：切换中英文标点(英文模式固定半角)。\nCtrl + 回车：发送。\nAlt + V：打开或关闭剪贴板，方向键选择，回车粘贴并关闭，Esc 取消。\nAlt + H：开始或停止语音输入。", style = MaterialTheme.typography.bodyMedium)
            Text("候选翻页", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.settingsSearchAnchor("候选翻页", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
            Text("可多选。有待选内容时翻页，没有待选内容时输入符号；取消勾选后按普通符号处理。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CandidatePageOption("减号和等号", "− 上一页 / = 下一页", "page_minus_equals", options.pageMinusEquals) {
                save("page_minus_equals", it)
            }
            CandidatePageOption("中括号", "[ 上一页 / ] 下一页", "page_brackets", options.pageBrackets) {
                save("page_brackets", it)
            }
            CandidatePageOption("逗号和句号", ", 上一页 / . 下一页(含 < / >)", "page_comma_period", options.pageCommaPeriod) {
                save("page_comma_period", it)
            }
            Text("浮窗", style = MaterialTheme.typography.titleMedium)
            HardwareOption("候选跟随光标", "应用提供光标位置时生效；否则显示在底部", options.followCursor) { save("follow_cursor", it) }
            // 联想词只在「智能联想」开着时才有（PredictionManager）；关着时这项没有效果，置灰写明。
            val predictionOn = remember { com.kingzcheung.xime.settings.SettingsPreferences.isSmartPredictionEnabled(context) }
            HardwareOption("联想也显示在候选窗", "上屏后的联想词和打字候选显示在同一个浮窗里；关闭时联想显示在工具条上",
                options.associationInWindow,
                lockMessage = if (predictionOn) null else "「智能联想」关着，没有联想词") { save("association_in_window", it) }
            HardwareOption("自动避开输入区域", "有位置数据时避让输入框；大文档优先避让光标附近", options.avoidEditor) { save("avoid_editor", it) }
            HardwareOption("靠边停靠", "浮窗拖到屏幕左右边变成竖条，拖到底边变成候选长条；拖近时有蓝色轮廓", options.dockAtEdge) { save("dock_edge", it) }
            // 原「底边使用三星式通栏」（用户 11-01 改名「底部候选」）：只在停靠到底边时起作用（HardwareKeyboardToolbar 里
            // samsungBottomBar && bottomDocked），所以只在开着靠边停靠时显示；存的值不变。
            if (options.dockAtEdge) {
                HardwareOption("底部候选", "停靠到底边时，候选栏铺满屏幕底部整宽，九个候选带 1–9 编号",
                    options.bottomBar) { save("bottom_bar", it) }
                BottomCandidatesLegend(options.bottomBar)
            }
            HardwareOption("锁定浮窗位置", "工具条停在现在的位置，不能拖动；按钮照常能点", options.lockPosition) { save("lock_position", it) }
            Text(if (options.lockPosition) "长按语言键选择语言。" else "浮条任意位置均可拖动；长按语言键选择语言。",
                style = MaterialTheme.typography.bodyMedium)
            Text("游戏", style = MaterialTheme.typography.titleMedium)
            HardwareOption("游戏直通", "游戏类应用里不显示键盘，按键直接交给游戏；按应用声明的类别识别",
                options.gamePassthrough) { save("game_passthrough", it) }
        }
    }
}

/** 「底部候选」图例：屏幕底部，开着是整宽带编号的候选栏，关着是普通候选长条。 */
@Composable
private fun BottomCandidatesLegend(on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    SettingsLegend("bottom-candidates-legend") {
        Box(Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface,
            androidx.compose.foundation.shape.RoundedCornerShape(4.dp))) {
            Text("屏幕底部", Modifier.align(Alignment.TopStart).padding(6.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val words = listOf("你好", "拟好", "你号", "泥好", "倪浩")
            Row(
                (if (on) Modifier.fillMaxWidth() else Modifier.padding(bottom = 6.dp)).align(Alignment.BottomCenter)
                    .background(primary.copy(alpha = 0.22f), androidx.compose.foundation.shape.RoundedCornerShape(if (on) 0.dp else 6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                words.forEachIndexed { index, word ->
                    Text(if (on) "${index + 1} $word" else word, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        Text(if (on) "贴住屏幕底边，整宽，按数字键选词" else "普通的候选长条",
            style = MaterialTheme.typography.labelSmall, color = primary)
    }
}

@Composable
private fun HardwareOption(title: String, detail: String, checked: Boolean,
                           /** 当前用不上时写原因：整行置灰、点不动，原因写在说明下面；存的值不变。 */
                           lockMessage: String? = null, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.settingsSearchAnchor(title, bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth()
            .toggleable(checked, enabled = lockMessage == null, role = Role.Switch, onValueChange = onChange)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp).alpha(if (lockMessage == null) 1f else 0.6f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lockMessage?.let { SettingsLockNote(it) }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = lockMessage == null)
        }
    }
}

@Composable
private fun CandidatePageOption(title: String, detail: String, key: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.settingsSearchAnchor(title, bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth().testTag("hardware-$key")
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}
