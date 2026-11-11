package com.kingzcheung.xime.ui.keyboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.data.EmojiCategory
import com.kingzcheung.xime.data.EmojiSkinTone
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.ui.common.CompactMenuStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EmojiKeyboardLayout(
    onEmojiSelect: (String) -> Unit,
    onImageEmojiSelect: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    onHapticFeedback: (() -> Unit)? = null,
    onDeleteFace: ((String?) -> String?)? = null,
    pickedKaomoji: ArrayDeque<String> = remember { ArrayDeque() },
) {
    var faces by remember { mutableStateOf(false) }
    val modeTabs: @Composable RowScope.() -> Unit = {
        ExpressionTab("Emoji", !faces, textColor, accentColor, Modifier.testTag("expression-emoji")) {
            onHapticFeedback?.invoke(); faces = false
        }
        ExpressionTab("颜文字", faces, textColor, accentColor, Modifier.testTag("expression-kaomoji")) {
            onHapticFeedback?.invoke(); faces = true
        }
    }
    Box(modifier.fillMaxSize().keyboardPanelBackground(backgroundColor).padding(bottom = bottomPaddingDp.dp)) {
        if (faces) KaomojiKeyboard(onEmojiSelect, textColor = textColor, accentColor = accentColor,
            leadingTabs = modeTabs, onCategoryFeedback = onHapticFeedback, onDeleteFace = onDeleteFace,
            picked = pickedKaomoji)
        else EmojiContent(onEmojiSelect, onImageEmojiSelect, backgroundColor, textColor,
            accentColor, onHapticFeedback, modeTabs)
    }
}

