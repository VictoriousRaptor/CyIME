package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.kingzcheung.xime.R
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.kingzcheung.xime.platform.AndroidPlatform
import com.kingzcheung.xime.platform.DeviceVendor

enum class HapticMode(val value: String) {
    FollowingSystem("following_system"),
    Enabled("enabled"),
    Disabled("disabled");

    companion object {
        fun fromValue(value: String): HapticMode =
            entries.find { it.value == value } ?: FollowingSystem
    }
}

class FeedbackManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()

    private val vibrator: Vibrator by lazy {
        if (AndroidPlatform.vibratorManager) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var hasAmplitudeControl = false

    private val samsungTuned = isSamsungTuned(AndroidPlatform.vendor)

    private val bypassSystemKeyHaptic = bypassesSystemKeyHaptic(AndroidPlatform.vendor)

    private val feedbackScope = CoroutineScope(Dispatchers.Default)

    private var soundEnabled = true
    private var soundVolume = 50
    private var hapticMode = HapticMode.FollowingSystem
    private var hapticOnKeyUp = false
    private var pressDuration = 0L
    private var longPressDuration = 0L
    private var pressAmplitude = 0
    private var longPressAmplitude = 0
    private var cursorStepHaptic = true
    private var cursorStepSound = false
    private var deleteRepeatSpeed = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.DEFAULT

    companion object {
        /** 设置页「按键音色」写入的偏好键。 */
        const val KEY_SOUND_STYLE = "key_sound_style"

        /**
         * 按键音色：default（原有）或 mahjong（显示名「Rain75 紫薇轴」，用户实录）；只影响普通键、空格、回车、删除。
         * 未选音色时软键盘和实体键盘都用 Rain75；显式选用原音或旧值的软键盘保持原音。
         */
        fun resolveKeySoundStyle(stored: String?, hardware: Boolean): String =
            if (hardware || stored == null || stored == "mahjong") "mahjong" else "default"

        /** 不支持点击触感的普通马达上，按键震动时长：短于约 30 ms 时转子马达基本震不起来。 */
        fun plainMotorDurationMs(longPress: Boolean, light: Boolean): Long = when {
            longPress -> 70L
            light -> 30L
            else -> 45L
        }

        /**
         * 能调振幅、却不报告点击原语和预设效果的马达（多是线性马达，如部分小米机型）：45 ms 长脉冲在线性马达上
         * 会持续共振成「嗡」，只给十几毫秒，打出来是「哒」。
         */
        fun linearMotorDurationMs(longPress: Boolean, light: Boolean): Long = when {
            longPress -> 30L
            light -> 10L
            else -> 15L
        }

        /** 拿不到点击触感时自己发的脉冲长度：三星沿用实测的转子马达时长，其他机型能调振幅时按线性马达给短脉冲。 */
        fun fallbackPulseMs(samsungTuned: Boolean, hasAmplitudeControl: Boolean, longPress: Boolean, light: Boolean): Long =
            if (samsungTuned || !hasAmplitudeControl) plainMotorDurationMs(longPress, light)
            else linearMotorDurationMs(longPress, light)

        /** 按键触感按三星实测调过（原语 click/tick、转子马达 45 ms）；其他厂商默认交给系统按键触感。 */
        fun isSamsungTuned(vendor: DeviceVendor): Boolean = vendor == DeviceVendor.SAMSUNG

        /**
         * 不交给系统按键触感（KEYBOARD_TAP）的机型：三星按实测自己震；华为（鸿蒙 4.x）有用户反馈 10-30 改走系统触感后
         * 打字不震（未真机确认），退回改动前的自己震（原语 / 预设 / 短脉冲）。荣耀（MagicOS，和华为同一套系统底子）
         * 11-11 有用户反馈 Magic7 Pro 没有震动（未真机确认），同样自己震。
         */
        fun bypassesSystemKeyHaptic(vendor: DeviceVendor): Boolean =
            isSamsungTuned(vendor) || vendor == DeviceVendor.HUAWEI || vendor == DeviceVendor.HONOR

        /**
         * 非三星机型在没自定义时长、振幅时直接用系统按键触感（View.performHapticFeedback）：
         * 小米澎湃OS、Pixel 等由系统映射到厂商调校的短促点击，和自带输入法、微信输入法同一条路。
         * 「启用」模式下本机支持原语时仍按原语（设置页的振幅滑条要起作用），系统触感关着时也不走这条（系统会不震）。
         */
        fun prefersSystemHaptic(
            bypassSystem: Boolean, custom: Boolean, duration: Long, amplitude: Int,
            primitivesSupported: Boolean, systemHapticEnabled: Boolean
        ): Boolean = !bypassSystem && duration == 0L && amplitude == 0 && systemHapticEnabled &&
            (!custom || !primitivesSupported)

        /** 删除音连发时只保留最新一层；其他按键音照常可以重叠。 */
        fun chokesPrevious(keyType: String): Boolean = keyType == "delete"

        /** 按住删除键时每 30 ms 连删一下（「长按删除速度」标准档）；离上一下这么近的只能是连删，手指连点到不了。 */
        const val DELETE_REPEAT_GAP_MS = 80L

        /**
         * 连删时振动的最小间隔（用户转述 10-31：打字震得弱、长按删除震得很强）。原来每删一下都震，30 ms 一下的
         * 短震首尾相接成一长串，比单击强得多；现在每一下和打字一样强，中间留出停顿，一秒约八下。
         */
        const val DELETE_REPEAT_HAPTIC_MS = 100L

        fun isDeleteRepeat(nowUptime: Long, lastPressUptime: Long, gapMs: Long = DELETE_REPEAT_GAP_MS): Boolean =
            lastPressUptime > 0L && nowUptime - lastPressUptime < gapMs

        fun deleteRepeatHapticDue(nowUptime: Long, lastHapticUptime: Long, gapMs: Long = DELETE_REPEAT_HAPTIC_MS): Boolean =
            nowUptime - lastHapticUptime >= gapMs

        /**
         * 连删振动间隔跟着「长按删除速度」换档。「启用」里把「长按振动时长」调长了（连删用它），间隔至少比一下振动
         * 长 20 ms，免得一下没振完下一下又来，连成一长串嗡；没调（0）时就是档位本身的间隔。
         */
        fun deleteRepeatHapticGapMs(speed: com.kingzcheung.xime.keyboard.DeleteRepeatSpeed, customLongPressMs: Long): Long =
            if (customLongPressMs > 0L) maxOf(speed.hapticGapMs, customLongPressMs + 20L) else speed.hapticGapMs

        /** 设置页「录屏时包含按键音」写入的偏好键，默认关。 */
        const val KEY_SOUND_CAPTURABLE = "key_sound_capturable"

        const val KEY_CURSOR_STEP_HAPTIC = "cursor_step_haptic"
        const val KEY_CURSOR_STEP_SOUND = "cursor_step_sound"

        /** 系统只允许录屏抓取媒体/游戏类声音；默认仍按系统提示音播放（录不到、跟系统音量）。 */
        fun soundUsage(capturable: Boolean): Int =
            if (capturable) AudioAttributes.USAGE_GAME else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION

        /** 游戏类声音不会被系统静音，静音/振动模式下由我们自己不出声，保持与提示音一致。 */
        fun mutedByRinger(capturable: Boolean, ringerMode: Int): Boolean =
            capturable && ringerMode != AudioManager.RINGER_MODE_NORMAL

        /**
         * 勿扰模式会静音系统提示音，却放过游戏类声音；可录屏时也由我们自己按勿扰不出声：
         * 只有「全部允许」，或优先模式里放行了系统声音，才出声。
         */
        fun mutedByDnd(capturable: Boolean, interruptionFilter: Int, systemSoundsAllowed: Boolean): Boolean =
            capturable && when (interruptionFilter) {
                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> !systemSoundsAllowed
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE,
                android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                else -> false
            }

        /** 可录屏的声音此刻该不该静音：静音 / 振动模式和勿扰都看；不可录屏时交给系统。 */
        fun mutedForCapture(context: Context, capturable: Boolean): Boolean {
            if (!capturable) return false
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mutedByRinger(true, audio.ringerMode)) return true
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val filter = notifications.currentInterruptionFilter
            val systemAllowed = filter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY && runCatching {
                notifications.notificationPolicy.priorityCategories and
                    android.app.NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM != 0
            }.getOrDefault(false)
            return mutedByDnd(true, filter, systemAllowed)
        }
    }

    private var keySoundStyle: String? = null
    private var soundCapturable = false

    private fun readKeySoundStyle(): String? =
        SettingsPreferences.getPrefsPublic(context).getString(KEY_SOUND_STYLE, null)

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun initialize() {
        hasAmplitudeControl = vibrator.hasAmplitudeControl()
        if (AndroidPlatform.hapticQuery) {
            android.util.Log.i(
                "FeedbackManager",
                "haptics click=${vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)} " +
                    "tick=${vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)} " +
                    "effectTick=${vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_TICK)} amp=$hasAmplitudeControl"
            )
        }
        soundCapturable = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_SOUND_CAPTURABLE, false)
        initSoundPool()
        loadSettings()
        registerPrefsListener()
    }

    fun release() {
        prefsListener?.let {
            SettingsPreferences.getPrefsPublic(context).unregisterOnSharedPreferenceChangeListener(it)
        }
        soundPool?.release()
        soundPool = null
        feedbackScope.cancel()
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(soundUsage(soundCapturable))
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        soundPool?.let { pool ->
            soundIds["standard"] = pool.load(context, R.raw.kb_key_standard, 1)
            soundIds["delete"] = pool.load(context, R.raw.kb_key_delete, 1)
            soundIds["space"] = pool.load(context, R.raw.kb_key_space, 1)
            soundIds["enter"] = pool.load(context, R.raw.kb_key_enter, 1)
            // Rain75 音色预加载（每个仅十几 KB），切换和实体键盘首键都不用等解码。
            soundIds["mahjong:standard"] = pool.load(context, R.raw.kb_rec_normal, 1)
            soundIds["mahjong:delete"] = pool.load(context, R.raw.kb_rec_backspace, 1)
            soundIds["mahjong:space"] = pool.load(context, R.raw.kb_rec_space, 1)
            soundIds["mahjong:enter"] = pool.load(context, R.raw.kb_rec_enter, 1)
            soundIds["voice_start"] = pool.load(context, R.raw.kb_voice_start, 1)
            soundIds["voice_end"] = pool.load(context, R.raw.kb_voice_end, 1)
            soundIds["handwriting_erase"] = pool.load(context, R.raw.kb_handwriting_erase, 1)
        }
    }

    private fun loadSettings() {
        soundEnabled = SettingsPreferences.isSoundEnabled(context)
        soundVolume = SettingsPreferences.getSoundVolume(context)
        keySoundStyle = readKeySoundStyle()
        hapticMode = HapticMode.fromValue(SettingsPreferences.getHapticMode(context))
        hapticOnKeyUp = SettingsPreferences.isHapticOnKeyUp(context)
        pressDuration = SettingsPreferences.getVibrationPressDuration(context).toLong()
        longPressDuration = SettingsPreferences.getVibrationLongPressDuration(context).toLong()
        pressAmplitude = SettingsPreferences.getVibrationPressAmplitude(context)
        longPressAmplitude = SettingsPreferences.getVibrationLongPressAmplitude(context)
        readCursorStepSettings()
        readDeleteRepeatSpeed()
    }

    private fun readDeleteRepeatSpeed() {
        deleteRepeatSpeed = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.fromName(
            SettingsPreferences.getPrefsPublic(context).getString(com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.KEY, null))
    }

    /** 「移动光标时振动 / 播放按键音」两个开关，键名与 KeyboardInputPreferences 一致。 */
    private fun readCursorStepSettings() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        cursorStepHaptic = prefs.getBoolean(KEY_CURSOR_STEP_HAPTIC, true)
        cursorStepSound = prefs.getBoolean(KEY_CURSOR_STEP_SOUND, false)
    }

    private fun registerPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "sound_enabled" -> soundEnabled = SettingsPreferences.isSoundEnabled(context)
                "sound_volume" -> soundVolume = SettingsPreferences.getSoundVolume(context)
                KEY_SOUND_STYLE -> keySoundStyle = readKeySoundStyle()
                KEY_SOUND_CAPTURABLE -> {
                    val capturable = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_SOUND_CAPTURABLE, false)
                    if (capturable != soundCapturable) {
                        // 音频用途在 SoundPool 建立时固定，切换时重建（十几个小文件，重新加载很快）。
                        soundCapturable = capturable
                        soundPool?.release()
                        soundIds.clear()
                        initSoundPool()
                    }
                }
                "haptic_mode" -> hapticMode = HapticMode.fromValue(SettingsPreferences.getHapticMode(context))
                KEY_CURSOR_STEP_HAPTIC, KEY_CURSOR_STEP_SOUND -> readCursorStepSettings()
                com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.KEY -> readDeleteRepeatSpeed()
                "haptic_on_keyup" -> hapticOnKeyUp = SettingsPreferences.isHapticOnKeyUp(context)
                "vibration_press_duration" -> pressDuration = SettingsPreferences.getVibrationPressDuration(context).toLong()
                "vibration_long_press_duration" -> longPressDuration = SettingsPreferences.getVibrationLongPressDuration(context).toLong()
                "vibration_press_amplitude" -> pressAmplitude = SettingsPreferences.getVibrationPressAmplitude(context)
                "vibration_long_press_amplitude" -> longPressAmplitude = SettingsPreferences.getVibrationLongPressAmplitude(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun playKeySound(keyType: String = "standard", hardware: Boolean = false) {
        if (!soundEnabled) return
        if (soundCapturable && runCatching { mutedForCapture(context, true) }.getOrDefault(false)) return

        val pool = soundPool ?: return
        val style = resolveKeySoundStyle(keySoundStyle, hardware)
        val soundId = soundIds["$style:$keyType"] ?: soundIds[keyType] ?: soundIds["standard"] ?: return
        val volume = soundVolume / 100f

        // 长按删除每 30 ms 连发一次：新的一下切断上一下删除音，避免多层叠成刺耳的嗡声。
        if (chokesPrevious(keyType)) lastDeleteStream.takeIf { it != 0 }?.let { pool.stop(it) }
        val stream = pool.play(soundId, volume, volume, 1, 0, 1.0f)
        if (chokesPrevious(keyType)) lastDeleteStream = stream
    }

    private var lastDeleteStream = 0

    /** When a physical key last went down (uptime ms), sounding or not. */
    @Volatile var lastHardwareKeyUptime = 0L
        private set

    /**
     * Enter sound for a clipboard record put into the text; skipped when a physical key just chose it
     * (that key already made its own sound).
     */
    fun playClipboardCommitSound(nowUptime: Long = android.os.SystemClock.uptimeMillis()) {
        if (clipboardCommitNeedsSound(nowUptime, lastHardwareKeyUptime)) playKeySound("enter")
    }

    /** 外接实体键盘按下时调用；只出声，不振动。 */
    fun playHardwareKeySound(event: android.view.KeyEvent) {
        lastHardwareKeyUptime = event.eventTime
        val sound = HardwareKeySound.soundFor(event.keyCode, event.repeatCount,
            event.isCtrlPressed, event.isAltPressed, event.isMetaPressed) ?: return
        playKeySound(sound, hardware = true)
    }

    /**
     * [deleteRepeat]：按住删除键连删的一下。「启用」里调过「长按振动时长/振幅」时用那两项，没调（都为 0）时和按键振动一样。
     */
    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false, type: KeyFeedbackType = KeyFeedbackType.CHARACTER,
        deleteRepeat: Boolean = false) {
        // 未开启松手反馈时，不必读取系统触觉设置。
        if (keyUp && !hapticOnKeyUp) return
        if (hapticMode == HapticMode.Disabled) return
        val systemEnabled = systemHapticEnabled()
        if (hapticMode == HapticMode.FollowingSystem && !systemEnabled) return

        // 自定义时长、振幅只在「启用」模式下显示，跟随系统时不使用残留值。
        val custom = hapticMode == HapticMode.Enabled
        val useLong = longPress || (deleteRepeat && (longPressDuration != 0L || longPressAmplitude != 0))
        val duration = if (!custom) 0L else if (useLong) longPressDuration else pressDuration
        val amplitude = if (!custom) 0 else if (useLong) longPressAmplitude else pressAmplitude
        val hfc: Int = if (longPress) {
            HapticFeedbackConstants.LONG_PRESS
        } else if (keyUp) {
            HapticFeedbackConstants.KEYBOARD_RELEASE
        } else if (type == KeyFeedbackType.CURSOR_STEP || type == KeyFeedbackType.HANDWRITING_ERASE) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }

        val light = keyUp || hfc == HapticFeedbackConstants.TEXT_HANDLE_MOVE
        if (prefersSystemHaptic(bypassSystemKeyHaptic, custom, duration, amplitude, primitivesSupported, systemEnabled)) {
            if (view.performHapticFeedback(hfc, systemHapticFlags())) return
            // 系统没接（返回 false：厂商没映射这种触感、视图已脱离窗口等）时往下自己震，不让这一下没反馈。
        }
        // 线性马达优先：按下和长按是满强度 click，抬起/光标是稍轻的 tick；
        // 时长由马达决定，振幅设置只调强弱。三星上系统默认时不再交给 KEYBOARD_TAP（偏重）。
        val primitive = if (light) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
        val defaultScale = if (light) 0.8f else 1f
        if (vibratePrimitive(primitive, amplitude, defaultScale)) return
        if (duration == 0L && amplitude == 0 && vibratePredefined(if (light) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK)) return
        // 普通转子马达（如 Tab S8）：系统 KEYBOARD_TAP 只给 20 ms，马达还没转起来就停了，几乎感觉不到；
        // 改用与三星系统界面相同量级的短震（按键 45 ms、光标/抬起 30 ms、长按 70 ms）。
        // 其他厂商能调振幅的马达（自定义了振幅，或系统触感关着时的「启用」）只给十几毫秒，避免线性马达嗡嗡响。
        if (duration == 0L && AndroidPlatform.hapticQuery && vibrator.hasVibrator()) {
            val amp = if (hasAmplitudeControl && amplitude != 0) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            vibrateTouch(VibrationEffect.createOneShot(fallbackPulseMs(samsungTuned, hasAmplitudeControl, longPress, light), amp))
            return
        }
        if (duration != 0L) {
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            view.performHapticFeedback(hfc, systemHapticFlags())
        }
    }

    private fun systemHapticEnabled(): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
    } catch (_: Exception) {
        true
    }

    private fun systemHapticFlags(): Int {
        var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        if (hapticMode == HapticMode.Enabled) {
            @Suppress("DEPRECATION")
            flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        }
        return flags
    }

    private val primitivesSupported: Boolean by lazy {
        AndroidPlatform.hapticQuery &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
    }

    /** 移动光标每走一字：按两个开关分别轻震、出声，仍受全局振动模式和按键音开关约束。 */
    fun cursorStepFeedback(view: View) {
        if (cursorStepSound) playKeySound(KeyFeedbackType.CURSOR_STEP.sound)
        if (cursorStepHaptic) hapticFeedback(view, type = KeyFeedbackType.CURSOR_STEP)
    }

    fun performKeyPressEffect(keyType: String = "toolbar", view: View) {
        val type = KeyFeedbackType.fromKey(keyType)
        playKeySound(type.sound)
        hapticFeedback(view, type = type)
    }

    private var lastDeletePressUptime = 0L
    private var lastDeleteHapticUptime = 0L

    fun performKeyPressDownEffect(key: String, view: View, nowUptime: Long = android.os.SystemClock.uptimeMillis()) {
        val type = KeyFeedbackType.fromKey(key)
        playKeySound(type.sound)
        if (type != KeyFeedbackType.DELETE) {
            hapticFeedback(view, type = type)
            return
        }
        // 连删：声音照旧每下都有，振动按「长按删除速度」的间隔隔开（标准档 100 ms）；单点删除照常每下都震。
        val speed = deleteRepeatSpeed
        val repeat = isDeleteRepeat(nowUptime, lastDeletePressUptime, speed.repeatGapMs)
        lastDeletePressUptime = nowUptime
        val customLong = if (hapticMode == HapticMode.Enabled) longPressDuration else 0L
        if (repeat && !deleteRepeatHapticDue(nowUptime, lastDeleteHapticUptime, deleteRepeatHapticGapMs(speed, customLong))) return
        lastDeleteHapticUptime = nowUptime
        hapticFeedback(view, type = type, deleteRepeat = repeat)
    }


    /** 设备支持组合原语（线性马达）时按原语震动；振幅 0 用 [defaultScale]。不支持时返回 false 交给原路径。 */
    private fun vibratePrimitive(primitive: Int, amplitude: Int, defaultScale: Float = 1f): Boolean {
        if (!AndroidPlatform.hapticQuery) return false
        if (!vibrator.areAllPrimitivesSupported(primitive)) return false
        val scale = if (amplitude != 0) (amplitude / 255f).coerceIn(0.05f, 1f) else defaultScale
        vibrateTouch(VibrationEffect.startComposition().addPrimitive(primitive, scale).compose())
        return true
    }

    /** 不支持原语但马达确认支持预设效果（tick/click）时使用；状态未知时不用，交回系统触感。 */
    private fun vibratePredefined(effect: Int): Boolean {
        if (!AndroidPlatform.hapticQuery) return false
        if (vibrator.areAllEffectsSupported(effect) != Vibrator.VIBRATION_EFFECT_SUPPORT_YES) return false
        vibrateTouch(VibrationEffect.createPredefined(effect))
        return true
    }

    /** 跟随系统时标记为触摸反馈，随系统“触摸互动”强度；强制启用时不受系统开关影响。 */
    private fun vibrateTouch(effect: VibrationEffect) {
        if (hapticMode == HapticMode.FollowingSystem && AndroidPlatform.touchVibrationUsage) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            vibrator.vibrate(effect)
        }
    }

    fun performVibration() {
        if (hapticMode == HapticMode.Disabled) return
        if (!vibrator.hasVibrator()) return
        val custom = hapticMode == HapticMode.Enabled
        val pressAmp = if (custom) pressAmplitude else 0
        if (vibratePrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, pressAmp)) return
        if (!samsungTuned && pressAmp == 0 && !(custom && pressDuration != 0L) &&
            vibratePredefined(VibrationEffect.EFFECT_CLICK)) return
        val duration = if (custom && pressDuration != 0L) pressDuration
            else fallbackPulseMs(samsungTuned, hasAmplitudeControl, longPress = false, light = false)
        val amplitude = if (hasAmplitudeControl && pressAmp != 0) pressAmp else VibrationEffect.DEFAULT_AMPLITUDE
        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }
}

/** A clipboard record chosen within this long after a physical key press was chosen by that key. */
internal const val HARDWARE_PICK_WINDOW_MS = 400L

internal fun clipboardCommitNeedsSound(nowUptime: Long, lastHardwareKeyUptime: Long): Boolean =
    lastHardwareKeyUptime <= 0L || nowUptime - lastHardwareKeyUptime > HARDWARE_PICK_WINDOW_MS
