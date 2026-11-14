package com.kingzcheung.xime.rime

/** Owns an uncommitted composition; editing never rewrites host text. */
data class PinyinEditSession(
    val inputSessionId: Long,
    val schemaId: String,
    val expectedInput: String,
    val protectedInput: String,
    val protectedText: String,
    val text: String,
    val caret: Int,
    val isT9: Boolean,
    val t9RemainingDigits: String = "",
    val t9SelectionCount: Long = 0,
) {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)
    internal fun invalidate() { active.set(false) }
    internal fun isActive(): Boolean = active.get()
}

/** The host and engine can use different separators for the same syllable boundary. */
internal fun PinyinEditSession.inlineOffset(code: String): Int? {
    if (!code.startsWith(protectedText)) return null
    val suffix = code.drop(protectedText.length).replace(' ', '\'')
    if (suffix == text) return protectedText.length + caret
    if (suffix.replace("'", "") != text.replace("'", ""))
        return doublePinyinCaretMap(suffix)?.let { protectedText.length + it.displayOffset(caret) }
    return protectedText.length + mapCodeOffset(text, suffix, caret)
}

internal fun PinyinEditSession.editOffset(code: String, offset: Int): Int? {
    if (!code.startsWith(protectedText)) return null
    val suffix = code.drop(protectedText.length).replace(' ', '\'')
    val local = (offset - protectedText.length).coerceIn(0, suffix.length)
    if (suffix == text) return local
    if (suffix.replace("'", "") == text.replace("'", "")) return mapCodeOffset(suffix, text, local)
    return doublePinyinCaretMap(suffix)?.rawOffset(local)
}

/**
 * 26 键双拼的输入框显示全拼、编辑缓冲是双拼键：按方案键位对位置。不对的话，点了前面的拼音，
 * 编辑光标还留在末尾，删除删的是最后的键（11-08 小鹤双拼反馈）。
 */
private fun PinyinEditSession.doublePinyinCaretMap(suffix: String): DoublePinyinCaretMap? {
    if (isT9 || com.kingzcheung.xime.settings.InputProfiles.describe(schemaId).scheme !=
        com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN) return null
    val own = com.kingzcheung.xime.settings.DoublePinyinSchemes.find(schemaId)
    val schemes = if (own != null) listOf(own) else com.kingzcheung.xime.settings.DoublePinyinScheme.BUILTINS
    return schemes.firstNotNullOfOrNull { DoublePinyinCaretMap.build(suffix, text, it) }
}

/** Preserve which side of a visible boundary the user selected when both texts have one. */
internal fun mapCodeOffset(from: String, to: String, offset: Int): Int {
    val position = offset.coerceIn(0, from.length)
    val keys = codeKeysBefore(from, position)
    val fromBoundary = offsetAfterCodeKeys(from, keys)
    val toBoundary = offsetAfterCodeKeys(to, keys)
    val afterSeparator = (position - fromBoundary).coerceAtLeast(0)
    val separatorCount = to.drop(toBoundary).takeWhile { it == '\'' }.length
    return toBoundary + afterSeparator.coerceAtMost(separatorCount)
}

internal object PinyinEditBuffer {
    fun supports(schema: String): Boolean =
        com.kingzcheung.xime.settings.InputProfiles.describe(schema).capabilities.editablePinyin

    fun normalizedT9(text: String): String = text.lowercase().replace('ü', 'v')
        .replace(' ', '\'').filter { it in 'a'..'z' || it in '2'..'9' || it == '\'' }

    fun normalized(text: String): String = text.lowercase().replace('ü', 'v')
        .replace(' ', '\'').filter { it in 'a'..'z' || it == '\'' }

    /** 编码分大小写的方案（英文单词，打 Hello 出 Hello）留着大写字母，别的同 [normalized]。 */
    fun normalized(text: String, keepCase: Boolean): String = if (!keepCase) normalized(text)
        else text.replace(' ', '\'').filter { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }

    fun keepsCase(schema: String): Boolean =
        com.kingzcheung.xime.settings.InputProfiles.describe(schema).capabilities.caseSensitiveCode

    /** 去掉开头的分隔符（光标跟着往前挪）；编码开头的分隔符不分开任何音节。 */
    fun dropLeadingSeparators(text: String, caret: Int): Pair<String, Int> {
        val lead = text.takeWhile { it == '\'' }.length
        return if (lead == 0) text to caret else text.drop(lead) to (caret - lead).coerceAtLeast(0)
    }

    fun mergeSeparators(text: String, caret: Int): Pair<String, Int> {
        val result = StringBuilder()
        var newCaret = 0
        text.forEachIndexed { index, char ->
            if (char != '\'' || result.isEmpty() || result.last() != '\'') result.append(char)
            if (index < caret) newCaret = result.length
        }
        return result.toString() to newCaret
    }
}

