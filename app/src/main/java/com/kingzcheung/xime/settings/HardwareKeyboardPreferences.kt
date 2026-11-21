package com.kingzcheung.xime.settings

import android.content.Context

data class HardwareKeyboardOptions(
    val ctrlSpace: Boolean = true,
    val shiftTap: Boolean = true,
    val followCursor: Boolean = true,
    val avoidEditor: Boolean = true,
    val dockAtEdge: Boolean = true,
    val pageMinusEquals: Boolean = true,
    val pageBrackets: Boolean = true,
    val pageCommaPeriod: Boolean = true,
    /** 吸附到底边时使用三星式贴底通栏（整宽、九个带编号的候选）；关闭时保持原来的底部长条。 */
    val bottomBar: Boolean = false,
    /** 联想词也显示在跟随光标的候选浮窗里（和打字候选同一个位置）；关闭时联想在工具条上。默认关。 */
    val associationInWindow: Boolean = false,
    /** 游戏直通：游戏类应用里不显示工具条，实体按键直接交给游戏。默认关。 */
    val gamePassthrough: Boolean = false,
    /** 工具快捷键（Ctrl+. / Ctrl+回车 / Alt+V / Alt+H）。关掉后这些组合键交给应用。默认开。 */
    val toolShortcuts: Boolean = true,
    /** 锁定浮窗位置：工具条不能拖动，停在现在的位置（按钮照常能点）。默认关。 */
    val lockPosition: Boolean = false,
    /**
     * 浮窗固定在屏幕哪一边；null = 自由拖动（默认，和原来一样）。固定时工具条贴那一边、不能拖，
     * 不再跟着光标或避开输入区域挪动，候选卡贴着工具条出现（用户 11-20 转来的反馈：接实体键盘打注音，浮窗乱飘、挡输入框）。
     */
    val fixedEdge: HardwareToolbarEdge? = null,
)

/** 实体键盘浮窗可以固定的四条边；[key] 是存在设置里的值。 */
enum class HardwareToolbarEdge(val key: String, val label: String) {
    TOP("top", "顶部"), BOTTOM("bottom", "底部"), LEFT("left", "左侧"), RIGHT("right", "右侧");

    companion object {
        fun fromKey(key: String?): HardwareToolbarEdge? = entries.firstOrNull { it.key == key }
    }
}

object HardwareKeyboardPreferences {
    const val PREFIX = "hardware_keyboard_"
    fun read(context: Context): HardwareKeyboardOptions {
        val p = SettingsPreferences.getPrefsPublic(context)
        return HardwareKeyboardOptions(
            ctrlSpace = p.getBoolean(PREFIX + "ctrl_space", true),
            shiftTap = p.getBoolean(PREFIX + "shift_tap", true),
            followCursor = p.getBoolean(PREFIX + "follow_cursor", true),
            avoidEditor = p.getBoolean(PREFIX + "avoid_editor", true),
            dockAtEdge = p.getBoolean(PREFIX + "dock_edge", true),
            pageMinusEquals = p.getBoolean(PREFIX + "page_minus_equals", true),
            pageBrackets = p.getBoolean(PREFIX + "page_brackets", true),
            pageCommaPeriod = p.getBoolean(PREFIX + "page_comma_period", true),
            bottomBar = p.getBoolean(PREFIX + "bottom_bar", false),
            associationInWindow = p.getBoolean(PREFIX + "association_in_window", false),
            gamePassthrough = p.getBoolean(PREFIX + "game_passthrough", false),
            toolShortcuts = p.getBoolean(PREFIX + "tool_shortcuts", true),
            lockPosition = p.getBoolean(PREFIX + "lock_position", false),
            fixedEdge = HardwareToolbarEdge.fromKey(p.getString(PREFIX + FIXED_EDGE, null)),
        )
    }
    private const val FIXED_EDGE = "fixed_edge"
    /** null = 自由拖动：删掉这个键，回到原来的默认。 */
    fun setFixedEdge(context: Context, edge: HardwareToolbarEdge?) {
        val editor = SettingsPreferences.getPrefsPublic(context).edit()
        if (edge == null) editor.remove(PREFIX + FIXED_EDGE) else editor.putString(PREFIX + FIXED_EDGE, edge.key)
        editor.apply()
    }
    fun set(context: Context, key: String, enabled: Boolean) {
        require(key in setOf("ctrl_space", "shift_tap", "follow_cursor", "avoid_editor", "dock_edge",
            "page_minus_equals", "page_brackets", "page_comma_period", "bottom_bar", "association_in_window",
            "game_passthrough", "tool_shortcuts", "lock_position"))
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(PREFIX + key, enabled).apply()
    }
}
