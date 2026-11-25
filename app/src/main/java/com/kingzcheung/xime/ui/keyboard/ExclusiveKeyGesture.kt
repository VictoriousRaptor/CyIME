package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.stats.TouchStats
import com.kingzcheung.xime.stats.TouchStatsData.Outcome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 一次按下只触发一个输入分支；长按动作到时执行，滑动与菜单松手确认。 */
internal data class KeyGestureActions(
    val text: String,
    val onTap: () -> Unit,
    val onPress: () -> Unit,
    val onRelease: () -> Unit,
    val onPreview: (SwipeState) -> Unit,
    val upText: String? = null,
    val downText: String? = null,
    val onUp: ((String) -> Unit)? = null,
    val onDown: ((String) -> Unit)? = null,
    val leftText: String? = null,
    val onLeft: (() -> Unit)? = null,
    val rightText: String? = null,
    val onRight: (() -> Unit)? = null,
    val horizontalThresholdDp: Float = 50f,
    val verticalThresholdDp: Float = 50f,
    val reserveHorizontalSwipe: Boolean = false,
    val longPressItems: List<String> = emptyList(),
    val longPressDrawableIds: List<Int> = emptyList(),
    val keyWidth: Float = 1f,
    /**
     * 长按选项条在这个键自己坐标里的位置（左边相对按键左边），和气泡画的同一份
     * （[KeyboardKeyMetrics.longPressMenuCells]）。三项以上时手指在哪一格上就选哪一格，长按到点时就选手指下面那格；
     * 没给时按键宽均分（旧算法）。参数是项数、是不是图标项。
     */
    val longPressMenuCells: ((Int, Boolean) -> LongPressMenuCells)? = null,
    /**
     * 长按到点时要排在手指底下、默认选中的项，按先后（26 键字母键：先大写、再小写，见 [letterMenuCenterItems]）。
     * 第一项放在手指下那格并选中，后面的挨着放，其余项按原顺序从左往右填剩下的格（[arrangeLongPressMenu]）。空的照旧。
     */
    val longPressCenterItems: List<String> = emptyList(),
    /** 文字长按菜单原样排、不按左右半边翻转（九键「J K L 5 j k l」数字居中），默认项是 [longPressCenterItems] 第一项。 */
    val longPressFixedOrder: Boolean = false,
    /**
     * 文字长按菜单怎么排、每格多宽（[KeyboardKeyMetrics.longPressMenuOrder]、[KeyboardKeyMetrics.longPressTextMenuCells]），
     * 和气泡画的同一份。参数：原始项、离手指从近到远的项、是否原样排。给了就按「默认项正对按键、手指挪一格换一格」选；
     * 图标菜单和没给时照旧（[longPressMenuCells]）。
     */
    val longPressTextMenu: ((List<String>, List<String>, Boolean) -> LongPressTextMenu)? = null,
    val onLongPress: (() -> Unit)? = null,
    val onLongPressSelect: ((String) -> Unit)? = null,
    val onLongPressFeedback: () -> Unit = {},
    val longPressTimeoutMillis: Long? = null,
    /**
     * [onLongPress] 到点立刻输入的那个字（「长按输入」的角标、九键数字）：同一刻气泡换成它，按住一直显示，松手消失。
     * 不受按压气泡开关限制，和长按菜单一样是选择反馈。
     */
    val longPressBubbleText: String? = null,
    val blockUpSwipe: Boolean = false,
    val blockDownSwipe: Boolean = false,
    val inlineVerticalPreview: Boolean = false,
    val inlineHorizontalPreview: Boolean = false,
    val upPreviewText: String? = upText,
    val downPreviewText: String? = downText,
    val upPreviewFromTop: Boolean = true,
    val downPreviewFromTop: Boolean = false,
    /**
     * Four-way flick keys (nine-key flick letters, Japanese kana): the direction follows the finger's
     * current dominant axis, so the finger may re-aim without returning to the centre, and the release
     * point decides. Released within the threshold is a tap; past it in a direction without a choice
     * enters nothing.
     */
    val freeDirection: Boolean = false,
    /**
     * 滑到哪个键按哪个键（用户 11-14）：手指滑出这个键以后，每次移动把位置（这个键里的坐标）交给它，
     * 松手调 [slideRelease] 按手指下的键。null 时照旧：滑出键外松手不出字。
     * 只给没有任何方向手势的键（滑行和「上下滑输入符号」都关着时的字母键等），见 SwipeableKeyButton。
     */
    val slideOver: ((Offset) -> Unit)? = null,
    val slideRelease: (() -> Unit)? = null,
    val slideCancel: () -> Unit = {},
)

