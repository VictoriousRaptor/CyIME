package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.CandidateCommentMode
import com.kingzcheung.xime.ui.theme.liquidGlassSample

/**
 * 一排 [IllustratedChoiceCard] 的高度：至少 [min]；示意图和标题放不下（字多一行、系统字号调大）时按内容长高，
 * 不再切掉最后一行（11-02：角标三张卡的「长按 → …」被切掉一半）。别用固定 height 包选项卡片。
 */
internal fun Modifier.choiceCardRowHeight(min: Dp): Modifier = heightIn(min = min).height(IntrinsicSize.Min)

/**
 * 图例里的「键盘」配色：底色、键色、字色都取当前主题的真键盘（[com.kingzcheung.xime.ui.theme.LocalKeyboardPalette]），
 * 不用设置页 Material 的 surface／surfaceVariant——黑白浅色等主题两者几乎一样，图例里的键白底白键看不见（11-03 截图）。
 * 图例内部照旧读 surface（键）、surfaceVariant（底）、onSurface（字），所以只在图例外框套一层即可。
 * 键与底色太接近（导入主题）时把键往字色拉一点，保证看得出键。
 */
@Composable
internal fun LegendKeyboardColors(content: @Composable () -> Unit) {
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current
    if (palette == null) { content(); return }
    val scheme = MaterialTheme.colorScheme
    val board = palette.background.copy(alpha = 1f)
    val text = palette.text.copy(alpha = 1f)
    val key = legendKeyColor(board, palette.key.copy(alpha = 1f), text)
    MaterialTheme(colorScheme = scheme.copy(surface = key, surfaceVariant = board, onSurface = text,
        outlineVariant = androidx.compose.ui.graphics.lerp(board, text, .18f)), content = content)
}

/** 键和底色亮度差太小时（对比不到 [LEGEND_MIN_KEY_CONTRAST]）把键往字色拉，直到看得出。 */
internal fun legendKeyColor(board: Color, key: Color, text: Color): Color {
    fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + .05f) / (lo + .05f)
    }
    if (contrast(key, board) >= LEGEND_MIN_KEY_CONTRAST) return key
    for (step in 1..10) {
        val candidate = androidx.compose.ui.graphics.lerp(key, text, step * .03f)
        if (contrast(candidate, board) >= LEGEND_MIN_KEY_CONTRAST) return candidate
    }
    return androidx.compose.ui.graphics.lerp(key, text, .3f)
}

/** 黑白浅色真键盘白键对 #E8E8EA 底是 1.24；比这再淡就看不出键了。 */
internal const val LEGEND_MIN_KEY_CONTRAST = 1.15f

/**
 * 带示意图的单选卡片（与「编码显示」同一外观）：上方是这个选项在键盘上的样子，下方是名称。
 * 选中时描边加粗并标为 selected，读屏与测试都能识别。
 */
