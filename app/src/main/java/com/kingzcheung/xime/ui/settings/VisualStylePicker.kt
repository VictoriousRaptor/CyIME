package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyle
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.KeyShape
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.visualMaterial
import com.kingzcheung.xime.ui.theme.visualEnvironment
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.LocalMaterialPalette
import com.kingzcheung.xime.ui.theme.MaterialPalette
import kotlin.math.roundToInt

@Composable
internal fun VisualStylePicker(selected: VisualStyle, onSelect: (VisualStyle) -> Unit) {
    val keyboard = LocalKeyboardPalette.current
    val keyRadius = KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp
    Column(Modifier.settingsSearchAnchor("视觉样式", bleed = 6.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("视觉样式", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text("只改变材质。明暗、配色和背景始终跟随主题设置。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VisualStyle.offered.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { style ->
                    val palette = MaterialTheme.colorScheme
                    val surface = keyboard?.background ?: palette.surface
                    val accent = keyboard?.accent ?: palette.primary
                    val key = keyboard?.key ?: palette.surfaceContainerHigh
                    val ink = keyboard?.text ?: palette.onSurface
                    val shape = RoundedCornerShape(16.dp)
                    CompositionLocalProvider(LocalMaterialPalette provides MaterialPalette(surface, accent)) {
                    Column(Modifier.weight(1f).testTag("visual-style-${style.id}")
                        .semantics { this.selected = selected == style }
                        .clip(shape).background(surface).visualEnvironment(style)
                        .border(if (selected == style) 2.dp else 1.dp,
                            accent.copy(alpha = if (selected == style) 1f else .24f), shape)
                        .clickable { onSelect(style) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("拼音    你好", color = keyboard?.candidateText ?: ink, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)
                                .visualMaterial(style, 6.dp, level = MaterialLevel.BASE)
                                .padding(horizontal = 7.dp, vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("A", "S", "D").forEach { label ->
                                Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(keyRadius))
                                    .background(key)
                                    .visualMaterial(style, keyRadius), contentAlignment = Alignment.Center) {
                                    Text(label, color = ink, fontSize = 16.sp)
                                }
                            }
                        }
                        Text(style.title + if (selected == style) " · 已选" else "",
                            color = ink, style = MaterialTheme.typography.labelLarge)
                    }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Key corners: one preview of real-size keys in the current material and colours, and a slider under it (11-11: four
 * cards with the same two keys were redundant). The slider has exactly the four stored shapes as stops, ordered by
 * radius, so nothing is stored differently and an upgraded keyboard keeps its look. Dragging only moves the preview;
 * the choice is saved on release, so the keyboard theme is not reloaded on every step.
 */
@Composable
internal fun KeyShapePicker(selected: KeyShape, onSelect: (KeyShape) -> Unit) {
    val keyboard = LocalKeyboardPalette.current
    val style = VisualStyles.current
    // The theme's own radius; the preview caps it the way the keyboard would.
    val configured = KeysConfigHelper.configuredKeyCornerRadius()
    val stops = KEY_SHAPE_SLIDER_STOPS
    var position by remember(selected) { mutableFloatStateOf(stops.indexOf(selected).coerceAtLeast(0).toFloat()) }
    val shown = stops[position.roundToInt().coerceIn(0, stops.lastIndex)]
    Column(Modifier.settingsSearchAnchor("按键形状", bleed = 6.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("按键形状：${shown.title}", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text("所有布局和面板的按键一起改；蜂窝布局的六边形字母键不变。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val palette = MaterialTheme.colorScheme
        val surface = keyboard?.background ?: palette.surface
        val accent = keyboard?.accent ?: palette.primary
        val key = keyboard?.key ?: palette.surfaceContainerHigh
        val ink = keyboard?.text ?: palette.onSurface
        val radius = shown.radius(configured).dp
        val cardShape = RoundedCornerShape(16.dp)
        CompositionLocalProvider(LocalMaterialPalette provides MaterialPalette(surface, accent)) {
            Row(Modifier.fillMaxWidth().testTag("key-shape-preview").clip(cardShape).background(surface)
                .visualEnvironment(style).border(1.dp, accent.copy(alpha = .24f), cardShape).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("A", "S", "D", "F").forEach { label ->
                    Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(radius))
                        .background(key)
                        .visualMaterial(style, radius), contentAlignment = Alignment.Center) {
                        Text(label, color = ink, fontSize = 16.sp)
                    }
                }
            }
        }
        Slider(value = position, onValueChange = { position = it },
            onValueChangeFinished = {
                val chosen = stops[position.roundToInt().coerceIn(0, stops.lastIndex)]
                if (chosen != selected) onSelect(chosen)
            },
            valueRange = 0f..stops.lastIndex.toFloat(), steps = stops.size - 2,
            modifier = Modifier.fillMaxWidth().testTag("key-shape-slider")
                .semantics { stateDescription = shown.title })
        // Stop names under the track: first and last at the ends, the two middle ones evenly between, like the ticks.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            stops.forEach { shape ->
                Text(shape.title, style = MaterialTheme.typography.bodySmall,
                    color = if (shape == shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Slider stops from square to round. Same four stored shapes as before; only the order on screen is by radius. */
internal val KEY_SHAPE_SLIDER_STOPS = listOf(KeyShape.SHARP, KeyShape.SQUARE, KeyShape.ROUNDED, KeyShape.ROUNDER)
