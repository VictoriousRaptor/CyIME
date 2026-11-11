package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection

/**
 * 「输入成对符号」的规则（设置里默认关）：点开头的一半时连结尾一起上屏，光标停在中间；
 * 光标后面紧挨着同一个结尾符号时，点结尾只跳过它；光标夹在一对空符号中间时，退格一次删两边。
 * 只收开头、结尾写法不同的符号；" ' 开头结尾同形（英文里还是撇号），< > 是比较号，都不配对。
 */
internal object PairedSymbols {
    private val pairs = mapOf(
        "“" to "”", "‘" to "’", "（" to "）", "(" to ")", "《" to "》", "〈" to "〉",
        "「" to "」", "『" to "』", "【" to "】", "〔" to "〕", "〖" to "〗",
        "［" to "］", "[" to "]", "｛" to "｝", "{" to "}",
    )
    private val closings = pairs.values.toSet()

    fun closingFor(literal: String): String? = pairs[literal]

    fun isClosing(literal: String): Boolean = literal in closings

    /** 键盘上点的一个符号要怎么上屏：开头补上结尾并把光标退回中间，结尾在光标后已有时跳过。 */
    fun commitFor(literal: String): Pair<String, CommitCaret> {
        closingFor(literal)?.let { return literal + it to CommitCaret(caretBack = it.length) }
        if (isClosing(literal)) return literal to CommitCaret(overtype = literal)
        return literal to CommitCaret.NONE
    }

    /** 光标正好夹在一对空符号中间（如 “|”）。 */
    fun isEmptyPairAround(before: CharSequence?, after: CharSequence?): Boolean {
        val open = before?.lastOrNull()?.toString() ?: return false
        val close = pairs[open] ?: return false
        return after?.startsWith(close) == true
    }
}

/**
 * 上屏后的光标处理：[caretBack] 上屏后光标往回退几个字（落在成对符号中间）；
 * [overtype] 光标后紧挨着这段文字时先把它去掉再上屏，效果是光标跳过它。
 */
internal data class CommitCaret(val caretBack: Int = 0, val overtype: String? = null) {
    companion object {
        val NONE = CommitCaret()
    }
}

/** 按 [caret] 上屏：结尾单独上屏并把光标放在它前面（newCursorPosition 0 = 插入文字的开头），整段算一次编辑。 */
internal fun InputConnection.commitTextWithCaret(text: String, caret: CommitCaret): Boolean {
    val back = caret.caretBack.coerceIn(0, text.length)
    beginBatchEdit()
    try {
        caret.overtype?.let { overtype ->
            val after = runCatching { getTextAfterCursor(overtype.length, 0)?.toString() }.getOrNull()
            if (after == overtype && getSelectedText(0).isNullOrEmpty()) deleteSurroundingText(0, overtype.length)
        }
        if (!commitText(text.dropLast(back), 1)) return false
        if (back != 0 && !commitText(text.takeLast(back), 0)) return false
    } finally {
        endBatchEdit()
    }
    if (back != 0) putCaretBackIfIgnored(text, back)
    return true
}

/**
 * 有的编辑框（如 NotallyX 等记事本，#280）不认 newCursorPosition 0，光标落在结尾符号后面。
 * 这时光标前面正好是刚上屏的整段：按实际位置把光标往回挪 [back] 个字，读不到位置就发左方向键。
 */
private fun InputConnection.putCaretBackIfIgnored(text: String, back: Int) {
    val before = runCatching { getTextBeforeCursor(text.length, 0)?.toString() }.getOrNull()
    if (before != text) return
    val extracted = runCatching { getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
    if (extracted != null && extracted.selectionStart == extracted.selectionEnd && extracted.selectionStart >= back) {
        val caret = extracted.startOffset + extracted.selectionStart - back
        setSelection(caret, caret)
        return
    }
    repeat(back) {
        sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
    }
}

/** 光标夹在一对空符号中间时两边一起删掉；不是这种情况返回 false，由调用方照常退格。 */
internal fun InputConnection.deleteEmptyPairAroundCursor(): Boolean {
    if (!getSelectedText(0).isNullOrEmpty()) return false
    val before = runCatching { getTextBeforeCursor(1, 0) }.getOrNull()
    val after = runCatching { getTextAfterCursor(1, 0) }.getOrNull()
    if (!PairedSymbols.isEmptyPairAround(before, after)) return false
    return deleteSurroundingText(1, 1)
}