@Composable
internal fun IllustratedChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
    /** 标题最多几行；名字长（如「Rain75 紫薇轴」）时设 2，窄屏也显示完整。 */
    titleMaxLines: Int = 1,
    /** 三列角标卡在窄屏上收紧留白，给手势和符号留够宽度。 */
    illustrationPadding: Dp = 8.dp,
    illustration: @Composable ColumnScope.() -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.alpha(if (enabled) 1f else 0.45f)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .semantics { this.selected = selected }
                .border(if (selected) 2.dp else 0.5.dp,
                    if (selected) primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            LegendKeyboardColors {
                Column(
                    Modifier.fillMaxSize().padding(illustrationPadding).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(illustrationPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = illustration,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = titleMaxLines)
    }
}

/** 一个迷你按键：主字符居中，上方角标在右上，下方角标在左下（与键盘实际位置一致）。 */
@Composable
private fun MiniKey(main: String, top: String?, bottom: String?, highlight: Color? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(width = 44.dp, height = 50.dp).clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)),
    ) {
        Text(main, Modifier.align(Alignment.Center), fontSize = 20.sp, color = onSurface)
        if (top != null) Text(top, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
            fontSize = 11.sp, color = highlight ?: onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.Medium)
        if (bottom != null) Text(bottom, Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 2.dp),
            fontSize = 11.sp, color = highlight ?: onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun GestureLine(gesture: String, symbol: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(gesture, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text("→", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(symbol, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun CandidateStrip(withComments: Boolean, narrow: Boolean = false) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hint = onSurface.copy(alpha = 0.55f)
    Row(
        Modifier.then(if (narrow) Modifier.width(70.dp) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("你好", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, maxLines = 1)
        if (withComments) Text(" nǐ hǎo", fontSize = 9.sp, color = hint, maxLines = 1, overflow = TextOverflow.Clip)
        if (!narrow) {
            Spacer(Modifier.width(8.dp))
            Text("拟好", fontSize = 13.sp, color = onSurface, maxLines = 1)
            if (withComments) Text(" nǐ hǎo", fontSize = 9.sp, color = hint, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/** 候选旁的拼音／编码：显示、按宽度自动、隐藏。 */
@Composable
internal fun CandidateCommentIllustration(mode: CandidateCommentMode) {
    when (mode) {
        CandidateCommentMode.SHOW -> CandidateStrip(withComments = true)
        CandidateCommentMode.HIDE -> CandidateStrip(withComments = false)
        CandidateCommentMode.AUTO -> Column(verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            CandidateStrip(withComments = true)
            CandidateStrip(withComments = false, narrow = true)
        }
    }
}

/**
 * 候选栏取消按钮：打字时候选栏最左边的「<」，点它取消当前输入；关掉后候选从最左边开始。
 * 两端圆底按钮和真实候选栏一样（左取消、右展开），随开关实时变化。
 */
@Composable
internal fun CandidateCancelIllustration(show: Boolean) = LegendKeyboardColors { CandidateCancelContent(show) }

@Composable
private fun CandidateCancelContent(show: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val muted = onSurface.copy(alpha = 0.6f)
    @Composable
    fun EdgeButton(icon: ImageVector, highlight: Boolean) {
        Box(Modifier.size(26.dp).clip(CircleShape)
            .background(if (highlight) primary.copy(alpha = 0.3f) else onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (highlight) primary else muted, modifier = Modifier.size(20.dp))
        }
    }
    Column(Modifier.fillMaxWidth().testTag("candidate-cancel-illustration").clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (show) EdgeButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, highlight = true)
            Spacer(Modifier.width(if (show) 8.dp else 4.dp))
            Text("你好", fontSize = 13.sp, color = primary, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text("拟好", fontSize = 13.sp, color = onSurface, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text("你", fontSize = 13.sp, color = onSurface, maxLines = 1)
            Spacer(Modifier.weight(1f))
            EdgeButton(Icons.Filled.KeyboardArrowUp, highlight = false)
        }
        Spacer(Modifier.height(4.dp))
        // 开着时「↑」对着左箭头（按钮中心离边 4 + 13 = 17dp）。
        Text(if (show) "↑ 点它取消当前输入" else "候选词从最左边开始", fontSize = 12.sp, maxLines = 1,
            color = if (show) primary else muted, modifier = Modifier.padding(start = if (show) 13.dp else 4.dp))
    }
}

/** 候选注释内容：拼音／编码，或中英释义。 */
@Composable
internal fun CandidateCommentContentIllustration(content: com.kingzcheung.xime.settings.CandidateCommentContent) {
    val hint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val gloss = content == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(if (gloss) "你好" to " hello" else "你好" to " nǐ hǎo",
            if (gloss) "apple" to " 苹果" else "苹果" to " píng guǒ").forEachIndexed { index, (word, note) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
                androidx.compose.material3.Text(word, fontSize = 13.sp, maxLines = 1, fontWeight = if (index == 0) FontWeight.Medium else null,
                    color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(note, fontSize = 9.sp, color = hint, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** 振动反馈：跟随系统、始终振动、关闭。 */
@Composable
internal fun HapticModeIllustration(mode: String) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val (mark, caption) = when (mode) {
        "enabled" -> "〰▮〰" to "按键振动"
        "disabled" -> "▮" to "不振动"
        else -> "⚙→▮" to "系统设置"
    }
    // 行高写明，不继承正文 24sp 行高；卡片高度按同样的行高算（见 [HAPTIC_CARD_TEXT_HEIGHT]）。
    // 窄屏三张卡并排时图示不换行，否则会把下面的小字挤出卡片。
    Text(mark, fontSize = 20.sp, lineHeight = 24.sp, color = if (mode == "disabled") muted else primary, maxLines = 1, softWrap = false)
    Spacer(Modifier.height(4.dp))
    Text(caption, fontSize = 12.sp, lineHeight = 16.sp, color = if (mode == "disabled") muted else primary, maxLines = 1)
}

/** [HapticModeIllustration] 两行文字的行高之和，随系统字号缩放。 */
internal val HAPTIC_CARD_TEXT_HEIGHT = 40.sp

/** 三行字母错位示意：按真实单位画出 10/9/7 键，突出 s 与 z 看是否对齐。 */
@Composable
internal fun QwertyStaggerIllustration(stagger: Float) {
    val geometry = com.kingzcheung.xime.ui.keyboard.LetterKeyboardFrame(listOf(10, 9, 7)).geometry(1f, stagger)
    val key = MaterialTheme.colorScheme.surface
    val mark = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val unit = size.width / 10f
        val rowHeight = size.height / 3f
        val gap = unit * 0.12f
        fun row(index: Int, start: Float, count: Int, highlight: Int) {
            repeat(count) { i ->
                drawRoundRect(if (i == highlight) mark else key,
                    androidx.compose.ui.geometry.Offset((start + i) * unit + gap / 2f, index * rowHeight + gap / 2f),
                    androidx.compose.ui.geometry.Size(unit - gap, rowHeight - gap),
                    androidx.compose.ui.geometry.CornerRadius(unit * 0.15f))
            }
        }
        row(0, 0f, 10, -1)
        row(1, geometry.middleRowInsetDp, 9, 1)   // s
        row(2, geometry.outerKeyWeight, 7, 0)     // z
    }
}

/** 底排预览：模式键、空格两侧标点、语言键与回车，按设置实时变化。 */
@Composable
internal fun SpacePunctuationIllustration(comma: Boolean, period: Boolean) = LegendKeyboardColors {
    SpacePunctuationContent(comma, period)
}

@Composable
private fun SpacePunctuationContent(comma: Boolean, period: Boolean) {
    val (left, right) = com.kingzcheung.xime.ui.keyboard.spacePunctuationKeys(comma, period, fullWidth = true)
    val onSurface = MaterialTheme.colorScheme.onSurface
    @Composable
    fun RowScopeKey(label: String, sub: String?, weight: Float, scope: androidx.compose.foundation.layout.RowScope, accent: Boolean = false) {
        with(scope) {
            Box(Modifier.weight(weight).height(34.dp).padding(horizontal = 2.dp).clip(RoundedCornerShape(5.dp))
                .background(if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)) {
                Text(label, Modifier.align(Alignment.Center), fontSize = 12.sp, color = onSurface, maxLines = 1)
                if (sub != null) Text(sub, Modifier.align(Alignment.TopEnd).padding(end = 3.dp), fontSize = 9.sp,
                    color = onSurface.copy(alpha = 0.6f))
            }
        }
    }
    // 和真键盘底排一样：键画在键盘底色上（原来直接画在设置卡片上，浅色主题键和卡片分不开）。
    Row(Modifier.widthIn(max = SETTINGS_LEGEND_MAX_WIDTH).fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        RowScopeKey("!@#", null, 1.2f, this)
        RowScopeKey("123", null, 1.2f, this)
        if (comma) RowScopeKey(left?.primary ?: "，", left?.alternate ?: "。", 1f, this, accent = true)
        RowScopeKey("空格", null, 3.9f + 1f - (if (comma) 1 else 0) - (if (period) 1 else 0), this)
        if (right != null) RowScopeKey(right.primary, right.alternate, 1f, this, accent = true)
        RowScopeKey("中", null, 1f, this)
        RowScopeKey("↵", null, 1.7f, this)
    }
}

/**
 * 开关下面的图例框（11-01 合并设置时加）：只看不点，随开关实时变化；和 [CandidateCancelIllustration] 同一底色。
 * 放在没有图标的设置行下面，左右和文字对齐。最宽 [SETTINGS_LEGEND_MAX_WIDTH]：平板上是一张限宽卡片，不撑满整行（11-02）。
 */
@Composable
internal fun SettingsLegend(tag: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    LegendKeyboardColors {
        Column(modifier.widthIn(max = SETTINGS_LEGEND_MAX_WIDTH).fillMaxWidth().testTag(tag).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }

/** 图例卡片最宽多少：手机上撑满，平板上所有图例都是这一个宽度。 */
internal val SETTINGS_LEGEND_MAX_WIDTH = 420.dp

/** 图例里的一个输入框：[before] 光标 [after]，可再带一段没上屏的拼音。 */
@Composable
private fun LegendField(before: String, after: String, pinyin: String? = null, label: String? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f), maxLines = 1,
            modifier = Modifier.width(34.dp))
        Row(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendFieldText(before, after, pinyin)
        }
    }
}

@Composable
private fun LegendFieldText(before: String, after: String, pinyin: String?) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    Text(before, fontSize = 13.sp, color = onSurface, maxLines = 1)
    if (pinyin != null) Text(pinyin, fontSize = 13.sp, color = primary, maxLines = 1,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
    Box(Modifier.width(2.dp).height(15.dp).background(primary))
    Text(after, fontSize = 13.sp, color = onSurface, maxLines = 1)
    if (before.isEmpty() && after.isEmpty() && pinyin == null)
        Text(" 空", fontSize = 11.sp, color = onSurface.copy(alpha = 0.45f), maxLines = 1)
}

/**
 * 「操作前 → 操作后」图例（11-02，原来「之前／之后」上下叠四行，平板上输入框横着撑满，丑又占地方）：
 * 一张限宽卡片，左右两格一样宽，中间箭头上写动作；左格是操作前，右格随开关变化。
 */
@Composable
private fun SettingsStepLegend(
    tag: String,
    action: String,
    before: @Composable ColumnScope.() -> Unit,
    after: @Composable ColumnScope.() -> Unit,
) = LegendKeyboardColors {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.widthIn(max = SETTINGS_LEGEND_MAX_WIDTH).fillMaxWidth().testTag(tag).clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = before)
        // 箭头一栏定宽：几张图例卡片的左右两格上下对齐。
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(action, fontSize = 10.sp, color = primary, maxLines = 1)
            Text("→", fontSize = 15.sp, color = primary, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = after)
    }
}

/** 图例卡片一格里的输入框：占满这一格。 */
@Composable
private fun LegendBox(before: String, after: String = "", pinyin: String? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendFieldText(before, after, pinyin)
    }
}

/** 输入框下面的候选栏：灰色小字 [lead]（「联想」或删掉的字）后跟几个词；[highlight] 时词用强调色。 */
@Composable
private fun LegendChip(lead: String, words: String, highlight: Boolean = false) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lead, fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f), maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(words, fontSize = 12.sp, maxLines = 1,
            color = if (highlight) MaterialTheme.colorScheme.primary else onSurface,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal)
    }
}

/** 输入框下面的灰色小注。 */
@Composable
private fun LegendNote(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
}

/**
 * 角标输入方式三选一：键上的两个角标各用什么手势输入，长按做什么。
 * 随「反转上下滑方向」和「上方／下方角标提示」变化（用户 11-08）：关掉的角标键上不画，对应那一行也不列；
 * 角标提示只管显示，手势照样能输入，这句写在卡片下面的说明里。长按输入卡不再列「→ 无」那一行。
 */
@Composable
internal fun CornerInputIllustration(
    choice: com.kingzcheung.xime.ui.keyboard.CornerInputChoice,
    reversed: Boolean,
    upHint: Boolean = true,
    downHint: Boolean = true,
) {
    val down = "↓下滑"
    val up = "↑上滑"
    val top = if (reversed) up else down
    val bottom = if (reversed) down else up
    MiniKey("a", "@".takeIf { upHint }, "1".takeIf { downHint })
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.Start) {
        when (choice) {
            com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE -> {
                if (upHint) GestureLine(top, "@")
                if (downHint) GestureLine(bottom, "1")
                GestureLine("长按", "无")
            }
            com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE_BUBBLE -> {
                if (upHint) GestureLine(top, "@")
                if (downHint) GestureLine(bottom, "1")
                GestureLine("长按", "A á")
            }
            com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS -> {
                if (upHint) GestureLine("长按", "@")
                if (downHint) GestureLine(bottom, "1")
            }
        }
    }
}

/** 长按空格三选一：移动光标、连续空格、按住说话。 */
@Composable
internal fun SpaceHoldChoiceIllustration(action: com.kingzcheung.xime.ui.keyboard.SpaceHoldAction) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    when (action) {
        com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.CURSOR -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("你好", fontSize = 13.sp, color = onSurface)
            Box(Modifier.width(2.dp).height(15.dp).background(primary))
            Text("世界", fontSize = 13.sp, color = onSurface)
        }
        com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.REPEAT -> Text("a␣␣␣␣", fontSize = 13.sp, color = onSurface, maxLines = 1)
        // 麦克风用工具栏语音按钮同一个图标，不用 emoji。
        com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.VOICE_TOGGLE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(com.kingzcheung.xime.keyboard.ToolbarButton.VOICE.icon, contentDescription = null, tint = onSurface,
                modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(2.dp))
            Text("说话中…", fontSize = 12.sp, color = onSurface, maxLines = 1)
        }
    }
    Spacer(Modifier.height(6.dp))
    Box(Modifier.width(84.dp).height(20.dp).clip(RoundedCornerShape(5.dp)).background(primary.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center) {
        Text("空格", fontSize = 10.sp, color = onSurface)
    }
    Spacer(Modifier.height(4.dp))
    Text(when (action) {
        com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.CURSOR -> "按住左右拖"
        com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.REPEAT -> "按住一直出空格"
        com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.VOICE_TOGGLE -> "按住说，松手上屏"
    }, fontSize = 11.sp, color = primary, maxLines = 1)
}

/** 键盘面板滑动光标：开着时手指在按键上横扫就移光标；关着时横扫照常是滑动输入。 */
@Composable
internal fun KeyboardSwipeCursorLegend(on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    SettingsLegend("keyboard-swipe-cursor-legend") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("q", "w", "e", "r", "t", "y").forEach { key ->
                Box(Modifier.size(width = 22.dp, height = 26.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on) primary.copy(alpha = 0.3f) else surface), contentAlignment = Alignment.Center) {
                    Text(key, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(if (on) "  ←  手指横扫  →" else "", fontSize = 12.sp, color = primary, maxLines = 1)
        }
        LegendField("你好", "世界", label = if (on) "光标" else null)
        Text(if (on) "在按键上左右滑，光标跟着走；滑行输入这时用不了" else "按键上滑动照常输入，只有按住空格能移光标",
            fontSize = 12.sp, color = if (on) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2)
    }
}

/** 打字中清空：还有没上屏的拼音时，清空是否连输入框里的字一起清。 */
@Composable
internal fun ClearWhileComposingLegend(on: Boolean, reversed: Boolean) {
    SettingsStepLegend("clear-while-composing-legend", if (reversed) "上滑删除键" else "下滑删除键",
        before = { LegendBox("今天天气", pinyin = "hao") },
        after = { LegendBox(if (on) "" else "今天天气") })
}

/** 只清空光标前：光标后面的字留不留。 */
@Composable
internal fun ClearBeforeCursorLegend(on: Boolean, reversed: Boolean) {
    SettingsStepLegend("clear-before-cursor-legend", if (reversed) "上滑删除键" else "下滑删除键",
        before = { LegendBox("你好", "世界") },
        after = { LegendBox("", if (on) "世界" else "") })
}

/** 退格直接删字：上屏后候选栏出了联想词，第一下退格是删字还是只去掉联想词。 */
@Composable
internal fun DeleteSkipsAssociationLegend(on: Boolean) {
    SettingsStepLegend("delete-skips-association-legend", "退格",
        before = { LegendBox("你好"); LegendChip("联想", "世界 朋友") },
        after = {
            LegendBox(if (on) "你" else "你好")
            if (!on) LegendNote("只去掉了联想词")
        })
}

/** 删字后给同音字：退格删掉的字放回候选栏，后面跟同音字。 */
@Composable
internal fun DeleteRecoveryLegend(on: Boolean) {
    SettingsStepLegend("delete-recovery-legend", "退格",
        before = { LegendBox("你号") },
        after = {
            LegendBox("你")
            if (on) LegendChip("号", "好 浩 豪", highlight = true) else LegendNote("候选栏空着")
        })
}

/** 键帽字母三选一：中文、英文键盘上各显示大写还是小写。 */
@Composable
internal fun KeyLabelCaseIllustration(choice: com.kingzcheung.xime.ui.keyboard.KeyLabelCaseChoice) {
    @Composable
    fun line(tag: String, letters: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tag, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.width(20.dp))
        letters.forEach { c ->
            Box(Modifier.padding(horizontal = 1.dp).size(width = 16.dp, height = 20.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Text(c.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    val upperZh = choice != com.kingzcheung.xime.ui.keyboard.KeyLabelCaseChoice.LOWER
    val upperEn = choice == com.kingzcheung.xime.ui.keyboard.KeyLabelCaseChoice.UPPER
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        line("中", if (upperZh) "QWE" else "qwe")
        line("英", if (upperEn) "QWE" else "qwe")
    }
}

/**
 * 「角标与气泡」的图例：上方角标、下方角标、双拼键位提示三个开关和角标不透明度一起实时变化。
 * 双拼键位提示和角标都开着时分上下两层（和键盘上一样，见 KeyButton 的 dpWithCorners）。
 */
@Composable
internal fun KeyFaceHintsLegend(up: Boolean, down: Boolean, doublePinyin: Boolean, opacity: Float) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    // 小鹤双拼的 u、i、o 三个键；角标只是示意。
    val keys = listOf(Triple("u", "sh", "u"), Triple("i", "ch", "i"), Triple("o", null, "uo"))
    val corners = listOf("&" to "7", "*" to "8", "(" to "9")
    val cornerColor = onSurface.copy(alpha = 0.6f * opacity.coerceIn(0.1f, 1f))
    SettingsLegend("key-face-hints-legend", Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            keys.zip(corners).forEach { (key, corner) ->
                val (letter, initial, finals) = key
                Box(Modifier.size(width = 48.dp, height = 54.dp).clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp))) {
                    Text(letter, Modifier.align(Alignment.Center), fontSize = if (doublePinyin) 16.sp else 20.sp, color = onSurface)
                    if (doublePinyin && initial != null) Text(initial, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
                        fontSize = 10.sp, color = primary, fontWeight = FontWeight.Medium)
                    if (doublePinyin) Text(finals, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp),
                        fontSize = 10.sp, color = primary)
                    if (up) Text(corner.first, Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp),
                        fontSize = 10.sp, color = cornerColor)
                    if (down) Text(corner.second, Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp),
                        fontSize = 10.sp, color = cornerColor)
                }
            }
        }
        if (!doublePinyin && !up && !down) Text("键上只有字母，滑动照样能输入符号",
            fontSize = 12.sp, color = onSurface.copy(alpha = 0.7f), maxLines = 2)
    }
}

