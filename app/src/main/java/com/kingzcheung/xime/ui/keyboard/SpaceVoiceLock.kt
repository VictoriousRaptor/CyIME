package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 按住空格说话，滑到左右任意一把浮起来的锁上松手 = 锁定：手指离开也一直听，点一下空格结束并上屏
 * （用户 11-20：只用长按空格的人也要能连续语音）。开了「上滑取消」时，两把锁正中间还有一个同样的取消按钮。
 *
 * 布局（用户 11-22：右上一把锁不对称）：以空格为中心，左右各一把锁、对称；键区上半部分用弧线分成三块，
 * 两侧弧线以内（以及往外冲过头）是锁定，中间一大块是取消（取消照旧要高过空格顶边一个键高，或在取消按钮上）。
 * 锁是键区里单独浮起来的圆按钮（11-21，#531），只在按住说话时出现，不挡触摸、不占布局；
 * 键盘很宽（平板、横屏）时离空格两端最多 [SpaceVoiceLockMetrics.reach]，不用横着滑半个平板。
 */
@Stable
internal class SpaceVoiceLockState {
    /** 键区（放锁按钮的那一层）在 Compose 根里的位置。 */
    var host: Rect = Rect.Zero
    /** 空格在 Compose 根里的位置。 */
    var space: Rect = Rect.Zero
    /** 按钮尺寸（像素）；键盘外没量过时为 null，不能锁定。 */
    var metrics: SpaceVoiceLockMetrics? = null
    /** 这次长按开了麦、手指还按着：两把锁浮出来。 */
    var holding by mutableStateOf(false)
    /** 手指在某一把锁上：松手就锁定。 */
    var armed by mutableStateOf(false)
    /** 手指在哪一把锁上：-1 左、1 右、0 都不在（画哪一把「到位」）。 */
    var side by mutableIntStateOf(0)
    /** 刚锁上的是哪一把：外框闪在那一把上。 */
    var lockedSide by mutableIntStateOf(1)
    /** 这次按住说话开了「上滑取消」：中间出取消按钮。 */
    var cancelShown by mutableStateOf(false)
    /** 手指在取消区：取消按钮涨满。 */
    var cancelArmed by mutableStateOf(false)
    /** 已锁定：手指离开也在听，空格写「点按结束」。 */
    var locked by mutableStateOf(false)
    /** 手指在根坐标里的位置：到位时按钮往手指那边吸一点。只在画的时候读，不触发重组。 */
    var finger by mutableStateOf(Offset.Unspecified)

    /**
     * 松手就锁定：锁按钮浮着（麦克风真开着）且手指到位。锁按钮画「到位」的样子、空格写「松手锁定」、
     * 松手时真的锁定，三处都看这一个条件，动画到了就一定锁得上（用户 11-22：动画到头了松手没锁上）。
     */
    val lockReady: Boolean get() = holding && armed

    private fun layout(): SpaceVoiceLockLayout? = metrics?.let { spaceVoiceLockLayout(host, space, it) }

    /** 手指在哪一把锁的区域里：-1 左、1 右、0 都不在。 */
    fun zoneSide(pointInRoot: Offset): Int = layout()?.sideAt(pointInRoot) ?: 0

    fun zoneContains(pointInRoot: Offset): Boolean = zoneSide(pointInRoot) != 0

    /** 手指在中间的取消按钮上（按钮大出 slop）。 */
    fun cancelButtonContains(pointInRoot: Offset): Boolean {
        val m = metrics ?: return false
        val c = layout()?.cancel ?: return false
        return (pointInRoot - c).getDistance() <= m.radius + m.slop
    }
}

/** 键盘外（设置页预览、测试）没有提供时为 null：长按空格说话照旧，只是没有锁定。 */
internal val LocalSpaceVoiceLock = staticCompositionLocalOf<SpaceVoiceLockState?> { null }

/**
 * 锁按钮的尺寸（像素）。[radius] 按钮半径；[margin] 离键区顶边、两边；[reach] 键盘很宽时按钮离空格两端最远多少；
 * [slop] 判定比按钮至少大出多少（手指不用正好落在按钮里）；[zone] 锁定弧线的最大半径；[gap] 锁定区和取消按钮之间至少留多少。
 */
internal data class SpaceVoiceLockMetrics(val radius: Float, val margin: Float, val reach: Float, val slop: Float,
    val zone: Float, val gap: Float)

