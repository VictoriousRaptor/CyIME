package com.kingzcheung.xime.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用户 11-22：没开日语时看不到日语专用的设置项，开了才出现；只管显示，存的值不动。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class JapaneseSettingsVisibilityTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun japaneseCountsAsOnOnlyWhenTurnedOnInTheLanguageList() {
        // 新装：中文、英文。
        assertFalse(LanguagePreferences.japaneseOn(ctx))
        SettingsPreferences.getPrefsPublic(ctx).edit()
            .putStringSet(LanguagePreferences.KEY, setOf("zh", InputLanguage.JAPANESE.id)).commit()
        assertTrue(LanguagePreferences.japaneseOn(ctx))
        SettingsPreferences.getPrefsPublic(ctx).edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "es")).commit()
        assertFalse(LanguagePreferences.japaneseOn(ctx))
    }

    @Test fun nineKeyPageSummaryDropsKanaHintsWithoutJapanese() {
        assertEquals("拂动选字母、双字母键", LayoutInputCategory.Nine.description(japanese = false))
        assertEquals(LayoutInputCategory.Nine.description, LayoutInputCategory.Nine.description(japanese = true))
        assertEquals(LayoutInputCategory.Qwerty.description, LayoutInputCategory.Qwerty.description(japanese = false))
    }

    @Test fun searchHidesJapaneseOnlyItemsUntilJapaneseIsOn() {
        val longVowel = searchSettings("长音")
        assertTrue(longVowel.any { it.title == "长音键" })
        assertTrue(withJapaneseState(longVowel, japaneseOn = false).none { it.title == "长音键" })
        assertEquals(longVowel, withJapaneseState(longVowel, japaneseOn = true))
        assertTrue(withJapaneseState(searchSettings("假名 方向"), false).none { it.title == "假名方向提示" })
        // 拂动圆盘九键、14 键也用：照常列出。
        assertTrue(withJapaneseState(searchSettings("拂动 圆盘"), false).any { it.title == "拂动时显示方向圆盘" })
    }
}
