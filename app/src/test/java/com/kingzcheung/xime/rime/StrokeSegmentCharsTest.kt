package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 11-23：笔画分词打出的词，长按首选按分词段拆成字，点哪个字重选哪个字。 */
class StrokeSegmentCharsTest {
    @Test fun oneCharacterPerSegment() {
        assertEquals(listOf("是", "由", "于"), strokeSegmentChars("是由于", "sz'szh'hh"))
        assertEquals(listOf("吉", "祥"), strokeSegmentChars("吉祥", "hshszh'nzsnnphhhs"))
    }

    @Test fun refusesWhatIsNotOneCharacterPerSegment() {
        assertNull(strokeSegmentChars("是由", "sz'szh'hh"))        // 段数和字数对不上
        assertNull(strokeSegmentChars("吉", "hshszh"))             // 只有一段
        assertNull(strokeSegmentChars("吉祥如意", "hshszh'nz''"))   // 空段：还没打笔画的字
        assertNull(strokeSegmentChars("二十", "h*'hs"))             // 通配符交给通配模块
        assertNull(strokeSegmentChars("是由", "⼁乛'⼁乛⼀"))        // 只认字母编码
    }

    @Test fun segmentEndIsBeforeTheSeparator() {
        assertEquals(2, strokeSegmentEnd("sz'szh'hh", 0))
        assertEquals(6, strokeSegmentEnd("sz'szh'hh", 1))
        assertEquals(9, strokeSegmentEnd("sz'szh'hh", 2))
    }
}
