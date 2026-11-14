package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import com.kingzcheung.xime.settings.KeyGapLightConfig
import com.kingzcheung.xime.settings.KeyGapLightStyle
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 键缝动效（用户 11-11 定的样机）：光画在键帽下面的一层，只从键缝里露出来，颜色取主题强调色。
 * 按下一个键，光沿着键缝网往四面八方走最短路（[GapNet]）；删除键反过来往里收。
 *
 * 规矩和 CyMotion 一样：按下当帧就有反馈，不等动画；只多画一层，不改任何键的大小、位置，也不让键重组；
 * 不拦触摸。开关关着时（默认）[LocalKeyGapLight] 是 null，键上不多挂任何东西。
 */

/** 这个键在键缝里画什么。 */
enum class GapLightKind {
    /** 按下时往外走（打字）。 */
    PRESS,
    /** 按下时往里收（删除）。 */
    PULL,
    /** 点一下（没拖动、没按成长按）松手时，一块和键一样宽的色带往上走到按键区顶边（空格：上屏）。 */
    RISE,
    /** 点一下松手时，整排色带从下往上扫（回车：发送）。 */
    SWEEP,
    /** 只登记位置，不画。 */
    NONE,
}

/** 当前键盘的键缝光层；键缝动效关着、或这块键盘不画它时是 null。 */
internal val LocalKeyGapLight = staticCompositionLocalOf<KeyGapLightField?> { null }

/**
 * 听写时的音量（0～1；没在听、没声音都是 0）。由 KeyboardView 提供，只在光层里读：音量变了只重画光层，键盘不重组。
 * 没提供（设置页的预览等）就没有音量条。
 */
internal val LocalKeyGapLightVoice = staticCompositionLocalOf<(() -> Float)?> { null }

/** 同时在走的动效最多这么多个，打得再快也不会越积越多。 */
private const val GAP_LIGHT_MAX_PULSES = 8

/** 切换扩散的速度（dp 每毫秒）：匀速，扫过整块键盘一百多毫秒。 */
private const val GAP_LIGHT_WAVE_DP_PER_MS = 2.6f
private const val GAP_LIGHT_RISE_MS = 190
private const val GAP_LIGHT_SWEEP_MS = 260
private const val GAP_LIGHT_FLASH_MS = 240
/** 移光标走一格留下的刻度多久收掉；松手后色块多久收掉；色块的半宽（dp）。 */
private const val GAP_LIGHT_TICK_MS = 260
private const val GAP_LIGHT_BEAD_FADE_NANOS = 120_000_000L
private const val GAP_LIGHT_BEAD_HALF_DP = 14f
private const val BEAD_OFF = 0
private const val BEAD_ON = 1
private const val BEAD_ENDING = 2

private const val BAND_WAVE = 0
private const val BAND_RISE = 1
private const val BAND_SWEEP = 2
private const val BAND_FLASH = 3

/** 手指按下到出第一帧平均已经过了半帧：第一帧就从这里画起，不白等一帧（方框、菱形进度为 0 时什么都没有）。 */
private const val GAP_LIGHT_LEAD_NANOS = 8_000_000L

/**
 * 键自己在「这一下真的算数」的时候告诉光层：空格真的输入了、回车真的发了、移光标走了一格。
 * 不靠旁观触摸去猜，和键自己的判定始终一致。交给 [keyGlow]；键缝动效关着时这些调用什么都不做。
 */
@Stable
internal class KeyGapLightTrigger {
    internal var field: KeyGapLightField? = null
    internal var token: Any? = null
    internal var kind = GapLightKind.NONE

    /** 这个键点了一下并且生效了。 */
    fun tap() {
        field?.tap(token ?: return, kind)
    }

    /** 正在按住空格移光标；[x] 是手指在键格里的横坐标（像素，和这个键的触摸坐标同一个原点）。 */
    fun cursorAt(x: Float) {
        field?.bead(token ?: return, x)
    }

    /** 光标走了一格。 */
    fun cursorStep() {
        field?.beadTick()
    }

    /** 移光标结束（松手）。 */
    fun cursorEnd() {
        field?.beadEnd()
    }
}

@Stable
internal class KeyGapLightField {
    /** 由 [rememberKeyGapLight]、[KeyGapLightLayer] 在每次组合后写入。 */
    var config = KeyGapLightConfig()
    /** 键帽圆角（像素），半透明键帽时裁出键缝用。 */
    var cornerRadius = 0f
        set(value) { if (field != value) { field = value; mask = null } }
    /** 键帽半透明（玻璃效果、带透明度的键色）：方框、菱形要裁到键缝里，不然会透过键帽看到。 */
    var translucentCaps = false
    /** 系统的「动画时长缩放」；缩放为 0（关了动画）时什么都不画。 */
    var motionScale: MotionDurationScale? = null

    /** 光层在跑：有它才向系统要帧，停下来以后不占主线程。 */
    var running by mutableStateOf(false)
        private set

    private class Spot(var coordinates: LayoutCoordinates, var pads: FloatArray)

    private class Pulse(
        val pull: Boolean,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val routes: List<GapRoute>,
        val reach: Float, val dash: Float,
        val durationNanos: Long,
    ) {
        /** 起点时间（第一帧往前 [GAP_LIGHT_LEAD_NANOS]）；帧钟还没走到是 -1，这时按刚起步画（按下当帧就亮）。 */
        var start = -1L
    }

    /** 大范围的色带：前沿是硬边，后面分级变淡。细线快速移动会有重影，色带不会。 */
    private class Band(
        val type: Int,
        val x: Float, val y: Float,
        /** 扩散：最远走多远；上屏：色带的半宽；顶边亮条：最宽多宽。 */
        val extent: Float,
        val expand: Boolean,
        val durationNanos: Long,
        val delayNanos: Long = 0L,
    ) {
        var start = -1L
    }

    /** 一个 dp 多少像素；由 [KeyGapLightLayer] 写入。 */
    var pixelsPerDp = 1f
    /** 最近按下的键：大写取消时往这个键收。 */
    private var lastTouch: Any? = null
    /** Shift 键：大写出现时从它扩开。 */
    private var caseAnchor: Any? = null
    /** 从 Shift 拖到字母上松手的位置（光层坐标）：大写收到这里。下一次按键就作废。 */
    private var aimPoint: Offset? = null
    private val bands = ArrayList<Band>()
    private val ring = Path()

    /** 按住一个有长按的键：线沿它的键边走一圈，[durationNanos] 走满（就是长按生效的那一刻）。 */
    private class Hold(
        val token: Any,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val durationNanos: Long,
    ) {
        var start = -1L
    }
    private val holds = ArrayList<Hold>()

