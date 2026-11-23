package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.ui.keyboard.floatingResizeHeightBounds
import com.kingzcheung.xime.ui.keyboard.floatingStoredHeightBounds
import com.kingzcheung.xime.ui.keyboard.keyboardHeightBounds
import com.kingzcheung.xime.ui.keyboard.keyboardHeightFloorDp

/** 固定键盘继续使用原有高度键；悬浮高度独立保存，横竖屏互不覆盖。 */
internal object KeyboardHeightProfiles {
    const val FLOATING_HEIGHT = "floating_height_dp"
    const val FLOATING_HEIGHT_LANDSCAPE = "floating_height_dp_landscape"

    private fun fixedKey(landscape: Boolean) =
        if (landscape) "keyboard_height_dp_landscape" else "keyboard_height_dp"

    private fun floatingKey(landscape: Boolean) =
        if (landscape) FLOATING_HEIGHT_LANDSCAPE else FLOATING_HEIGHT

    /**
     * 拖动、确认和读已存高度用的范围：下限按真实物理尺寸算（用户 11-22，见 [keyboardHeightFloorDp]），
     * 只比原来的 244dp 低；上限不变。
     */
    fun fixedBounds(context: Context, landscape: Boolean): IntRange =
        keyboardHeightBounds(context.resources.configuration.screenHeightDp, landscape,
            minHeightDp = keyboardHeightFloorDp(context))

    /** 算默认高度用的范围，和 1.15.104 一样（下限 244dp）：默认高度不跟着新下限变。 */
    fun defaultBounds(context: Context, landscape: Boolean): IntRange =
        keyboardHeightBounds(context.resources.configuration.screenHeightDp, landscape)

    /** 没调过时显示的固定高度（「重置」回到的也是它），和 1.15.104 一样。 */
    fun defaultFixed(context: Context, landscape: Boolean): Int =
        SettingsPreferences.getDefaultKeyboardHeightDp(context, landscape).coerceIn(defaultBounds(context, landscape))

    fun fixed(context: Context, landscape: Boolean): Int =
        if (hasSavedHeight(context, false, landscape))
            SettingsPreferences.getKeyboardHeightDp(context, landscape).coerceIn(fixedBounds(context, landscape))
        else defaultFixed(context, landscape)

    fun floating(context: Context, landscape: Boolean, hostHeightDp: Int): Int {
        val stored = SettingsPreferences.getPrefsPublic(context).getInt(floatingKey(landscape), 0)
        // 已存高度可能是拖到方形吸附点的值，低于默认下限也要原样保留；默认值仍用默认范围。
        if (stored > 0) return stored.coerceIn(floatingStoredHeightBounds(hostHeightDp, landscape))
        return SettingsPreferences.getDefaultKeyboardHeightDp(context, landscape)
            .coerceIn(floatingResizeHeightBounds(hostHeightDp, landscape))
    }

    fun selected(context: Context, floating: Boolean, landscape: Boolean, hostHeightDp: Int): Int =
        if (floating) floating(context, landscape, hostHeightDp) else fixed(context, landscape)

    fun hasSavedHeight(context: Context, floating: Boolean, landscape: Boolean): Boolean {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (floating) return prefs.getInt(floatingKey(landscape), 0) > 0
        // 只看这个方向自己存的高度；竖屏调过不算横屏调过，横屏没调过照默认尺寸排。
        return prefs.getInt(fixedKey(landscape), 0) > 0
    }

    /** 只有确认才调用。悬浮确认绝不写 keyboard_height_dp*。 */
    fun save(
        context: Context,
        floating: Boolean,
        landscape: Boolean,
        heightDp: Int,
        hostHeightDp: Int,
    ): Int {
        val height = if (floating) {
            heightDp.coerceIn(floatingStoredHeightBounds(hostHeightDp, landscape))
        } else {
            heightDp.coerceIn(fixedBounds(context, landscape))
        }
        if (floating) {
            SettingsPreferences.getPrefsPublic(context).edit()
                .putInt(floatingKey(landscape), height).apply()
        } else {
            SettingsPreferences.setKeyboardHeightDp(context, height, landscape)
        }
        return height
    }

    /**
     * 新版第一次启动时只做一次，横竖屏、手机平板都查：存着的固定高度若超出 1.14.25 规则允许的范围，
     * 或横屏值高于 1.14.25 的默认横屏高度（1.14.26–1.14.27 的最低档规则只会把横屏存值往上推，
     * 1.14.25 的默认值和用户拖动都不会落到那里），就写回 1.14.25 规则下的默认高度；范围内的值不动。
     * 横屏默认必须显式写入：横屏键缺失时 [SettingsPreferences.getKeyboardHeightDp] 会退回竖屏值，比默认还高。
     */
    fun normalizeFixedHeightsOnce(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val marker = "fixed_height_normalized_to_1_14_25_v1"
        if (prefs.getBoolean(marker, false)) return
        val config = context.resources.configuration
        val portraitHeightDp = maxOf(config.screenWidthDp, config.screenHeightDp)
        val landscapeHeightDp = minOf(config.screenWidthDp, config.screenHeightDp)
        val editor = prefs.edit().putBoolean(marker, true)
        for (landscape in listOf(false, true)) {
            val key = fixedKey(landscape)
            val stored = prefs.getInt(key, 0)
            if (stored <= 0) continue
            val bounds = keyboardHeightBounds(if (landscape) landscapeHeightDp else portraitHeightDp, landscape)
            val default = if (landscape)
                (portraitHeightDp * SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE / 100).coerceIn(bounds)
            else (portraitHeightDp * SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_PERCENT / 100).coerceIn(bounds)
            if (stored !in bounds || (landscape && stored > default)) editor.putInt(key, default)
        }
        editor.apply()
    }

    /**
     * 仅首次加载当前方向时修复旧版共用高度键造成的越界值。
     * 合法固定高度原样保留；低于固定下限的旧值先备份，再恢复固定默认高度。
     * 旧版已覆盖且仍在合法范围内的值无法可靠辨别，不擅自改写。
     */
    fun migrateLegacy(context: Context, landscape: Boolean, hostHeightDp: Int) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val suffix = if (landscape) "_landscape" else ""
        val marker = "height_profiles_v1$suffix"
        if (prefs.getBoolean(marker, false)) return

        val key = fixedKey(landscape)
        val raw = prefs.getInt(key, 0)
        val bounds = fixedBounds(context, landscape)
        val editor = prefs.edit().putBoolean(marker, true)
        val hasFloatingSettings = prefs.getBoolean("floating_mode$suffix", false) ||
            prefs.getInt("floating_width_dp$suffix", 0) > 0
        if (!prefs.contains(floatingKey(landscape)) && hasFloatingSettings && raw > 0) {
            editor.putInt(floatingKey(landscape), raw.coerceIn(floatingResizeHeightBounds(hostHeightDp, landscape)))
        }
        if (prefs.contains(key) && raw < bounds.first) {
            editor.putInt("${key}_before_height_profiles", raw)
            editor.putInt(key, defaultFixed(context, landscape))
        }
        // 同一 editor 批量写入，监听器不会读到“已打迁移标记但高度尚未修复”的半成品。
        editor.apply()
    }
}