/**
 * 「液态玻璃强度」三选一（主题与外观 › 材质效果，液态玻璃开着才显示）：标准／加强／最强，标准是原来的样子。
 * 卡片里的两个键用键盘上同一段代码画这一档的亮边、厚度和高光；图片背景的折射画不进示意图，写在下面那行说明里。
 */
@Composable
internal fun LiquidGlassStrengthChoice(
    current: com.kingzcheung.xime.ui.theme.LiquidGlassStrength,
    onChoose: (com.kingzcheung.xime.ui.theme.LiquidGlassStrength) -> Unit,
) {
    Column(Modifier.settingsSearchAnchor("液态玻璃强度").fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("液态玻璃强度", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().choiceCardRowHeight(92.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.kingzcheung.xime.ui.theme.LiquidGlassStrength.entries.forEach { level ->
                IllustratedChoiceCard(level.label, current == level, onClick = { onChoose(level) },
                    modifier = Modifier.weight(1f), tag = "liquid-glass-strength:${level.id}", illustrationPadding = 6.dp) {
                    LiquidGlassStrengthIllustration(level)
                }
            }
        }
        Column {
            Text(when (current) {
                com.kingzcheung.xime.ui.theme.LiquidGlassStrength.STANDARD -> "原来的样子：按键一道细亮边。"
                com.kingzcheung.xime.ui.theme.LiquidGlassStrength.STRONG -> "亮边更粗更亮，边里面多一圈厚度；图片背景在边缘折射得更宽。"
                com.kingzcheung.xime.ui.theme.LiquidGlassStrength.STRONGEST -> "亮边、厚度和图片背景的折射都再加一档。"
            } + "模糊和透明度不跟着变。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (current) {
                com.kingzcheung.xime.ui.theme.LiquidGlassStrength.STANDARD -> Unit
                com.kingzcheung.xime.ui.theme.LiquidGlassStrength.STRONG ->
                    PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.LIQUID_GLASS_STRONG)
                com.kingzcheung.xime.ui.theme.LiquidGlassStrength.STRONGEST ->
                    PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.LIQUID_GLASS_STRONGEST)
            }
        }
    }
}