    /** 移光标时跟着手指的色块：在空格上面那条键缝里。 */
    private var beadState = BEAD_OFF
    private var beadX = 0f
    private var beadY = 0f
    private var beadEndStart = -1L
    /** 光标走一格留下的刻度。 */
    private class Tick(val x: Float, val y: Float, val durationNanos: Long) { var start = -1L }
    private val ticks = ArrayList<Tick>()

    /**
     * 听写时的音量条：两排键之间的键缝各一条，从中间往两边长。每来一次音量整体往上推一条：
     * 最下面是最新的，越往上越早。存的是量化后的级数（见 [gapLightVoiceLevel]），0 = 不画。
     */
    private val voiceLevels = IntArray(GAP_LIGHT_VOICE_MAX_ROWS)
    private var voiceActive = false
    /**
     * 每来一次音量，各条从现在画着的宽度用 [GAP_LIGHT_VOICE_GLIDE_MS] 匀速长到新宽度，接上下一次音量：
     * 原来音量每 80 ms 才跳一下，看着一顿一顿（用户 11-14：帧率太低）。只在这段时间里走帧钟，没声音时不出帧。
     */
    private val voiceFrom = FloatArray(GAP_LIGHT_VOICE_MAX_ROWS)
    private var voiceGliding = false
    /** 这一段从哪一帧开始；帧钟还没走到是 -1，这时按起点画。 */
    private var voiceGlideStart = -1L

    private fun voiceProgress(): Float =
        if (!voiceGliding || voiceGlideStart < 0L) (if (voiceGliding) 0f else 1f)
        else ((now - voiceGlideStart).toFloat() / (GAP_LIGHT_VOICE_GLIDE_MS * 1_000_000f)).coerceIn(0f, 1f)

    private fun voiceShown(row: Int, progress: Float): Float =
        voiceFrom[row] + (voiceLevels[row] - voiceFrom[row]) * progress
    /** 两排键之间的键缝中线（像素），从下往上。 */
    private var gutters = FloatArray(0)

    private var layer: LayoutCoordinates? = null
    private val spots = LinkedHashMap<Any, Spot>()
    private var net: GapNet? = null
    private val netKeys = HashMap<Any, Int>()
    /** 每个键四个数：键帽在光层里的左、上、右、下。 */
    private var caps = FloatArray(0)
    /** 每个键四个数：键格（键帽往外到键缝中线，对齐以后的）在光层里的左、上、右、下；没进网的键第一个数是 NaN。 */
    private var cells = FloatArray(0)
    private var capCount = 0
    /** 键缝的半宽：横向（左右相邻的键之间）和纵向（上下两排之间）。 */
    private var gapX = 0f
    private var gapY = 0f
    private var mask: Path? = null
    private val pulses = ArrayList<Pulse>()
    private var now = 0L
    /** 只在画的阶段读：每出一帧只重画光层这一层，不引起重组。 */
    private val stamp = mutableIntStateOf(0)
    private val outline = Path()
    private var stroke = Stroke(1f)

    internal fun attach(coordinates: LayoutCoordinates) {
        layer = coordinates
        net = null
    }

    /** 键帽的位置；[pads] 是键帽到键格四边的内边距（左、上、右、下，像素）。 */
    internal fun place(token: Any, coordinates: LayoutCoordinates, pads: FloatArray) {
        val spot = spots[token]
        if (spot == null) spots[token] = Spot(coordinates, pads) else { spot.coordinates = coordinates; spot.pads = pads }
        net = null
    }

    internal fun remove(token: Any) {
        if (spots.remove(token) != null) net = null
    }

    /** 键的位置变过才重算；平时按键直接用上一次的网。算不出来（还没排好）就不画，绝不让动效把键盘弄崩。 */
    private fun ensureNet(): GapNet? {
        net?.let { return it }
        val layer = layer?.takeIf { it.isAttached } ?: return null
        return runCatching {
            val frames = FloatArray(spots.size * 4)
            val boxes = FloatArray(spots.size * 4)
            netKeys.clear()
            var count = 0
            var padX = 0f
            var padY = 0f
            for ((token, spot) in spots) {
                if (!spot.coordinates.isAttached) continue
                val box = layer.localBoundingBoxOf(spot.coordinates, clipBounds = false)
                val pads = spot.pads
                boxes[count * 4] = box.left; boxes[count * 4 + 1] = box.top
                boxes[count * 4 + 2] = box.right; boxes[count * 4 + 3] = box.bottom
                frames[count * 4] = box.left - pads[0]; frames[count * 4 + 1] = box.top - pads[1]
                frames[count * 4 + 2] = box.right + pads[2]; frames[count * 4 + 3] = box.bottom + pads[3]
                padX += (pads[0] + pads[2]) / 2f
                padY += (pads[1] + pads[3]) / 2f
                netKeys[token] = count
                count++
            }
            if (count == 0) return null
            val built = GapNet.build(frames.copyOf(count * 4), max(1.5f, padX / count), max(1.5f, padY / count))
            val grid = FloatArray(count * 4)
            for (key in 0 until count) {
                if (built.has(key)) {
                    grid[key * 4] = built.left(key); grid[key * 4 + 1] = built.top(key)
                    grid[key * 4 + 2] = built.right(key); grid[key * 4 + 3] = built.bottom(key)
                } else grid[key * 4] = Float.NaN
            }
            // 网建成了才一起换：键帽、键格、键缝宽度始终是同一次排版的。
            gapX = padX / count
            gapY = padY / count
            caps = boxes
            cells = grid
            capCount = count
            gutters = gapLightRowGutters(grid, count)
            mask = null
            net = built
            built
        }.getOrNull()
    }

    /** 这个键是 Shift：大写出现时从它扩开。 */
    internal fun anchorCase(token: Any) {
        caseAnchor = token
    }

    /** 从 Shift 拖到一个字母上松手了：[point] 是那个位置（光层坐标），接下来大写取消时收到这里。 */
    internal fun aim(point: Offset) {
        aimPoint = point
    }

    private fun centerOf(token: Any?, net: GapNet): Offset? {
        val key = netKeys[token ?: return null] ?: return null
        if (!net.has(key)) return null
        return Offset((net.left(key) + net.right(key)) / 2f, (net.top(key) + net.bottom(key)) / 2f)
    }

    private fun scaled(ms: Float, scale: Float): Long = (ms * 1_000_000f / config.speed * scale).toLong().coerceAtLeast(1L)

