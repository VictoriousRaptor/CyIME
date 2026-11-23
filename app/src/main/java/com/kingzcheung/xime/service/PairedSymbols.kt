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

    /** 每补一次结尾加一；后面又补了一次时，前一次的延后检查作废。 */
    @Volatile internal var commitGeneration = 0L

    /**
     * 补上结尾后延后多久查编辑框认没认光标位置；测试里换成手动执行。
     * 不认的编辑框（NotallyX 等）要等它自己处理完这次上屏再挪光标，马上挪会被它改回去（11-14：打一个字光标又跳出去）。
     * 300 ms 是雨燕输入法在 NotallyX 里实际可用的延时（#489 用户 11-21 对比）。
     */
    internal var deferCaretCheck: (() -> Unit) -> Unit = { check ->
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ check() }, CARET_CHECK_DELAY_MS)
    }
    private const val CARET_CHECK_DELAY_MS = 300L

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
    // 跳过结尾也算一次新的上屏：上一次补结尾的延后检查作废。不然 300 ms 内点「(」再点「)」，光标前正好是 ()，
    // 会被当成编辑框不认光标、又挪回括号中间（11-22 审查）。
    if (caret.overtype != null) PairedSymbols.commitGeneration++
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
    if (back != 0) {
        // 不能上屏后马上读：有的编辑框（网页、部分跨平台框架）读回的是还没处理完的旧状态，光标看着落在结尾后面
        // （11-16 用户反馈：右半出来又马上没了）。等编辑框处理完再查。
        val generation = ++PairedSymbols.commitGeneration
        PairedSymbols.deferCaretCheck {
            if (generation == PairedSymbols.commitGeneration && caretSitsAfter(text)) {
                if (moveCaretBackBySelection(back)) {
                    // 有的网页编辑框不认 setSelection：再等一下，还在后面就按左方向键。
                    PairedSymbols.deferCaretCheck {
                        if (generation == PairedSymbols.commitGeneration && caretSitsAfter(text)) sendLeftKeys(back)
                    }
                } else {
                    sendLeftKeys(back)
                }
            }
        }
    }
    return true
}

/**
 * 有的编辑框不认 newCursorPosition 0，光标落在补上的结尾后面（#280，NotallyX 等记事本；#436 浏览器）。
 * 光标前面正好是刚上屏的整段、没有选中文字时才算。正常的编辑框（光标在中间）不受影响。
 */
private fun InputConnection.caretSitsAfter(text: String): Boolean {
    val before = runCatching { getTextBeforeCursor(text.length, 0)?.toString() }.getOrNull()
    return before == text && getSelectedText(0).isNullOrEmpty()
}

/**
 * 等编辑框处理完上屏以后，按它报的实际位置把光标往回挪 [back] 个字，落在一对符号中间。
 * 以前在这类编辑框里马上挪（#280），它随后又改回去，打一个字光标就跳出去（11-14），于是改成去掉结尾（#489）；
 * 等它处理完再挪就稳了（雨燕输入法同样做法，用户 11-21 说在 NotallyX 里正常）。读不到位置返回 false。
 */
private fun InputConnection.moveCaretBackBySelection(back: Int): Boolean {
    val extracted = runCatching { getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
        ?: return false
    if (extracted.selectionStart != extracted.selectionEnd || extracted.selectionStart < back) return false
    val caret = extracted.startOffset + extracted.selectionStart - back
    return setSelection(caret, caret)
}

private fun InputConnection.sendLeftKeys(back: Int) {
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
