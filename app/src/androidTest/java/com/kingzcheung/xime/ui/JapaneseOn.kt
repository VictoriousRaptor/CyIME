package com.kingzcheung.xime.ui

import android.content.Context
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 日语专用的设置项（日语语音、假名方向提示、长音键）只在开了日语时显示（用户 11-22）。
 * 测这些项时先打开日语，测完还原；本来就开着时不写，免得键盘跟着重新准备。
 */
internal fun <T> withJapaneseOn(context: Context, block: () -> T): T {
    val prefs = SettingsPreferences.getPrefsPublic(context)
    val saved = prefs.getStringSet(LanguagePreferences.KEY, null)?.toSet()
    val already = saved != null && InputLanguage.JAPANESE.id in saved
    if (!already) prefs.edit().putStringSet(LanguagePreferences.KEY,
        (saved ?: setOf(InputLanguage.CHINESE.id, InputLanguage.ENGLISH.id)) + InputLanguage.JAPANESE.id).commit()
    try {
        return block()
    } finally {
        if (!already) prefs.edit().apply {
            if (saved == null) remove(LanguagePreferences.KEY) else putStringSet(LanguagePreferences.KEY, saved)
        }.commit()
    }
}