    private fun add(band: Band) {
        if (bands.size >= GAP_LIGHT_MAX_PULSES) bands.removeAt(0)
        bands += band
        running = true
        stamp.intValue++
    }

    /**
     * 字母的大小写变了（[upper] = 现在显示大写）。大写出现：从 Shift 扩开一次；大写消失：收回一次，
     * 收到刚才松手的那个字母（从 Shift 拖过去的）、或最近按的那个键（打完的那个大写字母、点掉大写的 Shift）。
     * 只认状态变化，所以一次按压不会按下、松开各触发一次。
     */
    internal fun caseChanged(upper: Boolean) {
        val config = config
        val aimed = aimPoint
        aimPoint = null
        if (!config.enabled || !config.switch) return
        val scale = motionScale?.scaleFactor ?: 1f
        if (scale <= 0f) return
        val net = ensureNet() ?: return
        val size = layer?.takeIf { it.isAttached }?.size ?: return
        val origin = (if (upper) centerOf(caseAnchor, net) ?: centerOf(lastTouch, net)
            else aimed ?: centerOf(lastTouch, net) ?: centerOf(caseAnchor, net)) ?: return
        wave(origin, upper, size.width, size.height, scale)
    }

    /**
     * 整块键盘换了意思（用户 11-14：点 Ctrl、切换语言也要有大小写那样的全键盘效果）：和大小写同一种色带。
     * [on] 时从刚按的那个键扩开（按住的符号键、点的语言键）；Ctrl 松开时收回到最近按的键
     * （另一根手指点的那个字母，或符号键自己）。跟「切换大小写」同一个开关。
     */
    internal fun modeChanged(on: Boolean) {
        val config = config
        if (!config.enabled || !config.switch) return
        val scale = motionScale?.scaleFactor ?: 1f
        if (scale <= 0f) return
        val net = ensureNet() ?: return
        val size = layer?.takeIf { it.isAttached }?.size ?: return
        val origin = centerOf(lastTouch, net) ?: return
        wave(origin, on, size.width, size.height, scale)
    }

    private fun wave(origin: Offset, expand: Boolean, width: Int, height: Int, scale: Float) {
        val config = config
        val size = androidx.compose.ui.unit.IntSize(width, height)
        // 方形按横竖里较远的一边算，菱形按横竖相加算：要走到最远的那个角。
        val dx = max(origin.x, size.width - origin.x)
        val dy = max(origin.y, size.height - origin.y)
        val far = (if (config.style == KeyGapLightStyle.DIAMOND) dx + dy else max(dx, dy)) + 8f * pixelsPerDp
        add(Band(BAND_WAVE, origin.x, origin.y, far, expand, scaled(far / pixelsPerDp / GAP_LIGHT_WAVE_DP_PER_MS, scale)))
    }

    /** 点了一下空格或回车（松手时）。 */
    internal fun tap(token: Any, kind: GapLightKind) {
        val config = config
        if (!config.enabled || !config.commit) return
        val scale = motionScale?.scaleFactor ?: 1f
        if (scale <= 0f) return
        val net = ensureNet() ?: return
        val size = layer?.takeIf { it.isAttached }?.size ?: return
        if (kind == GapLightKind.RISE) {
            val key = netKeys[token] ?: return
            if (!net.has(key)) return
            val x = (net.left(key) + net.right(key)) / 2f
            val half = min((net.right(key) - net.left(key)) / 2f, net.unit * 1.3f)
            add(Band(BAND_RISE, x, net.top(key), half, true, scaled(GAP_LIGHT_RISE_MS.toFloat(), scale)))
            add(Band(BAND_FLASH, x, 0f, size.width * .5f, true, scaled(GAP_LIGHT_FLASH_MS.toFloat(), scale), scaled(90f, scale)))
        } else if (kind == GapLightKind.SWEEP) {
            add(Band(BAND_SWEEP, 0f, 0f, 0f, true, scaled(GAP_LIGHT_SWEEP_MS.toFloat(), scale)))
            add(Band(BAND_FLASH, size.width / 2f, 0f, size.width.toFloat(), true, scaled(GAP_LIGHT_FLASH_MS.toFloat(), scale), scaled(130f, scale)))
        }
    }

    /** 按住空格移光标：色块跟着手指。[xInCell] 是手指在这个键的键格里的横坐标。 */
    internal fun bead(token: Any, xInCell: Float) {
        val config = config
        if (!config.enabled || !config.space) return
        if ((motionScale?.scaleFactor ?: 1f) <= 0f) return
        val net = ensureNet() ?: return
        val key = netKeys[token] ?: return
        if (!net.has(key)) return
        val spot = spots[token] ?: return
        val width = layer?.takeIf { it.isAttached }?.size?.width ?: return
        beadX = (caps[key * 4] - spot.pads[0] + xInCell).coerceIn(0f, width.toFloat())
        beadY = net.top(key)
        beadState = BEAD_ON
        stamp.intValue++
    }

    internal fun beadTick() {
        if (beadState != BEAD_ON) return
        if (ticks.size >= GAP_LIGHT_MAX_PULSES * 2) ticks.removeAt(0)
        ticks += Tick(beadX, beadY, scaled(GAP_LIGHT_TICK_MS.toFloat(), motionScale?.scaleFactor ?: 1f))
        running = true
        stamp.intValue++
    }

    internal fun beadEnd() {
        if (beadState != BEAD_ON) return
        beadState = BEAD_ENDING
        beadEndStart = -1L
        running = true
        stamp.intValue++
    }

    /**
     * 听写时每来一次音量调一次（音量本身每 80 ms 才报一次）；[level] 是 0～1，0 = 没在听或没声音，音量条收掉。
     * 量化后的级数变了才动：接着用 [GAP_LIGHT_VOICE_GLIDE_MS] 匀速长过去（走帧钟，只重画光层），级数没变、没声音时不出帧。
     */
    internal fun voice(level: Float) {
        val config = config
        val on = level > 0f && config.enabled && config.voice && (motionScale?.scaleFactor ?: 1f) > 0f && ensureNet() != null
        if (!on) {
            if (voiceActive) {
                voiceLevels.fill(0)
                voiceFrom.fill(0f)
                voiceGliding = false
                voiceActive = false
                stamp.intValue++
            }
            return
        }
        val newest = gapLightVoiceLevel(level)
        var changed = voiceLevels[0] != newest
        for (row in voiceLevels.size - 1 downTo 1) if (voiceLevels[row] != voiceLevels[row - 1]) changed = true
        if (!changed) return
        // 从现在画着的样子接着长，上一段没走完也不跳。
        val progress = voiceProgress()
        for (row in voiceFrom.indices) voiceFrom[row] = voiceShown(row, progress)
        var any = newest != 0
        for (row in voiceLevels.size - 1 downTo 1) {
            voiceLevels[row] = voiceLevels[row - 1]
            if (voiceLevels[row] != 0) any = true
        }
        voiceLevels[0] = newest
        voiceActive = any || voiceFrom.any { it > 0f }
        voiceGliding = true
        voiceGlideStart = -1L
        running = true
        stamp.intValue++
    }

