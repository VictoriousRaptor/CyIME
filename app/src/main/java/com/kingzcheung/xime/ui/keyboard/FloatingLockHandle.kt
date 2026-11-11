package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 系统栏 inset 相对窗口，卡片坐标相对宿主；宿主已经避开的空间只扣一次。 */
@Composable
internal fun floatingControlViewport(width: Float, height: Float, origin: Offset, bottomInset: Float): ResizeRect {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val root = LocalView.current.rootView
    return ResizeRect(
        (bars.getLeft(density, direction) - origin.x).coerceAtLeast(0f),
        (bars.getTop(density) - origin.y).coerceAtLeast(0f),
        minOf(width, (root.width.takeIf { it > 0 }?.toFloat() ?: width) - origin.x - bars.getRight(density, direction)),
        minOf(height - bottomInset, (root.height.takeIf { it > 0 }?.toFloat() ?: height) - origin.y - bars.getBottom(density)),
    )
}

/**
 * 调节界面里悬浮卡片上方居中的旋转手柄。上方放不下（卡片顶边贴着屏幕顶）时挪到卡片下方，下方也放不下才收进卡片里；
 * 手柄随卡片一起转，靠边时收进可点范围。
 */
internal fun floatingRotateHandleRect(card: ResizeRect, degrees: Float, viewport: ResizeRect, density: Float): ResizeRect {
    val size = minOf(FLOATING_ROTATE_HANDLE_SIZE_DP * density, viewport.width, viewport.height).coerceAtLeast(1f)
    val gap = FLOATING_ROTATE_HANDLE_GAP_DP * density
    val above = card.top - gap - size >= viewport.top
    val below = !above && card.bottom + gap + size <= viewport.bottom
    val x = if (above || below) card.centerX else card.centerX + 72f * density + size / 2
    val y = when {
        above -> card.top - gap - size / 2
        below -> card.bottom + gap + size / 2
        else -> card.top + 42f * density
    }
    val (cx, cy) = rotatePoint(x, y, card.centerX, card.centerY, degrees)
    val left = (cx - size / 2).coerceIn(viewport.left, maxOf(viewport.left, viewport.right - size))
    val top = (cy - size / 2).coerceIn(viewport.top, maxOf(viewport.top, viewport.bottom - size))
    return ResizeRect(left, top, left + size, top + size)
}

internal fun Modifier.atFloatingControl(rect: ResizeRect, density: androidx.compose.ui.unit.Density): Modifier =
    absoluteOffset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })

/** 没锁时按住底部拖动条这么久锁定（沿用最初长按锁定的 1 秒）。 */
internal const val FLOATING_LOCK_HOLD_MS = 1000
/** 锁定后按住底部拖动条这么久解锁（用户 11-08：长按 0.5 秒）。 */
internal const val FLOATING_UNLOCK_HOLD_MS = 500
/** 按下后先等这么久才开始画进度：拖动一般在这之前就过了触摸阈值，拖的时候边框不闪。算在长按时间里。 */
internal const val FLOATING_HOLD_QUIET_MS = 120
/** 没满就松手时，进度从满原路退回到零要这么久（按已走的比例缩短）。 */
private const val FLOATING_LOCK_RETRACT_MS = 260
/** 满了切换成功后，整条进度淡掉要这么久。 */
private const val FLOATING_LOCK_FADE_MS = 240
/** 边框进度条的粗细。 */
private val FloatingLockSweepWidth = 3.dp

internal fun floatingLockHoldMs(locked: Boolean): Int = if (locked) FLOATING_UNLOCK_HOLD_MS else FLOATING_LOCK_HOLD_MS

/**
 * 底部拖动条中间的标记：没锁时是短横条；锁定后换成一把小锁（用户 11-08：下方用一个小的解锁图标）。
 * 长按的进度画在卡片边框上（[floatingLockOutline]），这里不画。
 * 小锁比短横条高得多，只放在最下面那段 [lockBandDp] 高的拖动条里，不往上伸进键帽下的留白：
 * 手机横屏数字键盘时锁的上半截压在底排按键下面那条上，看着和键区重叠（用户 11-10 截图）。条太矮时锁按比例缩小，上下各留 3dp。
 */
