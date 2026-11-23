package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** 手机默认键盘上的候选栏高度；键盘总高度里包含这一条。 */
internal const val CANDIDATE_BAR_BASE_HEIGHT_DP = 44f

/** 手机默认键盘（约 880dp 高屏的 35%）每行的高度：到这里为止候选栏不放大。 */
private const val CANDIDATE_SCALE_REFERENCE_ROW_DP = 66f
/** 窄键盘按宽度收住：比手机还窄就不放大。 */
private const val CANDIDATE_SCALE_REFERENCE_WIDTH_DP = 360f
/** 与按键内容放大的上限一致（见 adaptiveKeyContentScale）。 */
private const val CANDIDATE_SCALE_MAX_KEY_GROWTH = 1.5f

/**
 * 候选栏（高度、候选字号、工具栏图标、拼音浮块）跟着键盘尺寸放大，速度是按键的一半
 * （用户 2026-10-24 要求）：按键比手机默认大 40%，候选栏大 20%，最多 1.25 倍。
 * 只看面板尺寸，不看 26/9 键，换布局时候选栏不跳。[panelHeightDp] 含候选栏、不含底部留白。
 */
internal fun candidateBarScale(panelWidthDp: Float, panelHeightDp: Float): Float {
    if (!(panelWidthDp > 0f) || !(panelHeightDp > 0f)) return 1f
    val rowDp = (panelHeightDp - CANDIDATE_BAR_BASE_HEIGHT_DP) / 4f
    val keyGrowth = minOf(rowDp / CANDIDATE_SCALE_REFERENCE_ROW_DP,
        panelWidthDp / CANDIDATE_SCALE_REFERENCE_WIDTH_DP).coerceIn(1f, CANDIDATE_SCALE_MAX_KEY_GROWTH)
    return 1f + (keyGrowth - 1f) / 2f
}

/** 候选栏实际高度（dp）。 */
internal fun candidateBarHeightDp(scale: Float): Float = CANDIDATE_BAR_BASE_HEIGHT_DP * scale

/** 当前候选栏放大倍数；候选栏内部按屏宽限宽时要除掉它。 */
internal val LocalCandidateBarScale = staticCompositionLocalOf { 1f }

/**
 * 「候选字大小」相对默认的倍数（用户 11-15：输入的拼音、候选栏图标都跟着这个字号走）。候选字本身直接用设置的 sp；
 * 拼音、图标按这个倍数放大缩小，候选栏高度不变，所以收在 0.8～1.2 之间，大字时图标也放得进栏里。
 */
internal val LocalCandidateSizeFactor = staticCompositionLocalOf { 1f }

internal fun candidateSizeFactor(candidateSp: Int): Float =
    (candidateSp.toFloat() / com.kingzcheung.xime.settings.SettingsPreferences.DEFAULT_CANDIDATE_TEXT_SIZE).coerceIn(0.8f, 1.2f)

/** 「候选字大小」滑条范围（sp）。下限不变；上限原来 22，用户 11-22 反馈「调到最大还是不够大，可以再大 1–2 倍」，放到 44。 */
internal const val CANDIDATE_TEXT_SIZE_MIN_SP = 12
internal const val CANDIDATE_TEXT_SIZE_MAX_SP = 44

/** 44dp 候选栏里放得下的最大字号，也就是原来滑条的最大值。到这里为止候选栏一点不变（和 1.15.104 一样）。 */
internal const val CANDIDATE_TEXT_SIZE_FIT_SP = 22

/**
 * 字号超过 [CANDIDATE_TEXT_SIZE_FIT_SP] 时候选栏整条按比例放大（高度、候选字、拼音、图标一起），
 * 就像把 22sp 那一档整体放大：44sp 是两倍，候选栏 88dp 高，下面的按键让出这一截。22sp 及以下恒为 1。
 */
internal fun candidateTextZoom(candidateSp: Int): Float =
    (candidateSp.toFloat() / CANDIDATE_TEXT_SIZE_FIT_SP).coerceAtLeast(1f)

/** 放大后的候选栏里写的字号：栏已经整体放大了 [candidateTextZoom] 倍，这里只写 22sp 那一档，乘出来正好是设置的字号。 */
internal fun candidateBarTextSp(candidateSp: Int): Int = candidateSp.coerceAtMost(CANDIDATE_TEXT_SIZE_FIT_SP)

/** 候选栏内容整体放大：dp 与 sp 一起乘倍数，栏内各尺寸保持原有比例。字号超过 22sp 时再乘 [candidateTextZoom]。 */
@Composable
internal fun ScaledCandidateBar(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val candidateSp = rememberCandidateTextSize()
    val total = scale * candidateTextZoom(candidateSp)
    val sizeFactor = candidateSizeFactor(candidateBarTextSp(candidateSp))
    // 倍数为 1 时也走同一层，跨过 1 时候选栏不会重建、丢掉滚动位置。
    CompositionLocalProvider(
        LocalDensity provides if (total == 1f) density else Density(density.density * total, density.fontScale),
        LocalCandidateBarScale provides total,
        LocalCandidateSizeFactor provides sizeFactor,
        content = content,
    )
}