    /**
     * 按住了一个有长按的键，[millis] 毫秒后长按生效。这个时长是真实的长按时限，不跟「速度」和系统动画缩放走。
     * 松手、拖走时调 [holdEnd]；走满了自己收掉，并从这个键往外放一次大一圈的按键动效。
     */
    internal fun holdStart(token: Any, millis: Long) {
        val config = config
        if (!config.enabled || !config.hold || millis <= 0L) return
        if ((motionScale?.scaleFactor ?: 1f) <= 0f) return
        val net = ensureNet() ?: return
        val key = netKeys[token] ?: return
        if (!net.has(key)) return
        holdEnd(token)
        if (holds.size >= GAP_LIGHT_MAX_PULSES) holds.removeAt(0)
        holds += Hold(token, net.left(key), net.top(key), net.right(key), net.bottom(key), millis * 1_000_000L)
        running = true
        stamp.intValue++
    }

    internal fun holdEnd(token: Any) {
        var index = holds.size - 1
        while (index >= 0) {
            if (holds[index].token === token) { holds.removeAt(index); stamp.intValue++ }
            index--
        }
    }

    /** 长按走满的那一下：从这个键往外放一次范围大一半的按键动效。 */
    private fun burst(hold: Hold, frameNanos: Long) {
        val config = config
        val net = net ?: return
        val key = netKeys[hold.token] ?: return
        if (!net.has(key)) return
        val reach = net.unit * config.reach * 1.5f
        val routes = if (config.style == KeyGapLightStyle.TRACE) net.routes(key, reach) else emptyList()
        if (pulses.size >= GAP_LIGHT_MAX_PULSES) pulses.removeAt(0)
        pulses += Pulse(
            false, hold.left, hold.top, hold.right, hold.bottom, routes, reach,
            dash = net.unit * (.45f + config.tail * .9f),
            durationNanos = scaled(GAP_LIGHT_PRESS_MS.toFloat(), motionScale?.scaleFactor ?: 1f),
        ).also { it.start = frameNanos - GAP_LIGHT_LEAD_NANOS }   // 走满的这一帧就亮，不等下一帧
    }

    /** 按下一个键。只记下这一次动效并叫醒帧钟，不在这里画。 */
    internal fun press(token: Any, pressed: GapLightKind) {
        val config = config
        lastTouch = token
        aimPoint = null
        // 「上屏和发送」关着时，空格和回车就当普通键：按下照样走线。
        val kind = if (!config.commit && (pressed == GapLightKind.RISE || pressed == GapLightKind.SWEEP)) GapLightKind.PRESS else pressed
        if ((kind != GapLightKind.PRESS && kind != GapLightKind.PULL) || !config.enabled) return
        val pull = kind == GapLightKind.PULL
        if (if (pull) !config.delete else !config.typing) return
        val scale = motionScale?.scaleFactor ?: 1f
        if (scale <= 0f) return
        val net = ensureNet() ?: return
        val key = netKeys[token] ?: return
        if (!net.has(key)) return
        val reach = net.unit * config.reach
        val routes = if (config.style == KeyGapLightStyle.TRACE) net.routes(key, reach) else emptyList()
        if (pulses.size >= GAP_LIGHT_MAX_PULSES) pulses.removeAt(0)
        pulses += Pulse(
            pull, net.left(key), net.top(key), net.right(key), net.bottom(key), routes, reach,
            dash = net.unit * (.45f + config.tail * .9f),
            durationNanos = scaled(GAP_LIGHT_PRESS_MS.toFloat(), scale),
        )
        running = true
        stamp.intValue++
    }

    /** 帧钟每帧调一次：推进时间，走完的动效拿掉；都走完就停钟。 */
    internal fun advance(frameNanos: Long) {
        now = frameNanos
        var index = pulses.size - 1
        while (index >= 0) {
            val pulse = pulses[index]
            if (pulse.start < 0L) pulse.start = frameNanos - GAP_LIGHT_LEAD_NANOS
            else if (frameNanos - pulse.start >= pulse.durationNanos) pulses.removeAt(index)
            index--
        }
        index = bands.size - 1
        while (index >= 0) {
            val band = bands[index]
            if (band.start < 0L) band.start = frameNanos - GAP_LIGHT_LEAD_NANOS
            else if (frameNanos - band.start - band.delayNanos >= band.durationNanos) bands.removeAt(index)
            index--
        }
        index = holds.size - 1
        while (index >= 0) {
            val hold = holds[index]
            if (hold.start < 0L) hold.start = frameNanos
            else if (frameNanos - hold.start >= hold.durationNanos) { holds.removeAt(index); burst(hold, frameNanos) }
            index--
        }
        index = ticks.size - 1
        while (index >= 0) {
            val tick = ticks[index]
            if (tick.start < 0L) tick.start = frameNanos - GAP_LIGHT_LEAD_NANOS
            else if (frameNanos - tick.start >= tick.durationNanos) ticks.removeAt(index)
            index--
        }
        if (beadState == BEAD_ENDING) {
            if (beadEndStart < 0L) beadEndStart = frameNanos
            else if (frameNanos - beadEndStart >= GAP_LIGHT_BEAD_FADE_NANOS) beadState = BEAD_OFF
        }
        if (voiceGliding) {
            if (voiceGlideStart < 0L) voiceGlideStart = frameNanos
            else if (frameNanos - voiceGlideStart >= GAP_LIGHT_VOICE_GLIDE_MS * 1_000_000L) {
                voiceGliding = false
                voiceActive = voiceLevels.any { it != 0 }
            }
        }
        // 色块停着不动时不用出帧：位置变了由 [bead] 自己叫重画。
        if (pulses.isEmpty() && bands.isEmpty() && holds.isEmpty() && ticks.isEmpty() && beadState != BEAD_ENDING &&
            !voiceGliding) running = false
        stamp.intValue++
    }

