package com.kingzcheung.xime.ui.theme

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.BackgroundMotionEffect
import com.kingzcheung.xime.settings.BackgroundMotionPreferences
import com.kingzcheung.xime.settings.BackgroundMotionSettings
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 动态背景这一帧的样子：先以背景框中心放大 [scale] 倍（≥ 1），再平移 [shiftX]、[shiftY]（框宽、框高的比例）。
 * |shift| ≤ (scale − 1) / 2 时图片始终盖满原来的框，四边不会露出底色。
 *
 * [fadeTo] 不为空时，同一张图再按 [fadeTo] 的样子画一层，不透明度 [fadeAlpha]（0～1）盖在上面：
 * 放大、缩小走到头后用它渐变回起点，不再倒着放回去（用户 11-08：放大和缩小来回交替，看起来是一样的）。
 */
internal data class BackgroundMotionTransform(
    val scale: Float,
    val shiftX: Float,
    val shiftY: Float,
    val fadeTo: BackgroundMotionTransform? = null,
    val fadeAlpha: Float = 0f,
) {
    val isIdentity: Boolean get() = scale == 1f && shiftX == 0f && shiftY == 0f && (fadeTo == null || fadeAlpha <= 0f)

    /** 画面上占多数的那一层（渐变过半后是 [fadeTo]）：只能画一层的地方（按键边缘折射）用它。 */
    val dominant: BackgroundMotionTransform
        get() = if (fadeTo != null && fadeAlpha >= .5f) fadeTo else copy(fadeTo = null, fadeAlpha = 0f)

    companion object {
        val Identity = BackgroundMotionTransform(1f, 0f, 0f)
    }
}

/**
 * 动态背景的轨迹，只和走过的时间有关（键盘收起时时间停住，再弹出接着走，不跳）。
 *
 * - 放大：一段 [shotMs] 从原样慢慢放大到 [MAX_SCALE]，停在那里用 [RETURN_FADE_MS] 渐变回原样（不倒着缩回去）。
 * - 缩小：从放大慢慢退回原样，再渐变回放大的样子，接着往下缩。
 * - 平移：保持放大，从左慢慢移到右，再从右移回左。
 * - 轮换：放大并移向左边 → 平移到右边 → 缩回原样，三段首尾相接，不回跳。
 * 每段两头都缓入缓出。
 */
internal object BackgroundMotionPath {
    /** 放大 12%：图片四边各多出 6% 用来平移，键盘上看得出在动，又不至于把图裁掉太多。 */
    const val MAX_SCALE = 1.12f
    const val MAX_SHIFT = (MAX_SCALE - 1f) / 2f

    /** 最多每秒 20 帧：放大 12% 走 18 秒，每帧不到半个像素，再多帧也看不出更顺，只多耗电。 */
    const val FRAME_INTERVAL_MS = 50L

    /**
     * 动得慢的时候（每段开头结尾、慢速）不必每 50 ms 出一帧：画面上走得最快的那个角，在下一帧之前移不到
     * [MAX_STEP_PX] 像素，就多等一会儿再出，最长 [MAX_FRAME_INTERVAL_MS]。每一帧的样子和原来一样（同一条轨迹），
     * 只是少出那些挪不到半个像素、眼睛分不出的帧。用户 11-23：动态背景是键盘显示时的耗电大头，又不想看出变化。
     */
    const val MAX_STEP_PX = 0.5f
    const val MAX_FRAME_INTERVAL_MS = 400L

    /** 放大、缩小走到头后渐变回起点的时长，和轮播换图的淡入一样长。 */
    const val RETURN_FADE_MS = 800L

    /** 一圈多长：走完回到开头的样子。轮播在每圈结束时换下一张。 */
    fun cycleMs(effect: BackgroundMotionEffect, shotMs: Long): Long {
        val shot = shotMs.coerceAtLeast(1L)
        return when (effect) {
            BackgroundMotionEffect.ZOOM_IN, BackgroundMotionEffect.ZOOM_OUT -> shot + RETURN_FADE_MS
            BackgroundMotionEffect.PAN -> 2 * shot
            BackgroundMotionEffect.CYCLE -> 3 * shot
        }
    }

