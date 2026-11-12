package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.IntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset

/**
 * 菜单、表情、剪贴板等面板只盖住按键：从候选栏（打开面板时是工具栏）的下沿开始。
 * 候选栏上面还有翻译栏、工具面板、剪贴板搜索或拼音编辑条时，候选栏被往下推了 [aboveBarPx]，面板跟着往下
 * （11-10：开着翻译栏打开菜单，以前面板只从顶上量一个候选栏高，菜单格子画到了工具栏上，工具栏也点不到）。
 * 上面什么都没有时（[aboveBarPx] 为 0）和原来的 padding(top = 候选栏高) 完全一样。
 * 在布局阶段读，翻译栏开关时不用重组整块键盘。
 */
internal fun Modifier.belowCandidateBar(aboveBarPx: IntState, barHeight: Dp): Modifier = layout { measurable, constraints ->
    val top = (aboveBarPx.intValue.coerceAtLeast(0) + barHeight.roundToPx())
        .coerceAtMost(if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE)
    val placeable = measurable.measure(constraints.offset(vertical = -top))
    layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height + top)) {
        placeable.place(0, top)
    }
}
