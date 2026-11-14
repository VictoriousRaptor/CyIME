package com.kingzcheung.xime.ui.settings

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.kingzcheung.xime.ui.common.wheelScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import com.kingzcheung.xime.ui.theme.CyMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.japaneseKanaKeys
import com.kingzcheung.xime.ui.keyboard.letterKeyboardFrame
import com.kingzcheung.xime.ui.keyboard.letterKeySpan
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.liquidGlassEdge
import kotlin.math.roundToInt

/** 布局、方案两行卡片的横向位置和打开时的滑入：见 [rememberChoiceRowScroll]。 */
internal class ChoiceRowScroll(val list: LazyListState) {
    var userPicked = false
        private set
    /** 打开时的滑入进度：1 是整排往右错开一张卡片，0 是停到位。 */
    val slide = Animatable(1f)

    fun picked() { userPicked = true }
}

/**
 * 打开时列表已经停在当前选中的那张卡片，整排卡片从右边错开一张卡片的地方往左滑进来（用户 11-11：
 * 九键原来那一下滑动好看，三种布局都要；距离就按那一下，大约一张卡片，不从第一张滑过来，后面卡片再多也不会滑很久）。
 * 打开后自己点了别的卡片，列表才滚到那张。
 */
@Composable
internal fun rememberChoiceRowScroll(selectedIndex: Int, items: Any?): ChoiceRowScroll {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
    val scroll = remember(list) { ChoiceRowScroll(list) }
    LaunchedEffect(scroll) { scroll.slide.animateTo(0f, CyMotion.rowSlideIn()) }
    LaunchedEffect(selectedIndex, items) {
        if (selectedIndex < 0) return@LaunchedEffect
        if (scroll.userPicked) list.animateScrollToItem(selectedIndex) else list.scrollToItem(selectedIndex)
    }
    return scroll
}

/**
 * 给每张卡片用：滑入时往右错开一张卡片宽（加卡片间距），列表边缘照常裁掉；只改绘制位置，
 * 不重新布局。滑入途中点到的就是看到的那张。
 */
internal fun Modifier.choiceRowSlideIn(scroll: ChoiceRowScroll, spacing: Dp = ChoiceRowSpacing): Modifier =
    graphicsLayer {
        val progress = scroll.slide.value
        if (progress != 0f) {
            // 各张都按同一个距离错开（取第一张看得见的卡片宽），整排一起动，不挤不散。
            val card = scroll.list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: size.width
            translationX = progress * (card + spacing.toPx())
        }
    }

internal val ChoiceRowSpacing = 6.dp

