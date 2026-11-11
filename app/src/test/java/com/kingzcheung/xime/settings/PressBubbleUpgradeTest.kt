package com.kingzcheung.xime.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.runtime.adaptation.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 11-11 用户反馈「升级后打字慢动作，重装就好」：升级上来的低配机，没碰过按键浮标开关的，按低配档关掉一次。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PressBubbleUpgradeTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("press_bubble_upgrade_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val key = SettingsPreferences.KEY_SHOW_PRESS_BUBBLE

    /** 自动档记下的那一档（不重新测硬件）。 */
    private fun fit(tier: PerformanceTier) {
        prefs.edit().putString("device_performance_auto_tier", tier.name).commit()
        PerformanceProfile.fitPressBubbleOnUpgrade(prefs)
    }

    @Test fun untouchedSwitchOnALowEndPhoneIsTurnedOffOnce() {
        fit(PerformanceTier.LOW)
        assertFalse(prefs.getBoolean(key, true))
        // 只做一次：之后用户自己再打开，不会又被关上。
        prefs.edit().putBoolean(key, true).commit()
        fit(PerformanceTier.LOW)
        assertEquals(true, prefs.getBoolean(key, false))
    }

    @Test fun aSwitchTheUserSetIsKept() {
        prefs.edit().putBoolean(key, true).commit()
        fit(PerformanceTier.LOW)
        assertEquals(true, prefs.getBoolean(key, false))
    }

    @Test fun withoutAStoredTierNothingChanges() {
        PerformanceProfile.fitPressBubbleOnUpgrade(prefs)
        assertFalse(prefs.contains(key))
    }

    @Test fun mediumAndHighPhonesKeepTheDefault() {
        for (tier in listOf(PerformanceTier.MEDIUM, PerformanceTier.HIGH)) {
            prefs.edit().clear().commit()
            fit(tier)
            assertFalse(tier.name, prefs.contains(key))
        }
    }
}
