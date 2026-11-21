package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 开关下面的图例（用户 10-31「能用图例的多用图例」，11-02「之前／之后这种在大屏幕上极其丑陋」）：
 * 都用 [SettingsLegend] 限宽卡片，只画开关现在的效果，不画前后对比；只看不点，随开关和相关设置实时变化。
 */

/** 带图标的整行开关（SettingsToggleItem）下面的图例：和说明文字左对齐（16 + 图标 40 + 16 = 72dp）。 */
@Composable
internal fun ToggleRowLegend(tag: String, start: Dp = 72.dp, content: @Composable ColumnScope.() -> Unit) =
    SettingsLegend(tag, Modifier.padding(start = start, end = 16.dp, bottom = 12.dp), content)

/** 图例下面的一行小字：强调色写现在的效果，灰色写没有这个效果。 */
@Composable
private fun LegendCaption(text: String, active: Boolean) {
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}

/** 图例里的一条候选栏底。 */
@Composable
private fun LegendStrip(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** 图例里多出来的那一项（补上的候选、自动加的空格、邮箱后缀）：强调色浅底。 */
@Composable
private fun LegendAdded(text: String) {
    val primary = MaterialTheme.colorScheme.primary
    Text(text, Modifier.clip(RoundedCornerShape(4.dp)).background(primary.copy(alpha = 0.15f)).padding(horizontal = 4.dp),
        fontSize = 13.sp, color = primary, fontWeight = FontWeight.Medium, maxLines = 1)
}

/**
 * 图例里的一个按键（44×50dp）：[main] 居中；[top] 右上角；[bottom] 左下角（同键盘角标位置）；
 * [below] 写在字母下面居中（双拼韵母、分号键上的 ing），这时字母往上挪，不和下面的字叠在一起。
 */
@Composable
private fun LegendKey(main: String, top: String? = null, bottom: String? = null, below: String? = null,
                      mainColor: Color? = null, accent: Color? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.6f)
    Box(Modifier.size(width = 44.dp, height = 50.dp).clip(RoundedCornerShape(7.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp))) {
        Text(main, Modifier.align(Alignment.Center).padding(bottom = if (below != null) 12.dp else 0.dp),
            fontSize = if (main.length > 2) 14.sp else if (main.length > 1) 15.sp else if (below != null) 17.sp else 20.sp,
            color = mainColor ?: onSurface, maxLines = 1)
        if (top != null) Text(top, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
            fontSize = 11.sp, color = muted, fontWeight = FontWeight.Medium, maxLines = 1)
        if (bottom != null) Text(bottom, Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 2.dp),
            fontSize = 12.sp, color = accent ?: muted, fontWeight = if (accent != null) FontWeight.Bold else null, maxLines = 1)
        if (below != null) Text(below, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            fontSize = 11.sp, lineHeight = 12.sp, color = accent ?: muted,
            fontWeight = if (accent != null) FontWeight.Bold else null, maxLines = 1)
    }
}

/**
 * 「逗号键分词」：空格旁标点键（只开句号键时是句号键）左下角的 '，打拼音时往下角标的方向滑，
 * 方向跟「反转上下滑方向」走。两个标点键都关时只剩 F 键角标的 '。
 */
@Composable
internal fun SyllableSplitKeyLegend(on: Boolean, comma: Boolean, period: Boolean, reversed: Boolean) {
    val (left, right) = com.kingzcheung.xime.ui.keyboard.spacePunctuationKeys(comma, period, fullWidth = true)
    val key = left ?: right
    val primary = MaterialTheme.colorScheme.primary
    ToggleRowLegend("syllable-split-key-legend") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (key != null) LegendKey(key.primary, top = key.alternate.ifEmpty { null },
                bottom = if (on) "'" else null, accent = primary)
            else LegendKey("f", bottom = "'", accent = if (on) primary else null)
            if (on) LegendCaption("打拼音时${if (reversed) "下滑" else "上滑"}插入分词", active = true)
        }
    }
}

/** 「Shift 键分词」：打拼音时左下角 Shift 键写「分词」，点一下插入 '。 */
@Composable
internal fun ShiftSyllableSplitLegend(on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    ToggleRowLegend("shift-syllable-split-legend") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendKey(if (on) "分词" else "⇧", mainColor = if (on) primary else null)
            LegendKey("z"); LegendKey("x")
        }
    }
}

/** 「ing 放在 Shift 键上」（双拼方案页，方案卡片里已有内边距）：ing 在 L 右边的分号键，还是打了声母后在 Shift 键上。 */
@Composable
internal fun SemicolonOnShiftLegend(onShift: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    SettingsLegend("semicolon-on-shift-legend") {
        // 两排各取一端（第二排右端、第三排左端），不加「第二排」字样：手机上图例只有二百多 dp 宽。
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendKey("j"); LegendKey("k"); LegendKey("l")
            if (!onShift) LegendKey(";", below = "ing", accent = primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendKey(if (onShift) "ing" else "⇧", mainColor = if (onShift) primary else null)
            LegendKey("z"); LegendKey("x")
        }
    }
}

