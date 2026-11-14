package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.InputProfile

internal enum class HardwareCandidateKey { LEFT, RIGHT, UP, DOWN, SPACE, ENTER, DIGIT, CANCEL }

/** A hardware selection belongs to one editor, language and displayed candidate source. */
internal data class HardwareCandidateSnapshot(
    val editor: Long,
    val profile: InputProfile,
    val state: CandidateState,
    val english: Boolean,
    val predictionPending: Boolean = false,
    val associationContext: String = "",
    val associationLimit: Int = 10,
    val expanded: Boolean = false,
    val singleCharOnly: Boolean = false,
    /** First engine-page index shown by the toolbar (see [HardwareCandidatePresentation.window]). */
    val windowStart: Int = 0,
    /** How many of these the candidate row shows in full; paging moves by this many (see [HardwareCandidateNavigation.step]). */
    val windowStep: Int = HARDWARE_PAGE_SIZE,
) {
    private val basePresentation = hardwareCandidatePresentation(state, english, predictionPending, associationLimit)
        .let { if (expanded) it else it.window(windowStart, windowStep) }
    private val waitingForPrediction = predictionPending && !english && basePresentation.association
    /** Expanded UI indices refer to a filtered view; commits need the original global index. */
    val expandedGlobalIndices: List<Int>? = if (expanded && state.expandedCandidatesLoaded && !basePresentation.association) {
        state.expandedCandidates.indices.filter { index ->
            !singleCharOnly || state.expandedCandidates[index].text.isSingleCharacter()
        }
    } else null
    val words: List<String> = when {
        expandedGlobalIndices != null -> expandedGlobalIndices.map { state.expandedCandidates[it].text }
        basePresentation.association -> basePresentation.words
        else -> basePresentation.words.take(HARDWARE_PAGE_SIZE)
    }
    val comments: List<String> = expandedGlobalIndices?.map { state.expandedCandidates[it].comment }
        ?: basePresentation.comments.take(words.size)
    val presentation = basePresentation.copy(words = words, comments = comments)
    private val canonicalActions = state.candidates.indices.map { index ->
        state.candidateActions.getOrElse(index) { CandidateAction.engine(index) }
    }

    fun sameSource(other: HardwareCandidateSnapshot): Boolean =
        editor == other.editor && profile == other.profile && english == other.english &&
            waitingForPrediction == other.waitingForPrediction &&
            presentation.association == other.presentation.association &&
            if (presentation.association) {
                words == other.words && comments == other.comments &&
                    state.pendingEnglishText == other.state.pendingEnglishText &&
                    state.englishReplaceSupported == other.state.englishReplaceSupported &&
                    associationContext == other.associationContext
            } else {
                state.engineRevision == other.state.engineRevision &&
                    state.inputText == other.state.inputText &&
                    state.isShowingRecentClipboard == other.state.isShowingRecentClipboard &&
                    state.candidateComments == other.state.candidateComments &&
                    canonicalActions == other.canonicalActions &&
                    expanded == other.expanded && singleCharOnly == other.singleCharOnly &&
                    sameDisplayedCandidates(other)
            }

    private fun sameDisplayedCandidates(other: HardwareCandidateSnapshot): Boolean {
        val oldIndices = expandedGlobalIndices
        val newIndices = other.expandedGlobalIndices
        if (oldIndices == null || newIndices == null) {
            return oldIndices == newIndices && words == other.words && comments == other.comments
        }
        // Expanded candidates load in batches. Appending preserves every existing
        // display/global mapping; removal, insertion or reordering does not.
        return state.expandedCandidates.isPrefixOf(other.state.expandedCandidates) &&
            oldIndices.isPrefixOf(newIndices) && words.isPrefixOf(other.words) && comments.isPrefixOf(other.comments)
    }

    private fun <T> List<T>.isPrefixOf(other: List<T>): Boolean =
        size <= other.size && indices.all { this[it] == other[it] }
}

