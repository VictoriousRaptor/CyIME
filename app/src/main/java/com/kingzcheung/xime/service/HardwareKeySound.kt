package com.kingzcheung.xime.service

import android.view.KeyEvent

/**
 * 外接实体键盘按键音：与软键盘共用「按键音」开关和音色；修饰键和组合快捷键不出声。
 * 按住自动重复时只有删除键每下都响（同软键盘长按连删，新一下切断上一下），其他键只响第一下。
 */
object HardwareKeySound {
    fun soundFor(keyCode: Int, repeatCount: Int, ctrl: Boolean, alt: Boolean, meta: Boolean): String? {
        if (ctrl || alt || meta) return null
        if (repeatCount > 0 && keyCode != KeyEvent.KEYCODE_DEL && keyCode != KeyEvent.KEYCODE_FORWARD_DEL) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> KeyFeedbackType.DELETE.sound
            KeyEvent.KEYCODE_SPACE -> KeyFeedbackType.SPACE.sound
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyFeedbackType.ENTER.sound
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_POUND, // 数字、* 和 #
            in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT,
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_TAB,
            in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_AT, // - = [ ] \ ; ' / @
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_ESCAPE,
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> KeyFeedbackType.CHARACTER.sound
            else -> null
        }
    }
}
