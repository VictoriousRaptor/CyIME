package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ShiftSlideTargetsTest {
    private val keyboard = Rect(0f, 0f, 400f, 280f)
    private val shift = Rect(0f, 160f, 60f, 220f)
    private val commits = mutableListOf<String>()
    private fun targets() = ShiftSlideTargets().apply {
        keyboardBounds = keyboard
        put("a", "a", Rect(65f, 90f, 110f, 155f), commits::add)
        put("p", "P", Rect(355f, 10f, 400f, 75f), commits::add)
    }

    @Test fun movementInsideShiftNeverShowsLineEvenPastTouchSlop() {
        val targets = targets()
        for (point in listOf(shift.center, Offset(1f, 161f), Offset(59f, 219f))) {
            assertNull(targets.move(shift, point))
            assertNull(targets.drag)
        }
        assertTrue(commits.isEmpty())
    }

    @Test fun crossingLettersAndHoldingDoesNotCommitUntilRelease() {
        val targets = targets()
        targets.move(shift, Offset(85f, 120f))
        repeat(50) { targets.move(shift, Offset(375f, 40f)) }
        assertTrue(commits.isEmpty())
        assertEquals(shift.center, targets.drag!!.origin)
        assertEquals("P", targets.hovered!!.key)
        targets.hovered!!.commit()
        targets.clearDrag()
        assertEquals(listOf("P"), commits)
        assertNull(targets.drag)
        assertNull(targets.hovered)
    }

    @Test fun outsideKeyboardAndEmptySpaceCancelTargetAndReentryCanSelectAgain() {
        val targets = targets()
        targets.put("overflow", "x", Rect(390f, 20f, 440f, 80f), commits::add)
        targets.move(shift, Offset(85f, 120f))
        for (point in listOf(Offset(-1f, 120f), Offset(420f, 40f), Offset(200f, -1f),
            Offset(200f, 281f), Offset(160f, 120f))) {
            assertNull(targets.move(shift, point))
            assertNull(targets.hovered)
        }
        targets.move(shift, Offset(85f, 120f))!!.commit()
        assertEquals(listOf("A"), commits)
    }

    @Test fun returnToShiftOrSystemCancellationClearsLineAndTarget() {
        val targets = targets()
        targets.move(shift, Offset(85f, 120f))
        targets.move(shift, shift.center)
        assertNull(targets.drag)
        assertNull(targets.hovered)
        targets.move(shift, Offset(85f, 120f))
        targets.clearDrag()
        assertNull(targets.drag)
        assertNull(targets.hovered)
        assertTrue(commits.isEmpty())
    }

    @Test fun pairedLettersSelectOneUppercaseByHalfAndNonlettersNeverBecomeTargets() {
        val targets = targets()
        targets.put("pair", "qw", Rect(120f, 10f, 200f, 75f), commits::add)
        targets.put("symbols", "!@", Rect(210f, 10f, 290f, 75f), commits::add)
        targets.move(shift, Offset(130f, 40f))!!.commit()
        targets.move(shift, Offset(180f, 40f))!!.commit()
        assertNull(targets.move(shift, Offset(250f, 40f)))
        targets.remove("pair")
        assertNull(targets.move(shift, Offset(130f, 40f)))
        assertEquals(listOf("Q", "W"), commits)
    }

    // 用户 11-03：按住 !@# 点回车或拖到回车上是 Ctrl+回车。11-04：从 Shift 拖到回车上松手是 Shift+回车（换行），不是 Ctrl+回车。
    @Test fun ctrlEnterOnlyWhileSymbolKeyHeld() {
        val targets = targets()
        val enter = Rect(330f, 220f, 400f, 280f)
        var newlines = 0
        targets.putEnter("enter", enter) { newlines++ }
        targets.move(shift, enter.center)!!.commit()
        assertEquals(1, newlines)
        assertFalse(targets.ctrlEnter())
        targets.finish()
        assertFalse(targets.ctrlEnter())
        val sent = mutableListOf<Char>()
        targets.beginCtrl { sent += it }
        assertEquals(ShiftSlideTargets.ENTER_KEY, targets.move(shift, enter.center)?.key)
        assertTrue(targets.ctrlEnter())
        assertTrue(targets.ctrlUsed)
        assertEquals(listOf(com.kingzcheung.xime.keyboard.CtrlShortcuts.ENTER), sent)
        targets.finish()
        assertFalse(targets.ctrlEnter())
    }

    // 用户 11-13：按住 !@# 点删除是 Ctrl+删除；没按住时删除照常。
    @Test fun ctrlBackspaceOnlyWhileSymbolKeyHeld() {
        val targets = targets()
        assertFalse(targets.ctrlBackspace())
        val sent = mutableListOf<Char>()
        targets.beginCtrl { sent += it }
        assertTrue(targets.ctrlBackspace())
        assertTrue(targets.ctrlBackspace())
        assertTrue(targets.ctrlUsed)
        assertEquals(listOf(com.kingzcheung.xime.keyboard.CtrlShortcuts.BACKSPACE, com.kingzcheung.xime.keyboard.CtrlShortcuts.BACKSPACE), sent)
        targets.finish()
        assertFalse(targets.ctrlBackspace())
    }

    // 用户 11-03：九键数字键只在按住 !@# 时是目标，键名是它代表的字母；没按住时拖过去不算。
    @Test fun t9DigitTargetsOnlyWhileSymbolKeyHeld() {
        val targets = targets()
        val seven = Rect(100f, 160f, 160f, 220f)
        targets.putCtrl("7", 'z', seven)
        assertNull(targets.move(shift, seven.center))
        targets.finish()
        val sent = mutableListOf<Char>()
        targets.beginCtrl { sent += it }
        assertEquals("Z", targets.move(shift, seven.center)?.key)
        assertTrue(targets.ctrlTap("z"))
        assertEquals(listOf('z'), sent)
        targets.finish()
        targets.remove("7")
        targets.beginCtrl { }
        assertNull("撤销登记后不再是目标", targets.move(shift, seven.center))
        targets.finish()
    }

    @Test fun t9DigitTargetReusedAsLetterBecomesShiftTargetAgain() {
        val targets = targets()
        val cell = Rect(100f, 160f, 160f, 220f)
        targets.putCtrl("cell", 'c', cell)
        targets.put("cell", "c", cell, commits::add)
        assertEquals("C", targets.move(shift, cell.center)?.key)
    }

    // 用户 11-03：按住 !@# 长按空格拖动选中文字，和按住 Shift 一样；松开 !@# 后回到普通移动。
    @Test fun spaceCursorSelectsWhileSymbolKeyHeld() {
        val targets = targets()
        SpaceCursorShift.begin()
        try {
            assertFalse(SpaceCursorShift.selecting(com.kingzcheung.xime.viewmodel.ShiftMode.OFF))
            targets.beginCtrl { }
            assertTrue(SpaceCursorShift.selecting(com.kingzcheung.xime.viewmodel.ShiftMode.OFF))
            assertTrue("选过字松开 !@# 不再打开符号", targets.ctrlUsed)
            targets.finish()
            assertFalse(SpaceCursorShift.selecting(com.kingzcheung.xime.viewmodel.ShiftMode.OFF))
        } finally {
            SpaceCursorShift.end()
            targets.finish()
        }
    }
}
