package com.kingzcheung.xime.settings

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #329：底部留白、调低按横竖屏分开记，没调过的方向沿用升级前的值。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomCalibrationOrientationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun orient(landscape: Boolean) =
        RuntimeEnvironment.setQualifiers(if (landscape) "land" else "port")

    @Before fun clean() {
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        SettingsPreferences.setKeyboardBottomPosition(app, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
    }

    @Test fun portraitAndLandscapeKeepTheirOwnAdjustment() {
        orient(false)
        assertEquals(Configuration.ORIENTATION_PORTRAIT, app.resources.configuration.orientation)
        SettingsPreferences.setKeyboardBottomAdjustmentDp(app, 12)
        orient(true)
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(app, -8)
        assertEquals(-8, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        orient(false)
        assertEquals(12, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        assertEquals(12, SettingsPreferences.getKeyboardBottomPaddingDp(app))
    }

    @Test fun anUntouchedOrientationKeepsTheValueFromBeforeTheUpgrade() {
        SettingsPreferences.getPrefsPublic(app).edit()
            .putInt(SettingsPreferences.KEY_KEYBOARD_BOTTOM_PADDING_DP, 20).commit()
        orient(false)
        assertEquals(20, SettingsPreferences.getKeyboardBottomPaddingDp(app))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(app, 0)
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        orient(true)
        assertEquals(20, SettingsPreferences.getKeyboardBottomPaddingDp(app))
        // 1.15.68 留下的 _portrait/_landscape 旧值不再读。
        SettingsPreferences.getPrefsPublic(app).edit()
            .putInt(SettingsPreferences.KEY_KEYBOARD_BOTTOM_PADDING_DP + "_landscape", 40).commit()
        assertEquals(20, SettingsPreferences.getKeyboardBottomPaddingDp(app))
    }

    @Test fun resetClearsBothOrientations() {
        orient(false); SettingsPreferences.setKeyboardBottomAdjustmentDp(app, 10)
        orient(true); SettingsPreferences.setKeyboardBottomAdjustmentDp(app, -6)
        SettingsPreferences.resetKeyboardBottomCalibration(app)
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        orient(false)
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
    }

    @Test fun bothOrientationKeysCountAsCalibrationChanges() {
        assertTrue(SettingsPreferences.isBottomCalibrationKey("keyboard_bottom_padding_dp@landscape"))
        assertTrue(SettingsPreferences.isBottomCalibrationKey("keyboard_bottom_edge_lowering_dp@portrait"))
        assertTrue(SettingsPreferences.isBottomCalibrationKey("keyboard_bottom_lowering_dp"))
        assertFalse(SettingsPreferences.isBottomCalibrationKey("keyboard_bottom_lowering_limit_dp"))
        assertFalse(SettingsPreferences.isBottomCalibrationKey(null))
    }
}
