package com.kingzcheung.xime.service

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/** 编辑器光标操作不经过 Rime，所有键盘布局使用相同的选择区语义。 */
internal class EditorCursor(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /**
     * 键盘自己的输入框（剪贴板编辑、新增固定等）按它自己的排版给出的视觉行位置，都是绝对偏移。
     * 这些框在没有窗口焦点的键盘窗口/悬浮窗里，发方向键会被系统直接丢掉（用户 11-08：剪贴板编辑栏上下键没反应），
     * 所以上下、行首行尾都按排版算好位置再设选区。排版还没出来时返回 null，退回按文本换行。
     */
    interface VisualLines {
        fun verticalTarget(offset: Int, direction: Int): Int?
        fun lineBoundary(offset: Int, toEnd: Boolean): Int?
    }

    private companion object {
        const val PENDING_TIMEOUT_MS = 1_000L
        /** 拖动中停顿超过这么久，下一格重新读一次输入框。 */
        const val DRAG_TEXT_TTL_MS = 1_500L
        const val DRAG_SELECTIONS_MAX = 4_096
    }
    private var connection: InputConnection? = null
    private var anchor: Int? = null
    private var active: Int? = null
    private var reportedStart: Int? = null
    private var reportedEnd: Int? = null
    // 活动端在锚点左边时交给宿主的行首/行尾/上下：宿主多半不收反向选区，Shift 会动错一端。
    // 改为先折叠到活动端、发不带 Shift 的键，等宿主报来新光标位置后再接回锚点。
    private var pendingAnchor: Int? = null
    private var pendingFrom: Int = 0
    private var pendingAt: Long = 0
    // 拖动移光标（[moveWithFeedback]）时读一次输入框文字，之后每格按这份文字算位置、只发 setSelection。
    // 原来每格都向宿主要一次全文（阻塞等宿主主线程），B 站放视频时一次约 130 ms，滑几格就落后半秒（用户 10-31）。
    // 拖动中文字不变；宿主报来的选区不是自己设的（打了字、宿主自己改了）、换了输入框、做了别的编辑操作或停顿过久就丢掉重读。
    private var dragging = false
    private var dragText: ExtractedText? = null
    private var dragTextAt = 0L
    private val dragSelections = HashSet<Long>()

    fun observeSelection(ic: InputConnection, start: Int, end: Int) {
        bind(ic)
        reportedStart = start.takeIf { it >= 0 }
        reportedEnd = end.takeIf { it >= 0 }
        if (dragText != null && selectionKey(minOf(start, end), maxOf(start, end)) !in dragSelections) dropDragText()
        val anchorBack = pendingAnchor ?: return
        if (System.currentTimeMillis() - pendingAt > PENDING_TIMEOUT_MS) { pendingAnchor = null; return }
        // 折叠本身的回报（还在原处）不算；宿主移动后的折叠光标才是新的活动端。
        if (start != end || start < 0 || start == pendingFrom) return
        pendingAnchor = null
        if (runCatching { ic.setSelection(minOf(start, anchorBack), maxOf(start, anchorBack)) }.getOrDefault(false)) {
            anchor = anchorBack; active = start
            reportedStart = minOf(start, anchorBack); reportedEnd = maxOf(start, anchorBack)
        }
    }

    fun beginSelection(ic: InputConnection) {
        bind(ic)
        resetSelection()
        snapshot(ic)?.let { syncSelection(it) }
    }

    fun resetSelection() { anchor = null; active = null; pendingAnchor = null }

    /** The host may sort selection endpoints; the magnifier follows the end being moved. */
    fun selectionActiveEnd(ic: InputConnection): Int? = active.takeIf { connection === ic && anchor != null }

    /** 取消选择保留活动端的位置，反向选择不能误折叠到较大的端点。 */
    fun endSelection(ic: InputConnection) {
        bind(ic)
        val extracted = snapshot(ic)
        extracted?.let { syncSelection(it) }
        val collapsed = active?.let { runCatching { ic.setSelection(it, it) }.getOrDefault(false) } == true
        if (!collapsed) {
            // WebView 等宿主可能没有 ExtractedText 或拒绝 setSelection。
            // 只有确认仍有选区时才交给宿主折叠，避免普通光标被多移动一格。
            val hasSelection = (extracted != null && extracted.selectionStart != extracted.selectionEnd) ||
                runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)
            if (hasSelection) {
                val key = if (active != null && anchor != null && active!! < anchor!!)
                    KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                runCatching { sendDirection(ic, key) }
            }
        }
        resetSelection()
    }

    // Some web editors omit ExtractedText but expose absolute selection updates and
    // surrounding text. Use those instead of sending DPAD focus-navigation events.
    private fun surroundingSnapshot(ic: InputConnection): ExtractedText? {
        ic.readSurroundingWindow(4096, 4096, 0)?.takeIf {
            it.startOffset >= 0 && it.selectionStart in 0..it.text.length && it.selectionEnd in 0..it.text.length
        }?.let { return it }
        val start = reportedStart ?: return null
        val end = reportedEnd ?: return null
        return runCatching {
            val before = ic.getTextBeforeCursor(4096, 0) ?: return null
            val after = ic.getTextAfterCursor(4096, 0) ?: return null
            val selected = if (start == end) "" else ic.getSelectedText(0)?.toString() ?: return null
            if (selected.length != abs(end - start) || before.length > minOf(start, end)) return null
            ExtractedText().apply {
                text = before.toString() + selected + after
                startOffset = minOf(start, end) - before.length
                selectionStart = start - startOffset
                selectionEnd = end - startOffset
            }
        }.getOrNull()
    }
    private fun snapshot(ic: InputConnection): ExtractedText? {
        if (dragging) return dragSnapshot(ic)
        dropDragText()
        return freshSnapshot(ic)
    }

    private fun freshSnapshot(ic: InputConnection): ExtractedText? =
        runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()?.takeIf {
            val length = it.text?.length ?: return@takeIf false
            it.startOffset >= 0 && it.selectionStart in 0..length && it.selectionEnd in 0..length
        } ?: surroundingSnapshot(ic)

    private fun dragSnapshot(ic: InputConnection): ExtractedText? {
        val now = clock()
        dragText?.takeIf { now - dragTextAt <= DRAG_TEXT_TTL_MS }?.let { dragTextAt = now; return it }
        dropDragText()
        val fresh = freshSnapshot(ic) ?: return null
        val copy = ExtractedText().apply {
            text = fresh.text.toString()
            startOffset = fresh.startOffset
            selectionStart = fresh.selectionStart
            selectionEnd = fresh.selectionEnd
        }
        dragText = copy
        dragTextAt = now
        val start = copy.startOffset + minOf(copy.selectionStart, copy.selectionEnd)
        val end = copy.startOffset + maxOf(copy.selectionStart, copy.selectionEnd)
        dragSelections += selectionKey(start, end)
        return copy
    }

    /** 自己刚设的选区记进缓存；超出缓存的文字范围就丢掉，下一格重读。 */
    private fun recordDragSelection(start: Int, end: Int) {
        val cached = dragText ?: return
        val length = cached.text?.length ?: return dropDragText()
        val localStart = start - cached.startOffset
        val localEnd = end - cached.startOffset
        if (localStart !in 0..length || localEnd !in 0..length || dragSelections.size >= DRAG_SELECTIONS_MAX) return dropDragText()
        cached.selectionStart = localStart
        cached.selectionEnd = localEnd
        dragSelections += selectionKey(start, end)
    }

    private fun dropDragText() {
        dragText = null
        dragSelections.clear()
    }

    private fun selectionKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xffffffffL)

    private fun syncSelection(extracted: ExtractedText) {
        val start = extracted.startOffset + extracted.selectionStart
        val end = extracted.startOffset + extracted.selectionEnd
        // InputConnection 可将反向选区规范为 min/max，selectionEnd 不一定是活动端。
        active = when (anchor) {
            start -> end
            end -> start
            else -> { anchor = start; end }
        }
    }

    private fun setSelection(ic: InputConnection, target: Int, selecting: Boolean): Boolean {
        val start = if (selecting) anchor ?: target else target
        // 一律按小、大顺序设置：WebView 等宿主收到 start > end 会原样返回成功却不改选区，
        // 向左、向上、段首扩选因此失效。活动端由 anchor/active 记录，不依赖宿主保存方向。
        if (!runCatching { ic.setSelection(minOf(start, target), maxOf(start, target)) }.getOrDefault(false)) return false
        reportedStart = minOf(start, target)
        reportedEnd = maxOf(start, target)
        if (selecting) { anchor = start; active = target }
        recordDragSelection(minOf(start, target), maxOf(start, target))
        return true
    }

    private fun bind(ic: InputConnection) {
        if (connection !== ic) {
            connection = ic
            resetSelection()
            dropDragText()
            reportedStart = null
            reportedEnd = null
        }
    }

    /** 每一个成功的字符步长独立通知，边界和失败不产生额外触觉。 */
    fun moveWithFeedback(ic: InputConnection, steps: Int, selecting: Boolean = false, onStep: () -> Unit) {
        bind(ic)
        dragging = true
        try {
            repeat(abs(steps)) {
                if (!move(ic, steps.compareTo(0), selecting)) return
                onStep()
            }
        } finally {
            dragging = false
        }
    }

    fun move(ic: InputConnection, steps: Int, selecting: Boolean = false, allowKeyEventFallback: Boolean = true): Boolean {
        if (steps == 0) return false
        bind(ic)
        if (!selecting) resetSelection()
        val extracted = snapshot(ic)
        val text = extracted?.text
        if (text != null && extracted.selectionStart in 0..text.length && extracted.selectionEnd in 0..text.length) {
            val start = extracted.selectionStart
            val end = extracted.selectionEnd
            if (selecting) syncSelection(extracted)
            val collapsed = !selecting && start != end
            val from = if (collapsed) {
                if (steps < 0) minOf(start, end) else maxOf(start, end)
            } else if (selecting) active!! - extracted.startOffset else end
            val remaining = if (collapsed) steps - steps.compareTo(0) else steps
            val next = offsetByCharacters(text, from, remaining)
            val moved = Character.codePointCount(text, minOf(from, next), maxOf(from, next))
            // 片段边缘不一定是文档边缘：查询光标周围文本后计算绝对位置，
            // 在真实文首/文末截停，不能继续发 DPAD 让宿主把焦点移出输入框。
            val target = if (moved == abs(remaining)) extracted.startOffset + next
                else extendedHorizontalTarget(ic, extracted, from, remaining).also { dropDragText() }
            if (target != null && setSelection(ic, target, selecting))
                return target != extracted.startOffset + from || collapsed
        }
        // 下面交给宿主按方向键移动，缓存的文字和选区都不再可信；这一步也不用缓存。
        dropDragText()
        if (!allowKeyEventFallback) return false
        val wasDragging = dragging
        dragging = false
        try { return moveByKeys(ic, steps, selecting) } finally { dragging = wasDragging }
    }

    private fun moveByKeys(ic: InputConnection, steps: Int, selecting: Boolean): Boolean {
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        var moved = false
        repeat(abs(steps)) {
            val current = snapshot(ic)
            val hasSelection = current?.let { it.selectionStart != it.selectionEnd }
                ?: runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)
            val adjacent = runCatching {
                if (steps < 0) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
            }.getOrNull()
            if (!hasSelection && adjacent?.isEmpty() == true) return moved
            moved = sendDirection(ic, key, selecting) || moved
        }
        return moved
    }

    /**
     * 实体方向键先在文字里自己移光标。宿主读不出文字或不收 setSelection 时（部分输入框，外接键盘用户 11-01：
     * 方向键按了没反应，微信输入法能用），把方向键交给宿主自己处理，和别的输入法一样；
     * 读得到光标那一侧已经没有字（文首按左/上、文末按右/下）时不交，免得焦点跳出输入框。
     */
    fun movePhysical(ic: InputConnection, keyCode: Int, selecting: Boolean = false,
        byWord: Boolean = false, toBoundary: Boolean = false, lines: VisualLines? = null): Boolean {
        val backwards = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP
        val horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        val direction = if (backwards) -1 else 1
        if (!byWord && !toBoundary) {
            val moved = if (horizontal) move(ic, direction, selecting, allowKeyEventFallback = false)
                else moveVertical(ic, direction, selecting, lines)
            // 键盘自己的输入框收不到方向键，交过去只会丢掉。
            return moved || (lines == null && hostArrow(ic, keyCode, backwards, selecting))
        }
        bind(ic)
        if (!selecting) resetSelection()
        val extracted = snapshot(ic) ?: return false
        if (selecting) syncSelection(extracted)
        val position = if (selecting) active!! - extracted.startOffset else extracted.selectionEnd
        val target = if (horizontal && byWord && !toBoundary) {
            val words = java.text.BreakIterator.getWordInstance().apply { setText(extracted.text.toString()) }
            var next = if (backwards) words.preceding(position) else words.following(position)
            while (next != java.text.BreakIterator.DONE && next > 0 && next < extracted.text.length &&
                (if (backwards) extracted.text[next] else extracted.text[next - 1]).isWhitespace()) {
                next = if (backwards) words.preceding(next) else words.following(next)
            }
            if (next == java.text.BreakIterator.DONE) { if (backwards) 0 else extracted.text.length } else next
        } else paragraphBoundary(extracted.text, position, !backwards)
        return setSelection(ic, extracted.startOffset + target, selecting) && target != position
    }

    /** 自己没移动时交给宿主；光标这一侧确定没有字时不交（自己没动是因为到头了）。 */
    private fun hostArrow(ic: InputConnection, keyCode: Int, backwards: Boolean, selecting: Boolean): Boolean {
        val adjacent = runCatching {
            if (backwards) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
        }.getOrNull()
        if (adjacent?.isEmpty() == true) return false
        dropDragText()
        return sendKeyPair(ic, keyCode, selecting)
    }

    private fun extendedHorizontalTarget(ic: InputConnection, extracted: ExtractedText, from: Int, steps: Int): Int? {
        // 每码点最多两个 UTF-16 单元；周围文本从选区外端开始，需接上选区内部到活动端的片段。
        val requested = (abs(steps.toLong()) * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val surrounding = runCatching {
            if (steps < 0) ic.getTextBeforeCursor(requested, 0) else ic.getTextAfterCursor(requested, 0)
        }.getOrNull() ?: return null
        return if (steps < 0) {
            val endpoint = minOf(extracted.selectionStart, extracted.selectionEnd)
            val span = surrounding.toString() + extracted.text.subSequence(endpoint, from)
            extracted.startOffset + endpoint - surrounding.length + offsetByCharacters(span, span.length, steps)
        } else {
            val endpoint = maxOf(extracted.selectionStart, extracted.selectionEnd)
            val span = extracted.text.subSequence(from, endpoint).toString() + surrounding
            extracted.startOffset + from + offsetByCharacters(span, 0, steps)
        }
    }

    fun moveToParagraphBoundary(ic: InputConnection, toEnd: Boolean, selecting: Boolean = false) {
        bind(ic)
        if (!selecting) resetSelection()
        val extracted = snapshot(ic)
        if (extracted != null) {
            if (selecting) syncSelection(extracted)
            val position = if (selecting) active!! - extracted.startOffset else extracted.selectionEnd
            val target = paragraphBoundary(extracted.text, position, toEnd)
            val knownBoundary = if (toEnd) target < extracted.text.length else target > 0 || extracted.startOffset == 0
            val absoluteTarget = if (knownBoundary) extracted.startOffset + target
                else extendedParagraphBoundary(ic, extracted, toEnd)
            if (absoluteTarget != null && setSelection(ic, absoluteTarget, selecting)) return
        }
        // 不提供可读文本的宿主只能委托原生 Home/End；保留 Shift 扩选，不能跳到全文首尾。
        sendDirection(ic, if (toEnd) KeyEvent.KEYCODE_MOVE_END else KeyEvent.KEYCODE_MOVE_HOME, selecting)
    }

    private fun extendedParagraphBoundary(ic: InputConnection, extracted: ExtractedText, toEnd: Boolean): Int? {
        val endpoint = extracted.startOffset + if (toEnd) maxOf(extracted.selectionStart, extracted.selectionEnd)
            else minOf(extracted.selectionStart, extracted.selectionEnd)
        var requested = 256
        // 逐步查询，避免把 ExtractedText 的片段末尾或软换行当成段落末尾。
        while (requested <= 1_048_576) {
            val surrounding = runCatching {
                if (toEnd) ic.getTextAfterCursor(requested, 0) else ic.getTextBeforeCursor(requested, 0)
            }.getOrNull() ?: return null
            val index = if (toEnd) surrounding.indexOfFirst(::isParagraphSeparator)
                else surrounding.indexOfLast(::isParagraphSeparator)
            if (index >= 0) return if (toEnd) endpoint + index else endpoint - surrounding.length + index + 1
            if (surrounding.length < requested) return if (toEnd) endpoint + surrounding.length
                else (endpoint - surrounding.length).coerceAtLeast(0)
            requested *= 2
        }
        return null
    }

    /** 只修改当前编辑器选区；不能发送 DPAD_UP/DOWN，宿主可能将它解释为焦点导航。 */
    fun moveVertical(ic: InputConnection, rows: Int, selecting: Boolean = false, lines: VisualLines? = null): Boolean {
        if (rows == 0) return false
        bind(ic)
        if (!selecting) resetSelection()
        var moved = false
        repeat(abs(rows).coerceAtMost(1000)) {
            val extracted = snapshot(ic) ?: return moved
            if (selecting) syncSelection(extracted)
            val position = if (selecting) active!! - extracted.startOffset else extracted.selectionEnd
            val absolute = extracted.startOffset + position
            val target = lines?.verticalTarget(absolute, rows.compareTo(0))
                ?: (extracted.startOffset + verticalTextTarget(extracted.text, position, rows.compareTo(0)))
            if (target == absolute) return moved
            if (!setSelection(ic, target, selecting)) return moved
            moved = true
        }
        return moved
    }
    /**
     * 编辑面板的上下键按宿主排版的视觉行移动：多行输入框交给宿主处理方向键，
     * 单行或不明类型的输入框仍只在文本内移动。文首按上、文末按下不发送，避免焦点跳出输入框。
     */
    fun moveLine(ic: InputConnection, direction: Int, selecting: Boolean = false, multiLine: Boolean = false,
        lines: VisualLines? = null): Boolean {
        if (direction == 0) return false
        if (lines != null || !multiLine) return moveVertical(ic, direction, selecting, lines)
        val key = if (direction < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        return sendHostMove(ic, key, selecting, guardEdge = true)
    }

    /** 行首/行尾按视觉行，由宿主处理 Home/End；选择时保持锚点。 */
    fun moveToLineBoundary(ic: InputConnection, toEnd: Boolean, selecting: Boolean = false, lines: VisualLines? = null): Boolean {
        if (lines == null) {
            return sendHostMove(ic, if (toEnd) KeyEvent.KEYCODE_MOVE_END else KeyEvent.KEYCODE_MOVE_HOME, selecting, guardEdge = false)
        }
        bind(ic)
        if (!selecting) resetSelection()
        val extracted = snapshot(ic) ?: return false
        if (selecting) syncSelection(extracted)
        val start = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
        val end = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
        val position = if (selecting) active!! else if (toEnd) end else start
        val target = lines.lineBoundary(position, toEnd)
            ?: (extracted.startOffset + paragraphBoundary(extracted.text, position - extracted.startOffset, toEnd))
        return setSelection(ic, target, selecting)
    }

    private fun sendHostMove(ic: InputConnection, key: Int, selecting: Boolean, guardEdge: Boolean): Boolean {
        bind(ic)
        if (!selecting) resetSelection()
        val backwards = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_MOVE_HOME
        val extracted = snapshot(ic)
        if (extracted != null) {
            val start = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
            val end = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
            if (!selecting && start != end && guardEdge) {
                // 与左右键一致：有选区时先折叠到对应一端；宿主的上下键在全选时会把焦点移走。
                return setSelection(ic, if (backwards) start else end, false)
            }
            if (selecting) syncSelection(extracted)
            val position = if (selecting) active!! else if (backwards) start else end
            if (guardEdge && atDocumentEdge(ic, position, start, end, backwards)) return false
            if (selecting && active!! < anchor!!) return moveLeftActiveEnd(ic, key, extracted)
        } else if (guardEdge) {
            val adjacent = runCatching {
                if (backwards) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
            }.getOrNull()
            if (adjacent.isNullOrEmpty()) return false
        }
        return sendKeyPair(ic, key, selecting)
    }

    /** 活动端在锚点左边：折叠到活动端，发不带 Shift 的键，宿主报来新位置时由 [observeSelection] 接回锚点。 */
    private fun moveLeftActiveEnd(ic: InputConnection, key: Int, extracted: ExtractedText): Boolean {
        val from = active!!
        val local = from - extracted.startOffset
        val text = extracted.text
        // 已在本行（按文本换行）的首/尾时宿主不会动，也就等不到新位置；直接不动，保留选区。
        if (key == KeyEvent.KEYCODE_MOVE_HOME && (local <= 0 || isParagraphSeparator(text[local - 1]))) return false
        if (key == KeyEvent.KEYCODE_MOVE_END && local < text.length && isParagraphSeparator(text[local])) return false
        val anchorBack = anchor!!
        if (!runCatching { ic.setSelection(from, from) }.getOrDefault(false)) return false
        pendingAnchor = anchorBack; pendingFrom = from; pendingAt = System.currentTimeMillis()
        anchor = anchorBack; active = from
        return sendKeyPair(ic, key, false)
    }

    private fun atDocumentEdge(ic: InputConnection, position: Int, start: Int, end: Int, backwards: Boolean): Boolean {
        if (backwards) return position <= 0
        if (position != end) return false
        return runCatching { ic.getTextAfterCursor(1, 0) }.getOrNull().isNullOrEmpty()
    }

    /** 选择时先按住 Shift：EditText 只看 Shift 键本身设置的状态，不看方向键事件的 meta。 */
    private fun sendKeyPair(ic: InputConnection, key: Int, selecting: Boolean): Boolean {
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        if (selecting) ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
        val downAccepted = ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, meta))
        val upAccepted = ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, key, 0, meta))
        if (selecting) ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        return downAccepted || upAccepted
    }

    fun sendDirection(ic: InputConnection, key: Int, selecting: Boolean = false, additionalMeta: Int = 0): Boolean {
        bind(ic)
        if (!selecting) resetSelection()
        else snapshot(ic)?.let {
            syncSelection(it)
            // 恢复方向后再交给宿主处理上下行，避免规范化选区改变 Shift 的活动端。
            runCatching { ic.setSelection(anchor!!, active!!) }
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            return moveVertical(ic, if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, selecting)
        }
        val meta = additionalMeta or if (selecting) KeyEvent.META_SHIFT_ON else 0
        val downAccepted = ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, meta))
        val upAccepted = ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, key, 0, meta))
        return downAccepted || upAccepted
    }
}

