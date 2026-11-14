package com.kingzcheung.xime.clipboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's clipboard groups, kept in the board preferences next to the image pins. Like the
 * clipboard records themselves they stay on this device: not in backups, not sent by clipboard sync
 * (which only ever carries the current copy's text).
 */
class ClipboardGroupStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ClipboardGroupState> = _state.asStateFlow()

    /** Group chosen when 新增固定内容 was pressed on a group's tab; the new record goes into it. */
    @Volatile private var addTarget: Pair<String, Long>? = null
    /** Tab the board was on when it closed for the add / edit form; the board reopens on it. */
    @Volatile private var returnTab: Pair<String, Long>? = null
    /**
     * The keyboard opened the 剪贴板分组 page: "" from 管理分组, or the key of the record whose 分组 menu
     * offered 新建分组 (the group made there takes that record). The page takes it once when it opens.
     */
    @Volatile private var keyboardEntry: Pair<String, Long>? = null

    private fun read(): ClipboardGroupState {
        val groups = ClipboardGroupCodec.decodeGroups(prefs.getString(KEY_GROUPS, null))
        return ClipboardGroupState(groups,
            ClipboardGroupCodec.decodeMembers(prefs.getStringSet(KEY_MEMBERS, null), groups),
            ClipboardGroupCodec.decodeHidden(prefs.getStringSet(KEY_HIDDEN, null)),
            prefs.getString(KEY_TAB_ORDER, null)?.lines().orEmpty(),
            ClipboardGroupCodec.decodeRecordOrder(prefs.getString(KEY_RECORD_ORDER, null), groups))
    }

    @Synchronized private fun update(change: (ClipboardGroupState) -> ClipboardGroupState) {
        val previous = _state.value
        val next = change(previous)
        if (next == previous) return
        val editor = prefs.edit()
        if (next.groups != previous.groups) editor.putString(KEY_GROUPS, ClipboardGroupCodec.encodeGroups(next.groups))
        if (next.members != previous.members) editor.putStringSet(KEY_MEMBERS, ClipboardGroupCodec.encodeMembers(next.members))
        if (next.hidden != previous.hidden) editor.putStringSet(KEY_HIDDEN, next.hidden.mapTo(mutableSetOf()) { it.name })
        if (next.tabOrder != previous.tabOrder) editor.putString(KEY_TAB_ORDER, next.tabOrder.joinToString("\n"))
        if (next.recordOrder != previous.recordOrder) editor.putString(KEY_RECORD_ORDER, ClipboardGroupCodec.encodeRecordOrder(next.recordOrder))
        editor.apply()
        _state.value = next
    }

    /**
     * Null when the group was added; otherwise why the name cannot be used. [records] go into the new
     * group (the record whose 分组 menu led here).
     */
    @Synchronized fun add(name: String, records: Collection<String> = emptyList()): String? {
        val groups = _state.value.groups
        clipboardGroupNameError(name, groups)?.let { return it }
        var id = "g" + System.currentTimeMillis().toString(36)
        while (groups.any { it.id == id }) id += "x"
        update { it.withGroup(ClipboardGroup(id, cleanClipboardGroupName(name))).let { state ->
            if (records.isEmpty()) state else state.assigned(records, id)
        } }
        return null
    }

    /** Null when renamed; otherwise why the name cannot be used. */
    @Synchronized fun rename(id: String, name: String): String? {
        clipboardGroupNameError(name, _state.value.groups, renaming = id)?.let { return it }
        update { it.renamed(id, cleanClipboardGroupName(name)) }
        return null
    }

    fun delete(id: String) = update { it.withoutGroup(id) }

    /** The group called [name], made (last in the tab row) when there is none; null when the name cannot be used. */
    @Synchronized fun idForName(name: String): String? {
        val clean = cleanClipboardGroupName(name)
        _state.value.groups.firstOrNull { it.name == clean }?.let { return it.id }
        if (add(clean) != null) return null
        return _state.value.groups.firstOrNull { it.name == clean }?.id
    }

    /** Null [groupId] takes the records out of their group. */
    fun assign(keys: Collection<String>, groupId: String?) {
        if (keys.isNotEmpty()) update { it.assigned(keys, groupId) }
    }

    fun setHidden(filter: ClipboardFilter, hide: Boolean) = update { it.withHidden(filter, hide) }

    fun setTabOrder(keys: List<String>) = update { it.reordered(keys) }

    /** The order the user dragged group [groupId]'s records to on the board. */
    fun setRecordOrder(groupId: String, keys: List<String>) = update { it.withRecordOrder(groupId, keys) }

    fun setAddTarget(groupId: String?) {
        addTarget = groupId?.let { it to System.currentTimeMillis() }
    }

    /** The group for the record just added, once; a target left by an abandoned form expires. */
    fun takeAddTarget(): String? {
        val target = addTarget
        addTarget = null
        return target?.takeIf { (id, at) ->
            System.currentTimeMillis() - at < FORM_LIFETIME_MS && _state.value.groups.any { it.id == id }
        }?.first
    }

    /** The group the add form will put its record into (for the form's title), without taking it. */
    fun addTargetGroup(): ClipboardGroup? = addTarget?.takeIf { System.currentTimeMillis() - it.second < FORM_LIFETIME_MS }
        ?.let { (id, _) -> _state.value.groups.firstOrNull { it.id == id } }

    /** [recordKey]: the record whose 分组 menu opened the page; null from 管理分组. */
    fun setKeyboardEntry(recordKey: String?) {
        keyboardEntry = recordKey.orEmpty() to System.currentTimeMillis()
    }

    /** Null when the page was not opened from the keyboard just now; "" from 管理分组; else the record's key. */
    fun takeKeyboardEntry(): String? {
        val entry = keyboardEntry
        keyboardEntry = null
        return entry?.takeIf { System.currentTimeMillis() - it.second < KEYBOARD_ENTRY_MS }?.first
    }

    fun setReturnTab(key: String) {
        returnTab = key to System.currentTimeMillis()
    }

    fun takeReturnTab(): String? {
        val tab = returnTab
        returnTab = null
        return tab?.takeIf { System.currentTimeMillis() - it.second < FORM_LIFETIME_MS }?.first
    }

    companion object {
        private const val KEY_GROUPS = "groups"
        private const val KEY_MEMBERS = "group_members"
        private const val KEY_HIDDEN = "hidden_categories"
        private const val KEY_TAB_ORDER = "tab_order"
        private const val KEY_RECORD_ORDER = "group_record_order"
        private const val FORM_LIFETIME_MS = 10 * 60_000L
        /** Settings opens at once; an entry the page never took (it failed to open) must not stick. */
        private const val KEYBOARD_ENTRY_MS = 60_000L

        @Volatile private var instance: ClipboardGroupStore? = null

        fun getInstance(context: Context): ClipboardGroupStore = instance ?: synchronized(this) {
            instance ?: ClipboardGroupStore(context.applicationContext).also { instance = it }
        }
    }
}