@Composable
internal fun FloatingFooterIndicator(
    locked: Boolean,
    color: Color,
    lockBandDp: Float = Float.MAX_VALUE,
    /** 锁定时有人拖：小锁左右抖（[FloatingLockNudge]）；只在绘制时读。 */
    nudge: FloatingLockNudge? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(FLOATING_DRAG_INDICATOR_WIDTH_DP.dp, 4.dp).testTag("floating-drag-indicator"))
        Canvas(Modifier.fillMaxSize()) {
            if (!locked) {
                val halfBar = FLOATING_DRAG_INDICATOR_WIDTH_DP.dp.toPx() / 2f
                val halfHeight = 2.dp.toPx()
                drawRoundRect(color, Offset(center.x - halfBar, center.y - halfHeight), Size(halfBar * 2, halfHeight * 2),
                    CornerRadius(halfHeight))
                return@Canvas
            }
            val band = minOf(lockBandDp.dp.toPx(), size.height)
            val (fraction, offset) = floatingLockGlyphPlacement(band / 1.dp.toPx())
            val unit = fraction * 1.dp.toPx()
            val cx = center.x + (nudge?.shakeDp() ?: 0f) * 1.dp.toPx()
            val cy = size.height - offset * 1.dp.toPx()
            val stroke = 1.4f * unit
            // The outside of the shackle and bottom of the body are exactly 6 units from the same centre.
            val archTop = cy - 6 * unit + stroke / 2
            val archRadius = 3 * unit
            drawArc(color, 180f, 180f, false, Offset(cx - archRadius, archTop),
                Size(2 * archRadius, 2 * archRadius), style = Stroke(stroke))
            for (x in listOf(cx - archRadius, cx + archRadius)) {
                drawLine(color, Offset(x, archTop + archRadius), Offset(x, cy), strokeWidth = stroke)
            }
            drawRoundRect(color, Offset(cx - 4.5f * unit, cy - unit), Size(9 * unit, 7 * unit),
                CornerRadius(1.6f * unit))
        }
    }
}

/** 小锁原本 12dp 高。 */
internal const val FLOATING_LOCK_GLYPH_DP = 12f
/** 小锁上下至少留的空。 */
internal const val FLOATING_LOCK_GLYPH_MARGIN_DP = 3f

/**
 * 小锁在高 [bandDp] 的最下面那段拖动条里怎么放：返回（缩放比例，锁中心离卡片底边的 dp）。
 * 放得下 12dp 加上下各 3dp 就原样大小，放不下按比例缩小（最小 0.6 倍），锁中心在这段的正中。
 */
internal fun floatingLockGlyphPlacement(bandDp: Float): Pair<Float, Float> {
    val scale = ((bandDp - 2 * FLOATING_LOCK_GLYPH_MARGIN_DP) / FLOATING_LOCK_GLYPH_DP).coerceIn(0.6f, 1f)
    return scale to bandDp / 2f
}

/** 锁定时拖底部拖动条：小锁左右抖几下的总时长。 */
internal const val FLOATING_LOCK_SHAKE_MS = 360
/** 小锁抖动的最大幅度。 */
private const val FLOATING_LOCK_SHAKE_DP = 3f
/** 锁定时拖底部拖动条：卡片边框亮起后先停这么久，再用 [FLOATING_LOCK_FLASH_FADE_MS] 淡回平时的淡描边。 */
private const val FLOATING_LOCK_FLASH_HOLD_MS = 150
private const val FLOATING_LOCK_FLASH_FADE_MS = 650

/**
 * 锁定后有人拖底部拖动条，卡片不动（用户 11-10：不动也不知道为什么，让锁抖一下或把外边框显出来）：
 * 小锁左右抖几下、越抖越小，同时卡片边框整圈亮成强调色再慢慢淡回去。不加设置、不振动。
 */
internal class FloatingLockNudge {
    /** 0..1：抖动走到哪了。 */
    internal val shake = Animatable(0f)
    /** 0..1：边框亮度。 */
    internal val flash = Animatable(0f)
    /** 每次触发加一，动画从头来。 */
    internal var epoch by mutableIntStateOf(0)
        private set

