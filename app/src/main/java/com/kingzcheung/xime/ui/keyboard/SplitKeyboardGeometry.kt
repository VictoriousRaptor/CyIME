package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp

/**
 * 分体键盘：两半贴住键盘左右两边，中间留一条缝。
 *
 * 字母仍按正常 26 键的十单位网格排列，只在每行五单位中线处插入同一宽度的缝，
 * 所以上下行之间的错位与正常键盘完全一致。
 * 缝宽用整张键盘宽度的比例保存，横竖屏各一份；未调整过时按标定键距自动给出。
 */
internal object SplitKeyboardGeometry {
    const val MIDLINE_UNITS = 5f
    const val MIN_GAP_FRACTION = 0.05f
    const val MAX_GAP_FRACTION = 0.6f
    /** 自动缝宽的范围：窄屏仍留出两拇指之间的空隙，宽屏不把两半挤成两小块。 */
    const val AUTO_MIN_GAP_FRACTION = 0.12f
    const val AUTO_MAX_GAP_FRACTION = 0.4f
    const val FALLBACK_GAP_FRACTION = 0.25f
    /** 每半至少保留的单键宽度。 */
    const val MIN_UNIT_DP = 28f
    /** 屏幕宽度不到这个值（手机竖屏）时分体太窄，自动用完整键盘；转到够宽的方向再分开（用户 11-05）。 */
    const val MIN_SCREEN_WIDTH_DP = 600

    /** 这个屏幕宽度上分体放得下；分体两半总是贴边占满屏宽，所以按屏宽判断，不按键盘宽。 */
    fun fitsScreen(screenWidthDp: Int): Boolean = screenWidthDp >= MIN_SCREEN_WIDTH_DP

    /** 取消键宽上限后，键区左右仍保留的外边距（与 [keyboardGridMetrics] 的 gutter 一致）。 */
    fun gutterDp(floating: Boolean, horizontalInsetDp: Float = 8f): Float {
        val policy = KeyVisualPolicy.Qwerty
        val floor = if (floating) minOf(policy.minGutter, KeyVisualPolicy.FloatingGutter) else policy.minGutter
        return maxOf(floor, horizontalInsetDp)
    }

    fun gapRange(widthDp: Float, gutterDp: Float): ClosedFloatingPointRange<Float> {
        if (!widthDp.isFinite() || widthDp <= 0f) return MIN_GAP_FRACTION..MIN_GAP_FRACTION
        val usable = (widthDp - 2f * gutterDp - 10f * MIN_UNIT_DP) / widthDp
        return MIN_GAP_FRACTION..usable.coerceIn(MIN_GAP_FRACTION, MAX_GAP_FRACTION)
    }

    /** 自动缝宽：让每半的按键回到标定的实体键距；屏幕不够宽时只留最小可用缝。 */
    fun defaultGap(widthDp: Float, gutterDp: Float, pitchDp: Float?): Float {
        val range = gapRange(widthDp, gutterDp)
        val wanted = if (pitchDp == null || !pitchDp.isFinite() || pitchDp <= 0f) FALLBACK_GAP_FRACTION
            else (widthDp - 2f * gutterDp - 10f * pitchDp) / widthDp
        return wanted.coerceIn(AUTO_MIN_GAP_FRACTION, AUTO_MAX_GAP_FRACTION)
            .coerceIn(range.start, range.endInclusive)
    }

    fun resolvedGap(saved: Float?, widthDp: Float, gutterDp: Float, pitchDp: Float?): Float {
        val range = gapRange(widthDp, gutterDp)
        return saved?.takeIf { it.isFinite() && it > 0f }?.coerceIn(range.start, range.endInclusive)
            ?: defaultGap(widthDp, gutterDp, pitchDp)
    }

    /** 键区（去掉外边距后）留给按键的宽度比例。 */
    fun keyWidthFraction(widthDp: Float, gapFraction: Float, gutterDp: Float): Float {
        val body = (widthDp - 2f * gutterDp).coerceAtLeast(1f)
        return ((body - gapFraction * widthDp) / body).coerceIn(0.1f, 1f)
    }

    /**
     * 一行左半的按键个数：键中心落在五单位中线左侧（含中线）的留在左边。
     * 标准错位下即 qwert|yuiop、asdfg|hjkl、zxcv|bnm，每个键只出现一次。
     */
    fun leftCount(keys: List<String>, startUnits: Float, weighted: Boolean, widths: List<Float>? = null): Int {
        var x = startUnits
        var count = 0
        for ((index, key) in keys.withIndex()) {
            // 自定义布局调过键宽时按实际宽度找中线，分体缝才落在手指实际的分界上。
            val w = widths?.getOrNull(index) ?: if (weighted) key.length.toFloat() else 1f
            if (x + w / 2f > MIDLINE_UNITS + 0.001f) break
            count++
            x += w
        }
        return count
    }

