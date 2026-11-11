package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.BackgroundConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面板透出键盘本身那张背景，和工具栏是一张连续的图（用户 11-09：不开玻璃时是两张断开的）。 */
class PanelSeesKeyboardBackgroundTest {
    @Test fun `image and gradient themes share one background with the panel even without glass`() {
        assertTrue(panelSeesKeyboardBackground(false, BackgroundConfig(type = "image", src = "themes/a.jpg")))
        assertTrue(panelSeesKeyboardBackground(false, BackgroundConfig(type = "gradient", colors = listOf(0x112233L, 0x445566L))))
    }

    @Test fun `glass always lets the panel see through`() {
        assertTrue(panelSeesKeyboardBackground(true, null))
        assertTrue(panelSeesKeyboardBackground(true, BackgroundConfig(type = "solid", color = 0x112233L)))
    }

    @Test fun `solid themes without glass keep painting their own panel`() {
        assertFalse(panelSeesKeyboardBackground(false, null))
        assertFalse(panelSeesKeyboardBackground(false, BackgroundConfig(type = "solid", color = 0x112233L)))
    }
}
