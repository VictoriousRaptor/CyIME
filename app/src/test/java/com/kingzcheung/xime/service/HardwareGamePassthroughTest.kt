package com.kingzcheung.xime.service

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareGamePassthroughTest {
    @Test fun `apps declaring the game category are games`() {
        assertTrue(HardwareGamePassthrough.isGame(ApplicationInfo.CATEGORY_GAME, 0))
    }

    @Suppress("DEPRECATION")
    @Test fun `the legacy isGame flag also counts`() {
        assertTrue(HardwareGamePassthrough.isGame(ApplicationInfo.CATEGORY_UNDEFINED, ApplicationInfo.FLAG_IS_GAME))
        assertTrue(HardwareGamePassthrough.isGame(ApplicationInfo.CATEGORY_UNDEFINED,
            ApplicationInfo.FLAG_IS_GAME or ApplicationInfo.FLAG_HAS_CODE))
    }

    @Test fun `other categories and unknown apps are not games`() {
        assertFalse(HardwareGamePassthrough.isGame(null))
        assertFalse(HardwareGamePassthrough.isGame(ApplicationInfo.CATEGORY_UNDEFINED, ApplicationInfo.FLAG_HAS_CODE))
        for (category in listOf(ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_PRODUCTIVITY,
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_NEWS)) {
            assertFalse(HardwareGamePassthrough.isGame(category, 0))
        }
    }

    @Test fun `passthrough needs the switch, a physical keyboard and a game together`() {
        assertTrue(HardwareGamePassthrough.active(enabled = true, hardwareKeyboard = true, game = true))
        // 默认关：接着键盘在游戏里也照旧显示工具条、照旧打中文。
        assertFalse(HardwareGamePassthrough.active(enabled = false, hardwareKeyboard = true, game = true))
        // 没接实体键盘：游戏里点输入框照旧弹屏幕键盘。
        assertFalse(HardwareGamePassthrough.active(enabled = true, hardwareKeyboard = false, game = true))
        // 其他应用：实体键盘照旧打中文。
        assertFalse(HardwareGamePassthrough.active(enabled = true, hardwareKeyboard = true, game = false))
    }

    @Test fun `apps picked for passthrough skip the game check and the switch but still need a keyboard`() {
        // #265：远程桌面、没标成游戏的模拟器，用户单独选了就直通。
        assertTrue(HardwareGamePassthrough.active(enabled = false, hardwareKeyboard = true, game = false, listed = true))
        assertTrue(HardwareGamePassthrough.active(enabled = true, hardwareKeyboard = true, game = false, listed = true))
        // 没接实体键盘：照旧弹屏幕键盘。
        assertFalse(HardwareGamePassthrough.active(enabled = false, hardwareKeyboard = false, game = false, listed = true))
        // 没选的应用和以前一样。
        assertFalse(HardwareGamePassthrough.active(enabled = false, hardwareKeyboard = true, game = true, listed = false))
    }

    @Test fun `package lookup is cached until the editor package changes`() {
        val lookups = mutableListOf<String>()
        val packages = HardwareGamePackages { name -> lookups += name; name == "com.example.game" }
        repeat(5) { assertTrue(packages.isGame("com.example.game")) }
        assertEquals(listOf("com.example.game"), lookups)
        assertFalse(packages.isGame("com.tencent.mm"))
        assertTrue(packages.isGame("com.example.game"))
        assertEquals(listOf("com.example.game", "com.tencent.mm", "com.example.game"), lookups)
    }

    @Test fun `missing or hidden packages are not games`() {
        val packages = HardwareGamePackages { throw android.content.pm.PackageManager.NameNotFoundException(it) }
        assertFalse(packages.isGame("com.example.gone"))
        assertFalse(packages.isGame(null))
        assertFalse(packages.isGame(""))
    }
}