    internal fun draw(scope: DrawScope, color: Color) = with(scope) {
        stamp.intValue
        if (pulses.isEmpty() && bands.isEmpty() && holds.isEmpty() && ticks.isEmpty() && beadState == BEAD_OFF && !voiceActive) return@with
        val config = config
        val line = config.widthDp * density
        // 键缝里的线不比键缝宽：键帽半透明时也不会从键帽底下透出来。
        val lineAcross = min(line, gapY * 2f)   // 横着走的线（上下两排之间）
        val lineAlong = min(line, gapX * 2f)    // 竖着走的线（左右两个键之间）
        // 色带、方框、菱形会从键帽底下经过。键帽不透明时本来就只在键缝里看得见；键帽半透明时裁成只剩键缝，
        // 不然这儿透那儿不透。线路的线本身就在键缝里，不用裁。
        val underCaps = bands.isNotEmpty() || (pulses.isNotEmpty() && config.style != KeyGapLightStyle.TRACE)
        if (underCaps && translucentCaps) clipPath(gapMask()) {
            drawBands(config, color, line)
            if (config.style != KeyGapLightStyle.TRACE) drawPulses(config, color, line, lineAcross, lineAlong)
        } else {
            drawBands(config, color, line)
            if (config.style != KeyGapLightStyle.TRACE) drawPulses(config, color, line, lineAcross, lineAlong)
        }
        if (config.style == KeyGapLightStyle.TRACE) drawPulses(config, color, line, lineAcross, lineAlong)
        // 长按进度的线压在键格的边上，本来就在键缝里，不用裁。
        if (holds.isNotEmpty()) drawHolds(config, color, lineAcross, lineAlong)
        if (ticks.isNotEmpty() || beadState != BEAD_OFF) drawBead(config, color, lineAlong)
        // 音量条横穿整排，会从跨两排的高键底下经过：键帽半透明时同样裁到键缝里。
        if (voiceActive) {
            if (translucentCaps) clipPath(gapMask()) { drawVoice(config, color) } else drawVoice(config, color)
        }
    }

    /** 音量条：高度不超过键缝，两头是硬边；越早的一条越淡，按条分级，不做渐变。 */
    private fun DrawScope.drawVoice(config: KeyGapLightConfig, color: Color) {
        if (gapY < .5f) return
        val rows = min(gutters.size, voiceLevels.size)
        val inset = if (gapY > 2f) 1f else 0f
        val center = size.width / 2f
        val progress = voiceProgress()
        for (row in 0 until rows) {
            val level = voiceShown(row, progress)
            if (level <= 0f) continue
            val half = center * level / GAP_LIGHT_VOICE_LEVELS
            val y = gutters[row]
            fill(center - half, y - gapY + inset, center + half, y + gapY - inset, color,
                config.brightness * (1f - row.toFloat() / (rows + 1)))
        }
    }

    /** 移光标：色块和刻度都画在空格上面那条键缝里，高度不超过键缝，键帽半透明也透不出来。 */
    private fun DrawScope.drawBead(config: KeyGapLightConfig, color: Color, lineAlong: Float) {
        if (gapY < .5f) return
        for (index in ticks.indices) {
            val tick = ticks[index]
            val elapsed = if (tick.start < 0L) GAP_LIGHT_LEAD_NANOS else now - tick.start
            val alpha = gapLightCut((elapsed.toFloat() / tick.durationNanos).coerceIn(0f, 1f), .3f) * config.brightness
            val half = max(lineAlong, 1f) / 2f
            if (alpha > 0f) block(tick.x - half, tick.y - gapY, tick.x + half, tick.y + gapY, color, alpha, vertical = true)
        }
        if (beadState == BEAD_OFF) return
        val fade = if (beadState != BEAD_ENDING) 1f
            else if (beadEndStart < 0L) 1f
            else 1f - ((now - beadEndStart).toFloat() / GAP_LIGHT_BEAD_FADE_NANOS).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val half = GAP_LIGHT_BEAD_HALF_DP * pixelsPerDp
        // 上下各让一个像素，色块和两边的键帽之间留一条缝，看得出是一块东西。
        val inset = if (gapY > 2f) 1f else 0f
        fill(max(0f, beadX - half), beadY - gapY + inset, min(size.width, beadX + half), beadY + gapY - inset, color, config.brightness * fade)
    }

    /** 长按进度：从键的上边正中出发，顺时针沿键边走，走过的部分是实的。 */
    private fun DrawScope.drawHolds(config: KeyGapLightConfig, color: Color, lineAcross: Float, lineAlong: Float) {
        if (lineAcross < .5f && lineAlong < .5f) return
        val h = lineAcross / 2f
        val v = lineAlong / 2f
        for (index in holds.indices) {
            val hold = holds[index]
            val elapsed = if (hold.start < 0L) 0L else now - hold.start
            val width = hold.right - hold.left
            val height = hold.bottom - hold.top
            var left = (elapsed.toFloat() / hold.durationNanos).coerceIn(0f, 1f) * 2f * (width + height)
            if (left <= 0f) continue
            val middle = (hold.left + hold.right) / 2f
            // 上边右半段：从正中往右。
            var piece = min(left, width / 2f)
            block(middle, hold.top - h, middle + piece + if (piece >= width / 2f) v else 0f, hold.top + h, color, config.brightness, vertical = false)
            left -= width / 2f
            if (left <= 0f) continue
            // 右边：从上往下。
            piece = min(left, height)
            block(hold.right - v, hold.top + h, hold.right + v, hold.top + piece + if (piece >= height) h else 0f, color, config.brightness, vertical = true)
            left -= height
            if (left <= 0f) continue
            // 下边：从右往左。
            piece = min(left, width)
            block(hold.right - piece - if (piece >= width) v else 0f, hold.bottom - h, hold.right - v, hold.bottom + h, color, config.brightness, vertical = false)
            left -= width
            if (left <= 0f) continue
            // 左边：从下往上。
            piece = min(left, height)
            block(hold.left - v, hold.bottom - piece - if (piece >= height) h else 0f, hold.left + v, hold.bottom - h, color, config.brightness, vertical = true)
            left -= height
            if (left <= 0f) continue
            // 上边左半段：从左往正中。
            piece = min(left, width / 2f)
            block(hold.left + v, hold.top - h, hold.left + piece, hold.top + h, color, config.brightness, vertical = false)
        }
    }

