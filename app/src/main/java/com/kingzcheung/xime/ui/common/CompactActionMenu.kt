package com.kingzcheung.xime.ui.common

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.cyPopupEnter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class CompactActionMenuEntry(
    val icon: ImageVector?, val label: String, val tint: Color? = null,
    val enabled: Boolean = true, val tag: String? = null, val onClick: () -> Unit,
)

/** All rectangles are local to the actual panel, never the physical screen. */
internal fun compactMenuBounds(
    viewport: IntSize, anchor: Rect, occupied: List<Rect>, desired: IntSize,
    margin: Float, gap: Float, minScrollableHeight: Float, preferSide: Boolean,
): Rect {
    val inset = min(margin, min(viewport.width, viewport.height) / 4f).coerceAtLeast(0f)
    val safe = Rect(inset, inset, (viewport.width - inset).coerceAtLeast(inset),
        (viewport.height - inset).coerceAtLeast(inset))
    val width = min(desired.width.toFloat(), safe.width).coerceAtLeast(0f)
    val height = min(desired.height.toFloat(), safe.height).coerceAtLeast(0f)
    fun candidate(x: Float, y: Float, h: Float = height): Rect {
        val left = x.coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left))
        val top = y.coerceIn(safe.top, (safe.bottom - h).coerceAtLeast(safe.top))
        return Rect(left, top, left + width, top + h)
    }
    val choices = mutableListOf(
        candidate(anchor.right + gap, anchor.top), candidate(anchor.left - gap - width, anchor.top),
        candidate(anchor.right - width, anchor.bottom + gap), candidate(anchor.right - width, anchor.top - gap - height))
    // Inspect gaps between the visible records before covering another record.
    val xs = (listOf(safe.left, safe.right - width) + occupied.flatMap { listOf(it.right + gap, it.left - gap - width) }).distinct()
    val ys = (listOf(safe.top, safe.bottom - height) + occupied.flatMap { listOf(it.bottom + gap, it.top - gap - height) }).distinct()
    for (x in xs) for (y in ys) choices += candidate(x, y)
    // A narrow single-column panel can scroll the menu below/above its original record.
    val below = (safe.bottom - anchor.bottom - gap).coerceAtMost(height)
    val above = (anchor.top - gap - safe.top).coerceAtMost(height)
    if (below >= minScrollableHeight) choices += candidate(anchor.right - width, anchor.bottom + gap, below)
    if (above >= minScrollableHeight) choices += candidate(anchor.right - width, safe.top, above)
    fun overlap(a: Rect, b: Rect): Float =
        max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    fun covered(rect: Rect) = occupied.sumOf { overlap(rect, it).toDouble() }.toFloat()
    fun distance(rect: Rect): Float {
        val dx = max(0f, max(anchor.left - rect.right, rect.left - anchor.right))
        val dy = max(0f, max(anchor.top - rect.bottom, rect.top - anchor.bottom))
        return dx * dx + dy * dy
    }
    return choices.minWithOrNull(compareBy<Rect>(
        { if (overlap(it, anchor) > .5f) 1 else 0 },
        { if (covered(it) > .5f) 1 else 0 },
        { covered(it) / (it.width * it.height).coerceAtLeast(1f) },
        { height - it.height }, { distance(it) },
        { if (preferSide == (it.right <= anchor.left || it.left >= anchor.right)) 0 else 1 },
        { (it.center - anchor.center).getDistanceSquared() },
    )) ?: safe
}

