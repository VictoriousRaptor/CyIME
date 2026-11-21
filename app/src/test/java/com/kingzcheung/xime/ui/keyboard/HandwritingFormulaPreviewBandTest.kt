package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公式预览在横屏矮画板上不压住中间写字的地方（B 站 11-18：横屏公式模式被挡住三分之一）。 */
class HandwritingFormulaPreviewBandTest {
    @Test fun `tall portrait canvas keeps the preview below the toggle row`() {
        val band = formulaPreviewBand(220.dp)
        assertEquals(44.dp, band.top)
        assertEquals(52.dp, band.height)
    }

    @Test fun `short landscape canvas moves the preview into the toggle row`() {
        for (height in listOf(110.dp, 130.dp, 160.dp)) {
            val band = formulaPreviewBand(height)
            // 预览底边不超过公式按钮那一行（8dp 外边距 + 约 32dp），下面整块留给写字。
            assertTrue("$height", band.top + band.height <= 44.dp)
            // 右边让开「公式」按钮（约 60dp 宽 + 8dp 外边距）。
            assertTrue("$height", band.end >= 76.dp)
        }
    }
}
