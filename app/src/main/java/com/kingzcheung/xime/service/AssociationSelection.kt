package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection

/** Typed letters are already in the editor. Only corrections such as im → I'm need a replacement. */
internal fun InputConnection.commitEnglishCompletion(prefix: String, completion: String): Boolean {
    if (prefix.isEmpty() || completion.isEmpty()) return false
    if (!completion.startsWith(prefix)) return commitVerifiedCandidate(prefix, completion)
    val host = (this as? TrackedInputConnection)?.base ?: this
    return try {
        val window = host.readSurroundingWindow(prefix.length, 0, 0)
        val before = if (window != null) {
            val end = window.selectionEnd
            if (window.selectionStart != end || end !in prefix.length..window.text.length) return false
            window.text.subSequence(end - prefix.length, end)
        } else {
            if (!host.getSelectedText(0).isNullOrEmpty()) return false
            host.getTextBeforeCursor(prefix.length, 0)
        }
        if (before?.toString() != prefix) return false
        val rest = completion.substring(prefix.length)
        rest.isEmpty() || commitText(rest, 1)
    } catch (_: RuntimeException) {
        false // The host may close the connection when its comment editor loses focus.
    }
}

/**
 * No candidate state is changed until the editor accepts the selected text.
 * [suffix] follows an English completion (the auto space); it is never added to Chinese associations.
 */
internal fun commitAssociationSelection(
    state: CandidateState,
    index: Int,
    commit: (String) -> Boolean,
    replace: (String, String) -> Boolean,
    suffix: String = "",
): Boolean {
    val pending = state.pendingEnglishText
    val hasOriginal = state.showsTypedEnglishWord
    val word = associationWordAt(state, index) ?: return false
    return when {
        pending.isEmpty() -> commit(word)
        // The original word is already in the editor; only the suffix is new.
        hasOriginal && index == 0 -> suffix.isEmpty() || replace(pending, pending + suffix)
        !state.englishReplaceSupported -> false
        else -> replace(pending, word + suffix) // Stale cursor context must never append the whole word.
    }
}