/** One content-sized surface over a transparent dismissal layer; the list stays in place. */
@Composable
internal fun CompactActionMenu(
    anchor: Rect, entries: List<CompactActionMenuEntry>, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, occupiedBounds: List<Rect> = emptyList(),
    title: String? = null, message: String? = null, preferSide: Boolean = true,
    tag: String = "compact-action-menu", surfaceTag: String = "$tag-surface",
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = CompactMenuStyle.FontSize, lineHeight = CompactMenuStyle.LineHeight))
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current
    val menuText = palette?.text ?: colors.onSurface
    // 输入法窗口没有返回键分发器（系统返回由 IME 自己处理）；无条件调用 BackHandler 会直接崩溃。
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onDismiss)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val insets = with(density) { (CompactMenuStyle.HorizontalPadding * 2).roundToPx() }
        val iconSpace = with(density) { (CompactMenuStyle.IconSize + CompactMenuStyle.ItemGap).roundToPx() }
        val labelWidth = entries.maxOfOrNull { measurer.measure(it.label, style, maxLines = 1).size.width +
            if (it.icon != null) iconSpace else 0 } ?: 0
        val headerWidth = listOfNotNull(title, message).maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
        val width = max(labelWidth + insets, (headerWidth + insets).coerceAtMost(with(density) { 240.dp.roundToPx() }))
            .coerceAtMost((constraints.maxWidth - with(density) { 8.dp.roundToPx() }).coerceAtLeast(1))
        val verticalPadding = with(density) { (CompactMenuStyle.VerticalPadding * 2).roundToPx() }
        val surfacePadding = with(density) { (CompactMenuStyle.SurfacePadding * 2).roundToPx() }
        val minRow = with(density) { CompactMenuStyle.MinRowHeight.roundToPx() }
        fun textHeight(text: String, available: Int) = measurer.measure(text, style,
            constraints = Constraints(maxWidth = available.coerceAtLeast(1))).size.height
        val rowHeights = entries.map {
            max(minRow, textHeight(it.label, width - insets - if (it.icon != null) iconSpace else 0) + verticalPadding)
        }
        val minimumScrollableHeight = (rowHeights.maxOrNull() ?: minRow) + surfacePadding
        val height = surfacePadding + rowHeights.sum() +
            listOfNotNull(title, message).sumOf { textHeight(it, width - insets) + verticalPadding }
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        val desired = IntSize(width, height)
        val placement = remember(viewport, anchor, occupiedBounds, desired, density, minimumScrollableHeight, preferSide) {
            compactMenuBounds(viewport, anchor, occupiedBounds, desired,
                with(density) { 4.dp.toPx() }, with(density) { 6.dp.toPx() },
                minimumScrollableHeight.toFloat(), preferSide)
        }
        Box(Modifier.matchParentSize().testTag("$tag-dismiss").clickable(onClick = onDismiss))
        Layout(content = {
            // 从长按的位置方向淡入放大（统一弹出动效）。
            val origin = TransformOrigin(
                ((anchor.center.x - placement.left) / placement.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                ((anchor.center.y - placement.top) / placement.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
            Surface(Modifier.cyPopupEnter(origin).testTag(tag), shape = CompactMenuStyle.Shape,
                // This overlay covers existing records, so it is opaque; it uses the keyboard palette so
                // it matches the keys of every theme instead of a (possibly white) Material surface.
                color = palette?.key?.copy(alpha = 1f)
                    ?: if (glass.enabled) colors.surface.copy(alpha = 1f) else colors.surfaceContainerHigh,
                shadowElevation = CompactMenuStyle.Elevation, tonalElevation = 0.dp) {
                Column(Modifier.testTag(surfaceTag)
                    .verticalScroll(rememberScrollState()).padding(vertical = CompactMenuStyle.SurfacePadding)) {
                    listOfNotNull(title, message).forEachIndexed { index, text ->
                        Text(text, Modifier.fillMaxWidth().padding(horizontal = CompactMenuStyle.HorizontalPadding,
                            vertical = CompactMenuStyle.VerticalPadding), style = style,
                            color = if (index == 0 && title != null) menuText else menuText.copy(alpha = .7f))
                    }
                    entries.forEach { entry ->
                        CompactMenuRow(entry.label, onClick = { onDismiss(); entry.onClick() },
                            modifier = entry.tag?.let { Modifier.testTag(it) } ?: Modifier,
                            icon = entry.icon, enabled = entry.enabled, tint = entry.tint ?: menuText)
                    }
                }
            }
        }, modifier = Modifier.matchParentSize()) { measurables, constraints ->
            val widthPx = placement.width.roundToInt().coerceAtLeast(1)
            val content = measurables.single().measure(Constraints(minWidth = widthPx, maxWidth = widthPx,
                maxHeight = placement.height.roundToInt().coerceAtLeast(1)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                content.place(placement.left.roundToInt(), placement.top.roundToInt())
            }
        }
    }
}
