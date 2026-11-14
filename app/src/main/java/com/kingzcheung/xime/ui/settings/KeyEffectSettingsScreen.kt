package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.settings.KeysConfigHelper
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import com.kingzcheung.xime.ui.keyboard.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.KeyEffectSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyEffectSettingsContent(
    onBack: () -> Unit
) {
    val viewModel: KeyEffectSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    KeyboardSettingsScaffold("按键效果", onBack, preview = true) { modifier ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = modifier.settingsGutterScroll(listState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()
        ) {
            item {
                SettingsSection(title = "按键音效", content = {
                    Row(
                        modifier = Modifier.settingsSearchAnchor("启用按键音")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用按键音",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "按键时播放音效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.soundEnabled,
                            onCheckedChange = { viewModel.setSoundEnabled(it) }
                        )
                    }

                    if (uiState.soundEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        // 按键音色：和音量一样是按键音的从属行，按键音关掉时一起隐藏；写入即时生效（FeedbackManager 监听这个键）。
                        KeySoundStyleSetting()
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(
                            modifier = Modifier.settingsSearchAnchor("音量大小").padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "音量大小",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${uiState.soundVolume}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = uiState.soundVolume.toFloat(),
                                onValueChange = { viewModel.setSoundVolume(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        KeySoundCapturableSetting()
                    }
                })
            }

            item {
                SettingsSection(title = "振动反馈", content = {
                    Column(modifier = Modifier.settingsSearchAnchor("触觉反馈模式").padding(16.dp)) {
                        Text(
                            text = "触觉反馈模式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            // 有人调了半天不知道强弱在哪调（用户转述 10-31）：直接说强弱归谁管。
                            text = "跟随系统：强弱跟手机设置里的触感强度；想自己调强弱，选「启用」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 卡片高度按字号算：图示两行文字 + 间距 + 卡片内外边距 + 下方标题，大字体下也不裁字。
                        val hapticRowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
                            HAPTIC_CARD_TEXT_HEIGHT.toDp() + MaterialTheme.typography.bodyMedium.lineHeight.toDp()
                        } + 4.dp + 32.dp + 6.dp + 4.dp
                        Row(
                            modifier = Modifier.fillMaxWidth().height(maxOf(104.dp, hapticRowHeight)),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "following_system" to "跟随系统",
                                "enabled" to "启用",
                                "disabled" to "关闭"
                            ).forEach { (value, label) ->
                                IllustratedChoiceCard(label, uiState.hapticMode == value,
                                    onClick = { viewModel.setHapticMode(value) },
                                    modifier = Modifier.weight(1f), tag = "haptic-mode:$value") {
                                    HapticModeIllustration(value)
                                }
                            }
                        }
                    }

                    if (uiState.hapticMode != "disabled") {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier.settingsSearchAnchor("按键抬起振动")
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "按键抬起振动",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "手指抬起时触发短振动",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.hapticOnKeyUp,
                                onCheckedChange = { viewModel.setHapticOnKeyUp(it) }
                            )
                        }
                    }

                    if (uiState.hapticMode == "enabled") {
                        // 线性马达点击触感的机型上时长由马达决定，滑条不起作用就不显示。
                        if (!uiState.hapticPrimitives) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(modifier = Modifier.settingsSearchAnchor("按键振动时长").padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "按键振动时长",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.pressDuration == 0) "系统默认" else "${uiState.pressDuration} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = uiState.pressDuration.toFloat(),
                                onValueChange = { viewModel.setPressDuration(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(modifier = Modifier.settingsSearchAnchor("长按振动时长").padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "长按振动时长",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.longPressDuration == 0) "系统默认" else "${uiState.longPressDuration} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = uiState.longPressDuration.toFloat(),
                                onValueChange = { viewModel.setLongPressDuration(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }
                        }

                        if (uiState.hasAmplitudeControl || uiState.hapticPrimitives) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Column(modifier = Modifier.settingsSearchAnchor("按键振动振幅").padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "按键振动振幅",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.pressAmplitude == 0) "系统默认" else "${uiState.pressAmplitude}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Slider(
                                    value = uiState.pressAmplitude.toFloat(),
                                    onValueChange = { viewModel.setPressAmplitude(it.toInt()) },
                                    valueRange = 0f..255f,
                                    steps = 50
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Column(modifier = Modifier.settingsSearchAnchor("长按振动振幅").padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "长按振动振幅",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.longPressAmplitude == 0) "系统默认" else "${uiState.longPressAmplitude}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Slider(
                                    value = uiState.longPressAmplitude.toFloat(),
                                    onValueChange = { viewModel.setLongPressAmplitude(it.toInt()) },
                                    valueRange = 0f..255f,
                                    steps = 50
                                )
                            }
                        }

                        Text(
                            text = if (uiState.hapticPrimitives) "为 0(「系统默认」)时用内置的轻触强度。「长按振动振幅」管按住删除键连删时的每一下，为 0 时和按键振动一样。"
                                else "都为 0(「系统默认」)时用系统默认振动。「长按」两项管按住删除键连删时的每一下，为 0 时和按键振动一样。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }
                })
            }
            item {
                KeyVisualEffectSettings(uiState.keyGlowEnabled, uiState.keyAnimationEnabled,
                    viewModel::setKeyGlowEnabled, viewModel::setKeyAnimationEnabled,
                    uiState.gapLight, viewModel::setGapLightEnabled, viewModel::setGapLight)
            }
        }
    }
}

