package com.kingzcheung.xime.settings

import android.content.Context

internal object LetterSwipeBindings {
    const val CHINESE = "letter_swipes_zh"
    const val ENGLISH = "letter_swipes_en"
    val directions = mapOf("left" to "左滑", "right" to "右滑", "up" to "上滑", "down" to "下滑")
    val actions = mapOf("undo" to "撤销", "select_all" to "全选", "copy" to "复制", "cut" to "剪切", "paste" to "粘贴",
        "clipboard" to "剪贴板历史", "home" to "段首", "end" to "段尾")

    /**
     * 原「按键与手势 › Z X C V 角标做撤销、剪切、复制、粘贴」开关（10-31 加，默认关）。11-02 用户：和 Ctrl 的逻辑不搭，
     * 并进「按键符号」成为一个预设；开过的人第一次读取时换成同样的滑动动作，再删掉这个键。
     */
    const val LEGACY_ZXCV_KEY = "zxcv_edit_shortcuts"

    /** Z X C V 预设：上方角标那个方向分别撤销、剪切、复制、粘贴。 */
    val editPresetActions = mapOf("z" to "undo", "x" to "cut", "c" to "copy", "v" to "paste")

    fun key(english: Boolean) = if (english) ENGLISH else CHINESE

    /**
     * 九键 1～9 下方角标那一滑做的事（#297）：键是 "2.lower"，值是 [actions] 里的一个。默认空，照旧。
     * 上方角标那一滑是数字；下方角标往哪边滑见 [lowerDirection]（默认上滑，「反转上下滑方向」时下滑）。
     */
    const val T9 = "t9_swipes"
    val t9Digits = ('1'..'9').map { it.toString() }

    fun loadT9(context: Context): Map<String, String> =
        parseT9(SettingsPreferences.getPrefsPublic(context).getStringSet(T9, null).orEmpty())

    fun saveT9(context: Context, bindings: Map<String, String>) {
        SettingsPreferences.getPrefsPublic(context).edit().putStringSet(T9, encode(bindings.filterValues { it.isNotEmpty() })).apply()
    }

    internal fun parseT9(entries: Set<String>): Map<String, String> = buildMap {
        for (entry in entries) {
            val slot = entry.substringBefore('\t')
            val value = entry.substringAfter('\t', "")
            if (slot.substringBefore('.') in t9Digits && slot.substringAfter('.', "") == "lower" && value in actions) put(slot, value)
        }
    }

    /** 手指往哪边滑输入上方角标：默认下滑，「反转上下滑方向」打开时上滑。长按输入时这个方向空着，也用它。 */
    fun upperDirection(reverse: Boolean) = if (reverse) "up" else "down"
    fun lowerDirection(reverse: Boolean) = if (reverse) "down" else "up"

    fun label(value: String?): String? = value?.let { actions[it] ?: it.removePrefix("text:") }

    /** 键面上方、下方角标位置该写的动作名；那个方向没设动作时为 null，照常写符号。 */
    fun cornerLabels(bindings: Map<String, String>, letter: String, reverse: Boolean): Pair<String?, String?> {
        if (bindings.isEmpty()) return null to null
        val lower = letter.lowercase()
        return label(bindings["$lower.${upperDirection(reverse)}"]) to label(bindings["$lower.${lowerDirection(reverse)}"])
    }

    /** 在 [current] 上套用 Z X C V 预设；这四个键在上方角标方向原来设的动作被替换，别的不动。 */
    fun withEditPreset(current: Map<String, String>, reverse: Boolean): Map<String, String> =
        current + editPresetActions.map { (letter, action) -> "$letter.${upperDirection(reverse)}" to action }

    fun hasEditPreset(current: Map<String, String>, reverse: Boolean): Boolean =
        editPresetActions.all { (letter, action) -> current["$letter.${upperDirection(reverse)}"] == action }

    fun load(context: Context, english: Boolean): Map<String, String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.contains(LEGACY_ZXCV_KEY)) migrateLegacyZxcv(context)
        return parse(prefs.getStringSet(key(english), null).orEmpty())
    }

    private fun parse(entries: Set<String>): Map<String, String> = buildMap {
        for (entry in entries) {
            val slot = entry.substringBefore('\t')
            val value = entry.substringAfter('\t', "")
            if (slot.length > 2 && slot[0] in 'a'..'z' && slot[1] == '.' && slot.substring(2) in directions &&
                (value in actions || value.startsWith("text:") && value.length > 5)) put(slot, value)
        }
    }

    private fun encode(bindings: Map<String, String>): Set<String> =
        bindings.map { (slot, value) -> "$slot\t$value" }.toSet()

    /** 开关开着：中英文 26 键都套用预设（已经自己设过的方向不覆盖）；关着：只删键。 */
    private fun migrateLegacyZxcv(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val edit = prefs.edit()
        if (prefs.getBoolean(LEGACY_ZXCV_KEY, false)) {
            val reverse = prefs.getBoolean("reverse_symbol_swipe", false)
            for (english in listOf(false, true)) {
                val current = parse(prefs.getStringSet(key(english), null).orEmpty())
                edit.putStringSet(key(english), encode(withEditPreset(emptyMap(), reverse) + current))
            }
        }
        edit.remove(LEGACY_ZXCV_KEY).apply()
    }
}
