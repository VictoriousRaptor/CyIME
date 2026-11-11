package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 横竖屏分开记布局（用户 11-09：横屏 26 键、竖屏九键）。默认关，关着时行为与原来完全一样。
 *
 * 开着时每种语言横屏、竖屏各记一个输入条目（布局随条目走：26 键、九键、14 键、自定义布局都是条目）。
 * 在哪个方向选了布局或方案就记到哪个方向；另一方向还没记过时，补记成选之前用的那个，
 * 这样「竖屏九键时转横屏点 26 键」之后转回竖屏能回到九键。转屏后打开键盘时换到这个方向记的条目。
 */
object OrientationLayouts {
    const val KEY = "layout_per_orientation"
    private const val SLOT = "orientation_profile_"

    fun isEnabled(context: Context): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY, false)

    /** 关掉时清掉两边记的条目：以后再打开从当时的选择重新记，不带回很久以前的布局。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().apply {
            putBoolean(KEY, enabled)
            if (!enabled) prefs.all.keys.filter { it.startsWith(SLOT) }.forEach { remove(it) }
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
