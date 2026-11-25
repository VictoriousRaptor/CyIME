package com.kingzcheung.xime.ui.keyboard

/**
 * 点按 Shift 送出的动作。默认：点一下单次大写（shift_single），[doubleTap] 连点第二下锁定（shift_caps）。
 * 「点一下 Shift 锁定大写」开着时每一下都是 shift_caps：小写或单次大写 → 锁定，锁定 → 小写（[KeyboardShiftState.doubleTap]）。
 */
internal fun shiftTapAction(tapLocks: Boolean, doubleTap: Boolean): String =
    if (tapLocks || doubleTap) "shift_caps" else "shift_single"
