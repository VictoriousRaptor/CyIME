package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** #118：中文状态下角标打出的全角符号要换成半角，才对得上第三方方案的字母表和 recognizer 规则。 */
class SchemeCodeCharTest {
    @Test fun fullWidthGraveBecomesTheWanxiangReverseLead() {
        assertEquals('`', schemeCodeChar('｀'))
    }

    @Test fun fullWidthAsciiPunctuationBecomesHalfWidth() {
        assertEquals(',', schemeCodeChar('，'))
        assertEquals(';', schemeCodeChar('；'))
        assertEquals('/', schemeCodeChar('／'))
        assertEquals('~', schemeCodeChar('～'))
        assertEquals('!', schemeCodeChar('！'))
    }

    @Test fun chineseFullStopCountsAsPeriod() {
        assertEquals('.', schemeCodeChar('。'))
    }

    @Test fun halfWidthAndOtherCharactersAreUnchanged() {
        assertEquals('`', schemeCodeChar('`'))
        assertEquals(';', schemeCodeChar(';'))
        assertEquals('、', schemeCodeChar('、'))
        assertEquals('《', schemeCodeChar('《'))
        assertEquals('a', schemeCodeChar('a'))
    }
}
