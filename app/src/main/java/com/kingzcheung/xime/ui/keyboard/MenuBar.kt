package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.QuickActionLayout
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.WidthFull
import androidx.compose.material.icons.twotone.WidthNormal
import androidx.compose.material.icons.twotone.BrightnessAuto
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.LightMode
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.Rotate90DegreesCcw
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SettingsOverscan
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Mic
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import com.kingzcheung.xime.ui.settings.SettingsRoutes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

internal fun menuAppearanceLabel(mode: Int): String = when (mode) {
    0 -> "浅色模式"
    1 -> "深色模式"
    else -> "跟随系统"
}

data class MenuItem(
    val icon: Painter? = null,
    val label: String,
    val action: () -> Unit,
    val textIcon: String? = null,
    val currentState: String? = null,
    val id: String = label,
    /** 非空即开关项：开启时反色填充。 */
    val selected: Boolean? = null,
    val toolbarItem: ToolbarButtonItem? = null,
    /** 长按打开设置里的对应项；没有对应设置时为 null，长按不响应。 */
    val onLongClick: (() -> Unit)? = null,
    val hidden: Boolean = false,
)

data class MenuBarState(
    val isVisible: Boolean,
    val isDarkTheme: Boolean,
    val darkMode: Int = 2,
    val backgroundColor: Color,
    val keyBgColor: Color = Color.White,
    val keyTextColor: Color = Color(0xFF202124),
    val isFloatingMode: Boolean = false,
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    /** 英文（ASCII）模式下标点固定半角，全角／半角入口不适用。 */
    val isAsciiMode: Boolean = false,
)

/** 菜单与工具栏共用一组快捷入口；开关项开启时反色填充，点一下即切换且菜单保持打开。 */
@Composable
fun MenuBar(
    state: MenuBarState,
    actions: List<ToolbarAction>,
    modifier: Modifier = Modifier,
    hiddenActions: List<ToolbarAction> = emptyList(),
) {
    if (!state.isVisible) return
    val textColor = state.keyTextColor
    val configuration = LocalConfiguration.current
    val isLandscape = !state.isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val inlineEditor = LocalInlineQuickActionEditor.current
    val menuItems = (actions.map { it to false } + hiddenActions.map { it to true }).map { (action, hidden) ->
        val toggle = (action.item as? ToolbarButtonItem.Builtin)?.button in ToolbarButton.TOGGLES
        MenuItem(label = action.item.stateLabel(action.active), action = action.onClick, id = action.item.id,
            currentState = if (toggle) (if (action.active) "已开启" else "已关闭") else null,
            selected = if (toggle) action.active else null, toolbarItem = action.item, onLongClick = action.onLongClick,
            hidden = hidden)
    } + if (actions.isEmpty() && hiddenActions.isEmpty() && inlineEditor?.active == true)
        listOf(MenuItem(label = "拖动到这里", id = "#empty-menu", action = {})) else emptyList()
    if (menuItems.isEmpty()) {
        Box(modifier.fillMaxSize().keyboardPanelBackground(state.backgroundColor))
        return
    }
    KeyboardPanelGrid(menuItems, isLandscape, textColor, "menu-pages",
        modifier.fillMaxWidth().onGloballyPositioned {
            inlineEditor?.menuArea = it.boundsInRoot()
        }.keyboardPanelBackground(state.backgroundColor),
        preferSinglePage = true) { item, cellModifier ->
        MenuItemButton(item, state.keyBgColor, textColor,
            cellModifier.testTag("menu-item:${item.id}"), isLandscape)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MenuItemButton(
    item: MenuItem,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val inlineEditor = LocalInlineQuickActionEditor.current
    val on = item.selected == true
    // 开了玻璃，格子和按键是同一种玻璃键帽（同一个透明度，透出后面）；原来一直是不透明的键色，
    // 玻璃键盘上一整块实心格子（用户 11-09）。
    // 开启的开关用文字色作底、取反色作字，任何主题下都能一眼看出；玻璃上也留一点透。
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val fill = when {
        on -> if (glass.enabled) textColor.copy(alpha = MENU_ON_GLASS_ALPHA) else textColor
        else -> frostedKeyColor(bgColor, textColor, glass)
    }
    val content = if (on) (if (textColor.luminance() > .5f) Color.Black else Color.White) else textColor
    Box(modifier.onGloballyPositioned { inlineEditor?.registerMenuSize(it.boundsInRoot()) }
        .then(if (item.toolbarItem != null && !item.hidden) Modifier.inlineQuickActionTarget(item.id) else Modifier)
        .combinedClickable(enabled = inlineEditor?.active != true, onLongClick = item.onLongClick) { item.action() }
        .semantics {
            item.currentState?.let { stateDescription = it }
            item.selected?.let { selected = it }
        }) {
    Box(Modifier.fillMaxSize()
            .then(if (item.toolbarItem != null && !item.hidden) Modifier.inlineQuickActionMotion(item.id) else Modifier)
            .testTag("menu-visual:${item.id}")
            .graphicsLayer { alpha = if (inlineEditor?.dragging == item.id) 0f else 1f }) {
    Column(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { alpha = if (item.hidden) .58f else 1f }
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .visualMaterial(VisualStyles.current, 12.dp, level = com.kingzcheung.xime.ui.theme.MaterialLevel.RAISED)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (item.toolbarItem != null) {
            ToolbarButtonIcon(item = item.toolbarItem, tint = content.copy(alpha = if (on) 1f else 0.7f),
                modifier = Modifier.size(26.dp), active = on)
        } else if (item.textIcon != null) {
            Text(
                text = item.textIcon,
                color = content.copy(alpha = 0.7f),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else if (item.icon != null) {
            Icon(
                painter = item.icon,
                contentDescription = item.label,
                tint = content.copy(alpha = 0.7f),
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            color = content,
            fontSize = 12.sp,
            lineHeight = if (isLandscape) 13.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (inlineEditor?.active == true && item.toolbarItem != null && (item.hidden || item.id != QuickActionLayout.PINNED)) {
        val label = if (item.hidden) "添加${item.label}" else "隐藏${item.label}"
        Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(40.dp)
            .clickable(enabled = inlineEditor.dragging == null, onClickLabel = label) {
                inlineEditor.toggleVisibility(item.id)
            }.semantics { contentDescription = label }.testTag("quick-action-visibility:${item.id}"),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(15.dp).clip(CircleShape)
                .background(if (item.hidden) Color(0xFF2478F8) else Color(0xFFF83C3C))
                .border(1.25.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (item.hidden) Icons.Default.Add else Icons.Default.Remove,
                    contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
    }
    }
}

/** 玻璃上开启的格子：反色底留一点透，白字黑字对它都在 10:1 以上。 */
internal const val MENU_ON_GLASS_ALPHA = .86f