    private fun DrawScope.drawBands(config: KeyGapLightConfig, color: Color, line: Float) {
        if (bands.isEmpty()) return
        val tail = (8f + config.tail * 110f) * density
        for (index in bands.indices) {
            val band = bands[index]
            val elapsed = (if (band.start < 0L) GAP_LIGHT_LEAD_NANOS else now - band.start) - band.delayNanos
            if (elapsed < 0L) continue
            val progress = (elapsed.toFloat() / band.durationNanos).coerceIn(0f, 1f)
            when (band.type) {
                BAND_WAVE -> drawWave(band, progress, config, color, tail)
                BAND_RISE -> {
                    // 上沿是硬边，从键的上边走到按键区顶边外面；后面的尾巴不会伸到键的上边以下。
                    val length = tail * .7f
                    val top = band.y + (-length - band.y) * gapLightEase(progress)
                    steps(band.x - band.extent, band.x + band.extent, top, length, band.y, color,
                        gapLightCut(progress, .8f) * .9f * config.brightness)
                }
                BAND_SWEEP -> {
                    val top = size.height - (size.height + tail) * gapLightEase(progress)
                    steps(0f, size.width, top, tail, size.height, color, gapLightCut(progress, .8f) * .9f * config.brightness)
                }
                else -> {
                    // 顶边的一条实心亮条：从 x 往两边长开，长满以后收掉。
                    val grown = .04f + .96f * gapLightEase((progress / .6f).coerceAtMost(1f))
                    val half = band.extent * grown / 2f
                    val height = max(2f * density, line)
                    fill(band.x - half, 0f, band.x + half, height, color, gapLightCut(progress, .6f) * config.brightness)
                }
            }
        }
    }

    /** 一块往上走的色带：上沿 [top] 是硬边，往下 [length] 这么长分级变淡，最低画到 [floor]。 */
    private fun DrawScope.steps(left: Float, right: Float, top: Float, length: Float, floor: Float, color: Color, alpha: Float) {
        if (alpha <= 0f) return
        val step = length / GAP_LIGHT_TAIL_STEPS
        for (level in 0 until GAP_LIGHT_TAIL_STEPS) {
            val a = top + level * step
            val b = min(floor, a + step)
            if (b <= a) break
            fill(left, a, right, b, color, alpha * (1f - level.toFloat() / GAP_LIGHT_TAIL_STEPS))
        }
    }

    /**
     * 切换扩散：方形（样式是菱形时用菱形）的色带匀速扩开，外沿是硬边、尾巴在里面；
     * 收回时反过来，从外面收到那个点，硬边在里侧、尾巴在外面。
     */
    private fun DrawScope.drawWave(band: Band, progress: Float, config: KeyGapLightConfig, color: Color, tailLength: Float) {
        val alpha = gapLightCut(progress, .6f) * .85f * config.brightness
        if (alpha <= 0f) return
        val diamond = config.style == KeyGapLightStyle.DIAMOND
        val radius = band.extent * (if (band.expand) progress else 1f - progress)
        val tail = if (diamond) tailLength * 1.2f else tailLength
        val reach = if (band.expand) min(tail, radius) else tail
        val step = reach / GAP_LIGHT_TAIL_STEPS
        if (step <= 0f) return
        for (level in 0 until GAP_LIGHT_TAIL_STEPS) {
            val inner: Float
            val outer: Float
            if (band.expand) { outer = radius - level * step; inner = outer - step }
            else { inner = radius + level * step; outer = inner + step }
            val shade = alpha * (1f - level.toFloat() / GAP_LIGHT_TAIL_STEPS)
            val x = band.x
            val y = band.y
            if (diamond) {
                ring.rewind()
                ring.fillType = PathFillType.EvenOdd
                ring.moveTo(x, y - outer); ring.lineTo(x + outer, y); ring.lineTo(x, y + outer); ring.lineTo(x - outer, y); ring.close()
                if (inner > 0f) {
                    ring.moveTo(x, y - inner); ring.lineTo(x + inner, y); ring.lineTo(x, y + inner); ring.lineTo(x - inner, y); ring.close()
                }
                drawPath(ring, color, alpha = shade.coerceIn(0f, 1f))
            } else {
                fill(x - outer, y - outer, x + outer, y - inner, color, shade)
                fill(x - outer, y + inner, x + outer, y + outer, color, shade)
                fill(x - outer, y - inner, x - inner, y + inner, color, shade)
                fill(x + inner, y - inner, x + outer, y + inner, color, shade)
            }
        }
    }

    /** 一块实色，边对齐到整像素；超出按键区的部分由光层自己裁掉。 */
    private fun DrawScope.fill(left: Float, top: Float, right: Float, bottom: Float, color: Color, alpha: Float) {
        val l = round(left).coerceIn(0f, size.width)
        val r = round(right).coerceIn(0f, size.width)
        val t = round(top).coerceIn(0f, size.height)
        val b = round(bottom).coerceIn(0f, size.height)
        if (r <= l || b <= t || alpha <= 0f) return
        drawRect(color, Offset(l, t), Size(r - l, b - t), alpha = alpha.coerceIn(0f, 1f))
    }

    private fun DrawScope.drawPulses(config: KeyGapLightConfig, color: Color, line: Float, lineAcross: Float, lineAlong: Float) {
        for (index in pulses.indices) {
            val pulse = pulses[index]
            val elapsed = if (pulse.start < 0L) GAP_LIGHT_LEAD_NANOS else now - pulse.start
            val raw = (elapsed.toFloat() / pulse.durationNanos).coerceIn(0f, 1f)
            // 删除是按键的动画倒着放。
            val progress = if (pulse.pull) 1f - raw else raw
            when (config.style) {
                KeyGapLightStyle.TRACE -> drawTrace(pulse, progress, config, color, lineAcross, lineAlong)
                else -> drawEchoes(pulse, progress, config, color, line)
            }
        }
    }

    /** 线路：键边一圈先亮，短线沿键缝网往外走，头是实的，后面分级变淡。 */
    private fun DrawScope.drawTrace(pulse: Pulse, progress: Float, config: KeyGapLightConfig, color: Color,
        lineAcross: Float, lineAlong: Float) {
        if (lineAcross < .5f && lineAlong < .5f) return
        val box = gapLightCut(progress, .35f) * config.brightness
        if (box > 0f) frame(pulse.left, pulse.top, pulse.right, pulse.bottom, lineAcross, lineAlong, color, box)
        val reached = pulse.reach * gapLightEase(progress)
        val fade = gapLightCut(progress, .75f) * config.brightness
        if (fade <= 0f) return
        for (layer in 0 until config.layers) {
            val front = reached - layer * pulse.dash * 1.4f
            if (front <= 0f) continue
            val alpha = fade * (1f - layer * .28f)
            for (i in pulse.routes.indices) {
                val route = pulse.routes[i]
                val meet = route.meet
                val thickness = if (route.ay == route.by) lineAcross else lineAlong
                if (thickness < .5f) continue
                piece(route.ax, route.ay, route.bx, route.by, route.length, route.da, meet, front, pulse.dash, thickness, color, alpha)
                piece(route.bx, route.by, route.ax, route.ay, route.length, route.db, route.length - meet, front, pulse.dash, thickness, color, alpha)
            }
        }
    }

