package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.platform.AndroidPlatform

/** Both service sizing and the Compose docking preview read the same physical display. */
internal fun keyboardPhysicalMetrics(
    context: Context,
    attachedDisplay: Display? = null,
    pixelsPerDp: Float = context.resources.displayMetrics.density,
): KeyboardPhysicalMetrics? = KeyboardDisplayMetricsCache.read(context, attachedDisplay, pixelsPerDp)

private object KeyboardDisplayMetricsCache {
    private data class Key(
        val displayId: Int, val rotation: Int, val modeId: Int,
        val modeWidth: Int, val modeHeight: Int,
        val density: Float, val resourceXDpi: Float, val resourceYDpi: Float,
        val windowWidthDp: Int, val windowHeightDp: Int,
    )
    private var lastKey: Key? = null
    private var lastValue: KeyboardPhysicalMetrics? = null

    @Synchronized
    @Suppress("DEPRECATION") // Read physical dpi only, never use full-display bounds to size the keyboard.
    fun read(context: Context, attachedDisplay: Display?, density: Float): KeyboardPhysicalMetrics? = runCatching {
        val display = attachedDisplay ?: if (AndroidPlatform.windowMetrics) context.display
            else context.getSystemService(WindowManager::class.java)?.defaultDisplay
        if (display == null || !display.isValid) return@runCatching null
        val resources = context.resources
        val reported = resources.displayMetrics
        val config = resources.configuration
        val mode = display.mode
        val key = Key(display.displayId, display.rotation, mode.modeId, mode.physicalWidth, mode.physicalHeight,
            density, reported.xdpi, reported.ydpi, config.screenWidthDp, config.screenHeightDp)
        if (key == lastKey) return@runCatching lastValue
        val physical = DisplayMetrics()
        display.getRealMetrics(physical)
        val value = KeyboardPhysicalMetrics.from(density, physical.xdpi)
        // Keep only numbers for the current display/configuration, never a Context or a View.
        lastKey = key
        lastValue = value
        value
    }.getOrNull()
}

/**
 * 这块屏上 1dp 有多少毫米：用屏幕实际像素密度（xdpi，和分体键距标定同一个读数）换算，
 * 读不到或读数离谱时按 Android 标称（见 [plausibleMillimetersPerDp]）。
 */
internal fun keyboardMillimetersPerDp(
    context: Context,
    attachedDisplay: Display? = null,
    pixelsPerDp: Float = context.resources.displayMetrics.density,
): Float = plausibleMillimetersPerDp(keyboardPhysicalMetrics(context, attachedDisplay, pixelsPerDp)?.millimetersFromDp(1f))

/** 按物理尺寸算的键盘最低高度（dp），见 [physicalKeyboardFloorDp]。 */
internal fun keyboardHeightFloorDp(context: Context, attachedDisplay: Display? = null): Int =
    physicalKeyboardFloorDp(keyboardMillimetersPerDp(context, attachedDisplay))

/** 界面里用的 [keyboardHeightFloorDp]；和后台算范围用同一个 density（Context 的，不受候选栏/悬浮卡片改写的 LocalDensity 影响）。 */
@Composable
internal fun rememberKeyboardHeightFloorDp(): Int {
    val context = LocalContext.current
    val display = LocalView.current.display
    val configuration = LocalConfiguration.current
    return remember(context, display, configuration) { keyboardHeightFloorDp(context, display) }
}
