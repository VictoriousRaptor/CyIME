package com.kingzcheung.xime.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * CyIME 统一动效规范。新动画只从这里取时长和曲线，不再在各处写数字。
 *
 * - 按下的反馈立即出现（[INSTANT]），只有松手和出现/切换才有过渡。
 * - 只改 graphicsLayer 的透明度/缩放：每帧只重画，不重组、不重新布局，打字路径不加成本。
 * - 全部基于 Compose 动画（tween、spring）：系统“移除动画”或调了“动画时长缩放”时，Compose 按系统
 *   缩放走，缩放为 0 时直接到位；View 一侧用系统动画器或 Compose 动画，同样跟系统缩放。
 * - 动画只管画面：触摸、上屏照常立即处理，不等动画；动画中途来了新的手势就直接换成新的，不排队。
 */
object CyMotion {
    const val INSTANT = 0
    /** 松手恢复、气泡/弹出菜单出现、键盘页切换淡入。 */
    const val SHORT = 120
    /** 面板内切页、对话框。 */
    const val MEDIUM = 200
    /** 整块面板展开（沿用原 240 ms）。 */
    const val LONG = 240
    /** 整块面板收起，比展开快约 25%（沿用原 180 ms）。 */
    const val LONG_EXIT = 180
    /** 松手后的淡出（按键气泡、长按选项、滑动符号气泡）：手已经离开，比出现还快。 */
    const val EXIT_FAST = 90
    /** 引导页选项选中：底色、边框、打勾慢慢变过去，让人看清选了哪个（用户 10-30「过渡动画要慢」）。 */
    const val GUIDE = 360

    /** 位移和尺寸。 */
    val Standard = FastOutSlowInEasing
    /** 出现、淡入：减速进入。 */
    val Enter = LinearOutSlowInEasing
    /** 消失、淡出：加速离开。 */
    val Exit = FastOutLinearInEasing

    /**
     * 跟手移动（长按选项里高亮在各项之间滑动）：带一点回弹（约 5%），约 140 ms 基本到位。
     * 弹簧没有固定时长，手指来回划时从当前位置和速度接着走，不会一顿一顿。
     */
    const val MOVE_DAMPING = 0.7f
    const val MOVE_STIFFNESS = 1000f
    /** 弹出层从按键里长出来（按键气泡展开成长按选项）：回弹更小（约 2%），不晃。 */
    const val POP_DAMPING = 0.8f
    const val POP_STIFFNESS = 800f

    fun <T> move(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(MOVE_DAMPING, MOVE_STIFFNESS, visibilityThreshold)
    fun <T> pop(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(POP_DAMPING, POP_STIFFNESS, visibilityThreshold)

    /**
     * 卡片行打开时滑进来（布局选择）：不回弹的弹簧，约 300 ms 到位，比面板揭幕（240 ms）稍长，
     * 下面方案那一行揭开时还看得到在滑。用户 11-11 说九键原来那一下列表滚动好看，就照它。
     */
    const val ROW_SLIDE_STIFFNESS = 400f
    fun <T> rowSlideIn(): SpringSpec<T> = spring(1f, ROW_SLIDE_STIFFNESS)

    fun <T> enter(duration: Int = SHORT): TweenSpec<T> = tween(duration, easing = Enter)
    fun <T> exit(duration: Int = SHORT): TweenSpec<T> = tween(duration, easing = Exit)
    fun <T> panel(expanding: Boolean): TweenSpec<T> =
        tween(if (expanding) LONG else LONG_EXIT, easing = Standard)

    /** 弹出层出现时的起始透明度和缩放。 */
    const val POPUP_FROM_ALPHA = 0f
    const val POPUP_FROM_SCALE = 0.92f
    /** 键盘页切换淡入的起始透明度：按键始终看得见，深色主题下也不会像黑了一下。 */
    const val SWAP_FROM_ALPHA = 0.7f
}

/**
 * 弹出层（长按气泡、语言菜单等）出现时淡入并从 [origin] 轻微放大。
 * 只在首次组合时播放一次；内容从第一帧起就能接收触摸，不吞点击。
 */
@Composable
fun Modifier.cyPopupEnter(
    origin: TransformOrigin = TransformOrigin.Center,
    scale: Boolean = true,
): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, CyMotion.enter()) }
    return graphicsLayer {
        val p = progress.value
        alpha = CyMotion.POPUP_FROM_ALPHA + (1f - CyMotion.POPUP_FROM_ALPHA) * p
        if (scale) {
            val s = CyMotion.POPUP_FROM_SCALE + (1f - CyMotion.POPUP_FROM_SCALE) * p
            scaleX = s
            scaleY = s
            transformOrigin = origin
        }
    }
}

/**
 * [key] 变化时（换布局、进数字页等）新内容快速淡入。新内容立即可点，不做淡出、
 * 不叠两层内容，因此不会像揭幕遮罩那样在动画期间吞掉连点。首次出现不播放，
 * 除非 [fadeOnFirst]（如符号页这类每次打开都新建的页面，打开本身就是一次换页）。
 */
@Composable
fun Modifier.cySwapFadeIn(key: Any?, instantKey: Any? = null, fadeOnFirst: Boolean = false): Modifier {
    // 新 key 的第一帧就用起始透明度：若等 LaunchedEffect 再降透明度，会先整帧闪现一次。
    // [instantKey] 同时变了（如中英切换）就直接换，不淡入：同一块键盘只是换了字。
    val first = remember { booleanArrayOf(true) }
    val lastInstant = remember { arrayOf(instantKey) }
    val alpha = remember(key) {
        val instant = (first[0] && !fadeOnFirst) || (!first[0] && lastInstant[0] != instantKey)
        first[0] = false
        lastInstant[0] = instantKey
        Animatable(if (instant) 1f else CyMotion.SWAP_FROM_ALPHA)
    }
    lastInstant[0] = instantKey
    LaunchedEffect(alpha) { alpha.animateTo(1f, CyMotion.enter()) }
    return graphicsLayer { this.alpha = alpha.value }
}
