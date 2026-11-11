package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceWordsTest {
    @Test fun sentenceComponentsBecomeTheWordsToTap() {
        assertEquals(listOf("我想", "去", "北京"),
            splitSentenceWords("我想去北京", "我想:-3.2:2;去:-5.1:1;北京:-4.0:2;"))
    }

    @Test fun traditionalOutputIsSplitByEachWordsLength() {
        // 繁体输出：引擎的组成还是简体，按字数切显示的整句。
        assertEquals(listOf("我們", "聽", "音樂"), splitSentenceWords("我們聽音樂", "我们:-1:2;听:-2:1;音乐:-3:2;"))
    }

    @Test fun aSingleWordOrAMismatchOffersNothing() {
        assertNull(splitSentenceWords("北京", "北京:-4.0:2;"))
        assertNull(splitSentenceWords("北京", ""))
        assertNull("组成和首选字数对不上（被改写过）就不拆", splitSentenceWords("我想去北京吧", "我想:-3:2;去:-5:1;北京:-4:2;"))
    }

    @Test fun nineKeyReadingCountsItsKeys() {
        assertEquals(7, t9KeysOfReading("bei jing"))
        assertEquals(7, t9KeysOfReading("bei'jing"))
        assertEquals(2, t9KeysOfReading("lü"))
        assertEquals("注释里不是拼音（标记、汉字）时不挪光标", 0, t9KeysOfReading("〔纠〕bei"))
        assertEquals(0, t9KeysOfReading(""))
    }
}
