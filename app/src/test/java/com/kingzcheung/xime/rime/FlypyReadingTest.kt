package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 14 键小鹤双拼：屏上编码、回车和原样上屏都是首选读音的小鹤双拼字母，不是合并编码（10-27）。 */
class FlypyReadingTest {
    @Test fun syllablesFollowTheFlypySpeller() {
        mapOf("ni" to "ni", "hao" to "hc", "wo" to "wo", "men" to "mf", "shi" to "ui", "zhuang" to "vl", "chuan" to "ir",
            "xiang" to "xl", "jiong" to "js", "ying" to "yk", "kuai" to "kk", "yue" to "yt", "nüe" to "nt", "lv" to "lv",
            "a" to "aa", "e" to "ee", "ang" to "ah", "eng" to "eg", "ai" to "ai", "en" to "en", "er" to "er", "ou" to "ou",
            "zhui" to "vv", "qiu" to "qq", "dian" to "dm", "xiao" to "xn", "gua" to "gx", "lin" to "lb", "dei" to "dw")
            .forEach { (pinyin, keys) -> assertEquals(pinyin, keys, flypySyllable(pinyin)) }
        assertEquals("ni hc", flypyReading("ni hao"))
    }

    @Test fun mergedCodesShowTheFirstCandidatesFlypyLetters() {
        // bn ui gh cv 打 你好：编码 BUGC、引擎回显 bu'gc，首选读音 ni hao。
        assertEquals("ni'hc", merged14Preedit("BUGC", "bu'gc", flypyReading("ni hao")))
        assertEquals("wo'mf", merged14Preedit("QOMD", "qo'md", flypyReading("wo men")))
        // 读音和按键对不上（首选只用了一部分编码）时保留引擎回显。
        assertEquals("bu'gc", merged14Preedit("BUGC", "bu'gc", flypyReading("ni")))
        // 全拼 14 键照旧。
        assertEquals("ni'hao", merged14Preedit("BUGaO", "bu'gao", "ni hao"))
    }

    /** 14 键双拼开着「双拼编码显示全拼」（11-21 他报开了还是双拼字母）：键都对上首选时显示全拼，只盖住前面时后面照打的键，对不上 null。 */
    @Test fun mergedDoublePinyinShowsFullPinyinWhenAsked() {
        assertEquals("ni'hao", mergedDoublePinyinFullPreedit("BUGC", flypyReading("ni hao"), "ni hao", merged14Groups))
        assertEquals("ni'gc", mergedDoublePinyinFullPreedit("BUGC", flypyReading("ni"), "ni", merged14Groups))
        assertEquals(null, mergedDoublePinyinFullPreedit("BUGC", flypyReading("wo men"), "wo men", merged14Groups))
        assertEquals(null, mergedDoublePinyinFullPreedit("BU", flypyReading("ni hao"), "ni hao", merged14Groups))
        assertEquals(null, mergedDoublePinyinFullPreedit("BUGC", "", "", merged14Groups))
    }

    /** 双拼九键开着「编码显示双拼键」：数字按首选读音换成双拼键，两键一字；对不上时 null，照原来显示拼音。 */
    @Test fun nineKeyDoublePinyinShowsTheSchemeKeys() {
        val flypy = com.kingzcheung.xime.settings.DoublePinyinScheme.FLYPY
        assertEquals("ul pb", t9DoublePinyinKeys("8572", "shuang pin", flypy))
        assertEquals("ul pb", t9DoublePinyinKeys("85'72", "shuang pin", flypy))
        // 最后一个字只按了一个键：显示这个键的字母；首选读音比按键长也只取按了的部分。
        assertEquals("ul p", t9DoublePinyinKeys("857", "shuang pin", flypy))
        assertEquals("vs go", t9DoublePinyinKeys("8746", "zhong guo", flypy))
        val ziranma = com.kingzcheung.xime.settings.DoublePinyinScheme.ZIRANMA
        assertEquals("ud pn", t9DoublePinyinKeys("8376", "shuang pin", ziranma))
        // 英文词、读音和按键对不上、读音不够长、分隔符落在字中间：照原来显示。
        assertEquals(null, t9DoublePinyinKeys("8378", "~test", flypy))
        assertEquals(null, t9DoublePinyinKeys("8573", "shuang pin", flypy))
        assertEquals(null, t9DoublePinyinKeys("857266", "shuang pin", flypy))
        assertEquals(null, t9DoublePinyinKeys("8'572", "shuang pin", flypy))
        assertEquals(null, t9DoublePinyinKeys("", "shuang pin", flypy))
    }
}
