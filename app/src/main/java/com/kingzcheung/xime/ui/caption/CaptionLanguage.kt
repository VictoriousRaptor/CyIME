package com.kingzcheung.xime.ui.caption

/**
 * The language the captions listen for. Each time the captions open it follows the app's interface
 * language (user 11-14: opening in English was wrong; Chinese UI → Chinese captions); the picker on the
 * caption page changes it for that session only.
 */
enum class CaptionLanguage(val id: String, val label: String) {
    CHINESE("zh", "中文"),
    JAPANESE("ja", "日本語"),
    ENGLISH("en", "English");

    companion object {
        /** The elder package's captions are Chinese only (docs/development/elder-pack-spec.md). */
        val supported: List<CaptionLanguage> = forPackage(com.kingzcheung.xime.BuildConfig.ELDER)
        internal fun forPackage(elder: Boolean) = if (elder) listOf(CHINESE) else entries.toList()
        /** A stored language this package does not offer (e.g. "ja" from a restored backup) reads as Chinese. */
        fun of(id: String?, offered: List<CaptionLanguage> = supported): CaptionLanguage =
            offered.firstOrNull { it.id == id } ?: CHINESE

        /** The caption language for an interface language tag ("zh-CN", "ja", "en-US" …); anything else is Chinese. */
        fun forInterface(tag: String?, offered: List<CaptionLanguage> = supported): CaptionLanguage =
            of(tag?.substringBefore('-')?.substringBefore('_')?.lowercase(), offered)

        /** The language the app's own screens are shown in right now (per-app choice, else the system's). */
        fun forInterface(context: android.content.Context): CaptionLanguage =
            forInterface(context.resources.configuration.locales[0]?.toLanguageTag())
    }
}
