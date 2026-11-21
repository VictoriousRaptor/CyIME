package com.kingzcheung.xime.rime

import com.kingzcheung.xime.handwriting.HanziPinyin
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 「拼音显示声调」：用 APK 里的两张表，按首选候选给拼音标调，只换字母、长度不变。 */
class PinyinTonesTest {
    private fun asset(path: String) = listOf("src/main/assets/$path", "app/src/main/assets/$path").map(::File).first { it.isFile }

    private val data by lazy {
        val table = asset("handwriting/hanzi_pinyin.tsv").useLines(Charsets.UTF_8) { HanziPinyin.parse(it) }
        val words = asset("pinyin/tone_words.tsv").useLines(Charsets.UTF_8) { PinyinTones.parseWords(it) }
        PinyinTones.Data(table::lookup, words)
    }

    private fun mark(display: String, candidate: String) = PinyinTones.mark(display, candidate, data).also {
        assertEquals("标调不能改变长度：$display", display.length, it.length)
    }

    @Test
    fun `marks every syllable from the first candidate`() {
        assertEquals("jiù'shì'zhè'yàng", mark("jiu'shi'zhe'yang", "就是这样"))
        assertEquals("nǐ'hǎo'ma", mark("ni'hao'ma", "你好吗"))
    }

    @Test
    fun `typed spelling picks the reading of a polyphonic character`() {
        assertEquals("xíng'rén", mark("xing'ren", "行人"))
        assertEquals("yín'háng", mark("yin'hang", "银行"))
        assertEquals("zhǎng'dà", mark("zhang'da", "长大"))
        assertEquals("chóng'xīn", mark("chong'xin", "重新"))
    }

    @Test
    fun `same spelling with different tones follows the word`() {
        assertEquals("yīn'wèi", mark("yin'wei", "因为"))
        assertEquals("chéng'wéi", mark("cheng'wei", "成为"))
        assertEquals("dé'dào", mark("de'dao", "得到"))
        assertEquals("shuō'de'duì", mark("shuo'de'dui", "说得对"))
        assertEquals("hǎo'de", mark("hao'de", "好的"))
        assertEquals("mù'dì", mark("mu'di", "目的"))
        assertEquals("guó'wù'yuàn", mark("guo'wu'yuan", "国务院"))
    }

    @Test
    fun `neutral tone stays unmarked and yi bu keep their own tone`() {
        assertEquals("xǐ'huan", mark("xi'huan", "喜欢"))
        assertEquals("xiāo'xi", mark("xiao'xi", "消息"))
        assertEquals("huān'yíng", mark("huan'ying", "欢迎"))
        assertEquals("yī'yàng", mark("yi'yang", "一样"))
        assertEquals("bù'duì", mark("bu'dui", "不对"))
    }

    @Test
    fun `u umlaut typed as v`() {
        assertEquals("lǜ'sè", mark("lv'se", "绿色"))
        assertEquals("lǜ'sè", mark("lü'se", "绿色"))
    }

    @Test
    fun `leaves what is not full pinyin alone`() {
        assertEquals("j's'z'y", mark("j's'z'y", "就是这样"))
        assertEquals("jiù's'zhè'y", mark("jiu's'zhe'y", "就是这样"))
        assertEquals("zhè'yan", mark("zhe'yan", "这样"))
        assertEquals("548'744", mark("548'744", "就是"))
        assertEquals("hello", mark("hello", "Hello"))
        assertEquals("a'gu", mark("a'gu", "A股"))
    }

    @Test
    fun `skips chosen characters caret and wrap spaces`() {
        assertEquals("就是zhè'yàng", mark("就是zhe'yang", "这样"))
        assertEquals("zh|è'yàng", mark("zh|e'yang", "这样"))
        assertEquals("zhè'\u200Byàng", mark("zhe'\u200Byang", "这样"))
    }
}