/** [LiquidGlassStrengthChoice] 卡片里的两个键。键色取图例的键色（当前主题的真键盘），深浅跟着它。 */
@Composable
internal fun LiquidGlassStrengthIllustration(strength: com.kingzcheung.xime.ui.theme.LiquidGlassStrength) {
    val key = MaterialTheme.colorScheme.surface
    val dark = key.luminance() < .5f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (label in listOf("A", "S")) {
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(7.dp)).background(key)
                .liquidGlassSample(7.dp, dark, strength), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** 键盘顶部样式三选一：上面一块是应用界面，下面是固定键盘，看两者交界处。 */
@Composable
internal fun KeyboardTopStyleIllustration(style: com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle) {
    val keyboard = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val app = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(app)) {
        // 应用界面里的几行字
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(0.55f).height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
        }
        val shape = if (style == com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.ROUNDED)
            RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp) else RoundedCornerShape(0.dp)
        val fill = if (style == com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.SOFT)
            Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to keyboard.copy(alpha = 0f), 0.35f to keyboard, 1f to keyboard))
        else Modifier.background(keyboard)
        Column(Modifier.fillMaxWidth().height(36.dp).clip(shape).then(fill).padding(start = 5.dp, end = 5.dp, top = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(6) {
                        Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(2.dp)).background(app))
                    }
                }
            }
        }
    }
}

