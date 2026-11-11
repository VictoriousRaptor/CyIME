package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlin.math.abs

internal enum class FloatingDockEdge { BOTTOM, LEFT, RIGHT, TOP }

/** 只在导航栏上方的底部贴边区恢复；横向和顶部位置不参与恢复判定。 */
@Suppress("UNUSED_PARAMETER")
internal fun floatingDockEdge(
    offsetX: Float,
    offsetY: Float,
    horizontalTravel: Float,
    maxOffsetY: Float,
    bottomInset: Float = 0f,
    tolerance: Float = 12f,
): FloatingDockEdge? {
    return if ((offsetY - bottomInset).coerceAtLeast(0f) <= tolerance) FloatingDockEdge.BOTTOM else null
}

/**
 * 卡片顶在底边、手指再往下顶过 [FLOATING_DOCK_ARM_PUSH_DP] 后，要停这么久才变回固定，渐变框也用这么久从卡片变成固定键盘。
 * 0.75 秒（用户 10-23：0.5 秒会误贴底，1 秒太慢）。11-03 改成 0.3 秒、还把拖到底之前的行程算进去，
 * 一路往下拖到底就直接满了，松手瞬间变固定、看不到渐变，也容易误贴底（用户 11-10）。
 */
internal const val FLOATING_DOCK_DURATION_MILLIS = 750L
/** 卡片已经到底边后，手指还得再往下顶这么多才开始准备贴底；只是把卡片挪到底边附近不会贴底。 */
internal const val FLOATING_DOCK_ARM_PUSH_DP = 8f

/**
 * Releasing docks exactly when the preview shows the docked shape: the outline has reached it and the
 * card is still on the bottom edge. The preview, not a separate timer, decides.
 */
internal fun floatingDockOnRelease(previewProgress: Float, onBottomEdge: Boolean, hasDestination: Boolean): Boolean =
    hasDestination && onBottomEdge && previewProgress >= 1f
internal const val FLOATING_DRAG_BAR_HEIGHT_DP = 28
internal fun floatingFooterHeightDp(shortSideDp: Int): Int =
    if (shortSideDp in 1 until 600) 16 else FLOATING_DRAG_BAR_HEIGHT_DP

internal val android.content.res.Configuration.floatingFooterHeightDp: Int
    get() = floatingFooterHeightDp(minOf(screenWidthDp, screenHeightDp))

/** 调节界面里固定键盘上拖变悬浮：先有 [FIXED_TO_FLOATING_DEAD_ZONE_DP] 不动的阻力，再拉 [FIXED_TO_FLOATING_PULL_DP] 才满。 */
internal const val FIXED_TO_FLOATING_DEAD_ZONE_DP = 48f
internal const val FIXED_TO_FLOATING_PULL_DP = 96f

internal fun modeSwitchPullProgress(verticalDp: Float, horizontalDp: Float = 0f): Float =
    ((verticalDp - abs(horizontalDp) - FIXED_TO_FLOATING_DEAD_ZONE_DP) / FIXED_TO_FLOATING_PULL_DP).coerceIn(0f, 1f)

/** 移动条覆盖键帽以下的完整空白，卡片本身仍只额外预留原来的 28dp。 */
internal fun floatingDragAreaHeightDp(contentPaddingDp: Int, keyClearanceDp: Float, totalHeightDp: Float,
    footerDp: Int = FLOATING_DRAG_BAR_HEIGHT_DP): Float =
    (footerDp + contentPaddingDp.coerceAtLeast(0) + keyClearanceDp.coerceAtLeast(0f))
        .coerceAtMost(totalHeightDp.coerceAtLeast(0f))

/**
 * 贴底变回固定只看卡片到底边以后的那一段：拖到底之前走了多远都不算（用户 11-10：一路拖到底就瞬间变固定）。
 * [move] 的纵向量是「手指往下、卡片却已经动不了」的那部分，卡片跟着动的不算；卡片离开底边贴边区就作废，再回来从头算。
 */
internal class FloatingDockGesture {
    private var x = 0f
    private var y = 0f
    private var pull by mutableFloatStateOf(0f)
    private var hold by mutableFloatStateOf(0f)
    var armed by mutableStateOf(false)
        private set
    var epoch by mutableIntStateOf(0)
        private set
    val progress get() = if (armed) maxOf(pull, hold) else 0f

    /** [dx] 手指横向位移，[pushDy] 卡片到底后手指继续往下顶出去的距离（dp），[onBottom] 卡片在底边贴边区。 */
    fun move(dx: Float, pushDy: Float, onBottom: Boolean) {
        if (!onBottom) {
            // 离开底边：这次贴底作废，再次靠近要从新的行程开始。
            x = 0f
            y = 0f
            pull = 0f
            if (armed) {
                armed = false
                hold = 0f
                epoch++
            }
            return
        }
        x += dx
        y += pushDy.coerceAtLeast(0f)
        pull = modeSwitchPullProgress(y, x)
        // 准备好以后手指在底边轻微晃动、往回一点点都不作废，横着滑出去比往下顶的还多才作废。
        val reach = y - abs(x)
        val next = if (armed) reach >= 0f else reach >= FLOATING_DOCK_ARM_PUSH_DP
        if (armed != next) {
            if (!next) {
                x = 0f
                y = 0f
                pull = 0f
            }
            armed = next
            hold = 0f
            epoch++
        }
    }

    fun advanceHold(elapsedMillis: Long, startedEpoch: Int) {
        if (armed && epoch == startedEpoch)
            hold = (elapsedMillis.toFloat() / FLOATING_DOCK_DURATION_MILLIS).coerceIn(0f, 1f)
    }

    fun cancel() {
        x = 0f
        y = 0f
        pull = 0f
        hold = 0f
        armed = false
        epoch++
    }
}

/** 用帧时间计时，系统动画倍率不会把手势的等待时间放大。 */
@Composable
internal fun TrackDockHold(gesture: FloatingDockGesture) {
    val epoch = gesture.epoch
    LaunchedEffect(gesture, epoch) {
        if (!gesture.armed) return@LaunchedEffect
        val start = withFrameMillis { it }
        do {
            val elapsed = withFrameMillis { it } - start
            gesture.advanceHold(elapsed, epoch)
        } while (elapsed < FLOATING_DOCK_DURATION_MILLIS && gesture.armed && gesture.epoch == epoch)
    }
}
