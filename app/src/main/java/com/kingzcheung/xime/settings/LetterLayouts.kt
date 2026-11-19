package com.kingzcheung.xime.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One letter arrangement per language for input typed letter by letter: English (direct and words),
 * Japanese romaji, and Chinese double pinyin and code tables (五笔 and imported tables such as 音形
 * share the Chinese choice; pinyin itself carries its layout in the schema). The choices are the letter layouts Chinese pinyin
 * offers (QWJRTK, Cyletix10, Colemak and the user's own), and rows, names, swipe positions and
 * moved-letter colours all come from that same [CustomKeyboardLayout]. Layouts with merged keys are
 * left out: a two-letter key cannot type one letter directly, and romaji and double pinyin have no
 * decoder that splits it (double pinyin types merged keys on its 14-key versions and on its versions
 * generated for each merged custom layout, see DoublePinyinSchemes.isOnLayout).
 */
object LetterLayouts {
    private const val PREFIX = "letter_layout_"
    private var chosen by mutableStateOf<Map<InputLanguage, String>>(emptyMap())

    fun appliesTo(language: InputLanguage, scheme: InputScheme): Boolean =
        language == InputLanguage.ENGLISH && (scheme == InputScheme.DIRECT || scheme == InputScheme.WORDS) ||
            language == InputLanguage.JAPANESE && scheme == InputScheme.ROMAJI ||
            language == InputLanguage.CHINESE && scheme in LETTER_CODED_CHINESE

    /** Chinese schemes typed letter by letter on 26 keys besides pinyin: double pinyin and code tables (五笔、仓颉、音形等导入的码表). */
    private val LETTER_CODED_CHINESE = setOf(InputScheme.DOUBLE_PINYIN, InputScheme.WUBI, InputScheme.WUBI_PINYIN,
        InputScheme.CANGJIE, InputScheme.EXTERNAL)

    fun typable(layout: CustomKeyboardLayout): Boolean = layout.mergedGroups.isEmpty()

    /** 法语 AZERTY（法国、比利时的排法，用户 11-16）：xime.yaml 里的 azerty_french。 */
    const val AZERTY_FRENCH = "azerty_french"

    /**
     * 字母语言自带的另一套键盘（xime.yaml 里不绑方案的 section）：法语可以换成 AZERTY。
     * 选了才用，存的键和上面的排列一样（letter_layout_<语言>），没选时照旧用语言自己的键盘。
     */
    private val SECTION_LAYOUTS: Map<InputLanguage, List<String>> = mapOf(InputLanguage.FRENCH to listOf(AZERTY_FRENCH))
    val sectionLayoutIds: Set<String> get() = SECTION_LAYOUTS.values.flatten().toSet()

    fun sectionChoices(language: InputLanguage): List<String> = SECTION_LAYOUTS[language].orEmpty()

    /** 这个语言换用的那套键盘；null 用语言自己的键盘。 */
    fun chosenSection(language: InputLanguage): String? = chosen[language]?.takeIf { it in sectionChoices(language) }

    /** null keeps the language's own 26 keys; a deleted custom layout falls back the same way. */
    fun chosen(language: InputLanguage): CustomKeyboardLayout? =
        chosen[language]?.let(CustomKeyboardLayouts::find)?.takeIf(::typable)

    /** Exactly the letter layouts the Chinese entries offer, plus the one already chosen. */
    fun choices(entries: List<SchemaInfo>, language: InputLanguage): List<CustomKeyboardLayout> =
        (entries.filter { it.language == InputLanguage.CHINESE }.mapNotNull { CustomKeyboardLayouts.find(it.schemaId) } +
            listOfNotNull(chosen(language))).filter(::typable).distinctBy { it.id }

    fun load(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        chosen = InputLanguage.entries.mapNotNull { language ->
            prefs.getString(PREFIX + language.id, null)?.let { language to it }
        }.toMap()
    }

    /** [layoutId] that is not a typable letter layout (the language's own 26 keys) clears the choice. */
    fun select(context: Context, language: InputLanguage, layoutId: String) {
        val id = layoutId.takeIf { it in sectionChoices(language) } ?: CustomKeyboardLayouts.find(layoutId)?.takeIf(::typable)?.id
        SettingsPreferences.getPrefsPublic(context).edit().apply {
            if (id == null) remove(PREFIX + language.id) else putString(PREFIX + language.id, id)
        }.apply()
        chosen = if (id == null) chosen - language else chosen + (language to id)
        KeysConfigHelper.applyLetterLayouts()
    }

    /** A layout tap resolved to [entry]; Chinese entries carry their layout in the schema itself. */
    fun selectFor(context: Context, entry: SchemaInfo, layoutId: String) {
        val base = entry.baseProfile
        if (base.layout.kind == LayoutKind.ALPHABETIC &&
            (appliesTo(base.language, base.scheme) || sectionChoices(base.language).isNotEmpty()))
            select(context, base.language, layoutId)
    }

    internal fun setForTest(language: InputLanguage, layoutId: String?) {
        chosen = if (layoutId == null) chosen - language else chosen + (language to layoutId)
    }
}
