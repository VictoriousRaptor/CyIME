package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputMode
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.LayoutKind

/** Display focus is tied to candidate identities, never a persistent engine selection. */
data class CandidateFocus(
    val index: Int,
    private val revision: Long,
    private val input: String,
    private val words: List<String>,
    private val comments: List<String>,
    private val actions: List<CandidateAction>,
    private val composing: Boolean,
) {
    internal fun matches(state: CandidateState): Boolean = composing == state.isComposing &&
        !state.isShowingRecentClipboard && revision == state.engineRevision && input == state.inputText &&
        words == state.t9NavigationWords && comments == state.candidateComments && actions == state.candidateActions

    companion object {
        internal fun at(index: Int, state: CandidateState) = CandidateFocus(index, state.engineRevision,
            state.inputText, state.t9NavigationWords, state.candidateComments, state.candidateActions, state.isComposing)
    }
}

/**
 * 「下一项」管得着的键盘：九键拼音、双拼，和笔画键盘（11-18 贡献者：笔画的 0 键也做成九键那样的下一项）。
 * 笔画的编码不是拼音，编码显示、左栏音节只按九键换（见 [shownPreedit]、[highlightedT9Syllable]）。
 */
fun CandidateState.usesT9CandidateNavigation(profile: InputProfile): Boolean =
    profile.language == InputLanguage.CHINESE && profile.mode == InputMode.KEYBOARD &&
        ((profile.layout.kind == LayoutKind.T9 &&
            (profile.scheme == InputScheme.PINYIN || profile.scheme == InputScheme.DOUBLE_PINYIN)) ||
            (profile.layout.kind == LayoutKind.STROKE_KEYPAD && profile.scheme == InputScheme.STROKE)) &&
        ((isComposing && inputText.isNotEmpty()) ||
            (!isComposing && inputText.isEmpty() && candidates.isEmpty() && associationCandidates.isNotEmpty() &&
                !isDeletionRecovery && !isDigitPhraseList)) &&
        pendingEnglishText.isEmpty() && !isShowingRecentClipboard

internal val CandidateState.t9NavigationWords get() = if (isComposing) candidates else associationCandidates
internal val CandidateState.focusedT9AssociationIndex: Int? get() = candidateFocus
    ?.takeIf { !isComposing && it.index in associationCandidates.indices && it.matches(this) }?.index

/** Missing/changed candidates reset to the first item, including after partial commit or editing. */
val CandidateState.highlightedCandidateIndex: Int get() = candidateFocus
    ?.takeIf { it.index in t9NavigationWords.indices && it.matches(this) }?.index ?: 0

internal fun CandidateState.advanceT9Candidate(profile: InputProfile): CandidateState =
    moveT9Candidate(profile, 1)

internal fun CandidateState.retreatT9Candidate(profile: InputProfile): CandidateState =
    moveT9Candidate(profile, -1)

/** 到首个、末个就停住，不绕到另一头（用户 10-31：左滑不能切到队尾）。 */
private fun CandidateState.moveT9Candidate(profile: InputProfile, direction: Int): CandidateState {
    val words = t9NavigationWords
    if (!usesT9CandidateNavigation(profile) || words.isEmpty()) return this
    val index = if (!isComposing && focusedT9AssociationIndex == null) {
        if (direction > 0) 0 else return this
    } else (highlightedCandidateIndex + direction).coerceIn(0, words.lastIndex)
    if (isComposing && index == highlightedCandidateIndex) return this
    if (!isComposing && index == focusedT9AssociationIndex) return this
    return copy(candidateFocus = CandidateFocus.at(index, this))
}

internal fun CandidateState.spaceCandidateIndex(profile: InputProfile): Int =
    if (usesT9CandidateNavigation(profile)) highlightedCandidateIndex else 0

/** 开语音、手写前要先上屏的高亮候选：只有「下一项」把高亮移开首选时才有，和空格上屏同一个；否则 null（照旧按首选）。 */
internal fun CandidateState.modeChangeCandidateIndex(profile: InputProfile): Int? =
    if (isComposing) spaceCandidateIndex(profile).takeIf { it > 0 } else null

/** 高亮候选自己的读音：九键候选注释就是拼音；没有注释的英文候选用它本身。 */
internal fun CandidateState.highlightedT9Reading(): String? {
    if (!isComposing) return null
    val index = highlightedCandidateIndex
    candidateComments.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return candidates.getOrNull(index)?.takeIf { text -> text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' } }
        ?.lowercase()
}

/**
 * 屏上显示的编码，空格、回车和符号上屏都以它为准（用户 10-27：屏上任何地方都不能和高亮候选不一致）。
 * 「下一项」换了高亮时，换成高亮候选的拼音；没换高亮就是引擎给的 [CandidateState.preeditText]。
 */
fun CandidateState.shownPreedit(profile: InputProfile): String {
    if (profile.layout.kind != LayoutKind.T9 ||
        !usesT9CandidateNavigation(profile) || !isComposing || highlightedCandidateIndex == 0) return preeditText
    return t9PreeditWithReading(preeditText, highlightedT9Reading() ?: return preeditText) ?: preeditText
}

/** 左栏应标出的音节：「下一项」换了高亮时是高亮候选的第一个音节，否则 null（左栏照旧）。 */
fun CandidateState.highlightedT9Syllable(profile: InputProfile): String? {
    if (profile.layout.kind != LayoutKind.T9 ||
        !usesT9CandidateNavigation(profile) || !isComposing || highlightedCandidateIndex == 0) return null
    return highlightedT9Reading()?.split(' ', '\'')?.firstOrNull { it.isNotEmpty() }?.lowercase()
}

private val T9_DIGIT = mapOf('a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3',
    'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5', 'm' to '6', 'n' to '6', 'o' to '6',
    'p' to '7', 'q' to '7', 'r' to '7', 's' to '7', 't' to '8', 'u' to '8', 'v' to '8', 'ü' to '8',
    'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9')

/**
 * 把 [preedit] 里候选用掉的那段换成它的读音 [reading]，前面已选的汉字和后面没用掉的编码不动。
 * 读音的按键和编码开头相同（同键不同音，如 pen/ren）就按字母数替换；简拼等对不上时按音节数替换。
 */
internal fun t9PreeditWithReading(preedit: String, reading: String): String? {
    val start = preedit.indexOfFirst { it.lowercaseChar() in T9_DIGIT }
    if (start < 0) return null
    val prefix = preedit.substring(0, start)
    val code = preedit.substring(start)
    val separator = code.firstOrNull { it == '\'' || it == ' ' } ?: '\''
    val syllables = reading.lowercase().split(' ', '\'').filter { it.isNotEmpty() }
    if (syllables.isEmpty()) return null
    val readingDigits = syllables.joinToString("").map { T9_DIGIT[it] ?: return null }.joinToString("")
    val codeDigits = code.filter { it.lowercaseChar() in T9_DIGIT }.map { T9_DIGIT.getValue(it.lowercaseChar()) }.joinToString("")
    val rest = if (codeDigits.startsWith(readingDigits)) {
        // 跳过读音用掉的字母，保留其余编码原来的分隔。
        var letters = readingDigits.length
        code.dropWhile { c -> if (letters == 0) false else { if (c.lowercaseChar() in T9_DIGIT) letters--; true } }
    } else {
        val segments = code.split('\'', ' ').filter { it.isNotEmpty() }
        if (syllables.size > segments.size) return null
        segments.drop(syllables.size).joinToString(separator.toString())
    }.trimStart('\'', ' ')
    val shown = syllables.joinToString(separator.toString())
    return prefix + if (rest.isEmpty()) shown else "$shown$separator$rest"
}
