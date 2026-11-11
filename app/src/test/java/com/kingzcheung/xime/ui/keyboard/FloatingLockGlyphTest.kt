package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 11-10：手机横屏数字键盘悬浮时，底部小锁的上半截伸进底排按键下面那条，看着和键区重叠。 */
class FloatingLockGlyphTest {
    private fun top(band: Float): Float {
        val (scale, centre) = floatingLockGlyphPlacement(band)
        return centre + FLOATING_LOCK_GLYPH_DP * scale / 2f
    }

    @Test fun phoneFooterKeepsTheLockInsideTheBarWithMargins() {
        val band = floatingFooterHeightDp(400).toFloat()
        assertEquals(16f, band, 0f)
        assertTrue("top ${top(band)}", top(band) <= band - FLOATING_LOCK_GLYPH_MARGIN_DP + 0.001f)
        val (scale, centre) = floatingLockGlyphPlacement(band)
        assertTrue("bottom", centre - FLOATING_LOCK_GLYPH_DP * scale / 2f >= FLOATING_LOCK_GLYPH_MARGIN_DP - 0.001f)
    }

    @Test fun tabletFooterKeepsTheFullSizeLockCentredInTheBar() {
        val band = floatingFooterHeightDp(800).toFloat()
        val (scale, centre) = floatingLockGlyphPlacement(band)
        assertEquals(1f, scale, 0f)
        assertEquals(band / 2f, centre, 0f)
    }
}
