package com.kingzcheung.xime.service

import com.kingzcheung.xime.speech.cleanSpeechText
import com.kingzcheung.xime.settings.InputLanguage

private val dictatedPunctuation = Regex(" *(逗号|句号|问号|感叹号|叹号) *")
/** With model marks kept, a dictated mark replaces the marks the model put right next to it. */
private val dictatedWithMarks = Regex("[，。？！、；：]*\\s*(逗号|句号|问号|感叹号|叹号)\\s*[，。？！、；：]*")

/**
 * Which of the model's marks 自动加标点 keeps; a dropped kind becomes spacing. Dictated marks always stay.
 * [trailing] = false drops only the model's marks at the very end of the dictation (every result is the whole text so far).
 */
internal data class VoiceMarks(val comma: Boolean = true, val period: Boolean = true, val question: Boolean = true,
    val trailing: Boolean = true) {
    val all get() = comma && period && question
}

private val trailingMarks = Regex("[，。？！、；：,.;:?!\\s]+$")

private val spacing = Regex(" {2,}")

/** Drops the model marks of the kinds turned off; numeric '.', ',' and ':' between digits stay. */
private fun dropMarks(text: String, marks: VoiceMarks): String {
    val kept = dropKinds(text, marks)
    return if (marks.trailing) kept else trailingMarks.replace(kept, "")
}

private fun dropKinds(text: String, marks: VoiceMarks): String {
    if (marks.all) return text
    val out = buildString {
        text.forEachIndexed { i, c ->
            val keep = when (c) {
                '，', '、', '；', '：', ',', ';', ':' -> marks.comma
                '。', '.' -> marks.period
                '？', '！', '?', '!' -> marks.question
                else -> true
            } || c in ".,:" && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true
            append(if (keep) c else ' ')
        }
    }
    return spacing.replace(out, " ").trim()
}

/**
 * Call once on incoming ASR text, never on already rendered partial text.
 * [keepMarks]: 整句定稿 keeps the model's sentence/clause marks instead of turning them into spaces;
 * [marks] picks which kinds stay (自动加标点 → 逐项); [arabicNumbers]: 数字转阿拉伯数字 (Chinese only).
 */
internal fun normalizeVoiceText(text: String, language: InputLanguage = InputLanguage.CHINESE, keepMarks: Boolean = false,
    marks: VoiceMarks = VoiceMarks(), arabicNumbers: Boolean = false): String {
    val clean = cleanSpeechText(text, keepMarks).let { if (keepMarks) dropMarks(it, marks) else it }
    if (language != InputLanguage.CHINESE) return clean
    val punctuated = (if (keepMarks) dictatedWithMarks else dictatedPunctuation).replace(clean) {
        when (it.groupValues[1]) {
            "逗号" -> "，"
            "句号" -> "。"
            "问号" -> "？"
            else -> "！"
        }
    }
    return if (arabicNumbers) spokenNumbersToDigits(punctuated) else punctuated
}

/** 汉字、假名、中日文标点和全角符号：挨着它们的空格是停顿留下的，不是词与词之间的空格。 */
internal fun isCjkText(c: Char): Boolean = c in '　'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' ||
    c in '豈'..'﫿' || c in '＀'..'￯'

/**
 * 「停顿处加空格」关掉时：去掉挨着中文（汉字、假名、全角标点）的空格；英文等单词之间的空格照留，
 * 「I love you」不会粘成一个词，韩文按词空格也不动。
 */
internal fun withoutCjkSpaces(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != ' ') { append(c); i++; continue }
        var next = i
        while (next < text.length && text[next] == ' ') next++
        val left = lastOrNull()
        val right = text.getOrNull(next)
        if (left != null && right != null && !isCjkText(left) && !isCjkText(right)) append(' ')
        i = next
    }
}
