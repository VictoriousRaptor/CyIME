package com.kingzcheung.xime.service

/** Hardware and screen keyboards share candidate data and selection semantics. */
internal data class HardwareCandidatePresentation(
    val words: List<String>,
    val comments: List<String> = emptyList(),
    val association: Boolean = false,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
) {
    // Suggestions belong to the draggable toolbar. Only active composition uses
    // the separate caret-following candidate window.
    val standaloneWords: List<String> get() = if (association) emptyList() else words

    /**
     * 「联想也显示在候选窗」打开时，联想和打字候选在同一个浮窗里，不在浮窗和工具条之间来回换位置
     * （B 站反馈 10-31，外接便携键盘）。默认关：联想仍在工具条。
     */
    fun windowWords(associationInWindow: Boolean): List<String> = if (associationInWindow) words else standaloneWords

    /**
     * The physical-keyboard toolbar numbers at most [HARDWARE_PAGE_SIZE] candidates. A longer engine
     * page is shown as consecutive windows; paging moves through them before turning the engine page.
     * [step] is how many of them the row actually shows in full: the rest of the page still lies ahead.
     */
    fun window(start: Int, step: Int = HARDWARE_PAGE_SIZE): HardwareCandidatePresentation {
        val stride = step.coerceIn(1, HARDWARE_PAGE_SIZE)
        if (association || (start == 0 && words.size <= stride)) return this
        val size = hardwareWindowSize(words.size - start, stride)
        return copy(words = words.drop(start).take(size),
            comments = comments.drop(start).take(size),
            hasPreviousPage = start > 0 || hasPreviousPage,
            hasNextPage = start + size < words.size || hasNextPage)
    }
}

/**
 * 一页引擎候选（默认 20 个）在工具条上分几组显示，每组放得下 [fit] 个：组数按放得下的算，个数平均分。
 * 原来每组都放满、最后剩几个单成一组：放得下 9 个时是 9、9、2，第三组后面空一大片，每页都这样循环
 * （11-16 用户反馈，平板接蓝牙键盘输 yi）。现在是 7、7、6：不重复、不漏，也不留大片空白。
 */
internal fun hardwareWindowSize(remaining: Int, fit: Int): Int {
    if (remaining <= 0) return 0
    val f = fit.coerceIn(1, HARDWARE_PAGE_SIZE)
    if (remaining <= f) return remaining
    val groups = (remaining + f - 1) / f
    return (remaining + groups - 1) / groups
}

internal const val HARDWARE_PAGE_SIZE = 10

internal fun hardwareCandidatePresentation(
    state: CandidateState,
    english: Boolean,
    predictionPending: Boolean = false,
    associationLimit: Int = 10,
): HardwareCandidatePresentation {
    if (!english && (state.isComposing || state.inputText.isNotEmpty())) {
        return HardwareCandidatePresentation(state.candidates, state.candidateComments,
            hasPreviousPage = state.candidates.isNotEmpty() && state.hasPrevPage,
            hasNextPage = state.candidates.isNotEmpty() && state.hasNextPage)
    }
    // The shared state may still contain the previous association while the next
    // request runs. Hide its text and click targets only in the physical toolbar.
    if (!english && predictionPending) return HardwareCandidatePresentation(emptyList(), association = true)
    val words = if (state.pendingEnglishText.isNotEmpty() && state.englishReplaceSupported)
        listOf(state.pendingEnglishText) + state.associationCandidates
    else state.associationCandidates
    return HardwareCandidatePresentation(words.take(associationLimit.coerceAtLeast(0)), association = true)
}
