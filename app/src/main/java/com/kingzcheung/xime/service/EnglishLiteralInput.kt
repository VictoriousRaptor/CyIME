package com.kingzcheung.xime.service

import android.content.Context
import android.text.TextUtils
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.settings.SettingsPreferences

/** a–z typed straight into the editor; on a [com.kingzcheung.xime.settings.LetterLanguage] keyboard also its [extraLetters] (ñ, ä, é, й…). */
internal fun isDirectLetter(key: String, extraLetters: String): Boolean = key.length == 1 &&
    (key[0] in 'a'..'z' || key[0] in 'A'..'Z' || key[0] in extraLetters)

/** English letters are literal editor input, never a request to the Chinese decoder. */
internal fun commitEnglishLetter(
    state: CandidateState,
    key: String,
    shifted: Boolean,
    replaceSupported: () -> Boolean,
    /** 光标紧跟刚滑行上屏的词：这个字母开始新词，前面补空格。 */
    afterGlide: Boolean = false,
    commit: (String) -> Boolean,
): CandidateState? {
    require(key.length == 1 && key[0].isLetter())
    // ß 没有单个大写字母（uppercase 是 SS）：Shift 时照旧打 ß。
    val text = if (shifted) key.uppercase().takeIf { it.length == 1 } ?: key else key.lowercase()
    if (!commit(if (afterGlide) " $text" else text)) return null
    return state.copy(
        inputText = "", preeditText = "", isComposing = false,
        candidates = emptyList(), candidateComments = emptyList(), candidateActions = emptyList(),
        pendingEnglishText = (if (afterGlide) "" else state.pendingEnglishText) + text, glidedWord = "",
        // Deliver the letter before asking the host about optional completion support.
        associationCandidates = emptyList(), englishReplaceSupported = replaceSupported(),
        hasNextPage = false, hasPrevPage = false, isShowingRecentClipboard = false,
    )
}

/**
 * 「英文单词」方案（字母先进组字区，从候选里选词上屏）正在打单词，不是它的 ABC 状态：
 * 「首字母大写」「选词后自动加空格」「标点后自动加空格」这些英文开关同样要管（#311：以前只管英文直接输入）。
 */
internal fun typesEnglishWords(profile: com.kingzcheung.xime.settings.InputProfile, asciiMode: Boolean): Boolean =
    !asciiMode && profile.language == com.kingzcheung.xime.settings.InputLanguage.ENGLISH &&
        profile.scheme == com.kingzcheung.xime.settings.InputScheme.WORDS

/**
 * 英文单词方案里首字母是自动大写打出来的，回车上屏后记进英文词记忆时首字母还原成小写（Xyzw 记 xyzw），
 * 免得句中再打时也给大写；后面还有别的大写字母（McDonald、NASA）是自己打的，照原样记。
 */
internal fun spellingWithoutAutoCap(input: String): String =
    if (input.length > 1 && input[0].isUpperCase() && input.drop(1).none { it.isUpperCase() })
        input.replaceFirstChar { it.lowercaseChar() } else input

/** 英文单词方案选词后自动加的空格只跟在词后面：候选里没有字母（标点、数字、符号）不加。 */
internal fun takesWordsAutoSpace(text: String): Boolean = text.isNotEmpty() && text.any { it.isLetter() } && !text.last().isWhitespace()

/** 「首字母大写」：英文和西、德、法、意、俄语键盘共用这一个开关（键名沿用英文时的旧名，已有的开关状态不变）。 */
internal const val ENGLISH_SENTENCE_CAPS = "english_sentence_caps"

/**
 * Ask the editor about the real insertion point, including text the user moved into or selected.
 * [wordStart]：光标前不是正在打的词，才再看一眼光标前是不是 ¿ ¡ « „ 这些句首开头符号（打到词中间不多读输入框）。
 */
internal fun englishSentenceStart(context: Context, connection: InputConnection?, wordStart: Boolean = false): Boolean {
    if (!SettingsPreferences.getPrefsPublic(context).getBoolean(ENGLISH_SENTENCE_CAPS, false)) return false
    val sentences = TextUtils.CAP_MODE_SENTENCES
    return try {
        // TextUtils returns CAP_MODE_WORDS at the start of a paragraph even when only
        // sentences were requested. Requesting both would also capitalize ordinary words.
        val mode = connection?.getCursorCapsMode(sentences) ?: 0
        mode and (sentences or TextUtils.CAP_MODE_WORDS) != 0 ||
            wordStart && connection != null && sentenceStartsAfterOpener(connection.getTextBeforeCursor(OPENER_CONTEXT, 0))
    } catch (_: RuntimeException) {
        false // Some editors close their connection while switching fields.
    }
}

/** 句首可以先打的开头符号：西班牙语 ¿ ¡，法、俄语 « ‹，德语 „，英文 “ ‘。系统只跳过 " ' ( [ {，跳不过这些。 */
private const val SENTENCE_OPENERS = "¿¡«‹„“‘\"'([{"
private const val SENTENCE_CLOSERS = "\"')]}»›”’"
private const val OPENER_CONTEXT = 32

/**
 * 光标紧跟句首开头符号（«Привет、¿Qué、„Hallo）：把这些符号去掉后按系统同一套规则看是不是句首，
 * 即输入框开头、换行后，或 . ? ! （后面可跟引号、括号）再加空格之后；a.m. 这类缩写的点不算句末。
 */
internal fun sentenceStartsAfterOpener(before: CharSequence?): Boolean {
    if (before == null || before.isEmpty() || before.last() !in SENTENCE_OPENERS) return false
    var i = before.length
    while (i > 0 && before[i - 1] in SENTENCE_OPENERS) i--
    var j = i
    while (j > 0 && (before[j - 1] == ' ' || before[j - 1] == '\t')) j--
    // 读到的这一段全是开头符号和空格：要么是输入框开头，要么前面更远处还有字，按读到的长度判断。
    if (j == 0) return before.length < OPENER_CONTEXT
    if (before[j - 1] == '\n') return true
    if (i == j) return false // 开头符号紧贴着前面的字（a¿）：不是新句子。
    while (j > 0 && before[j - 1] in SENTENCE_CLOSERS) j--
    if (j == 0) return false
    val end = before[j - 1]
    if (end == '?' || end == '!') return true
    if (end != '.') return false
    // 和系统一样：点前面的词里还有点（a.m.、e.g.）是缩写，不算句末。
    var k = j - 2
    while (k >= 0 && before[k].isLetter()) k--
    return k < 0 || before[k] != '.'
}