internal fun KeyGestureActions.withLetterSwipes(
    letter: String, bindings: Map<String, String>, execute: (String) -> Unit,
): KeyGestureActions {
    val left = bindings["$letter.left"]
    val right = bindings["$letter.right"]
    val up = bindings["$letter.up"]
    val down = bindings["$letter.down"]
    if (left == null && right == null && up == null && down == null) return this
    fun label(value: String?) = com.kingzcheung.xime.settings.LetterSwipeBindings.label(value)
    return copy(leftText = label(left) ?: leftText, rightText = label(right) ?: rightText,
        upText = label(up) ?: upText, downText = label(down) ?: downText,
        upPreviewText = label(up) ?: upPreviewText, downPreviewText = label(down) ?: downPreviewText,
        onLeft = left?.let { { execute(it) } } ?: onLeft,
        onRight = right?.let { { execute(it) } } ?: onRight,
        onUp = up?.let { action -> { _: String -> execute(action) } } ?: onUp,
        onDown = down?.let { action -> { _: String -> execute(action) } } ?: onDown,
        blockUpSwipe = up == null && blockUpSwipe, blockDownSwipe = down == null && blockDownSwipe,
        inlineHorizontalPreview = inlineHorizontalPreview || left != null || right != null,
        reserveHorizontalSwipe = reserveHorizontalSwipe || left != null || right != null)
}

/**
 * 「按键符号」给单字母键设的上下滑动作，键面角标改写动作名（如 Z X C V 预设的撤销、剪切、复制、粘贴）。
 * 条件和 SwipeableKeyButton 套用 [withLetterSwipes] 时一样；返回（上方角标, 下方角标），没设的方向为 null。
 */
@androidx.compose.runtime.Composable
internal fun letterSwipeCornerLabels(key: String): Pair<String?, String?> {
    val preferences = LocalKeyboardInputPreferences.current
    val actions = LocalKeyboardInputActions.current
    if (key.length != 1 || actions.onLetterSwipeAction == null || letterSwipeAvailable(LocalFourteenKeyLayout.current,
            LocalCustomLayout.current?.mergedGroups?.isNotEmpty() == true)) return null to null
    val bindings = if (actions.englishLetters) preferences.englishLetterSwipes else preferences.chineseLetterSwipes
    return com.kingzcheung.xime.settings.LetterSwipeBindings.cornerLabels(bindings, key, preferences.reverseSymbolSwipe)
}

/**
 * 这组手势按多少毫秒算长按；没有长按时是 null。[systemTimeoutMillis] 传系统的长按时限。
 * 手势本身和键缝动效的长按进度都用这一个数，进度走满正好是长按生效的那一刻。
 */
internal fun KeyGestureActions.longPressDelayMillis(systemTimeoutMillis: Long): Long? =
    if (longPressItems.isEmpty() && onLongPress == null) null
    else longPressTimeoutMillis ?: if (longPressItems.isNotEmpty()) 400L else systemTimeoutMillis

/**
 * [reserveDragForGlide]：这个键上的拖动留给滑行（九键滑行、26 键英文/中文滑行），键自己不做上下滑、拂动。
 * 拖动后滑行没接管（没有消费这根手指）时，抬手仍按点按结算（见 [glideDriftIsTap]）。
 */