    /**
     * 14 键分体：跨过中线的双字母键拆成两个单字母键（ty→t、y），左右手与 26 键一致。
     * 拆出的单键按精确字母输入；总宽不变，错位与列宽不受影响。
     */
    fun splitStraddlingPairs(keys: List<String>, startUnits: Float): List<String> {
        var x = startUnits
        return keys.flatMap { key ->
            val start = x
            x += key.length
            if (key.length == 2 && start < MIDLINE_UNITS - 0.001f && x > MIDLINE_UNITS + 0.001f)
                listOf(key.take(1), key.drop(1)) else listOf(key)
        }
    }

    /**
     * 分体「右半边也放 G」（设置，默认关）：中排是 a s d f g | h j k l 时，右半开头再放一个 G，
     * 右半五键贴到行尾，与上一排 y u i o p 上下对齐；左半不动，缝的右边与其他行一致，中缝不变。
     * 只认 26 键这一种中排（九个单字母键、左五右四、第五个是 g）：14 键、注音、自定义十键中排、不分体一律原样。
     * 缝窄到放不下一个键时也原样。尺寸都是 dp 数值。
     */
    fun homeRowWithRightG(
        keys: List<String>,
        splitAt: Int,
        enabled: Boolean,
        gap: Float,
        endInset: Float,
        unitWidth: Float,
    ): SplitHomeRow {
        val plain = SplitHomeRow(keys, -1, gap, endInset)
        if (!enabled || splitAt != 5 || keys.size != 9 || keys.any { it.length != 1 } ||
            !keys[4].equals("g", ignoreCase = true)) return plain
        val narrowed = gap + endInset - unitWidth
        if (!narrowed.isFinite() || narrowed < 0f) return plain
        return SplitHomeRow(keys.take(splitAt) + keys[4] + keys.drop(splitAt), splitAt, narrowed, 0f)
    }

    /** 底排在空格键内部分开：左侧空格补足到中线，其余归右侧。[leftFixed] 是空格左边各键的总宽。 */
    /**
     * 两半靠中缝那侧的圆角半径：和卡片外侧四角同一圆角。上角只在中缝通到卡片顶边（工具栏／候选栏也分到两侧）时有，
     * 栏是整条时两半挂在栏下面，那里是内角，保持直角；下角跟卡片底角（悬浮四角圆，固定看「底部圆角」）。
     * 圆角放在两半上，不碰中缝本身的宽度；缝太矮放不下时两角平分高度。返回 (上角, 下角)。
     */
    fun innerCornerRadii(
        radius: Float,
        roundedTop: Boolean,
        roundedBottom: Boolean,
        reachesTop: Boolean,
        holeHeight: Float,
    ): Pair<Float, Float> {
        if (!radius.isFinite() || radius <= 0f || !holeHeight.isFinite() || holeHeight <= 0f) return 0f to 0f
        val top = if (roundedTop && reachesTop) radius else 0f
        val bottom = if (roundedBottom) radius else 0f
        val scale = if (top + bottom > holeHeight) holeHeight / (top + bottom) else 1f
        return top * scale to bottom * scale
    }

    fun spaceSplit(
        bottom: QwertyBottomRowWeights,
        space: Float = bottom.space,
        leftFixed: Float = bottom.mode * 2f + bottom.punctuation,
    ): Pair<Float, Float> {
        val left = (bottom.total / 2f - leftFixed).coerceIn(space * 0.2f, space * 0.8f)
        return left to space - left
    }
}

/** 中排实际排出的键：[keys] 含补上的 G，[mirrorIndex] 是它的位置（-1 没补），[gap]、[endInset] 为这一行的缝宽和行尾留白（dp）。 */
internal data class SplitHomeRow(val keys: List<String>, val mirrorIndex: Int, val gap: Float, val endInset: Float)

/**
 * 分体中缝的实际位置（窗口像素坐标），由每行的缝占位上报。
 * 键盘背景挖空、触摸区让给宿主、玻璃模糊避开中缝，三处共用这一份测量。
 */
@Stable
internal class SplitKeyboardGap {
    private val rows = mutableStateMapOf<Int, Rect>()
    private var areaTop by mutableFloatStateOf(Float.NaN)
    /** 工具栏／候选栏也分到两侧时，中缝从栏顶开始；NaN 表示栏仍是整条。 */
    private var barTop by mutableFloatStateOf(Float.NaN)
    /** 面板、候选展开等盖住字母键盘时，中缝不能再挖空它们。 */
    var exposed by mutableStateOf(true)
        private set
    var onChanged: (() -> Unit)? = null

