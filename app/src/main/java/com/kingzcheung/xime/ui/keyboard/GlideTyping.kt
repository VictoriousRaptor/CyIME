package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.glide.GlideTracker

internal val LocalLetterGlide = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 滑行时手指走过的轨迹（键盘本地坐标），抬手即清空。
 * [gesture] 在每次确认为滑行时加一：上一笔还没算完的预览对不上就丢掉。
 */
internal class GlideTrail {
    var points by mutableStateOf<List<Offset>>(emptyList())
    var gesture = 0
        private set

    fun startGesture() { gesture++ }
}

/**
 * 滑行实时预览的后台识别：每笔一份（[gesture]），[english] 是这一笔开头取的英文上下文规则。
 * 同一时间只有一个 [job]；它在算或歇着时手指又动了就记 [moved]，算完歇够再按最新轨迹算一次。
 */
internal class GlidePreviewJob {
    var gesture = -1
        private set
    var job: kotlinx.coroutines.Job? = null
    var moved = false
    var points: (() -> Pair<FloatArray, FloatArray>)? = null
    var english: ((List<com.kingzcheung.xime.glide.GlideCandidate>, (String) -> String) -> GlideEnglishPreview)? = null

    fun start(gesture: Int) {
        job?.cancel(); job = null
        this.gesture = gesture
        moved = false
        points = null
        english = null
    }

    fun stop() { job?.cancel(); job = null; points = null }
}

data class GlideEnglishPreview(val text: String, val candidates: List<String>)

/** 实时预览两次识别之间至少隔这么久；手机上一次中文识别约十几到三十毫秒。 */
internal const val GLIDE_PREVIEW_INTERVAL_MS = 60L

/**
 * 在字母区上方观察触摸（Initial 阶段，先于按键）。确认是滑行后才消费事件，
 * 按键随即取消自己的点按、上下滑和长按；确认之前一律不干预。
 * [rootOrigin] 把本地坐标换到按键记录位置所用的根坐标。
 */
@Composable
internal fun Modifier.glideTyping(
    enabled: Boolean,
    letterGeometry: LetterGeometry?,
    rootOrigin: () -> Offset,
    trail: GlideTrail,
    onTrace: (xs: FloatArray, ys: FloatArray) -> Unit,
    /** 确认为滑行后手指每移动一批调用一次（实时预览用），参数取当前整条轨迹（根坐标），只能在主线程调用。 */
    onProgress: ((points: () -> Pair<FloatArray, FloatArray>) -> Unit)? = null,
    onEnd: (() -> Unit)? = null,
): Modifier {
    // 回调每次重组都会换新，不能作为 pointerInput 的 key，否则正在进行的滑行会被打断。
    val currentOnTrace by rememberUpdatedState(onTrace)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOrigin by rememberUpdatedState(rootOrigin)
    val holdMs by rememberUpdatedState(LocalKeyboardInputPreferences.current.glideHoldMs)
    if (!enabled || letterGeometry == null) return this
    return pointerInput(letterGeometry, trail) { awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val keys = letterGeometry.snapshot()
        if (keys.size < 26) return@awaitEachGesture
        val origin = currentOrigin()
        val tracker = GlideTracker(keys, viewConfiguration.touchSlop,
            down.position.x + origin.x, down.position.y + origin.y, down.uptimeMillis, holdMs)
        if (tracker.abandoned) return@awaitEachGesture
        val local = arrayListOf(down.position)
        var traced = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // 另一根手指按下（快速连打）时放弃，不影响任何按键。
                if (!tracker.claimed && event.changes.any { it.id != down.id && it.pressed }) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!tracker.claimed && change.isConsumed) break
                // 抬手也是最后一个轨迹点：短滑行可能在这里才到达相邻字母。
                // 历史采样必须用各自的时刻，不能把整批都算成最后一刻。
                change.historical.forEach { sample ->
                    local += sample.position
                    tracker.move(sample.position.x + origin.x, sample.position.y + origin.y, sample.uptimeMillis, change.uptimeMillis)
                }
                local += change.position
                tracker.move(change.position.x + origin.x, change.position.y + origin.y, change.uptimeMillis)
                if (tracker.abandoned) break
                if (!change.pressed) {
                    if (tracker.claimed) {
                        change.consume()
                        traced = true
                        val xs = tracker.xs(); val ys = tracker.ys()
                        // 「贡献滑行轨迹」页开着时才有人收，平时直接返回。
                        com.kingzcheung.xime.glide.GlideSampleRecorder.record("qwerty", keys, xs, ys, tracker.ts())
                        currentOnTrace(xs, ys)
                    }
                    break
                }
                if (tracker.claimed) {
                    if (trail.points.isEmpty()) trail.startGesture()
                    change.consume()
                    trail.points = local.toList()
                    currentOnProgress?.invoke { tracker.xs() to tracker.ys() }
                }
            }
        } finally {
            if (tracker.claimed && !traced) currentOnEnd?.invoke()
            trail.points = emptyList()
        }
    } }
}

/**
 * 滑行轨迹（用户 11-10 对照 Gboard 截图：原来 5dp 的半透明灰线又细又淡，整条留到抬手，盖住一片键）。
 * 现在只画手指最近走过的 [GLIDE_TRAIL_LENGTH] 一段：手指处最粗最实，往后渐细渐淡；颜色用主题强调色，
 * 强调色是灰色（饱和度低）时换成醒目的蓝，深浅主题都看得清。
 */
@Composable
internal fun GlideTrailOverlay(trail: GlideTrail, color: Color, modifier: Modifier = Modifier) {
    val stroke = glideTrailColor(color)
    Canvas(modifier) {
        val points = trail.points
        if (points.size < 2) return@Canvas
        val tail = GLIDE_TRAIL_LENGTH.toPx()
        val head = GLIDE_TRAIL_HEAD_WIDTH.toPx()
        val thin = GLIDE_TRAIL_TAIL_WIDTH.toPx()
        // 从手指往回走，走过的长度决定这一小段的粗细和透明度。
        var walked = 0f
        var index = points.size - 1
        while (index > 0 && walked < tail) {
            val a = points[index]; val b = points[index - 1]
            val length = (a - b).getDistance()
            if (length > 0f) {
                val fade = 1f - (walked + length / 2f) / tail
                if (fade <= 0f) break
                drawLine(stroke.copy(alpha = stroke.alpha * (0.15f + 0.85f * fade)), a, b,
                    strokeWidth = thin + (head - thin) * fade, cap = StrokeCap.Round)
            }
            walked += length
            index--
        }
    }
}

/** 轨迹颜色：强调色够鲜艳就用它，灰色系主题（饱和度不到 0.3）换成蓝色。 */
internal fun glideTrailColor(accent: Color): Color {
    val max = maxOf(accent.red, accent.green, accent.blue)
    val min = minOf(accent.red, accent.green, accent.blue)
    val saturation = if (max <= 0f) 0f else (max - min) / max
    return if (saturation >= 0.3f) accent.copy(alpha = 0.9f) else GLIDE_TRAIL_FALLBACK
}

internal val GLIDE_TRAIL_FALLBACK = Color(0xE6448AFF)
internal val GLIDE_TRAIL_LENGTH = 240.dp
internal val GLIDE_TRAIL_HEAD_WIDTH = 10.dp
internal val GLIDE_TRAIL_TAIL_WIDTH = 2.dp
