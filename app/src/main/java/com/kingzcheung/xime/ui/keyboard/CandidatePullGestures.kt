package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/*
 * 候选栏一边只有一个按钮（用户 11-20）：左边「<」取消，右边「⌄」收起键盘，展开候选不占按钮。
 * 候选栏这一行不滚动，放得下几个显示几个（用户 11-20：「显示多少就多少长度」）。
 * 展开：在候选栏上往右划，整行跟手但越拉越紧，拉过门槛松手就展开，不够就弹回（像安卓下拉通知栏）。
 * 收回：展开着在候选栏上往左划，同样拉过门槛松手收回（用户 11-21：「右滑展开，左滑收起来」）；
 * 在展开页的候选上左右划、返回键、选词照旧也收回。没展开时往左划、展开着往右划，栏不动、也不算点候选。
 *
 * 不用系统的边缘拉伸回弹：11-05 用户录屏，有的手机回弹收不完，列表把每次按下都当成接着滑，点候选选不上。
 * 这里的回弹是自己画的位移，松手后 [RELEASE_MS] 内一定回到 0；回弹途中按下去就停住，点到的就是看到的那个候选，抬手再接着弹回。
 * 点候选、长按候选（删词、整句选段）不受影响：手指横着走过触摸阈值才算拉，之前照旧是点和长按。
 */

/** 松手后拉出的部分弹回原位的时长。 */
internal const val RELEASE_MS = 150

/** 手指走的距离只有这么多会变成位移（越拉越紧）。 */
internal const val PULL_RESISTANCE = 0.5f

/** 被拉出这么多（dp）松手就展开 / 收回。 */
internal const val PULL_THRESHOLD_DP = 48f

/** 最多被拉出门槛的这么多倍。 */
private const val PULL_MAX_FACTOR = 1.5f

/** 手指横着走了 [raw] 像素（带方向），整行被拉出多少像素（同方向）。 */
internal fun pulledDistance(raw: Float, threshold: Float): Float =
    sign(raw) * (abs(raw) * PULL_RESISTANCE).coerceAtMost(threshold * PULL_MAX_FACTOR)

/** 松手时拉出 [shown] 像素（哪个方向都算），要不要展开 / 收回。 */
internal fun pullReleases(shown: Float, threshold: Float): Boolean = threshold > 0f && abs(shown) >= threshold

/** 一次横拉：手指走的距离、拉出的位移、是否已过门槛、正在弹回的动画。 */
private class RubberBand(private val scope: CoroutineScope, private val haptic: HapticFeedback, val threshold: Float) {
    var shown by mutableFloatStateOf(0f)
    /** 只认的方向：1 往右，-1 往左，0 两边都算。拉过头折回来最多回到 0，不会拉到另一边去。 */
    var direction = 0f
    /** 这一下是能左右滑的候选行滑到头以后多出来的（嵌套滚动喂进来），松手由 onPreFling 结算，不由手势那边弹回。 */
    var nested = false
    private var raw = 0f
    private var armed = false
    private var job: Job? = null

    /** 新的一下按下去：没收完的回弹停在原地（接着拉就从这里拉），点到的就是看到的那个候选。 */
    fun hold() {
        job?.cancel()
        raw = shown / PULL_RESISTANCE
        armed = pullReleases(shown, threshold)
    }

