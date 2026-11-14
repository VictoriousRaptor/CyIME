package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kingzcheung.xime.ui.common.CompactMenuStyle
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette

/**
 * 翻译栏的「源 ⇄ 目标」和工具栏长按「翻译」弹出的语言列表：样子和语言键长按的菜单一样
 * （同一套颜色、圆角、选中行的底色和对勾），只是选项是插件给的一串文字。
 */

/** 工具栏长按列表开着：工具栏按住两秒换位置的手势让开，不在选语言时把按钮拖走。 */
internal object ToolbarPressMenuGate {
    @Volatile var open: Boolean = false
}

/**
 * 按住滑动时手指指着第几行（屏幕坐标）。和语言键一样：还没进过列表就在外面松手，什么都不选；
 * 进过以后横着滑出去，留着上一次指着的那行；竖着滑出上下边，算最近的那行。
 */
internal fun optionMenuSelection(rows: List<Rect>, pointer: Offset, previous: Int?): Int? {
    if (rows.isEmpty() || !pointer.x.isFinite() || !pointer.y.isFinite()) return null
    val hit = rows.indexOfFirst { pointer.x >= it.left && pointer.x < it.right && pointer.y >= it.top && pointer.y < it.bottom }
    if (hit >= 0) return hit
    val prev = previous?.takeIf { it in rows.indices } ?: return null
    val left = rows.minOf { it.left }
    val right = rows.maxOf { it.right }
    if (pointer.x < left || pointer.x >= right) return prev
    return if (pointer.y < rows.minOf { it.top }) 0 else if (pointer.y >= rows.maxOf { it.bottom }) rows.lastIndex else prev
}

/** 列表放在锚点（按钮、标签）正下方，左右不出屏；下面放不下就往上挪到放得下。 */
internal class BelowAnchorPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        anchorBounds.bottom.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

private val OPTION_ROW_HEIGHT = 40.dp
private val OPTION_TITLE_HEIGHT = 28.dp

/**
 * 一张选项卡片。[highlighted] 是按住滑动时手指指着的行（没有就看 [selected]）；
 * [onRowBounds] 报每行的屏幕位置，给按住滑动命中用；[onChoose] 非空时点一行就选它。
 */
@Composable
internal fun OptionMenuCard(
    title: String,
    options: List<String>,
    selected: String?,
    highlighted: Int? = null,
    width: Dp = 148.dp,
    onRowBounds: ((Int, Rect) -> Unit)? = null,
    onChoose: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    // 弹窗是另一个窗口，后面没有实时模糊：按不透明画，盖住下面按键的字。
    val colors = languageMenuColors(LocalKeyboardPalette.current, MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary, glass, blurBehind = false)
    val accent = colors.accent
    val selectedBackground = lerp(colors.surface, accent.copy(alpha = 1f), 0.22f)
    val focusIndex = highlighted ?: options.indexOf(selected).takeIf { it >= 0 }
    Surface(
        shape = CompactMenuStyle.Shape, tonalElevation = 0.dp, shadowElevation = CompactMenuStyle.Elevation,
        color = colors.surface, contentColor = colors.text,
        border = if (glass.enabled) BorderStroke(1.dp, colors.text.copy(alpha = 0.14f)) else null,
        modifier = modifier.width(width).testTag("option-menu"),
    ) {
        Column(Modifier.padding(6.dp)) {
            if (title.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(OPTION_TITLE_HEIGHT), contentAlignment = Alignment.Center) {
                    Text(title, fontSize = CompactMenuStyle.FontSize, lineHeight = CompactMenuStyle.LineHeight,
                        textAlign = TextAlign.Center, color = colors.secondary)
                }
            }
            options.forEachIndexed { index, option ->
                val isFocus = index == focusIndex
                val isSelected = option == selected
                Box(
                    Modifier.fillMaxWidth().height(OPTION_ROW_HEIGHT)
                        .testTag("option-menu-row:$option")
                        .semantics { this.selected = isSelected; contentDescription = "选择$option" }
                        .then(if (onRowBounds != null) Modifier.onGloballyPositioned {
                            onRowBounds(index, Rect(it.positionOnScreen(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
                        } else Modifier)
                        .clip(CompactMenuStyle.Shape)
                        .background(if (isFocus) selectedBackground else Color.Transparent)
                        .then(if (isFocus) Modifier.border(1.dp, accent.copy(alpha = 0.32f), CompactMenuStyle.Shape) else Modifier)
                        .then(if (onChoose != null) Modifier.clickable(interactionSource = null, indication = null) {
                            onChoose(option)
                        } else Modifier)
                        .padding(horizontal = CompactMenuStyle.HorizontalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = colors.text,
                        modifier = Modifier.align(Alignment.CenterStart).size(CompactMenuStyle.IconSize))
                    Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        fontSize = CompactMenuStyle.FontSize, lineHeight = CompactMenuStyle.LineHeight,
                        textAlign = TextAlign.Center, color = colors.text)
                }
            }
        }
    }
}

/** 点一下弹出、点一项就选、点外面就关的列表（翻译栏的源语言、目标语言）。 */
@Composable
internal fun OptionMenuPopup(
    title: String,
    options: List<String>,
    selected: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val position = remember { BelowAnchorPosition() }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true,
            clippingEnabled = false),
    ) {
        // 弹窗是另一个窗口：沿用键盘的密度和字号
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
            OptionMenuCard(title, options, selected, onChoose = onChoose)
        }
    }
}

