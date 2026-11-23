package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSystemStatusTest {
    private val pkg = "com.cyletix.cyime"

    @Test fun anyEvidenceCountsAsEnabled() {
        // #508：红米 Turbo5 / Android 17 启用列表里没有，但设置里写着、或已是当前输入法、或服务已开。
        assertTrue(imeEnabledFrom(pkg, false, "com.other/.Ime:$pkg/com.kingzcheung.xime.service.XimeInputMethodService;123", false, false))
        assertTrue(imeEnabledFrom(pkg, false, null, selected = true, serviceAlive = false))
        assertTrue(imeEnabledFrom(pkg, false, "", selected = false, serviceAlive = true))
        assertTrue(imeEnabledFrom(pkg, true, null, false, false))
    }

    @Test fun anotherPackageIsNotCyime() {
        assertFalse(imeEnabledFrom(pkg, false, "com.cyletix.cyime.debug/.Ime:com.other/.Ime", false, false))
        assertFalse(imeEnabledFrom(pkg, false, null, false, false))
    }

    @Test fun eitherTheSettingOrTheSystemsCurrentImeCounts() {
        assertTrue(imeSelectedFrom(pkg, "$pkg/com.kingzcheung.xime.service.XimeInputMethodService", null))
        assertTrue("系统报的当前输入法是 CyIME，设置里留着旧值也算（#508）", imeSelectedFrom(pkg, "com.other/.Ime", pkg))
        assertFalse(imeSelectedFrom(pkg, "com.other/.Ime", "com.other"))
        assertTrue("读不到设置时看当前输入法", imeSelectedFrom(pkg, "", pkg))
        assertFalse(imeSelectedFrom(pkg, "", null))
    }

    @Test fun theRunningServiceCountsOnlyWhenNothingElseIsReadable() {
        assertTrue(imeSelectedFrom(pkg, "", null, serviceAlive = true))
        assertFalse("读得到别的输入法就不看服务", imeSelectedFrom(pkg, "com.other/.Ime", null, serviceAlive = true))
        assertFalse(imeSelectedFrom(pkg, "", "com.other", serviceAlive = true))
    }

    @Test fun theWayOutShowsOnlyAfterVisitingSystemSettingsWithTheSchemesReady() {
        val ready = SetupStatus(imeEnabled = false, imeSelected = false, preparing = false, schemas = listOf("中文26键"))
        assertTrue(showSkipDetection(true, ready))
        assertFalse("没去过系统设置的新用户照常三步", showSkipDetection(false, ready))
        assertFalse("词库还在准备", showSkipDetection(true, ready.copy(preparing = true)))
        assertFalse("已经认出来了", showSkipDetection(true, ready.copy(imeEnabled = true, imeSelected = true)))
    }
}
