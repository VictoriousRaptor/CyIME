package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection

/** Routes transient glide text without changing the engine's candidate state. */
internal class GlidePreviewRouter(
    private val session: () -> Long,
    private val useInputBox: () -> Boolean,
    private val input: (String, Long) -> Unit,
    private val clearInput: () -> Unit,
    private val candidate: (String) -> Unit,
) {
    fun show(text: String, expectedSession: Long) {
        if (session() != expectedSession) return
        if (useInputBox()) {
            candidate("")
            input(text, expectedSession)
        } else {
            clearInput()
            candidate(text.trim())
        }
    }

    fun end() { candidate(""); clearInput() }
}

/** Temporary composing text owned by one glide and one editor connection. */
internal class GlideInputPreview(
    private val connection: () -> InputConnection?,
    private val session: () -> Long,
    private val baseline: () -> String?,
    private val canStart: () -> Boolean,
    private val mark: (String) -> Unit,
    private val owns: (String) -> Boolean,
    private val clear: () -> Unit,
) {
    private var owner: InputConnection? = null
    private var ownerSession = 0L
    private var original: String? = null
    private var written: String? = null

    fun show(text: String, expectedSession: Long) = show(text, expectedSession, complete = false)
    fun showComplete(text: String, expectedSession: Long) = show(text, expectedSession, complete = true)

    private fun show(text: String, expectedSession: Long, complete: Boolean) {
        if (session() != expectedSession) return
        if (text.isEmpty()) { end(); return }
        if (owner == null) {
            if (!canStart()) return
            val ic = connection() ?: return
            if (runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
            owner = ic
            ownerSession = session()
            original = baseline()
        }
        val ic = owner ?: return
        if (connection() !== ic || session() != ownerSession || written?.let { !owns(it) } == true ||
            runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
        val prefix = original.orEmpty()
        val separator = if (prefix.isNotEmpty() && !prefix.last().isWhitespace() && !text.first().isWhitespace()) " " else ""
        val preview = if (complete) text else prefix + separator + text
        if (preview == written) return
        if (runCatching { ic.setComposingText(preview, 1) }.getOrDefault(false)) {
            written = preview
            mark(preview)
        }
    }

    fun end() {
        val ic = owner
        val text = written
        if (ic != null && text != null && connection() === ic && session() == ownerSession && owns(text) &&
            runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) {
            val before = original
            if (before != null && runCatching { ic.setComposingText(before, 1) }.getOrDefault(false)) mark(before)
            else if (before == null) clear()
        }
        owner = null; original = null; written = null
    }

    fun release() { owner = null; original = null; written = null }
}
