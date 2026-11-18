package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An isolated candidate lookup; the live composition changes only on confirmation. */
data class SentenceEditSession(
        val inputSessionId: Long,
        val schema: String,
        val snapshot: RimeEngine.SentenceEditSnapshot,
        val start: Int,
        val end: Int,
    ) {
        val originalCode: String get() = snapshot.input.substring(snapshot.boundaries[start], snapshot.boundaries[end])
        val originalWord: String get() = snapshot.sentence.substring(start, end)
        fun sentence(word: String) = snapshot.sentence.take(start) + word + snapshot.sentence.drop(end)
    }

/** Original character where the remaining unchanged code begins, or null after unrelated edits. */
internal fun SentenceEditSession.originalRemainingStart(code: String, confirmedCode: String): Int? {
    fun keys(value: String) = value.filterNot { it == '\'' || it.isWhitespace() }
    val input = snapshot.input
    val bounds = snapshot.boundaries
    val first = bounds[start]
    return (start until end).firstOrNull { index ->
        keys(input.substring(first, bounds[index])) == keys(confirmedCode) &&
            keys(input.substring(bounds[index], bounds[end])) == keys(code)
    }
}

/** Map sentence characters to the currently displayed spelling, only when the mapping is reliable. */
internal fun SentenceEditSession.displaySelection(text: String): IntRange? {
    val syllables = Regex("[A-Za-züÜ;]+").findAll(text).toList()
    val range = if (syllables.size == snapshot.sentence.length) {
        syllables[start].range.first until (syllables[end - 1].range.last + 1)
    } else {
        fun keys(s: String) = s.filterNot { it == '\'' || it.isWhitespace() }.lowercase()
        if (keys(text) != keys(snapshot.input)) return null
        val display = text.replace(' ', '\'')
        com.kingzcheung.xime.rime.mapCodeOffset(snapshot.input, display, snapshot.boundaries[start]) until
            com.kingzcheung.xime.rime.mapCodeOffset(snapshot.input, display, snapshot.boundaries[end])
    }
    return range.takeIf { it.first >= 0 && it.last < text.length && !it.isEmpty() }
}

internal class ImeSentenceEditor(private val service: XimeInputMethodService) {
    private var previewOwner: SentenceEditSession? = null
    private var previewConnection: android.view.inputmethod.InputConnection? = null
    private var previewBase: String? = null
    private var previewWritten: String? = null
    private var previewCaret = 0
    private var dragConnection: android.view.inputmethod.InputConnection? = null
    private var dragText: String? = null
    private var dragCaret = 0
    private var dragOwner: SentenceEditSession? = null

    private fun current(owner: SentenceEditSession): Boolean = service.uiState.value.let {
        it.inputSessionId == owner.inputSessionId && it.currentSchemaId == owner.schema && !it.isAsciiMode
    }

