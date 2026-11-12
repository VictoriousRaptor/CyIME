package com.kingzcheung.xime.ui.keyboard

/** A candidate's transparent tap padding may cross an edge while its whole label still fits. */
internal fun candidateLabelFits(offset: Int, size: Int, padding: Int, start: Int, end: Int): Boolean =
    offset + padding >= start && offset + size - padding <= end

/** 候选的字被左边栏边切掉了（透明的点按留白越界不算）。右边越界的照常显示、被裁掉。 */
internal fun candidateLabelCutAtStart(offset: Int, padding: Int, start: Int): Boolean = offset + padding < start

/** Keep a contiguous prefix, no clipped last candidate. A single oversized first word is ellipsized. */
internal fun candidatePrefixCount(widths: List<Int>, available: Int, spacing: Int): Int =
    candidatePrefixCount(widths.size, available, spacing) { widths[it] }

/** Keep the current window while focus fits; crossing an edge scrolls toward that edge.
 * Indices remain engine/display indices, never positions in the visible window. */
internal inline fun candidateWindow(total: Int, highlighted: Int, available: Int, spacing: Int,
    windowStart: Int = 0,
    noinline edgeWidthAt: ((Int) -> Int)? = null,
    widthAt: (Int) -> Int): IntRange {
    if (total <= 0 || available <= 0) return IntRange.EMPTY
    val focused = highlighted.takeIf { it in 0 until total } ?: 0
    val start = windowStart.coerceIn(0, total - 1)
    if (focused < start) {
        val count = fittingCandidateCount(total, available, spacing, focused, edgeWidthAt, widthAt).coerceAtLeast(1)
        return focused until focused + count
    }
    val count = fittingCandidateCount(total, available, spacing, start, edgeWidthAt, widthAt).coerceAtLeast(1)
    if (focused < start + count) return start until start + count

    // Fill backwards from the next focused word, so it stays last instead of jumping to first.
    var first = focused
    var used = edgeWidthAt?.invoke(focused) ?: widthAt(focused)
    while (first > 0) {
        val next = used + spacing + widthAt(first - 1)
        if (next > available) break
        used = next
        first--
    }
    return first..focused
}

/** Measure only the visible prefix and one overflow item, regardless of engine page size. */
internal inline fun candidatePrefixCount(total: Int, available: Int, spacing: Int, widthAt: (Int) -> Int): Int {
    if (available <= 0 || total == 0) return 0
    return fittingCandidateCount(total, available, spacing, 0, null, widthAt).coerceAtLeast(1)
}

/** A trailing gloss can cross the edge if the candidate itself fits; earlier items use their full width. */
private inline fun fittingCandidateCount(total: Int, available: Int, spacing: Int, start: Int,
    noinline edgeWidthAt: ((Int) -> Int)?, widthAt: (Int) -> Int): Int {
    var used = 0
    var count = 0
    while (start + count < total) {
        val offset = used + (if (count == 0) 0 else spacing)
        val index = start + count
        val next = offset + widthAt(index)
        if (next > available) {
            if (edgeWidthAt != null && offset + edgeWidthAt(index) <= available) count++
            break
        }
        used = next
        count++
    }
    return count
}

/**
 * 能滑的候选行末尾要补的空白：从最后一个往前排满一屏，剩下的就是要补的，
 * 这样滑到最末、对齐到候选开头时，最后一屏左边也是一个完整的候选，不会被切掉一半。
 * 最后一个比整条还宽时不补。
 */
internal inline fun candidateTailPadding(total: Int, available: Int, spacing: Int, widthAt: (Int) -> Int): Int {
    if (total <= 0 || available <= 0) return 0
    var used = widthAt(total - 1)
    if (used >= available) return 0
    var first = total - 1
    while (first > 0) {
        val next = used + spacing + widthAt(first - 1)
        if (next > available) break
        used = next
        first--
    }
    return available - used
}

/** Remove the rendered prefix only; global engine indices survive filtering/reflow unchanged. */
internal fun remainingCandidates(entries: List<CandidateEntry>, visible: List<String>): List<CandidateEntry> =
    if (entries.take(visible.size).map { it.text } == visible) entries.drop(visible.size) else entries

/** 分体候选窗口：[range] 为显示的候选，前 [leftCount] 个排在中缝左边。 */
internal data class CandidateWindowSplit(val range: IntRange, val leftCount: Int)

/** 分体候选：从窗口起点先填满中缝左侧，再接着填右侧；焦点不在其中时从焦点重新开始。 */
internal fun splitCandidateWindow(
    total: Int, highlighted: Int, leftAvailable: Int, rightAvailable: Int, spacing: Int,
    windowStart: Int, edgeWidthAt: ((Int) -> Int)? = null, widthAt: (Int) -> Int,
): CandidateWindowSplit {
    if (total <= 0) return CandidateWindowSplit(IntRange.EMPTY, 0)
    val focused = highlighted.takeIf { it in 0 until total } ?: 0
    fun fill(start: Int): CandidateWindowSplit {
        val left = fittingCandidateCount(total, leftAvailable, spacing, start, edgeWidthAt, widthAt)
        val right = fittingCandidateCount(total, rightAvailable, spacing, start + left, edgeWidthAt, widthAt)
        val end = (start + (left + right).coerceAtLeast(1)).coerceAtMost(total)
        return CandidateWindowSplit(start until end, left)
    }
    val start = windowStart.coerceIn(0, total - 1)
    val window = fill(if (focused < start) focused else start)
    return if (focused in window.range) window else fill(focused)
}
