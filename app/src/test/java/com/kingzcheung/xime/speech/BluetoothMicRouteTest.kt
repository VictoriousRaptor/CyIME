package com.kingzcheung.xime.speech

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #309：只把蓝牙耳机的通话麦克风当耳机麦克风；手机自带、有线、只放音乐的蓝牙声道都不算。 */
class BluetoothMicRouteTest {
    @Test fun onlyHeadsetCallMicsCount() {
        assertTrue(BluetoothMicRoute.isHeadsetMic(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(BluetoothMicRoute.isHeadsetMic(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertFalse(BluetoothMicRoute.isHeadsetMic(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(BluetoothMicRoute.isHeadsetMic(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertFalse(BluetoothMicRoute.isHeadsetMic(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }
}
