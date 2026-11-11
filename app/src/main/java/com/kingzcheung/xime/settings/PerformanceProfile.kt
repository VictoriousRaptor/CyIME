package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe
import com.kingzcheung.xime.runtime.adaptation.PerformanceTier
import com.kingzcheung.xime.runtime.adaptation.PerformanceTiers

/**
 * 性能预设页管的几项。都是已有设置，预设只是一组值，不另存一份。
 * 前四项每档都写；后四项（11-03 用户「档位要更强」加的）只关不开：中配、低配把它们关掉，高配不动，
 * 所以选高配不会替用户打开没开过的效果（见 [PerformanceProfile.turnsOff]）。
 */
data class PerformanceSettings(
    val keyAnimation: Boolean,
    val keyGlow: Boolean,
    /** 空闲多少分钟后卸掉 SenseVoice 第二遍、保留 Paraformer；0 = 永不。 */
    val speechRefinerIdleMinutes: Int,
    /** 空闲多少分钟后卸掉全部语音模型；0 = 常驻。 */
    val speechEngineIdleMinutes: Int,
    /** 液态玻璃（主题与外观）；玻璃关着时它不起作用，算不开。 */
    val liquidGlass: Boolean = false,
    /** 点按弹出气泡（主题与外观）。 */
    val pressBubble: Boolean = false,
    /** 滑行时实时显示词（26 键）。 */
    val glidePreview: Boolean = false,
    /** 手写边写边识别。 */
    val handwritingLive: Boolean = false,
)

/**
 * 自动 / 高配 / 中配 / 低配 / 自定义。
 * 当前值和某一档完全一致就显示那一档，否则显示“自定义”；用户改任何一项都会关掉“自动”。
 */
object PerformanceProfile {
    private const val KEY_AUTO = "performance_profile_auto"
    /**
     * 「自动」套用的是哪一档。以 device_ 开头，备份不带走（[BackupManager.stripDeviceState]），换机器后按新机器重新判断。
     * 旧版本没有这一项：当时套的那档就是现在的值对上的那档。
     */
    private const val KEY_AUTO_TIER = "device_performance_auto_tier"

    val presets: Map<PerformanceTier, PerformanceSettings> = mapOf(
        PerformanceTier.LOW to PerformanceSettings(keyAnimation = false, keyGlow = false,
            speechRefinerIdleMinutes = 1, speechEngineIdleMinutes = 1),
        PerformanceTier.MEDIUM to PerformanceSettings(keyAnimation = true, keyGlow = false,
            speechRefinerIdleMinutes = 5, speechEngineIdleMinutes = 30),
        PerformanceTier.HIGH to PerformanceSettings(keyAnimation = true, keyGlow = true,
            speechRefinerIdleMinutes = 30, speechEngineIdleMinutes = 0),
    )

    /** 档位只关不开的几项（见 [PerformanceSettings]）：高配不动，中配关液态玻璃，低配全关。 */
    enum class Extra { LIQUID_GLASS, PRESS_BUBBLE, GLIDE_PREVIEW, HANDWRITING_LIVE }

    val turnsOff: Map<PerformanceTier, Set<Extra>> = mapOf(
        PerformanceTier.HIGH to emptySet(),
        PerformanceTier.MEDIUM to setOf(Extra.LIQUID_GLASS),
        PerformanceTier.LOW to Extra.entries.toSet(),
    )

    private fun PerformanceSettings.isOn(extra: Extra): Boolean = when (extra) {
        Extra.LIQUID_GLASS -> liquidGlass
        Extra.PRESS_BUBBLE -> pressBubble
        Extra.GLIDE_PREVIEW -> glidePreview
        Extra.HANDWRITING_LIVE -> handwritingLive
    }

    private fun PerformanceSettings.core() = PerformanceSettings(keyAnimation, keyGlow, speechRefinerIdleMinutes, speechEngineIdleMinutes)

    /** 选这一档之后这几项是什么样：前四项换成这档的值，[turnsOff] 里的关掉，其余照旧。 */
    fun applied(current: PerformanceSettings, tier: PerformanceTier): PerformanceSettings {
        val preset = checkNotNull(presets[tier])
        val off = turnsOff.getValue(tier)
        return preset.copy(
            liquidGlass = current.liquidGlass && Extra.LIQUID_GLASS !in off,
            pressBubble = current.pressBubble && Extra.PRESS_BUBBLE !in off,
            glidePreview = current.glidePreview && Extra.GLIDE_PREVIEW !in off,
            handwritingLive = current.handwritingLive && Extra.HANDWRITING_LIVE !in off,
        )
    }