    fun trigger() {
        epoch++
    }

    /** 小锁现在的横向偏移（dp）：三个来回，幅度线性收到零。 */
    fun shakeDp(): Float {
        val t = shake.value
        if (t <= 0f || t >= 1f) return 0f
        return sin(t * 6f * PI.toFloat()) * (1f - t) * FLOATING_LOCK_SHAKE_DP
    }
}

@Composable
internal fun rememberFloatingLockNudge(): FloatingLockNudge {
    val nudge = remember { FloatingLockNudge() }
    val epoch = nudge.epoch
    LaunchedEffect(nudge, epoch) {
        if (epoch == 0) return@LaunchedEffect
        coroutineScope {
            launch {
                nudge.shake.snapTo(0f)
                nudge.shake.animateTo(1f, tween(FLOATING_LOCK_SHAKE_MS, easing = LinearEasing))
                nudge.shake.snapTo(0f)
            }
            launch {
                nudge.flash.snapTo(1f)
                nudge.flash.animateTo(0f, tween(FLOATING_LOCK_FLASH_FADE_MS, delayMillis = FLOATING_LOCK_FLASH_HOLD_MS))
            }
        }
    }
    return nudge
}

/** 长按底部拖动条的阶段：按住时边框进度往上走；没满松手原路退回；满了切换锁定，整条进度淡掉。 */
internal enum class FloatingLockHoldPhase { Idle, Holding, Done }

/** 边框进度：[progress] 0..1 是从底边中点沿两边走到顶边中点的比例，[alpha] 只在切换成功后淡出时变。都只在绘制时读。 */
internal class FloatingLockSweep {
    val progress = Animatable(0f)
    val alpha = Animatable(1f)
}

/** [holdMs] 是从开始画到画满的时间（长按总时间减去开头不画的那一小段）。 */
@Composable
internal fun rememberFloatingLockSweep(phase: FloatingLockHoldPhase, holdMs: Int): FloatingLockSweep {
    val sweep = remember { FloatingLockSweep() }
    LaunchedEffect(sweep, phase) {
        when (phase) {
            FloatingLockHoldPhase.Holding -> {
                sweep.alpha.snapTo(1f)
                val rest = ((1f - sweep.progress.value) * holdMs).roundToInt().coerceAtLeast(0)
                sweep.progress.animateTo(1f, tween(rest, easing = LinearEasing))
            }
            FloatingLockHoldPhase.Idle -> {
                val back = (sweep.progress.value * FLOATING_LOCK_RETRACT_MS).roundToInt().coerceAtLeast(0)
                sweep.progress.animateTo(0f, tween(back, easing = LinearEasing))
            }
            FloatingLockHoldPhase.Done -> {
                sweep.progress.snapTo(1f)
                sweep.alpha.animateTo(0f, tween(FLOATING_LOCK_FADE_MS))
                sweep.progress.snapTo(0f)
                sweep.alpha.snapTo(1f)
            }
        }
    }
    return sweep
}

/**
 * 卡片边框：锁定时一圈淡描边，提示位置和大小已锁住；长按底部拖动条时，一条进度从底边中点沿左右两边同时往上走，
 * 到顶边中点会合（用户 11-09），没满松手原路退回。两半路径一样长，含圆角。
 */
