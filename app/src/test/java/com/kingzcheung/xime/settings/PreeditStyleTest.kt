package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 拼音位置（11-19）：没设过的照原来浮在键盘上方；老开关「拼音放在键盘内」亲手开过的照旧；纠错读音默认照旧在候选旁。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PreeditStyleTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = SettingsPreferences.getPrefsPublic(app)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun unsetKeepsTheFloatingBubble() {
        assertEquals(SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.preeditStyle(app))
        assertTrue("拼音编辑栏本身默认照旧打开", SettingsPreferences.showPreeditPreview(app))
    }

    @Test fun oldInsideKeyboardSwitchIsKept() {
        prefs.edit().putBoolean(SettingsPreferences.KEY_INLINE_PREEDIT_PREVIEW, true).commit()
        assertEquals("「候选栏左侧」去掉了（11-20），开过老开关的按小签读", SettingsPreferences.PREEDIT_STYLE_LINE, SettingsPreferences.preeditStyle(app))
        prefs.edit().putBoolean(SettingsPreferences.KEY_INLINE_PREEDIT_PREVIEW, false).commit()
        assertEquals("亲手关过的是气泡", SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.preeditStyle(app))
    }

    @Test fun chosenStyleWinsOverTheOldSwitch() {
        prefs.edit().putBoolean(SettingsPreferences.KEY_INLINE_PREEDIT_PREVIEW, true).commit()
        SettingsPreferences.setPreeditStyle(app, SettingsPreferences.PREEDIT_STYLE_LINE)
        assertEquals(SettingsPreferences.PREEDIT_STYLE_LINE, SettingsPreferences.preeditStyle(app))
        SettingsPreferences.setPreeditStyle(app, "nonsense")
        assertEquals(SettingsPreferences.PREEDIT_STYLE_LINE, SettingsPreferences.preeditStyle(app))
    }

    @Test fun fourStylesAndAlignment() {
        for (style in listOf(SettingsPreferences.PREEDIT_STYLE_BLOCK, SettingsPreferences.PREEDIT_STYLE_STRIP,
                SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.PREEDIT_STYLE_LINE)) {
            SettingsPreferences.setPreeditStyle(app, style)
            assertEquals(style, SettingsPreferences.preeditStyle(app))
        }
        assertEquals("没选过靠左（气泡原来就靠左）", SettingsPreferences.PREEDIT_ALIGN_LEFT, SettingsPreferences.preeditAlign(app))
        SettingsPreferences.setPreeditAlign(app, SettingsPreferences.PREEDIT_ALIGN_RIGHT)
        assertEquals(SettingsPreferences.PREEDIT_ALIGN_RIGHT, SettingsPreferences.preeditAlign(app))
        SettingsPreferences.setPreeditAlign(app, "nonsense")
        assertEquals(SettingsPreferences.PREEDIT_ALIGN_RIGHT, SettingsPreferences.preeditAlign(app))
    }

    @Test fun resolveTable() {
        assertEquals(SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.resolvePreeditStyle(null, null))
        assertEquals(SettingsPreferences.PREEDIT_STYLE_LINE, SettingsPreferences.resolvePreeditStyle(null, true))
        assertEquals("存过「候选栏左侧」的按小签读", SettingsPreferences.PREEDIT_STYLE_LINE, SettingsPreferences.resolvePreeditStyle("left", null))
        assertEquals(SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.resolvePreeditStyle(null, false))
        assertEquals(SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.resolvePreeditStyle("bubble", true))
        assertEquals(SettingsPreferences.PREEDIT_STYLE_BUBBLE, SettingsPreferences.resolvePreeditStyle("x", null))
        assertEquals(SettingsPreferences.PREEDIT_STYLE_LINE, SettingsPreferences.resolvePreeditStyle("line", false))
    }

    /** 用户 11-19「纠错也别显示了」：没存过时默认不显示；亲手开过、关过的都照旧。 */
    @Test fun correctionMarksBesideCandidatesDefaultOffSavedKept() {
        assertFalse(SettingsPreferences.showCorrectionMarks(app))
        SettingsPreferences.setShowCorrectionMarks(app, true)
        assertTrue(SettingsPreferences.showCorrectionMarks(app))
        SettingsPreferences.setShowCorrectionMarks(app, false)
        assertFalse(SettingsPreferences.showCorrectionMarks(app))
    }
}
