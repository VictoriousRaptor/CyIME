package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 量候选宽度要和画出来的一样：纠错候选量纠错那段，「打过的词」图标和画的同样大。 */
class CandidateItemWidthTest {
    @Test fun secondaryTextIsTheCommentWithoutCorrection() {
        assertEquals("yong", candidateSecondaryText("yong", "").text)
        assertEquals("", candidateSecondaryText("", "").text)
    }

    @Test fun secondaryTextIsTheCorrectionWhenThereIsOne() {
        // 注释不显示（如窄屏）时纠错照样画出来，宽度也要算上。
        assertEquals("yong hao", candidateSecondaryText("", "yogn=yong hao").text)
        assertEquals("yong", candidateSecondaryText("zong", "yogn=yong").text)
    }

    @Test fun learnedMarkScalesWithCandidateFont() {
        assertEquals(9f, learnedMarkSize(20.sp).value, 0.001f)
    }
}