    /** 轮播到第几张（[count] 张，少于 2 张时总是 0）。 */
    fun slideIndex(effect: BackgroundMotionEffect, elapsedMs: Long, shotMs: Long, count: Int): Int =
        if (count < 2) 0 else ((elapsedMs.coerceAtLeast(0L) / cycleMs(effect, shotMs)) % count).toInt()

    fun at(effect: BackgroundMotionEffect, elapsedMs: Long, shotMs: Long): BackgroundMotionTransform {
        val shot = shotMs.coerceAtLeast(1L)
        val elapsed = elapsedMs.coerceAtLeast(0L)
        return when (effect) {
            BackgroundMotionEffect.ZOOM_IN -> zoom(elapsed, shot, from = 1f, to = MAX_SCALE)
            BackgroundMotionEffect.ZOOM_OUT -> zoom(elapsed, shot, from = MAX_SCALE, to = 1f)
            BackgroundMotionEffect.PAN -> {
                val t = elapsed % (2 * shot)
                val x = if (t < shot) lerp(-MAX_SHIFT, MAX_SHIFT, ease(t, shot))
                    else lerp(MAX_SHIFT, -MAX_SHIFT, ease(t - shot, shot))
                BackgroundMotionTransform(MAX_SCALE, x, 0f)
            }
            BackgroundMotionEffect.CYCLE -> {
                val t = elapsed % (3 * shot)
                when {
                    t < shot -> ease(t, shot).let { p -> BackgroundMotionTransform(lerp(1f, MAX_SCALE, p), -MAX_SHIFT * p, 0f) }
                    t < 2 * shot -> BackgroundMotionTransform(MAX_SCALE, lerp(-MAX_SHIFT, MAX_SHIFT, ease(t - shot, shot)), 0f)
                    else -> ease(t - 2 * shot, shot).let { p -> BackgroundMotionTransform(lerp(MAX_SCALE, 1f, p), MAX_SHIFT * (1f - p), 0f) }
                }
            }
        }
    }

    /**
     * 现在走到 [elapsedMs]，下一帧隔多久出（[FRAME_INTERVAL_MS] 的整数倍）。[widthPx]、[heightPx] 是背景框的像素大小，
     * 宁大勿小。渐变回起点那一段每帧透明度都在变，照旧每 [FRAME_INTERVAL_MS] 一帧。
     */
    fun nextFrameIntervalMs(effect: BackgroundMotionEffect, elapsedMs: Long, shotMs: Long, widthPx: Float, heightPx: Float): Long {
        val now = at(effect, elapsedMs, shotMs)
        if (now.fadeTo != null) return FRAME_INTERVAL_MS
        var interval = FRAME_INTERVAL_MS
        var next = 2 * FRAME_INTERVAL_MS
        while (next <= MAX_FRAME_INTERVAL_MS) {
            val then = at(effect, elapsedMs + next, shotMs)
            if (then.fadeTo != null || maxCornerShiftPx(now, then, widthPx, heightPx) > MAX_STEP_PX) break
            interval = next
            next += FRAME_INTERVAL_MS
        }
        return interval
    }

    /** 两个样子之间，背景框四个角里挪得最远的那个挪了几个像素（以框中心缩放、再平移）。 */
    fun maxCornerShiftPx(a: BackgroundMotionTransform, b: BackgroundMotionTransform, widthPx: Float, heightPx: Float): Float {
        val ds = kotlin.math.abs(b.scale - a.scale)
        return maxOf(ds * widthPx / 2f + kotlin.math.abs(b.shiftX - a.shiftX) * widthPx,
            ds * heightPx / 2f + kotlin.math.abs(b.shiftY - a.shiftY) * heightPx)
    }

