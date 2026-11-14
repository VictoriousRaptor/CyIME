package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kingzcheung.xime.keyboard.QuickActionLayout
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalInlineQuickActionEditor = staticCompositionLocalOf<InlineQuickActionEditor?> { null }

internal class InlineQuickActionEditor(private val motionScope: CoroutineScope? = null) {
    var active by mutableStateOf(false)
        private set
    var toolbar by mutableStateOf<List<String>>(emptyList())
        private set
    var menu by mutableStateOf<List<String>>(emptyList())
        private set
    var unused by mutableStateOf<List<String>>(emptyList())
        private set
    var dragging by mutableStateOf<String?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var dragBounds by mutableStateOf(Rect.Zero)
        private set
    var saveBounds by mutableStateOf(Rect.Zero)
    var dragFromToolbar by mutableStateOf(false)
        private set
    private var grabOffset = Offset.Zero
    private var original = QuickActionLayout(emptyList(), emptyList())
    private var hidden = emptySet<String>()
    private var dragStartToolbar = emptyList<String>()
    private var dragStartMenu = emptyList<String>()
    private var lastHover: String? = null
    private var toolbarSize = androidx.compose.ui.geometry.Size.Zero
    private var menuSize = androidx.compose.ui.geometry.Size.Zero
    private val bounds = mutableMapOf<String, Rect>()
    private val coordinates = mutableMapOf<String, LayoutCoordinates>()
    private val owners = mutableMapOf<String, Any>()
    private val motions = mutableMapOf<String, QuickActionMotion>()
    private var orderRevision = 0
    var rootCoordinates: LayoutCoordinates? = null
    var toolbarArea = Rect.Zero
    var menuArea = Rect.Zero

    fun start(saved: QuickActionLayout, visibleToolbar: List<String>, visibleMenu: List<String>,
        available: List<String> = emptyList()) {
        original = saved
        toolbar = visibleToolbar
        menu = visibleMenu
        unused = available.distinct().filter { it !in saved.toolbar && it !in saved.menu }
        hidden = (saved.toolbar + saved.menu).toSet() - (toolbar + menu).toSet()
        bounds.clear()
        coordinates.clear()
        owners.clear()
        saveBounds = Rect.Zero
        motions.values.forEach { it.job?.cancel() }
        motions.clear()
        orderRevision = 0
        toolbarSize = androidx.compose.ui.geometry.Size.Zero
        menuSize = androidx.compose.ui.geometry.Size.Zero
        active = true
    }

    /** Hidden entries remain in the editor and rejoin the end of the visible menu. */
    fun toggleVisibility(id: String) {
        if (!active || dragging != null) return
        // 「设置」不能撤掉，否则进不了设置页（他 11-14 定的，和「排列」面板一致）。
        if (id == QuickActionLayout.PINNED && id !in unused) return
        when (id) {
            in menu -> { menu = menu - id; unused = unused + id }
            in unused -> { unused = unused - id; menu = menu + id }
        }
    }

    fun finish(): QuickActionLayout {
        val result = QuickActionLayout.restoreHidden(QuickActionLayout(toolbar, menu), original, hidden)
        cancel()
        return result
    }