@Composable
private fun EmojiContent(
    onEmojiSelect: (String) -> Unit,
    onImageEmojiSelect: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    onHapticFeedback: (() -> Unit)?,
    leadingTabs: @Composable RowScope.() -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = remember { ClipboardManager.getInstance(context) }
    val allCategories by ExtensionManager.emojiCategoriesFlow.collectAsStateWithLifecycle()
    var recentEmojis by remember {
        mutableStateOf(RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_EMOJIS))
    }
    // One category strip for built-ins and plugins, in exactly the pager's order.
    // No separate plugin-group bar or bottom navigation consuming content height.
    val categories = remember(allCategories, recentEmojis) {
        listOf(EmojiCategory(name = "最近使用", icon = "🕘", emojis = recentEmojis)) +
            allCategories.filter { !it.isPlugin } + allCategories.filter { it.isPlugin }
    }
    val pager = rememberPagerState(initialPage = if (categories.size > 1) 1 else 0,
        pageCount = { categories.size })
    var initialCategoryChosen by remember { mutableStateOf(categories.size > 1) }
    val tabs = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(categories.size) {
        if (!initialCategoryChosen && categories.size > 1) {
            pager.scrollToPage(1)
            initialCategoryChosen = true
        }
    }
    LaunchedEffect(pager.currentPage, categories.size) {
        if (tabs.layoutInfo.visibleItemsInfo.isNotEmpty() &&
            tabs.layoutInfo.visibleItemsInfo.none { it.index == pager.currentPage })
            tabs.animateScrollToItem(pager.currentPage.coerceAtMost(categories.lastIndex))
    }
    // 长按可加肤色的表情：在格子上方弹出原色 + 5 种肤色；点按仍是原表情。
    var tonePicker by remember { mutableStateOf<SkinTonePicker?>(null) }
    val panelCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(pager.currentPage) { tonePicker = null }
    // 正在「最近使用」页上点时只记下来，不立刻重排，免得表情在手指下面换位置；离开这一页再刷新。
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != 0) recentEmojis = RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_EMOJIS)
    }
    val recordRecent: (String) -> Unit = { emoji ->
        val updated = RecentUsageStore.record(context, RecentUsageStore.KEY_RECENT_EMOJIS, emoji)
        if (pager.settledPage != 0) recentEmojis = updated
    }
    Column(Modifier.fillMaxSize().onPlaced { panelCoords[0] = it }) {
        ExpressionPanelBar(textColor, { onEmojiSelect("delete") }, leadingTabs) {
            LazyRow(Modifier.weight(1f).testTag("emoji-categories"), state = tabs,
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(categories) { index, category ->
                    val samePlugin = category.isPlugin && categories.count { it.isPlugin && it.pluginId == category.pluginId } > 1
                    val label = if (category.isPlugin) {
                        if (samePlugin) category.name else category.pluginIcon?.text ?: category.icon
                    } else category.icon
                    // Asset-only plugin icons keep their image; the category name is
                    // always exposed for accessibility and multi-category plugins.
                    val asset = category.pluginIcon?.assetName.takeIf { category.isPlugin && !samePlugin }
                    if (asset != null) {
                        Box(Modifier.size(32.dp).testTag("emoji-category:$index").clip(RoundedCornerShape(8.dp))
                            .background(if (index == pager.currentPage) accentColor.copy(alpha = .22f) else Color.Transparent)
                            .tolerantClick { onHapticFeedback?.invoke(); scope.launch { pager.animateScrollToPage(index) } }
                            .padding(4.dp)) {
                            AsyncImage(asset, category.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    } else ExpressionTab(label, index == pager.currentPage, textColor, accentColor,
                        Modifier.testTag("emoji-category:$index"), description = category.name) {
                        onHapticFeedback?.invoke()
                        scope.launch { pager.animateScrollToPage(index) }
                    }
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("emoji-pages")) { page ->
            val category = categories[page]
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                val grid = emojiGridGeometry(maxWidth.value, maxHeight.value)
                val pluginItems = category.emojiItems
                if (category.isPlugin && pluginItems != null) {
                    val hasImages = pluginItems.any { it.imageUrl != null }
                    val columns = if (category.layoutColumns > 0) category.layoutColumns
                        else if (hasImages) (maxWidth.value / 64f).toInt().coerceAtLeast(1) else grid.columns
                    val itemHeight = if (category.layoutItemHeightDp > 0) category.layoutItemHeightDp
                        else if (hasImages) 60 else 40
                    val rows = remember(pluginItems, columns) { pluginItems.chunked(columns) }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (hasImages) 6.dp else 2.dp)) {
                        items(rows, key = { it.firstOrNull()?.id ?: it.hashCode() }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { item ->
                                    PluginEmojiButton(item, onClick = {
                                        val imageUrl = item.imageUrl
                                        when {
                                            imageUrl != null && onImageEmojiSelect != null -> onImageEmojiSelect(imageUrl)
                                            imageUrl != null -> {
                                                val success = clipboardManager.copyImageToSystemClipboard(imageUrl, item.text)
                                                Toast.makeText(context, if (success) "已复制表情，可粘贴发送" else "复制失败", Toast.LENGTH_SHORT).show()
                                            }
                                            else -> onEmojiSelect(item.insertText ?: item.text)
                                        }
                                    }, defaultHeightDp = itemHeight, backgroundColor = backgroundColor,
                                        textColor = textColor, modifier = Modifier.weight(1f))
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else if (category.emojis.isEmpty()) {
                    PanelEmptyHint("暂无表情记录", textColor, Modifier.fillMaxSize())
                } else {
                    val rows = remember(category.emojis, grid.columns) { category.emojis.chunked(grid.columns) }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(rows) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                row.forEach { emoji ->
                                    val tones = remember(emoji) { EmojiSkinTone.variants(emoji) }
                                    EmojiButton(emoji, {
                                        recordRecent(emoji)
                                        onEmojiSelect(emoji)
                                    }, Modifier.weight(1f).height(grid.rowHeightDp.dp), grid.rowHeightDp,
                                        onLongClick = tones.takeIf { it.isNotEmpty() }?.let { { cell: LayoutCoordinates ->
                                            val panel = panelCoords[0]
                                            if (panel != null && panel.isAttached && cell.isAttached) {
                                                onHapticFeedback?.invoke()
                                                tonePicker = SkinTonePicker(tones, panel.localBoundingBoxOf(cell, false))
                                            }
                                        } })
                                }
                                repeat(grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
    tonePicker?.let { picker ->
        SkinTonePickerOverlay(picker, onPick = { emoji ->
            tonePicker = null
            recordRecent(emoji)
            onEmojiSelect(emoji)
        }, onDismiss = { tonePicker = null })
    }
}

private data class SkinTonePicker(val variants: List<String>, val anchor: Rect)

/** 肤色条：盖在表情面板上，点条外任何地方只关闭，不会顺手打出下面的表情。 */
@Composable
private fun SkinTonePickerOverlay(picker: SkinTonePicker, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = keyboardCardColors()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardPadding = 4.dp
        val fitWidth = (maxWidth - cardPadding * 2) / picker.variants.size
        val itemSize = with(density) { picker.anchor.height.toDp() }.coerceIn(40.dp, 56.dp).coerceAtMost(fitWidth)
        val anchor = picker.anchor
        Layout(content = {
            Box(Modifier.fillMaxSize().testTag("emoji-skin-tone-dismiss")
                .pointerInput(Unit) { detectTapGestures { onDismiss() } })
            Row(Modifier.shadow(CompactMenuStyle.Elevation, CompactMenuStyle.Shape)
                .clip(CompactMenuStyle.Shape).background(colors.card)
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag("emoji-skin-tones").padding(cardPadding)) {
                picker.variants.forEachIndexed { index, variant ->
                    Box(Modifier.size(itemSize).clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(variant) }.testTag("emoji-skin-tone:$index"),
                        contentAlignment = Alignment.Center) {
                        Text(variant, fontSize = (itemSize.value * 0.55f).coerceIn(20f, 32f).sp,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val scrim = measurables[0].measure(Constraints.fixed(width, height))
            val card = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = 4.dp.roundToPx()
            val x = (anchor.center.x - card.width / 2f).roundToInt()
                .coerceIn(0, (width - card.width).coerceAtLeast(0))
            val above = anchor.top - gap - card.height
            val y = (if (above >= 0f) above else anchor.bottom + gap).roundToInt()
                .coerceIn(0, (height - card.height).coerceAtLeast(0))
            layout(width, height) {
                scrim.place(0, 0)
                card.place(x, y)
            }
        }
    }
}

@Composable
fun EmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeightDp: Float? = null,
    /** 非空时长按回调，带上格子坐标（肤色条据此定位）。 */
    onLongClick: ((LayoutCoordinates) -> Unit)? = null,
) {
    val cell = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier = modifier
            .then(if (cellHeightDp == null) Modifier.aspectRatio(1f) else Modifier)
            .then(if (onLongClick != null) Modifier.onPlaced { cell[0] = it } else Modifier)
            .clip(RoundedCornerShape(4.dp))
            .tolerantClick(onLongClick = onLongClick?.let { callback -> { cell[0]?.let(callback) } },
                onClick = onClick)
            .keyGlow(Modifier.clip(RoundedCornerShape(4.dp)), liquid = false),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = (cellHeightDp?.let { (it * 0.45f).coerceIn(22f, 36f) } ?: 22f).sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PluginEmojiButton(
    emojiItem: PluginResultItem,
    onClick: () -> Unit,
    defaultHeightDp: Int = 40,
    backgroundColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLightTheme =
        (backgroundColor.red + backgroundColor.green + backgroundColor.blue) / 3f > 0.5f
    val buttonBackgroundColor = if (isLightTheme) Color.White.copy(alpha = 0.8f)
    else Color.LightGray.copy(alpha = 0.15f)
    val contentColor = if (isLightTheme) Color.Black else textColor

    Box(
        modifier = modifier
            .height(defaultHeightDp.dp)
            .then(
                if (emojiItem.imageUrl != null) Modifier.aspectRatio(1f)
                else Modifier.fillMaxWidth()
            )
            .clip(RoundedCornerShape(4.dp))
            .background(buttonBackgroundColor)
            .tolerantClick(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (emojiItem.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(emojiItem.imageUrl)
                    // 高频网格滚动场景：关闭渐显动画，降低加载突发期的重绘压力
                    .crossfade(false)
                    .build(),
                contentDescription = emojiItem.text,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = emojiItem.text,
                fontSize = 12.sp,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
