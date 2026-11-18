package com.kingzcheung.xime.ui.settings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 启动广告彩蛋（用户 11-15）：默认关，只在正常打开设置时放，只在设置 App 里。 */
class LaunchAdEggTest {
    @Test fun onlyPlainOpensOfSettingsShowIt() {
        // 桌面图标：什么都不带。键盘菜单的「设置」：带的是首页。
        assertTrue(LaunchAdEgg.allowedAtLaunch(false, Intent.ACTION_MAIN, null, null))
        assertTrue(LaunchAdEgg.allowedAtLaunch(false, null, SettingsRoutes.Main, null))
        // 跳到具体页面、插件页、别的应用分享文件进来：不挡。
        assertFalse(LaunchAdEgg.allowedAtLaunch(false, null, SettingsRoutes.Theme, null))
        assertFalse(LaunchAdEgg.allowedAtLaunch(false, null, "plugins", "some.plugin"))
        assertFalse(LaunchAdEgg.allowedAtLaunch(false, Intent.ACTION_SEND, null, null))
        assertFalse(LaunchAdEgg.allowedAtLaunch(false, Intent.ACTION_SEND_MULTIPLE, null, null))
        // 老人包不放。
        assertFalse(LaunchAdEgg.allowedAtLaunch(true, Intent.ACTION_MAIN, null, null))
        assertEquals(5, LaunchAdEgg.SECONDS)
    }

    @Test fun offByDefaultAndNeverOnTheKeyboard() {
        val root = File("src/main/java/com/kingzcheung/xime")
        assertTrue("彩蛋默认不是关的",
            File(root, "settings/SettingsPreferences.kt").readText().contains("getBoolean(KEY_LAUNCH_AD_EGG, false)"))
        // 键盘和输入法服务里不能出现这页广告。
        val leaked = listOf("ui/keyboard", "ui/menubar", "service").flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { it.readText().contains("LaunchAd") }.map { it.name }
        assertEquals(emptyList<String>(), leaked)
        // 这一页不联网：只画东西，不出现网络、链接、文件的调用。
        val poster = File(root, "ui/settings/LaunchAdPoster.kt").readText() + File(root, "ui/settings/LaunchAdEasterEgg.kt").readText()
        val forbidden = listOf("http", "java.net", "okhttp", "UriHandler", "openUri", "startActivity", "java.io")
        assertEquals(emptyList<String>(), forbidden.filter { poster.contains(it) })
    }
}