    fun cancel() {
        active = false; dragging = null; bounds.clear(); coordinates.clear(); owners.clear()
        motions.values.forEach { it.job?.cancel() }; motions.clear()
    }
    fun register(id: String, rect: Rect) {
        bounds[id] = rect
        if (id in toolbar) toolbarSize = rect.size else if (id in menu) menuSize = rect.size
    }
    fun registerMenuSize(rect: Rect) { if (rect.width > 0 && rect.height > 0) menuSize = rect.size }
    fun register(id: String, layout: LayoutCoordinates, owner: Any) {
        register(id, layout.boundsInRoot()); coordinates[id] = layout; owners[id] = owner
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return
        val target = root.localPositionOf(layout, Offset.Zero)
        val motion = motionFor(id)
        val previous = motion.position
        if (previous != null && previous != target && motion.revision != orderRevision &&
            dragging != null && id != dragging && motionScope != null) {
            val from = previous + motion.offset.value - target
            motion.job?.cancel()
            motion.job = motionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                motion.offset.snapTo(from)
                motion.offset.animateTo(Offset.Zero, tween(220, easing = FastOutSlowInEasing))
            }
        }
        motion.position = target
        motion.revision = orderRevision
    }
    fun unregister(id: String, owner: Any) {
        if (owners[id] === owner) { bounds.remove(id); coordinates.remove(id); owners.remove(id) }
    }
    fun begin(point: Offset) {
        pointer = point
        val entry = bounds.entries.firstOrNull { it.value.contains(point) }
        dragging = entry?.key
        if (entry != null) {
            dragFromToolbar = entry.key in toolbar
            dragStartToolbar = toolbar
            dragStartMenu = menu
            lastHover = null
            val root = rootCoordinates?.takeIf { it.isAttached }
            val item = coordinates[entry.key]?.takeIf { it.isAttached }
            dragBounds = if (root != null && item != null) root.localBoundingBoxOf(item, clipBounds = false) else entry.value
            grabOffset = toLocal(point) - dragBounds.topLeft
            if (dragFromToolbar) {
                val side = minOf(dragBounds.width, dragBounds.height)
                grabOffset = Offset(grabOffset.x * side / dragBounds.width, grabOffset.y * side / dragBounds.height)
                dragBounds = Rect(Offset.Zero, androidx.compose.ui.geometry.Size(side, side))
            }
        }
    }
    fun move(point: Offset) { pointer = point; reorderAt(point) }
    fun refreshHover() { reorderAt(pointer) }
    fun toRoot(point: Offset): Offset = rootCoordinates?.takeIf { it.isAttached }?.localToRoot(point) ?: point
    fun toLocal(point: Offset): Offset = rootCoordinates?.takeIf { it.isAttached }?.let {
        it.localPositionOf(it.findRootCoordinates(), point)
    } ?: point
    fun dragTopLeft(): Offset = toLocal(pointer) - grabOffset
    fun motionFor(id: String): QuickActionMotion = motions.getOrPut(id) { QuickActionMotion() }
    fun cancelDrag() {
        if (dragging != null) { toolbar = dragStartToolbar; menu = dragStartMenu }
        dragging = null
        lastHover = null
    }
    fun drop(point: Offset) {
        if (point != pointer) move(point)
        val onItem = bounds.values.any { it.inflate(minOf(it.width, it.height) * .3f).contains(point) }
        if (!onItem && !toolbarArea.contains(point) && !menuArea.contains(point)) { cancelDrag(); return }
        dragging = null
        lastHover = null
    }
    private fun reorderAt(point: Offset) {
        val source = dragging ?: return
        val target = bounds.entries.firstOrNull { it.value.contains(point) }?.key
            ?: bounds.entries.filter { (_, rect) -> rect.inflate(minOf(rect.width, rect.height) * .3f).contains(point) }
                .minByOrNull { (_, rect) -> (rect.center - point).getDistanceSquared() }?.key
        if (target == source) { lastHover = null; return }
        if (target != null || toolbarArea.contains(point) || menuArea.contains(point)) {
            val inToolbar = if (target != null) target in toolbar else toolbarArea.contains(point)
            if (dragFromToolbar != inToolbar) {
                val size = if (inToolbar) {
                    val raw = toolbarSize.takeIf { it.width > 0f } ?: androidx.compose.ui.geometry.Size(toolbarArea.height, toolbarArea.height)
                    val side = minOf(raw.width, raw.height)
                    androidx.compose.ui.geometry.Size(side, side)
                } else {
                    menuSize.takeIf { it.width > 0f } ?: androidx.compose.ui.geometry.Size(menuArea.width / 4f, menuArea.height / 2f)
                }
                if (size.width > 0f && size.height > 0f) {
                    dragBounds = Rect(Offset.Zero, size)
                    grabOffset = Offset(size.width / 2f, size.height / 2f)
                }
                dragFromToolbar = inToolbar
            }
            // A blank area can accept an item from the other section, but hovering over
            // whitespace in its current section must not repeatedly jump it to the end.
            if (target == null && (source in toolbar) == inToolbar) { lastHover = null; return }
            val hover = target ?: if (inToolbar) "#toolbar" else "#menu"
            if (hover == lastHover) return
            lastHover = hover
            val oldDestination = if (inToolbar) toolbar else menu
            val targetIndex = target?.let { oldDestination.indexOf(it) }?.takeIf { it >= 0 }
            val bar = toolbar.toMutableList().apply { remove(source) }
            val cards = menu.toMutableList().apply { remove(source) }
            val destination = if (inToolbar) bar else cards
            destination.add((targetIndex ?: destination.size).coerceAtMost(destination.size), source)
            if (toolbar != bar || menu != cards) {
                orderRevision++
                toolbar = bar
                menu = cards
            }
        } else lastHover = null
    }
}