    fun reportRow(row: Int, bounds: Rect?) {
        val next = bounds?.takeIf { it.width > 0f && it.height > 0f }
        if (rows[row] == next) return
        if (next == null) rows.remove(row) else rows[row] = next
        onChanged?.invoke()
    }

    fun reportBarTop(top: Float?) {
        val next = top ?: Float.NaN
        if (barTop == next || (barTop.isNaN() && next.isNaN())) return
        barTop = next
        onChanged?.invoke()
    }

    /** 中缝左右边（窗口横坐标）；没有分体或被面板盖住时为 null。 */
    fun gapX(): Pair<Float, Float>? {
        if (!exposed || rows.isEmpty()) return null
        val left = rows.values.maxOf { it.left }
        val right = rows.values.minOf { it.right }
        return if (right - left < 1f) null else left to right
    }

    /**
     * 正在挖中缝（[cutout] 不为空）：挖法是用 Clear 清掉键盘自己画的底色，键盘外层必须是一张单独的离屏图，
     * 否则会连窗口里垫在下面的东西一起清掉。只读状态，可在 graphicsLayer 块里读。
     */
    val cutsKeyboard: Boolean get() = !filled && gapX() != null

    fun reportAreaTop(top: Float) {
        if (areaTop == top) return
        areaTop = top
        onChanged?.invoke()
    }

    fun updateExposed(value: Boolean) {
        if (exposed == value) return
        exposed = value
        onChanged?.invoke()
    }

    /** 卡片外侧圆角（像素）和上下两角是否圆；两半靠中缝的那侧照这个画圆角（GitHub #78）。 */
    private var cornerRadius by mutableFloatStateOf(0f)
    private var cornerTop by mutableStateOf(false)
    private var cornerBottom by mutableStateOf(false)

    fun updateCorners(radiusPx: Float, roundedTop: Boolean, roundedBottom: Boolean) {
        if (cornerRadius == radiusPx && cornerTop == roundedTop && cornerBottom == roundedBottom) return
        cornerRadius = radiusPx
        cornerTop = roundedTop
        cornerBottom = roundedBottom
        onChanged?.invoke()
    }

    /**
     * 要清掉的形状（窗口坐标）：中缝矩形，加上两半靠中缝四个角圆角外的那一小块。
     * 没有圆角时就是 [hole] 本身。触摸区仍按 [hole] 的矩形让出，角上那几个像素不影响按键。
     */
    fun cutout(bottom: Float): Path? {
        val hole = hole(bottom) ?: return null
        val (rt, rb) = SplitKeyboardGeometry.innerCornerRadii(cornerRadius, cornerTop, cornerBottom,
            reachesTop = !barTop.isNaN(), holeHeight = hole.height)
        return Path().apply {
            val l = hole.left; val r = hole.right; val t = hole.top; val b = hole.bottom
            moveTo(l - rt, t)
            lineTo(r + rt, t)
            if (rt > 0f) arcTo(Rect(r, t, r + 2f * rt, t + 2f * rt), 270f, -90f, false)
            lineTo(r, b - rb)
            if (rb > 0f) arcTo(Rect(r, b - 2f * rb, r + 2f * rb, b), 180f, -90f, false)
            lineTo(l - rb, b)
            if (rb > 0f) arcTo(Rect(l - 2f * rb, b - 2f * rb, l, b), 90f, -90f, false)
            lineTo(l, t + rt)
            if (rt > 0f) arcTo(Rect(l - 2f * rt, t, l, t + 2f * rt), 0f, -90f, false)
            close()
        }
    }

    /** 中缝用键盘底色填满：仍分两侧排布，但不挖空、不让出触摸、玻璃整块。 */
    var filled by mutableStateOf(false)
        private set

    fun updateFilled(value: Boolean) {
        if (filled == value) return
        filled = value
        onChanged?.invoke()
    }

    /**
     * 中缝矩形：各行缝的公共部分，两半因此是两块矩形面板（键在面板内照常错位）。
     * 上沿补到键区顶边，下沿补到 [bottom]（卡片底边，含底部留白）。
     */
    fun hole(bottom: Float): Rect? {
        if (!exposed || filled || rows.isEmpty()) return null
        val all = rows.values
        val left = all.maxOf { it.left }
        val right = all.minOf { it.right }
        if (right - left < 1f) return null
        val top = all.minOf { it.top }.let { if (areaTop.isNaN()) it else minOf(it, areaTop) }
            .let { if (barTop.isNaN()) it else minOf(it, barTop) }
        return Rect(left, top, right, maxOf(all.maxOf { it.bottom }, bottom))
    }
}

