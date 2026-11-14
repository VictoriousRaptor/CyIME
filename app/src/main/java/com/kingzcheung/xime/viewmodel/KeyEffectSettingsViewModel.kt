package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.KeyEffectPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.kingzcheung.xime.platform.AndroidPlatform

data class KeyEffectUiState(
    val soundEnabled: Boolean = true,
    val keyGlowEnabled: Boolean = false,
    val keyAnimationEnabled: Boolean = false,
    val gapLight: com.kingzcheung.xime.settings.KeyGapLightConfig = com.kingzcheung.xime.settings.KeyGapLightConfig(),
    val soundVolume: Int = 50,
    val hapticMode: String = "following_system",
    val hapticOnKeyUp: Boolean = false,
    val pressDuration: Int = 0,
    val longPressDuration: Int = 0,
    val pressAmplitude: Int = 0,
    val longPressAmplitude: Int = 0,
    val hasAmplitudeControl: Boolean = false,
    /** 支持点击/轻触组合原语（线性马达）：按键触感由马达决定时长，时长设置不生效（见 FeedbackManager.vibratePrimitive）。 */
    val hapticPrimitives: Boolean = false,
)

class KeyEffectSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(KeyEffectUiState(
        soundEnabled = SettingsPreferences.isSoundEnabled(context),
        keyGlowEnabled = KeyEffectPreferences.glowEnabled(SettingsPreferences.getPrefsPublic(context)),
        keyAnimationEnabled = KeyEffectPreferences.animationEnabled(SettingsPreferences.getPrefsPublic(context)),
        gapLight = com.kingzcheung.xime.settings.KeyGapLightPreferences.read(SettingsPreferences.getPrefsPublic(context)),
        soundVolume = SettingsPreferences.getSoundVolume(context),
        hapticMode = SettingsPreferences.getHapticMode(context),
        hapticOnKeyUp = SettingsPreferences.isHapticOnKeyUp(context),
        pressDuration = SettingsPreferences.getVibrationPressDuration(context),
        longPressDuration = SettingsPreferences.getVibrationLongPressDuration(context),
        pressAmplitude = SettingsPreferences.getVibrationPressAmplitude(context),
        longPressAmplitude = SettingsPreferences.getVibrationLongPressAmplitude(context)
    ))
    val uiState: StateFlow<KeyEffectUiState> = _uiState.asStateFlow()

    init {
        updateHasAmplitudeControl()
    }

    private fun updateHasAmplitudeControl() {
        val vibrator = if (AndroidPlatform.vibratorManager) {
            val vm = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        val hasAmp = vibrator.hasAmplitudeControl()
        val primitives = AndroidPlatform.hapticQuery &&
            vibrator.areAllPrimitivesSupported(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK,
                android.os.VibrationEffect.Composition.PRIMITIVE_TICK)
        _uiState.update { it.copy(hasAmplitudeControl = hasAmp, hapticPrimitives = primitives) }
    }

    fun setKeyGlowEnabled(enabled: Boolean) {
        KeyEffectPreferences.setGlowEnabled(SettingsPreferences.getPrefsPublic(context), enabled)
        _uiState.update { it.copy(keyGlowEnabled = enabled) }
    }

    fun setKeyAnimationEnabled(enabled: Boolean) {
        KeyEffectPreferences.setAnimationEnabled(SettingsPreferences.getPrefsPublic(context), enabled)
        _uiState.update { it.copy(keyAnimationEnabled = enabled) }
    }

    fun setGapLightEnabled(enabled: Boolean) {
        com.kingzcheung.xime.settings.KeyGapLightPreferences.setEnabled(SettingsPreferences.getPrefsPublic(context), enabled)
        _uiState.update { it.copy(gapLight = it.gapLight.copy(enabled = enabled)) }
    }

    /** 键缝动效的细调项（样式、滑条、分项开关）。 */
    fun setGapLight(config: com.kingzcheung.xime.settings.KeyGapLightConfig) {
        com.kingzcheung.xime.settings.KeyGapLightPreferences.save(SettingsPreferences.getPrefsPublic(context), config)
        _uiState.update { it.copy(gapLight = config) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        SettingsPreferences.setSoundEnabled(context, enabled)
        _uiState.update { it.copy(soundEnabled = enabled) }
    }

    fun setSoundVolume(volume: Int) {
        SettingsPreferences.setSoundVolume(context, volume)
        _uiState.update { it.copy(soundVolume = volume) }
    }

    fun setHapticMode(mode: String) {
        SettingsPreferences.setHapticMode(context, mode)
        _uiState.update { it.copy(hapticMode = mode) }
    }

    fun setHapticOnKeyUp(enabled: Boolean) {
        SettingsPreferences.setHapticOnKeyUp(context, enabled)
        _uiState.update { it.copy(hapticOnKeyUp = enabled) }
    }

    fun setPressDuration(duration: Int) {
        SettingsPreferences.setVibrationPressDuration(context, duration)
        _uiState.update { it.copy(pressDuration = duration) }
    }

    fun setLongPressDuration(duration: Int) {
        SettingsPreferences.setVibrationLongPressDuration(context, duration)
        _uiState.update { it.copy(longPressDuration = duration) }
    }

    fun setPressAmplitude(amplitude: Int) {
        SettingsPreferences.setVibrationPressAmplitude(context, amplitude)
        _uiState.update { it.copy(pressAmplitude = amplitude) }
    }

    fun setLongPressAmplitude(amplitude: Int) {
        SettingsPreferences.setVibrationLongPressAmplitude(context, amplitude)
        _uiState.update { it.copy(longPressAmplitude = amplitude) }
    }
}
