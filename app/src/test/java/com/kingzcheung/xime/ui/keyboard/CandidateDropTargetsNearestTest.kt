package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #274：点在候选栏空白处时找横向最近的候选。 */
class CandidateDropTargetsNearestTest {
    private val picked = mutableListOf<String>()

    private fun targets() = CandidateDropTargets().apply {
        // 候选比栏矮：栏 0..44，候选 7..37；两个候选之间空 4。
        put("这", Rect(48f, 7f, 75f, 37f)) { picked += "这" }
        put("着", Rect(79f, 7f, 106f, 37f)) { picked += "着" }
    }

    @Test fun belowTheFirstCandidatePicksIt() {
        targets().nearest(Offset(60f, 42f), maxDx = 12f, maxDy = 24f)!!()
        assertEquals(listOf("这"), picked)
    }

    @Test fun leftOfTheFirstCandidatePicksIt() {
        targets().nearest(Offset(45f, 40f), maxDx = 12f, maxDy = 24f)!!()
        assertEquals(listOf("这"), picked)
    }

    @Test fun theGapGoesToTheNearerSide() {
        targets().nearest(Offset(76f, 22f), maxDx = 12f, maxDy = 24f)!!()
        targets().nearest(Offset(78.5f, 22f), maxDx = 12f, maxDy = 24f)!!()
        assertEquals(listOf("这", "着"), picked)
    }

    @Test fun farFromEveryCandidatePicksNothing() {
        assertNull(targets().nearest(Offset(130f, 22f), maxDx = 12f, maxDy = 24f))
        assertNull(targets().nearest(Offset(60f, 80f), maxDx = 12f, maxDy = 24f))
    }
}
