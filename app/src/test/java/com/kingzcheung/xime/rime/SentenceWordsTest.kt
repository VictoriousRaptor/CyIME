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

    /** 九键拖选（11-20）：从拖选开头所在的词起重选，光标放到拖选最后一个字后面（按整句拼音数数字键）。 */
    @Test fun t9DragStartsAtTheWordAndEndsAtTheLastDraggedCharacter() {
        val words = listOf("我想", "去", "北京")
        val reading = "wo xiang qu bei jing"
        // 拖「想去北」：从「我想」起重选，光标在 wo xiang qu bei 后面（2+5+2+3 个数字键）。
        assertEquals(0 to 12, t9DragReselect(words, reading, 1, 4))
        // 拖「北京」：前面两个词照原样选定，从「北京」起，光标在 bei jing 后面。
        assertEquals(2 to 7, t9DragReselect(words, reading, 3, 5))
        // 拖「去」一个字。
        assertEquals(1 to 2, t9DragReselect(words, reading, 2, 3))
        // 拼音对不上字数（注释不是一字一音节）：光标照长按拆词放到开头那个词后面。
        assertEquals(1 to -1, t9DragReselect(words, "woxiang qu beijing", 2, 4))
        assertEquals(1 to -1, t9DragReselect(words, "〔纠〕wo xiang qu bei jing", 2, 4))
        // 整句只有一个词也能拖其中几个字。
        // 拖的是词中间的字：从词头起（拖「天」，光标在 jin tian 后面）。
        assertEquals(0 to 7, t9DragReselect(listOf("今天"), "jin tian", 1, 2))
        assertEquals(0 to 3, t9DragReselect(listOf("今天"), "jin tian", 0, 1))
        assertNull("超出整句", t9DragReselect(words, reading, 4, 6))
        assertNull(t9DragReselect(words, reading, 2, 2))
        assertNull(t9DragReselect(emptyList(), reading, 0, 1))
    }
}
