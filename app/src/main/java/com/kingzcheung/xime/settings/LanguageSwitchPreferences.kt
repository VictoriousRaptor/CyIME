package com.kingzcheung.xime.settings

import android.content.Context

internal enum class LanguageSwitchMode(val id: String) {
    CURRENT_ENGLISH("current_english"), CYCLE("cycle"), SPECIFIC_ENGLISH("specific_english"),
    /** 指定的两种语言互切（如中文 ↔ 意大利语，不经过英文）；用户 11-09 收到的建议，要自己选才生效。 */
    SPECIFIC_PAIR("specific_pair")
}

internal data class LanguageSwitchOptions(
    val mode: LanguageSwitchMode = LanguageSwitchMode.CURRENT_ENGLISH,
    val language: InputLanguage? = null,
    /** [LanguageSwitchMode.SPECIFIC_PAIR] 的另一种语言；没选过就是英文。 */
    val partner: InputLanguage? = null,
)

/** Explicit language-key settings. Ordinary language selection never modifies these preferences. */
internal object LanguageSwitchPreferences {
    const val MODE_KEY = "language_key_switch_mode"
    const val LANGUAGE_KEY = "language_key_specific_language"
    const val PARTNER_KEY = "language_key_specific_partner"

    fun read(context: Context): LanguageSwitchOptions {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        return LanguageSwitchOptions(
            LanguageSwitchMode.entries.firstOrNull { it.id == prefs.getString(MODE_KEY, null) }
                ?: LanguageSwitchMode.CURRENT_ENGLISH,
            InputLanguage.fromId(prefs.getString(LANGUAGE_KEY, null).orEmpty())?.takeUnless { it == InputLanguage.ENGLISH },
            InputLanguage.fromId(prefs.getString(PARTNER_KEY, null).orEmpty()),
        )
    }

    fun save(context: Context, options: LanguageSwitchOptions) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(MODE_KEY, options.mode.id)
            .putString(LANGUAGE_KEY, options.language?.id).putString(PARTNER_KEY, options.partner?.id).apply()
    }

    /**
     * 「指定语言与英文切换」列出的语言：每种已支持的非英文语言都列出（中文、日语、西班牙语……），按语言顺序，
     * 没在顺序里的（未启用的）排在后面。哪些此刻能选由设置页按启用和方案就绪另外判断，不在这里漏掉语言。
     */
    fun specificChoices(order: List<InputLanguage>): List<InputLanguage> = (order + InputLanguage.supported).distinct()
        .filter { it in InputLanguage.supported && it != InputLanguage.ENGLISH && it != InputLanguage.UNSPECIFIED }

    /**
     * 设置页「两种语言互切」选好的两种存成哪种模式（用户 11-09 把三项合成一项）：第一种是「当前语言」（null）就是
     * 原来的默认「当前语言与英文」，另一种是英文就是「指定语言与英文」，其余才是 [LanguageSwitchMode.SPECIFIC_PAIR]。
     * 这样存的值和旧版本通用，默认行为不变。
     */
    fun pair(first: InputLanguage?, partner: InputLanguage?): LanguageSwitchOptions = when {
        first == null || first == InputLanguage.ENGLISH -> LanguageSwitchOptions(LanguageSwitchMode.CURRENT_ENGLISH)
        partner == null || partner == InputLanguage.ENGLISH || partner == first ->
            LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, first)
        else -> LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, first, partner)
    }

    /** Hardware shortcuts have fixed meanings without changing the user's language-key choice. */
    fun forRequest(configured: LanguageSwitchOptions, overrideMode: LanguageSwitchMode?): LanguageSwitchOptions =
        overrideMode?.let { LanguageSwitchOptions(it) } ?: configured

    fun target(current: InputLanguage, nativeFallback: InputLanguage, options: LanguageSwitchOptions,
        languageOrder: List<InputLanguage>, available: Set<InputLanguage>): InputLanguage? {
        val ordered = (languageOrder + available).distinct().filter { it in available }
        return when (options.mode) {
            LanguageSwitchMode.CURRENT_ENGLISH ->
                (if (current == InputLanguage.ENGLISH) nativeFallback else InputLanguage.ENGLISH)
                    .takeIf { it in available && it != current }
            LanguageSwitchMode.CYCLE -> {
                if (ordered.isEmpty()) null else ordered[(ordered.indexOf(current) + 1) % ordered.size]
                    .takeUnless { it == current }
            }
            LanguageSwitchMode.SPECIFIC_ENGLISH -> {
                fun native(language: InputLanguage) = language != InputLanguage.ENGLISH && language in available
                val selected = options.language?.takeIf(::native) ?: nativeFallback.takeIf(::native)
                    ?: ordered.firstOrNull(::native)
                if (selected == null) null
                else (if (current == selected) InputLanguage.ENGLISH else selected).takeIf { it in available }
            }
            LanguageSwitchMode.SPECIFIC_PAIR -> {
                // 第一种语言同「指定语言与英文切换」的取法；另一种没选、没开或和第一种相同时退回英文。
                fun native(language: InputLanguage) = language != InputLanguage.ENGLISH && language in available
                val first = options.language?.takeIf(::native) ?: nativeFallback.takeIf(::native)
                    ?: ordered.firstOrNull(::native)
                val second = options.partner?.takeIf { it in available && it != first } ?: InputLanguage.ENGLISH
                if (first == null) null
                else (if (current == first) second else first).takeIf { it in available && it != current }
            }
        }
    }
}
