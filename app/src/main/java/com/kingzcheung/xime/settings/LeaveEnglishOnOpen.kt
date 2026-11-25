package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 「打开键盘时退出英文」（GitHub #404，默认关）：在一个应用里切到英文、去别的应用再回来，或换一个输入框时，
 * 从英文切回方案自己的语言（中文、日文……）。关着时行为不变：中英状态一直跟着上次。
 */
object LeaveEnglishOnOpen {
    const val PREF_KEY = "leave_english_on_open"

    fun enabled(context: Context): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(PREF_KEY, false)

    /**
     * 只在进新输入框时（同一输入框重启不算）、正在英文、方案的语言开着时退出。
     * 输入框自己定了中英（网址邮箱框自动切英文、密码框、数字框，[fieldAscii] 或 [scoped]）时不动。
     */
    internal fun applies(enabled: Boolean, restarting: Boolean, scoped: Boolean, fieldAscii: Boolean?,
        ascii: Boolean, nativeEnabled: Boolean): Boolean =
        enabled && !restarting && !scoped && fieldAscii == null && ascii && nativeEnabled
}
