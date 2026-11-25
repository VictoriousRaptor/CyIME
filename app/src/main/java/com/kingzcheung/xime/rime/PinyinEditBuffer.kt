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
    val suffix = hostCode(code.drop(protectedText.length).replace(' ', '\''))
    if (suffix == text) return protectedText.length + caret
    if (suffix.replace("'", "") != text.replace("'", ""))
        return doublePinyinCaretMap(suffix)?.let { protectedText.length + it.displayOffset(caret) }
    return protectedText.length + mapCodeOffset(text, suffix, caret)
}

internal fun PinyinEditSession.editOffset(code: String, offset: Int): Int? {
    if (!code.startsWith(protectedText)) return null
    val suffix = hostCode(code.drop(protectedText.length).replace(' ', '\''))
    val local = (offset - protectedText.length).coerceIn(0, suffix.length)
    if (suffix == text) return local
    if (suffix.replace("'", "") == text.replace("'", "")) return mapCodeOffset(suffix, text, local)
    return doublePinyinCaretMap(suffix)?.rawOffset(local)
}

/** 笔画编码在输入框里可能显示成笔画（设置开着），一个笔画对一个字母，换回字母再对位置。 */
private fun PinyinEditSession.hostCode(suffix: String): String =
    if (!isT9 && PinyinEditBuffer.isStrokeCode(schemaId)) strokeLetters(suffix) else suffix

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
        com.kingzcheung.xime.settings.InputProfiles.describe(schema).capabilities.editableCode

    fun normalizedT9(text: String): String = text.lowercase().replace('ü', 'v')
        .replace(' ', '\'').filter { it in 'a'..'z' || it in '2'..'9' || it == '\'' }

    fun normalized(text: String): String = text.lowercase().replace('ü', 'v')
        .replace(' ', '\'').filter { it in 'a'..'z' || it == '\'' }

    /** 编码分大小写的方案（英文单词，打 Hello 出 Hello）留着大写字母，别的同 [normalized]。 */
    fun normalized(text: String, keepCase: Boolean): String = if (!keepCase) normalized(text)
        else text.replace(' ', '\'').filter { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }

    /** 双拼的分号是韵母键（微软双拼 ; = ing），编辑时和字母一样留着（#365：p;vg 改不了前面的 p）。 */
    fun normalized(text: String, keepCase: Boolean, keepSemicolon: Boolean): String =
        if (!keepSemicolon) normalized(text, keepCase)
        else text.split(';').joinToString(";") { normalized(it, keepCase) }

    /**
     * 笔画编码：通配符 * 是编码（一笔），编辑时和字母一样留着；分词号连打、打在开头都算数
     * （n 个分词号 = n+1 个字，空段代表任意一个字，11-18），不合并、不去掉。
     */
    fun isStrokeCode(schema: String): Boolean =
        com.kingzcheung.xime.settings.InputProfiles.describe(schema).capabilities.editableStrokeCode

    /** [normalized]，笔画编码另外留着通配符 *。 */
    fun normalized(text: String, keepCase: Boolean, keepSemicolon: Boolean, keepWildcard: Boolean): String =
        if (!keepWildcard) normalized(text, keepCase, keepSemicolon)
        else text.split('*').joinToString("*") { normalized(it, keepCase, keepSemicolon) }

    fun keepsSemicolon(schema: String): Boolean =
        com.kingzcheung.xime.settings.InputProfiles.describe(schema).scheme ==
            com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN

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

/**
 * 屏上拼音（气泡、小签、整行）里的一个「字母」：拼音字母、带声调的字母、九键数字、双拼的分号。
 * 空格、隔音符、零宽空格、光标竖线、已经选好的汉字都不算。点拼音打开编辑时按「点在第几个字母后面」对位置。
 */
internal fun isPreeditLetter(c: Char): Boolean = c == ';' ||
    (c.isLetterOrDigit() && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.HAN)

/** [text] 里 [offset] 前面有几个 [isPreeditLetter]。 */
internal fun preeditLettersBefore(text: String, offset: Int): Int =
    text.take(offset.coerceIn(0, text.length)).count(::isPreeditLetter)

/** [text] 里第 [letters] 个 [isPreeditLetter] 之后的位置；0 个时是第一个字母前面（跳过前面选好的汉字），不够时是末尾。 */
internal fun offsetAfterPreeditLetters(text: String, letters: Int): Int {
    if (letters <= 0) return text.indexOfFirst(::isPreeditLetter).takeIf { it >= 0 } ?: text.length
    var seen = 0
    text.forEachIndexed { index, c -> if (isPreeditLetter(c) && ++seen == letters) return index + 1 }
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
    if (!isAscii && com.kingzcheung.xime.settings.InputProfiles.describe(schema).capabilities.editablePinyin) pinyinPreviewText(text) else text

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

/**
 * 「双拼编码显示全拼」开着时合并键（14 键、自建布局）上的双拼：打的键和首选读音换成的双拼键 [keysReading] 逐个对得上，
 * 就显示首选的全拼 [spelling]（shuang'pin），和 26 键双拼一样；首选只盖住前面几个键时，后面没对上的键照打的显示。
 * 一个键都对不上时返回 null，照原来显示双拼键。
 */
internal fun mergedDoublePinyinFullPreedit(raw: String, keysReading: String, spelling: String, groups: List<String>): String? {
    val input = PinyinEditBuffer.normalized(raw).replace("'", "")
    val keys = PinyinEditBuffer.normalized(keysReading).replace("'", "")
    val syllables = spelling.trim().split(' ', '\'').filter { it.isNotEmpty() }
    if (keys.isEmpty() || keys.length > input.length || syllables.isEmpty()) return null
    if (syllables.any { syllable -> syllable.any { it !in 'a'..'z' && it != 'ü' } }) return null
    if (keys.indices.any { groupRepresentative(input[it], groups) != groupRepresentative(keys[it], groups) }) return null
    val rest = input.drop(keys.length)
    return syllables.joinToString("'") + if (rest.isEmpty()) "" else "'$rest"
}

/** 14 键编码条找读音时，除了首选再往后看几个候选。 */
internal const val MERGED14_READING_LOOKAHEAD = 8

/**
 * 依次用 [readings] 里的读音换合并键编码，用第一个把每个键都对上的；都对不上照旧返回 [preedit]（Rime 的原码）。
 * 首选是英文词、混合词或没有读音的短语时，它的注释对不上，后面候选的读音常能对上，不用再显示大写原码。
 */
internal fun merged14PreeditFrom(raw: String, preedit: String, readings: List<String>, groups: List<String> = merged14Groups): String {
    for (reading in readings) {
        if (reading.isBlank()) continue
        val shown = merged14Preedit(raw, preedit, reading, groups)
        if (shown != preedit) return shown
    }
    return preedit
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
