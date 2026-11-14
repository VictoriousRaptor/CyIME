package com.kingzcheung.xime.service

internal fun canPredictAfter(text: String): Boolean = text.lastOrNull()?.isLetter() == true

/**
 * 连续联想什么时候停：选了联想词之后，下一批里最有把握的那个够不够可能是接下来要打的。
 * 分数是联想表的 P(下一个词 | 前文)（乘 0.7，再加个人学习），句末语气词后、词拼不成句时都很低。
 * 不够就这一批不出，等用户自己打字（打了字照常出联想）。只看分数，不数选了几次：
 * 离线模拟（公开短信 1500 条 + 两套日常语料，前 5 个里有下一个词才算选中），首选分数低于 0.03 的那批只有 7% 接得上，
 * 四成多是一句已经说完了；同样少出约 490 批，「只连两次」少选中 60 个词，按分数只少 34 个。
 */
internal const val ASSOCIATION_CHAIN_MIN_SCORE = 0.03f

internal fun continuesAssociationChain(candidates: List<com.kingzcheung.xime.association.AssociationCandidate>): Boolean =
    (candidates.maxOfOrNull { it.score } ?: 0f) >= ASSOCIATION_CHAIN_MIN_SCORE

/**
 * 这次上屏的以两个以上汉字结尾（整句、词、语音）：最后一个字已是一个词的末尾，联想只接下一个词，
 * 不再把它补成以它开头的长词（「你在吃饭莫」后不出“莫名其妙”）。单个字上屏照旧，可以补成词（我→们）。
 */
internal fun endsWithWholeWord(committed: String): Boolean =
    committed.takeLastWhile { it in '一'..'鿿' }.length >= 2

internal fun isLiteralPunctuation(key: String): Boolean = key.length == 1 && when (key[0].category) {
    CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
    CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
    CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
    CharCategory.OTHER_PUNCTUATION, CharCategory.MATH_SYMBOL,
    CharCategory.CURRENCY_SYMBOL, CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL -> true
    else -> false
}

/**
 * 「标点上屏首选」管的标点：断句用的逗号、句号、问号、感叹号、顿号、分号、冒号和省略号，全角半角都算。
 * 括号、引号、@、# 这些不管，照旧先上屏打的字母。
 */
internal fun isSentencePunctuation(text: String): Boolean =
    text.isNotEmpty() && text.all { it in ",.?!;:，。？！、；：…" }

/**
 * 日语罗马字的长音 -（26 键 L 右侧或 Shift 位置的 ー 键送出）：方案字母表里有它，ka- → かー，
 * 交给按键队列送进转换，不按标点直接上屏。只认屏幕键盘点按送来的 -；角标、符号面板走直接上屏那条路，不经过这里。
 */
internal fun japaneseChoonpuIsCode(key: String, ascii: Boolean, profile: com.kingzcheung.xime.settings.InputProfile): Boolean =
    key == "-" && !ascii && profile.capabilities.japaneseConversion

/** Keyboard punctuation only: never rewrite pasted prose, digits, emoji or speech results. */
internal fun punctuationWidth(text: String, full: Boolean, japanese: Boolean = false): String {
    if (text.isEmpty() || !text.all { isLiteralPunctuation(it.toString()) }) return text
    return buildString {
        for (c in text) {
            if (full) {
                append(when (c) {
                    ',' -> if (japanese) '、' else '，'; '.' -> '。'; '｡' -> '。'; '､' -> '、'
                    '｢' -> '「'; '｣' -> '」'; '･' -> '・'
                    in '!'..'~' -> (c.code + 0xFEE0).toChar()
                    else -> c
                })
            } else {
                append(when (c) {
                    '。', '｡' -> '.'; '、', '､' -> ','; '・' -> '･'
                    '“', '”', '「', '」', '『', '』', '｢', '｣' -> '"'
                    '‘', '’' -> '\''
                    '【', '〔' -> '['; '】', '〕' -> ']'
                    '《', '〈' -> '<'; '》', '〉' -> '>'
                    in '！'..'～' -> (c.code - 0xFEE0).toChar()
                    else -> c
                })
            }
        }
    }
}

/** Japanese 英数 letters in full width (Ａ–Ｚ, ａ–ｚ); romaji inside Japanese text stays ASCII. */
internal fun fullWidthLetters(text: String): String =
    text.map { c -> if (c in 'a'..'z' || c in 'A'..'Z') (c.code + 0xFEE0).toChar() else c }.joinToString("")

/** Only keyboard literals use this policy; pasted text, speech and candidate words stay intact. */
internal fun keyboardLiteralWidth(text: String, full: Boolean, japanese: Boolean, numberPanel: Boolean = false, preserveWidth: Boolean = false,
    chineseKeyboard: Boolean = false, before: CharSequence? = null): String {
    if (preserveWidth) return text
    // 中文屏幕键盘的符号都是固定字符，印什么上屏什么；只有空格两侧的逗号句号由键盘按全角/半角给出。
    if (!japanese && chineseKeyboard) return text
    if (!japanese) return if (numberPanel) text else punctuationWidth(text, full)
    // Numeric-panel punctuation is arithmetic (a decimal point is not a Japanese full stop).
    if (numberPanel) return text.map { c ->
        when {
            full && c == ' ' -> '\u3000'
            full && c in '!'..'~' -> (c.code + 0xFEE0).toChar()
            !full && c == '\u3000' -> ' '
            !full && c in '！'..'～' -> (c.code - 0xFEE0).toChar()
            else -> c
        }
    }.joinToString("")
    return punctuationWidth(text, full, japanese = true).map { c ->
        when {
            full && c == ' ' -> '\u3000'
            full && c in '0'..'9' -> (c.code + 0xFEE0).toChar()
            !full && c == '\u3000' -> ' '
            !full && c in '０'..'９' -> (c.code - 0xFEE0).toChar()
            else -> c
        }
    }.joinToString("")
}

/** 拼音条里表示光标的竖线。 */
internal const val PREEDIT_CARET = '|'

/**
 * 在显示的拼音里插入光标竖线。[input] 是引擎原始编码，[caret] 是光标在其中的位置，
 * [confirmed] 之前是已选定的部分。只要显示末尾的字母与光标后的编码一一对应就标，
 * 对不上（如合并键位改写了显示）或光标在末尾时返回 null。
 */
internal fun markPreeditCaret(preedit: String, input: String, caret: Int, confirmed: Int): String? {
    if (caret < 0 || caret >= input.length || caret < confirmed || preedit.isEmpty()) return null
    val tail = input.drop(confirmed)
    fun separator(c: Char) = c == ' ' || c == '\''
    // 从末尾往前数出未选定编码对应的显示字符，确认它们就是这些字母。
    // Rime 只转换光标前的编码，光标后原样接在末尾；只核对这一段，双拼也能标出光标。
    var index = preedit.length
    var matched = 0
    val after = tail.length - (caret - confirmed)
    while (index > 0 && matched < after) {
        val c = preedit[index - 1]
        if (separator(c)) { index--; continue }
        if (!c.equals(tail[tail.length - 1 - matched], ignoreCase = true)) return null
        matched++
        index--
    }
    if (matched != after) return null
    var insertAt = index
    // 光标落在音节分隔处时，竖线放在分隔符之前，紧贴前一个字母。
    while (insertAt > 0 && separator(preedit[insertAt - 1])) insertAt--
    return preedit.substring(0, insertAt) + PREEDIT_CARET + preedit.substring(insertAt)
}
