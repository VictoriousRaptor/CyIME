package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import com.kingzcheung.xime.keyboard.commonSymbolsFor
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.data.SymbolCategory
import com.kingzcheung.xime.data.SymbolData
import com.kingzcheung.xime.settings.KeysConfigHelper
import kotlinx.coroutines.launch

@Composable
fun SymbolKeyboardLayout(
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onKeyPressDown: ((String) -> Unit)? = null,
    /** 删除键松手，同 26 键删除键的 onRelease（松手振动设置）。 */
    onKeyRelease: ((String) -> Unit)? = null,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    /** 分类切换的振动，也供没有按键反馈回调的面板使用。 */
    onHapticFeedback: (() -> Unit)? = null,
    onNumber: () -> Unit,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    specialKeyBackgroundColor: Color = accentColor,
    specialKeyTextColor: Color = textColor,
    onSelectExact: (String) -> Unit = onSelect,
    isFloatingMode: Boolean = false,
) {
    val context = LocalContext.current
    // 常用在首屏；最近使用（LRU）作为紧邻分类，点击符号时置顶记录
    var recentSymbols by remember {
        mutableStateOf(RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_SYMBOLS))
    }
    val textLabel = LocalTextModeLabel.current
    val japanese = LocalKeyboardPunctuation.current?.japanese == true
    // 「按键符号 › 常用符号栏」里自己填的符号、短语排在「常用」页最前面，按原样上屏（默认的「符号」键进的就是这个面板）。
    val ownCommon = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.COMMON).orEmpty()
    val displayCategories = remember(recentSymbols, textLabel, japanese, ownCommon) {
        listOf(SymbolCategory(name = "常用", id = "common",
                symbols = ownCommon + commonSymbolsFor(textLabel).filterNot { it in ownCommon }),
            SymbolCategory(name = "最近", id = "recentSymbols", symbols = recentSymbols)) +
            SymbolData.categories.map { category ->
                if (japanese && category.id == "punctuationSymbols") category.copy(name = "全")
                else category
            }
    }
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = 0, // Common symbols are always the first page, including on a fresh install.
        pageCount = { displayCategories.size }
    )

    // 在「最近」页上点时不立刻重排，离开这一页再刷新，免得符号在手指下面换位置。
    val onRecentPage = displayCategories.getOrNull(pagerState.settledPage)?.id == "recentSymbols"
    LaunchedEffect(onRecentPage) {
        if (!onRecentPage) recentSymbols = RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_SYMBOLS)
    }

    val keySpacing = KeysConfigHelper.getKeyboardKeyConfig().spacingFor("qwerty")
    // The spacing scope hands back a fresh modifier; keep the host's glass inheritance marker,
    // otherwise the panel paints a second keyboard background over the glass.
    val inheritBackground = modifier.inheritsKeyboardBackground()
    // 键缝动效的光层（默认关，关着时是 null）：点符号键打开这一页时从符号键的位置扩开（用户 11-22），
    // 和数字页、常用符号页一样点按也有键缝光。
    val gapLight = rememberKeyGapLight()
    KeyboardKeySpacingScope(modifier.padding(bottom = bottomPaddingDp.dp),
        policy = KeyVisualPolicy.Qwerty, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacing) { bodyModifier ->
    CompositionLocalProvider(LocalKeyVisualPadding provides PaddingValues(
        horizontal = keySpacing.first?.dp ?: 2.dp,
        vertical = keySpacing.second?.dp ?: 2.dp,
    ), LocalKeyGapLight provides gapLight) {
    Box(
        modifier = bodyModifier
            .fillMaxWidth()
            .inheritKeyboardBackground(inheritBackground)
            .keyboardPanelBackground(backgroundColor)
            .padding(bottom = KeyboardGridBottomMargin)
    ) {
    // 最先画 = 在所有键帽底下，只从键缝里露出来。
    KeyGapLightLayer(gapLight, Modifier.matchParentSize())
    Column(modifier = Modifier.fillMaxSize()) {
        // 内容区：符号网格 + HorizontalPager
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(3f)
                .padding(bottom = 4.dp)
        ) {
            val grid = symbolGridGeometry(maxWidth.value, maxHeight.value)
            val columns = grid.columns
            // 和字母键同一个字号：同一基准、同一键帽尺度，也跟「按键字号」走（用户 11-18：符号和按键字号不一样）。
            val symbolFontSize = keyLabelSizeSp("中", KeyboardKeyMetrics.LabelSize.value, maxWidth.value / columns,
                grid.rowHeightDp, LocalDensity.current.fontScale,
                LocalKeyboardInputPreferences.current.keyTextScale).sp
            // 符号网格自成一格：按网格自身格宽算度量（不套主体键宽上限/gutter）
            val gridMetrics = keyVisualMetrics(
                policy = KeyVisualPolicy.Qwerty.copy(maxKeyWidth = Float.MAX_VALUE, minGutter = 0f),
                availableWidthDp = maxWidth.value,
                availableHeightDp = grid.rowHeightDp * 3f,
                columns = columns.toFloat(),
                rows = 3f, verticalInsetDp = 0f,
            )
            CompositionLocalProvider(LocalKeyboardKeyVisualMetrics provides gridMetrics) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val category = displayCategories[page]
                val preserveWidth = category.id == "englishSymbols"

                if (category.symbols.isEmpty()) {
                    // 最近使用为空时的占位提示
                    PanelEmptyHint("暂无最近使用", textColor, Modifier.fillMaxSize())
                } else {
                    // 大分类有数百个符号：只组合可见行，打开或切换分类时不一次创建全部按键。
                    val rows = remember(category.symbols, columns) { category.symbols.chunked(columns) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("symbol-grid:${category.id}"),
                        verticalArrangement = Arrangement.Top
                    ) {
                        items(rows) { rowSymbols ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                rowSymbols.forEach { symbol ->
                                    SymbolButton(
                                        symbol = symbol,
                                        preserveWidth = preserveWidth,
                                        onClick = {
                                            if (onKeyPressDown != null) onKeyPressDown(symbol) else onHapticFeedback?.invoke()
                                            val updated = RecentUsageStore.record(
                                                context, RecentUsageStore.KEY_RECENT_SYMBOLS, symbol
                                            )
                                            if (!onRecentPage) recentSymbols = updated
                                            if (preserveWidth || (category.id == "common" && symbol in ownCommon)) onSelectExact(symbol)
                                            else onSelect(symbol)
                                            // 「选完符号自动返回」（默认关）：符号已排进按键队列，回到打字键盘不影响上屏。
                                            if (com.kingzcheung.xime.settings.SettingsPreferences
                                                    .isSymbolReturnAfterPickEnabled(context)) onBack()
                                        },
                                        modifier = Modifier.weight(1f).height(grid.rowHeightDp.dp).testTag("symbol-key:$symbol"),
                                        fontSize = symbolFontSize,
                                        textColor = textColor,
                                        backgroundColor = keyBgColor,
                                    )
                                }
                                repeat(columns - rowSymbols.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        }

        // 和标准 26 键共用底栏权重；分类只占余下空间，不挤压两侧入口。
        val bottom = QwertyBottomRowWeights.Standard
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (slot in com.kingzcheung.xime.keyboard.modeSlotOrder(LocalKeyboardInputPreferences.current.swapModeKeys)) {
                KeyboardModeKey(
                    slot = slot, page = KeyboardInputPage.SYMBOLS,
                    onKeyPress = { action -> if (action == "abc") onBack() else onNumber() },
                    onKeyPressDown = { action ->
                        if (onKeyPressDown != null) onKeyPressDown(action) else onHapticFeedback?.invoke()
                    },
                    backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                    modifier = Modifier.weight(bottom.mode),
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(bottom.total - 2 * bottom.mode - bottom.enter)
                    .fillMaxHeight()
                    .testTag("symbol-category-strip"),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The rail uses the same footer and key inset as the adjacent actions.
                // Only tab width is capped; a tablet must not get a shorter category row.
                val tabWidth = (maxHeight * 1.1f).coerceIn(48.dp, 88.dp)
                val glass = LocalKeyboardInputPreferences.current.frostedGlass
                // Under glass the rail is a translucent keycap like its neighbours, not a solid bar.
                // A light tint over whatever is below (solid colour, gradient or glass); on a solid
                // background this equals the former lerp(background, text, 0.05).
                val categoryColor = frostedKeyColor(lerp(backgroundColor, textColor, 0.05f), textColor, glass,
                    legacyStateColor = textColor.copy(alpha = 0.05f))
                Row(
                    Modifier.fillMaxSize().padding(scaledKeyVisualPadding())
                        .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                        .background(categoryColor)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    displayCategories.forEachIndexed { index, category ->
                        SymbolCategoryTab(
                            name = category.name,
                            isSelected = index == pagerState.currentPage,
                            onClick = {
                                onHapticFeedback?.invoke()
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            // The rail already paints the fill; tabs only add the selection tint.
                            backgroundColor = Color.Transparent,
                            textColor = textColor,
                            selectedBackgroundColor = textColor.copy(alpha = 0.12f),
                            modifier = Modifier.width(tabWidth).fillMaxHeight()
                                .testTag("symbol-category:${category.id}"),
                        )
                    }
                }
            }

            SwipeableIconKeyButton(
                icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.Backspace),
                onClick = { onSelect("delete") },
                onLongClick = { onSelect("delete") }, distanceDelete = true,
                // 按键音和振动在按下时给，长按连删每一下也给，同 26 键删除键（11-21「符号页删除键没有按键声音」）。
                onPress = { if (onKeyPressDown != null) onKeyPressDown("delete") else onHapticFeedback?.invoke() },
                onRelease = { onKeyRelease?.invoke("delete") },
                // 清空、撤回同其他键盘的删除键（11-05「部分页面的删除键功能缺失」），方向跟删除键设置走。
                followSymbolSwipeDirection = true,
                upFeedback = KeyFlickFeedback.DELETE, swipeUpLabel = "上滑清空",
                downFeedback = KeyFlickFeedback.UNDO, swipeDownLabel = "下滑撤回",
                onSwipeUp = { onSelect("clear_all") }, onSwipeDown = { onSelect("undo_clear") },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(bottom.enter).testTag("symbol-delete")
                    .semantics { contentDescription = "删除" },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
        }

    }
    }
    }
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    backgroundColor: Color,
    preserveWidth: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = KeyboardKeyMetrics.LabelSize,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            )
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(frostedKeyColor(backgroundColor, textColor,
                    LocalKeyboardInputPreferences.current.frostedGlass, pressed = isPressed))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (preserveWidth) symbol else punctuationKeyLabel(symbol),
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            color = textColor,
            fontFamily = AppFonts.keyFontFamily
        )
    }
}

@Composable
private fun SymbolCategoryTab(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    selectedBackgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fill = if (isSelected) selectedBackgroundColor else backgroundColor
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                selected = isSelected
                role = Role.Tab
                onClick { onClick(); true }
            }
            .tolerantClick(interactionSource = interactionSource, onClick = onClick)
            .background(if (pressed) lerp(fill, textColor, .15f) else fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, color = if (isSelected) textColor else textColor.copy(alpha = 0.65f),
            fontSize = 16.sp, maxLines = 1, textAlign = TextAlign.Center,
            fontFamily = AppFonts.keyFontFamily)
        if (isSelected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.55f)
            .height(2.dp).background(textColor))
    }
}