/** 「长音键」三选一的图例：L 右下两排各取一端，强调色画 ー 在哪个键上。 */
@Composable
internal fun JapaneseLongVowelIllustration(mode: com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    @Composable
    fun Key(main: String, below: String? = null, corner: String? = null, accent: Boolean = false) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
            .background(if (accent) primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface)
            .border(if (accent) 1.dp else 0.5.dp, if (accent) primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(6.dp))) {
            Text(main, Modifier.align(Alignment.Center).padding(bottom = if (below != null) 9.dp else 0.dp),
                fontSize = if (below != null) 14.sp else 16.sp, color = if (accent) primary else onSurface, maxLines = 1)
            if (below != null) Text(below, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                fontSize = 11.sp, color = primary, fontWeight = FontWeight.Bold, maxLines = 1)
            if (corner != null) Text(corner, Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp),
                fontSize = 9.sp, color = onSurface.copy(alpha = 0.6f), maxLines = 1)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag("long-vowel-illustration:${mode.name}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (mode) {
                // L 键就是长音：键面写 ー，角上小 l 表示是原来 L 的位置（和键盘上一致）。
                com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey.L_KEY -> { Key("k"); Key("ー", corner = "l", accent = true) }
                com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey.SEPARATE -> { Key("l"); Key("ー", accent = true) }
                com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey.SHIFT -> { Key("k"); Key("l") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mode == com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey.SHIFT) Key("ー", corner = "⇧", accent = true)
            else Key("⇧")
            Key("z")
        }
    }
}
