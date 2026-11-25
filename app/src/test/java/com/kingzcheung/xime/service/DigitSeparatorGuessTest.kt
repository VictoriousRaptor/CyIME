package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 数字后的句号、逗号（11-25 他：小数点、千分位要半角，句子里数字后面接的标点要全角，不用删了重打）。
 * 这里按「输入法上屏前先问一下 [DigitSeparatorGuess]」的顺序模拟一个输入框。
 */
class DigitSeparatorGuessTest {
    /** 光标一直在末尾的输入框：只实现上屏、往前删、读光标前。 */
    private class Field(var text: String = "") {
        val ic: InputConnection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> text.takeLast(args[0] as Int)
                "deleteSurroundingText" -> { text = text.dropLast(args[0] as Int); true }
                "commitText" -> { text += args[0].toString(); true }
                "beginBatchEdit", "endBatchEdit" -> true
                else -> null
            }
        } as InputConnection
    }

    private val guess = DigitSeparatorGuess()
    private var now = 1000L

    /** 输入法上屏一段字：先问 guess，再上屏（同一个键再点时 guess 已经办完）。 */
    private fun Field.type(s: String) {
        if (!guess.beforeCommit(ic, s, now)) text += s
        now += 100
    }

    /** 点中文键盘的「。」「，」：光标前是数字时先上半角并记下。 */
    private fun Field.punct(key: String) {
        val half = DigitSeparatorGuess.halfFor(key)
        if (half != null && DigitSeparatorGuess.digitBefore(text)) { type(half.toString()); guess.arm(half) } else type(key)
    }

    @Test fun decimalAndThousandsStayHalfWidth() {
        val f = Field()
        f.type("3"); f.punct("。"); f.type("1"); f.type("4")
        assertEquals("3.14", f.text)
        val g = Field()
        g.type("1"); g.punct("，"); g.type("0"); g.type("0"); g.type("0")
        assertEquals("1,000", g.text)
    }

    @Test fun aChineseWordAfterTurnsItIntoChinesePunctuation() {
        val f = Field("共3个")
        f.punct("。")
        assertEquals("共3个。", f.text)
        val g = Field("我有3")
        g.punct("，"); g.type("你有")
        assertEquals("我有3，你有", g.text)
        // 已经在文字里的数字（不是刚打的）也一样。
        val h = Field("第2条在这里写着5")
        h.punct("。"); h.type("下")
        assertEquals("第2条在这里写着5。下", h.text)
    }

    @Test fun otherPunctuationSpaceAndSameKeyAgain() {
        val f = Field("5")
        f.punct("，"); f.type("“")
        assertEquals("5，“", f.text)
        val g = Field("5")
        g.punct("。"); g.punct("。")
        assertEquals("同一个键再点只改不加", "5。", g.text)
        val h = Field("5")
        h.punct("。"); h.type(" ")
        assertEquals("5。 ", h.text)
    }

    @Test fun backspaceRightAfterTheChangeUndoesIt() {
        val f = Field("5")
        f.punct("。"); f.type(" ")
        assertTrue(guess.undoOnDelete(f.ic, now))
        assertEquals("5.", f.text)
        // 撤销过就留着半角，后面打汉字不再改。
        f.type("版")
        assertEquals("5.版", f.text)
        val g = Field("5")
        g.punct("。"); g.punct("。")
        assertTrue(guess.undoOnDelete(g.ic, now))
        assertEquals("5.", g.text)
        // 改成全角是被汉字带出来的：退格照常删汉字。
        val h = Field("5")
        h.punct("。"); h.type("好")
        assertFalse(guess.undoOnDelete(h.ic, now))
        // 过了一会儿、或中间又打了别的：退格照常。
        val k = Field("5")
        k.punct("。"); k.type(" ")
        assertFalse(guess.undoOnDelete(k.ic, now + DigitSeparatorGuess.UNDO_WINDOW_MS + 1))
    }

    @Test fun leavesTheFieldAloneWhenItChanged() {
        val f = Field("5")
        f.punct("。")
        f.text = "5.x"   // 光标挪了、应用改了文字
        f.type("好")
        assertEquals("5.x好", f.text)
        // 光标前不是数字：「。」照常是全角，什么都不记。
        val g = Field("好")
        g.punct("。"); g.type("吗")
        assertEquals("好。吗", g.text)
    }

    @Test fun enterSendsChinesePunctuation() {
        val f = Field("价格是5")
        f.punct("。")
        guess.beforeEnter(f.ic, newline = false, now = now)
        assertEquals("价格是5。", f.text)
    }
}
