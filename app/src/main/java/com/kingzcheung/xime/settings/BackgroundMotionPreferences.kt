package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 动态背景怎么动：放大、缩小、左右平移，或三种接着来（组合）。
 * 组合原叫「轮换」，和轮播图片放在一起像是同一件事（用户 11-13），只改显示的字，存的 id 仍是 cycle。
 */
enum class BackgroundMotionEffect(val id: String, val label: String) {
    ZOOM_IN("zoom_in", "放大"),
    ZOOM_OUT("zoom_out", "缩小"),
    PAN("pan", "平移"),
    CYCLE("cycle", "组合");

    companion object {
        fun of(id: String?): BackgroundMotionEffect = entries.firstOrNull { it.id == id } ?: CYCLE
    }
}

/** 一段动作走多久（[shotMs]），越慢越不显眼。 */
enum class BackgroundMotionSpeed(val id: String, val label: String, val shotMs: Long) {
    SLOW("slow", "慢", 30_000L),
    NORMAL("normal", "中", 18_000L),
    FAST("fast", "快", 10_000L);

    companion object {
        fun of(id: String?): BackgroundMotionSpeed = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

data class BackgroundMotionSettings(
    val enabled: Boolean = false,
    val effect: BackgroundMotionEffect = BackgroundMotionEffect.CYCLE,
    val speed: BackgroundMotionSpeed = BackgroundMotionSpeed.NORMAL,
    /** 「我的图片」之外再加的轮播图片（themes/… 路径，按加入顺序）；文件由 CustomImageTheme 管。 */
    val slides: List<String> = emptyList(),
)

/**
 * 主题与外观 › 背景图片 › 动态背景（默认关）。只对图片背景（内置图片主题、我的图片）起作用；
 * 键盘怎么画、什么时候停见 ui/theme/KeyboardBackgroundMotion.kt。
 */
object BackgroundMotionPreferences {
    const val KEY_ENABLED = "keyboard_background_motion"
    const val KEY_EFFECT = "keyboard_background_motion_effect"
    const val KEY_SPEED = "keyboard_background_motion_speed"
    const val KEY_SLIDES = "keyboard_background_motion_slides"
    val KEYS = setOf(KEY_ENABLED, KEY_EFFECT, KEY_SPEED, KEY_SLIDES)
    /** 连同「我的图片」最多 9 张一起轮播。 */
    const val MAX_SLIDES = 8

    fun read(context: Context): BackgroundMotionSettings {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        return BackgroundMotionSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            effect = BackgroundMotionEffect.of(prefs.getString(KEY_EFFECT, null)),
            speed = BackgroundMotionSpeed.of(prefs.getString(KEY_SPEED, null)),
            slides = decodeSlides(prefs.getString(KEY_SLIDES, null)),
        )
    }

    fun setSlides(context: Context, slides: List<String>) {
        check(SettingsPreferences.getPrefsPublic(context).edit()
            .putString(KEY_SLIDES, encodeSlides(slides.take(MAX_SLIDES))).commit()) { "无法保存设置" }
    }

    /** 一行一张；路径里不会有换行。 */
    internal fun encodeSlides(slides: List<String>): String = slides.filter { it.isNotBlank() }.joinToString("\n")

    internal fun decodeSlides(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(MAX_SLIDES) ?: emptyList()

    fun setEnabled(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setEffect(context: Context, effect: BackgroundMotionEffect) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(KEY_EFFECT, effect.id).apply()
    }

    fun setSpeed(context: Context, speed: BackgroundMotionSpeed) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(KEY_SPEED, speed.id).apply()
    }
}
