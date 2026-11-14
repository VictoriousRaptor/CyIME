package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import com.kingzcheung.xime.ui.theme.CyMotion
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.*

internal fun <T> keyboardPanelMotion(expanding: Boolean): TweenSpec<T> = CyMotion.panel(expanding)

internal data class HandwritingExpansion(val fraction: Float, val active: Boolean)

/**
 * Keep the full host and its normal app inset until the closing animation has actually finished.
 * [instant]：按记忆恢复全屏时直接全屏不播展开动画（11-05）；只有点「全屏手写」才动画展开，收起照旧动画。
 */
@Composable
internal fun rememberHandwritingExpansion(enabled: Boolean, expanded: Boolean, session: Long,
    instant: Boolean = false): HandwritingExpansion {
    val visibility = remember(enabled, session) { MutableTransitionState(false) }
    visibility.targetState = enabled && expanded
    val snapOpen = instant && visibility.targetState
    val transition = updateTransition(visibility, label = "handwriting-fullscreen")
    val fraction by transition.animateFloat(transitionSpec = {
        if (instant && targetState) snap<Float>() else keyboardPanelMotion(targetState)
    }, label = "handwriting-size") {
        if (it) 1f else 0f
    }
    return HandwritingExpansion(if (!enabled) 0f else if (snapOpen) 1f else fraction,
        enabled && (visibility.currentState || visibility.targetState || !visibility.isIdle))
}
