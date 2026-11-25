package com.kingzcheung.xime.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

/** 屏蔽外放音的三种做法（用户 11-04 截图的选项和叫法）；默认不屏蔽，和以前一样（用户「默认不变」）。 */
enum class VoiceOtherAudio(val id: String, val label: String) {
    DUCK("duck", "压低音量"),
    PAUSE("pause", "暂停播放"),
    OFF("off", "不屏蔽");

    companion object {
        val DEFAULT = OFF
        fun fromId(id: String?): VoiceOtherAudio = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 屏蔽外放音（用户 2026-11-04 要的）：语音输入开麦时把正在放的音乐、视频压低或暂停，关麦就恢复。
 *
 * 暂停播放：申请临时独占音频焦点，播放器自己暂停、录完自己接着放。Android 15 起不在前台的应用申请焦点
 * 会被系统拒绝，输入法可能也算在内；这时退回为临时静音媒体音量。系统给了焦点、播放器却不理（还在放）时
 * 同样退回静音（用户 11-12 转来：开了屏蔽，抖音照样出声）。接着放是播放器自己的事：抖音这类收到焦点回来
 * 也不接着放（同一条反馈），输入法没法替它点播放，设置里写明这类应用选压低音量。
 * 压低音量：直接把媒体音量临时调到原来的三分之一（至少留 1 档）。原来用可压低的焦点让系统去压，用户 11-06 实测没用——
 * 播放器是语音内容（播客、视频解说）或用 AAudio 时系统不压，交给应用自己处理，很多应用收到也不理。
 *
 * 关麦时只还原自己动过的那次，说话时用户自己调过音量就听用户的。动音量之前先落盘记一笔，进程中途被杀
 * 没来得及还原，下次创建时补上，不会让媒体一直很小声或没声。
 *
 * 只在开麦那一刻有媒体在放时动作；同一时刻可能有新旧两段录音交接，按持有者计数，最后一个放手才恢复。
 */
internal class VoiceAudioFocus(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    companion object {
        private const val TAG = "VoiceAudioFocus"
        /**
         * 压低音量时调到原音量的几分之一。原来是 5：三星媒体音量 0–15，常用的 8 档压到 1 档，
         * 用户 11-24 转来「直接统一把手机音量变成最低档」。
         */
        private const val DUCK_DIVISOR = 3
        /** 压低后隔这么久再读一次实际音量：有的系统（三星细调音量）过一会儿才落到最终的档。 */
        private const val DUCK_SETTLE_MS = 600L
        /** 拿到焦点后等这么久还在放，算播放器不理焦点。播放器淡出、停下一般在 300 ms 内。 */
        internal const val IGNORED_FOCUS_CHECK_MS = 800L
    }

    private val lock = Any()
    private val owners = HashSet<Any>()
    private var focusRequest: AudioFocusRequest? = null
    private var mutedMusic = false
    /** 压低前的媒体音量和压到的音量；-1 表示没压。 */
    private var volumeBefore = -1
    private var volumeLowered = -1
    /** 压低后读到过的最高一档（要的、立刻读回的、隔一会儿读回的）；关麦时音量不高于它就算用户没动过。 */
    private var volumeLoweredMax = -1

    private val audio: AudioManager? by lazy {
        try { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (_: Exception) { null }
    }

    init {
        // 上次动了媒体音量后进程没走到恢复：先把声音还回去。
        try {
            if (SettingsPreferences.isSttMusicMutedByVoice(context)) {
                unmuteMusic()
                SettingsPreferences.setSttMusicMutedByVoice(context, false)
            }
            val before = SettingsPreferences.getSttMusicVolumeBeforeVoice(context)
            if (before >= 0) {
                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                SettingsPreferences.setSttMusicVolumeBeforeVoice(context, -1)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Restoring media volume failed", error)
        }
    }

    fun acquire(owner: Any) { synchronized(lock) {
        if (!owners.add(owner) || owners.size > 1) return
        try {
            val mode = SettingsPreferences.getSttOtherAudio(context)
            if (mode == VoiceOtherAudio.OFF) return
            val manager = audio ?: return
            // 写进日志（设置 › 关于 › 日志查看器），用户反馈「没用」时能看出停在哪一步：公开版只写警告级，
            // 所以每步结果都按警告写；只有开了这个选项的人每次开麦多一行。
            if (!manager.isMusicActive) {
                FileLogger.w(TAG, "mode=${mode.id}: no media playing at mic start, nothing to quiet")
                return
            }
            if (mode == VoiceOtherAudio.DUCK) {
                lowerMusic(manager)
                return
            }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener { }
                .build()
            if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
                FileLogger.w(TAG, "pause: focus granted")
                mainHandler.postDelayed({ muteIfFocusIgnored(request) }, IGNORED_FOCUS_CHECK_MS)
                return
            }
            // 系统不给焦点：临时静音媒体音量（不弹音量条），关麦时还原。
            FileLogger.w(TAG, "pause: focus refused, muting media instead")
            muteMusic(manager)
        } catch (error: Exception) {
            FileLogger.w(TAG, "Quieting other audio failed", error)
        }
    } }

    /** 拿到焦点的那次录音还没结束、媒体还在放：播放器不理焦点，改为临时静音。 */
    private fun muteIfFocusIgnored(request: AudioFocusRequest) { synchronized(lock) {
        if (focusRequest !== request || mutedMusic) return
        try {
            val manager = audio ?: return
            if (!manager.isMusicActive) return
            FileLogger.w(TAG, "pause: player kept playing after losing focus, muting media instead")
            muteMusic(manager)
        } catch (error: Exception) {
            FileLogger.w(TAG, "Muting ignored-focus player failed", error)
        }
    } }

    private fun muteMusic(manager: AudioManager) {
        if (manager.isVolumeFixed || manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            FileLogger.w(TAG, "media volume fixed or already muted, left alone")
            return
        }
        SettingsPreferences.setSttMusicMutedByVoice(context, true)
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mutedMusic = true
        if (!manager.isStreamMute(AudioManager.STREAM_MUSIC)) FileLogger.w(TAG, "system did not mute media")
    }

    /** 媒体音量临时调到原来的 1/[DUCK_DIVISOR]（不弹音量条）。 */
    private fun lowerMusic(manager: AudioManager) {
        if (manager.isVolumeFixed || manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            FileLogger.w(TAG, "duck: media volume fixed or muted, left alone")
            return
        }
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val lowered = (current / DUCK_DIVISOR).coerceAtLeast(1)
        if (current <= 0 || lowered >= current) return
        SettingsPreferences.setSttMusicVolumeBeforeVoice(context, current)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, lowered, 0)
        volumeBefore = current
        // 有的系统会把音量档换算一下，实际落下的档和要的不一样（#322 压低后不恢复）：记实际读回来的档，
        // 关麦时拿它判断用户有没有自己调过。读回来还是原来的档（系统还没落下）就按要的档记。
        volumeLowered = manager.getStreamVolume(AudioManager.STREAM_MUSIC).takeIf { it != current } ?: lowered
        volumeLoweredMax = maxOf(lowered, volumeLowered)
        mainHandler.postDelayed({ noteSettledVolume(current) }, DUCK_SETTLE_MS)
        // 有的系统不让后台应用改音量：读回来没变就记一笔（关麦时照常按原音量还原）。
        if (volumeLowered != lowered) FileLogger.w(TAG, "duck: asked $current -> $lowered, system set $volumeLowered")
        else FileLogger.w(TAG, "duck: media volume $current -> $lowered")
    }

    /** 压低后过一会儿系统落下的那一档也算「我们压的」（11-24：三星上读回的档和关麦时读到的不一样，音量一直不还原）。 */
    private fun noteSettledVolume(before: Int) { synchronized(lock) {
        if (volumeBefore != before) return
        try {
            val settled = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
            if (settled < before && settled > volumeLoweredMax) volumeLoweredMax = settled
        } catch (_: Exception) { }
    } }

    fun release(owner: Any) { synchronized(lock) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        restore()
    } }

    private fun restore() {
        val request = focusRequest
        focusRequest = null
        if (request != null) try {
            audio?.abandonAudioFocusRequest(request)
            FileLogger.i(TAG, "pause: focus given back; resuming is up to the player")
        } catch (error: Exception) {
            FileLogger.w(TAG, "Abandoning audio focus failed", error)
        }
        if (mutedMusic) {
            mutedMusic = false
            try {
                unmuteMusic()
                SettingsPreferences.setSttMusicMutedByVoice(context, false)
            } catch (error: Exception) {
                Log.w(TAG, "Restoring media volume failed", error)
            }
        }
        if (volumeBefore >= 0) {
            val before = volumeBefore
            val lowered = maxOf(volumeLowered, volumeLoweredMax)
            volumeBefore = -1
            volumeLowered = -1
            volumeLoweredMax = -1
            try {
                // 说话时用户自己往上调过音量就听用户的，不再改回去；还在压低的档（或更低）就还原。
                // 原来只认「正好等于压低时读到的那一档」，系统过一会儿换算成别的档时一直不还原（11-24 反馈）。
                val manager = audio
                val now = manager?.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (manager != null && now != null && now <= lowered) {
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                    FileLogger.w(TAG, "duck: media volume $now -> $before restored")
                } else FileLogger.w(TAG, "duck: media volume is $now (lowered $lowered), user changed it, left alone")
                SettingsPreferences.setSttMusicVolumeBeforeVoice(context, -1)
            } catch (error: Exception) {
                Log.w(TAG, "Restoring media volume failed", error)
            }
        }
    }

    private fun unmuteMusic() {
        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }
}