    fun move(dx: Float) {
        raw += dx
        if (direction > 0f) raw = raw.coerceAtLeast(0f) else if (direction < 0f) raw = raw.coerceAtMost(0f)
        shown = pulledDistance(raw, threshold)
        val now = pullReleases(shown, threshold)
        if (now && !armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        armed = now
    }

    /** 松手（[lifted] 为 false 是手势被取消）：弹回原位，返回要不要展开 / 收回。 */
    fun release(lifted: Boolean): Boolean {
        val trigger = lifted && pullReleases(shown, threshold)
        raw = 0f
        armed = false
        job?.cancel()
        val from = shown
        if (from != 0f) job = scope.launch {
            animate(from, 0f, animationSpec = tween(RELEASE_MS)) { value, _ -> shown = value }
            shown = 0f
        }
        return trigger
    }
}

@Composable
private fun rememberRubberBand(): RubberBand {
    val threshold = with(LocalDensity.current) { PULL_THRESHOLD_DP.dp.toPx() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    return remember(threshold, scope, haptic) { RubberBand(scope, haptic, threshold) }
}

/**
 * 横着拉过门槛松手调 [onTrigger]；[enabled] 为 false 时手指照常交给里面的候选。
 * [direction] 1 只认往右、-1 只认往左、0 两边都算；往不认的那边划，栏不动、不触发，这一下也不当成点候选。
 */
private fun Modifier.rubberBandDrag(band: RubberBand, enabled: () -> Boolean, direction: () -> Float = { 0f },
    onTrigger: () -> Unit): Modifier =
    pointerInput(band) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            band.hold()
            if (enabled()) {
                val dir = direction()
                band.direction = dir
                var wrongWay = false
                // 里面的候选先看到这一下（长按整句选段会接走），横着走过触摸阈值、没被接走才算拉。
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    change.consume()
                    if (dir != 0f && sign(over) != dir) wrongWay = true else band.move(over)
                }
                if (start != null) {
                    // 拉起来以后这一下归拉动：在 Initial 阶段先于里面的候选接走移动和抬起。候选用的 tolerantClick 不看移动
                    // 有没有被接走，只认没被接走的抬起；不接走抬起，拉一小段（没过门槛）松手时会把手指下的候选当成点了
                    // （11-20 电脑上 AssociationHideButtonTest：英文补全展开页短划一下，选上了一个补全）。
                    var lifted = false
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == start.id } ?: break
                        if (!change.pressed) {
                            lifted = true
                            change.consume()
                            break
                        }
                        if (!wrongWay) band.move(change.positionChange().x)
                        change.consume()
                    }
                    if (band.release(lifted) && !wrongWay) onTrigger()
                    return@awaitEachGesture
                }
            }
            // 不是拉（点候选、长按）：手指抬起来以后再接着弹回，按着时不动，点到的就是看到的那个。
            while (currentEvent.changes.any { it.pressed }) awaitPointerEvent(PointerEventPass.Final)
            // 能滑的候选行滑到头再拉（嵌套滚动）：松手时由列表的 onPreFling 判断展开没有，这里不抢着弹回。
            if (!band.nested) band.release(false)
        }
    }

/**
 * 包住候选栏里的候选行：往左拉过门槛松手调 [onExpand]（和合并前一样，往左拉就展开）；给了 [onCollapse]（展开着）时改成往右拉过门槛松手调它（11-21 他：合并后方向反了）。
 * [enabled] 为 false（没有更多候选、滑行预览）且没有 [onCollapse] 时不拉，行为和以前一样。
 */