/** 两把锁的圆心（根坐标）和锁定弧线半径；取消按钮在两把锁正中间、同一高度。 */
internal class SpaceVoiceLockLayout(val left: Offset, val right: Offset, val zone: Float) {
    val cancel: Offset get() = Offset((left.x + right.x) / 2f, left.y)

    fun center(side: Int): Offset = if (side < 0) left else right

    /**
     * 锁定区：锁按钮外一圈弧线以内，以及往外侧冲过头（锁的外侧、弧线底边以上，包括冲出键盘顶边、侧边）。
     * 中间（两条弧线之间）不算。
     */
    fun sideAt(point: Offset): Int {
        for (side in intArrayOf(-1, 1)) {
            val c = center(side)
            val outward = (point.x - c.x) * side
            if ((point - c).getDistance() <= zone || (outward >= 0f && point.y <= c.y + zone)) return side
        }
        return 0
    }
}

/**
 * 两把锁的位置：以空格中心左右对称，各自尽量靠键区两边（往里 margin），但离空格两端不超过 reach；
 * 两边能放的距离不一样时取近的那边，保证对称。键区太窄、空格太靠边放不下两把锁时为 null（不出锁）。
 * 锁定弧线半径最大 [SpaceVoiceLockMetrics.zone]，键区窄时收小，给中间的取消按钮留出位置。
 */
internal fun spaceVoiceLockLayout(host: Rect, space: Rect, m: SpaceVoiceLockMetrics): SpaceVoiceLockLayout? {
    val edge = m.margin + m.radius
    if (host.width < 2f * edge || host.height <= 0f) return null
    val hasSpace = space.width > 0f
    val mid = if (hasSpace) space.center.x.coerceIn(host.left + edge, host.right - edge) else host.center.x
    val right = if (hasSpace) minOf(host.right - edge, space.right + m.reach) else host.right - edge
    val left = if (hasSpace) maxOf(host.left + edge, space.left - m.reach) else host.left + edge
    val d = minOf(right - mid, mid - left)
    if (d < 2f * m.radius + m.gap) return null
    val y = host.top + minOf(edge, host.height / 2f)
    val zone = minOf(m.zone, maxOf(m.radius + m.slop, d - m.radius - m.gap))
    return SpaceVoiceLockLayout(Offset(mid - d, y), Offset(mid + d, y), zone)
}

/**
 * 这一下手指算不算在锁上：在锁定区里就算；松手那一下手指离屏时坐标常会跳一点，之前已经到位的照旧算到位，
 * 不因为抬手那一下偏出去就锁不上。
 */
internal fun spaceVoiceLockArmed(inZone: Boolean, released: Boolean, wasArmed: Boolean): Boolean =
    inZone || (released && wasArmed)

/** 锁图标往上挪多少（占图标边长）：锁身在下、锁梁细，按外框居中时看着偏下，按视觉重心往上提一点。 */
internal const val SPACE_VOICE_LOCK_ICON_LIFT = 0.06f

/**
 * 上滑取消：手指高过空格顶边一个键高（[yInKey] 是空格自己的坐标），或在中间的取消按钮上；在锁定区里时除外。
 */
internal fun spaceVoiceCancelArmed(lockArmed: Boolean, yInKey: Float, keyHeight: Float, onCancelButton: Boolean = false): Boolean =
    !lockArmed && (yInKey < -keyHeight || onCancelButton)

/** 到位时按钮往手指那边吸多少（像素，最多 [max]）。 */
internal fun spaceVoiceLockPull(center: Offset, finger: Offset, max: Float): Offset {
    if (!finger.isSpecified) return Offset.Zero
    val d = finger - center
    val len = d.getDistance()
    return if (len <= max || len == 0f) d else d * (max / len)
}

/** 取消按钮和取消区的颜色：浅、深主题上都认得出的红。 */
private val SPACE_VOICE_CANCEL_RED = Color(0xFFE5534B)

/**
 * 锁按钮这一层：铺满键区、不接触摸，只在按住说话时画出左右两把锁（开了上滑取消时中间加一个取消按钮），
 * 以及分开锁定区和取消区的两条弧线。
 * 浮出：从小到大弹出、往上浮一点；手指到位：那一把涨满强调色、锁变白、放大一圈并往手指吸，外面一圈光晕，
 * 它那一块区域亮一点；手指在取消区：取消按钮涨满红色，中间那一块淡淡透红。
 */