private fun isParagraphSeparator(char: Char): Boolean =
    char == '\n' || char == '\r' || char == '\u2028' || char == '\u2029'

/** 段落按实际换行分隔，CRLF 是一个边界；视觉软换行不产生新段落。 */
internal fun paragraphBoundary(text: CharSequence, position: Int, toEnd: Boolean): Int {
    var cursor = position.coerceIn(0, text.length)
    if (cursor > 0 && cursor < text.length && text[cursor - 1] == '\r' && text[cursor] == '\n') cursor--
    if (toEnd) {
        while (cursor < text.length && !isParagraphSeparator(text[cursor])) cursor++
    } else {
        while (cursor > 0 && !isParagraphSeparator(text[cursor - 1])) cursor--
    }
    return cursor
}

/** UTF-16 下每步越过完整码点，避免把光标放进 emoji 的代理对中间。 */
internal fun offsetByCharacters(text: CharSequence, position: Int, steps: Int): Int {
    var cursor = position.coerceIn(0, text.length)
    repeat(abs(steps)) {
        if (steps < 0 && cursor > 0) {
            cursor -= Character.charCount(Character.codePointBefore(text, cursor))
        } else if (steps > 0 && cursor < text.length) {
            cursor += Character.charCount(Character.codePointAt(text, cursor))
        }
    }
    return cursor
}

/** 保持码点列号，跨显式换行；未暴露视觉折行时停在当前段首/尾。 */
internal fun verticalTextTarget(text: CharSequence, position: Int, direction: Int): Int {
    val from = position.coerceIn(0, text.length)
    val start = paragraphBoundary(text, from, false)
    val end = paragraphBoundary(text, from, true)
    val column = Character.codePointCount(text, start, from)
    if (direction < 0) {
        if (start == 0) return 0
        var previousEnd = start - 1
        if (previousEnd > 0 && text[previousEnd] == '\n' && text[previousEnd - 1] == '\r') previousEnd--
        val previousStart = paragraphBoundary(text, previousEnd, false)
        return offsetByCharacters(text.subSequence(previousStart, previousEnd), 0, column) + previousStart
    }
    if (end == text.length) return text.length
    var nextStart = end + 1
    if (text[end] == '\r' && nextStart < text.length && text[nextStart] == '\n') nextStart++
    val nextEnd = paragraphBoundary(text, nextStart, true)
    return offsetByCharacters(text.subSequence(nextStart, nextEnd), 0, column) + nextStart
}