/** 候选条：[code] 是打的字母，[words] 是原有候选，开着时 [extra] 排在 [skipped] 个省略之后。 */
@Composable
private fun CandidateLine(code: String, words: List<String>, extra: String?, skipped: Boolean = false) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    LegendStrip {
        Text(code, fontSize = 10.sp, color = onSurface.copy(alpha = 0.55f), maxLines = 1)
        Spacer(Modifier.width(10.dp))
        words.forEachIndexed { index, word ->
            if (index > 0) Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Text(word, fontSize = 13.sp, maxLines = 1, color = if (index == 0) primary else onSurface,
                fontWeight = if (index == 0) FontWeight.Medium else null)
        }
        if (extra != null) {
            if (words.isNotEmpty()) Spacer(Modifier.width(12.dp))
            if (skipped) {
                Text("…", fontSize = 13.sp, color = onSurface.copy(alpha = 0.55f), maxLines = 1)
                Spacer(Modifier.width(12.dp))
            }
            LegendAdded(extra)
        }
    }
}

/** 中文输出里补候选的开关（生僻字、日文汉字、韩文汉字、粤语字、时间日期、表情、符号名、希腊字母）：一条候选，开着时多出那一项。 */
@Composable
internal fun CandidateExtraLegend(tag: String, code: String, words: List<String>, extra: String, on: Boolean,
                                  skipped: Boolean = false) {
    ToggleRowLegend(tag) { CandidateLine(code, words, extra.takeIf { on }, skipped) }
}

/** 「双拼简拼」：只打声母 wxzf，开着时出「微信支付」。 */
@Composable
internal fun DoublePinyinAbbreviationLegend(on: Boolean) {
    ToggleRowLegend("double-pinyin-abbreviation-legend") {
        CandidateLine("wxzf", emptyList(), "微信支付".takeIf { on })
        if (!on) LegendCaption("只打声母不出词", active = false)
    }
}

/** 「合并键均匀排布」：内置 14 键的缩略键盘（同布局栏的 [MiniatureKeyboard]），合并键宽度随开关变。 */
@Composable
internal fun EvenMergedKeysLegend(even: Boolean) {
    val rows = remember {
        listOf(listOf("qw", "er", "ty", "ui", "op"), listOf("as", "df", "gh", "jk", "l"), listOf("zx", "cv", "bn", "m"))
    }
    ToggleRowLegend("even-merged-keys-legend") {
        Box(Modifier.fillMaxWidth().height(76.dp)) {
            MiniatureKeyboard(rows, letterLayout = true, contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier, evenMergedKeys = even)
        }
    }
}

/** 「右半边也放 G 键」：分体 26 键左右两半的缩略图，右半第二排开头有没有 G。 */
@Composable
internal fun SplitRightGLegend(rightG: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    val left = remember { listOf("qwert", "asdfg", "zxcvb").map { row -> row.map(Char::toString) } }
    val right = listOf("yuiop".map(Char::toString), listOfNotNull("g".takeIf { rightG }) + "hjkl".map(Char::toString),
        listOf("n", "m"))
    ToggleRowLegend("split-right-g-legend") {
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxSize()) { MiniatureKeyboard(left, letterLayout = false, contentColor = color, modifier = Modifier) }
            Box(Modifier.weight(1f).fillMaxSize()) { MiniatureKeyboard(right, letterLayout = false, contentColor = color, modifier = Modifier) }
        }
    }
}

/** 「候选词间距加宽」（候选词页，行没有图标）：同一排候选，开时每两个之间空出约一个字，中间一条浅色竖线。 */
@Composable
internal fun CandidateSpacingLegend(wide: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ToggleRowLegend("candidate-spacing-legend", start = 16.dp) {
        LegendStrip {
            listOf("你", "尼", "泥", "拟", "逆").forEachIndexed { index, word ->
                if (index > 0 && wide) Box(Modifier.width(22.dp).testTag("candidate-spacing-legend-divider"),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.width(1.dp).height(12.dp).background(onSurface.copy(alpha = 0.2f)))
                } else if (index > 0) Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Text(word, fontSize = 13.sp, maxLines = 1,
                    color = if (index == 0) MaterialTheme.colorScheme.primary else onSurface,
                    fontWeight = if (index == 0) FontWeight.Medium else null)
            }
        }
    }
}

/** 英文自动加空格：[label] 是怎么打的，[before] 后面开着时多一个 ␣（强调），再接 [after]。 */
@Composable
internal fun AutoSpaceLegend(tag: String, label: String, before: String, after: String, on: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ToggleRowLegend(tag) {
        LegendStrip {
            Text(label, fontSize = 11.sp, color = onSurface.copy(alpha = 0.55f), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(before, fontSize = 13.sp, color = onSurface, maxLines = 1)
            if (on) LegendAdded("␣")
            Text(after, fontSize = 13.sp, color = onSurface, maxLines = 1)
        }
    }
}

/** 「打 @ 时给邮箱后缀」：打 abc@ 后候选栏给常用后缀。 */
@Composable
internal fun EmailSuffixLegend(on: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ToggleRowLegend("email-suffix-legend") {
        LegendStrip {
            Text("abc@", fontSize = 13.sp, color = onSurface, maxLines = 1)
            if (on) {
                Spacer(Modifier.width(10.dp))
                LegendAdded("qq.com")
                Spacer(Modifier.width(6.dp))
                LegendAdded("163.com")
            }
        }
        if (!on) LegendCaption("候选栏不给后缀", active = false)
    }
}
