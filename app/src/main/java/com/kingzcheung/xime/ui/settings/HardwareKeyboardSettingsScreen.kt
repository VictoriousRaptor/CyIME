package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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
            HardwareOption("Ctrl + 空格", "依次切换已启用的语言", options.ctrlSpace) { save("ctrl_space", it) }
            HardwareOption("单独轻按 Shift", "在当前语言和英文之间切换", options.shiftTap) { save("shift_tap", it) }
            Text("工具快捷键", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.settingsSearchAnchor("工具快捷键", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
            HardwareOption("Ctrl / Alt 组合键", "关闭后下面这些组合键交给应用", options.toolShortcuts) { save("tool_shortcuts", it) }
            Text("Ctrl + .：切换中英文标点(英文模式固定半角)。\nCtrl + 回车：发送。\nAlt + V：打开或关闭剪贴板，方向键选择，回车粘贴并关闭，Esc 取消。\nAlt + H：开始或停止语音输入。\nSYM 键（有的全键盘手机）：打开或关闭符号页。", style = MaterialTheme.typography.bodyMedium)
            Text("候选翻页", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.settingsSearchAnchor("候选翻页", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
            Text("有候选时用这些键翻页，可多选",
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
            // 固定在某一边时，靠边停靠、锁定这两项用不上：置灰写明，存的值不变（用户 11-20 转来的反馈）。
            // 跟随光标、避开输入区域照样管候选卡：固定的只是工具条，候选跟到正在打字的那一行旁边（用户 11-22）。
            val fixedNote = options.fixedEdge?.let { "浮窗固定在${it.label}，这项不起作用" }
            FloatingBarEdgeOption(options.fixedEdge) {
                HardwareKeyboardPreferences.setFixedEdge(context, it)
                options = HardwareKeyboardPreferences.read(context)
            }
            HardwareOption("候选跟随光标", "候选窗显示在光标旁边", options.followCursor) { save("follow_cursor", it) }
            // 联想词和英文直接输入的补全都只在「智能联想」开着时才有（11-23 起补全也归它管）；「英文出短语」、邮箱后缀照样按这项放，所以不置灰（#526）。
            HardwareOption("联想也显示在候选窗", "联想词和英文补全也显示在候选浮窗里",
                options.associationInWindow) { save("association_in_window", it) }
            HardwareOption("自动避开输入区域", "浮窗不挡住正在输入的地方", options.avoidEditor) { save("avoid_editor", it) }
            HardwareOption("靠边停靠", "浮窗拖到屏幕边缘时贴边停靠", options.dockAtEdge,
                lockMessage = fixedNote) { save("dock_edge", it) }
            // 原「底边使用三星式通栏」（用户 11-01 改名「底部候选」）：只在停靠到底边时起作用（HardwareKeyboardToolbar 里
            // samsungBottomBar && bottomDocked），所以只在开着靠边停靠时显示；存的值不变。
            if (options.fixedEdge == null && options.dockAtEdge ||
                options.fixedEdge == com.kingzcheung.xime.settings.HardwareToolbarEdge.BOTTOM) {
                HardwareOption("底部候选", "停靠底边时候选栏铺满屏幕宽度",
                    options.bottomBar) { save("bottom_bar", it) }
                BottomCandidatesLegend(options.bottomBar)
            }
            HardwareOption("锁定浮窗位置", "工具条固定在当前位置", options.lockPosition,
                lockMessage = fixedNote) { save("lock_position", it) }
            Text(if (options.lockPosition || options.fixedEdge != null) "长按语言键选择语言。" else "拖动浮条可移动位置；长按语言键选择语言。",
                style = MaterialTheme.typography.bodyMedium)
            Text("游戏", style = MaterialTheme.typography.titleMedium)
            HardwareOption("游戏直通", "在游戏里不弹键盘，按键直接给游戏",
                options.gamePassthrough) { save("game_passthrough", it) }
            PassthroughAppsSection(options.passthroughApps) { app, on ->
                HardwareKeyboardPreferences.setPassthroughApp(context, app, on)
                options = HardwareKeyboardPreferences.read(context)
            }
        }
    }
}

/**
 * 浮窗位置：自由拖动（默认，和原来一样），或固定在顶部、底部、左侧、右侧。
 * 固定后工具条贴那一边、不能拖，候选卡贴着工具条出现，不跟光标、不躲输入框。
 */
@Composable
private fun FloatingBarEdgeOption(selected: com.kingzcheung.xime.settings.HardwareToolbarEdge?,
                                  onSelect: (com.kingzcheung.xime.settings.HardwareToolbarEdge?) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.settingsSearchAnchor("浮窗位置", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth()
            .padding(16.dp)) {
            Text("浮窗位置", style = MaterialTheme.typography.titleSmall)
            Text("固定后工具条停在这一边，不能拖动",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val choices = listOf<Pair<com.kingzcheung.xime.settings.HardwareToolbarEdge?, String>>(null to "自由拖动") +
                com.kingzcheung.xime.settings.HardwareToolbarEdge.entries.map { it to "固定在${it.label}" }
            choices.forEach { (edge, label) ->
                Row(Modifier.fillMaxWidth().testTag("hardware-fixed-edge:${edge?.key ?: "free"}")
                    .selectable(selected == edge, role = Role.RadioButton) { onSelect(edge) }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == edge, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
            }
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
        Text(if (on) "贴住屏幕底边，按数字键选词" else "普通的候选长条",
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

/**
 * 按应用直通（#265）：游戏直通认不出的应用（远程桌面、没标成游戏的模拟器）单独打开。
 * 列出最近用实体键盘打字的那个应用和已经打开直通的应用，每个一行开关；不读手机上装了哪些应用。
 */
@Composable
private fun PassthroughAppsSection(selected: Set<String>, onChange: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    // 进这一页时记下的最近应用；开关以后行不挪位置。
    val last = remember { HardwareKeyboardPreferences.lastApp(context) }
    val apps = remember(selected) { (listOfNotNull(last) + selected.sorted()).distinct() }
    Text("按应用直通", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.settingsSearchAnchor("按应用直通", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
    Text("打开的应用里不弹键盘，按键直接给应用。先在那个应用里用实体键盘打几个字，再回到这里打开。",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    apps.forEach { app ->
        val checked = app in selected
        val label = remember(app) { passthroughAppLabel(context, app) }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().testTag("hardware-passthrough-app:$app")
                .toggleable(checked, role = Role.Switch) { onChange(app, it) }
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    androidx.compose.material3.Text(label, style = MaterialTheme.typography.titleSmall)
                    androidx.compose.material3.Text(app, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = null)
            }
        }
    }
}

/** 应用名；查不到（看不到这个应用、已卸载）时用包名。 */
private fun passthroughAppLabel(context: android.content.Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

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
