package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate

data class CandidateState(
    val candidates: List<String> = emptyList(),
    val candidateComments: List<String> = emptyList(),
    val inputText: String = "",
    val preeditText: String = "",
    val isComposing: Boolean = false,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val associationCandidates: List<String> = emptyList(),
    val pendingEnglishText: String = "",
    /** 刚由滑行整词上屏的词；光标仍紧跟它时，下一个点按字母先补空格。 */
    val glidedWord: String = "",
    val isShowingRecentClipboard: Boolean = false,
    /** 显示剪贴板时 [candidates] 开头有几项是提取出来的链接、长数字（前面的短胶囊），其余是原文。 */
    val clipboardExtractedCount: Int = 0,
    /** 当前宿主是否支持英文候选的"回删替换"（终端等受限宿主为 false，不展示英文候选）。 */
    val englishReplaceSupported: Boolean = true,
    /** 英文直接输入打这个词时「智能联想」关着：不补词，原词也不单独放上候选栏，见 [showsTypedEnglishWord]。 */
    val englishCompletionOff: Boolean = false,
    /** 候选词变换映射（插件 candidate_transform 能力）：与 [candidates] 平行；
     *  空列表 = 纯引擎语义（显示 index 即引擎 index）。见 CandidateAction。 */
    val candidateActions: List<CandidateAction> = emptyList(),
    /** 当前引擎候选中用户以前打过的词（按文字），候选右上角画学习标记。 */
    val learnedCandidates: Set<String> = emptySet(),
    /** 当前引擎候选中纠错来的词（按文字）→ 引擎给的逐音节纠正，候选旁标出怎么改的。 */
    val correctedCandidates: Map<String, String> = emptyMap(),
    /** 跨页全量候选（仅候选展开态时由服务层填充，供本地分页与单字筛选）。
     *  空列表 = 未填充或引擎无候选。全局索引用于 selectCandidateByGlobalIndex。 */
    val expandedCandidates: List<RimeCandidate> = emptyList(),
    val expandedCandidatesLoaded: Boolean = false,
    /** Identity of the native candidate list, independent of displayed preedit text. */
    val engineRevision: Long = 0L,
    val traceEventId: Int = 0,
    val smsVerificationCode: String? = null,
    val clipboardImage: com.kingzcheung.xime.clipboard.ClipboardImage? = null,
    val candidateFocus: CandidateFocus? = null,
    /** 「删字后给同音字」放上去的那一组（第一项是删掉的那一段）；联想位置换成别的词后自然不再算。见 DeletionRecovery。 */
    val deletionRecovery: List<String> = emptyList(),
    /** 「打数字出短语」放上去的那一组；[digitPhraseText] 是光标前那串数字（原样），选了就换掉它。见 DigitPhrases。 */
    val digitPhrases: List<String> = emptyList(),
    val digitPhraseText: String = "",
    /**
     * 数字键盘算式放上去的那一组（结果、算式 = 结果）；[candidates] 还是这一组时候选栏按计算器显示（能滑、没有展开键）。
     * 计算器引擎不是界面状态，界面按它判断会停在旧值：长算式被当成打字候选，展开页是空的（GitHub #289）。
     */
    val calculatorCandidates: List<String> = emptyList(),
) {
    /** 候选栏现在放的是不是计算器结果。 */
    val showsCalculator: Boolean get() = calculatorCandidates.isNotEmpty() && calculatorCandidates == candidates
}

/**
 * 英文直接输入时候选栏第一个放已打的原词，后面接补全。「智能联想」关着又没有别的词（「英文出短语」、邮箱后缀）时
 * 不放：关了联想，直接输入就什么候选都不给（用户 11-23）。英文单词方案、滑行不走这里，照常有候选。
 */
internal val CandidateState.showsTypedEnglishWord: Boolean
    get() = pendingEnglishText.isNotEmpty() && englishReplaceSupported &&
        !(englishCompletionOff && associationCandidates.isEmpty())

/** 候选栏上现在是删字后放回的那一组：不挡退格、不被空格上屏、不进九键选词。 */
internal val CandidateState.isDeletionRecovery: Boolean
    get() = deletionRecovery.isNotEmpty() && associationCandidates == deletionRecovery

/** 候选栏上现在是打数字给的短语：不挡退格、不被空格上屏、不进九键选词，选了换掉光标前那串数字。 */
internal val CandidateState.isDigitPhraseList: Boolean
    get() = digitPhrases.isNotEmpty() && digitPhraseText.isNotEmpty() && associationCandidates == digitPhrases

/** Appending expanded pages or updating suggestions does not change existing candidate identities. */
internal fun CandidateState.hasSameSelectionSource(current: CandidateState): Boolean =
    engineRevision == current.engineRevision && inputText == current.inputText &&
        isShowingRecentClipboard == current.isShowingRecentClipboard &&
        candidates == current.candidates && candidateComments == current.candidateComments &&
        candidateActions == current.candidateActions &&
        expandedCandidates.size <= current.expandedCandidates.size &&
        expandedCandidates.indices.all { expandedCandidates[it] == current.expandedCandidates[it] }

/** The expanded strip and grid use global indices, even for items within the first page. */
internal fun CandidateState.englishForHeldSelection(index: Int, global: Boolean): String? {
    val text = if (global) expandedCandidates.getOrNull(index)?.text else candidates.getOrNull(index)
    return text?.let(com.kingzcheung.xime.gloss.CandidateGlossary::englishFor)
}

/** A refreshed row elsewhere in the bar does not invalidate the actual item under the finger. */
internal fun CandidateState.stillOffers(current: CandidateState, index: Int, expanded: RimeCandidate?): Boolean {
    if (engineRevision != current.engineRevision || inputText != current.inputText ||
        isShowingRecentClipboard != current.isShowingRecentClipboard) return false
    if (expanded != null) return expandedCandidates.getOrNull(index) == expanded && current.expandedCandidates.getOrNull(index) == expanded
    return index in candidates.indices && candidates[index] == current.candidates.getOrNull(index) &&
        candidateComments.getOrNull(index) == current.candidateComments.getOrNull(index) &&
        candidateActions.getOrNull(index) == current.candidateActions.getOrNull(index)
}
