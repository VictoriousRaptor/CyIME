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
    if (back != 0) dropClosingIfCaretIgnored(text, back)
    return true
}

/**
 * 有的编辑框不认 newCursorPosition 0，光标落在补上的结尾后面（#280，NotallyX 等记事本）。
 * 原来这里再用 setSelection／左方向键把光标挪回中间，但这类编辑框自己记的插入位置没跟着变：
 * 接着打一个字以后光标又跳到结尾外面，拼音只剩第一个字母（11-14 用户反馈）。
 * 这类编辑框里没法可靠地把光标放进一对符号中间，所以把刚补上的结尾去掉，只留用户点的开头，
 * 和没开「输入成对符号」时一样；结尾由用户自己打，不会多出一个。正常的编辑框（光标前面不是整段）不受影响。
 */
private fun InputConnection.dropClosingIfCaretIgnored(text: String, back: Int) {
    val before = runCatching { getTextBeforeCursor(text.length, 0)?.toString() }.getOrNull()
    if (before != text || !getSelectedText(0).isNullOrEmpty()) return
    deleteSurroundingText(back, 0)
}

/** 光标夹在一对空符号中间时两边一起删掉；不是这种情况返回 false，由调用方照常退格。 */
internal fun InputConnection.deleteEmptyPairAroundCursor(): Boolean {
    if (!getSelectedText(0).isNullOrEmpty()) return false
    val before = runCatching { getTextBeforeCursor(1, 0) }.getOrNull()
    val after = runCatching { getTextAfterCursor(1, 0) }.getOrNull()
    if (!PairedSymbols.isEmptyPairAround(before, after)) return false
    return deleteSurroundingText(1, 1)
}