internal sealed interface HardwareCandidateDecision {
    data class Highlight(val index: Int) : HardwareCandidateDecision
    /** [wholeWindow]: the highlight walked off the end of the numbered words, so skip all of them. */
    data class Page(val direction: Int, val wholeWindow: Boolean = false) : HardwareCandidateDecision
    data class Confirm(val index: Int, val association: Boolean) : HardwareCandidateDecision
    data object Cancel : HardwareCandidateDecision
    data object Consume : HardwareCandidateDecision
    data object Host : HardwareCandidateDecision
    data object DefaultInput : HardwareCandidateDecision
    data object MoveCursor : HardwareCandidateDecision
    data object LiteralSpace : HardwareCandidateDecision
    /** Commit the composition exactly as typed, adding nothing. */
    data object CommitTyped : HardwareCandidateDecision
}

/**
 * Physical arrows belong to the editor, independent of candidate-window navigation.
 *
 * 空格有意不跟随工具栏高亮（用户 2026-10-12 决定，勿按 Windows 端改回“空格选高亮”）：
 * Android 每次上屏后都会自动弹出下一个词的智能联想，空格必须始终是可预期的普通动作——
 * 组词时确认默认候选，上屏后的联想只能用数字或点按选，空格照常输入空格，
 * 否则正常打字会频繁把联想词误上屏。Windows 端空格上屏 Rime 高亮的那个候选，两端在此有意不同。
 */
internal fun physicalCandidateDecision(
    key: HardwareCandidateKey,
    snapshot: HardwareCandidateSnapshot,
    engineHasInput: Boolean?,
    digitIndex: Int? = null,
): HardwareCandidateDecision = when (key) {
    HardwareCandidateKey.CANCEL -> HardwareCandidateDecision.Cancel
    HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT,
    HardwareCandidateKey.UP, HardwareCandidateKey.DOWN -> HardwareCandidateDecision.MoveCursor
    HardwareCandidateKey.SPACE -> when {
        snapshot.english || snapshot.state.pendingEnglishText.isNotEmpty() -> HardwareCandidateDecision.LiteralSpace
        engineHasInput == true || snapshot.state.isComposing || snapshot.state.inputText.isNotEmpty() -> {
            // Only post-commit suggestions are number/click-only. Space still
            // confirms active composition, using the current FIFO snapshot.
            if (engineHasInput == true && !snapshot.presentation.association &&
                !snapshot.state.isShowingRecentClipboard && snapshot.words.isNotEmpty()) {
                val index = if (snapshot.expandedGlobalIndices != null || snapshot.windowStart > 0) 0
                    else snapshot.state.spaceCandidateIndex(snapshot.profile)
                HardwareCandidateDecision.Confirm(index, false)
            } else if (engineHasInput == true && !snapshot.presentation.association &&
                !snapshot.state.isShowingRecentClipboard && snapshot.state.isComposing &&
                snapshot.state.candidates.isEmpty() && snapshot.state.expandedCandidates.isEmpty()) {
                // The engine itself has nothing for this spelling (the list was refreshed in the
                // FIFO): Space leaves the letters as typed and adds no space, since Space selects
                // and never sometimes inserts one (user, 2026-10-24).
                HardwareCandidateDecision.CommitTyped
            } else {
                // Missing/stale or filtered-out candidates must not turn Space into raw-letter Enter.
                HardwareCandidateDecision.Consume
            }
        }
        else -> HardwareCandidateDecision.LiteralSpace
    }
    HardwareCandidateKey.ENTER -> HardwareCandidateDecision.DefaultInput
    HardwareCandidateKey.DIGIT -> when {
        // An English completion must not steal the digits of hello123. Idle/pending
        // predictions and unlabelled/out-of-range digits also remain literal input.
        snapshot.english || snapshot.state.pendingEnglishText.isNotEmpty() -> HardwareCandidateDecision.DefaultInput
        digitIndex !in snapshot.words.indices || snapshot.state.isShowingRecentClipboard -> HardwareCandidateDecision.DefaultInput
        !snapshot.presentation.association && engineHasInput != true -> HardwareCandidateDecision.DefaultInput
        else -> HardwareCandidateDecision.Confirm(digitIndex!!, snapshot.presentation.association)
    }
}

/** Keeps display navigation separate from engine selection and ordinary English spacing. */
internal class HardwareCandidateNavigation {
    private data class Focus(val source: HardwareCandidateSnapshot, val index: Int)
    @Volatile private var focus: Focus? = null
    private val ownedArrows = mutableSetOf<HardwareCandidateKey>()

