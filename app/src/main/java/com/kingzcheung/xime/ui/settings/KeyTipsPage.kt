package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
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
            // 「点一下 Shift 锁定大写」开着时点一下就是锁定（用户 11-23）。
            *(if (settings.shiftTapLocks) arrayOf(KeyTip("点一下 Shift", "大写锁定，再点一下解除。"))
            else arrayOf(KeyTip("点一下 Shift", "下一个字母大写，打完自动回到小写。"),
                KeyTip("连点两下 Shift", "大写锁定，再点一下解除。"))),
            KeyTip("按住 Shift 再点字母", "按住期间一直是大写。"),
            KeyTip("从 Shift 拖到字母上松手", "只把这一个字母打成大写。"),
            KeyTip("按住 Shift 看候选", "有英文的候选换成英文。"),
            KeyTip("从 Shift 拖到候选上松手", "这条候选有英文就上屏英文。"),
            KeyTip("长按空格移光标时按住 Shift", "选中文字。"),
        )),
        KeyTipGroup("Ctrl(左下角 !@# 键)", buildList {
            add(KeyTip("按住 !@#", "按满 $hold 秒，这个键变成 Ctrl，字母键上显示快捷键。"))
            add(KeyTip("按住 !@# 拖到字母上松手", "发送 Ctrl 加这个字母，如 Ctrl+C 复制。"))
            add(KeyTip("按住 !@# 点回车", "发送消息，同 Ctrl+回车。"))
            add(KeyTip("按住 !@# 点删除", "删掉前一个词，同 Ctrl+删除。"))
            add(KeyTip("按住 !@# 长按空格拖动", "选中文字，同按住 Shift。"))
            add(KeyTip("点一下 !@#", "$hold 秒内松手打开符号面板；时长在「按键与手势 › 符号键变 Ctrl 的时长」里调。",
                listOf("按键与手势 › 符号键变 Ctrl 的时长")))
        }),
        KeyTipGroup("九键", buildList {
            add(KeyTip("按住 !@# 拖到数字键上松手", "1 关闭、2 刷新、3 重做、4 全选、5 剪切、6 查找、7 撤销、8 复制、9 粘贴。"))
            // 「去掉 0 键，回车加高」开着时没有「下一项」键，这两条不写（用户 11-15）。
            if (!settings.t9MergeZeroIntoEnter) {
                add(KeyTip("长按「下一项」", "同按住 Shift，候选换成英文。"))
                add(KeyTip("从「下一项」拖到候选上松手", "这条候选有英文就上屏英文。"))
            } else if (settings.cornerSymbolInput) {
                add(KeyTip("回车${if (settings.reverseSymbolSwipe) "上拉" else "下拉"}", "打 0，打字选词时不接。0 键已去掉，见「九键与 14 键 › 去掉 0 键，回车加高」。",
                    listOf("九键与 14 键 › 去掉 0 键，回车加高")))
            }
            // 「空格上滑输入 0」开着时（用户 11-22，默认关）写上空格上滑，以及 0 键一直是「→」时没候选能移光标。
            if (settings.t9SpaceSwipeUpZero) {
                add(KeyTip("空格上滑", "打 0，打字时不接。见「九键与 14 键 › 空格上滑输入 0」。",
                    listOf("九键与 14 键 › 空格上滑输入 0")))
                if (!settings.t9MergeZeroIntoEnter) add(KeyTip("没有候选时点「→」", "光标右移一格，左滑左移、右滑右移。"))
            }
        }),
        KeyTipGroup("其他按键", listOf(
            KeyTip("${upper}字母键", "输入键上方的角标；${lower}输入下方的角标。"),
            KeyTip("长按空格左右拖", "移动光标。"),
            KeyTip("删除键${clearSwipe}", "清空输入框，${restoreSwipe}可撤回。"),
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
