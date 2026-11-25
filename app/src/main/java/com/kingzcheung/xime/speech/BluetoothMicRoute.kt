package com.kingzcheung.xime.speech

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * 「用蓝牙耳机的麦克风」（#309，骑车、手不方便时戴着蓝牙耳机说话）。设置默认关，关着时这里什么都不做。
 *
 * 开着且连着带麦克风的蓝牙耳机时：录音前让系统打开耳机的通话声道（Android 12 起 setCommunicationDevice，
 * 之前 startBluetoothSco），录音指定用耳机的麦克风；这次录音结束就放开，音乐回到原来的声道。
 * 没连蓝牙耳机时返回 null，照旧用手机麦克风。
 */
internal class BluetoothMicRoute(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var routed = false

    /** 蓝牙耳机的麦克风；没有时为 null。 */
    fun input(): AudioDeviceInfo? = runCatching {
        audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { isHeadsetMic(it.type) }
    }.getOrNull()

    /** 打开耳机的通话声道，返回该指定给录音的麦克风；打不开或没有耳机时 null。 */
    @Synchronized
    fun begin(): AudioDeviceInfo? {
        val manager = audio ?: return null
        val mic = input() ?: return null
        val ok = runCatching {
            if (AndroidPlatform.callAudioRoute) {
                val output = manager.availableCommunicationDevices.firstOrNull { it.type == mic.type } ?: return@runCatching false
                manager.setCommunicationDevice(output)
            } else {
                @Suppress("DEPRECATION") manager.startBluetoothSco()
                @Suppress("DEPRECATION") manager.isBluetoothScoOn = true
                true
            }
        }.getOrDefault(false)
        routed = ok
        return mic.takeIf { ok }
    }

    /** 这次录音结束：放开通话声道。没打开过就什么都不做。 */
    @Synchronized
    fun end() {
        if (!routed) return
        routed = false
        val manager = audio ?: return
        runCatching {
            if (AndroidPlatform.callAudioRoute) manager.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") manager.isBluetoothScoOn = false
                @Suppress("DEPRECATION") manager.stopBluetoothSco()
            }
        }
    }

    companion object {
        /** 蓝牙耳机的通话麦克风（经典蓝牙 SCO；LE Audio 耳机也算）。 */
        fun isHeadsetMic(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}
