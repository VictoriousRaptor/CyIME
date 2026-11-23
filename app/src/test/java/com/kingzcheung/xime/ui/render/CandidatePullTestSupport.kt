package com.kingzcheung.xime.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe

/**
 * 候选栏没有展开按钮了（用户 11-20）：在候选上横着拉过门槛松手才展开。
 * [leftward] 为 true 是手指往左拉，false 往右。候选栏上往左拉展开、展开着往右拉收回（11-21），默认往左。
 */
internal fun SemanticsNodeInteraction.pullAcross(leftward: Boolean = true, fraction: Float = 0.8f): SemanticsNodeInteraction =
    performTouchInput {
        val margin = (1f - fraction) / 2f
        val a = Offset(width * (1f - margin), centerY)
        val b = Offset(width * margin, centerY)
        swipe(if (leftward) a else b, if (leftward) b else a, durationMillis = 300)
    }

/** 在候选栏上往左拉开展开页。 */
internal fun SemanticsNodeInteractionsProvider.pullOpenCandidates(useUnmergedTree: Boolean = false) {
    onNodeWithTag("candidate-pull-expand", useUnmergedTree).pullAcross()
}

/** 在展开页的候选区横着划，收回展开页。 */
internal fun SemanticsNodeInteractionsProvider.swipeCloseCandidates() {
    onNodeWithTag("expanded-candidates", useUnmergedTree = true).pullAcross(leftward = false)
}

/** 直接调读屏的「展开候选」「收回候选」，不走手势、不推时钟（量动画中途的状态时用）。在主线程上调。 */
internal fun SemanticsNodeInteractionsProvider.runCustomAction(label: String) {
    val matcher = SemanticsMatcher("custom action $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }
    // 展开着时候选栏和展开页都带「收回候选」，两个都收回，取第一个。
    val node = onAllNodes(matcher, useUnmergedTree = true).onFirst().fetchSemanticsNode()
    node.config[SemanticsActions.CustomActions].first { it.label == label }.action()
}