/** Maps engine syllables to code. Editor sessions retain these boundaries as editable text. */
internal class PinyinEditDisplay(raw: String, preedit: String, isT9: Boolean = false,
    includeAutomaticBoundaries: Boolean = true, isMerged14: Boolean = false, groups: List<String> = if (isMerged14) merged14Groups else emptyList()) {
    val text: String
    private val offsets: List<Int>
    init {
        val visible = PinyinEditBuffer.normalized(preedit)
        val boundaries = mutableSetOf<Int>()
        val reading = visible.replace("'", "")
        val input = raw.replace("'", "")
        fun digit(c: Char): Char = when (c) {
            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
            in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> c
        }
        // Numeric keys remain ambiguous in the engine, but display its actual reading.
        // Literal letters and caret offsets retain their original identity.
        val matches = input.length == reading.length && input.indices.all {
            input[it] == reading[it] || isT9 && input[it] in '2'..'9' && input[it] == digit(reading[it]) ||
                groups.isNotEmpty() && groupRepresentative(input[it], groups) == groupRepresentative(reading[it], groups)
        }
        if (matches) {
            var letters = 0
            visible.forEach { if (it == '\'') boundaries.add(letters) else letters++ }
        }
        val mapping = mutableListOf(0)
        text = buildString {
            var letters = 0
            raw.forEachIndexed { index, character ->
                if (includeAutomaticBoundaries && character != '\'' && letters in boundaries && isNotEmpty() && last() != '\'') {
                    append('\''); mapping.add(index)
                }
                append(if (matches && (character in '2'..'9' || groups.isNotEmpty() && character != '\'')) reading[letters] else character); mapping.add(index + 1)
                if (character != '\'') letters++
            }
        }
        offsets = mapping
    }
    fun rawOffset(display: Int): Int = offsets[display.coerceIn(0, offsets.lastIndex)]
    fun displayOffset(raw: Int): Int = offsets.indexOfLast { it <= raw }.coerceAtLeast(0)
}

/**
 * 14 键的编辑缓冲显示读音（hao），引擎里是按过的键（GAO，大写是合并键）。在编码中间删字母或插键时，
 * 没动的字母照旧送当初按的键，只有新插的是新的。不能把显示的字母当精确字母送进引擎：没认出读音的地方
 * 显示的是键上第一个字母，完完全全的 wwqq 会变成精确的 qqqq，hao 变成 gao（11-13 反馈：14 键挪光标后删除、新增就乱了）。
 * 返回与 [text] 逐字对齐的按键（分隔符照抄）；字母数和按键数对不上时 null，调用方照旧送 [text]。
 */
internal fun merged14KeysOf(text: String, engineCode: String): String? {
    val keys = engineCode.filter { it != '\'' && !it.isWhitespace() }
    if (keys.length != text.count { it != '\'' }) return null
    var next = 0
    return text.map { if (it == '\'') '\'' else keys[next++] }.joinToString("")
}

/** 14 键的合并键（qw）在编辑缓冲里显示键上第一个字母，送进引擎是大写的合并键（Q）；单字母键（l、m）原样。 */
internal fun merged14TapDisplay(group: String): String = group.take(1)
internal fun merged14TapKey(group: String): String = if (group.length > 1) group.take(1).uppercase() else group

/** Nine-key code and its displayed reading match key for key; syllable delimiters are not keys. */
internal fun codeKeysBefore(text: String, caret: Int): Int = text.take(caret.coerceAtLeast(0)).count { it != '\'' }

/** Offset just after the first [keys] keys of [text]; 0 for none, the end when it has fewer. */
internal fun offsetAfterCodeKeys(text: String, keys: Int): Int {
    if (keys <= 0) return 0
    var seen = 0
    text.forEachIndexed { index, c -> if (c != '\'' && ++seen == keys) return index + 1 }
    return text.length
}

/** The reading after its first [keys] keys, keeping its own syllable boundaries. */
internal fun dropReadingKeys(reading: String, keys: Int): String {
    val start = offsetAfterCodeKeys(reading.replace(' ', '\''), keys)
    return reading.drop(start).trimStart(' ', '\'')
}

/** Preserve confirmed Chinese text and real codes; make Latin syllable spaces visible. */
internal fun pinyinPreviewText(preedit: String): String = preedit.trim()
    .replace(Regex("(?<=[a-zA-ZüÜ'])\\s+(?=[a-zA-ZüÜ'])"), "'")

internal fun editablePreeditText(text: String, schema: String, isAscii: Boolean): String =
    if (!isAscii && PinyinEditBuffer.supports(schema)) pinyinPreviewText(text) else text

internal val merged14Groups = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
internal fun merged14Representative(c: Char): Char = merged14Groups.firstOrNull { c in it }?.first() ?: c

/** Only substitute a dictionary reading when every entered key is accounted for. */
internal fun groupRepresentative(c: Char, groups: List<String>): Char = groups.firstOrNull { c.lowercaseChar() in it }?.first() ?: c
internal fun merged14Preedit(raw: String, preedit: String, spelling: String, groups: List<String> = merged14Groups): String {
    val reading = spelling.trim()
    if (reading.isEmpty() || reading.any { it !in 'a'..'z' && it != '\'' && !it.isWhitespace() && it != 'ü' }) return preedit
    val input = PinyinEditBuffer.normalized(raw).replace("'", "")
    val letters = PinyinEditBuffer.normalized(reading).replace("'", "")
    if (input.isEmpty() || input.length != letters.length || input.indices.any {
            groupRepresentative(input[it], groups) != groupRepresentative(letters[it], groups) }) return preedit
    return PinyinEditDisplay(raw, reading, groups = groups).text
}

/** Multi-tap exists only in the explicit pinyin editor, never in ordinary composition. */
internal class PinyinMultiTap {
    private var group = ""
    private var previousText = ""
    private var previousCaret = -1
    private var previousTime = Long.MIN_VALUE
    private var index = 0
    fun reset() { group = ""; previousCaret = -1 }
    fun press(text: String, caret: Int, letters: String, timeMs: Long): Pair<String, Int> {
        val cycling = group == letters && text == previousText && caret == previousCaret &&
            caret > 0 && timeMs - previousTime in 0..650
        index = if (cycling) (index + 1) % letters.length else 0
        val position = if (cycling) caret - 1 else caret
        val next = text.take(position) + letters[index] + text.drop(caret)
        group = letters; previousText = next; previousCaret = position + 1; previousTime = timeMs
        return next to previousCaret
    }
}