/** 工具栏长按列表的状态：开着没有、插件给的选项、手指指着哪行、各行的屏幕位置。 */
internal class ToolbarPressMenuState {
    var open by androidx.compose.runtime.mutableStateOf(false)
    var options by androidx.compose.runtime.mutableStateOf<com.kingzcheung.xime.keyboard.PressMenuOptions?>(null)
    var highlighted by androidx.compose.runtime.mutableStateOf<Int?>(null)
    val rows = mutableMapOf<Int, Rect>()
    var origin = Offset.Zero

    fun rowList(): List<Rect> {
        val count = options?.options?.size ?: return emptyList()
        return (0 until count).map { rows[it] ?: return emptyList() }
    }
}

/**
 * 工具栏按钮带长按列表时的手势：点按照常；按住到长按时间弹出列表，不松手滑到一项再松手就选它，
 * 没进过列表就松手什么都不选。按住后先滑动了算横着翻工具栏，交给外面。
 */
internal fun Modifier.toolbarPressMenu(
    menu: () -> com.kingzcheung.xime.keyboard.ToolbarPressMenu?,
    state: ToolbarPressMenuState,
    source: androidx.compose.foundation.interaction.MutableInteractionSource,
    scope: kotlinx.coroutines.CoroutineScope,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
): Modifier = this
    .onGloballyPositioned { state.origin = it.positionOnScreen() }
    // 键不随每次重组换：工具栏重组（候选、音量变了）时正在按的手势不能被打断
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
            source.tryEmit(press)
            // true：松手（点按）；false：滑走了或被别人拿走；null：按到了长按时间
            val tap: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var result: Boolean? = null
                while (result == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    result = when {
                        change == null -> false
                        !change.pressed -> { change.consume(); true }
                        change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                        else -> null
                    }
                }
                result
            }
            when (tap) {
                true -> { source.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Release(press)); onClick() }
                false -> source.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press))
                null -> {
                    val menu = menu() ?: run {
                        source.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press))
                        return@awaitEachGesture
                    }
                    ToolbarPressMenuGate.open = true
                    onLongPress()
                    state.options = null
                    state.highlighted = null
                    state.rows.clear()
                    state.open = true
                    val loading = scope.launch { state.options = runCatching { menu.load() }.getOrNull() }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            state.highlighted = optionMenuSelection(state.rowList(), state.origin + change.position, state.highlighted)
                            if (!change.pressed) break
                        }
                        val options = state.options
                        val chosen = state.highlighted?.let { options?.options?.getOrNull(it) }
                        if (options != null && chosen != null && chosen != options.selected) menu.onChoose(options.key, chosen)
                    } finally {
                        loading.cancel()
                        state.open = false
                        ToolbarPressMenuGate.open = false
                        source.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Release(press))
                    }
                }
            }
        }
    }

/** 长按列表本身：插件的选项回来以后画在按钮下面，不接触摸（选哪行由按钮那里的手势算）。 */
@Composable
internal fun ToolbarPressMenuPopup(state: ToolbarPressMenuState) {
    val options = state.options
    if (!state.open || options == null || options.options.isEmpty()) return
    val density = LocalDensity.current
    val position = remember { BelowAnchorPosition() }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
            clippingEnabled = false),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
            OptionMenuCard(options.title, options.options, options.selected, highlighted = state.highlighted,
                onRowBounds = { index, bounds -> state.rows[index] = bounds })
        }
    }
}
