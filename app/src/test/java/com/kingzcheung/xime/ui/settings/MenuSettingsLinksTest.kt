package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSettingsLinksTest {
    /** 注音和其他中文方案共用「繁体输出」，长按简繁按钮都去这一项。 */
    @Test fun scriptButtonOpensTheTraditionalSetting() {
        assertEquals("繁体输出", menuSettingsEntry(ToolbarButtonItem.Builtin(ToolbarButton.TRADITIONAL))?.anchor)
    }

    /** 菜单项长按去的设置必须真在设置搜索索引里，改了设置标题而没改这里会失败。 */
    @Test fun everyLinkResolvesToOneSetting() {
        val missing = MenuSettingsTitles.filterValues { title -> SettingsSearchIndex.none { it.title == title } }
        assertTrue("索引里没有：$missing", missing.isEmpty())
    }

    /** 除纯动作和临时状态外，每个菜单功能都有设置项；新增菜单功能时要在这里决定归属。 */
    @Test fun onlyActionsAndTransientStatesHaveNoSetting() {
        val noSetting = setOf(
            ToolbarButton.EDIT, ToolbarButton.FULL_SHAPE, ToolbarButton.SETTINGS, ToolbarButton.EMOJI, ToolbarButton.SYMBOL,
        )
        assertEquals(ToolbarButton.entries.toSet() - noSetting, MenuSettingsTitles.keys)
        for (b in noSetting) assertNull(b.id, menuSettingsEntry(ToolbarButtonItem.Builtin(b)))
    }

    @Test fun sizeFloatingAndArrangeOpenTheKeyboardPanelPages() {
        fun target(b: ToolbarButton) = menuSettingsEntry(ToolbarButtonItem.Builtin(b))?.target
        assertEquals(SettingsRoutes.KeyboardResize, target(ToolbarButton.FLOAT))
        assertEquals(SettingsRoutes.KeyboardResize, target(ToolbarButton.FLOATING))
        assertEquals(SettingsRoutes.ToolbarArrange, target(ToolbarButton.ARRANGE))
        assertEquals(SettingsRoutes.SmartPrediction, target(ToolbarButton.INCOGNITO))
        assertEquals("无痕模式", menuSettingsEntry(ToolbarButtonItem.Builtin(ToolbarButton.INCOGNITO))?.anchor)
    }
}