    fun read(context: Context): PerformanceSettings {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val input = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context)
        return PerformanceSettings(
            KeyEffectPreferences.animationEnabled(prefs), KeyEffectPreferences.glowEnabled(prefs),
            SettingsPreferences.getSttRefinerIdleMinutes(context), SettingsPreferences.getSttEngineIdleMinutes(context),
            liquidGlass = FrostedGlassPreferences.readProfiles(context).dark.enabled && SettingsPreferences.glassLiquid(context),
            pressBubble = SettingsPreferences.shouldShowPressBubble(context),
            glidePreview = input.glidePreview,
            handwritingLive = input.handwritingRecognizeWhileWriting)
    }

    /** 空闲释放时间的几个档：1、5、10、30、60 分钟和“永不”（0）。明细滑条和 [score] 共用。 */
    val idleLevels = listOf(1, 5, 10, 30, 60, 0)

    /** 落在第几档；旧设置里不在档上的值算最接近的那档（与滑条显示一致）。 */
    fun idleLevelIndex(minutes: Int): Int = idleLevels.indexOf(minutes).takeIf { it >= 0 }
        ?: idleLevels.indices.filter { idleLevels[it] != 0 }.minBy { kotlin.math.abs(idleLevels[it] - minutes) }

    /**
     * 配置高低，0～100：0 = 这页能调到的最省（低配），100 = 全开。按这页管的几项算：
     * 语音模型全部释放前常驻多久 40 分，二次校正模型常驻多久 30 分（最多跟全部释放一样久），
     * 按键动效 15 分，炫光 15 分；释放时间按滑条档位等分。三档都用同一算法，所以选中某档时正好落在那档的刻度上。
     */
    fun score(settings: PerformanceSettings): Int {
        val top = (idleLevels.size - 1).toDouble()
        val engine = idleLevelIndex(settings.speechEngineIdleMinutes) / top
        // 全部释放时校正模型也一起卸掉，比全部释放更晚的设置不多算。
        val refiner = minOf(idleLevelIndex(settings.speechRefinerIdleMinutes) / top, engine)
        val points = 40 * engine + 30 * refiner +
            (if (settings.keyAnimation) 15 else 0) + (if (settings.keyGlow) 15 else 0)
        return Math.round(points).toInt().coerceIn(0, 100)
    }

    /** 当前值对应的档位；null = 自定义。前四项要和这档一样，这档要关的几项要关着；高配不管后四项。 */
    fun matching(settings: PerformanceSettings): PerformanceTier? =
        presets.entries.firstOrNull { (tier, preset) ->
            preset.core() == settings.core() && turnsOff.getValue(tier).none { settings.isOn(it) }
        }?.key

    /**
     * “自动”只在当前值仍是自动套上的那一档时成立：在「按键效果」里单独改了动效、炫光，或别处改了语音释放，
     * 页面就显示“自定义”，不会一边勾着自动一边用着另一组值。
     */
    fun isAuto(context: Context): Boolean {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        return isAuto(prefs.getBoolean(KEY_AUTO, false), read(context), autoTier(context))
    }

    /** [applied] 是自动套上的那档；null = 旧版本没记，值对得上任一档就算。 */
    fun isAuto(chosenAuto: Boolean, settings: PerformanceSettings, applied: PerformanceTier?): Boolean {
        val tier = matching(settings) ?: return false
        return chosenAuto && (applied == null || tier == applied)
    }

    /** 自动套上的那档；没选自动或旧版本没记返回 null。 */
    fun autoTier(context: Context): PerformanceTier? {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (!prefs.getBoolean(KEY_AUTO, false)) return null
        return prefs.getString(KEY_AUTO_TIER, null)?.let { name -> PerformanceTier.entries.firstOrNull { it.name == name } }
            ?: matching(read(context))
    }

    fun detectedTier(context: Context): PerformanceTier = PerformanceTiers.classify(DeviceCapabilityProbe.read(context))

    /** 选“自动”：按本机配置套用对应档，并记住是自动选的、选的哪档。 */
    fun applyAuto(context: Context): PerformanceTier {
        val tier = detectedTier(context)
        write(context, checkNotNull(presets[tier]))
        turnOff(context, turnsOff.getValue(tier))
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(KEY_AUTO, true).putString(KEY_AUTO_TIER, tier.name).apply()
        return tier
    }

    /**
     * CPU 刚测完：还在「自动」、实测定出了档、和自动套上的那档不一样，就改套新的一档。
     * 没选自动、改过任何一项（已经是自定义）、或分档线还没定，都不动。返回改成的档，没改返回 null。
     */
    fun onCpuMeasured(context: Context): PerformanceTier? {
        if (!isAuto(context)) return null
        val verdict = PerformanceTiers.explain(DeviceCapabilityProbe.read(context))
        if (verdict.cpuTier == null) return null
        return if (verdict.tier != autoTier(context)) applyAuto(context) else null
    }

    fun applyPreset(context: Context, tier: PerformanceTier) {
        write(context, checkNotNull(presets[tier]))
        turnOff(context, turnsOff.getValue(tier))
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(KEY_AUTO, false).apply()
    }

    /** 单项手动改动：写入后不再算“自动”，页面按值自动显示对应档或“自定义”。 */
    fun applyCustom(context: Context, settings: PerformanceSettings) {
        write(context, settings)
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(KEY_AUTO, false).apply()
    }

    /**
     * 新装默认就是“自动”：按键效果本来就按同一档位初始化（[com.kingzcheung.xime.runtime.adaptation.DeviceKeyEffectDefaults]），
     * 语音释放也写同一档，否则新装就显示“自定义”，好像用户改过。
     */
    internal fun writeFirstRunAuto(editor: android.content.SharedPreferences.Editor, tier: PerformanceTier) {
        val settings = checkNotNull(presets[tier])
        // 动效、炫光由 DeviceDefaults 按 DeviceKeyEffectDefaults 写，与这一档相同（PerformanceProfileTest 检查）。
        editor.putInt(SettingsPreferences.KEY_STT_REFINER_IDLE_MINUTES, settings.speechRefinerIdleMinutes)
            .putInt(SettingsPreferences.KEY_STT_ENGINE_IDLE_MINUTES, settings.speechEngineIdleMinutes)
            .putBoolean(SettingsPreferences.KEY_STT_KEEP_ENGINE_ALIVE, settings.speechEngineIdleMinutes == 0)
            .putBoolean(KEY_AUTO, true)
            .putString(KEY_AUTO_TIER, tier.name)
        // 按键浮标 11-09 起默认开，不再“本来就关着”：这档要关它（低配）时新装就关上，性能页才显示“自动”而不是“自定义”。
        if (Extra.PRESS_BUBBLE in turnsOff.getValue(tier)) editor.putBoolean(SettingsPreferences.KEY_SHOW_PRESS_BUBBLE, false)
    }

    private const val KEY_PRESS_BUBBLE_FIT = "press_bubble_tier_fit_v1"

    /**
     * 升级上来的老用户：按键浮标 11-09 起默认开，低配档只在新装时关（[writeFirstRunAuto]），升级的低配机还开着，
     * 加上 1.15.75 的气泡动画打字发卡（11-11 用户反馈「升级后慢动作，重装就好」）。没碰过这个开关的，
     * 这档要关它就关上一次；自己开过或关过的不动。只做一次。
     */
    internal fun fitPressBubbleOnUpgrade(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_PRESS_BUBBLE_FIT, false)) return
        val editor = prefs.edit().putBoolean(KEY_PRESS_BUBBLE_FIT, true)
        val tier = storedTier(prefs)
        if (tier != null && !prefs.contains(SettingsPreferences.KEY_SHOW_PRESS_BUBBLE) && Extra.PRESS_BUBBLE in turnsOff.getValue(tier)) {
            editor.putBoolean(SettingsPreferences.KEY_SHOW_PRESS_BUBBLE, false)
        }
        editor.apply()
    }

    /**
     * 老安装不重新测硬件（[com.kingzcheung.xime.model.DeviceDefaults] 的约定）：用记下的自动档，没有就用首次安装时记下的
     * 内存、核数、主频按同一套分档线算；都没记过返回 null，不动。
     */
    private fun storedTier(prefs: android.content.SharedPreferences): PerformanceTier? {
        prefs.getString(KEY_AUTO_TIER, null)?.let { name -> PerformanceTier.entries.firstOrNull { it.name == name } }?.let { return it }
        val memory = prefs.getLong("device_initial_memory_bytes", -1L).takeIf { it > 0 } ?: return null
        val hardware = com.kingzcheung.xime.runtime.adaptation.DeviceCapabilities(0, prefs.getInt("device_initial_sdk", 0),
            prefs.getString("device_initial_abis", "").orEmpty().split(",").filter { it.isNotBlank() },
            prefs.getString("device_initial_low_ram", null)?.toBooleanStrictOrNull(), memory,
            prefs.getInt("device_initial_cores", -1).takeIf { it > 0 }, prefs.getLong("device_initial_max_cpu_khz", -1L).takeIf { it > 0 })
        return com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.classify(hardware)
    }

    /** 只关不开：和各自设置页写同样的值（[PerformanceCosts.set]）。新装时除按键浮标外这几项本来就关着（按键浮标见 [writeFirstRunAuto]）。 */
    private fun turnOff(context: Context, extras: Set<Extra>) {
        val current = read(context)
        extras.filter { current.isOn(it) }.forEach { extra ->
            PerformanceCosts.set(context, when (extra) {
                Extra.LIQUID_GLASS -> PerformanceCosts.Item.LIQUID_GLASS
                Extra.PRESS_BUBBLE -> PerformanceCosts.Item.PRESS_BUBBLE
                Extra.GLIDE_PREVIEW -> PerformanceCosts.Item.GLIDE_PREVIEW
                Extra.HANDWRITING_LIVE -> PerformanceCosts.Item.HANDWRITING_LIVE
            }, false)
        }
    }

    private fun write(context: Context, settings: PerformanceSettings) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        KeyEffectPreferences.setAnimationEnabled(prefs, settings.keyAnimation)
        KeyEffectPreferences.setGlowEnabled(prefs, settings.keyGlow)
        SettingsPreferences.setSttIdleLadder(context, settings.speechRefinerIdleMinutes, settings.speechEngineIdleMinutes)
    }
}