@Composable
internal fun SpaceVoiceLockLayer(state: SpaceVoiceLockState?, accent: Color, surface: Color, modifier: Modifier = Modifier) {
    if (state == null) return
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) { SpaceVoiceLockMetrics(radius = 26.dp.toPx(), margin = 12.dp.toPx(), reach = 168.dp.toPx(),
            slop = 24.dp.toPx(), zone = 64.dp.toPx(), gap = 8.dp.toPx()) }
    }
    state.metrics = metrics
    val lock = rememberVectorPainter(Icons.Default.Lock)
    val close = rememberVectorPainter(Icons.Default.Close)
    val shown by animateFloatAsState(if (state.holding) 1f else 0f,
        if (state.holding) spring(dampingRatio = 0.62f, stiffness = 520f) else tween(140, easing = FastOutSlowInEasing),
        label = "voiceLockShown")
    // 左右两把各自的「到位」：手指从一边挪到另一边时，这边退、那边涨。
    val leftAnim by animateFloatAsState(if (state.lockReady && state.side < 0) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 700f), label = "voiceLockLeft")
    val rightAnim by animateFloatAsState(if (state.lockReady && state.side > 0) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 700f), label = "voiceLockRight")
    val cancelAnim by animateFloatAsState(if (state.holding && state.cancelShown && state.cancelArmed) 1f else 0f,
        tween(140), label = "voiceCancelArmed")
    // 锁上的那一下：那一把留住半秒，外框亮一圈白边闪一下再淡出（用户 11-22：锁定的反馈不够显著）。
    val flash = remember { Animatable(0f) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(state.locked) {
        if (!state.locked) { flash.snapTo(0f); flashing = false; return@LaunchedEffect }
        flashing = true
        try {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(SPACE_VOICE_LOCK_FLASH_IN_MS, easing = FastOutSlowInEasing))
            flash.animateTo(0f, tween(SPACE_VOICE_LOCK_FLASH_OUT_MS, easing = FastOutSlowInEasing))
        } finally { flashing = false }
    }
    val idleFill = lerp(surface.copy(alpha = 1f), accent, 0.12f)
    val cancelIdle = lerp(surface.copy(alpha = 1f), SPACE_VOICE_CANCEL_RED, 0.12f)
    Box(modifier.onGloballyPositioned { state.host = it.boundsInRoot() }.drawBehind {
        if (!flashing && shown <= 0.01f) return@drawBehind
        val layout = spaceVoiceLockLayout(state.host, state.space, metrics) ?: return@drawBehind
        val origin = state.host.topLeft
        val visible = if (flashing) 1f else shown
        val alpha = visible.coerceIn(0f, 1f)
        fun armedOf(side: Int) = if (flashing) (if (side == state.lockedSide) 1f else 0f) else if (side < 0) leftAnim else rightAnim
        val zone = layout.zone
        // 区域和弧线（锁上后闪的那半秒不画，只留那一把锁）。
        if (!flashing) clipRect {
            var lockAreas = Path()
            for (side in intArrayOf(-1, 1)) {
                val c = layout.center(side) - origin
                val area = Path().apply {
                    addOval(Rect(c, zone))
                    addRect(if (side > 0) Rect(c.x, -zone, size.width + zone, c.y + zone) else Rect(-zone, -zone, c.x, c.y + zone))
                }
                lockAreas = Path().apply { op(lockAreas, area, PathOperation.Union) }
                val armed = armedOf(side)
                drawPath(area, accent.copy(alpha = (0.07f + 0.22f * armed) * alpha))
                // 弧线：锁定区朝中间的半圈，底下接一段横线到键区边上。
                val stroke = Stroke((1.5f + 0.5f * armed).dp.toPx())
                val line = accent.copy(alpha = (0.55f + 0.4f * armed) * alpha)
                drawArc(line, if (side > 0) 90f else -90f, 180f, false, c - Offset(zone, zone), Size(zone * 2f, zone * 2f), style = stroke)
                drawLine(line, Offset(c.x, c.y + zone), Offset(if (side > 0) size.width else 0f, c.y + zone), stroke.width)
            }
            if (state.cancelShown) {
                // 取消区：空格顶边以上一个键高往上，扣掉两边的锁定区。
                val lineY = state.space.top - state.space.height - origin.y
                if (lineY > 0f) {
                    val band = Path().apply { addRect(Rect(0f, 0f, size.width, lineY)) }
                    val cancelArea = Path().apply { op(band, lockAreas, PathOperation.Difference) }
                    drawPath(cancelArea, SPACE_VOICE_CANCEL_RED.copy(alpha = (0.05f + 0.17f * cancelAnim) * alpha))
                }
            }
        }
        if (state.cancelShown && !flashing) {
            val r = metrics.radius * (0.6f + 0.4f * visible) * (1f + 0.12f * cancelAnim)
            val center = layout.cancel - origin + Offset(0f, (1f - alpha) * 8.dp.toPx())
            drawShadowed(center, r, alpha)
            if (cancelAnim > 0f) drawCircle(SPACE_VOICE_CANCEL_RED.copy(alpha = 0.22f * cancelAnim * alpha), r * 1.25f, center)
            drawCircle(lerp(cancelIdle, SPACE_VOICE_CANCEL_RED, cancelAnim).copy(alpha = alpha), r, center)
            drawCircle(SPACE_VOICE_CANCEL_RED.copy(alpha = 0.6f * (1f - cancelAnim) * alpha), r, center, style = Stroke(1.5f.dp.toPx()))
            val icon = r * 0.9f
            translate(center.x - icon / 2f, center.y - icon / 2f) {
                with(close) { draw(Size(icon, icon), alpha = alpha,
                    colorFilter = ColorFilter.tint(lerp(SPACE_VOICE_CANCEL_RED, Color.White, cancelAnim))) }
            }
        }
        for (side in intArrayOf(-1, 1)) {
            if (flashing && side != state.lockedSide) continue
            val armed = armedOf(side)
            val c0 = layout.center(side)
            val pull = spaceVoiceLockPull(c0, state.finger, 8.dp.toPx()) * armed
            val r = metrics.radius * (0.6f + 0.4f * visible) * (1f + 0.12f * armed)
            // 光晕连同吸过去的那几 dp 都不出键区（键区外是别的应用，画出去会被截掉一块）。
            val glow = r * 1.25f
            val raw = c0 - origin + pull + Offset(0f, (1f - alpha) * 8.dp.toPx())
            val center = Offset(raw.x.coerceIn(glow, maxOf(glow, size.width - glow)), raw.y.coerceIn(glow, maxOf(glow, size.height - glow)))
            drawShadowed(center, r, alpha)
            if (armed > 0f) drawCircle(accent.copy(alpha = 0.22f * armed * alpha), glow, center)
            drawCircle(lerp(idleFill, accent, armed).copy(alpha = alpha), r, center)
            drawCircle(accent.copy(alpha = 0.55f * (1f - armed) * alpha), r, center, style = Stroke(1.5f.dp.toPx()))
            val f = if (side == state.lockedSide) flash.value else 0f
            if (f > 0f) {
                // 锁上了：外框闪一下。外面一圈强调色粗边往外扩（扩到键区边为止，不被截掉），按钮边上一圈白边亮起；
                // 浅色、深色主题都看得见（白边压在强调色上，强调色边压在键盘底色上）。
                val room = minOf(center.x, size.width - center.x, center.y, size.height - center.y)
                val stroke = 3.dp.toPx()
                val ring = minOf(r + 2.dp.toPx() + 6.dp.toPx() * f, room - stroke / 2f)
                drawCircle(accent.copy(alpha = 0.9f * f), ring, center, style = Stroke(stroke))
                drawCircle(Color.White.copy(alpha = 0.95f * f), r - 1.5f.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.16f * f), r, center)
            }
            val icon = r * 0.95f
            translate(center.x - icon / 2f, center.y - icon / 2f - icon * SPACE_VOICE_LOCK_ICON_LIFT) {
                with(lock) { draw(Size(icon, icon), alpha = alpha, colorFilter = ColorFilter.tint(lerp(accent, Color.White, armed))) }
            }
        }
    }) {
        // 测试用：按住说话时有这些节点。
        if (state.holding) Box(Modifier.testTag("space-voice-lock-button"))
        if (state.holding && state.cancelShown) Box(Modifier.testTag("space-voice-cancel-button"))
        if (flashing) Box(Modifier.testTag("space-voice-lock-flash"))
    }
}

/** 柔和的投影：两层淡黑圆往下错开一点。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadowed(center: Offset, r: Float, alpha: Float) {
    drawCircle(Color.Black.copy(alpha = 0.10f * alpha), r + 3.dp.toPx(), center + Offset(0f, 2.5f.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.16f * alpha), r + 1.dp.toPx(), center + Offset(0f, 1.dp.toPx()))
}

private const val SPACE_VOICE_LOCK_FLASH_IN_MS = 110
private const val SPACE_VOICE_LOCK_FLASH_OUT_MS = 380
