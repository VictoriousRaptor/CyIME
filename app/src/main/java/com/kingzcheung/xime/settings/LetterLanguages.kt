package com.kingzcheung.xime.settings

/**
 * 不经 Rime 组字的语言：字母像英文一样逐字上屏（韩语按两벌식拼成音节，见 [com.kingzcheung.xime.service.HangulComposer]）。
 * 每种语言一个只标语言的入口方案（assets/rime_letters/<schemaId>.schema.yaml）和一个键盘（xime.yaml 的 [keyboard]）。
 * 补全词表是商店里的可选下载（[wordsModel]），开启语言时自动下载；没下载时照样能打字，只是没有补全。
 * 都默认不启用：在「语言与方案」打开时才加入方案列表并部署。
 */
enum class LetterLanguage(
    val language: InputLanguage,
    val schemaId: String,
    /** 实体键盘浮条和语音条上的语言标记。 */
    val badge: String,
    val keyboard: String,
    /** a–z 之外，这个键盘上点按或长按打出的字母（含大写）。 */
    val extraLetters: String,
    /** 补全词表在商店里的模型 id；null 表示没有补全（韩语）。 */
    val wordsModel: String?,
) {
    SPANISH(InputLanguage.SPANISH, "spanish", "ES", "qwerty_spanish", "áéíóúüñÁÉÍÓÚÜÑ", "words-es"),
    GERMAN(InputLanguage.GERMAN, "german", "DE", "qwerty_german", "äöüßÄÖÜ", "words-de"),
    FRENCH(InputLanguage.FRENCH, "french", "FR", "qwerty_french",
        "àâæçéèêëîïôœùûüÿÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸ", "words-fr"),
    ITALIAN(InputLanguage.ITALIAN, "italian", "IT", "qwerty_italian", "àèéìíòóùúÀÈÉÌÍÒÓÙÚ", "words-it"),
    RUSSIAN(InputLanguage.RUSSIAN, "russian", "RU", "jcuken_russian",
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ", "words-ru"),
    KOREAN(InputLanguage.KOREAN, "korean", "한", "dubeolsik_korean",
        "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ", null),
    /** 越南语：声调和变音用 Telex 打（[com.kingzcheung.xime.service.VietnameseTelex]），长按出的是不带声调的变音字母。 */
    VIETNAMESE(InputLanguage.VIETNAMESE, "vietnamese", "VI", "qwerty_vietnamese", "ăâêôơưđĂÂÊÔƠƯĐ", "words-vi"),
    /** 马来语：没有 a–z 以外的字母；叠词（kanak-kanak）和补英文见 [com.kingzcheung.xime.association.MalayCompletion]。 */
    MALAY(InputLanguage.MALAY, "malay", "MS", "qwerty_malay", "", "words-ms");

    /** 词表文件名带版本；换新版时改这里和商店目录。 */
    val wordsFile: String? get() = wordsModel?.let { "cyime-$it-v1.txt" }

    companion object {
        /** 所有语言键盘上的非 a–z 字母，键帽随 Shift 改大小写时用。 */
        val allExtraLetters: String = entries.joinToString("") { it.extraLetters }

        fun of(language: InputLanguage): LetterLanguage? = entries.firstOrNull { it.language == language }
        fun ofSchema(schemaId: String): LetterLanguage? = entries.firstOrNull { it.schemaId == schemaId }
        val schemaIds: List<String> = entries.map { it.schemaId }
        val keyboards: Set<String> = entries.mapTo(mutableSetOf()) { it.keyboard }

        /**
         * 字母键上的字母：a–z 和各语言键盘上的 ñ ä é й ㅂ…。这些键都能按住 Shift 拖过去打大写、
         * 按住 !@# 当 Ctrl 的目标（用户 11-17：俄语键盘上 Shift 拖动和 Ctrl 都没用，以前只认 a–z）。
         */
        fun isKeyLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z' || char in allExtraLetters

        private val qwertyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        /**
         * 按住 !@# 当 Ctrl 时这个字母键发 Ctrl+几：a–z 就是自己（AZERTY 的 a 也是 Ctrl+A，和电脑上一样认字母）；
         * 俄语、韩语这种不是拉丁字母的键盘按位置，取 [rows] 里同一行同一格 26 键的字母（ЙЦУКЕН 的 с 在 c 那一格，
         * 发 Ctrl+C），和电脑上俄语、韩语键盘一样。ñ ä 这类拉丁字母、超出 26 键那一格的（х ж э）没有，返回 null。
         */
        fun ctrlLetter(key: Char, rows: List<List<String>>): Char? {
            val lower = key.lowercaseChar()
            if (lower in 'a'..'z') return lower
            if (!isKeyLetter(key) || Character.UnicodeScript.of(lower.code) == Character.UnicodeScript.LATIN) return null
            rows.forEachIndexed { row, keys ->
                val column = keys.indexOfFirst { it.singleOrNull()?.lowercaseChar() == lower }
                if (column >= 0) return qwertyRows.getOrNull(row)?.getOrNull(column)
            }
            return null
        }
    }
}
