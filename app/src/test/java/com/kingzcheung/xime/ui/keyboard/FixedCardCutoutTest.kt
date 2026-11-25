package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** #556：横屏挖孔压进固定卡片多少，按键就往里让多少；没压到不让。 */
class FixedCardCutoutTest {
    @Test
    fun aFullWidthCardStepsAsideByTheWholeCutoutBand() {
        assertEquals(32f, fixedCardCutoutOverlapDp(cutoutDp = 32f, cardEdgeDp = 0f), 0.001f)
    }

    @Test
    fun aNarrowedCardOnlyStepsAsideByWhatStillOverlaps() {
        assertEquals(12f, fixedCardCutoutOverlapDp(cutoutDp = 32f, cardEdgeDp = 20f), 0.001f)
        assertEquals(0f, fixedCardCutoutOverlapDp(cutoutDp = 32f, cardEdgeDp = 40f), 0.001f)
    }

    @Test
    fun noCutoutOnThisSideChangesNothing() {
        assertEquals(0f, fixedCardCutoutOverlapDp(cutoutDp = 0f, cardEdgeDp = 0f), 0.001f)
    }
}
