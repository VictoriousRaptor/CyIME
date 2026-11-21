package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #486：菜单「声音振动」关了再开，照关之前的样子恢复，只开振动的不会连按键音一起打开。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundVibrationToggleTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clear() { SettingsPreferences.getPrefsPublic(app).edit().clear().commit() }

    @Test fun onlyVibrationComesBackAsOnlyVibration() {
        SettingsPreferences.setSoundEnabled(app, false)
        SettingsPreferences.setHapticMode(app, "custom")
        SettingsPreferences.setSoundAndVibrationEnabled(app, false)
        assertFalse(SettingsPreferences.isSoundEnabled(app))
        assertFalse(SettingsPreferences.isVibrationEnabled(app))
        SettingsPreferences.setSoundAndVibrationEnabled(app, true)
        assertFalse(SettingsPreferences.isSoundEnabled(app))
        assertEquals("custom", SettingsPreferences.getHapticMode(app))
    }

    @Test fun onlySoundComesBackAsOnlySound() {
        SettingsPreferences.setSoundEnabled(app, true)
        SettingsPreferences.setHapticMode(app, "disabled")
        SettingsPreferences.setSoundAndVibrationEnabled(app, false)
        SettingsPreferences.setSoundAndVibrationEnabled(app, true)
        assertTrue(SettingsPreferences.isSoundEnabled(app))
        assertFalse(SettingsPreferences.isVibrationEnabled(app))
    }

    @Test fun withoutARecordBothTurnOnAsBefore() {
        SettingsPreferences.setSoundEnabled(app, false)
        SettingsPreferences.setHapticMode(app, "disabled")
        SettingsPreferences.setSoundAndVibrationEnabled(app, true)
        assertTrue(SettingsPreferences.isSoundEnabled(app))
        assertTrue(SettingsPreferences.isVibrationEnabled(app))
    }
}
