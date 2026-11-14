package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘窗口为工具面板多留的高度要和面板本身一样高，否则容器多/少一截。 */
class ToolPanelHeightTest {
    @Test
    fun `翻译栏只占一行，输入面板照旧，全屏面板不撑高`() {
        assertEquals(TOOL_PANEL_BAR_HEIGHT, toolPanelExtraHeight(visible = true, display = "LIVE"))
        assertEquals(48, TOOL_PANEL_BAR_HEIGHT)
        assertEquals(TOOL_PANEL_HEIGHT, toolPanelExtraHeight(visible = true, display = "DIRECT"))
        assertEquals(TOOL_PANEL_HEIGHT, toolPanelExtraHeight(visible = true, display = null))
        assertEquals(TOOL_PANEL_HEIGHT, toolPanelExtraHeight(visible = true, display = "EDITING"))
        assertEquals(0, toolPanelExtraHeight(visible = true, display = "PASSIVE"))
        assertEquals(0, toolPanelExtraHeight(visible = false, display = "LIVE"))
    }

    @Test
    fun `横屏放不下完整面板时用一行的面板，翻译栏和全屏面板不变`() {
        // 用户 11-12：下载的翻译、AI 插件横屏用不了（以前横屏、悬浮直接不开）。
        // 手机竖屏：可用约 700dp，键盘 300dp，放得下完整面板。
        assertFalse(toolPanelCompact(keyboardHeight = 300, available = 700))
        // 手机横屏：可用约 360dp，键盘 230dp，加 170dp 面板超出。
        assertTrue(toolPanelCompact(keyboardHeight = 230, available = 360))
        assertEquals(TOOL_PANEL_COMPACT_HEIGHT, toolPanelExtraHeight(visible = true, display = "DIRECT", compact = true))
        assertEquals(48, TOOL_PANEL_COMPACT_HEIGHT)
        assertEquals(TOOL_PANEL_BAR_HEIGHT, toolPanelExtraHeight(visible = true, display = "LIVE", compact = true))
        assertEquals(0, toolPanelExtraHeight(visible = true, display = "PASSIVE", compact = true))
        assertEquals(0, toolPanelExtraHeight(visible = false, display = null, compact = true))
    }
}
