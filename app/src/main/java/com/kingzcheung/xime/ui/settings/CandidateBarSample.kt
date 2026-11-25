package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.i18n.tr
import com.kingzcheung.xime.ui.keyboard.LocalEdgeButtonStrength
import com.kingzcheung.xime.ui.keyboard.ToolbarButtonIcon
import com.kingzcheung.xime.ui.keyboard.candidateDividerColor
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette

/**
 * 「候选栏按钮」卡片里的小候选栏（11-24 他：做出来要和 11-23 的效果图一样）。效果图里是他键盘的真截图，
 * 这里照真候选栏画：当前主题、当前深浅色的键盘底（图片主题就是那张图），候选字色、第一个候选的选中底、
 * 两端按钮的淡底和真候选栏同一套图标；空闲那条放他自己排的工具栏按钮。只画给人看，不能点。
 */
private class SampleLook(
    val isDark: Boolean,
    val background: com.kingzcheung.xime.settings.BackgroundConfig?,
    val fallback: Color,
    val text: Color,
    val selectedText: Color,
    val accent: Color,
)

@Composable
private fun rememberSampleLook(): SampleLook {
    val context = LocalContext.current
    // 和键盘一样按「显示模式」定深浅：0 浅色、1 深色、2 跟随系统。深色单独配了主题时用那一套（同 XimeTheme）。
    val isDark = when (SettingsPreferences.getDarkMode(context)) {
        0 -> false
        1 -> true
        else -> isSystemInDarkTheme()
    }
    return remember(isDark) {
        val base = SettingsPreferences.getKeyboardTheme(context)
        val themeId = if (isDark) KeysConfigHelper.loadThemeIdForMode(context, true).takeIf { it.isNotEmpty() } ?: base else base
        val theme = KeyboardThemes.getThemeById(themeId)
        val palette = resolveKeyboardPalette(themeId, isDark)
        SampleLook(isDark, theme.keyboardBackground, if (isDark) theme.keyboardBgDark else theme.keyboardBgLight,
            palette.candidateText.copy(alpha = 1f), palette.selectedText.copy(alpha = 1f), palette.accent.copy(alpha = 1f))
    }
}

private val SAMPLE_WORDS = listOf("测试", "测是", "侧视", "侧室", "策士", "测")

@Composable
private fun SampleStrip(look: SampleLook, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
        .keyboardBackground(look.background, look.isDark, look.fallback)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** 两端按钮：和真候选栏一样平时垫一层淡底（[LocalEdgeButtonStrength]）。[filled] 为 false 时只有图标（工具栏右边的收起键）。 */
@Composable
private fun SampleEdge(icon: ImageVector, look: SampleLook, filled: Boolean = true) {
    val strength = LocalEdgeButtonStrength.current
    val fill = if (!filled) Color.Transparent
        else if (look.isDark) Color.White.copy(alpha = 0.25f * strength) else Color(0xFFE0E0E0).copy(alpha = strength)
    Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = look.text, modifier = Modifier.size(22.dp))
        }
    }
}

/** 打字时的候选栏：[left] 为 null 时左边不放键，候选从最左开始。 */
@Composable
internal fun CandidateBarSample(left: ImageVector?, right: ImageVector, modifier: Modifier = Modifier) {
    val look = rememberSampleLook()
    Box(modifier) {
        SampleStrip(look) {
            if (left != null) SampleEdge(left, look) else Spacer(Modifier.width(4.dp))
            // 后面的候选照真的一样放不下就被切掉（效果图里最后一个「测」只露一半）。
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                    verticalAlignment = Alignment.CenterVertically) {
                    SAMPLE_WORDS.forEachIndexed { index, word ->
                        if (index > 0) Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(16.dp)
                            .background(candidateDividerColor(look.text)))
                        val selected = index == 0
                        androidx.compose.material3.Text(word,
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (selected) look.accent.copy(alpha = 0.2f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            color = if (selected) look.selectedText else look.text, fontSize = 17.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, softWrap = false)
                    }
                }
            }
            SampleEdge(right, look)
        }
    }
}

/** 没在打字时的工具栏：左边 CyIME 标志，中间是他排好的工具栏按钮，右边收起键盘。 */
@Composable
internal fun CandidateToolbarSample(modifier: Modifier = Modifier) {
    val look = rememberSampleLook()
    val context = LocalContext.current
    val items = remember {
        SettingsPreferences.getToolbarButtons(context).mapNotNull { id ->
            ToolbarButton.fromId(id)?.let { ToolbarButtonItem.Builtin(it) }
        }
    }
    Box(modifier) {
        SampleStrip(look) {
            Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.cyime_toolbar_mark), contentDescription = null, tint = look.text,
                    modifier = Modifier.size(20.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                items.forEach { ToolbarButtonIcon(it, tint = look.text, modifier = Modifier.size(20.dp)) }
            }
            SampleEdge(Icons.Filled.KeyboardArrowDown, look, filled = false)
        }
    }
}

