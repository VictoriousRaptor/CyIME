package com.kingzcheung.xime.service

import android.content.pm.ApplicationInfo

/**
 * 「游戏直通」：开关开着、接着实体键盘、当前应用自报是游戏时，CyIME 不显示工具条，也不接实体按键，
 * 按键原样交给游戏（WASD 等）。其他应用照旧用实体键盘打中文。
 * 用户在设置里单独选了直通的应用（远程桌面、没标成游戏的模拟器等，#265）不看开关也不看是不是游戏，接着实体键盘就直通。
 */
internal object HardwareGamePassthrough {
    /** 应用在清单里声明的类别（appCategory="game"）；只写了旧的 isGame 标记的也算。 */
    @Suppress("DEPRECATION")
    fun isGame(category: Int, flags: Int): Boolean =
        category == ApplicationInfo.CATEGORY_GAME || flags and ApplicationInfo.FLAG_IS_GAME != 0

    fun isGame(info: ApplicationInfo?): Boolean = info != null && isGame(info.category, info.flags)

    fun active(enabled: Boolean, hardwareKeyboard: Boolean, game: Boolean, listed: Boolean = false): Boolean =
        hardwareKeyboard && (listed || enabled && game)
}

/** 每个按键都会问一次当前应用是不是游戏：只记最近一个包名的结果，换应用才重新查。查不到（已卸载等）按非游戏处理。 */
internal class HardwareGamePackages(private val isGameLookup: (String) -> Boolean) {
    private var lastPackage: String? = null
    private var lastGame = false

    fun isGame(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (packageName != lastPackage) {
            lastGame = runCatching { isGameLookup(packageName) }.getOrDefault(false)
            lastPackage = packageName
        }
        return lastGame
    }
}
