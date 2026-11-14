package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.PinyinEditBuffer
import com.kingzcheung.xime.rime.editOffset
import com.kingzcheung.xime.rime.PinyinEditSession
import com.kingzcheung.xime.rime.PinyinEditDisplay
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No InputConnection writes: only the unconfirmed Rime code can be edited. */
internal class ImePreeditEditor(private val service: XimeInputMethodService) {
    private val multiTap = com.kingzcheung.xime.rime.PinyinMultiTap()
    private var liveOwner: PinyinEditSession? = null
    private var liveSession: PinyinEditSession? = null

    private fun enqueue(schema: String, block: suspend () -> Unit) {
        val t9Queue = if (isT9Schema(schema)) service.keyboardCallbacks?.onT9RunLiteralInput else null
        if (t9Queue != null) t9Queue(block) else service.keyRouter.postRimeJob { block() }
    }

    fun open(reply: (PinyinEditSession?) -> Unit) {
        val state = service.uiState.value
        if (state.isAsciiMode || !PinyinEditBuffer.supports(state.currentSchemaId)) { reply(null); return }
        enqueue(state.currentSchemaId) {
            // Drain a preceding nine-key UI snapshot before reading its protected partial segments.
            withContext(Dispatchers.Main) { Unit }
            if (service.uiState.value.inputSessionId != state.inputSessionId ||
                service.uiState.value.currentSchemaId != state.currentSchemaId) return@enqueue
            val engine = service.rimeEngine
            val snapshot = engine.readPinyinEditSnapshot()
            val input = snapshot.getOrNull(0).orEmpty()
            val confirmed = snapshot.getOrNull(2)?.toIntOrNull()?.coerceIn(0, input.length) ?: 0
            val t9 = isT9Schema(state.currentSchemaId)
            val rawText = if (t9) snapshot.getOrNull(4).orEmpty() else input.drop(confirmed)
            val normalized = PinyinEditBuffer.normalized(rawText, PinyinEditBuffer.keepsCase(state.currentSchemaId))
            val unsupportedCode = rawText.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' && !it.isWhitespace() && it != 'ü' }
            val prefix = if (t9) service.t9PartialSegments.joinToString("") { it.text }
                else snapshot.getOrNull(3).orEmpty()
            val display = PinyinEditDisplay(normalized, snapshot.getOrNull(4).orEmpty().removePrefix(prefix).trim(), t9, isMerged14 = com.kingzcheung.xime.settings.InputProfiles.describe(state.currentSchemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14, groups = com.kingzcheung.xime.settings.CustomKeyboardLayouts.forSchema(state.currentSchemaId)?.rows?.flatten() ?: if (com.kingzcheung.xime.settings.InputProfiles.describe(state.currentSchemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14) com.kingzcheung.xime.rime.merged14Groups else emptyList())
            val text = display.text
            // Keep the visible separators in the editor buffer. Merely opening/moving
            // does not rewrite Rime or lock a nine-key translation; a real edit does.
            val session = if (input.isEmpty() || text.isEmpty() || unsupportedCode) null
            else PinyinEditSession(state.inputSessionId, state.currentSchemaId, input,
                if (t9) "" else input.take(confirmed), prefix, text,
                if (t9) com.kingzcheung.xime.rime.offsetAfterCodeKeys(text, com.kingzcheung.xime.rime.codeKeysBefore(input, snapshot.getOrNull(1)?.toIntOrNull() ?: input.length)) else display.displayOffset(((snapshot.getOrNull(1)?.toIntOrNull() ?: input.length) - confirmed).coerceIn(0, normalized.length)), t9, if (t9) engine.t9GetRemainingDigits() else "", service.uiState.value.t9RightCandidateSelectedCount)
            withContext(Dispatchers.Main) {
                if (service.uiState.value.inputSessionId == state.inputSessionId &&
                    service.uiState.value.currentSchemaId == state.currentSchemaId) {
                    if (unsupportedCode) android.widget.Toast.makeText(service, "请先选择左侧拼音，再编辑编码", android.widget.Toast.LENGTH_SHORT).show()
                    multiTap.reset()
                    liveOwner = session
                    liveSession = session
                    reply(session)
                }
            }
        }
    }

    /** Reuse the real keyboard. All edits share its FIFO and update composition immediately. */
    fun edit(owner: PinyinEditSession, key: String?, selection: Int?, replacement: String?, reply: (PinyinEditSession?) -> Unit) {
        val eventTime = android.os.SystemClock.uptimeMillis()
        enqueue(owner.schemaId) {
            val session = liveSession
            if (liveOwner !== owner || !owner.isActive() || session == null || !isOwnerCurrent(session)) {
                withContext(Dispatchers.Main) { reply(null) }; return@enqueue
            }
            var text = session.text
            var caret = (selection ?: session.caret).coerceIn(0, text.length)
            // 14 键：与编辑缓冲逐字对齐的按键（合并键大写），删改时跟着改，送进引擎的是它而不是显示的读音。
            val merged14 = !session.isT9 && com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout ==
                com.kingzcheung.xime.settings.InputLayout.MERGED14
            var keys = if (merged14) com.kingzcheung.xime.rime.merged14KeysOf(text,
                session.expectedInput.drop(session.protectedInput.length)) ?: text else null
            // 编码中间点了 14 键的合并键（qw）：插在光标处，仍是合并键，不关掉编辑。
            val mergedTap = merged14 && key != null && com.kingzcheung.xime.rime.MergedLetterInput.isGroup(key, true)
            val letters = when {
                com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14 && key?.length == 1 ->
                    com.kingzcheung.xime.rime.merged14Groups.firstOrNull { it.first().toString() == key }
                else -> null
            }
            // A nine-key digit typed at the code caret is the same ambiguous key as one typed
            // at the end: rebuild the editable code as plain keys, never a fixed letter.
            // Backspace likewise removes the key before the caret, as it does at the end.
            val t9Key = session.isT9 && replacement == null && selection == null &&
                (key?.length == 1 && key[0] in '2'..'9' || key == "delete" && text.take(caret).any { it != '\'' })
            if (letters == null || replacement != null || selection != null) multiTap.reset()
            if (t9Key) {
                val keys = text.filter { it != '\'' }.map(::t9Digit).joinToString("")
                val before = text.take(caret).count { it != '\'' }
                if (key == "delete") {
                    text = keys.removeRange(before - 1, before)
                    caret = before - 1
                } else {
                    text = keys.take(before) + key + keys.drop(before)
                    caret = before + 1
                }
            } else if (letters != null && replacement == null && selection == null) {
                val edited = multiTap.press(text, caret, letters, eventTime)
                keys = keys?.let { it.take(edited.second - 1) + edited.first[edited.second - 1] + it.drop(caret) }
                text = edited.first; caret = edited.second
            } else if (replacement != null) {
                text = if (session.isT9) PinyinEditBuffer.normalizedT9(replacement)
                    else PinyinEditBuffer.normalized(replacement, PinyinEditBuffer.keepsCase(session.schemaId))
                keys = keys?.let { text }
                caret = text.length
            } else if (key?.startsWith("preedit_cursor:") == true) {
                val movement = key.substringAfter(":")
                caret = when (movement) {
                    "start" -> 0
                    "end" -> text.length
                    else -> if (movement.startsWith("inline:")) {
                        val code = service.inlinePinyinText()
                            ?: service.candidateState.value.shownPreedit(service.uiState.value.inputProfile)
                        movement.removePrefix("inline:").toIntOrNull()?.let { session.editOffset(code, it) } ?: caret
                    } else (caret + (movement.toIntOrNull() ?: 0)).coerceIn(0, text.length)
                }
            } else if (key == "delete") {
                if (caret > 0) { text = text.removeRange(caret - 1, caret); keys = keys?.removeRange(caret - 1, caret); caret-- }
                else if (!session.isT9 && session.protectedInput.isEmpty()) {
                    // 光标在编码最前面：编码里没有可删的，删输入框里编码前面的字，编码留着接着打
                    // （11-09 平板：原来删除键在这里没反应）。
                    withContext(Dispatchers.Main) {
                        if (owner.isActive() && isOwnerCurrent(session)) service.deleteBeforeComposition()
                        reply(session)
                    }
                    return@enqueue
                }
            } else if (key != null) {
                // 英文单词方案的编码分大小写：Shift 打的大写字母原样插进去。
                val insertion = if (session.isT9 && key == "1") "'"
                    else if (mergedTap) com.kingzcheung.xime.rime.merged14TapDisplay(key)
                    else if (PinyinEditBuffer.keepsCase(session.schemaId)) key else key.lowercase()
                val pressed = if (mergedTap) com.kingzcheung.xime.rime.merged14TapKey(key) else insertion
                keys = keys?.let { it.take(caret) + pressed + it.drop(caret) }
                text = text.take(caret) + insertion + text.drop(caret)
                caret += insertion.length
            }
            if (text != session.text) {
                val merged = PinyinEditBuffer.mergeSeparators(text, caret)
                keys = keys?.let { PinyinEditBuffer.mergeSeparators(it, caret).first }
                text = merged.first
                caret = merged.second
                // 删光前面的音节后，开头剩下的分隔符没用：留着显示成「‘hao」（11-09 平板）。
                // 编码分大小写的方案（英文单词）里开头的撇号可能是单词本身的，不去掉。
                if (!PinyinEditBuffer.keepsCase(session.schemaId)) {
                    val trimmed = PinyinEditBuffer.dropLeadingSeparators(text, caret)
                    keys = keys?.let { PinyinEditBuffer.dropLeadingSeparators(it, caret).first }
                    text = trimmed.first
                    caret = trimmed.second
                }
            }
            val engine = service.rimeEngine
            val changed = text != session.text
            val result = engine.editPinyinAndReadState {
                // Candidates follow the caret. Merely moving it must not lock an ambiguous
                // nine-key reading, so only the engine caret moves; an edit rebuilds the keys first.
                if (session.isT9) (!changed || engine.applyT9PinyinEdit(session.expectedInput, text,
                    session.t9RemainingDigits, session.schemaId) { owner.isActive() && isOwnerCurrent(session) }) &&
                    engine.placeT9CodeCaret(com.kingzcheung.xime.rime.codeKeysBefore(text, caret),
                        if (changed) null else session.expectedInput, session.schemaId) { owner.isActive() && isOwnerCurrent(session) }
                else {
                    val engineText = if (changed) session.protectedInput + (com.kingzcheung.xime.settings.CustomKeyboardLayouts.find(session.schemaId)?.encode(text)
                        ?: keys?.takeIf { it.length == text.length } ?: text) else session.expectedInput
                    val engineCaret = if (changed) caret else PinyinEditDisplay(
                        session.expectedInput.drop(session.protectedInput.length), session.text, isMerged14 = com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14, groups = com.kingzcheung.xime.settings.CustomKeyboardLayouts.forSchema(session.schemaId)?.rows?.flatten() ?: if (com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14) com.kingzcheung.xime.rime.merged14Groups else emptyList()).rawOffset(caret)
                    // 光标在编码最前面时，候选按整段编码给：Rime 的光标放在 0 时前面没有编码，
                    // 候选是空的，空格选不了字（11-09 平板）。显示的光标仍在最前面。
                    val placedCaret = if (engineCaret == 0) engineText.length - session.protectedInput.length else engineCaret
                    engine.applyPinyinEdit(session.expectedInput, engineText,
                        session.protectedInput.length + placedCaret, session.protectedInput.length,
                        session.protectedText, session.schemaId, visibleReading = text) { owner.isActive() && isOwnerCurrent(session) }
                }
            }
            if (result == null) { withContext(Dispatchers.Main) { reply(null) }; return@enqueue }
            if (t9Key) {
                // Show the engine's reading of the plain keys, caret right after the new key.
                val display = PinyinEditDisplay(text, result.preeditText.removePrefix(session.protectedText).trim(), true)
                caret = display.displayOffset(caret)
                text = display.text
            }
            val next = session.copy(expectedInput = result.inputText, text = text, caret = caret,
                t9RemainingDigits = if (session.isT9) engine.t9GetRemainingDigits() else "")
            liveSession = next
            withContext(Dispatchers.Main) {
                if (!owner.isActive() || !isOwnerCurrent(session)) return@withContext
                if (session.isT9) service.keyboardCallbacks?.onT9RefreshAfterPreeditEdit?.invoke()
                // A caret-only move leaves the host's text and composing span intact.
                service.sessionController.updateUIWithResult(result, updateInputBoxComposition = changed)
                // A host drag has already placed the cursor. Replaying queued positions here
                // would pull the handle backwards while the user is still dragging it.
                if (key?.startsWith("preedit_cursor:inline:") != true) service.showInlineEditCaret(next)
                reply(next)
            }
        }
    }

    private fun t9Digit(c: Char): Char = when (c) {
        in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> c
    }

    private fun isOwnerCurrent(session: PinyinEditSession): Boolean = service.uiState.value.let {
        it.inputSessionId == session.inputSessionId && it.currentSchemaId == session.schemaId && !it.isAsciiMode &&
            (!session.isT9 || (it.t9RightCandidateSelectedCount == session.t9SelectionCount &&
                service.t9PartialSegments.joinToString("") { part -> part.text } == session.protectedText))
    }

}
