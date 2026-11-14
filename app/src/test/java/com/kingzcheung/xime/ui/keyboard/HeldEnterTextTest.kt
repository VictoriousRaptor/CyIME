package com.kingzcheung.xime.ui.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 11-14：回车键写它现在实际做的事。按住 Ctrl 是发送，按住 Shift 是换行，其余照输入框动作。 */
class HeldEnterTextTest {
    @Test fun ctrlShowsSendShiftShowsNewline() {
        assertEquals("搜索", heldEnterText("搜索", ctrl = false))
        assertEquals("发送", heldEnterText("搜索", ctrl = true))
        assertEquals("发送", heldEnterText("回车", ctrl = true, shiftHeld = true))
        assertEquals("换行", heldEnterText("搜索", ctrl = false, shiftHeld = true))
        assertEquals(Icons.AutoMirrored.Filled.Send, actionKeyIcon(heldEnterText("完成", ctrl = true)))
        assertEquals(Icons.AutoMirrored.Filled.KeyboardReturn, actionKeyIcon(heldEnterText("搜索", ctrl = false, shiftHeld = true)))
    }
}
