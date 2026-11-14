package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 关掉打字统计后要能开回来（用户 11-13）：首页「智能」组里一直有一行带开关的打字统计，
 * 搜索结果的路径写的也是「智能」。标题栏右边的小字只在开着时显示，不能当唯一入口。
 */
class TypingStatsEntryTest {
    private val main = File("src/main/java/com/kingzcheung/xime/ui/settings/SettingsMainScreen.kt").readText()

    @Test fun smartSectionAlwaysShowsTheTypingStatsSwitch() {
        val smart = main.substringAfter("searchKey = \"智能\"").substringBefore("R.string.section_system")
        assertTrue("智能组里要有打字统计一行", smart.contains("TypingStatsSmartRow"))
        val row = main.substringAfter("private fun TypingStatsSmartRow").substringBefore("\n}\n")
        assertTrue(row.contains("SettingsToggleItem"))
        assertTrue("关着也要显示，不能提前 return", !row.contains("return"))
        val search = File("src/main/java/com/kingzcheung/xime/ui/settings/SettingsSearch.kt").readText()
        assertTrue(search.contains("page(\"打字统计\", smart, SettingsRoutes.TypingStats"))
    }
}
