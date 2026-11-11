package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.data.KaomojiData
import com.kingzcheung.xime.data.RecentUsageStore
import kotlinx.coroutines.launch
import kotlin.math.ceil

@Composable
internal fun KaomojiKeyboard(onSelect: (String) -> Unit, modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    leadingTabs: @Composable RowScope.() -> Unit = {},
    onCategoryFeedback: (() -> Unit)? = null,
    onDeleteFace: ((String?) -> String?)? = null,
    picked: ArrayDeque<String> = remember { ArrayDeque() },
) {
    val context = LocalContext.current
    var recent by remember { mutableStateOf(RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_KAOMOJI)) }
    // Same as the emoji panel: page 0 is 最近, page i + 1 is category i, in tab order;
    // swiping the grid left/right moves between categories and the tab strip follows.
    val categories = KaomojiData.categories
    val pager = rememberPagerState(initialPage = 1, pageCount = { categories.size + 1 })
    val tabs = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) {
        if (tabs.layoutInfo.visibleItemsInfo.isNotEmpty() &&
            tabs.layoutInfo.visibleItemsInfo.none { it.index == pager.currentPage })
            tabs.animateScrollToItem(pager.currentPage)
    }
    // 在「最近」页上点时不立刻重排，离开这一页再刷新（和表情面板一样）。
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != 0) recent = RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_KAOMOJI)
    }
    Column(modifier.fillMaxSize()) {
        ExpressionPanelBar(textColor, { picked.clear(); onSelect("delete") }, leadingTabs,
            onDeleteFace = onDeleteFace?.let { delete -> {
                val last = picked.lastOrNull()
                val removed = delete(last)
                if (last != null && removed == last) picked.removeLast() else picked.clear()
            } }) {
            LazyRow(Modifier.weight(1f).testTag("kaomoji-categories"), state = tabs,
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                item {
                    ExpressionTab("最近", pager.currentPage == 0, textColor, accentColor, Modifier.testTag("kaomoji-recent")) {
                        onCategoryFeedback?.invoke(); scope.launch { pager.animateScrollToPage(0) }
                    }
                }
                itemsIndexed(categories) { index, item ->
                    ExpressionTab(item.name, pager.currentPage == index + 1, textColor, accentColor, Modifier.testTag("kaomoji-category:$index")) {
                        onCategoryFeedback?.invoke(); scope.launch { pager.animateScrollToPage(index + 1) }
                    }
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("kaomoji-pages")) { page ->
        val faces = if (page == 0) recent else categories[page - 1].faces
        if (faces.isEmpty()) PanelEmptyHint("暂无颜文字记录", textColor, Modifier.fillMaxSize())
        else BoxWithConstraints(Modifier.fillMaxSize()) {
            // Long faces span several cells instead of wrapping onto a second line.
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val style = LocalTextStyle.current.merge(TextStyle(fontSize = 16.sp))
            val widths = remember(faces, density, style) {
                faces.associateWith { measurer.measure(it, style, softWrap = false).size.width }
            }
            val gridWidth = with(density) { (maxWidth - 8.dp).toPx() }
            val gap = with(density) { 4.dp.toPx() }
            val padding = with(density) { 14.dp.toPx() }
            LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize().testTag("kaomoji-grid"),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(faces, key = { it }, span = { face ->
                val cell = (gridWidth - gap * (maxLineSpan - 1)) / maxLineSpan
                val needed = (widths[face] ?: 0) + padding
                GridItemSpan(ceil((needed + gap) / (cell + gap)).toInt().coerceIn(1, maxLineSpan))
            }) { face ->
                Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(KeyShapes.current.radius(8.dp)))
                    .background(textColor.copy(alpha = .08f)).testTag("kaomoji:$face").clickable {
                        val updated = RecentUsageStore.record(context, RecentUsageStore.KEY_RECENT_KAOMOJI, face)
                        if (pager.settledPage != 0) recent = updated
                        if (picked.size >= 32) picked.removeFirst()
                        picked.addLast(face)
                        onSelect(face)
                    }.padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(face, color = textColor, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                }
            }
            }
        }
        }
    }
}