internal suspend fun PointerInputScope.detectExclusiveKeyGestures(
    reserveDragForGlide: Boolean = false,
    /** 拖动留给的是 26 键字母滑行（不是九键）：没成滑行时按字母滑行的接管距离判点按，见 [glideDriftIsTap]。 */
    letterGlide: Boolean = false,
    currentActions: () -> KeyGestureActions,
) = coroutineScope {
    val gestureScope = this
    val horizontalCancelThreshold = 60.dp.toPx()
    // 快速连打同一个键（小鹤 uu）时两只拇指会交叠：前一下还没抬，后一下已按在同一键上。
    // 后按下的手指结束前一下（按它当时的位置结算），再接着算自己的一下；不等所有手指抬起，否则这一下被吞。
    var handover: PointerInputChange? = null
    awaitPointerEventScope { while (true) {
        val down = handover ?: awaitNewDown()
        handover = null
        val actions = currentActions()
        val swipeThreshold = actions.verticalThresholdDp.dp.toPx()
        val horizontalThreshold = actions.horizontalThresholdDp.dp.toPx()
        down.consume()
        // 按键落点统计（只有自用包在记，见 [TouchStats]）：落点在按下回调之前给，回调里报了键名才有这一下的号。
        TouchStats.landing(down.position.x, down.position.y, size.width, size.height, density, down.uptimeMillis)
        actions.onPress()
        val touch = TouchStats.landed()
        // 这一下最后的结果，只给统计用。长按动作已经执行、已经提前出字的，手指之后被上层拿走也不改。
        var outcome = Outcome.NONE_LOST
        var lastAt = down.uptimeMillis
        actions.onPreview(SwipeState(isPressed = true, pressedText = actions.text))
        var longPressed = false
        var moved = false
        var crossedSwipeThreshold = false
        // Once a direction owns the drag, sideways drift cannot undo its preview.
        // Returning along that axis to the key centre releases the direction.
        var verticalDrag: Boolean? = null
        // A free flick keeps the touch once it has left the centre, even after coming back.
        var flickClaimed = false
        // 滑到哪个键按哪个键：手指已经滑出过这个键（[KeyGestureActions.slideOver]）。
        var slid = false
        var selectedIndex = 0
        var items = actions.longPressItems
        // 三项以上的长按菜单（西欧字母、拼音声调，七八个变音）：每格按画出来的格宽算，起点是手指下的那格。
        // 以前整条挤在一个键宽里，八项时手指挪八分之一个键就换一格（用户 11-15）。两项（上下角标）照旧。
        val cellsFor = actions.longPressMenuCells?.takeIf { items.size > 2 }
        var menuCells: LongPressMenuCells? = null
        // 文字长按菜单（[KeyGestureActions.longPressTextMenu]）：默认项第几格、每格多宽、长按到点时手指在哪。
        var menuAnchor = 0
        var menuCellWidth = 0f
        var menuAnchorX = 0f
        var lastX = down.position.x
        var lastY = down.position.y
        val longPressDelay = actions.longPressDelayMillis(viewConfiguration.longPressTimeoutMillis)
        val longPressJob = if (longPressDelay != null) gestureScope.launch {
            delay(longPressDelay)
            longPressed = true
            actions.onLongPressFeedback()
            if (items.isNotEmpty()) {
                val textMenu = actions.longPressTextMenu?.takeIf { items.size > 1 && actions.longPressDrawableIds.isEmpty() }
                    ?.invoke(items, actions.longPressCenterItems, actions.longPressFixedOrder)
                if (textMenu != null) {
                    // 默认项正对按键、先选中；之后按手指从这一刻起横着挪了几格选（11-24 长按方案）。
                    items = textMenu.items
                    menuAnchor = textMenu.anchor
                    menuCellWidth = textMenu.cellWidth
                    menuAnchorX = lastX
                    selectedIndex = menuAnchor
                } else {
                menuCells = cellsFor?.invoke(items.size, actions.longPressDrawableIds.isNotEmpty())
                menuCells?.let { selectedIndex = KeyboardKeyMetrics.longPressMenuIndex(lastX, it, items.size) }
                }
                if (textMenu == null && actions.longPressCenterItems.isNotEmpty()) {
                    // 两项（[k, K]）没有分格、从第一项起选：大写排第一就是默认选中，往右滑选小写。
                    items = arrangeLongPressMenu(items, actions.longPressCenterItems, selectedIndex)
                    selectedIndex = items.indexOf(actions.longPressCenterItems.first()).coerceAtLeast(0)
                }
                actions.onPreview(SwipeState(isPressed = true, isLongPress = true,
                    longPressItems = items, selectedLongPressIndex = selectedIndex,
                    longPressDrawableIds = actions.longPressDrawableIds, longPressAnchorIndex = menuAnchor))
            } else {
                outcome = Outcome.LONG
                actions.onLongPress?.invoke()
                actions.longPressBubbleText?.takeIf { it.isNotEmpty() }?.let { shown ->
                    actions.onPreview(SwipeState(isPressed = true, isLongPress = true, longPressItems = listOf(shown)))
                }
            }
        } else null
        // 后按下的键先抬手出字时，这一下已经提前按点按出过了：之后这根手指只等抬起，不再出字。
        var settled = false
        val press = KeyPressOrder.begin {
            // 这一下还是点按（没到长按、没滑出方向、没滑到别的键）才提前出；别的照旧等它自己抬手。
            // 留给滑行的键挪过了触摸阈值时，按手指现在的位置照抬手的算法判（[glideDriftIsTap]）：后一根手指按下时
            // 滑行已经改跟后一根，这一根不会再变成滑行。
            if (!settled && !longPressed && !slid && !crossedSwipeThreshold && verticalDrag == null && !flickClaimed &&
                if (reserveDragForGlide && moved) glideDriftIsTap(down.position, Offset(lastX, lastY), size.width.toFloat(),
                    size.height.toFloat(), viewConfiguration.touchSlop, letterGlide)
                else actions.freeDirection || abs(lastX - down.position.x) < horizontalCancelThreshold) {
                settled = true
                outcome = Outcome.TAP_EARLY
                longPressJob?.cancel()
                TouchStats.early(touch)
                actions.onTap()
                actions.onPreview(SwipeState())
            }
        }
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                lastAt = change.uptimeMillis
                if (settled) {
                    // 字已经出过。同一个键上又按下一根手指时照旧换手，否则等这根手指抬起。
                    change.consume()
                    handover = event.changes.firstOrNull { it.id != down.id && it.changedToDown() && !it.isConsumed }
                    if (!change.pressed || handover != null) break
                    continue
                }
                if (change.isConsumed) {
                    if (outcome != Outcome.LONG) outcome = Outcome.NONE_TAKEN
                    break
                }
                val dx = change.position.x - down.position.x
                lastX = change.position.x
                lastY = change.position.y
                val dy = change.position.y - down.position.y
                if (!longPressed && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                    moved = true
                    longPressJob?.cancel()
                    // 留给滑行的键以前一过触摸阈值就放手，可滑行要走一段才接管（九键 DigitStroke.claimed、
                    // 26 键 GlideTracker.claimed）：快打时手指一抖、没到滑行又不算点按，这一下就吞了（用户 11-10）。
                    // 现在接着跟这根手指：滑行接管时会消费事件，下面照常放手；没接管就在抬手时按点按算。
                }
                val slop = viewConfiguration.touchSlop
                // 滑到哪个键按哪个键：一出这个键就交给键盘找手指下的键，之后回到本键也由它按（松手按本键）。
                if (actions.slideOver != null && !longPressed && !reserveDragForGlide) {
                    val p = change.position
                    if (slid || p.x < 0f || p.y < 0f || p.x > size.width || p.y > size.height) {
                        slid = true
                        actions.slideOver.invoke(p)
                    }
                }
                // Back at the key's own centre also means the centre choice, for presses that began
                // off-centre; a fixed tolerance, so wide tablet keys still flick sideways.
                val atKeyCentre = actions.freeDirection && moved &&
                    abs(change.position.x - size.width / 2f) <= slop && abs(change.position.y - size.height / 2f) <= slop
                if (reserveDragForGlide) {
                    // 留给滑行的拖动不认方向：不上下滑、不拂动，只等滑行接管或抬手。
                } else if (actions.freeDirection) {
                    if (!longPressed) verticalDrag = when {
                        maxOf(abs(dx), abs(dy)) <= slop || atKeyCentre -> null
                        abs(dy) >= abs(dx) -> true
                        else -> false
                    }
                    if (verticalDrag != null) flickClaimed = true
                } else if ((verticalDrag == true && abs(dy) <= slop) || (verticalDrag == false && abs(dx) <= slop))
                    verticalDrag = null
                if (verticalDrag == null && !longPressed && !actions.freeDirection && !reserveDragForGlide) {
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.1f &&
                        (actions.onUp != null || actions.onDown != null || actions.blockUpSwipe || actions.blockDownSwipe)) verticalDrag = true
                    else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.1f &&
                        (actions.onLeft != null || actions.onRight != null)) verticalDrag = false
                }
                val up = verticalDrag == true && dy < -swipeThreshold && actions.onUp != null
                val downSwipe = verticalDrag == true && dy > swipeThreshold && actions.onDown != null
                val left = verticalDrag == false && dx < -horizontalThreshold && actions.onLeft != null
                val right = verticalDrag == false && dx > horizontalThreshold && actions.onRight != null
                val blockedUp = verticalDrag == true && actions.blockUpSwipe && dy < -swipeThreshold
                val blockedDown = verticalDrag == true && actions.blockDownSwipe && dy > swipeThreshold
                // Free flicks are decided where the finger lifts, so leaving the centre is not sticky.
                val outsideCentre = !reserveDragForGlide && actions.freeDirection && !atKeyCentre && maxOf(abs(dx), abs(dy)) > swipeThreshold
                crossedSwipeThreshold = if (actions.freeDirection) outsideCentre
                    else crossedSwipeThreshold || up || downSwipe || left || right || blockedUp || blockedDown
                if (longPressed && items.isNotEmpty()) {
                    selectedIndex = if (menuCellWidth > 0f)
                        KeyboardKeyMetrics.longPressMenuStep(menuAnchor, change.position.x - menuAnchorX, menuCellWidth, items.size)
                    else menuCells?.let { KeyboardKeyMetrics.longPressMenuIndex(change.position.x, it, items.size) }
                        ?: longPressIndexByKeyWidth(dx, actions.keyWidth, items.size)
                    actions.onPreview(SwipeState(isPressed = true, isLongPress = true,
                        longPressItems = items, selectedLongPressIndex = selectedIndex,
                        longPressDrawableIds = actions.longPressDrawableIds, longPressAnchorIndex = menuAnchor))
                } else if (!longPressed) {
                    val hint = if (up) actions.upText else if (downSwipe) actions.downText else if (left) actions.leftText else if (right) actions.rightText else null
                    actions.onPreview(SwipeState(isSwiping = hint != null, swipeText = hint,
                        isSwipeDown = downSwipe, isPressed = !moved, pressedText = actions.text,
                        keyFlick = (if (actions.inlineVerticalPreview && verticalDrag == true) keyFlickPreview(
                            0f, dy, viewConfiguration.touchSlop, swipeThreshold,
                            actions.upPreviewText.takeIf { actions.onUp != null && !actions.blockUpSwipe },
                            actions.downPreviewText.takeIf { actions.onDown != null && !actions.blockDownSwipe },
                            actions.upPreviewFromTop, actions.downPreviewFromTop,
                        ) else null) ?: if (actions.inlineHorizontalPreview && verticalDrag == false) horizontalKeyFlickPreview(
                            dx, 0f, viewConfiguration.touchSlop, horizontalThreshold,
                            actions.leftText.takeIf { actions.onLeft != null },
                            actions.rightText.takeIf { actions.onRight != null },
                        ) else null))
                }
                // 长按菜单正在选时不换手，保持原来的选择。
                val nextDown = if (longPressed) null
                    else event.changes.firstOrNull { it.id != down.id && it.changedToDown() && !it.isConsumed }
                if (!change.pressed || nextDown != null) {
                    change.consume()
                    handover = nextDown
                    KeyPressOrder.settleOlderThan(press)
                    val tap = if (nextDown != null) Outcome.TAP_HANDOVER else Outcome.TAP
                    outcome = when {
                        longPressed -> { items.getOrNull(selectedIndex)?.let { actions.onLongPressSelect?.invoke(it) }; Outcome.LONG }
                        slid -> { actions.slideRelease?.invoke(); Outcome.SLIDE }
                        up -> { actions.onUp?.invoke(actions.upText.orEmpty()); Outcome.SWIPE }
                        downSwipe -> { actions.onDown?.invoke(actions.downText.orEmpty()); Outcome.SWIPE }
                        left -> { actions.onLeft?.invoke(); Outcome.SWIPE }
                        right -> { actions.onRight?.invoke(); Outcome.SWIPE }
                        reserveDragForGlide && moved ->
                            if (glideDriftIsTap(down.position, change.position, size.width.toFloat(), size.height.toFloat(),
                                    viewConfiguration.touchSlop, letterGlide)) { actions.onTap(); tap } else Outcome.NONE_DRIFT
                        !crossedSwipeThreshold && (actions.freeDirection || abs(dx) < horizontalCancelThreshold) -> { actions.onTap(); tap }
                        // 没出字：滑过了阈值（那个方向没有动作），或者横着拖出了取消的距离。
                        crossedSwipeThreshold -> Outcome.NONE_BLOCKED
                        else -> Outcome.NONE_FAR
                    }
                    break
                }
                // Reserve paired-letter drags before the whole-keyboard cursor's
                // activation distance, even when the user sets a longer threshold.
                if (slid || longPressed || crossedSwipeThreshold || verticalDrag != null || flickClaimed || actions.reserveHorizontalSwipe && !reserveDragForGlide &&
                    abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.1f) change.consume()
                else {
                    // 父层光标/滚动手势可能在 Main 后半段消费；取消后不得补出普通字符。
                    val finalChange = awaitPointerEvent(PointerEventPass.Final)
                        .changes.firstOrNull { it.id == down.id }
                    if (finalChange == null || finalChange.isConsumed) {
                        if (finalChange != null) outcome = Outcome.NONE_TAKEN
                        break
                    }
                }
            }
        } finally {
            KeyPressOrder.end(press)
            longPressJob?.cancel()
            if (slid) actions.slideCancel()
            TouchStats.finish(touch, outcome, lastX - down.position.x, lastY - down.position.y, density, lastAt)
            actions.onRelease()
            actions.onPreview(SwipeState())
        }
    } }
}

