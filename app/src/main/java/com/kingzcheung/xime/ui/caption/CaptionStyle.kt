package com.kingzcheung.xime.ui.caption

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial

/**
 * 字幕页统一尺寸：所有按钮同一高度、同一圆角、同一间距；颜色全部取自当前主题（跟随
 * CyIME 主题与深浅色），玻璃质感走键盘同一套 visualMaterial，不另写透明度。
 */
internal object CaptionStyle {
    val ButtonHeight = 56.dp
    val PrimaryHeight = 64.dp
    val Corner = 16.dp
    val PanelCorner = 24.dp
    val Gap = 12.dp
    val Edge = 16.dp
}

internal enum class CaptionEmphasis { Normal, Primary, Danger, DangerQuiet }

/** 图标 + 文字的大按钮（老人认字不认图，所以文字始终在）。 */
@Composable
internal fun CaptionButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasis: CaptionEmphasis = CaptionEmphasis.Normal,
    height: Dp = CaptionStyle.ButtonHeight,
    fontSize: TextUnit = 20.sp,
    maxLines: Int = 1,
    /** 图标在上、字在下：横屏侧栏一排放三个时用。 */
    stacked: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (emphasis) {
        CaptionEmphasis.Normal -> colors.surfaceContainerHighest.translucent(.78f) to colors.onSurface
        CaptionEmphasis.Primary -> colors.primaryContainer to colors.onPrimaryContainer
        CaptionEmphasis.Danger -> colors.error to colors.onError
        CaptionEmphasis.DangerQuiet -> colors.surfaceContainerHighest.translucent(.78f) to colors.error
    }
    val border = when (emphasis) {
        CaptionEmphasis.Normal -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f))
        CaptionEmphasis.DangerQuiet -> BorderStroke(1.dp, colors.error.copy(alpha = .7f))
        // 单色主题里强调色容器和普通按钮几乎同色：加一道强调色描边，选中的一眼能看出。
        CaptionEmphasis.Primary -> BorderStroke(2.dp, colors.primary.copy(alpha = .85f))
        else -> null
    }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = height), shape = RoundedCornerShape(CaptionStyle.Corner),
        color = container, contentColor = content, border = border) {
        if (stacked) Column(Modifier.visualMaterial(VisualStyles.current, CaptionStyle.Corner, level = MaterialLevel.RAISED)
            .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, fontSize = fontSize, lineHeight = fontSize * 1.2f, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        } else Row(Modifier.visualMaterial(VisualStyles.current, CaptionStyle.Corner, level = MaterialLevel.RAISED)
            .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = fontSize, lineHeight = fontSize * 1.2f, fontWeight = FontWeight.Medium,
                maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** 半透明玻璃面板：顶部控制条、底部操作条、对话框都用它。 */
@Composable
internal fun CaptionPanel(modifier: Modifier = Modifier, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(CaptionStyle.PanelCorner), color = colors.surfaceContainer.translucent(.82f),
        contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .35f))) {
        Column(Modifier.visualMaterial(VisualStyles.current, CaptionStyle.PanelCorner, level = MaterialLevel.FLOATING).padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** 字幕区顶部渐隐：往上滚走的字自然淡出，不在面板边上硬切。 */
internal fun Modifier.fadeTopEdge(height: Dp = 24.dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = height.toPx().coerceAtMost(size.height / 3)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, edge / size.height.coerceAtLeast(1f) to Color.Black),
            blendMode = BlendMode.DstIn)
    }