    private fun zoom(elapsed: Long, shot: Long, from: Float, to: Float): BackgroundMotionTransform {
        val t = elapsed % (shot + RETURN_FADE_MS)
        if (t < shot) return BackgroundMotionTransform(lerp(from, to, ease(t, shot)), 0f, 0f)
        // 走到头：停在终点，起点的样子在上面淡入；这一圈结束时正好盖满，下一圈从起点接着走，看不出接缝。
        return BackgroundMotionTransform(to, 0f, 0f, fadeTo = BackgroundMotionTransform(from, 0f, 0f),
            fadeAlpha = ease(t - shot, RETURN_FADE_MS))
    }

    private fun ease(t: Long, duration: Long): Float {
        val p = (t.toFloat() / duration).coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
}

/**
 * 动态背景什么时候动。开关关着、不是图片背景、当前应用是游戏、系统省电、系统关了动画：不动，按原样静止显示。
 * 键盘收起、面板或展开候选盖住键盘时只是暂停（[KeyboardBackgroundMotion.covered]），位置留着。
 */
internal object BackgroundMotionGate {
    fun systemAllows(powerSave: Boolean, animationsOn: Boolean): Boolean = !powerSave && animationsOn

    /** [surface]：键盘本体在显示（不是实体键盘浮条、语音悬浮条）。 */
    fun allowed(enabled: Boolean, imageBackground: Boolean, game: Boolean, systemAllows: Boolean, surface: Boolean): Boolean =
        enabled && imageBackground && !game && systemAllows && surface
}

/**
 * 键盘里共用的一只钟：所有画背景图的地方读同一个 [transform]，导航条、两侧留空和键盘本体一起动。
 * [slides] 是「我的图片」轮播的全部图片（主图在前），少于 2 张不轮播。
 */
@Stable
internal class KeyboardBackgroundMotion(
    val effect: BackgroundMotionEffect,
    private val shotMs: Long,
    val slides: List<String> = emptyList(),
    /** 轮播图片各自的摆放（ThemeImagePlacement.encode()）；没有的铺满居中。 */
    private val placements: Map<String, String> = emptyMap(),
) {
    private val elapsed = mutableLongStateOf(0L)

    private val slide = mutableIntStateOf(0)

    /** 现在轮到的那张图（themes/…）；不轮播时 null。每圈才变一次，在组合里读也只在换图时重组。 */
    val slideSource: String? get() = if (slides.size < 2) null else slides[slide.intValue.coerceIn(0, slides.size - 1)]

    /** 这张轮播图调过的摆放；没调过 null。 */
    internal fun placementOf(src: String): ThemeImagePlacement? = placements[src]?.let(ThemeImagePlacement::decode)

    /** 面板、展开候选盖住了键盘：钟停下，不再出新帧。由 KeyboardView 设。 */
    var covered by mutableStateOf(false)

    /** 只在画的阶段读（graphicsLayer 块或 draw 块），不引起重组。 */
    val transform: BackgroundMotionTransform get() = BackgroundMotionPath.at(effect, elapsed.longValue, shotMs)

    /** 下一帧隔多久出，见 [BackgroundMotionPath.nextFrameIntervalMs]。 */
    internal fun nextFrameIntervalMs(widthPx: Float, heightPx: Float): Long =
        BackgroundMotionPath.nextFrameIntervalMs(effect, elapsed.longValue, shotMs, widthPx, heightPx)

    /** [maxStepMs]：一步最多走多久，主线程卡了一下之后不一步跳过去；帧隔得长时由钟按帧距放宽。 */
    internal fun advance(ms: Long, maxStepMs: Long = MAX_STEP_MS) {
        elapsed.longValue += ms.coerceIn(0L, maxStepMs)
        // 只在换图时写，读 slideSource 的地方每圈重组一次。
        val index = BackgroundMotionPath.slideIndex(effect, elapsed.longValue, shotMs, slides.size)
        if (index != slide.intValue) slide.intValue = index
    }

    internal companion object {
        /** 主线程卡了一下之后不一步跳过去：每 50 ms 一帧时最多走 100 ms。 */
        const val MAX_STEP_MS = 100L
        /** 比预定帧距多出这么多以内照实走（等垂直同步、主线程稍忙）。 */
        const val STALL_ALLOWANCE_MS = MAX_STEP_MS - BackgroundMotionPath.FRAME_INTERVAL_MS
    }
}

internal val LocalKeyboardBackgroundMotion = staticCompositionLocalOf<KeyboardBackgroundMotion?> { null }

/**
 * 键盘本体背景图交给单独一层画（[BackgroundImageSlot]），每帧只改这一层的缩放和位移，按键和候选不重画。
 * [draw] 在本层坐标里按原样（不带动作）画图；[frame] 是图所在的框（缩放以它中心为准），null 为整层。
 */
@Stable
class BackgroundImageSlot {
    var draw: (DrawScope.() -> Unit)? by mutableStateOf(null)
        private set
    var frame: (() -> Rect?) by mutableStateOf({ null })
        private set
    /** 换图（轮播、换主题、重新渲染）时旧图留着淡出，[fade] 从 0 升到 1 时新图盖上来；见 KeyboardBackgroundMotionLayer。 */
    var previousDraw: (DrawScope.() -> Unit)? by mutableStateOf(null)
    val fade = mutableFloatStateOf(1f)
    var fadeToken by mutableIntStateOf(0)
        private set
    private var key: Any? = null

