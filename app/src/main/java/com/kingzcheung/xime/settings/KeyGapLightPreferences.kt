package com.kingzcheung.xime.settings

import android.content.SharedPreferences

/** 键缝动效的样式；[id] 是存进设置里的值，不要改。 */
enum class KeyGapLightStyle(val id: String, val title: String) {
    /** 光线沿着键缝往四面八方走。 */
    TRACE("trace", "线路"),
    /** 从按下的键往外扩的方框。 */
    FRAME("frame", "方框"),
    /** 从按下的键往外扩的菱形。 */
    DIAMOND("diamond", "菱形");

    companion object {
        fun of(id: String?): KeyGapLightStyle = entries.firstOrNull { it.id == id } ?: TRACE
    }
}

/**
 * 键缝动效：光画在键帽下面一层，只从键缝里露出来，颜色取主题强调色。
 * [enabled] 默认关；关着时其余各项都不起作用，键盘上也不多挂任何东西。
 */
data class KeyGapLightConfig(
    val enabled: Boolean = false,
    val style: KeyGapLightStyle = KeyGapLightStyle.TRACE,
    /** 速度倍数，1 = 按键 200 ms。 */
    val speed: Float = 1f,
    /** 走多远，单位是一个键（见 GapNet.unit）。 */
    val reach: Float = 2f,
    /** 线宽（dp）；画的时候不会超过键缝的宽度。 */
    val widthDp: Float = 2f,
    val brightness: Float = .9f,
    /** 拖尾长短，0 最短，1 最长。 */
    val tail: Float = .6f,
    /** 一次按键跟着几道。 */
    val layers: Int = 2,
    /** 打字时往外走。 */
    val typing: Boolean = true,
    /** 删除时往里收。 */
    val delete: Boolean = true,
    /** 上屏和发送：点空格一块色带往上走，点回车整排往上扫。 */
    val commit: Boolean = true,
    /** 切换扩散：大写出现时从 Shift 扩开，取消时收回去。 */
    val switch: Boolean = true,
    /** 长按进度：按住有长按的键，线沿键边走一圈，走满就是长按生效的那一刻。 */
    val hold: Boolean = true,
    /** 空格移光标：色块跟着手指走，每走一格留一个刻度。 */
    val space: Boolean = true,
    /** 语音音量：听写时两排键之间的键缝当音量条，从中间往两边长。 */
    val voice: Boolean = true,
)

object KeyGapLightPreferences {
    const val ENABLED = "key_gap_light_enabled"
    const val STYLE = "key_gap_light_style"
    const val SPEED = "key_gap_light_speed"
    const val REACH = "key_gap_light_reach"
    const val WIDTH = "key_gap_light_width"
    const val BRIGHTNESS = "key_gap_light_brightness"
    const val TAIL = "key_gap_light_tail"
    const val LAYERS = "key_gap_light_layers"
    const val TYPING = "key_gap_light_typing"
    const val DELETE = "key_gap_light_delete"
    const val COMMIT = "key_gap_light_commit"
    const val SWITCH = "key_gap_light_switch"
    const val HOLD = "key_gap_light_hold"
    const val SPACE = "key_gap_light_space"
    const val VOICE = "key_gap_light_voice"

    val SPEED_RANGE = .5f..2f
    val REACH_RANGE = 1f..4f
    val WIDTH_RANGE = 1f..4f
    val BRIGHTNESS_RANGE = .3f..1f
    val TAIL_RANGE = 0f..1f
    val LAYERS_RANGE = 1..3

    /** 正在显示的键盘监听这些键，改了立即生效（并进 KeyboardInputPreferences.watchedKeys）。 */
    val keys: Set<String> = setOf(ENABLED, STYLE, SPEED, REACH, WIDTH, BRIGHTNESS, TAIL, LAYERS,
        TYPING, DELETE, COMMIT, SWITCH, HOLD, SPACE, VOICE)

    /**
     * 各种动效的分项开关。键名同时是性能代价表里的 id（[PerformanceCosts.Item]），每一种单独记代价，
     * 「性能与省电」里也能一种一种关。
     */
    val eventKeys: Set<String> = setOf(TYPING, DELETE, COMMIT, SWITCH, HOLD, SPACE, VOICE)

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(ENABLED, false)

    /** 这一种现在画不画：总开关开着，并且它自己开着（分项默认开）。 */
    fun eventEnabled(prefs: SharedPreferences, key: String): Boolean = enabled(prefs) && prefs.getBoolean(key, true)

    /** 性能页拨分项用：开一种时总开关没开就一起开；关只关这一种。 */
    fun setEvent(prefs: SharedPreferences, key: String, on: Boolean) {
        if (key !in eventKeys) return
        val editor = prefs.edit().putBoolean(key, on)
        if (on) editor.putBoolean(ENABLED, true)
        editor.apply()
    }

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED, enabled).apply()
    }

    fun read(prefs: SharedPreferences): KeyGapLightConfig {
        val defaults = KeyGapLightConfig()
        return KeyGapLightConfig(
            enabled = enabled(prefs),
            style = KeyGapLightStyle.of(prefs.getString(STYLE, null)),
            speed = prefs.getFloat(SPEED, defaults.speed).coerceIn(SPEED_RANGE),
            reach = prefs.getFloat(REACH, defaults.reach).coerceIn(REACH_RANGE),
            widthDp = prefs.getFloat(WIDTH, defaults.widthDp).coerceIn(WIDTH_RANGE),
            brightness = prefs.getFloat(BRIGHTNESS, defaults.brightness).coerceIn(BRIGHTNESS_RANGE),
            tail = prefs.getFloat(TAIL, defaults.tail).coerceIn(TAIL_RANGE),
            layers = prefs.getInt(LAYERS, defaults.layers).coerceIn(LAYERS_RANGE),
            typing = prefs.getBoolean(TYPING, true),
            delete = prefs.getBoolean(DELETE, true),
            commit = prefs.getBoolean(COMMIT, true),
            switch = prefs.getBoolean(SWITCH, true),
            hold = prefs.getBoolean(HOLD, true),
            space = prefs.getBoolean(SPACE, true),
            voice = prefs.getBoolean(VOICE, true),
        )
    }

    /** 设置页改细调项用；总开关走 [setEnabled]（性能页也拨它）。 */
    fun save(prefs: SharedPreferences, config: KeyGapLightConfig) {
        prefs.edit()
            .putBoolean(ENABLED, config.enabled)
            .putString(STYLE, config.style.id)
            .putFloat(SPEED, config.speed.coerceIn(SPEED_RANGE))
            .putFloat(REACH, config.reach.coerceIn(REACH_RANGE))
            .putFloat(WIDTH, config.widthDp.coerceIn(WIDTH_RANGE))
            .putFloat(BRIGHTNESS, config.brightness.coerceIn(BRIGHTNESS_RANGE))
            .putFloat(TAIL, config.tail.coerceIn(TAIL_RANGE))
            .putInt(LAYERS, config.layers.coerceIn(LAYERS_RANGE))
            .putBoolean(TYPING, config.typing)
            .putBoolean(DELETE, config.delete)
            .putBoolean(COMMIT, config.commit)
            .putBoolean(SWITCH, config.switch)
            .putBoolean(HOLD, config.hold)
            .putBoolean(SPACE, config.space)
            .putBoolean(VOICE, config.voice)
            .apply()
    }
}
