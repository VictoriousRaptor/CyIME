package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode

/** 一条技巧：[how] 怎么按，[what] 会怎样；[jumps] 是 [what] 里可点的「」指路（见 [settingsJumpText]）。 */
internal data class KeyTip(val how: String, val what: String, val jumps: List<String> = emptyList())

/** 一组技巧，对应「按键技巧」页里的一个分组。 */
internal data class KeyTipGroup(val title: String, val tips: List<KeyTip>)

/**
 * 「按键技巧」页的内容（用户 10-31：Shift、Ctrl 的高级操作很多人不知道，要有地方说明）。
 * 方向、时长跟着实际设置写（角标长按/反转、符号键时限），不写死默认值。
 * Z X C V 角标 11-02 并进「按键符号」的滑动动作预设，不再算 Ctrl 的用法，这里不写。
 */
internal fun keyTipGroups(settings: KeyboardInputPreferences): List<KeyTipGroup> {
    val upper = when {
        settings.symbolInputMode == SymbolInputMode.LONG_PRESS -> "长按"
        settings.reverseSymbolSwipe -> "上拉"
        else -> "下拉"
    }
    val lower = if (settings.reverseSymbolSwipe) "下拉" else "上拉"
    val clearSwipe = if (settings.deleteSwipeReversed) "上拉" else "下拉"
    val restoreSwipe = if (settings.deleteSwipeReversed) "下拉" else "上拉"
    val hold = "%.1f".format(com.kingzcheung.xime.ui.keyboard.normalizeSymbolKeyHoldSeconds(settings.symbolKeyHoldSeconds))
    return listOf(
        KeyTipGroup("Shift", listOf(
            KeyTip("点一下 Shift", "下一个字母大写，打完自动回到小写。"),
            KeyTip("连点两下 Shift", "大写锁定，再点一下解除。"),
            KeyTip("按住 Shift 再点字母", "按住期间一直是大写。"),
            KeyTip("从 Shift 拖到字母上松手", "只把这一个字母打成大写。14 键拖动时双字母键拆成单字母，停在哪个出哪个。"),
            KeyTip("按住 Shift 看候选", "有英文的候选中英对调，这时点候选或按空格上屏英文。"),
            KeyTip("从 Shift 拖到候选上松手", "这条候选有英文就上屏英文。"),
            KeyTip("长按空格移光标时按住 Shift", "选中文字，和电脑上 Shift 加方向键一样。"),
        )),
        KeyTipGroup("Ctrl(左下角 !@# 键)", buildList {
            add(KeyTip("按住 !@#", "按满 $hold 秒，这个键写成 Ctrl，字母键渐变成显示快捷键：全选、复制、粘贴、剪切、撤销、重做、保存、查找等；点一下开符号时键面不变。"))
            add(KeyTip("按住 !@# 拖到字母上松手", "发送 Ctrl 加这个字母，和实体键盘一样；也可以另一根手指直接点字母。不用等快捷键显示出来。"))
            add(KeyTip("按住 !@# 点回车", "发送消息，同 Ctrl+回车；也可以拖到回车上松手。还在打字时只上屏编码。"))
            add(KeyTip("按住 !@# 点删除", "同 Ctrl+删除：系统输入框里删到上一个词的开头，不认 Ctrl 的应用当普通删除。还在打字时只删编码。"))
            add(KeyTip("按住 !@# 长按空格拖动", "选中文字，同按住 Shift。"))
            add(KeyTip("点一下 !@#", "$hold 秒内、没拖动就松手才打开符号面板；时限在「按键与手势 › 符号键松手打开符号的时限」里调。",
                listOf("按键与手势 › 符号键松手打开符号的时限")))
        }),
        KeyTipGroup("九键", listOf(
            KeyTip("按住 !@# 拖到数字键上松手", "1 关闭、2 刷新、3 重做、4 全选、5 剪切、6 查找、7 撤销、8 复制、9 粘贴；也可以另一根手指直接点数字键。回车、空格同 26 键。"),
            KeyTip("长按「下一项」", "等于按住 Shift：候选中英对调，松手恢复。"),
            KeyTip("从「下一项」拖到候选上松手", "这条候选有英文就上屏英文。"),
        )),
        KeyTipGroup("其他按键", listOf(
            KeyTip("${upper}字母键", "输入键上方的角标；${lower}输入下方的角标。"),
            KeyTip("长按空格左右拖", "移动光标。"),
            KeyTip("删除键${clearSwipe}", "清空输入框；随后${restoreSwipe}删除键撤回这次清空。"),
            KeyTip("长按语言键", "弹出语言列表。"),
            KeyTip("长按候选", "确认后删掉这个词的学习记录。"),
        )),
    )
}

/** 「按键技巧」页：按 [keyTipGroups] 分组列出，每条一行「怎么按」加一行「会怎样」。 */
@Composable
internal fun KeyTipsSettings() {
    val settings = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        keyTipGroups(settings).forEach { group ->
            SettingsSection(title = group.title, content = {
                group.tips.forEachIndexed { index, tip ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(tip.how, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface)
                        SettingsJumpText(tip.what, tip.jumps, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            })
        }
    }
}