    // Toolbar window over the engine page (HardwareCandidatePresentation.window). A page turned
    // backwards is a full engine page and opens at its last window, as paging forward would end.
    private data class Window(val revision: Long, val candidates: List<String>, val start: Int,
        val previousStarts: List<Int> = emptyList())
    private data class BackwardPage(val revision: Long, val input: String)
    @Volatile private var window: Window? = null
    @Volatile private var backwardPage: BackwardPage? = null

    // 候选行实际完整显示了几个（工具条窄、图标多时放不下 10 个）。翻页按这个数走，
    // 否则 +/- 和上下键一次跳 10 个，被栏边裁掉的那几个永远翻不到（用户 11-13，平板接物理键盘）。
    private data class Shown(val words: List<String>, val count: Int)
    @Volatile private var shown: Shown? = null

    /** The row drew [words] and [count] of them fit completely. Called from composition. */
    fun reportShown(words: List<String>, count: Int) {
        if (words.isEmpty() || count <= 0) return
        val next = Shown(words.take(HARDWARE_PAGE_SIZE), count.coerceAtMost(words.size))
        if (shown != next) shown = next
    }

    /** Latest row's count, for windows not measured yet (paging back into the previous page). */
    private fun estimate(): Int = shown?.count?.coerceIn(1, HARDWARE_PAGE_SIZE) ?: HARDWARE_PAGE_SIZE

    /** Paging stride for the window starting at [start]: what the row shows of it, else the numbered ten. */
    fun step(state: CandidateState, start: Int = windowStart(state)): Int {
        val current = shown ?: return HARDWARE_PAGE_SIZE
        val words = state.candidates.drop(start).take(HARDWARE_PAGE_SIZE)
        return if (current.words.size <= words.size && current.words == words.take(current.words.size))
            current.count.coerceIn(1, HARDWARE_PAGE_SIZE) else HARDWARE_PAGE_SIZE
    }

    fun clear() { focus = null }

    fun windowStart(state: CandidateState): Int {
        window?.let { if (it.revision == state.engineRevision && it.candidates == state.candidates) return it.start }
        val back = backwardPage ?: return 0
        if (back.revision == state.engineRevision) return 0
        backwardPage = null
        if (back.input != state.inputText || state.candidates.isEmpty()) return 0
        // 新的一页还没量过：按刚才那页显示得下的个数估一个，从末尾往前开；再往前翻每次退同样多，不会漏掉。
        val start = (state.candidates.size - estimate()).coerceAtLeast(0)
        window = Window(state.engineRevision, state.candidates, start)
        return start
    }

    /**
     * Moves within the engine page; false when the engine itself has to page. Forward moves by what the
     * row shows ([step]), or by [stride]; backward returns to the window it came from.
     */
    fun moveWindow(state: CandidateState, direction: Int, stride: Int? = null): Boolean {
        val current = windowStart(state)
        val history = window?.takeIf { it.revision == state.engineRevision && it.candidates == state.candidates }
            ?.previousStarts.orEmpty()
        val (next, previous) = when {
            direction > 0 -> current + (stride ?: step(state, current)).coerceAtLeast(1) to history + current
            history.isNotEmpty() -> history.last() to history.dropLast(1)
            // 没走过的窗口（从下一页翻回来）：前面那几个还没量过，按最近一行的个数往前退。
            current > 0 -> (current - (stride ?: estimate())).coerceAtLeast(0) to emptyList()
            else -> -1 to emptyList()
        }
        if (next < 0 || next >= state.candidates.size) return false
        window = Window(state.engineRevision, state.candidates, next, previous)
        backwardPage = null
        focus = null
        return true
    }

    /** Call after the engine turned its page from [previous]. */
    fun enginePaged(previous: CandidateState, direction: Int) {
        // The old window keeps matching only the old page; the new page has a new revision.
        backwardPage = if (direction < 0) BackwardPage(previous.engineRevision, previous.inputText) else null
        focus = null
    }

    // FIFO-only hold ownership outlives the candidate list until the physical UP.
    fun startPress(key: HardwareCandidateKey, repeat: Boolean) { if (!repeat) ownedArrows.remove(key) }
    fun retainPress(key: HardwareCandidateKey) {
        if (key == HardwareCandidateKey.LEFT || key == HardwareCandidateKey.RIGHT ||
            key == HardwareCandidateKey.UP || key == HardwareCandidateKey.DOWN) ownedArrows += key
    }
    fun releasePress(key: HardwareCandidateKey) { ownedArrows.remove(key) }
    fun ownsPress(key: HardwareCandidateKey): Boolean = key in ownedArrows