@Composable
internal fun PullToExpandCandidates(
    enabled: Boolean,
    onExpand: () -> Unit,
    hintColor: Color,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    /** 左边「<」和第一个候选之间的空白（4dp）：画在拉动区里，按在这里也拉得动；点一下照旧算点最近的候选（#274）。 */
    leadingGap: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val band = rememberRubberBand()
    val collapsing = onCollapse != null
    val currentEnabled by rememberUpdatedState(enabled || collapsing)
    val currentCollapsing by rememberUpdatedState(collapsing)
    val currentExpand by rememberUpdatedState(onExpand)
    val currentCollapse by rememberUpdatedState(onCollapse)
    // 1.15.101 的候选行能左右滑（11-21 恢复）：滑到头还往左拉，多出来的距离拉动整行，过门槛松手展开；
    // 展开着滑到最前还往右拉就收回。没滑到头时照旧只是滑候选，▾ 按钮照旧。
    val overscrollPull = remember(band) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!band.nested || source != NestedScrollSource.UserInput) return Offset.Zero
                val dx = available.x
                // 拉出来以后往回走：先收回拉出的这段，列表不动。
                if (band.shown < 0f && dx > 0f || band.shown > 0f && dx < 0f) {
                    band.move(dx)
                    return Offset(dx, 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !currentEnabled) return Offset.Zero
                val dir = if (currentCollapsing) 1f else -1f
                val dx = available.x
                if (dx == 0f || !band.nested && sign(dx) != dir) return Offset.Zero
                band.direction = dir
                band.nested = true
                band.move(dx)
                return Offset(dx, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!band.nested) return Velocity.Zero
                band.nested = false
                if (band.release(true)) { if (currentCollapsing) currentCollapse?.invoke() else currentExpand() }
                return available
            }
        }
    }
    Box(
        modifier
            .clipToBounds()
            .testTag("candidate-pull-expand")
            .nestedScroll(overscrollPull)
            .semantics {
                if (collapsing) customActions = listOf(CustomAccessibilityAction("收回候选") { currentCollapse?.invoke(); true })
                else if (enabled) customActions = listOf(CustomAccessibilityAction("展开候选") { currentExpand(); true })
            }
            .rubberBandDrag(band, { currentEnabled }, { if (currentCollapsing) 1f else -1f }) {
                if (currentCollapsing) currentCollapse?.invoke() else currentExpand()
            },
    ) {
        if (leadingGap > 0.dp) Spacer(Modifier.align(Alignment.CenterStart).width(leadingGap).fillMaxHeight().candidateGapTaps())
        // 位移只在画的时候读，拉动时候选行不重组。
        Box(Modifier.align(Alignment.CenterStart).padding(start = leadingGap).graphicsLayer { translationX = band.shown }) { content() }
        PullHint(band, hintColor, if (collapsing) "收起" else "展开")
    }
}

/** 整行往哪边走，空出来的那一边写「展开」/「收起」，过了门槛写「松手展开」/「松手收起」。 */
@Composable
private fun BoxScope.PullHint(band: RubberBand, hintColor: Color, verb: String) {
    val shown = band.shown
    if (shown == 0f) return
    val widthDp = with(LocalDensity.current) { abs(shown).toDp() }
    Box(Modifier.align(if (shown < 0f) Alignment.CenterEnd else Alignment.CenterStart).width(widthDp).fillMaxHeight(),
        contentAlignment = Alignment.Center) {
        Text(if (pullReleases(shown, band.threshold)) "松手$verb" else verb, color = hintColor.copy(alpha = 0.75f),
            fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.graphicsLayer { alpha = (abs(band.shown) / band.threshold).coerceIn(0f, 1f) })
    }
}

/**
 * 展开页的候选区：往右划过门槛松手调 [onCollapse]，页面跟手挪，不够就弹回；往左划不动（只在「候选滑到头回弹展开」打开时有）。
 * 上下划照旧滚列表；只认横着划（先过横向触摸阈值才算），不影响点候选、长按。
 */
@Composable
internal fun Modifier.swipeToCollapse(onCollapse: (() -> Unit)?): Modifier {
    if (onCollapse == null) return this
    val band = rememberRubberBand()
    val currentCollapse by rememberUpdatedState(onCollapse)
    return this
        .semantics { customActions = listOf(CustomAccessibilityAction("收回候选") { currentCollapse(); true }) }
        // 只认往右划（和候选栏上展开着往右拉收回同一方向，11-21 他：展开后往回划收起）。
        .rubberBandDrag(band, { true }, { 1f }) { currentCollapse() }
        .graphicsLayer {
            translationX = band.shown
            alpha = 1f - 0.3f * (abs(band.shown) / (band.threshold * PULL_MAX_FACTOR)).coerceIn(0f, 1f)
        }
}
