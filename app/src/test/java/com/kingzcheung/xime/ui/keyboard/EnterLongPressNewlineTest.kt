package com.kingzcheung.xime.ui.keyboard

import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.service.EnterKeyAction
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #169：「回车发送」开着、回车会发送时，长按回车换行；其他时候长按回车和以前一样。 */
class EnterLongPressNewlineTest {
    private val newline: () -> Unit = {}

    @Test fun onlySendFaceGetsLongPress() {
        val send = EnterKeyAction.label(EditorInfo.IME_ACTION_SEND, appSend = true)
        var called = false
        enterLongPressNewline(send) { called = true }!!.invoke()
        assertTrue(called)
        // 「回车发送」关着：同一个输入框键面写换行，没有长按。
        assertNull(enterLongPressNewline(EnterKeyAction.label(EditorInfo.IME_ACTION_SEND, appSend = false), newline))
        // 搜索、完成、普通换行都不加长按。
        assertNull(enterLongPressNewline(EnterKeyAction.label(EditorInfo.IME_ACTION_SEARCH, appSend = true), newline))
        assertNull(enterLongPressNewline(EnterKeyAction.label(EditorInfo.IME_ACTION_DONE, appSend = true), newline))
        assertNull(enterLongPressNewline(EnterKeyAction.label(EditorInfo.IME_ACTION_UNSPECIFIED, appSend = true), newline))
        // 应用标了「回车不做动作」：回车不发送，也不加长按。
        assertNull(enterLongPressNewline(EnterKeyAction.label(
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, appSend = true), newline))
        assertNotNull(enterLongPressNewline("发送", newline))
        assertNull(enterLongPressNewline("发送", null))
    }
}
