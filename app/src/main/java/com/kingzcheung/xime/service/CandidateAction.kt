package com.kingzcheung.xime.service

/**
 * 候选词变换后单个显示候选的上屏动作（与 CandidateState.candidates 平行）。
 *
 * - [engineIndex] >= 0：引擎候选引用（插件可能仅覆盖了显示注释），点击走引擎选词
 * - [engineIndex] < 0：插件自有候选，点击直接上屏 [commitText]（绕过引擎，所见即所得）
 *
 * 空列表 = 纯引擎语义（显示 index 即引擎 index），所有既有路径零影响。
 *
 * [isPrivate]：通讯录插件的人名、号码。上屏不学习：不进词库、联想、打字统计和插件事件。
 *
 * [glideInput]：中文滑行的备选（[GlideAlternatives]）。点它不上屏，把这一笔的编码换掉、组合留着。
 */
data class CandidateAction(
    val engineIndex: Int,
    val commitText: String,
    val isPrivate: Boolean = false,
    val glideInput: String? = null,
) {
    val isPluginCandidate: Boolean get() = engineIndex < 0

    companion object {
        fun engine(index: Int) = CandidateAction(engineIndex = index, commitText = "")
        fun plugin(text: String) = CandidateAction(engineIndex = -1, commitText = text)
        fun contact(text: String) = CandidateAction(engineIndex = -1, commitText = text, isPrivate = true)
        fun glide(text: String, input: String) = CandidateAction(engineIndex = -1, commitText = text, glideInput = input)
    }
}
