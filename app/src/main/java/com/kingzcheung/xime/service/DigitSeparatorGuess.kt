package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection

/**
 * 数字后面的句号、逗号（11-25 他：小数点、千分位要半角，句子里数字后面的标点要全角，不用删了重打）。
 *
 * 中文全角标点下，光标前是数字时点「。」「，」先上半角 . ,（3.14、1,000 照常打）；下一个上屏的：
 * - 是数字：留着半角；
 * - 是别的（汉字、标点、空格、回车、发送）：先把那个半角改成全角 。，再上屏它；
 * - 是同一个键再点一次（「。」「，」）：只把半角改成全角，不再多一个。
 * 改完紧接着按退格是撤销这次改动：改回半角，连同改动时跟着上屏的空格、换行一起退掉。
 * 改之前都核对光标前确实是「数字 + 刚上的那个半角」，光标挪过、应用改过文字就不动。
 */
internal class DigitSeparatorGuess {
    /** 刚上屏、还没定的半角 . 或 ,。 */
    private var pending: Char? = null
    /** 刚改成全角的：光标前是 [Undo.shown] 时退格改回 [Undo.half]。 */
    private var undo: Undo? = null

    private data class Undo(val shown: String, val half: String, val at: Long)

    val isPending: Boolean get() = pending != null

    fun arm(half: Char) {
        pending = half
        undo = null
    }

    fun reset() {
        pending = null
        undo = null
    }

    /**
     * 上屏 [text] 之前调用。返回 true 表示这次上屏已经由这里办完（同一个键再点：半角改成全角，不再上屏 [text]）。
     */
    fun beforeCommit(ic: InputConnection, text: String, now: Long): Boolean {
        undo = null
        val half = pending ?: return false
        pending = null
        if (text.isEmpty() || text[0].isAsciiOrWideDigit()) return false
        val full = fullFor(half)
        if (!ic.endsWithDigitAnd(half)) return false
        val sameKey = text == full.toString()
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(full.toString(), 1)
        } finally {
            ic.endBatchEdit()
        }
        undo = when {
            sameKey -> Undo(full.toString(), half.toString(), now)
            text == " " || text == "\n" -> Undo(full + text, half.toString(), now)
            else -> null
        }
        return sameKey
    }

    /** 回车发送、换行之前：数字后面那个半角按句末标点改成全角（发出去的是「5。」）。 */
    fun beforeEnter(ic: InputConnection, newline: Boolean, now: Long) {
        undo = null
        val half = pending ?: return
        pending = null
        if (!ic.endsWithDigitAnd(half)) return
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(fullFor(half).toString(), 1)
        } finally {
            ic.endBatchEdit()
        }
        if (newline) undo = Undo(fullFor(half) + "\n", half.toString(), now)
    }

    /** 退格：紧接着自动改动之后（3 秒内、光标前还是改出来的样子）改回半角，返回 true；否则照常退格。 */
    fun undoOnDelete(ic: InputConnection, now: Long): Boolean {
        val last = undo ?: return false
        undo = null
        pending = null
        if (now - last.at > UNDO_WINDOW_MS) return false
        val before = runCatching { ic.getTextBeforeCursor(last.shown.length, 0)?.toString() }.getOrNull()
        if (before != last.shown) return false
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(last.shown.length, 0)
            ic.commitText(last.half, 1)
        } finally {
            ic.endBatchEdit()
        }
        // 撤销过就是要半角：后面再打什么都不再改。
        return true
    }

    companion object {
        const val UNDO_WINDOW_MS = 3000L

        fun fullFor(half: Char): Char = if (half == '.') '。' else '，'

        /** 中文键盘的「。」「，」点在数字后面时先上的半角；别的字返回 null。 */
        fun halfFor(key: String): Char? = when (key) {
            "。" -> '.'
            "，" -> ','
            else -> null
        }

        fun digitBefore(before: CharSequence?): Boolean = before?.lastOrNull()?.isAsciiOrWideDigit() == true

        private fun Char.isAsciiOrWideDigit() = this in '0'..'9' || this in '０'..'９'

        private fun InputConnection.endsWithDigitAnd(half: Char): Boolean {
            val before = runCatching { getTextBeforeCursor(2, 0)?.toString() }.getOrNull() ?: return false
            return before.length == 2 && before[1] == half && before[0].isAsciiOrWideDigit()
        }
    }
}
