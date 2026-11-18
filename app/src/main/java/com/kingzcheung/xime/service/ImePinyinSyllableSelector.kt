package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.PinyinSyllableChoices
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.LayoutKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shares the input FIFO. A letter choice edits the active composition without committing it. */
internal class ImePinyinSyllableSelector(private val service: XimeInputMethodService) {
    private fun eligible(state: InputUIState): Boolean = !state.isAsciiMode &&
        state.inputProfile.language == InputLanguage.CHINESE &&
        state.inputProfile.scheme in setOf(InputScheme.PINYIN, InputScheme.DOUBLE_PINYIN) &&
        state.inputProfile.layout.kind != LayoutKind.T9

    fun request(revision: Long, reply: (PinyinSyllableChoices?) -> Unit) {
        val owner = service.uiState.value
        // 打了辅助码（` 后面是形码，SpellingPatches.AuxCode）：编码里不全是拼音，不给改读音。
        if (!eligible(owner) || com.kingzcheung.xime.settings.SpellingPatches.AUX_CODE_TRIGGER in
            service.candidateState.value.inputText) { reply(null); return }
        val layout = owner.inputProfile.layout
        val isCurrent = { current(owner.inputSessionId, owner.currentSchemaId, layout) }
        service.keyRouter.postRimeJob {
            if (!isCurrent()) return@postRimeJob
            val snapshot = service.rimeEngine.readPinyinSyllableChoices(revision, layout == InputLayout.MERGED14, isCurrent)
                ?.copy(inputSessionId = owner.inputSessionId)
            withContext(Dispatchers.Main) {
                if (isCurrent() && service.candidateState.value.engineRevision == revision) reply(snapshot)
            }
        }
    }

    fun select(snapshot: PinyinSyllableChoices, index: Int, reply: (PinyinSyllableChoices?) -> Unit) {
        val layout = service.uiState.value.inputProfile.layout
        val isCurrent = { current(snapshot.inputSessionId, snapshot.schemaId, layout) }
        if (!isCurrent() || (snapshot.letterGroup != null && layout != InputLayout.MERGED14)) { reply(null); return }
        service.keyRouter.postRimeJob {
            if (!isCurrent()) return@postRimeJob
            val result = service.rimeEngine.selectPinyinSyllable(snapshot, index, isCurrent)
            val next = result?.let {
                service.rimeEngine.readPinyinSyllableChoices(it.engineRevision, layout == InputLayout.MERGED14, isCurrent)
            }?.copy(inputSessionId = snapshot.inputSessionId)
            withContext(Dispatchers.Main) {
                if (!isCurrent()) return@withContext
                if (result != null) service.sessionController.updateUIWithResult(result,
                    updateInputBoxComposition = result.inputText != snapshot.inputText)
                reply(next)
            }
        }
    }

    /** The rail's delete key takes back the last chosen syllable first, as on the nine-key rail. */
    fun undo(snapshot: PinyinSyllableChoices, notConsumed: () -> Unit, reply: (PinyinSyllableChoices?) -> Unit) {
        val layout = service.uiState.value.inputProfile.layout
        val isCurrent = { current(snapshot.inputSessionId, snapshot.schemaId, layout) }
        if (!isCurrent() || snapshot.chosen <= 0) { notConsumed(); return }
        service.keyRouter.postRimeJob {
            if (!isCurrent()) return@postRimeJob
            val result = service.rimeEngine.undoPinyinSyllable(snapshot, isCurrent)
            val next = result?.let {
                service.rimeEngine.readPinyinSyllableChoices(it.engineRevision, layout == InputLayout.MERGED14, isCurrent)
            }?.copy(inputSessionId = snapshot.inputSessionId)
            withContext(Dispatchers.Main) {
                if (!isCurrent()) return@withContext
                if (result == null) { notConsumed(); return@withContext }
                service.sessionController.updateUIWithResult(result, updateInputBoxComposition = false)
                reply(next)
            }
        }
    }

    private fun current(session: Long, schema: String, layout: InputLayout): Boolean = service.uiState.value.let {
        eligible(it) && it.inputSessionId == session && it.currentSchemaId == schema && it.inputProfile.layout == layout
    }
}