    /** [key] 是这张图本身（位图对象）：同一张图只换画法时不淡入淡出。 */
    fun update(key: Any?, frame: () -> Rect?, draw: (DrawScope.() -> Unit)?) {
        if (key != null && this.key != null && key !== this.key && this.draw != null) {
            previousDraw = this.draw
            fade.floatValue = 0f
            fadeToken++
        }
        this.key = key
        this.frame = frame
        this.draw = draw
    }
}

/**
 * 轮播：「我的图片」换成现在轮到的那张（第一张是主图，按调整页的摆放；其余按各自调过的摆放，没调过铺满居中）。
 * 别的背景、没开轮播时原样返回。只在键盘里起作用（设置页预览没有钟）。
 */
@Composable
internal fun BackgroundConfig?.withCurrentSlide(): BackgroundConfig? =
    withCurrentSlide(LocalKeyboardBackgroundMotion.current)

@Composable
internal fun BackgroundConfig?.withCurrentSlide(motion: KeyboardBackgroundMotion?): BackgroundConfig? {
    val slide = motion?.slideSource ?: return this
    val placement = motion.placementOf(slide)
    return remember(this, slide, placement) { withSlide(slide, placement) }
}

/** 轮播到的图按它自己调过的摆放画（11-15 起轮播图也能调）；没调过的铺满居中。 */
internal fun BackgroundConfig?.withSlide(slide: String?, placement: ThemeImagePlacement? = null): BackgroundConfig? {
    if (slide == null || this == null || !CustomImageTheme.isCustomImage(this) || slide == src) return this
    val p = placement ?: ThemeImagePlacement.DEFAULT
    return if (p == ThemeImagePlacement.DEFAULT) copy(src = slide, srcDark = null, fit = "cover", zoom = null, alignX = null, alignY = null)
    else copy(src = slide, srcDark = null, fit = p.fit, zoom = p.zoom, alignX = p.alignX, alignY = p.alignY)
}

/** 按 [transform] 画 [frame] 里的背景图：不在独立图层里的背景（导航条、两侧留空、玻璃共用背景）用这个。 */
internal inline fun DrawScope.withBackgroundMotion(transform: BackgroundMotionTransform?, frame: Rect, block: DrawScope.() -> Unit) {
    if (transform == null || transform.isIdentity) { block(); return }
    withMotionPose(transform, frame, block)
    // 渐变回起点：起点的样子单独一层按 fadeAlpha 盖上去。
    val fadeTo = transform.fadeTo ?: return
    if (transform.fadeAlpha <= 0f) return
    val paint = androidx.compose.ui.graphics.Paint().apply { alpha = transform.fadeAlpha.coerceIn(0f, 1f) }
    drawContext.canvas.saveLayer(Rect(androidx.compose.ui.geometry.Offset.Zero, size), paint)
    try { withMotionPose(fadeTo, frame, block) } finally { drawContext.canvas.restore() }
}

internal inline fun DrawScope.withMotionPose(pose: BackgroundMotionTransform, frame: Rect, block: DrawScope.() -> Unit) {
    if (pose.scale == 1f && pose.shiftX == 0f && pose.shiftY == 0f) { block(); return }
    withTransform({
        translate(pose.shiftX * frame.width, pose.shiftY * frame.height)
        scale(pose.scale, pose.scale, pivot = frame.center)
    }, block)
}

@Composable
internal fun rememberBackgroundMotionSettings(): BackgroundMotionSettings {
    val context = LocalContext.current
    var settings by remember(context) { mutableStateOf(BackgroundMotionPreferences.read(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in BackgroundMotionPreferences.KEYS) settings = BackgroundMotionPreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

/** 系统省电、系统关了动画（开发者选项「动画程序时长」为 0、无障碍「移除动画」）时不动。只在开关开着时才监听。 */
@Composable
internal fun rememberSystemAllowsBackgroundMotion(listen: Boolean): Boolean {
    if (!listen) return false
    val context = LocalContext.current.applicationContext
    var allowed by remember(context) { mutableStateOf(systemAllowsMotion(context)) }
    DisposableEffect(context) {
        val update = { allowed = systemAllowsMotion(context) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { update() }
        }
        val receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { update() }
        }
        val observerRegistered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }.isSuccess
        update()
        onDispose {
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            if (observerRegistered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return allowed
}

private fun systemAllowsMotion(context: Context): Boolean {
    val powerSave = runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
    val animations = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    return BackgroundMotionGate.systemAllows(powerSave, animations)
}

/**
 * 动态背景的钟。[allowed] 为假（开关关、不是图片背景、游戏、省电、关了动画）时返回 null，背景按原样画；
 * [running] 为假（键盘没显示）时钟停住。走钟只写一个数，读它的是各层的画图块：键盘本体只改图层属性，不重组、不重画按键。
 */
@Composable
internal fun rememberKeyboardBackgroundMotion(
    settings: BackgroundMotionSettings,
    allowed: Boolean,
    running: Boolean,
    /** 轮播的全部图片（主图在前）；不轮播时空。 */
    slides: List<String> = emptyList(),
): KeyboardBackgroundMotion? {
    if (!allowed) return null
    // 背景框的像素大小宁大勿小：取屏幕宽（键盘不会比屏幕宽），高也按宽算，算出的帧距只会偏短。
    val framePx = LocalContext.current.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    val motion = remember(settings.effect, settings.speed, slides, settings.slidePlacements) {
        KeyboardBackgroundMotion(settings.effect, settings.speed.shotMs, slides, settings.slidePlacements)
    }
    LaunchedEffect(motion, running) {
        if (!running) return@LaunchedEffect
        while (true) {
            snapshotFlow { motion.covered }.first { !it }
            var last = withFrameMillis { it }
            var interval = motion.nextFrameIntervalMs(framePx, framePx)
            while (!motion.covered) {
                // 先等到下一帧该出的时候再要一帧：两帧之间主线程和渲染线程都不为背景干活。
                delay(interval - VSYNC_SLACK_MS)
                val now = withFrameMillis { it }
                motion.advance(now - last, maxStepMs = interval + KeyboardBackgroundMotion.STALL_ALLOWANCE_MS)
                last = now
                interval = motion.nextFrameIntervalMs(framePx, framePx)
            }
        }
    }
    return motion
}

/** 等垂直同步最多约一帧（60Hz 16ms），提前这么多开始等，平均下来接近每 50ms 一帧。 */
private const val VSYNC_SLACK_MS = 10L
