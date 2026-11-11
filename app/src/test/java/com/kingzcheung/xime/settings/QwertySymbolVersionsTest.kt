package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 英文26键也能存为版本，和中文的版本表分开存，互不混入。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QwertySymbolVersionsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun full(mark: String) = QwertySwipeSymbols.slotIds.associateWith { "$mark$it" }

    @Before fun clean() { SettingsPreferences.getPrefsPublic(app).edit().clear().commit() }

    @Test fun englishVersionsAreSavedSeparatelyFromChinese() {
        QwertySwipeSymbols.saveVersion(app, "中文A", full("z"))
        QwertySwipeSymbols.saveVersion(app, "英文草稿", full("e"), english = true)
        assertEquals(listOf("中文A"), QwertySwipeSymbols.versions(app).keys.toList())
        assertEquals(listOf("英文草稿"), QwertySwipeSymbols.versions(app, english = true).keys.toList())
        assertEquals(full("e"), QwertySwipeSymbols.versions(app, english = true).getValue("英文草稿"))
    }

    @Test fun deletingAnEnglishVersionLeavesChineseVersionsAlone() {
        QwertySwipeSymbols.saveVersion(app, "同名", full("z"))
        QwertySwipeSymbols.saveVersion(app, "同名", full("e"), english = true)
        QwertySwipeSymbols.deleteVersion(app, "同名", english = true)
        assertTrue(QwertySwipeSymbols.versions(app, english = true).isEmpty())
        assertEquals(full("z"), QwertySwipeSymbols.versions(app).getValue("同名"))
    }
}
