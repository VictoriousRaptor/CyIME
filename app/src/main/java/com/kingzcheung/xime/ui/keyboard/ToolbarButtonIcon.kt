package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoFixHigh
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.keyboard.ToolbarButtonItem

/**
 * 工具栏按钮图标统一渲染：内置按钮用 ImageVector 图标（简繁用「简」「繁」字）；
 * 自家 AI 回复、AI 改写、翻译插件用宿主的图标（[ownPluginButtonIcon]）；
 * 其它插件按钮优先显示其资源图标（本地文件，coil 加载），无图标用 label 文字兜底。
 */
@Composable
fun ToolbarButtonIcon(
    item: ToolbarButtonItem,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fontSize: TextUnit = 16.sp,
    /** 开关按钮当前是否开启；全角/半角、简繁按状态换图标。 */
    active: Boolean = false,
) {
    val ownPluginIcon = (item as? ToolbarButtonItem.Plugin)?.let { ownPluginButtonIcon(it.id) }
    when {
        // 简繁：图标就是当前输出的字，「简」或「繁」（用户 2026-11-11）。
        item is ToolbarButtonItem.Builtin && item.button == com.kingzcheung.xime.keyboard.ToolbarButton.TRADITIONAL ->
            GlyphIcon(if (active) "繁" else "简", tint, modifier, contentDescription ?: item.stateLabel(active))
        item is ToolbarButtonItem.Builtin -> Icon(
            imageVector = if (item.button == com.kingzcheung.xime.keyboard.ToolbarButton.FULL_SHAPE && active)
                com.kingzcheung.xime.keyboard.FullWidthIcon else item.button.icon,
            contentDescription = contentDescription ?: item.stateLabel(active),
            tint = tint,
            modifier = modifier,
        )
        ownPluginIcon != null -> Icon(
            imageVector = ownPluginIcon,
            contentDescription = contentDescription ?: item.label,
            tint = tint,
            modifier = modifier,
        )
        item is ToolbarButtonItem.Plugin -> {
            val context = LocalContext.current
            val icon = item.icon
            when {
                icon?.assetName != null -> AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(icon.assetName)
                        .crossfade(true)
                        .build(),
                    contentDescription = contentDescription ?: item.label,
                    modifier = modifier,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                else -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = icon?.text ?: item.label,
                        color = tint,
                        fontSize = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 自家 AI 回复、AI 改写、翻译插件的按钮：用和内置按钮同一套 TwoTone 图标，
 * 不再显示插件 manifest 里的单字（应、改、译；用户 2026-11-11 嫌丑）。按按钮 id 认，别人的插件照旧。
 */
internal fun ownPluginButtonIcon(buttonId: String): ImageVector? = when (buttonId) {
    "com.kingzcheung.xime.plugin.ai_reply:open" -> Icons.TwoTone.Quickreply
    "com.kingzcheung.xime.plugin.ai_rewrite:open" -> Icons.TwoTone.AutoFixHigh
    "com.kingzcheung.xime.plugin.translate:open" -> Icons.TwoTone.Translate
    else -> null
}

/** 用一个字当图标：字高按图标框算（框的 0.78），和旁边的线条图标看起来一样大。 */
@Composable
private fun GlyphIcon(text: String, tint: Color, modifier: Modifier, description: String) {
    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val box = minOf(maxWidth, maxHeight).let { if (it == Dp.Infinity) 24.dp else it }
        val fontSize = with(LocalDensity.current) { (box * 0.78f).toSp() }
        Text(
            text = text,
            color = tint,
            maxLines = 1,
            style = TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** 键盘上显示的名称：全角/半角、简繁开关按当前状态显示，其余按钮用原名称。 */
internal fun ToolbarButtonItem.stateLabel(active: Boolean): String = when {
    this is ToolbarButtonItem.Builtin && button == com.kingzcheung.xime.keyboard.ToolbarButton.FULL_SHAPE ->
        if (active) "全角" else "半角"
    this is ToolbarButtonItem.Builtin && button == com.kingzcheung.xime.keyboard.ToolbarButton.TRADITIONAL ->
        if (active) "繁体" else "简体"
    else -> label
}
