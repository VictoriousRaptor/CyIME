package com.kingzcheung.xime.ui.settings

import android.app.Activity
import android.view.Display

/**
 * 设置页要高刷（用户 11-16：进设置锁 60 帧，只有开发者选项「强制停用动态刷新率」才 120）。
 * 设置窗口自己没投任何刷新率票，有的系统的动态刷新率就按低档给；这里给设置窗口（只这一个 Activity，
 * 键盘窗口不动）明确要分辨率不变、刷新率最高的显示模式。没有更高的就什么都不改。
 */
internal object SettingsRefreshRate {
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** 同分辨率里刷新率最高的模式；它就是当前模式或比当前不高时返回 null（不改）。 */
    fun pickHighest(current: Mode, modes: List<Mode>): Int? {
        val best = modes.filter { it.width == current.width && it.height == current.height }
            .maxByOrNull { it.refreshRate } ?: return null
        return best.id.takeIf { best.refreshRate > current.refreshRate + 0.5f }
    }

    fun apply(activity: Activity) {
        runCatching {
            @Suppress("DEPRECATION")
            val display: Display = activity.windowManager.defaultDisplay
            fun Display.Mode.toMode() = Mode(modeId, physicalWidth, physicalHeight, refreshRate)
            val id = pickHighest(display.mode.toMode(), display.supportedModes.map { it.toMode() }) ?: return
            val window = activity.window
            window.attributes = window.attributes.also { it.preferredDisplayModeId = id }
        }
    }
}
