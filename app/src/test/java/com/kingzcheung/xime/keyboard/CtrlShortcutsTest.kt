package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CtrlShortcutsTest {
    @Test
    fun selectAllCopyCutPasteUseEditPanelActions() {
        assertEquals("select_all", CtrlShortcuts.editingAction('a'))
        assertEquals("select_all", CtrlShortcuts.editingAction('A'))
        assertEquals("copy", CtrlShortcuts.editingAction('c'))
        assertEquals("cut", CtrlShortcuts.editingAction('x'))
        assertEquals("paste", CtrlShortcuts.editingAction('v'))
    }

    @Test
    fun otherLettersStayCtrlKeyEvents() {
        // 撤销/重做与编辑面板一样仍是 Ctrl+Z/Y 按键；其余字母交给应用自己处理。
        for (letter in listOf('z', 'y', 's', 'f', 'w', 't', 'b', 'q')) assertNull(CtrlShortcuts.editingAction(letter))
    }

    @Test
    fun enterHasItsOwnCommand() {
        assertEquals(CtrlShortcuts.ENTER_COMMAND, CtrlShortcuts.command(CtrlShortcuts.ENTER))
        // ctrl:enter 不是字母命令，不会被当成 Ctrl+E 之类发出去。
        assertNull(CtrlShortcuts.letterOf(CtrlShortcuts.ENTER_COMMAND))
        assertEquals("ctrl:c", CtrlShortcuts.command('C'))
    }

    // 用户 11-13：按住 !@# 点删除是 Ctrl+删除，有自己的命令，不会被当成字母。
    @Test
    fun backspaceHasItsOwnCommandAndCtrlKeyEvents() {
        assertEquals(CtrlShortcuts.BACKSPACE_COMMAND, CtrlShortcuts.command(CtrlShortcuts.BACKSPACE))
        assertNull(CtrlShortcuts.letterOf(CtrlShortcuts.BACKSPACE_COMMAND))
    }

    /** 用户 11-03、11-14：九键 1 关闭 2 刷新 3 重做 4 全选 5 剪切 6 查找 7 撤销 8 复制 9 粘贴。 */
    @Test
    fun t9DigitsMapToTheRequestedShortcuts() {
        val expected = mapOf('1' to "关闭", '2' to "刷新", '3' to "重做", '4' to "全选", '5' to "剪切",
            '6' to "查找", '7' to "撤销", '8' to "复制", '9' to "粘贴")
        for ((digit, label) in expected) {
            val letter = CtrlShortcuts.t9Letter(digit)!!
            assertEquals("$digit", label, CtrlShortcuts.label(letter))
        }
        // 4/5/8/9 和 26 键一样走编辑面板的全选、剪切、复制、粘贴。
        assertEquals("select_all", CtrlShortcuts.editingAction(CtrlShortcuts.t9Letter('4')!!))
        assertEquals("cut", CtrlShortcuts.editingAction(CtrlShortcuts.t9Letter('5')!!))
        assertEquals("copy", CtrlShortcuts.editingAction(CtrlShortcuts.t9Letter('8')!!))
        assertEquals("paste", CtrlShortcuts.editingAction(CtrlShortcuts.t9Letter('9')!!))
        assertNull(CtrlShortcuts.t9Letter('0'))
        assertNull(CtrlShortcuts.t9Letter('a'))
    }
}