@Composable
internal fun KeyVisualEffectSettings(
    glowEnabled: Boolean, animationEnabled: Boolean,
    onGlowChange: (Boolean) -> Unit, onAnimationChange: (Boolean) -> Unit,
    gapLight: com.kingzcheung.xime.settings.KeyGapLightConfig = com.kingzcheung.xime.settings.KeyGapLightConfig(),
    onGapLightChange: (Boolean) -> Unit = {},
    onGapLightConfigChange: (com.kingzcheung.xime.settings.KeyGapLightConfig) -> Unit = {},
) {
    SettingsSection(title = "按键视觉效果", content = {
        Column(Modifier.padding(16.dp)) {
            KeyVisualEffectToggle("按键动效", "点按时键帽缩放回弹。",
                animationEnabled, onAnimationChange, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_ANIMATION)
            Spacer(Modifier.height(12.dp))
            KeyVisualEffectToggle("键内炫光", "点按时显示半秒渐变光效。",
                glowEnabled, onGlowChange, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GLOW)
            Spacer(Modifier.height(12.dp))
            KeyVisualEffectToggle("键缝动效", "点按时光线沿键缝往四周走，删除时往回收，颜色跟主题。",
                gapLight.enabled, onGapLightChange, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT)
            if (gapLight.enabled) KeyGapLightDetailSettings(gapLight, onGapLightConfigChange)
            Spacer(Modifier.height(12.dp))
            Text("首次使用时按设备配置推荐。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("点按下方按键预览", style = MaterialTheme.typography.labelMedium)
            val preview = rememberKeyboardInputPreferences().copy(
                keyGlowEnabled = glowEnabled, keyAnimationEnabled = animationEnabled, gapLight = gapLight)
            CompositionLocalProvider(LocalKeyboardInputPreferences provides preview,
                LocalKeyCornerRadius provides KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp) {
                // 预览也带键缝光层：和键盘上一样画在键帽底下，从这四个键的键缝里露出来。
                val gapLightField = rememberKeyGapLight()
                CompositionLocalProvider(LocalKeyGapLight provides gapLightField) {
                    Box(Modifier.fillMaxWidth().height(56.dp)) {
                        KeyGapLightLayer(gapLightField, Modifier.matchParentSize())
                        Row(Modifier.fillMaxSize()) {
                            listOf("A", "あ", "123").forEach { label ->
                                KeyButton(label, {}, MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f))
                            }
                            ActionKeyButton("删除", {}, MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    })
}

@Composable
private fun KeyVisualEffectToggle(title: String, description: String, enabled: Boolean, onChange: (Boolean) -> Unit,
    costItem: com.kingzcheung.xime.settings.PerformanceCosts.Item) {
    Row(Modifier.settingsSearchAnchor(title, bleed = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PerformanceCostNote(costItem)
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/** 键缝动效的细调项：样式、六根滑条、七个分项开关。改了立即生效，下面的预览行可以直接点着看。 */
@Composable
private fun KeyGapLightDetailSettings(
    config: com.kingzcheung.xime.settings.KeyGapLightConfig,
    onChange: (com.kingzcheung.xime.settings.KeyGapLightConfig) -> Unit,
) {
    val ranges = com.kingzcheung.xime.settings.KeyGapLightPreferences
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("样式", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
            HAPTIC_CARD_TEXT_HEIGHT.toDp() + MaterialTheme.typography.bodyMedium.lineHeight.toDp()
        } + 4.dp + 32.dp + 6.dp + 4.dp
        Row(Modifier.fillMaxWidth().height(maxOf(96.dp, rowHeight)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                com.kingzcheung.xime.settings.KeyGapLightStyle.TRACE to ("┼" to "沿键缝走"),
                com.kingzcheung.xime.settings.KeyGapLightStyle.FRAME to ("□" to "方框扩开"),
                com.kingzcheung.xime.settings.KeyGapLightStyle.DIAMOND to ("◇" to "菱形扩开"),
            ).forEach { (style, art) ->
                IllustratedChoiceCard(style.title, config.style == style, onClick = { onChange(config.copy(style = style)) },
                    modifier = Modifier.weight(1f), tag = "key-gap-light-style:${style.id}") {
                    val primary = MaterialTheme.colorScheme.primary
                    Text(art.first, fontSize = 20.sp, lineHeight = 24.sp, color = primary)
                    Spacer(Modifier.height(4.dp))
                    Text(art.second, fontSize = 12.sp, lineHeight = 16.sp, color = primary, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        KeyGapLightSlider("速度", "%.2f 倍".format(config.speed), config.speed, ranges.SPEED_RANGE, 5) {
            onChange(config.copy(speed = it))
        }
        KeyGapLightSlider("范围", "%.1f 个键".format(config.reach), config.reach, ranges.REACH_RANGE, 5) {
            onChange(config.copy(reach = it))
        }
        KeyGapLightSlider("线宽", "%.1f".format(config.widthDp), config.widthDp, ranges.WIDTH_RANGE, 5) {
            onChange(config.copy(widthDp = it))
        }
        KeyGapLightSlider("亮度", "${Math.round(config.brightness * 100)}%", config.brightness, ranges.BRIGHTNESS_RANGE, 6) {
            onChange(config.copy(brightness = it))
        }
        KeyGapLightSlider("拖尾", "${Math.round(config.tail * 100)}%", config.tail, ranges.TAIL_RANGE, 9) {
            onChange(config.copy(tail = it))
        }
        KeyGapLightSlider("层数", "${config.layers} 道", config.layers.toFloat(),
            ranges.LAYERS_RANGE.first.toFloat()..ranges.LAYERS_RANGE.last.toFloat(), ranges.LAYERS_RANGE.last - ranges.LAYERS_RANGE.first - 1) {
            onChange(config.copy(layers = Math.round(it)))
        }
        // 画多少跟这三根滑条走：调大了每帧要画的线段更多。
        Text("范围、层数、拖尾调大，每帧要画的线段更多。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        // 每一种单独记性能开销（用户 11-11「性能一定要分的细一点」）：觉得卡可以只关最费的那种。
        // 每种动效的代价项见 PerformanceCosts.Item.KEY_GAP_LIGHT_*。
        KeyGapLightEventToggle("打字", "按键时光线往四周走。", config.typing, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_TYPING) { onChange(config.copy(typing = it)) }
        KeyGapLightEventToggle("删除", "删除时光线往回收。", config.delete, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_DELETE) { onChange(config.copy(delete = it)) }
        KeyGapLightEventToggle("上屏和发送", "点空格一块光往上走，点回车整排往上扫。", config.commit, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_COMMIT) { onChange(config.copy(commit = it)) }
        KeyGapLightEventToggle("切换大小写", "大写出现时从 Shift 扩开，取消时收回去。", config.switch, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_SWITCH) { onChange(config.copy(switch = it)) }
        KeyGapLightEventToggle("长按进度", "按住时线沿键边走一圈，走满就是长按生效。", config.hold, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_HOLD) { onChange(config.copy(hold = it)) }
        KeyGapLightEventToggle("空格移光标", "色块跟着手指走，每走一格留一个刻度。", config.space, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_SPACE) { onChange(config.copy(space = it)) }
        KeyGapLightEventToggle("语音音量", "听写时两排键之间的键缝当音量条，从中间往两边长。", config.voice, com.kingzcheung.xime.settings.PerformanceCosts.Item.KEY_GAP_LIGHT_VOICE) { onChange(config.copy(voice = it)) }
    }
}

@Composable
private fun KeyGapLightSlider(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** 整行开关：标题、一行说明、这一种的性能开销、右侧 Switch。 */
@Composable
private fun KeyGapLightEventToggle(title: String, description: String, checked: Boolean,
    costItem: com.kingzcheung.xime.settings.PerformanceCosts.Item, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PerformanceCostNote(costItem)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 录屏时包含按键音：按键音的从属行，按键音关掉时一起隐藏；默认关。
 * 键名与 FeedbackManager.KEY_SOUND_CAPTURABLE 相同，FeedbackManager 监听这个键即时切换。
 */
@Composable
private fun KeySoundCapturableSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
    var capturable by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(prefs.getBoolean("key_sound_capturable", false))
    }
    val onChange: (Boolean) -> Unit = {
        capturable = it
        prefs.edit().putBoolean("key_sound_capturable", it).apply()
    }
    Row(Modifier.settingsSearchAnchor("录屏时包含按键音").fillMaxWidth().toggleable(value = capturable, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("录屏时包含按键音", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("开启后录屏能录到按键音；按键音改跟随媒体音量，静音和振动模式下仍不出声",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = capturable, onCheckedChange = null)
    }
}

/** 按键音色二选一，沿用原有的 default / mahjong 存储值。 */
@Composable
private fun KeySoundStyleSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
    val key = com.kingzcheung.xime.service.FeedbackManager.KEY_SOUND_STYLE
    // 以前选过已删的「清脆」（crisp）按默认显示，和 FeedbackManager 的处理一致。
    var style by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.kingzcheung.xime.service.FeedbackManager.resolveKeySoundStyle(
            prefs.getString(key, null), hardware = false))
    }
    Column(modifier = Modifier.settingsSearchAnchor("按键音色").padding(16.dp)) {
        Text(text = "按键音色", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(text = "换一种按键声音，选好立即生效", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
            // 标题按两行算：「Rain75 紫薇轴」在窄屏要换行才放得下。
            HAPTIC_CARD_TEXT_HEIGHT.toDp() + MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 2
        } + 4.dp + 32.dp + 6.dp + 4.dp
        Row(modifier = Modifier.fillMaxWidth().height(maxOf(104.dp, rowHeight)),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("mahjong", "Rain75 紫薇轴", "▣" to "默认 · 线性轴"), Triple("default", "经典音色", "♪" to "屏幕键盘原音")).forEach { (value, label, art) ->
                IllustratedChoiceCard(label, style == value, onClick = {
                    style = value
                    prefs.edit().putString(key, value).apply()
                }, modifier = Modifier.weight(1f), tag = "key-sound-style:$value", titleMaxLines = 2) {
                    val primary = MaterialTheme.colorScheme.primary
                    Text(art.first, fontSize = 20.sp, lineHeight = 24.sp, color = primary)
                    Spacer(Modifier.height(4.dp))
                    Text(art.second, fontSize = 12.sp, lineHeight = 16.sp, color = primary, maxLines = 1)
                }
            }
        }
    }
}