/** A view of the layout data, never a second keyboard or an engine session. */
@Composable
internal fun LayoutPreviewChoices(
    layouts: List<InputLayout>, language: InputLanguage, selectedId: String?, tag: String,
    containerColor: Color, contentColor: Color, accentColor: Color,
    modifier: Modifier = Modifier, itemTagPrefix: String = "$tag:option:",
    previewHeightLimit: Dp = 80.dp,
    rowHeight: Dp? = null,
    qwertyPreviewStagger: Float = 0f,
    onManage: (() -> Unit)? = null,
    /** 没有当前方案的布局：标灰，仍可点（点了换方案，由调用方提示）。 */
    unavailableIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
) {
    val revision by KeysConfigHelper.configVersion.collectAsState()
    val evenMergedKeys = rememberKeyboardInputPreferences().evenMergedKeys
    val scroll = rememberChoiceRowScroll(layouts.indexOfFirst { it.id == selectedId }, layouts)
    val list = scroll.list
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (rowHeight != null) "布\n局" else "布局",
            Modifier.then(if (rowHeight != null) Modifier.align(Alignment.CenterVertically) else Modifier.padding(top = 3.dp)),
            color = contentColor.copy(alpha = .75f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium, maxLines = if (rowHeight != null) 2 else 1)
        BoxWithConstraints(Modifier.weight(1f)) {
            val (distributedWidth, managementWidth) = profileCardWidths(maxWidth, layouts.size, onManage != null, fontScale)
            // In a short IME the scheme row must remain visible too. Shrink only
            // the miniature, never the name or the scheme's touch target.
            val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 4.dp
            val previewHeight = rowHeight?.let { (it - labelHeight - 1.dp).coerceAtLeast(24.dp) }
                ?: (distributedWidth * .42f).coerceIn(32.dp, previewHeightLimit.coerceAtLeast(32.dp))
            // A tall row needs wider preview cards, not vertically stretched keyboard keys.
            val width = if (rowHeight == null) distributedWidth else
                (((previewHeight.value - 8f) * 2.15f + 8f).roundToInt().dp)
                    .coerceIn(minOf(112.dp * fontScale, maxWidth), maxWidth)
            val actionWidth = if (rowHeight == null) managementWidth else
                minOf(managementWidth, (width * .72f).coerceAtLeast(minOf(92.dp * fontScale, maxWidth)))
            LazyRow(Modifier.fillMaxWidth().wheelScroll(list).testTag("$tag:list"), state = list,
                horizontalArrangement = Arrangement.spacedBy(ChoiceRowSpacing)) {
                items(layouts, key = { it.id }) { layout ->
                    val chosen = layout.id == selectedId
                    // Custom layouts are observable; do not cache their rows across an edit.
                    val rows = previewRows(layout, language, revision)
                    val custom = CustomKeyboardLayouts.find(layout.id)?.takeIf { it.widths.isNotEmpty() }
                    val widthOf = custom?.let { l -> { key: String -> l.widthOf(key) } }
                    Box(Modifier.choiceRowSlideIn(scroll)) {
                    PreviewChoiceCard(layout.displayName, chosen, width, previewHeight,
                        containerColor, contentColor, accentColor, "$itemTagPrefix${layout.id}", { scroll.picked(); onSelect(layout.id) },
                        unavailable = layout.id in unavailableIds) {
                                if (rows.isEmpty()) Icon(Icons.Outlined.Keyboard, "${layout.displayName}布局", tint = contentColor)
                                else MiniatureKeyboard(rows, layout.kind in setOf(LayoutKind.ALPHABETIC, LayoutKind.MERGED, LayoutKind.ZHUYIN),
                                    contentColor, Modifier.testTag("layout-preview:${layout.id}"), fillKeys = !containerColor.isNearBlack(),
                                    equalMergedKeys = layout.kind == LayoutKind.MERGED && CustomKeyboardLayouts.find(layout.id) == null,
                                    evenMergedKeys = evenMergedKeys && CustomKeyboardLayouts.find(layout.id) != null,
                                    qwertyStagger = qwertyPreviewStagger, widthOf = widthOf)
                    }
                    }
                }
                if (onManage != null) item(key = "layout-management") {
                    Box(Modifier.choiceRowSlideIn(scroll)) {
                    PreviewChoiceCard("布局管理", false, actionWidth, previewHeight, containerColor, contentColor,
                        accentColor, "profile-layout-management", onManage, isAction = true) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(24.dp), tint = contentColor)
                    }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PreviewChoiceCard(label: String, chosen: Boolean, width: Dp, previewHeight: Dp,
    containerColor: Color, contentColor: Color, accentColor: Color, tag: String,
    onSelect: () -> Unit, isAction: Boolean = false, unavailable: Boolean = false,
    preview: @Composable BoxScope.() -> Unit) {
    // 同方案卡片：键盘里开着液态玻璃时，没选中的卡片用细亮边代替灰描边。
    val liquid = !chosen && com.kingzcheung.xime.ui.theme.LiquidGlass.surfacesOn()
    Surface(modifier = if (unavailable) Modifier.alpha(.4f).semantics { stateDescription = "没有当前方案" } else Modifier,
        shape = RoundedCornerShape(14.dp), color = containerColor,
        border = if (liquid) null else BorderStroke(if (chosen) 1.5.dp else 1.dp,
            if (chosen) accentColor else contentColor.copy(alpha = idleBorderAlpha(containerColor)))) {
        Column(Modifier.then(if (liquid) Modifier.liquidGlassEdge(14.dp) else Modifier).width(width).testTag(tag).then(if (isAction) Modifier.clickable(role = Role.Button, onClick = onSelect)
            else Modifier.selectable(chosen, role = Role.RadioButton, onClick = onSelect))) {
            Box(Modifier.fillMaxWidth().height(previewHeight).padding(4.dp), contentAlignment = Alignment.Center) {
                preview()
                if (chosen) Icon(Icons.Default.CheckCircle, "已选中", Modifier.align(Alignment.TopEnd).size(14.dp), tint = accentColor)
            }
            HorizontalDivider(Modifier.testTag("$tag:divider"), color = contentColor.copy(alpha = .22f))
            Row(Modifier.fillMaxWidth()
                .background(if (chosen) chosenFill(containerColor, accentColor) else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, Modifier.weight(1f), color = contentColor,
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun previewRows(layout: InputLayout, language: InputLanguage, @Suppress("UNUSED_PARAMETER") revision: Int): List<List<String>> =
    when (layout.kind) {
        LayoutKind.T9 -> listOf(listOf("分词", "ABC", "DEF"), listOf("GHI", "JKL", "MNO"), listOf("PQRS", "TUV", "WXYZ"))
        LayoutKind.KANA_KEYPAD -> japaneseKanaKeys.take(9).map { it.center.label }.chunked(3)
        LayoutKind.STROKE_KEYPAD -> listOf(listOf("一", "丨", "丿"), listOf("丶", "乛", "*"), listOf("分词", "，", "。"))
        LayoutKind.NUMBER -> (1..9).map(Int::toString).chunked(3)
        else -> KeysConfigHelper.previewKeyRows(layout, language)
    }

@Composable
internal fun MiniatureKeyboard(rows: List<List<String>>, letterLayout: Boolean, contentColor: Color, modifier: Modifier,
    fillKeys: Boolean = true, equalMergedKeys: Boolean = false, qwertyStagger: Float = 0f,
    /** 「合并键均匀排布」：行宽不变，行内按 [letterKeySpan] 分键宽，同键盘。 */
    evenMergedKeys: Boolean = false, widthOf: ((String) -> Float)? = null) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val frame = if (letterLayout) letterKeyboardFrame(rows) else null
    val columns = rows.maxOfOrNull { it.sumOf { key -> if (letterLayout) key.length.coerceAtLeast(1) else 1 } } ?: 1
    // Stable keyboard-shaped reference, scaled uniformly into every phone/tablet card.
    val referenceWidth = columns * (if (letterLayout) 32f else 72f) + (columns - 1) * 4f
    val referenceHeight = rows.size * 48f + (rows.size - 1) * 4f
    val aspect = referenceWidth / referenceHeight
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val height = minOf(maxHeight, maxWidth / aspect)
    Canvas(modifier.size(height * aspect, height).semantics {
        contentDescription = "按键排列：" + rows.joinToString(" / ") { it.joinToString(" ") }
    }) {
        val gap = size.width / referenceWidth * 4f
        val unit = (size.width - gap * (columns - 1)) / columns
        val geometry = frame?.geometry(unit + gap, qwertyStagger)
        val h = (size.height - gap * (rows.size - 1)) / rows.size
        rows.forEachIndexed { r, row ->
            // 单独调过宽度的键在缩略图里也按实际比例画；整排仍归一到原来的格数。
            val customSpans = if (letterLayout && widthOf != null) {
                val nominal = row.sumOf { it.length.coerceAtLeast(1) }.toFloat().coerceAtLeast(0.001f)
                val raw = row.map { widthOf(it).coerceAtLeast(0.05f) }
                val total = raw.sum().coerceAtLeast(0.001f)
                raw.map { it * nominal / total }
            } else null
            val units = customSpans?.sum() ?: row.sumOf { if (letterLayout) it.length.coerceAtLeast(1) else 1 }.toFloat()
            // Typing compacts an editor-only spare cell; the first row still fills the frame.
            val rowUnit = if (frame != null && r == 0) (size.width - gap * (units - 1)) / units else unit
            val rowWidth = units * rowUnit + (units - 1) * gap
            var x = (size.width - rowWidth) / 2
            if (geometry != null) {
                x = when (r) {
                    1 -> geometry.middleRowInsetDp
                    2 -> geometry.outerKeyWeight * (unit + gap)
                    else -> 0f
                }
            }
            val evenSpans = if (customSpans == null && evenMergedKeys && letterLayout) row.map { letterKeySpan(it, true, true) } else null
            row.forEachIndexed { index, key ->
                val span = customSpans?.get(index) ?: if (letterLayout) key.length.coerceAtLeast(1).toFloat() else 1f
                val w = when {
                    equalMergedKeys -> (rowWidth - gap * (row.size - 1)) / row.size
                    evenSpans != null -> (rowWidth - gap * (row.size - 1)) * evenSpans[index] / evenSpans.sum()
                    else -> rowUnit * span + gap * (span - 1)
                }
                val y = r * (h + gap)
                if (fillKeys) drawRoundRect(contentColor.copy(alpha = .10f), Offset(x, y), Size(w, h), CornerRadius(3.dp.toPx()))
                else drawRoundRect(contentColor.copy(alpha = .30f), Offset(x, y), Size(w, h), CornerRadius(3.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx() / 2))
                paint.color = contentColor.copy(alpha = .90f).toArgb()
                paint.textSize = minOf(h * .40f, w / ((if (equalMergedKeys) 2 else key.length) * .65f + .6f), 13.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(key, x + w / 2, y + h / 2 - (paint.ascent() + paint.descent()) / 2, paint)
                x += w + gap
            }
        }
    }
    }
}

/** 近黑底（黑白主题深色）不铺灰：卡片和缩略键盘只描边，选中靠亮描边和对勾。 */
internal fun Color.isNearBlack(): Boolean = alpha > .99f && maxOf(red, green, blue) < .14f

internal fun chosenFill(containerColor: Color, accentColor: Color): Color =
    if (containerColor.isNearBlack()) containerColor else accentColor.copy(alpha = .16f).compositeOver(containerColor)

/** 未选中卡片的边：近黑底上加亮到看得出（与键盘的细白边一致）。 */
internal fun idleBorderAlpha(containerColor: Color): Float = if (containerColor.isNearBlack()) .30f else .12f
