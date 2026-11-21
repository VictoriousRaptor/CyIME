package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 日文汉字、韩文汉字、粤语字 11-20 从「生僻字」分出来，各自默认关。开过生僻字的人更新后照样打得出这些字：
 * 新开关没存过时跟生僻字原来的值；存过就按存的。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SplitRareSwitchesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = SettingsPreferences.getPrefsPublic(context)
    private val keys = listOf(KeyboardInputPreferences.RARE_CHARACTERS_KEY, KeyboardInputPreferences.JAPANESE_KANJI_KEY,
        KeyboardInputPreferences.KOREAN_HANJA_KEY, KeyboardInputPreferences.CANTONESE_CHARACTERS_KEY)

    @After fun clear() { prefs.edit().apply { keys.forEach { remove(it) } }.commit() }

    @Test fun offForNewUsers() {
        clear()
        val read = KeyboardInputPreferences.read(context)
        assertFalse(read.rareCharacters)
        assertFalse(read.japaneseKanji)
        assertFalse(read.koreanHanja)
        assertFalse(read.cantoneseCharacters)
    }

    @Test fun followTheOldRareSwitchUntilSaved() {
        clear()
        prefs.edit().putBoolean(KeyboardInputPreferences.RARE_CHARACTERS_KEY, true).commit()
        val read = KeyboardInputPreferences.read(context)
        assertTrue(read.japaneseKanji)
        assertTrue(read.koreanHanja)
        assertTrue(read.cantoneseCharacters)
        read.copy(japaneseKanji = false).save(context)
        val saved = KeyboardInputPreferences.read(context)
        assertFalse(saved.japaneseKanji)
        assertTrue(saved.rareCharacters)
        assertEquals(true, prefs.getBoolean(KeyboardInputPreferences.CANTONESE_CHARACTERS_KEY, false))
    }
}
