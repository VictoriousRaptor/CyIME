package com.kingzcheung.xime.gloss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import android.view.KeyEvent
import com.kingzcheung.xime.service.shiftedCandidateOffset
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class CandidateGlossaryTest {

    /** 与生成脚本一致：键按 UTF-8 字节序排序，开头是 # 说明行。 */
    private fun table(vararg rows: Pair<String, String>, corrections: Map<String, String> = emptyMap()): GlossTable {
        val body = rows.sortedWith { a, b ->
            val x = a.first.toByteArray(); val y = b.first.toByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return@sortedWith d
            }
            x.size - y.size
        }.joinToString("") { "${it.first}\t${it.second}\n" }
        return GlossTable.fromBuffer(ByteBuffer.wrap("# 来源与许可\n# 格式说明\n$body".toByteArray()), corrections)
    }

    @Test
    fun `software terms override v12 in both directions and respect private choices`() {
        val corrections = CandidateGlossary.readCorrections(
            File("src/main/res/raw/candidate_gloss_corrections.tsv").inputStream()
        )
        // v12 has no entry for uninstall. These other rows are from the published table.
        val base = table("download" to "卸载", "downloadable" to "可下载的", "下载" to "download",
            "卸载" to "unload cargo", "卸載" to "unload cargo", corrections = corrections)
        val words = listOf("Download", "uninstall", "Update", "downloadable", "下载", "下載", "卸载", "卸載", "更新")
        assertEquals(listOf("下载", "卸载", "更新", "可下载的", "download", "download", "uninstall", "uninstall", "update"),
            CandidateGlossary.annotate(base, words, emptyList()))
        assertEquals("uninstall", CandidateGlossary.englishFor(base, "卸载"))
        assertEquals(listOf("uninstall", "Download") to listOf("卸載", "下载"),
            CandidateGlossary.swapped(base, listOf("卸載", "Download"), emptyList()))

        // 11-11：v12 把「明天见」注成 “food that passes…”。
        val farewell = table("明天見" to "food that passes…", "明天见" to "food that passes…", corrections = corrections)
        assertEquals("see you tomorrow", CandidateGlossary.gloss(farewell, "明天见"))
        assertEquals("see you tomorrow", CandidateGlossary.gloss(farewell, "明天見"))

        base.overlay = table("download" to GlossTable.HIDDEN, "卸载" to "remove")
        assertNull(CandidateGlossary.gloss(base, "Download"))
        assertEquals("remove", CandidateGlossary.englishFor(base, "卸载"))
    }

    @Test
    fun `finds both directions and skips header lines`() {
        val glossary = table("apple" to "苹果", "你好" to "hello", "电脑" to "computer", "app" to "应用")
        assertEquals(4, glossary.size)
        assertEquals("苹果", glossary.lookup("apple"))
        assertEquals("hello", glossary.lookup("你好"))
        assertEquals("computer", glossary.lookup("电脑"))
    }

    @Test
    fun `prefix keys do not match each other`() {
        val glossary = table("app" to "应用", "apple" to "苹果", "apples" to "苹果们")
        assertEquals("应用", glossary.lookup("app"))
        assertEquals("苹果", glossary.lookup("apple"))
        assertNull(glossary.lookup("appl"))
        assertNull(glossary.lookup("applesauce"))
        assertNull(glossary.lookup(""))
        assertNull(glossary.lookup("# 来源与许可"))
    }

    @Test
    fun `ascii candidates are looked up case-insensitively, chinese as written`() {
        assertEquals("hello", CandidateGlossary.keyOf("Hello"))
        assertEquals("ios", CandidateGlossary.keyOf(" iOS "))
        assertEquals("你好", CandidateGlossary.keyOf("你好"))
    }

    @Test
    fun `letters and abbreviations get no Chinese gloss`() {
        val glossary = table("s" to "标量", "abc" to "预定章程", "kfc" to "肯德基", "nasa" to "美国航空航天局",
            "obsidian" to "黑曜石", "苹果" to "apple")
        assertEquals(listOf("", "", "", "", "", "黑曜石", "黑曜石", "apple"), CandidateGlossary.annotate(glossary,
            listOf("S", "s", "abc", "KFC", "NASA", "Obsidian", "obsidian", "苹果"), List(8) { "" }))
    }

    @Test
    fun `annotate keeps original comments while no glossary is loaded`() {
        assertEquals(listOf("ni hao", "ni hao"),
            CandidateGlossary.annotate(listOf("你好", "拟好"), listOf("ni hao", "ni hao")))
        assertEquals("ni hao", CandidateGlossary.annotate("你好", "ni hao"))
    }

    private val shiftTable get() = table("你好" to "hello", "电脑" to "computer", "computer" to "电脑",
        "苹果" to "apple", "东西" to "a long description that got cut…")

    @Test fun timestampKeepsItsMeaningInGlossAndShiftModes() {
        val texts = listOf("1793536505", "1234567890", "你好")
        val notes = listOf("时间戳", "", "ni hao")
        assertEquals(listOf("时间戳", "", "hello"), CandidateGlossary.annotate(shiftTable, texts, notes))
        assertEquals(listOf("1793536505", "1234567890", "hello") to listOf("时间戳", "", "你好"),
            CandidateGlossary.swapped(shiftTable, texts, notes))
    }

    @Test
    fun `shift-held selection commits the English of a Chinese candidate`() {
        assertEquals("hello", CandidateGlossary.englishFor(shiftTable, "你好"))
        assertEquals("computer", CandidateGlossary.englishFor(shiftTable, "电脑"))
    }

    @Test
    fun `candidates without a complete English word keep committing Chinese`() {
        assertNull(CandidateGlossary.englishFor(shiftTable, "拟好"))          // 词典里没有
        assertNull(CandidateGlossary.englishFor(shiftTable, "computer"))      // 本身就是英文
        assertNull(CandidateGlossary.englishFor(shiftTable, "东西"))          // 释义被截断，不是完整英文
    }

    @Test
    fun `loaded glossary shows nothing instead of falling back to pinyin`() {
        assertEquals(listOf("hello", "", "电脑", ""),
            CandidateGlossary.annotate(shiftTable, listOf("你好", "拟好", "computer", "nih"),
                listOf("ni hao", "ni hao", "", "ni h")))
    }

    @Test
    fun `holding shift swaps Chinese and English only where English exists`() {
        val (words, notes) = CandidateGlossary.swapped(shiftTable,
            listOf("你好", "拟好", "computer", "东西"), listOf("ni hao", "ni hao", "", "dong xi"))
        assertEquals(listOf("hello", "拟好", "computer", "东西"), words)
        assertEquals(listOf("你好", "", "电脑", "a long description that got cut…"), notes)
    }

    @Test
    fun `physical shift with a digit or space picks the same candidate as without shift`() {
        assertEquals(0, shiftedCandidateOffset(KeyEvent.KEYCODE_1) { 7 })
        assertEquals(8, shiftedCandidateOffset(KeyEvent.KEYCODE_9) { 7 })
        assertEquals(9, shiftedCandidateOffset(KeyEvent.KEYCODE_0) { 7 })
        assertEquals(7, shiftedCandidateOffset(KeyEvent.KEYCODE_SPACE) { 7 })
        assertNull(shiftedCandidateOffset(KeyEvent.KEYCODE_A) { 7 })
    }

    @Test
    fun `long-pressing the T9 next key counts as holding shift`() {
        try {
            assertEquals(false, CandidateGlossary.swapHeld(screenShiftHeld = false))
            CandidateGlossary.t9NextHeld.value = true
            assertEquals(true, CandidateGlossary.swapHeld(screenShiftHeld = false))
            CandidateGlossary.t9NextHeld.value = false
            CandidateGlossary.physicalShiftHeld.value = true
            assertEquals(true, CandidateGlossary.swapHeld(screenShiftHeld = false))
            CandidateGlossary.physicalShiftHeld.value = false
            assertEquals(true, CandidateGlossary.swapHeld(screenShiftHeld = true))
        } finally {
            CandidateGlossary.t9NextHeld.value = false
            CandidateGlossary.physicalShiftHeld.value = false
        }
    }

    private val composeTable get() = table("我" to "I", "想" to "think", "吃" to "eat", "吃饭" to "have a meal",
        "会" to "~meet", "昨天" to "yesterday", "你好" to "hello", "我估计" to "+I estimate")

    @Test
    fun `everyday phrases come pre-composed from the dictionary, other combinations show nothing`() {
        // 词典里预先拼好的日常短语（+ 前缀）显示时去掉前缀；运行时不再按字数拼。
        assertEquals(listOf("I estimate", "", "hello"),
            CandidateGlossary.annotate(composeTable, listOf("我估计", "我吃饭", "你好"), listOf("", "", "")))
        // 单字照常显示，去掉不可靠标记。
        assertEquals("meet", CandidateGlossary.annotate(composeTable, listOf("会"), listOf("")).first())
    }

    @Test
    fun `runtime composition, when switched on, uses dictionary words and fixed characters only`() {
        assertEquals("I have a meal", CandidateGlossary.compose(composeTable, "我吃饭"))
        assertEquals("I yesterday", CandidateGlossary.compose(composeTable, "我昨天"))
    }

    @Test
    fun `sentences longer than four characters are never composed`() {
        assertNull(CandidateGlossary.compose(composeTable, "我昨天吃饭"))
    }

    @Test
    fun `phrases with a non-fixed single character show nothing rather than a guess`() {
        // 想、会 不在固定常用字里（会 还只有不可靠释义）；拟 词典里没有。
        assertNull(CandidateGlossary.compose(composeTable, "我想吃饭"))
        assertNull(CandidateGlossary.compose(composeTable, "我会吃"))
        assertNull(CandidateGlossary.compose(composeTable, "我拟"))
    }

    @Test
    fun `shift only commits dictionary words, never a word-by-word phrase gloss`() {
        assertNull(CandidateGlossary.englishFor(composeTable, "我吃饭"))
        assertNull(CandidateGlossary.englishFor(composeTable, "我估计"))   // 预拼短语只显示不上屏
        assertEquals("meet", CandidateGlossary.englishFor(composeTable, "会"))
        assertEquals("have a meal", CandidateGlossary.englishFor(composeTable, "吃饭"))
    }

    @Test
    fun `private add-on overrides or hides public glosses and falls through otherwise`() {
        val base = table("保存" to "conserve", "的" to "of", "电脑" to "computer")
        base.overlay = table("保存" to "save", "的" to GlossTable.HIDDEN, "频谱" to "spectrum")
        assertEquals("save", base.lookup("保存"))
        assertNull(base.lookup("的"))
        assertEquals("spectrum", base.lookup("频谱"))
        assertEquals("computer", base.lookup("电脑"))
        assertEquals("save", CandidateGlossary.gloss(base, "保存"))
        assertNull(CandidateGlossary.englishFor(base, "的"))
    }

    @Test
    fun `sentence-final particles and numbers with units get a gloss, but are never committed`() {
        val base = table("早上" to "morning", "好了" to "done", "会" to "~meet", "懒" to "lazy", "是" to "is")
        assertEquals("morning", CandidateGlossary.gloss(base, "早上啊"))
        assertEquals("done?", CandidateGlossary.gloss(base, "好了吗"))
        assertEquals("done?", CandidateGlossary.gloss(base, "好了么"))
        assertNull(CandidateGlossary.gloss(base, "会吧"))          // 单字只有不可靠释义，不套
        assertEquals("lazy", CandidateGlossary.gloss(base, "懒啊"))
        assertNull(CandidateGlossary.gloss(base, "是啊"))
        assertEquals("nine o'clock", CandidateGlossary.gloss(base, "九点"))
        assertEquals("half past six", CandidateGlossary.gloss(base, "六点半"))
        assertEquals("nine o'clock", CandidateGlossary.gloss(base, "九点喽"))
        assertEquals("twenty-one days", CandidateGlossary.gloss(base, "二十一天"))
        assertEquals("one year", CandidateGlossary.gloss(base, "一年"))
        assertEquals("two times", CandidateGlossary.gloss(base, "两次"))
        assertNull(CandidateGlossary.gloss(base, "十三点"))         // 不是钟点
        assertNull(CandidateGlossary.gloss(base, "零天"))
        assertNull(CandidateGlossary.englishFor(base, "九点"))
        assertNull(CandidateGlossary.englishFor(base, "早上啊"))
    }
}