/**
 * 把 [center] 里的项排到 [fingerCell] 附近：第一项正好在 [fingerCell]，后面的按离它近的格先右后左放；
 * 其余项保持原来的先后，从左往右填空着的格。[center] 里没出现在 [items] 的项跳过；排不出来时原样返回。
 * 例：o O ō ó ǒ ò ô õ ö ø，手指在第 8 格、center = [O, o] → ō ó ǒ ò ô õ ö ø O o（群友 11-23：长按离手指最近的是大小写字母）。
 */
internal fun arrangeLongPressMenu(items: List<String>, center: List<String>, fingerCell: Int): List<String> {
    val wanted = center.filter { it in items }.distinct()
    if (wanted.isEmpty() || items.size != items.distinct().size) return items
    val n = items.size
    val slots = arrayOfNulls<String>(n)
    var placed = 0
    val start = fingerCell.coerceIn(0, n - 1)
    for (item in wanted) {
        val cell = if (placed == 0) start else (1 until n).asSequence()
            .flatMap { d -> sequenceOf(start + d, start - d) }.firstOrNull { it in 0 until n && slots[it] == null } ?: return items
        slots[cell] = item
        placed++
    }
    val rest = items.filter { it !in wanted }.iterator()
    for (i in 0 until n) if (slots[i] == null) slots[i] = rest.next()
    return slots.map { it!! }
}