internal fun Modifier.floatingLockOutline(
    locked: Boolean,
    sweep: FloatingLockSweep,
    color: Color,
    /** 锁定时有人拖：整圈边框亮一下（[FloatingLockNudge.flash]）。 */
    nudge: FloatingLockNudge? = null,
) = drawWithCache {
    val cx = size.width / 2f
    fun halfPath(inset: Float): Path {
        val left = inset
        val top = inset
        val bottom = size.height - inset
        val radius = minOf(FloatingKeyboardCardCorner.toPx() - inset, cx - left, size.height / 2f - top).coerceAtLeast(0f)
        val diameter = 2f * radius
        return Path().apply {
            moveTo(cx, bottom)
            lineTo(left + radius, bottom)
            arcTo(Rect(left, bottom - diameter, left + diameter, bottom), 90f, 90f, false)
            lineTo(left, top + radius)
            arcTo(Rect(left, top, left + diameter, top + diameter), 180f, 90f, false)
            lineTo(cx, top)
        }
    }
    val sweepWidth = FloatingLockSweepWidth.toPx()
    val faint = halfPath(0.5f)
    val track = halfPath(sweepWidth / 2f)
    val measure = PathMeasure().apply { setPath(track, false) }
    val segment = Path()
    val faintStroke = Stroke(width = 1f)
    val sweepStroke = Stroke(width = sweepWidth, cap = StrokeCap.Round)
    onDrawWithContent {
        drawContent()
        if (size.width <= sweepWidth || size.height <= sweepWidth) return@onDrawWithContent
        fun halves(path: Path, tint: Color, stroke: Stroke) {
            drawPath(path, tint, style = stroke)
            scale(-1f, 1f, center) { drawPath(path, tint, style = stroke) }
        }
        if (locked) halves(faint, color.copy(alpha = color.alpha * .55f), faintStroke)
        val flash = nudge?.flash?.value ?: 0f
        if (flash > 0f) halves(track, color.copy(alpha = color.alpha * flash), sweepStroke)
        val p = sweep.progress.value
        if (p > 0f) {
            segment.reset()
            measure.getSegment(0f, measure.length * p, segment, true)
            halves(segment, color.copy(alpha = color.alpha * sweep.alpha.value), sweepStroke)
        }
    }
}

/**
 * 按住底部拖动条（整条都算）不动：没锁时 [FLOATING_LOCK_HOLD_MS] 锁定，锁定后 [FLOATING_UNLOCK_HOLD_MS] 解锁
 * （用户 11-09：不要锁定按钮，长按下面这条锁定）。挪过触摸阈值、移出这条或多指就不算，拖动照旧；
 * 开头 [FLOATING_HOLD_QUIET_MS] 不报进度。只在 Initial 轮里看、不消费按下，点一下和拖动照样落到下面的手势；
 * 满了之后这根手指的后续事件全部吃掉，不再算拖动或点击。
 */
@Composable
internal fun Modifier.floatingFooterHold(
    locked: Boolean,
    onPhase: (FloatingLockHoldPhase) -> Unit,
    onToggle: () -> Unit,
): Modifier {
    val phase by rememberUpdatedState(onPhase)
    val toggle by rememberUpdatedState(onToggle)
    val lockedNow by rememberUpdatedState(locked)
    return semantics {
        role = Role.Button
        contentDescription = "悬浮键盘底部拖动条"
        stateDescription = if (locked) "已锁定" else "可移动"
        onLongClick(label = if (locked) "解锁" else "锁定") { toggle(); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val total = floatingLockHoldMs(lockedNow).toLong()
            if (!holdLasts(down, FLOATING_HOLD_QUIET_MS.toLong())) return@awaitEachGesture
            var done = false
            phase(FloatingLockHoldPhase.Holding)
            try {
                if (holdLasts(down, total - FLOATING_HOLD_QUIET_MS)) {
                    done = true
                    phase(FloatingLockHoldPhase.Done)
                    toggle()
                    // This finger has already acted, including if it later moves or lifts over the strip.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            } finally {
                if (!done) phase(FloatingLockHoldPhase.Idle)
            }
        }
    }
}

/** 这根手指在 [millis] 内一直按着、没挪过触摸阈值、没出这条时为 true；挪开、松手、多指或被别人用掉为 false。 */
private suspend fun AwaitPointerEventScope.holdLasts(down: PointerInputChange, millis: Long): Boolean =
    withTimeoutOrNull(millis) {
        while (true) {
            val pointer = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: break
            if (pointer.id != down.id || pointer.isConsumed ||
                (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                pointer.position.x !in 0f..size.width.toFloat() ||
                pointer.position.y !in 0f..size.height.toFloat()) break
            if (!pointer.pressed) {
                // A cancelled hold only retracts the sweep; a short tap still lights the resize corners.
                if (pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) pointer.consume()
                break
            }
        }
        false
    } ?: true
