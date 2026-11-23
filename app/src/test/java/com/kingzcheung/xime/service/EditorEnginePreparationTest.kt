package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.InputCommandQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EditorEnginePreparationTest {
    @Test fun repeatedFocusDoesNotReloadTheSameDictionary() {
        var loads = 0
        var clears = 0
        repeat(500) {
            assertTrue(prepareEditorEngine("pinyin", "pinyin", { loads++; true }, { clears++ }, {}))
        }
        assertEquals(0, loads)
        assertEquals(500, clears)
    }

    @Test fun failedSwitchDoesNotClearOrRestoreTheWrongSchema() {
        val events = mutableListOf<String>()
        assertFalse(prepareEditorEngine("pinyin", "wubi", { events += it; false },
            { events += "clear" }, { events += "restore" }))
        assertEquals(listOf("wubi"), events)
    }

    @Test fun firstKeysWaitForPreparationAndRemainInOrder() = runBlocking {
        val queue = InputCommandQueue(this, Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        try {
            queue.submit {
                started.complete(Unit)
                release.await()
                prepareEditorEngine("pinyin", "wubi", { events += "switch"; true },
                    { events += "clear" }, { events += "options" })
            }
            withTimeout(3000) { started.await() }
            val keys = "nihao".map { key -> queue.submit { events += key.toString() } }
            assertTrue(queue.hasPendingCommands)
            assertTrue(keys.none { it.isCompleted })
            release.complete(Unit)
            withTimeout(3000) { keys.joinAll() }
            assertEquals(listOf("switch", "clear", "options", "n", "i", "h", "a", "o"), events)
            assertFalse(queue.hasPendingCommands)
        } finally { queue.close() }
    }

    @Test fun delayedPreparationFromOldFocusCannotTouchTheNewEditor() = runBlocking {
        val readiness = InputReadiness().also { it.completeStartup() }
        val queue = InputCommandQueue(this, Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val ticket = readiness.ticket()!!
        val owner = InputCommandOwner(ticket, 1) {
            if (!readiness.accepts(ticket)) throw CancellationException("old editor")
        }
        var oldTouchedEngine = false
        var newTyped = false
        try {
            queue.submit { started.complete(Unit); release.await() }
            withTimeout(3000) { started.await() }
            val old = queue.submit(owner.context()) {
                InputCommandOwner.requireOwner().requireCurrent(readiness, 1)
                oldTouchedEngine = true
            }
            readiness.newEditorSession()
            val next = queue.submit { newTyped = true }
            release.complete(Unit)
            withTimeout(3000) { next.join() }
            assertTrue(old.isCancelled)
            assertFalse(oldTouchedEngine)
            assertTrue(newTyped)
        } finally { queue.close() }
    }

    @Test fun newClipboardCannotInterruptCompositionEnglishOrAnUnrenderedKey() {
        assertFalse(canShowClipboardSuggestions(true, "", false))
        assertFalse(canShowClipboardSuggestions(false, "hello", false))
        assertFalse(canShowClipboardSuggestions(false, "", true))
        assertTrue(canShowClipboardSuggestions(false, "", false))
    }

    @Test fun onlyAFreshCtrlVCountsAsAHardwarePaste() {
        val v = android.view.KeyEvent.KEYCODE_V
        assertTrue(isHardwarePasteShortcut(v, ctrl = true, alt = false, meta = false, repeatCount = 0))
        // 按住不放的重复、Alt+V（剪贴板快捷键）、Win+V、不带 Ctrl 的 V、Ctrl+C 都不是粘贴。
        assertFalse(isHardwarePasteShortcut(v, ctrl = true, alt = false, meta = false, repeatCount = 1))
        assertFalse(isHardwarePasteShortcut(v, ctrl = true, alt = true, meta = false, repeatCount = 0))
        assertFalse(isHardwarePasteShortcut(v, ctrl = false, alt = true, meta = false, repeatCount = 0))
        assertFalse(isHardwarePasteShortcut(v, ctrl = true, alt = false, meta = true, repeatCount = 0))
        assertFalse(isHardwarePasteShortcut(v, ctrl = false, alt = false, meta = false, repeatCount = 0))
        assertFalse(isHardwarePasteShortcut(android.view.KeyEvent.KEYCODE_C, ctrl = true, alt = false, meta = false, repeatCount = 0))
    }

    /** 11-21：没接实体键盘时 XposedEdge 等注入的 Ctrl+C / V 交给应用，不打成字母；普通按键、接了实体键盘的照原路走。 */
    @Test fun injectedShortcutsWithoutAKeyboardGoToTheApp() {
        assertTrue(passesInjectedShortcutToApp(hardwareKeyboard = false, ctrl = true, alt = false, meta = false))
        assertTrue(passesInjectedShortcutToApp(hardwareKeyboard = false, ctrl = false, alt = true, meta = false))
        assertTrue(passesInjectedShortcutToApp(hardwareKeyboard = false, ctrl = false, alt = false, meta = true))
        assertFalse(passesInjectedShortcutToApp(hardwareKeyboard = false, ctrl = false, alt = false, meta = false))
        // 接了实体键盘：Alt 数字层、语言切换快捷键还要先看，交给原来那段处理。
        assertFalse(passesInjectedShortcutToApp(hardwareKeyboard = true, ctrl = true, alt = false, meta = false))
    }

    /** onKeyDown 里要在把按键当字母打之前先放行（否则 Ctrl+C 的 C 进了编码）。 */
    @Test fun serviceChecksInjectedShortcutsBeforeTypingLetters() {
        val source = java.io.File("src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt").readText()
        val onKeyDown = source.substring(source.indexOf("override fun onKeyDown("), source.indexOf("override fun onKeyUp("))
        val pass = onKeyDown.indexOf("passesInjectedShortcutToApp(")
        assertTrue(pass > 0)
        assertTrue(pass < onKeyDown.indexOf("keyRouter.handleKeyPress(key, isShifted"))
    }
}