internal class QuickActionMotion {
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    var position: Offset? = null
    var revision = 0
    var job: Job? = null
}

@Composable
internal fun Modifier.inlineQuickActionTarget(id: String): Modifier {
    val editor = LocalInlineQuickActionEditor.current ?: return this
    if (!editor.active) return this
    val owner = remember(editor, id) { Any() }
    DisposableEffect(editor, id, owner) { onDispose { editor.unregister(id, owner) } }
    return onGloballyPositioned { editor.register(id, it, owner) }
}

/** Animate the visible content only; the measured slot remains the drag target. */
@Composable
internal fun Modifier.inlineQuickActionMotion(id: String): Modifier {
    val editor = LocalInlineQuickActionEditor.current ?: return this
    if (!editor.active) return this
    val motion = remember(editor, id) { editor.motionFor(id) }
    return graphicsLayer {
        translationX = motion.offset.value.x
        translationY = motion.offset.value.y
    }
}

internal fun Modifier.inlineQuickActionGestures(editor: InlineQuickActionEditor): Modifier =
    onGloballyPositioned { editor.rootCoordinates = it }
        .pointerInput(editor.active) {
            if (editor.active) {
                var point = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { point = editor.toRoot(it); editor.begin(point) },
                    onDrag = { change, _ -> point = editor.toRoot(change.position); editor.move(point); change.consume() },
                    onDragEnd = { editor.drop(point) },
                    onDragCancel = { editor.cancelDrag() },
                )
            }
        }

/** A single overlay follows the pointer across the toolbar, pager and their clipping boundaries. */
@Composable
internal fun BoxScope.InlineQuickActionOverlay(
    editor: InlineQuickActionEditor,
    actions: List<ToolbarAction>,
    showSaveHint: Boolean,
    keyColor: Color,
    textColor: Color,
    accentColor: Color,
) {
    val density = LocalDensity.current
    if (editor.active && showSaveHint && editor.saveBounds != Rect.Zero && editor.dragging == null) {
        val anchor = editor.toLocal(editor.saveBounds.bottomRight)
        val rootWidth = editor.rootCoordinates?.size?.width ?: 0
        Text("点此保存", color = if (textColor.luminance() > .5f) Color.Black else Color.White,
            fontSize = 12.sp, modifier = Modifier
                .align(AbsoluteAlignment.TopRight)
                .absoluteOffset { IntOffset((anchor.x - rootWidth).roundToInt().coerceAtMost(-4),
                    (anchor.y + with(density) { 4.dp.toPx() }).roundToInt()) }
                .zIndex(20f).shadow(4.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)).background(textColor.copy(alpha = .95f))
                .padding(horizontal = 10.dp, vertical = 5.dp).testTag("quick-actions-save-hint"))
    }
    val id = editor.dragging ?: return
    val action = actions.firstOrNull { it.item.id == id } ?: return
    val bounds = editor.dragBounds
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val width = with(density) { bounds.width.toDp() }
    val height = with(density) { bounds.height.toDp() }
    Box(Modifier.offset {
        val position = editor.dragTopLeft()
        IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }.size(width, height).zIndex(21f)
        .shadow(6.dp, RoundedCornerShape(12.dp))
        .clearAndSetSemantics { testTag = "quick-action-drag-preview" }, contentAlignment = Alignment.Center) {
        if (editor.dragFromToolbar) {
            // 和「排列」面板一样：拿起来的是按钮本身，圆角卡片加阴影，不画椭圆、圆圈描边（他 11-14 要求）。
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(keyColor),
                contentAlignment = Alignment.Center) {
                ToolbarButtonIcon(action.item, tint = if (action.active) accentColor else textColor,
                    modifier = Modifier.size(22.dp), active = action.active)
            }
        } else {
            val toggle = (action.item as? ToolbarButtonItem.Builtin)?.button in ToolbarButton.TOGGLES
            CompositionLocalProvider(LocalInlineQuickActionEditor provides null) {
                MenuItemButton(MenuItem(label = action.item.stateLabel(action.active), action = {},
                    toolbarItem = action.item, selected = if (toggle) action.active else null),
                    keyColor, textColor, Modifier.fillMaxSize())
            }
        }
    }
}
