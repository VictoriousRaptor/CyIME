package com.kingzcheung.xime.service

/**
 * CyIME 写进输入框的组合区（拼音编码回显 / 语音临时文本）。Android 不能查询宿主的组合状态，
 * 由写入点登记；拼音会同时记下文字，宿主重启输入后框架把组合区定格成普通文字时用来核对。
 */
internal class InlineCompositionTracker {
    var active = false
        private set
    /** 最近一次写入的拼音编码；语音临时文本不记文字（null）。 */
    var text: String? = null
        private set

    /** Offset in the last composing text. Ordinary setComposingText(..., 1) puts it at the end. */
    var caret = 0
        private set
    fun shown(text: String?) {
        if (!active) start = anchor
        active = true; this.text = text; caret = text?.length ?: 0
    }
    fun moveCaret(offset: Int) { caret = offset }

    /** 编码在输入框里的起点（宿主报来的组合区起点，或开始打拼音前宿主报来的光标）；不知道是 -1。 */
    var start = -1
        private set
    /** 还没打拼音时宿主最近报来的折叠光标：下一段编码从这里开始写。 */
    private var anchor = -1
    private var hostReportedSpan = false
    /** 宿主报过的组合区起点；只知道开打前的光标、没报过组合区时为 -1。 */
    val reportedStart: Int get() = if (hostReportedSpan) start else -1

    /**
     * onUpdateSelection（和新输入框的初始选区）：记下编码起点。宿主换了整段文字（setText）后光标常被放回开头，
     * 光标处找不到编码，要靠起点找回；上屏、清空后起点作废，等宿主重新报光标。
     */
    fun selectionReported(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (active) {
            if (text != null && candidatesStart >= 0 && candidatesEnd - candidatesStart == text?.length) {
                start = candidatesStart
                hostReportedSpan = true
            }
        } else anchor = if (selStart >= 0 && selStart == selEnd && (candidatesStart < 0 || candidatesStart == candidatesEnd)) selStart else -1
    }
    fun hostCaret(start: Int, end: Int, selection: Int, selectionEnd: Int): Int? {
        val code = text ?: return null
        if (start < 0 || end - start != code.length || selection != selectionEnd || selection !in start..end) return null
        return (selection - start).takeIf { it != caret }
    }
    /** A host can finish composing when a selection handle is touched without restarting input. */
    fun takeLostSpan(selection: Int, end: Int, candidatesStart: Int, candidatesEnd: Int): DetachedComposition? {
        val code = text ?: return null
        if (!hostReportedSpan || candidatesStart != -1 || candidatesEnd != -1) return null
        hostReportedSpan = false
        if (selection != end || selection !in start..(start + code.length)) return null
        return DetachedComposition(start, start + code.length)
    }

    fun ended() { active = false; text = null; start = -1; anchor = -1; hostReportedSpan = false }

    /** 新的输入连接开始：旧组合区已不可再用，返回其拼音文字供核对。 */
    fun detach(): String? = text.takeIf { active }.also { ended() }
}

internal data class DetachedComposition(val start: Int, val end: Int)

/**
 * 编码显示在输入框时，长按空格挪编码里的光标，宿主里该放到的位置。先拿宿主文字（[content] 从 [contentStart] 起，
 * 选区 [selStart]..[selEnd] 相对 [content]）核对编码在哪；有的宿主取不到文字、给的是旧文字，或上次放光标没照做，
 * 核对不上就用宿主报过的组合区起点 [reportedStart]（用户 11-18：「拼音显示在输入框的时候无法移动，但是候选词是跟着移动而变化的」）。
 * 两样都没有返回 null，不往编码外面放。
 */
internal fun inlineCaretPosition(
    code: String, trackedCaret: Int, offset: Int,
    content: CharSequence?, contentStart: Int, selStart: Int, selEnd: Int, reportedStart: Int,
): Int? {
    if (offset !in 0..code.length) return null
    if (content != null && contentStart >= 0 && selStart in 0..content.length && selStart == selEnd) {
        val start = selStart - trackedCaret
        if (start >= 0 && start + code.length <= content.length &&
            content.subSequence(start, start + code.length).toString() == code) return contentStart + start + offset
    }
    return if (reportedStart >= 0) reportedStart + offset else null
}

/**
 * 宿主重启输入后，在光标处找回被定格的拼音编码。光标必须是折叠的，且光标前后的文字
 * 恰好能拼回原编码（允许宿主把光标放在编码中间）；拼法不唯一或对不上时返回 null，不碰任何文字。
 */
internal fun locateDetachedComposition(
    text: String?, selStart: Int, selEnd: Int, before: CharSequence?, after: CharSequence?,
): DetachedComposition? {
    if (text.isNullOrEmpty() || selStart < 0 || selStart != selEnd || before == null || after == null) return null
    val split = (0..text.length).filter { k ->
        before.endsWith(text.substring(0, k)) && after.startsWith(text.substring(k))
    }.singleOrNull() ?: return null
    val start = selStart - split
    return if (start < 0) null else DetachedComposition(start, start + text.length)
}

/**
 * 宿主整段换掉文字（setText）后光标常被放回开头，[locateDetachedComposition] 在光标处找不到编码。
 * 这时看编码起点 [start]：那里的文字（[content] 从 [contentStart] 起）正好是原编码才算找到。
 */
internal fun locateFrozenCompositionAt(
    text: String?, start: Int, content: CharSequence?, contentStart: Int,
): DetachedComposition? {
    if (text.isNullOrEmpty() || start < 0 || content == null || contentStart < 0) return null
    val local = start - contentStart
    if (local < 0 || local + text.length > content.length) return null
    return if (content.subSequence(local, local + text.length).toString() == text)
        DetachedComposition(start, start + text.length) else null
}

/**
 * 宿主重启同一个输入框后找回还要接着打的编码。知道编码起点 [start] 时只认那个位置（[extracted] 读宿主文字和它的起始偏移），
 * 不再到光标处找：光标被放回开头时，开头碰巧是同样的字母会认错。不知道起点才在光标处找（[locateDetachedComposition]）。
 */
internal fun locateResumableComposition(
    text: String?, selStart: Int, selEnd: Int, before: CharSequence?, after: CharSequence?,
    start: Int, extracted: () -> Pair<CharSequence?, Int>?,
): DetachedComposition? = if (start >= 0) {
    if (text.isNullOrEmpty()) null
    else extracted()?.let { (content, contentStart) -> locateFrozenCompositionAt(text, start, content, contentStart) }
} else locateDetachedComposition(text, selStart, selEnd, before, after)

/**
 * 宿主报来的组合区还在（[candidatesStart] < [candidatesEnd]），选区却整个或部分落在它外面：
 * 用户把光标点到了编码以外。光标在编码两头（紧挨着）仍算在里面。
 */
internal fun inlineCaretOutsideSpan(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int): Boolean {
    if (selStart < 0 || selEnd < 0 || candidatesStart < 0 || candidatesEnd <= candidatesStart) return false
    val from = minOf(selStart, selEnd)
    val to = maxOf(selStart, selEnd)
    return from < candidatesStart || to > candidatesEnd
}