    /**
     * 一小段线在一段键缝上露出来的部分。从 (ax, ay) 这头量：这头离出发键 [offset]，光头在路程 [front] 的地方，
     * 往回 [dash] 这么长是拖尾，最远画到 [limit]（另一头来的光在那里接上）。
     */
    private fun DrawScope.piece(ax: Float, ay: Float, bx: Float, by: Float, length: Float, offset: Float, limit: Float,
        front: Float, dash: Float, thickness: Float, color: Color, alpha: Float) {
        val head = front - offset
        val from = max(0f, head - dash)
        val to = min(limit, head)
        if (to <= from || length <= 0f) return
        val ux = (bx - ax) / length
        val uy = (by - ay) / length
        val step = dash / GAP_LIGHT_TAIL_STEPS
        for (level in 0 until GAP_LIGHT_TAIL_STEPS) {
            val a = max(from, head - (level + 1) * step)
            val b = min(to, head - level * step)
            if (b <= a) continue
            val x0 = ax + ux * a; val x1 = ax + ux * b
            val y0 = ay + uy * a; val y1 = ay + uy * b
            val shade = alpha * (1f - level.toFloat() / GAP_LIGHT_TAIL_STEPS)
            if (ay == by) block(min(x0, x1), ay - thickness / 2f, max(x0, x1), ay + thickness / 2f, color, shade, vertical = false)
            else block(ax - thickness / 2f, min(y0, y1), ax + thickness / 2f, max(y0, y1), color, shade, vertical = true)
        }
    }

    /** 方框、菱形：从按下的键往外扩的硬边轮廓，后面跟着几道。 */
    private fun DrawScope.drawEchoes(pulse: Pulse, progress: Float, config: KeyGapLightConfig, color: Color, line: Float) {
        for (layer in 0 until config.layers) {
            val local = progress - layer * .2f
            if (local <= 0f || local >= 1f) continue
            val grow = pulse.reach * gapLightEase(local)
            val alpha = gapLightCut(local, .55f) * config.brightness * (1f - layer * .28f)
            if (alpha <= 0f) continue
            if (config.style == KeyGapLightStyle.FRAME) {
                frame(pulse.left - grow, pulse.top - grow, pulse.right + grow, pulse.bottom + grow, line, line, color, alpha)
            } else {
                val cx = (pulse.left + pulse.right) / 2f
                val cy = (pulse.top + pulse.bottom) / 2f
                val radius = (pulse.right - pulse.left + pulse.bottom - pulse.top) * .3f + grow * 1.25f
                if (stroke.width != line) stroke = Stroke(width = line, join = StrokeJoin.Miter)
                outline.rewind()
                outline.moveTo(cx, cy - radius); outline.lineTo(cx + radius, cy)
                outline.lineTo(cx, cy + radius); outline.lineTo(cx - radius, cy)
                outline.close()
                drawPath(outline, color, alpha = alpha, style = stroke)
            }
        }
    }

    /** 一个硬边的框：四条实心的边，线的中心压在框上。[across] 是上下两条边的粗细，[along] 是左右两条。 */
    private fun DrawScope.frame(left: Float, top: Float, right: Float, bottom: Float, across: Float, along: Float,
        color: Color, alpha: Float) {
        val h = across / 2f
        val v = along / 2f
        if (across >= .5f) {
            block(left - v, top - h, right + v, top + h, color, alpha, vertical = false)
            block(left - v, bottom - h, right + v, bottom + h, color, alpha, vertical = false)
        }
        if (along >= .5f) {
            block(left - v, top + h, left + v, bottom - h, color, alpha, vertical = true)
            block(right - v, top + h, right + v, bottom - h, color, alpha, vertical = true)
        }
    }

    /**
     * 一块实色，边对齐到整像素（边界清楚，不出半像素的虚边）。
     * 压在按键区边线上的线整条挪进来，不被边线切掉一半，也不画到按键区外面。
     */
    private fun DrawScope.block(left: Float, top: Float, right: Float, bottom: Float, color: Color, alpha: Float, vertical: Boolean) {
        var l = round(left); var t = round(top); var r = round(right); var b = round(bottom)
        if (vertical) {
            if (r - l < 1f) r = l + 1f
            if (r <= 0f || l >= size.width) return   // 整条在外面（往外扩的方框走出去了）：不画
            if (l < 0f) { r -= l; l = 0f } else if (r > size.width) { l -= r - size.width; r = size.width }
        } else {
            if (b - t < 1f) b = t + 1f
            if (b <= 0f || t >= size.height) return
            if (t < 0f) { b -= t; t = 0f } else if (b > size.height) { t -= b - size.height; b = size.height }
        }
        if (r <= l || b <= t) return
        drawRect(color, Offset(l, t), Size(r - l, b - t), alpha = alpha.coerceIn(0f, 1f))
    }

    /**
     * 键缝的形状：每个登记过的键，键格减去键帽，合在一起。只有键帽半透明、样式又会画到键帽底下时才用。
     * 只认登记过的键：没登记的半透明面板（侧边符号栏之类）和没有键的空当底下都不画，不会这儿透那儿不透。
     */
    private fun gapMask(): Path {
        mask?.let { return it }
        val path = Path()
        path.fillType = PathFillType.EvenOdd
        val radius = CornerRadius(cornerRadius)
        for (index in 0 until capCount) {
            val left = cells[index * 4]
            if (left.isNaN()) continue
            path.addRect(Rect(left, cells[index * 4 + 1], cells[index * 4 + 2], cells[index * 4 + 3]))
            path.addRoundRect(RoundRect(caps[index * 4], caps[index * 4 + 1], caps[index * 4 + 2], caps[index * 4 + 3], radius))
        }
        mask = path
        return path
    }
}

/**
 * 这块键盘的键缝光层；开关关着（默认）或 [allowed] 为 false 时返回 null，后面什么都不挂。
 * 蜂窝布局的六边形键没有横平竖直的键缝，不画。
 */