internal val LocalSplitKeyboardGap = staticCompositionLocalOf<SplitKeyboardGap?> { null }

/** 调节会话中的缝宽预览；null 表示沿用已保存值。 */
internal val LocalSplitGapPreview = staticCompositionLocalOf<MutableState<Float?>?> { null }

/** 每行插入的缝；row 0 带旧测试标签，其余按行号区分。 */
@Composable
internal fun SplitGapSpacer(row: Int, width: Dp) {
    val gap = LocalSplitKeyboardGap.current
    if (gap != null) DisposableEffect(gap, row) { onDispose { gap.reportRow(row, null) } }
    Spacer(Modifier.width(width).fillMaxHeight()
        .testTag(if (row == 0) "split-keyboard-gap" else "split-keyboard-gap:$row")
        .then(if (gap != null) Modifier.onGloballyPositioned { gap.reportRow(row, it.boundsInWindow()) } else Modifier))
}

/**
 * 在背景之后、按键之前清掉中缝像素，让宿主内容透出来。
 * 只清背景层：按键气泡、长按候选越过中缝时照常绘制。
 */
@Composable
internal fun Modifier.splitGapCutout(gap: SplitKeyboardGap?): Modifier {
    if (gap == null) return this
    var origin by remember(gap) { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInWindow() }.drawBehind {
        val cutout = gap.cutout(origin.y + size.height) ?: return@drawBehind
        cutout.translate(Offset(-origin.x, -origin.y))
        drawPath(cutout, Color.Transparent, blendMode = BlendMode.Clear)
    }
}

/** 标定的实体键距（dp）；读不到屏幕物理尺寸时为 null。 */
@Composable
internal fun rememberCalibratedKeyPitchDp(): Float? {
    val context = LocalContext.current
    val display = LocalView.current.display
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(context, display, configuration, density) {
        keyboardPhysicalMetrics(context, display, density)?.dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
    }
}

/**
 * 只按中缝的横向范围裁掉本节点（含背景与子内容）；用于分到两侧的工具栏和候选栏。
 * 给了 [gap] 且键盘在挖中缝时按同一形状裁，两半靠中缝的上角和键盘一样是圆的。
 */
internal fun Modifier.clipSplitGapColumn(
    gapWindowX: Pair<Float, Float>?,
    gap: SplitKeyboardGap? = null,
    origin: () -> Offset,
): Modifier =
    if (gapWindowX == null) this else drawWithContent {
        val o = origin()
        val cutout = gap?.cutout(o.y + size.height)
        if (cutout != null) {
            cutout.translate(Offset(-o.x, -o.y))
            clipPath(cutout, ClipOp.Difference) { this@drawWithContent.drawContent() }
        } else clipRect(gapWindowX.first - o.x, 0f, gapWindowX.second - o.x, size.height, ClipOp.Difference) {
            this@drawWithContent.drawContent()
        }
    }

/**
 * 把一行内容分到中缝两侧：[left] 占中缝左边的宽度，[right] 从中缝右边开始到行尾。
 * 两侧宽度以 dp 传给内容，便于各自排布。
 */
@Composable
internal fun SplitGapRow(
    gapWindowX: Pair<Float, Float>,
    modifier: Modifier = Modifier,
    left: @Composable (leftWidth: Dp, rightWidth: Dp) -> Unit,
    right: @Composable (leftWidth: Dp, rightWidth: Dp) -> Unit,
) {
    var originX by remember { mutableFloatStateOf(Float.NaN) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.onGloballyPositioned { originX = it.positionInWindow().x }) {
        val density = LocalDensity.current.density
        val width = constraints.maxWidth.toFloat()
        val leftPx = if (originX.isNaN()) width / 2f else (gapWindowX.first - originX).coerceIn(0f, width)
        val rightStart = if (originX.isNaN()) width / 2f else (gapWindowX.second - originX).coerceIn(leftPx, width)
        val leftDp = (leftPx / density).dp
        val rightDp = ((width - rightStart) / density).dp
        androidx.compose.foundation.layout.Box(Modifier.width(leftDp).fillMaxHeight()) { left(leftDp, rightDp) }
        androidx.compose.foundation.layout.Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(rightStart.toInt(), 0) }
            .width(rightDp).fillMaxHeight()) { right(leftDp, rightDp) }
    }
}
