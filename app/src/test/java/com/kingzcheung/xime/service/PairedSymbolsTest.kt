package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 「输入成对符号」：用户 10-30 要的设置项（默认关）；这里只验证开着时的上屏与退格。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairedSymbolsTest {
    private val field = EditText(ApplicationProvider.getApplicationContext())
    private val connection: InputConnection = field.onCreateInputConnection(EditorInfo())

    private fun type(literal: String) {
        val (text, caret) = PairedSymbols.commitFor(literal)
        assertTrue(connection.commitTextWithCaret(text, caret))
    }

    /** 用 | 标出光标。 */
    private fun shown(): String = field.text.toString().let {
        it.substring(0, field.selectionStart) + "|" + it.substring(field.selectionEnd)
    }

    @Test fun openingHalvesCommitBothHalvesWithTheCaretBetween() {
        listOf("“" to "”", "（" to "）", "《" to "》", "【" to "】", "(" to ")").forEach { (open, close) ->
            field.setText("")
            type(open)
            assertEquals("$open|$close", shown())
        }
    }

    @Test fun typingTheClosingHalfStepsOverTheOneAlreadyThere() {
        type("“"); type("好"); type("”")
        assertEquals("“好”|", shown())
        // 光标后面不是这个结尾时照常输入。
        type("”")
        assertEquals("“好””|", shown())
    }

    @Test fun nestedPairsCloseInOrder() {
        type("《"); type("（"); type("一"); type("）"); type("》")
        assertEquals("《（一）》|", shown())
    }

    @Test fun otherSymbolsAndSameShapedQuotesAreNotPaired() {
        listOf("，", "\"", "'", "<", "……").forEach { literal ->
            assertEquals(literal to CommitCaret.NONE, PairedSymbols.commitFor(literal))
        }
    }

    @Test fun backspaceBetweenAnEmptyPairDeletesBothHalves() {
        type("“")
        assertTrue(connection.deleteEmptyPairAroundCursor())
        assertEquals("|", shown())
    }

    @Test fun backspaceElsewhereIsLeftToTheNormalDeleteKey() {
        type("“"); type("好")
        assertFalse(connection.deleteEmptyPairAroundCursor())
        assertEquals("“好|”", shown())
        field.setText("（）")
        field.setSelection(2)
        assertFalse(connection.deleteEmptyPairAroundCursor())
    }

    /** #280：编辑框不认 newCursorPosition 0（光标总落在上屏文字后面）时，照样落在一对符号中间。 */
    @Test fun editorsThatIgnoreTheCaretPositionStillEndBetweenThePair() {
        val ignoring = object : android.view.inputmethod.InputConnectionWrapper(connection, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = super.commitText(text, 1)
        }
        fun typeInto(literal: String) {
            val (text, caret) = PairedSymbols.commitFor(literal)
            assertTrue(ignoring.commitTextWithCaret(text, caret))
        }
        typeInto("《")
        assertEquals("《|》", shown())
        typeInto("好")
        assertEquals("《好|》", shown())
        typeInto("》")
        assertEquals("《好》|", shown())
    }

    @Test fun aSelectionIsReplacedByThePairLikeAnyTypedSymbol() {
        field.setText("你好")
        field.setSelection(0, 2)
        type("“")
        assertEquals("“|”", shown())
    }
}
