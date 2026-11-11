package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilities
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe
import com.kingzcheung.xime.runtime.adaptation.DeviceKeyEffectDefaults
import com.kingzcheung.xime.settings.KeyEffectPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.SpeechModelCatalog

/** A conservative first-run recommendation, not a benchmark or an ongoing override. */
object DeviceDefaults {
    internal const val VERSION = "device_model_defaults_version"
    internal const val TIER = "device_model_defaults_tier"
    private const val VOICE = "device_default_voice_model"

    fun initialize(context: Context) = initialize(context) { DeviceCapabilityProbe.read(context) }

    /** Must run before other first-run preference writers. One atomic defaults snapshot. */
    @Synchronized internal fun initialize(context: Context, probe: () -> DeviceCapabilities) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.contains(VERSION)) {
            com.kingzcheung.xime.settings.PerformanceProfile.fitPressBubbleOnUpgrade(prefs)
            return
        }
        val fresh = prefs.all.isEmpty() && listOf("asr_model", "default_models", "bundled_models").all {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()
        }
        val editor = prefs.edit()
        if (fresh) {
            val hardware = probe()
            val profile = DeviceModelProfiles.choose(hardware,
                if (com.kingzcheung.xime.BuildConfig.BUNDLED_MODELS) BundledModelInstaller.bundledIds(context) else emptySet())
            val effects = DeviceKeyEffectDefaults.choose(hardware)
            editor.putString(TIER, profile.tier.name)
                .putString(VOICE, profile.voiceModel)
                // 新装默认连续联想（用户 11-08）；老用户没存过这项时仍按单次读，不跟着变。
                .putBoolean(SettingsPreferences.KEY_ASSOCIATION_SINGLE_MODE, false)
                .putBoolean(KeyEffectPreferences.GLOW, effects.glow)
                .putBoolean(KeyEffectPreferences.ANIMATION, effects.animation)
                .also { com.kingzcheung.xime.settings.PerformanceProfile.writeFirstRunAuto(it,
                    com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.classify(hardware)) }
                .putLong("device_initial_memory_bytes", hardware.totalMemoryBytes ?: -1)
                .putInt("device_initial_cores", hardware.processors ?: -1)
                .putLong("device_initial_max_cpu_khz", hardware.maxCpuKHz ?: -1)
                .putString("device_initial_low_ram", hardware.lowRam?.toString() ?: "unknown")
                .putInt("device_initial_sdk", hardware.sdk)
                .putString("device_initial_abis", hardware.abis.joinToString(","))
        } else {
            // Missing keys on an existing installation do not prove the user wanted new defaults.
            editor.putString(TIER, "PRESERVED")
        }
        check(editor.putInt(VERSION, 1).commit()) { "无法保存设备模型默认配置" }
        if (fresh && com.kingzcheung.xime.BuildConfig.ELDER) applyElderDefaults(context)
    }

    /**
     * The elder package (BuildConfig.ELDER, docs/development/elder-pack-spec.md). Fresh
     * installs only; existing settings are never changed:
     * - 九键拼音 as the keyboard, with 手写 one tap away on the toolbar;
     * - toolbar 语音、手写、字幕、拍照识别、表情、剪贴板、输入方式 (no cursor editing tools);
     * - the separate 字幕 home-screen icon on (the 字幕 plugin itself is installed by CaptionPlugin.migrateOnce,
     *   which treats the elder package as a caption user);
     * - larger candidates (24 sp) and a taller keyboard (40 % of the long screen side);
     * - the high-contrast 黑白 theme, which is already the default.
     */
    private fun applyElderDefaults(context: Context) {
        SettingsPreferences.setToolbarButtons(context,
            listOf(ToolbarButton.VOICE, ToolbarButton.HANDWRITING_LOOKUP, ToolbarButton.CAPTION, ToolbarButton.OCR,
                ToolbarButton.EMOJI, ToolbarButton.CLIPBOARD, ToolbarButton.SCHEMA).map { it.id })
        SettingsPreferences.setCurrentSchema(context, "t9_pinyin")
        SettingsPreferences.setCandidateTextSize(context, ELDER_CANDIDATE_SP)
        val config = context.resources.configuration
        SettingsPreferences.setKeyboardHeightDp(context, maxOf(config.screenWidthDp, config.screenHeightDp) * ELDER_HEIGHT_PERCENT / 100)
        SettingsPreferences.setKeyboardTheme(context, com.kingzcheung.xime.ui.theme.PureBlackTheme.ID)
        runCatching { com.kingzcheung.xime.ui.caption.CaptionLauncherIcon.setEnabled(context, true) }
    }

    private const val ELDER_CANDIDATE_SP = 24
    private const val ELDER_HEIGHT_PERCENT = 40

    fun voiceModel(context: Context): String {
        val initial = SettingsPreferences.getPrefsPublic(context).getString(VOICE, null)
        // This key stores an automatic recommendation. Explicit selections live in asr_model
        // and take priority in AsrModelManager, including a deliberate Zipformer selection.
        return when (initial) {
            null, SpeechModelCatalog.ZIPFORMER, SpeechModelCatalog.ZIPFORMER_TWO_PASS,
            SpeechModelCatalog.TWO_PASS -> SpeechModelCatalog.PARAFORMER
            else -> initial
        }
    }

    fun supportsRefinement(lowRam: Boolean, memoryBytes: Long, cores: Int): Boolean =
        !lowRam && memoryBytes >= 6L * 1024 * 1024 * 1024 && cores >= 4

    fun supportsRefinement(context: Context): Boolean {
        return DeviceModelProfiles.choose(DeviceCapabilityProbe.read(context)).tier == DeviceModelTier.ENHANCED
    }
}
