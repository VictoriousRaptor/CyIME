package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 14 键挪光标后删除、新增（11-13 反馈：1.15.68 修过的乱码又回来了）。编辑缓冲显示的是读音，
 * 引擎里是按过的合并键；删改后送进引擎的必须还是按过的键，不能是显示的字母。
 */
class Merged14CaretEditTest {
    @Test
    fun keysFollowTheDisplayedReadingLetterForLetter() {
        // 截图：噢好大家好完完全全，后四个没认出读音，显示的是键上第一个字母 q。
        assertEquals("O'GAO'DA'JUA'GAO'Q'Q'Q'Q",
            merged14KeysOf("o'hao'da'jia'hao'q'q'q'q", "OGAODAJUAGAOQQQQ"))
        // 引擎里的分隔符不算键；先前编辑过的精确字母（小写）原样留着。
        assertEquals("ni'hAO", merged14KeysOf("ni'hao", "ni'hAO"))
        assertNull(merged14KeysOf("ni'hao", "BUGAOX"))
    }

    @Test
    fun deletingInsideKeepsTheOtherKeysMerged() {
        // 光标挪到 hao 后面删一个：剩下的 wwqq 仍是合并键 Q，不变成精确的 qqqq（会出「去去去去」）。
        val text = "o'hao'da'jia'hao'q'q'q'q"
        val keys = merged14KeysOf(text, "OGAODAJUAGAOQQQQ")!!
        val caret = "o'hao'da'jia'hao".length
        val (edited, editedCaret) = PinyinEditBuffer.mergeSeparators(text.removeRange(caret - 1, caret), caret - 1)
        val engine = PinyinEditBuffer.mergeSeparators(keys.removeRange(caret - 1, caret), caret - 1).first
        assertEquals("o'hao'da'jia'ha'q'q'q'q", edited)
        assertEquals("O'GAO'DA'JUA'GA'Q'Q'Q'Q", engine)
        assertEquals(edited.length, engine.length)
        assertEquals(caret - 1, editedCaret)
    }

    @Test
    fun aMergedKeyTappedInsideIsInsertedAsTheMergedKey() {
        assertEquals("q", merged14TapDisplay("qw"))
        assertEquals("Q", merged14TapKey("qw"))
        assertEquals("G", merged14TapKey("gh"))
        assertEquals("l", merged14TapKey("l"))
        assertTrue(MergedLetterInput.isGroup("qw", true))
        val text = "ni'hao"
        val keys = merged14KeysOf(text, "BUGAO")!!
        val caret = 2
        assertEquals("BU" + "Q" + "'GAO", keys.take(caret) + merged14TapKey("qw") + keys.drop(caret))
        // 光标顶在编码最前面（11-13 补充：只有这时才乱）：插在最前面，不跑到末尾。
        assertEquals("QBU'GAO", "" + merged14TapKey("qw") + keys)
        // 显示的读音和插进去的合并键逐位同组，显示照旧对得上。
        assertEquals("ni'q'hao", PinyinEditDisplay("BUQ'GAO", "ni'q'hao", groups = merged14Groups).text)
    }

    @Test
    fun editorSendsPressedKeysAndKeepsMergedTapsInside() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val editor = File(root, "app/src/main/java/com/kingzcheung/xime/service/ImePreeditEditor.kt").readText()
        assertTrue(editor.contains("?: keys?.takeIf { it.length == text.length } ?: text"))
        assertTrue(editor.contains("keys = keys?.removeRange(caret - 1, caret)"))
        val view = File(root, "app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardView.kt").readText()
        assertTrue(view.contains("com.kingzcheung.xime.rime.MergedLetterInput.isGroup(key,\n" +
            "                        state.inputProfile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14"))
    }
}
