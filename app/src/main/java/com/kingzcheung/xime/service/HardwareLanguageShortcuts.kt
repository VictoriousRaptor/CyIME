package com.kingzcheung.xime.service

import android.view.KeyEvent
import com.kingzcheung.xime.settings.HardwareKeyboardOptions
import com.kingzcheung.xime.settings.LanguageSwitchMode

internal enum class HardwareToolShortcut { PUNCTUATION, CLIPBOARD, MICROPHONE }

internal data class HardwareShortcutResult(val consume: Boolean = false, val switchMode: LanguageSwitchMode? = null,
    val tool: HardwareToolShortcut? = null) {
    val switchLanguage: Boolean get() = switchMode != null
}

/** Stateful so holding Space cannot toggle repeatedly, and Shift chords never toggle on release. */
internal class HardwareLanguageShortcuts {
    private val pressed = mutableSetOf<Int>()
    private val held = mutableSetOf<Int>()
    private var shift: Int? = null
    private var shiftDownAt = 0L
    private var shiftUsed = false
    fun reset() { pressed.clear(); held.clear(); shift = null; shiftUsed = false }
    /**
     * [altTypesChar]：这台键盘上 Alt 加这个键本身打得出字（全键盘手机 Titan 2 等的 Alt 数字层）。
     * 这时 Alt+V/H 是打数字，不当剪贴板、麦克风快捷键（GitHub #295）。
     */
    fun down(key: Int, time: Long, repeat: Int, ctrl: Boolean, alt: Boolean, meta: Boolean,
        shifted: Boolean, options: HardwareKeyboardOptions, altTypesChar: Boolean = false): HardwareShortcutResult {
        val otherKeyHeld = pressed.any { it != key }
        pressed += key
        val isShift = key == KeyEvent.KEYCODE_SHIFT_LEFT || key == KeyEvent.KEYCODE_SHIFT_RIGHT
        if (shift != null && (!isShift || shift != key)) shiftUsed = true
        if (key in held) return HardwareShortcutResult(consume = true)
        val tool = if (options.toolShortcuts && !meta && !shifted) when {
            ctrl && !alt && key == KeyEvent.KEYCODE_PERIOD -> HardwareToolShortcut.PUNCTUATION
            alt && !ctrl && !altTypesChar && key == KeyEvent.KEYCODE_V -> HardwareToolShortcut.CLIPBOARD
            alt && !ctrl && !altTypesChar && key == KeyEvent.KEYCODE_H -> HardwareToolShortcut.MICROPHONE
            else -> null
        } else null
        if (tool != null) {
            held += key
            return HardwareShortcutResult(consume = true, tool = tool.takeIf { repeat == 0 })
        }
        if (isShift && options.shiftTap) {
            held += key
            if (repeat == 0 && shift == null) { shift = key; shiftDownAt = time; shiftUsed = ctrl || alt || meta || otherKeyHeld }
            return HardwareShortcutResult(consume = true)
        }
        if (key == KeyEvent.KEYCODE_SPACE && ctrl && !alt && !meta && !shifted && options.ctrlSpace) {
            held += key
            return HardwareShortcutResult(consume = true,
                switchMode = LanguageSwitchMode.CYCLE.takeIf { repeat == 0 })
        }
        return HardwareShortcutResult()
    }
    fun up(key: Int, time: Long, canceled: Boolean, options: HardwareKeyboardOptions): HardwareShortcutResult {
        pressed.remove(key)
        val consumed = held.remove(key)
        val switch = key == shift && !shiftUsed && !canceled && options.shiftTap && time - shiftDownAt in 0..500
        if (key == shift) shift = null
        return HardwareShortcutResult(consumed, LanguageSwitchMode.CURRENT_ENGLISH.takeIf { switch })
    }
}
