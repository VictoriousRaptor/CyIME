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

    /**
     * 编辑框不认 newCursorPosition 0（光标总落在上屏文字后面，#280）：没法可靠地停在中间，
     * 只上屏开头，不补结尾（11-14：挪回中间以后打一个字光标又跳出去）。接着打字留在原处，结尾自己打也不会多一个。
     */
    @Test fun editorsThatIgnoreTheCaretPositionGetOnlyTheOpeningHalf() {
        val ignoring = object : android.view.inputmethod.InputConnectionWrapper(connection, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = super.commitText(text, 1)
        }
        fun typeInto(literal: String) {
            val (text, caret) = PairedSymbols.commitFor(literal)
            assertTrue(ignoring.commitTextWithCaret(text, caret))
        }
        typeInto("《")
        assertEquals("《|", shown())
        typeInto("好")
        typeInto("的")
        assertEquals("《好的|", shown())
        typeInto("》")
        assertEquals("《好的》|", shown())
    }

    /** 正常编辑框：括号里连着打几个字都留在里面，打结尾才跳出去，不重复加（用户 11-14）。 */
    @Test fun typingSeveralCharactersStaysInsideUntilTheClosingHalf() {
        type("（"); type("n"); type("i"); type("好")
        assertEquals("（ni好|）", shown())
        type("）")
        assertEquals("（ni好）|", shown())
    }

    @Test fun aSelectionIsReplacedByThePairLikeAnyTypedSymbol() {
        field.setText("你好")
        field.setSelection(0, 2)
        type("“")
        assertEquals("“|”", shown())
    }
}
