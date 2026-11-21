package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 横竖屏分开记布局（用户 11-09：横屏 26 键、竖屏九键）。默认关，关着时行为与原来完全一样。
 *
 * 开着时每种语言横屏、竖屏各记一个输入条目（布局随条目走：26 键、九键、14 键、自定义布局都是条目）。
 * 在哪个方向选了布局或方案就记到哪个方向；另一方向还没记过时，补记成选之前用的那个，
 * 这样「竖屏九键时转横屏点 26 键」之后转回竖屏能回到九键。转屏后打开键盘时换到这个方向记的条目。
 *
 * 字母排列（[LetterLayouts]：双拼、五笔等码表、英文、日语罗马字用的 QWJRTK、Colemak、自己排的布局）不在方案 id 里，
 * 一种语言只有一个选择，以前两个方向共用，选了自定义布局后转屏换不回来（用户 11-20）。现在也按方向各记一份，规则同上。
 */
object OrientationLayouts {
    const val KEY = "layout_per_orientation"
    private const val SLOT = "orientation_profile_"
    private const val LETTER_SLOT = "orientation_letters_"
    /** 这个方向用语言自己的 26 键（没选字母排列）。 */
    private const val OWN_KEYS = "-"

    fun isEnabled(context: Context): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY, false)

    /** 关掉时清掉两边记的条目：以后再打开从当时的选择重新记，不带回很久以前的布局。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().apply {
            putBoolean(KEY, enabled)
            if (!enabled) prefs.all.keys.filter { it.startsWith(SLOT) || it.startsWith(LETTER_SLOT) }.forEach { remove(it) }
        }.apply()
    }

    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.let { it.screenWidthDp > it.screenHeightDp }

    internal fun slotKey(language: InputLanguage, landscape: Boolean): String =
        SLOT + (if (landscape) "land_" else "port_") + language.id

    fun slot(context: Context, language: InputLanguage, landscape: Boolean = isLandscape(context)): String? =
        SettingsPreferences.getPrefsPublic(context).getString(slotKey(language, landscape), null)

    /** [InputModes.selectProfile] 写入新选择之前调用；[previous] 是写入前选着的条目。 */
    internal fun record(context: Context, language: InputLanguage, schemaId: String, previous: String?) {
        if (schemaId.isBlank() || !isEnabled(context)) return
        record(SettingsPreferences.getPrefsPublic(context), language, schemaId, previous, isLandscape(context))
    }

    internal fun record(prefs: SharedPreferences, language: InputLanguage, schemaId: String, previous: String?,
        landscape: Boolean) {
        val other = slotKey(language, !landscape)
        prefs.edit().apply {
            putString(slotKey(language, landscape), schemaId)
            if (prefs.getString(other, null) == null && !previous.isNullOrBlank()) putString(other, previous)
        }.apply()
    }

    internal fun letterKey(language: InputLanguage, landscape: Boolean): String =
        LETTER_SLOT + (if (landscape) "land_" else "port_") + language.id

    /** [LetterLayouts.select] 写入之前调用：[layoutId]、[previous] 为 null 表示语言自己的 26 键。 */
    internal fun recordLetters(context: Context, language: InputLanguage, layoutId: String?, previous: String?) {
        if (!isEnabled(context)) return
        recordLetters(SettingsPreferences.getPrefsPublic(context), language, layoutId, previous, isLandscape(context))
    }

    internal fun recordLetters(prefs: SharedPreferences, language: InputLanguage, layoutId: String?, previous: String?,
        landscape: Boolean) {
        val other = letterKey(language, !landscape)
        prefs.edit().apply {
            putString(letterKey(language, landscape), layoutId ?: OWN_KEYS)
            // 另一方向没记过：补记成换之前的（没选过也是一种选择：语言自己的 26 键）。
            if (prefs.getString(other, null) == null) putString(other, previous ?: OWN_KEYS)
        }.apply()
    }

    /**
     * 打开键盘时字母排列该换成什么：这个方向记过、和现在 [current] 不同、记的排列还在（[usable]）的语言 → 排列 id
     * （null = 语言自己的 26 键）。关着或没记过的语言不在里面。
     */
    internal fun lettersToRestore(prefs: SharedPreferences, landscape: Boolean, current: Map<InputLanguage, String>,
        usable: (InputLanguage, String) -> Boolean): Map<InputLanguage, String?> =
        if (!prefs.getBoolean(KEY, false)) emptyMap() else InputLanguage.entries.mapNotNull { language ->
            val saved = prefs.getString(letterKey(language, landscape), null) ?: return@mapNotNull null
            val id = saved.takeIf { it != OWN_KEYS }
            if (id == current[language] || id != null && !usable(language, id)) null else language to id
        }.toMap()

    /**
     * 打开输入框时用的条目：开着、[stored] 是某语言（英文除外）的键盘条目、这个方向记过同语言另一个条目时换成它，
     * 否则原样返回 [stored]。按应用固定的方案由调用方跳过。
     */
    fun prepared(context: Context, stored: String, entries: List<SchemaInfo>): String {
        if (!isEnabled(context)) return stored
        val entry = entries.firstOrNull { it.schemaId == stored && it.profile.mode == InputMode.KEYBOARD } ?: return stored
        val language = entry.profile.language
        if (language == InputLanguage.ENGLISH) return stored
        return target(entries, language, slot(context, language), stored) ?: stored
    }

    /**
     * 打开键盘时该换到的条目：只在这个方向记过、条目还在（同一语言的键盘条目）且和当前不同的时候给出，
     * 其余情况 null（不动）。
     */
    fun target(entries: List<SchemaInfo>, language: InputLanguage, remembered: String?, current: String): String? =
        remembered?.takeIf { id -> id != current &&
            entries.any { it.schemaId == id && it.profile.language == language && it.profile.mode == InputMode.KEYBOARD } }
}
