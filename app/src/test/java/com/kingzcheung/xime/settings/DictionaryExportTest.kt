package com.kingzcheung.xime.settings

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryExportTest {
    private val words = listOf(
        DictEntry("你好", "ni hao", 5),
        DictEntry("绿色", "lv se ", 2),       // Rime user dictionaries end codes with a space
        DictEntry("你好", "ni hao", 9),       // same word and pinyin: written once, larger count
        DictEntry("好", "hao", 7),            // single character
        DictEntry("今天天气不错", "jin tian tian qi bu cuo", 0),
        DictEntry("你好abc", "ni hao a b c", 1),
        DictEntry("世界", "shi", 3),          // pinyin does not match the characters
    )

    private fun write(format: DictionaryExport.Format) = DictionaryExport.write(format, "测试", words)

    private fun utf16(bytes: ByteArray): String {
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), bytes.copyOf(2))
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }

    @Test fun `each format writes the lines its input method imports`() {
        val text = write(DictionaryExport.Format.TEXT)
        assertEquals(3, text.written)
        assertEquals(3, text.skipped)
        assertEquals("你好\tni hao\t9\n绿色\tlv se\t2\n今天天气不错\tjin tian tian qi bu cuo\t1\n", String(text.bytes, Charsets.UTF_8))
        assertEquals("'ni'hao 你好\r\n'lv'se 绿色\r\n'jin'tian'tian'qi'bu'cuo 今天天气不错\r\n",
            String(write(DictionaryExport.Format.SOGOU).bytes, charset("GBK")))
        assertEquals("ni'hao 你好 9\r\n", utf16(write(DictionaryExport.Format.QQ).bytes).lines().first() + "\r\n")
        assertEquals("你好\tni'hao'\t9", utf16(write(DictionaryExport.Format.BAIDU).bytes).lines().first())
        assertEquals("你好(ni|hao) 9", utf16(write(DictionaryExport.Format.BAIDU_MOBILE).bytes).lines().first())
        val rime = String(write(DictionaryExport.Format.RIME).bytes, Charsets.UTF_8)
        assertTrue(rime.startsWith("# CyIME 导出的词库\n---\nname: cyime_export\n"))
        assertTrue(rime.contains("\n...\n你好\tni hao\t9\n"))
        val zip = ZipInputStream(write(DictionaryExport.Format.GBOARD).bytes.inputStream())
        assertEquals("dictionary.txt", zip.nextEntry!!.name)
        assertEquals("# Gboard Dictionary version:1\nnihao\t你好\tzh-CN\n", String(zip.readBytes(), Charsets.UTF_8).lines()
            .take(2).joinToString("\n", postfix = "\n"))
    }

    @Test fun `english words of an imported list go into text and Rime only`() {
        // #560：只有英文的词库以前导出 0 个词。
        val mixed = listOf(DictEntry("你好", "ni hao", 5), DictEntry("Team Spirit", "", 0), DictEntry("x", "", 0))
        val text = DictionaryExport.write(DictionaryExport.Format.TEXT, "测试", mixed)
        assertEquals(2, text.written)
        assertEquals(1, text.skipped)
        assertEquals("你好\tni hao\t5\nTeam Spirit\tteamspirit\t1\n", String(text.bytes, Charsets.UTF_8))
        val parsed = TxtCellDictionary.parse(text.bytes)
        assertEquals(listOf("Team Spirit"), parsed.english)
        assertTrue(String(DictionaryExport.write(DictionaryExport.Format.RIME, "测试", mixed).bytes, Charsets.UTF_8)
            .endsWith("\n...\n你好\tni hao\t5\nTeam Spirit\tteamspirit\t1\n"))
        val sogou = DictionaryExport.write(DictionaryExport.Format.SOGOU, "测试", mixed)
        assertEquals(1, sogou.written)
        assertEquals(2, sogou.skipped)
        assertEquals(0, DictionaryExport.write(DictionaryExport.Format.GBOARD, "测试", listOf(DictEntry("hello", "", 0))).written)
    }

    @Test fun `CyIME json is the phone and Windows import format`() {
        val bytes = write(DictionaryExport.Format.CYIME).bytes
        val parsed = JsonCellDictionary.parse(bytes)
        assertEquals("测试", parsed.name)
        assertEquals(listOf("你好|ni hao", "绿色|lv se", "今天天气不错|jin tian tian qi bu cuo"),
            parsed.entries.map { it.word + "|" + it.code })
        assertTrue(String(bytes, Charsets.UTF_8).startsWith("{\"format\":\"cyime.dictionary.v1\",\"name\":\"测试\","))
    }

    @Test fun `every text format reads back through the phone importer`() {
        val dir = Files.createTempDirectory("export").toFile()
        try {
            DictionaryExport.Format.entries.filter { it != DictionaryExport.Format.CYIME }.forEach { format ->
                val file = File(dir, "out.${format.extension}").apply { writeBytes(write(format).bytes) }
                val text = DictionaryImportFormats.toText(file) ?: file
                val parsed = TxtCellDictionary.parse(text.readBytes())
                assertEquals(format.name, listOf("你好|ni hao", "绿色|lv se", "今天天气不错|jin tian tian qi bu cuo"),
                    parsed.entries.map { it.word + "|" + it.code })
                assertEquals(format.name, 0, parsed.skipped)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `words outside GBK are left out of the Sogou file only`() {
        val rare = listOf(DictEntry("䶮䶮", "yan yan", 1), DictEntry("你好", "ni hao", 1))
        val sogou = DictionaryExport.write(DictionaryExport.Format.SOGOU, "x", rare)
        assertEquals(1, sogou.written)
        assertEquals(1, sogou.skipped)
        assertEquals(2, DictionaryExport.write(DictionaryExport.Format.QQ, "x", rare).written)
    }

    @Test fun `json name falls back and is escaped`() {
        val parsed = JsonCellDictionary.parse(DictionaryExport.write(DictionaryExport.Format.CYIME, "我的\"词\"\n", words).bytes)
        assertEquals("我的\"词\"", parsed.name)
        assertEquals("CyIME 词库", JsonCellDictionary.parse(DictionaryExport.write(DictionaryExport.Format.CYIME, " ", words).bytes).name)
    }

    @Test fun `user dictionary rows give learned words by count without deleted ones`() {
        val rows = sequenceOf(
            "# Rime user dictionary",
            "ni hao \t你好\tc=3 d=0.9 t=12",
            "shi jie \t世界\tc=8 d=1 t=13",
            "ni hao \t你好\tc=2 d=0.5 t=14",
            "shan diao \t删掉\tc=-1 d=0 t=15",
            "bad row",
        )
        assertEquals(listOf(DictEntry("世界", "shi jie", 8), DictEntry("你好", "ni hao", 5)), DictionaryExport.fromUserdb(rows))
    }

    @Test fun `imported packs are read from their Rime file`() {
        val rows = "# Imported\n---\nname: cell_x\n...\n你好\tni hao\t100\n世界\tshi jie\n".lineSequence()
        assertEquals(listOf(DictEntry("你好", "ni hao", 100), DictEntry("世界", "shi jie", 0)), DictionaryExport.fromRimeDictionary(rows))
    }
}
