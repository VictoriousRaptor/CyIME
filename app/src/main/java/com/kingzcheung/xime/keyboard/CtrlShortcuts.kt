package com.kingzcheung.xime.keyboard

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * 按住 26 键的符号键当 Ctrl 用（用户 10-24）：字母键上显示常用快捷键，拖到字母上松手发 Ctrl+字母。
 * 没列出的字母照样能发，只是键面不写用途。九键的数字键按 [t9Letter] 换成字母，走同一条路。
 */
object CtrlShortcuts {
    /** 键面写的用途，两个字以内，手机上的窄键也放得下。 */
    private val labels = mapOf(
        'a' to "全选", 'c' to "复制", 'v' to "粘贴", 'x' to "剪切", 'z' to "撤销", 'y' to "重做",
        's' to "保存", 'f' to "查找", 'w' to "关闭", 't' to "新页", 'n' to "新建", 'o' to "打开",
        'r' to "刷新", 'p' to "打印", 'b' to "加粗", 'i' to "斜体",
    )

    fun label(letter: Char): String? = labels[letter.lowercaseChar()]

    /**
     * 九键按住 !@# 当 Ctrl（用户 11-03）：数字键按这张表发 Ctrl+字母，键面写这个字母的用途。
     * 1 关闭 2 刷新 3 重做 / 4 全选 5 剪切 6 查找 / 7 撤销 8 复制 9 粘贴；0 键不算。
     * 用户 11-14：剪切挪到 5（复制 8 正上方、挨着全选 4），6 改成查找；手机上很少用保存。
     */
    private val t9Letters = mapOf(
        '1' to 'w', '2' to 'r', '3' to 'y',
        '4' to 'a', '5' to 'x', '6' to 'f',
        '7' to 'z', '8' to 'c', '9' to 'v',
    )

    fun t9Letter(digit: Char): Char? = t9Letters[digit]

    /**
     * 全选、复制、剪切、粘贴不发模拟按键，走编辑面板同一条输入框自带动作（返回编辑面板的动作名）。
     * 11-02 用户反馈：荣耀 Magic8 的微信收到模拟的 Ctrl+A 不全选，只在光标处留一个空选择框，杀掉微信才消失；
     * 编辑面板的全选在同一个输入框里正常。其他字母仍发 Ctrl+字母，由应用自己处理。
     */
    fun editingAction(letter: Char): String? = when (letter.lowercaseChar()) {
        'a' -> "select_all"
        'c' -> "copy"
        'x' -> "cut"
        'v' -> "paste"
        else -> null
    }

    /** 和实体键盘一样：Ctrl 按下、字母按下抬起都带 Ctrl、Ctrl 抬起；由应用自己处理（复制、关标签页等）。 */
    fun keyEvents(letter: Char, now: Long): List<KeyEvent> {
        val lower = letter.lowercaseChar()
        require(lower in 'a'..'z')
        val code = KeyEvent.KEYCODE_A + (lower - 'a')
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        fun event(action: Int, keyCode: Int, metaState: Int) =
            KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags)
        return listOf(
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta),
            event(KeyEvent.ACTION_DOWN, code, meta),
            event(KeyEvent.ACTION_UP, code, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
        )
    }

    /**
     * Ctrl+回车的按键：输入框没有「发送」动作时交给应用自己发送（见 EnterKeyAction）。
     * 只给回车用；上面 Ctrl+A/C/X/V 走编辑面板、其他字母发按键的规则不变。
     */
    fun enterKeyEvents(now: Long): List<KeyEvent> {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        fun event(action: Int, keyCode: Int, metaState: Int) =
            KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags)
        return listOf(
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta),
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
        )
    }

    /**
     * Ctrl+删除：按住 !@# 点删除键（用户 11-13：按住时就是真正的 Ctrl）。应用自己处理，系统输入框是删到上一个词的开头，
     * 和实体键盘一样；不认 Ctrl 的应用当普通删除。
     */
    fun backspaceKeyEvents(now: Long): List<KeyEvent> {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        fun event(action: Int, keyCode: Int, metaState: Int) =
            KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags)
        return listOf(
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta),
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
        )
    }

    /** 终端里的 Shift+回车：Shift 按下、回车按下抬起都带 Shift、Shift 抬起，由终端自己处理。 */
    fun shiftEnterKeyEvents(now: Long): List<KeyEvent> {
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        fun event(action: Int, keyCode: Int, metaState: Int) =
            KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags)
        return listOf(
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, meta),
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, meta),
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0),
        )
    }

    /** 按住符号键点回车、拖到回车上松手：Ctrl+回车，发送（用户 11-03）。 */
    const val ENTER = '\n'
    const val ENTER_COMMAND = "ctrl:enter"
    /** 按住符号键点删除键：Ctrl+删除（用户 11-13）。 */
    const val BACKSPACE = '\b'
    const val BACKSPACE_COMMAND = "ctrl:backspace"
    /** 按住 Shift 点回车、从 Shift 拖到回车上松手：Shift+回车，换行（用户 11-04）。 */
    const val SHIFT_ENTER_COMMAND = "shift:enter"

    /** 键盘层经 [GestureAction.COMMAND] 传给服务的命令名。 */
    fun command(letter: Char): String = when (letter) {
        ENTER -> ENTER_COMMAND
        BACKSPACE -> BACKSPACE_COMMAND
        else -> "ctrl:${letter.lowercaseChar()}"
    }
    fun letterOf(command: String): Char? =
        command.removePrefix("ctrl:").takeIf { command.startsWith("ctrl:") && it.length == 1 && it[0] in 'a'..'z' }?.get(0)
}