    fun selectedIndex(snapshot: HardwareCandidateSnapshot): Int? = focus
        ?.takeIf { it.source.sameSource(snapshot) && it.index in snapshot.words.indices }?.index

    /** engineHasInput is read inside the input FIFO; null means the engine cannot safely be inspected. */
    fun decide(
        key: HardwareCandidateKey,
        snapshot: HardwareCandidateSnapshot,
        engineHasInput: Boolean?,
        pageAtBoundary: Boolean = false,
        digitIndex: Int? = null,
    ): HardwareCandidateDecision {
        val index = selectedIndex(snapshot)
        if (index == null) clear()
        val state = snapshot.state
        val uiHasComposition = !snapshot.english && (state.isComposing || state.inputText.isNotEmpty())
        val hasComposition = engineHasInput == true || uiHasComposition
        val hasInput = hasComposition || state.pendingEnglishText.isNotEmpty() ||
            state.candidates.isNotEmpty() || state.associationCandidates.isNotEmpty() || snapshot.predictionPending
        if (key == HardwareCandidateKey.CANCEL) {
            clear()
            return if (hasInput) HardwareCandidateDecision.Cancel else HardwareCandidateDecision.Host
        }
        if (engineHasInput == null && uiHasComposition) return HardwareCandidateDecision.Consume
        // Enter during composition keeps the established raw-input/reading commit.
        if (key == HardwareCandidateKey.ENTER) return HardwareCandidateDecision.DefaultInput
        if (key == HardwareCandidateKey.DIGIT) {
            return when {
                // English is already in the editor. Digits extend that text (hello123),
                // rather than replacing it with one of the completion suggestions.
                (snapshot.english || state.pendingEnglishText.isNotEmpty()) && index == null -> HardwareCandidateDecision.DefaultInput
                hasComposition && snapshot.words.isEmpty() -> HardwareCandidateDecision.Consume
                snapshot.words.isEmpty() -> HardwareCandidateDecision.DefaultInput
                digitIndex != null && digitIndex in snapshot.words.indices ->
                    HardwareCandidateDecision.Confirm(digitIndex, snapshot.presentation.association)
                snapshot.presentation.association -> HardwareCandidateDecision.DefaultInput
                else -> HardwareCandidateDecision.Consume
            }
        }
        val vertical = key == HardwareCandidateKey.UP || key == HardwareCandidateKey.DOWN
        val direction = if (key == HardwareCandidateKey.LEFT || key == HardwareCandidateKey.UP) -1 else 1
        if (vertical && !snapshot.presentation.association) {
            val canPage = snapshot.expanded || if (direction < 0) snapshot.presentation.hasPreviousPage else snapshot.presentation.hasNextPage
            if (canPage) {
                clear()
                return HardwareCandidateDecision.Page(direction)
            }
            return HardwareCandidateDecision.Consume
        }
        if (snapshot.words.isNotEmpty()) {
            if (key == HardwareCandidateKey.SPACE) {
                // Suggestions are number/click-only. A separator must never insert predicted text.
                return if (snapshot.presentation.association) HardwareCandidateDecision.DefaultInput
                    else HardwareCandidateDecision.Confirm(index ?: 0, false)
            }
            if (pageAtBoundary && !snapshot.presentation.association &&
                ((direction < 0 && (index ?: 0) == 0 && snapshot.presentation.hasPreviousPage) ||
                    (direction > 0 && (index ?: 0) == snapshot.words.lastIndex && snapshot.presentation.hasNextPage))) {
                clear()
                // Tab 和触摸箭头是逐个走的：走到这组最后一个时，这组都已经看过，翻过整组。
                return HardwareCandidateDecision.Page(direction, wholeWindow = direction > 0)
            }
            val next = ((index ?: 0) + direction).coerceIn(snapshot.words.indices)
            focus = Focus(snapshot, next)
            return HardwareCandidateDecision.Highlight(next)
        }
        if (hasComposition) {
            return HardwareCandidateDecision.Consume
        }
        return if (key == HardwareCandidateKey.SPACE || key == HardwareCandidateKey.ENTER) HardwareCandidateDecision.DefaultInput
            else HardwareCandidateDecision.Host
    }
}
