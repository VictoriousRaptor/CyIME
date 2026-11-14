package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.PhraseLineBreaks
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class CustomPhraseUiState(
    val entries: List<DictEntry> = emptyList(),
    val filteredEntries: List<DictEntry> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editIndex: Int = -1,
    val editWord: String = "",
    val editCode: String = "",
    val editWeight: String = "",
    /** 编辑框里的「固定在第几位」，空＝不固定。 */
    val editPlace: String = "",
    /** 固定的词（长按候选固定的、短语填了第几位的），按编码排。 */
    val pins: List<PinnedWord> = emptyList(),
)

/** 一条固定：编码（九键是数字）、词、第几位（0 排第一）。 */
data class PinnedWord(val code: String, val word: String, val place: Int)

/** 「固定在第几位」能填的范围，和引擎（candidate_pins.h kMaxPlace）一致。 */
internal const val MAX_PHRASE_PLACE = 27

/** 编辑框里的位置：1…[MAX_PHRASE_PLACE] 才算，其它（空、0、太大、不是数字）都是不固定。 */
internal fun phrasePlace(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_PHRASE_PLACE }

class CustomPhraseViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private var schemaId: String? = null

    private val _uiState = MutableStateFlow(CustomPhraseUiState())
    val uiState: StateFlow<CustomPhraseUiState> = _uiState.asStateFlow()

    fun setSchema(id: String?) {
        if (schemaId == id) return
        schemaId = id
        loadEntries()
    }

    /** The phrases every keyboard shares (custom_phrase.txt); saving applies them to all enabled schemas. */
    fun loadShared() {
        schemaId = null
        loadEntries()
    }

    private fun loadEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) {
                PersonalDictManager.loadCustomPhrases(context, schemaId)
            }
            _uiState.update {
                it.copy(
                    entries = entries,
                    filteredEntries = filterEntries(entries, ""),
                    searchQuery = "",
                    isLoading = false
                )
            }
            loadPins()
        }
    }

    /** 固定的词从引擎读（cyime_pins.txt 由引擎管，长按固定和这里改的是同一份）。读不到就空着。 */
    private suspend fun loadPins() {
        val pins = withContext(Dispatchers.IO) {
            try {
                if (!RimeConfigHelper.prepareEngine(context)) return@withContext null
                RimeEngine.getInstance().listPins().map { PinnedWord(it.code, it.text, it.place) }
                    .sortedWith(compareBy({ it.code }, { it.place }))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
        } ?: return
        _uiState.update { it.copy(pins = pins) }
    }

    private fun pinKey(code: String) = code.trim().filterNot { it == ' ' || it == '\'' }

    /** 这条短语现在固定在第几位（按原编码查）；没固定是 null。 */
    private fun placeOf(entry: DictEntry): Int? =
        _uiState.value.pins.firstOrNull { it.word == entry.word && it.code == pinKey(entry.code) }?.place?.takeIf { it > 0 }

    /**
     * 短语的「固定在第几位」：旧的那条（改了或删了）在各键盘编码下的固定先取消，新的有位置就按各键盘编码固定。
     * 没有填过位置的短语不碰引擎。
     */
    private suspend fun applyPlace(old: DictEntry?, new: DictEntry?, place: Int?) {
        val oldPinned = old != null && placeOf(old) != null
        if (!oldPinned && (new == null || place == null)) return
        withContext(Dispatchers.IO) {
            try {
                if (!RimeConfigHelper.prepareEngine(context)) return@withContext
                val engine = RimeEngine.getInstance()
                if (old != null && oldPinned)
                    PersonalDictManager.phraseTypedCodes(context, old.code).forEach { engine.unpinCode(it, old.word) }
                if (new != null && place != null)
                    PersonalDictManager.phraseTypedCodes(context, new.code).forEach { engine.pinCode(it, new.word, place) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }
        loadPins()
    }

    /** 固定的词那一组里点「取消」。 */
    fun unpin(pin: PinnedWord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (RimeConfigHelper.prepareEngine(context)) RimeEngine.getInstance().unpinCode(pin.code, pin.word)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            loadPins()
        }
    }

    fun addEntry(word: String, code: String, weight: Int? = null, place: Int? = null) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.entries
            val entry = DictEntry(PhraseLineBreaks.stored(trimmedWord), trimmedCode, weight)
            val updated = current + entry
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, updated) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = updated, filteredEntries = filterEntries(updated, query))
            }
            withContext(Dispatchers.IO) { PersonalDictManager.applyCustomPhrases(context, schemaId) }
            applyPlace(null, entry, place)
        }
    }

    fun updateEntry(index: Int, word: String, code: String, weight: Int? = null, place: Int? = null) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            val old = current[index]
            val entry = DictEntry(PhraseLineBreaks.stored(trimmedWord), trimmedCode, weight)
            current[index] = entry
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, current) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
            withContext(Dispatchers.IO) { PersonalDictManager.applyCustomPhrases(context, schemaId) }
            applyPlace(old, entry, place)
        }
    }

    fun deleteEntry(index: Int) {
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            val old = current.removeAt(index)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, current) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
            withContext(Dispatchers.IO) { PersonalDictManager.applyCustomPhrases(context, schemaId) }
            applyPlace(old, null, null)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredEntries = filterEntries(it.entries, query)
            )
        }
    }

    fun clearSearch() {
        setSearchQuery("")
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, editWord = "", editCode = "", editWeight = "", editPlace = "", editIndex = -1) }
    }

    fun hideAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }

    fun showEditDialog() {
        _uiState.update { it.copy(showEditDialog = true) }
    }

    fun hideEditDialog() {
        _uiState.update { it.copy(showEditDialog = false) }
    }

    fun setEditing(index: Int, entry: DictEntry) {
        _uiState.update { it.copy(
            editIndex = index,
            // 编辑框里是真的换行，存的时候再换成 U+2028。
            editWord = PhraseLineBreaks.typed(entry.word),
            editCode = entry.code,
            editWeight = entry.weight?.toString() ?: "",
            editPlace = placeOf(entry)?.toString() ?: ""
        ) }
    }

    fun setEditWord(word: String) {
        _uiState.update { it.copy(editWord = word) }
    }

    fun setEditCode(code: String) {
        _uiState.update { it.copy(editCode = code) }
    }

    fun setEditWeight(weight: String) {
        _uiState.update { it.copy(editWeight = weight) }
    }

    fun setEditPlace(place: String) {
        _uiState.update { it.copy(editPlace = place.filter(Char::isDigit).take(2)) }
    }

    private fun filterEntries(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries
        val lowerQuery = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) ||
                it.code.contains(query, ignoreCase = true) ||
                it.code.lowercase(Locale.ROOT).contains(lowerQuery)
        }
    }
}
