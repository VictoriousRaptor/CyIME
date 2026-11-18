package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPanelToggleTest {
    @Test fun secondTapOnTheSamePluginClosesItsPanel() {
        // 11-15: 翻译栏开着再点「翻译」原来是重新打开一遍，只能点叉关。
        assertTrue(toolPanelOpenFor(true, "translate", "translate"))
        assertFalse("关着时点是打开", toolPanelOpenFor(false, "translate", "translate"))
        assertFalse("开着的是别的插件：点了换成这个", toolPanelOpenFor(true, "ai_rewrite", "translate"))
    }
}