@Composable
internal fun rememberKeyGapLight(allowed: Boolean = true): KeyGapLightField? {
    val config = LocalKeyboardInputPreferences.current.gapLight
    if (!config.enabled || !allowed) return null
    val field = remember { KeyGapLightField() }
    SideEffect { field.config = config }
    return field
}

/**
 * 光层本身。放在按键布局根 Box 里、所有键的前面（先画 = 在键帽底下），大小和按键区一样。
 * 自带一层：每帧只重画这一层，键上的字不跟着重画；超出按键区的部分被这一层裁掉。
 */
@Composable
internal fun KeyGapLightLayer(field: KeyGapLightField?, modifier: Modifier = Modifier) {
    if (field == null) return
    val palette = LocalKeyboardPalette.current
    val color = palette?.accent ?: MaterialTheme.colorScheme.primary
    val radius = with(LocalDensity.current) { LocalKeyCornerRadius.current.toPx() }
    val translucent = LocalKeyboardInputPreferences.current.frostedGlass.enabled ||
        (palette != null && (palette.key.alpha < 1f || palette.function.alpha < 1f))
    val pixelsPerDp = LocalDensity.current.density
    SideEffect {
        field.cornerRadius = radius
        field.translucentCaps = translucent
        field.pixelsPerDp = pixelsPerDp
    }
    // 听写音量：分项关着或宿主没提供就不听。只在这里读音量，变了只重画光层这一层。
    val voiceWanted = LocalKeyboardInputPreferences.current.gapLight.voice
    val voiceSource = LocalKeyGapLightVoice.current
    val voice = if (voiceWanted) voiceSource else null
    LaunchedEffect(field, voice) {
        if (voice == null) field.voice(0f) else snapshotFlow { voice() }.collect { field.voice(it) }
    }
    LaunchedEffect(field) {
        field.motionScale = coroutineContext[MotionDurationScale]
        // 有动效在走才向系统要帧；走完就停在这里等下一次按键。
        snapshotFlow { field.running }.collect { running ->
            if (running) while (field.running) withFrameNanos { field.advance(it) }
        }
    }
    Spacer(modifier
        .onGloballyPositioned { field.attach(it) }
        .graphicsLayer { clip = true }
        .drawBehind { field.draw(this, color) })
}

/**
 * 挂在键帽上（视觉内边距之后）：登记键帽的位置，旁观按下。不消费触摸，不影响拂动和长按。
 * 没有光层时返回空 Modifier。
 */
@Composable
internal fun keyGapLightSource(
    kind: GapLightKind = GapLightKind.PRESS, caseAnchor: Boolean = false,
    /** 这个键有长按、按这么多毫秒生效（和手势里用的是同一个数）；没有长按传 null。 */
    holdMillis: Long? = null,
    /** 键自己报「点了一下并且生效了」、移光标；给了它，就不再靠旁观触摸判断点按。 */
    trigger: KeyGapLightTrigger? = null,
): Modifier {
    val field = LocalKeyGapLight.current ?: return Modifier
    val token = remember(field) { Any() }
    val padding = scaledKeyVisualPadding()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val pads = remember(padding, density, direction) {
        with(density) {
            floatArrayOf(padding.calculateLeftPadding(direction).toPx(), padding.calculateTopPadding().toPx(),
                padding.calculateRightPadding(direction).toPx(), padding.calculateBottomPadding().toPx())
        }
    }
    val currentKind by rememberUpdatedState(kind)
    val currentPads by rememberUpdatedState(pads)
    val currentHold by rememberUpdatedState(holdMillis)
    val currentTrigger by rememberUpdatedState(trigger)
    if (trigger != null) {
        DisposableEffect(trigger, field, token) {
            trigger.field = field
            trigger.token = token
            onDispose { if (trigger.field === field) { trigger.field = null; trigger.token = null } }
        }
        SideEffect { trigger.kind = kind }
    }
    DisposableEffect(field, token) { onDispose { field.remove(token) } }
    if (caseAnchor) SideEffect { field.anchorCase(token) }
    return Modifier
        .onGloballyPositioned { field.place(token, it, currentPads) }
        .pointerInput(field) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val kind = currentKind
                field.press(token, kind)
                if (kind == GapLightKind.RISE || kind == GapLightKind.SWEEP) {
                    // 键自己会报点按（[KeyGapLightTrigger.tap]）时听它的，这里不猜。
                    if (currentTrigger != null) return@awaitEachGesture
                    // 没接的键：旁观到松手。没拖动、也没按成长按，才算点了一下。
                    var moved = false
                    var upTime = -1L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) { upTime = change.uptimeMillis; break }
                    }
                    if (upTime >= 0L && !moved && upTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        field.tap(token, kind)
                    }
                } else {
                    val hold = currentHold ?: return@awaitEachGesture
                    // 长按进度：旁观到长按会被取消的那一刻。和按键手势同一套规则——松手、横竖任一方向挪出触摸阈值、
                    // 同一个键上又按下一根手指，长按都不算了。
                    field.holdStart(token, hold)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (event.changes.any { it.id != down.id && it.pressed && !it.previousPressed }) break
                            val moved = change.position - down.position
                            if (abs(moved.x) > viewConfiguration.touchSlop || abs(moved.y) > viewConfiguration.touchSlop) break
                        }
                    } finally {
                        field.holdEnd(token)
                    }
                }
            }
        }
}

/**
 * 整块键盘换了意思就告诉光层（[KeyGapLightField.modeChanged]）：[state] 每变一次画一次，[on] 决定扩开还是收回。
 * 用在按住符号键当 Ctrl（键面换成快捷键时扩开、换回字母时收回）和中英、语言切换（每切一次从语言键扩开）。
 * 第一次组合只记下状态，不画。
 */
@Composable
internal fun KeyGapLightMode(field: KeyGapLightField?, state: Any?, on: Boolean = true) {
    if (field == null) return
    val shown = remember(field) { arrayOf(state) }
    SideEffect {
        if (shown[0] != state) {
            shown[0] = state
            field.modeChanged(on)
        }
    }
}

/**
 * 字母大小写的显示变了就告诉光层（切换扩散）。放在按键布局里，[upper] 传当前是不是显示大写。
 * 第一次组合只记下状态，不画。
 */
@Composable
internal fun KeyGapLightCase(field: KeyGapLightField?, upper: Boolean) {
    if (field == null) return
    val shown = remember(field) { booleanArrayOf(upper) }
    SideEffect {
        if (shown[0] != upper) {
            shown[0] = upper
            field.caseChanged(upper)
        }
    }
}