/** 文字长按菜单排好的样子：从左到右的项、默认项第几格、每格多宽（像素）。 */
internal data class LongPressTextMenu(val items: List<String>, val anchor: Int, val cellWidth: Float)

/** 旧算法：整条按键宽均分，从第一项起（两项的上下角标还用它，手感不变）。 */
internal fun longPressIndexByKeyWidth(dx: Float, keyWidth: Float, count: Int): Int {
    val itemWidth = (keyWidth / count).coerceAtLeast(1f)
    return ((dx / itemWidth) + if (count > 1) 0.5f else 0f).toInt().coerceIn(0, count - 1)
}

/**
 * 留给滑行的键上手指挪过了触摸阈值、滑行却没接管，抬手算不算点了这个键：
 * 抬手点还在这个键上（各边放宽一个触摸阈值），或者离按下点不到滑行接管要走的距离
 * （[DigitStroke.CLAIM_KEY_FRACTION] 个键的短边；快打时按在键边往隔壁抖一下也算这一键）。
 * 26 键字母滑行（[letterGlide]）接管要横竖合起来走满 [GlideTracker.MIN_SPAN_KEYS] 个键（宽、高各按键宽、键高算），
 * 比九键的 0.6 个短边远，竖着更远：以前两条线中间那一段（快打时手指一抖出键 0.6～0.75 个键）既不是滑行也不算点按，
 * 这一下就吞了（用户 11-11「打两个字第二个字被吞」）。现在没到字母滑行的接管距离都算点按。
 * 都不是（拖到左栏、功能键或拖远了又没成滑行）就不出字，和以前滑出键外取消一样。
 */
internal fun glideDriftIsTap(down: Offset, up: Offset, keyWidth: Float, keyHeight: Float, slop: Float,
                             letterGlide: Boolean = false): Boolean {
    val inside = up.x >= -slop && up.x <= keyWidth + slop && up.y >= -slop && up.y <= keyHeight + slop
    val drift = up - down
    val belowLetterClaim = letterGlide && keyWidth > 0f && keyHeight > 0f &&
        kotlin.math.hypot(drift.x / keyWidth, drift.y / keyHeight) < com.kingzcheung.xime.glide.GlideTracker.MIN_SPAN_KEYS
    return inside || belowLetterClaim || drift.getDistance() < minOf(keyWidth, keyHeight) * DigitStroke.CLAIM_KEY_FRACTION
}

/** 这个键上新按下的一根手指；已经结算过、还按着的手指不算。 */
private suspend fun AwaitPointerEventScope.awaitNewDown(): PointerInputChange {
    while (true) {
        awaitPointerEvent().changes.firstOrNull { it.changedToDown() && !it.isConsumed }?.let { return it }
    }
}