/** 说明文字里的这几个符号画成和候选栏上同一个图标，大小跟字一样（11-23 他：「跟文字保持一样」）。 */
private fun glyphIcon(ch: Char): ImageVector? = when (ch) {
    '‹' -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    '▾', '⌄' -> Icons.Filled.KeyboardArrowDown
    '×' -> Icons.Filled.Close
    '⊗' -> Icons.Outlined.HighlightOff
    else -> null
}

/**
 * 一行字，里面的 ‹ ▾ ⌄ × ⊗ 换成候选栏上的图标。先整句翻译（en.tsv 里整句一条），再换图标；
 * 读屏和测试读到的仍是原来的字符。
 */
@Composable
internal fun GlyphText(
    zh: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
) {
    val shown = tr(zh)
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val icons = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        for (ch in shown) {
            val icon = glyphIcon(ch)
            if (icon == null) { append(ch); continue }
            val id = "glyph-$ch"
            appendInlineContent(id, ch.toString())
            icons.getOrPut(id) {
                InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
    androidx.compose.material3.Text(text, modifier, color = tint, style = style, fontWeight = fontWeight, inlineContent = icons)
}

/**
 * 候选栏三种用法的一张卡，照 11-23 他认可的效果图（cand-mode-picker-1123/mock.png）：卡片里第一行是名字、标签、单选圆点，
 * 下面是真样子的小候选栏，再下面每行「左边 ‹　取消输入…」。选中时描边。
 * 名字（默认、经典、右侧收起）、不要小标题是 11-24/11-25 他在截图上改的；「左右拉」是他要两边都能滑（11-24）。
 */
@Composable
internal fun CandidateModeCard(
    mode: com.kingzcheung.xime.settings.CandidateRightKey,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 新手引导里用：页面底和卡片同色，没选中的也垫一层淡底、细边，和引导里别的选项一样看得出是一张卡。 */
    standalone: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
            .testTag("candidate-right-key:${mode.name.lowercase()}")
            .semantics { this.selected = selected }
            .then(if (selected) Modifier.border(2.dp, primary, RoundedCornerShape(16.dp))
                else if (standalone) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = if (standalone) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.kingzcheung.xime.ui.i18n.Text(name, style = MaterialTheme.typography.titleMedium,
                    color = if (selected) primary else onSurface, maxLines = 1)
                // 11-25 他：默认适合三键导航；右侧收起也是给全面屏这种返回不好按的。两张全面屏的卡用后半句分开。
                val tag = when (mode) {
                    com.kingzcheung.xime.settings.CandidateRightKey.EXPAND -> "适合三键导航"
                    com.kingzcheung.xime.settings.CandidateRightKey.CLOSE -> "适合全面屏手势"
                    com.kingzcheung.xime.settings.CandidateRightKey.HIDE -> "适合全面屏，随时收键盘"
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (tag.isNotEmpty()) com.kingzcheung.xime.ui.i18n.Text(tag, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                androidx.compose.material3.RadioButton(selected = selected, onClick = null)
            }
            when (mode) {
                com.kingzcheung.xime.settings.CandidateRightKey.EXPAND -> {
                    CandidateBarSample(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Icons.Filled.KeyboardArrowDown)
                    CandidateModeCaption("左边 ‹", "取消输入、关掉联想")
                    CandidateModeCaption("右边 ▾", "展开更多候选")
                }
                com.kingzcheung.xime.settings.CandidateRightKey.HIDE -> {
                    CandidateBarSample(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Icons.Filled.KeyboardArrowDown)
                    CandidateModeCaption("左边 ‹", "取消输入、关掉联想")
                    CandidateModeCaption("右边 ⌄", "随时收起键盘")
                    CandidateModeCaption("左右拉", "展开更多候选")
                }
                com.kingzcheung.xime.settings.CandidateRightKey.CLOSE -> {
                    CandidateBarSample(null, Icons.Filled.Close)
                    CandidateToolbarSample()
                    CandidateModeCaption("右边 ×", "关候选、关联想，同一处再点收键盘")
                    CandidateModeCaption("左右拉", "展开更多候选")
                }
            }
        }
    }
}

/** 卡片里的一行说明：前面主题色的「左边 ‹」，后面说它干什么。 */
@Composable
private fun CandidateModeCaption(where: String, what: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.Top) {
        // 前半截至少 64dp，几行的说明对齐成一列（效果图）。
        GlyphText(where, Modifier.widthIn(min = 64.dp), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        GlyphText(what, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge)
    }
}
