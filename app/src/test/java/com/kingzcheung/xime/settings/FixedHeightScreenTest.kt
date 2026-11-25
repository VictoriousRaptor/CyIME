package com.kingzcheung.xime.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** 固定键盘高度按屏分开（GitHub #396：折叠屏外屏调好的高度带到内屏）。 */
class FixedHeightScreenTest {
    private fun prefs(vararg values: Pair<String, Int>): SharedPreferences {
        val map = values.toMap()
        val prefs = mock<SharedPreferences>()
        whenever(prefs.contains(any())).thenAnswer { it.getArgument<String>(0) in map }
        whenever(prefs.getInt(any(), any())).thenAnswer { map[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
        return prefs
    }

    private fun stored(prefs: SharedPreferences, screen: Int, landscape: Boolean = false) =
        SettingsPreferences.storedFixedHeightDp(prefs, landscape, screen)

    @Test fun oldHeightWithoutScreenKeepsWorking() {
        // 旧版本只存了不带屏的一份：单屏手机、平板升级后照旧。
        assertEquals(300, stored(prefs("keyboard_height_dp" to 300), 384))
        assertEquals(0, stored(prefs(), 384))
    }

    @Test fun foldableScreensKeepTheirOwnHeight() {
        // 外屏（380）调成 260，展开内屏（650）没调过：按默认，不沿用外屏的 260。
        val outerOnly = prefs("keyboard_height_dp" to 260, "keyboard_height_dp_sw380" to 260, "keyboard_height_screen" to 380)
        assertEquals(260, stored(outerOnly, 380))
        assertEquals(0, stored(outerOnly, 650))
        // 内屏后来调成 340：合上回外屏用外屏自己的 260，展开用 340。
        val both = prefs("keyboard_height_dp" to 340, "keyboard_height_dp_sw380" to 260,
            "keyboard_height_dp_sw650" to 340, "keyboard_height_screen" to 650)
        assertEquals(260, stored(both, 380))
        assertEquals(340, stored(both, 650))
    }

    @Test fun landscapeIsSeparate() {
        val p = prefs("keyboard_height_dp" to 300, "keyboard_height_screen" to 380,
            "keyboard_height_dp_landscape" to 200, "keyboard_height_screen_landscape" to 650)
        assertEquals(200, stored(p, 650, landscape = true))
        assertEquals(0, stored(p, 380, landscape = true))
    }
}
