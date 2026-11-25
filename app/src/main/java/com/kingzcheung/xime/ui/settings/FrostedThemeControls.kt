package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.legibleForTheme
import com.kingzcheung.xime.ui.theme.SamsungWindowBlur
import kotlin.math.roundToInt
import com.kingzcheung.xime.platform.AndroidPlatform

/** Edits a preview draft, never writes preferences before Apply. */
@Composable
internal fun FrostedThemeControls(config: FrostedGlassConfig, isDark: Boolean,
    blurAvailable: Boolean = rememberCrossWindowBlurAvailable(), compact: Boolean = false,
    themeId: String? = null,
    onChange: (FrostedGlassConfig) -> Unit) {
    // 调得过透时键盘会自动加深（legibleForTheme）：滑条旁写出实际用的值，不然拖到很低看着没变化（GitHub #63）。
    val effective = remember(config, themeId, isDark) {
        themeId?.let { runCatching { config.legibleForTheme(it, isDark) }.getOrNull() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
        if (!compact) Text(if (isDark) "深色玻璃效果" else "浅色玻璃效果", style = MaterialTheme.typography.titleMedium)
        GlassSlider("模糊强度", "${config.blurRadiusDp.roundToInt()} dp" +
            if (blurAvailable) "" else " · 当前系统不可用", config.blurRadiusDp, 0f..40f, enabled = blurAvailable) {
            onChange(config.copy(blurRadiusDp = it.roundToInt().toFloat()))
        }
        // 白色玻璃底色最少 WHITE_GLASS_PANEL_OPACITY（见 FrostedKeyColors），没拖过时写出实际值；
        // 在浅色页拖过就按滑条显示，工具栏也跟着（lightPanelCustom）。
        val whiteGlassFloor = com.kingzcheung.xime.ui.keyboard.WHITE_GLASS_PANEL_OPACITY
        val backgroundActual = effective?.backgroundOpacity
            ?: if (!isDark && !config.lightPanelCustom && config.backgroundOpacity < whiteGlassFloor) whiteGlassFloor else null
        GlassSlider("背景不透明度", "${(config.backgroundOpacity * 100).roundToInt()}%" +
            actualSuffix(config.backgroundOpacity, backgroundActual),
            config.backgroundOpacity) {
            onChange(config.copy(backgroundOpacity = (it * 100).roundToInt() / 100f,
                lightPanelCustom = config.lightPanelCustom || !isDark))
        }
        GlassSlider("按键不透明度", "${(config.keyOpacity * 100).roundToInt()}%" +
            actualSuffix(config.keyOpacity, effective?.keyOpacity), config.keyOpacity) {
            onChange(config.copy(keyOpacity = (it * 100).roundToInt() / 100f))
        }
        if (!compact) Text("浅色、深色分别保存。太透时自动加深。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 实际用的值比滑条高（自动加深、白色玻璃补底）时写「· 实际 N%」；差不到 1% 不写。 */
internal fun actualSuffix(set: Float, actual: Float?): String {
    if (actual == null || !actual.isFinite()) return ""
    val shown = (set * 100).roundToInt()
    val real = (actual * 100).roundToInt()
    return if (real > shown) " · 实际 $real%" else ""
}

@Composable
private fun GlassSlider(label: String, valueLabel: String, value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onChange, valueRange = range, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun rememberCrossWindowBlurAvailable(): Boolean {
    if (!AndroidPlatform.windowBlur) return false
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(android.view.WindowManager::class.java) }
    var available by remember(manager) { mutableStateOf(runCatching { manager?.isCrossWindowBlurEnabled == true }.getOrDefault(false)) }
    DisposableEffect(manager) {
        val listener = java.util.function.Consumer<Boolean> { available = it }
        val registered = manager != null && runCatching {
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        }.isSuccess
        if (!registered) available = false
        onDispose {
            if (registered) runCatching { manager?.removeCrossWindowBlurEnabledListener(listener) }
        }
    }
    return available || SamsungWindowBlur.isAvailable
}