    /** Highlight existing composing text without changing its characters or final cursor position. */
    fun dragPreview(owner: SentenceEditSession?) {
        val ic = service.currentInputConnection
        if (owner == null) {
            val text = dragText
            if (ic != null && ic === dragConnection && text != null && service.inlinePinyinText() == text &&
                service.inlinePinyinCaret() == dragCaret && dragOwner?.let(::current) == true &&
                runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) {
                ic.beginBatchEdit()
                try {
                    if (ic.setComposingText(text, 1)) {
                        service.markInputBoxComposing(text)
                        service.placeInlinePinyinCaret(text, dragCaret)
                    }
                } finally { ic.endBatchEdit() }
            }
            dragConnection = null; dragText = null; dragOwner = null
            return
        }
        if (!current(owner) || service.uiState.value.inputTarget.isInternal ||
            TerminalEditor.inputTextLocation(service) != com.kingzcheung.xime.settings.SettingsPreferences.INPUT_TEXT_INPUT_BOX || ic == null) return
        if (dragConnection == null) {
            dragText = service.inlinePinyinText() ?: return
            dragCaret = service.inlinePinyinCaret()
            dragConnection = ic
        }
        val text = dragText ?: return
        if (ic !== dragConnection || service.inlinePinyinText() != text || service.inlinePinyinCaret() != dragCaret ||
            runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
        val range = owner.displaySelection(text) ?: return
        val styled = android.text.SpannableString(text).apply {
            setSpan(android.text.style.BackgroundColorSpan(0x663399ff), range.first, range.last + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ic.beginBatchEdit()
        try {
            if (ic.setComposingText(styled, 1)) {
                service.markInputBoxComposing(text)
                if (!service.placeInlinePinyinCaret(text, dragCaret)) dragCaret = text.length
                dragOwner = owner
            }
        } finally { ic.endBatchEdit() }
    }

    /** Only replace our own composing span; never touch surrounding host text. */
    fun preview(owner: SentenceEditSession, code: String) {
        if (!current(owner) || service.uiState.value.inputTarget.isInternal ||
            TerminalEditor.inputTextLocation(service) != com.kingzcheung.xime.settings.SettingsPreferences.INPUT_TEXT_INPUT_BOX) return
        val ic = service.currentInputConnection ?: return
        if (previewOwner !== owner) {
            val base = service.inlinePinyinText() ?: return
            fun keys(s: String) = s.filterNot { it == '\'' || it.isWhitespace() }.lowercase()
            val syllables = Regex("[A-Za-züÜ;]+").findAll(base).toList()
            if (keys(base) != keys(owner.snapshot.input) && syllables.size != owner.snapshot.sentence.length) return
            previewOwner = owner; previewConnection = ic; previewBase = base; previewWritten = base
            previewCaret = service.inlinePinyinCaret()
        }
        if (previewConnection !== ic || service.inlinePinyinText() != previewWritten ||
            service.inlinePinyinCaret() != previewCaret || runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
        val base = previewBase ?: return
        val from = owner.snapshot.boundaries[owner.start]
        val to = owner.snapshot.boundaries[owner.end]
        val visible = base.replace(' ', '\'')
        val syllables = Regex("[A-Za-züÜ;]+").findAll(base).toList()
        val syllableDisplay = syllables.size == owner.snapshot.sentence.length
        val start = if (syllableDisplay) syllables[owner.start].range.first
            else com.kingzcheung.xime.rime.mapCodeOffset(owner.snapshot.input, visible, from)
        val end = if (syllableDisplay) syllables[owner.end - 1].range.last + 1
            else com.kingzcheung.xime.rime.mapCodeOffset(owner.snapshot.input, visible, to)
        if (start !in 0..end || end > base.length) return
        val before = base.take(start)
        val after = base.drop(end)
        val text = if (code.isNotEmpty()) before + code + after else {
            val left = before.trimEnd(' ', '\'')
            val right = after.trimStart(' ', '\'')
            val separator = before.lastOrNull()?.takeIf { it == ' ' || it == '\'' }
                ?: after.firstOrNull()?.takeIf { it == ' ' || it == '\'' }
            left + (if (left.isNotEmpty() && right.isNotEmpty()) separator?.toString().orEmpty() else "") + right
        }
        val caret = if (code.isNotEmpty()) start + code.length else before.trimEnd(' ', '\'').length.coerceAtMost(text.length)
        if (code.isEmpty() && text == previewWritten && caret == previewCaret) return
        val styled = android.text.SpannableString(text)
        if (code.isNotEmpty()) styled.setSpan(android.text.style.BackgroundColorSpan(0x663399ff), start,
            start + code.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ic.beginBatchEdit()
        try {
            if (runCatching { ic.setComposingText(styled, 1) }.getOrDefault(false)) {
                previewWritten = text
                service.markInputBoxComposing(text)
                previewCaret = if (service.placeInlinePinyinCaret(text, caret)) caret else text.length
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    fun endPreview(owner: SentenceEditSession) {
        val ic = service.currentInputConnection
        if (previewOwner === owner && previewConnection === ic && ic != null && current(owner) &&
            service.inlinePinyinText() == previewWritten && service.inlinePinyinCaret() == previewCaret &&
            runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) {
            previewWritten?.let { service.placeInlinePinyinCaret(it, it.length) }
            service.sessionController.refreshInputBoxComposition()
        }
        if (previewOwner === owner) {
            previewOwner = null; previewConnection = null; previewBase = null; previewWritten = null; previewCaret = 0
        }
    }

    fun open(start: Int, end: Int, revision: Long, reply: (SentenceEditSession?) -> Unit) {
        val state = service.uiState.value
        if (state.isAsciiMode || !com.kingzcheung.xime.ui.keyboard.usesSentenceDragEdit(state.inputProfile)) { reply(null); return }
        service.keyRouter.postRimeJob("sentence_edit_open") {
            val snapshot = service.rimeEngine.sentenceEditSnapshot(revision)
            val owner = snapshot?.takeIf { start >= 0 && start < end && end < it.boundaries.size &&
                it.sentence == service.candidateState.value.candidates.firstOrNull() }
                ?.let { SentenceEditSession(state.inputSessionId, state.currentSchemaId, it, start, end) }
            withContext(Dispatchers.Main) { reply(owner?.takeIf(::current)) }
        }
    }

    fun candidates(owner: SentenceEditSession, code: String, selected: List<com.kingzcheung.xime.rime.PinyinSyllableOption>,
                   confirmedCode: String, reply: (RimeEngine.SentenceEditCandidates) -> Unit) {
        service.keyRouter.postRimeJob("sentence_edit_lookup") {
            val originalStart = owner.originalRemainingStart(code, confirmedCode)
            val result = if (current(owner)) service.rimeEngine.sentenceEditCandidates(owner.snapshot, code, selected,
                originalStart ?: -1, if (originalStart != null) owner.end else -1)
                else RimeEngine.SentenceEditCandidates(emptyList(), emptyList())
            withContext(Dispatchers.Main) { if (current(owner)) reply(result) }
        }
    }

    fun apply(owner: SentenceEditSession, segments: List<RimeEngine.SentenceEditCandidate>, commit: Boolean, reply: (Boolean) -> Unit) {
        service.keyRouter.postRimeJob("sentence_edit_apply") {
            val result = if (current(owner)) service.rimeEngine.applySentenceEdit(owner.snapshot,
                owner.start, owner.end, segments) else null
            if (result != null) withContext(Dispatchers.Main) {
                if (current(owner)) service.sessionController.updateUIWithResult(result)
            }
            if (result != null && commit && result.candidates.isNotEmpty() && current(owner)) service.keyRouter.selectCandidateAsync(0)
            withContext(Dispatchers.Main) {
                reply(result != null)
            }
        }
    }
}
