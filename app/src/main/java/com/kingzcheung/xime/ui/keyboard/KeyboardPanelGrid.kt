package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.kingzcheung.xime.ui.common.wheelPages

/**
 * 横着划过了触摸阈值、而这一边又翻不了页时，吃掉后面的移动：格子的点击在松手时看到被消费就不触发。
 * 以前在第一页往右划、或者在没压到右边缘的格子上往左划，手指没离开格子，松手就算点了一下（11-03 声音振动）。
 */
@Composable
private fun Modifier.swallowDeadHorizontalDrag(rightDead: () -> Boolean, leftDead: () -> Boolean): Modifier {
    // 判断放在越过阈值的那一刻读：拖动中翻页会改变能不能往回翻，不能拿它当 key 重启手势（会打断正在翻的页）。
    val right by androidx.compose.runtime.rememberUpdatedState(rightDead)
    val left by androidx.compose.runtime.rememberUpdatedState(leftDead)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            var swallowing = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                if (!swallowing) {
                    val dx = change.position.x - down.position.x
                    val dy = kotlin.math.abs(change.position.y - down.position.y)
                    if (kotlin.math.abs(dx) >= slop && kotlin.math.abs(dx) > dy &&
                        ((dx > 0f && right()) || (dx < 0f && left()))) swallowing = true
                }
                if (swallowing) change.consume()
                if (!change.pressed) return@awaitEachGesture
            }
        }
    }
}

/** 菜单和方案共享受面板高度约束的网格；低高度时分页，卡片不能越界挤压下一行。 */
@Composable
internal fun <T> KeyboardPanelGrid(
    items: List<T>, isLandscape: Boolean, textColor: Color, pagerTag: String,
    modifier: Modifier = Modifier, compactCards: Boolean = false, preferSinglePage: Boolean = false,
    content: @Composable (T, Modifier) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier.padding(horizontal = if (isLandscape) 16.dp else 12.dp, vertical = 8.dp)) {
        val twoRowModes = compactCards && items.size > 2 && maxHeight >= 240.dp
        // Include actual CJK font metrics, not just the requested 14sp line height.
        val minimumRowHeight = (36 + (if (preferSinglePage && LocalDensity.current.fontScale <= 1.3f) 18 else 32) * LocalDensity.current.fontScale).dp
        val lowWidePanel = maxWidth >= 560.dp && maxHeight < minimumRowHeight * 2 + 24.dp
        val menuColumns = ((maxWidth.value + 8f) / (64f * LocalDensity.current.fontScale + 8f))
            .toInt().coerceIn(1, if (lowWidePanel) 8 else 4)
        val columns = minOf(items.size, if (twoRowModes) 2 else if (compactCards) 4 else menuColumns)
        val spacing = 8.dp
        val indicatorHeight = 16.dp
        val rows = if (twoRowModes) 2 else if (items.size > columns &&
            maxHeight - (if (preferSinglePage && items.size <= columns * 2) 0.dp else indicatorHeight) >= minimumRowHeight * 2 + spacing) 2 else 1
        val pages = items.chunked(rows * columns)
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val pageIndicatorHeight = if (pages.size == 1) 0.dp else indicatorHeight
        val gridHeight = if (compactCards) minOf(maxHeight,
            maxOf(120.dp, minimumRowHeight) * rows + spacing * (rows - 1) + pageIndicatorHeight) else minOf(maxHeight,
            maxOf(104.dp, minimumRowHeight) * rows + spacing * (rows - 1) + pageIndicatorHeight)
        val gridWidth = if (compactCards) minOf(maxWidth, 180.dp * columns + spacing * (columns - 1)) else minOf(maxWidth, 144.dp * columns + spacing * (columns - 1))
        // 划不动的方向（第一页往右、最后一页往左）：这一划什么也不做，也不能在松手时算成点了格子。
        // 最后一页再往左划不退出菜单（11-10：翻到最后一页多划一下，什么都没点菜单就没了）；退出用返回键。
        // 手势放在整块面板上，平板上从网格两边的空白起手也一样。
        Box(Modifier.fillMaxSize()
            .swallowDeadHorizontalDrag(rightDead = { !pagerState.canScrollBackward },
                leftDead = { !pagerState.canScrollForward })) {
        Column(Modifier.align(Alignment.Center).width(gridWidth).height(gridHeight),
            horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalPager(pagerState, pageSpacing = spacing, modifier = Modifier.fillMaxWidth().weight(1f).wheelPages(pagerState).testTag(pagerTag)) { page ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                    repeat(rows) { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            repeat(columns) { column ->
                                val cellModifier = Modifier.weight(1f).fillMaxHeight()
                                val item = pages[page].getOrNull(row * columns + column)
                                if (item == null) Spacer(cellModifier) else content(item, cellModifier)
                            }
                        }
                    }
                }
            }
            Row(Modifier.height(pageIndicatorHeight), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (pages.size > 1) repeat(pages.size) { index ->
                    Box(Modifier.size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                        .clip(CircleShape).background(if (index == pagerState.currentPage) textColor else textColor.copy(alpha = 0.3f)))
                }
            }
        }
        }
    }
}
