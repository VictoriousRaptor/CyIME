package com.kingzcheung.xime.ui.render

import com.kingzcheung.xime.ui.keyboard.sentenceDragRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 首选上长按（11-24 他：长按点不了固定，长按全是改单字的候选）：按住时手指抖一点、还在同一个字上是长按，出卡片；
 * 拖到别的字上才改局部，不会只选一个字。手势本身在 CandidateItem 里（Robolectric 的放大镜关不掉，这里测松手时的判定）。
 */
class SentenceDragLongPressTest {
    @Test fun holdingStillOrJitterOnTheSameCharacterIsALongPress() {
        assertNull(sentenceDragRange(dragged = false, first = 3, last = 3))
        assertNull(sentenceDragRange(dragged = true, first = 3, last = 3))
    }

    @Test fun draggingToAnotherCharacterSelectsAtLeastTwo() {
        assertEquals(3..4, sentenceDragRange(dragged = true, first = 3, last = 4))
        assertEquals(1..3, sentenceDragRange(dragged = true, first = 3, last = 1))
    }
}
