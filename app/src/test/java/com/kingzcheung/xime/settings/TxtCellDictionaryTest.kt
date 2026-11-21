package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtCellDictionaryTest {
    private fun code(line: String) = TxtCellDictionary.parseLine(line)?.let { it.word + "|" + it.code }

    @Test
    fun `common line layouts`() {
        assertEquals("你好|ni hao", code("你好 ni hao"))
        assertEquals("你好|ni hao", code("你好\tni hao\t100"))      // Rime
        assertEquals("你好|ni hao", code("'ni'hao 你好"))            // Sogou export
        assertEquals("你好|ni hao", code("ni'hao 你好 1"))           // QQ export
        assertEquals("你好|ni hao", code("nihao,你好"))
        assertEquals("西安|xi an", code("西安 xian"))                // run together, split by character count
        assertEquals("策略|ce lve", code("策略 ce lue"))
        assertEquals("你好|ni hao", code("你好\tni'hao'\t1"))           // Baidu
        assertEquals("你好|ni hao", code("你好(ni|hao) 20000"))         // Baidu mobile
        assertEquals("你好|ni hao", code("nihao\t你好\tzh-CN"))         // Gboard dictionary.txt
        assertEquals("你好|ni hao", code("ni hao \t你好\tc=3 d=0.9 t=12"))  // Rime user dictionary snapshot
    }

    @Test
    fun `lines without usable pinyin are skipped`() {
        assertNull(code("你好"))
        assertNull(code("你好 hello"))
        assertNull(code("你 ni"))  // single characters are not imported, as with SCEL
    }

    @Test
    fun `stroke dictionary lines are kept for stroke input instead of skipped`() {
        assertEquals("猫娘", TxtCellDictionary.strokeWord("猫娘 pz"))
        assertEquals("猫娘", TxtCellDictionary.strokeWord("猫娘\tpzpshhsszhsh'zphnzhhzpn\t100"))
        assertEquals("骆驼", TxtCellDictionary.strokeWord("zzhpzns 骆驼"))
        assertNull(TxtCellDictionary.strokeWord("你好 hello"))
        assertNull(TxtCellDictionary.strokeWord("你好"))
        assertNull(TxtCellDictionary.strokeWord("你 p"))
        val words = ArrayList<String>()
        val text = "猫娘 pz\n你好 ni hao\n骆驼 zz\n猫娘 pzps'zphn\n你好 hello\n"
        val summary = TxtCellDictionary.read({ text.byteInputStream() }, { words += it }) { }
        assertEquals(listOf("猫娘", "骆驼"), words)
        assertEquals(1, summary.count)
        assertEquals(2, summary.stroke)
        assertEquals(1, summary.skipped)
        // 只有笔画编码的文件也能导入，不再报「没有读到带拼音的词条」。
        assertEquals(1, TxtCellDictionary.read({ "猫娘 pz\n".byteInputStream() }) { }.stroke)
    }

    @Test
    fun `encodings and rime header`() {
        val text = "# comment\n---\nname: x\n...\n你好\tni hao\n世界\tshi jie\n"
        val parsed = TxtCellDictionary.parse(text.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("你好", "世界"), parsed.entries.map { it.word })
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "你好 nihao\r\n".toByteArray(Charsets.UTF_16LE)
        assertEquals("ni hao", TxtCellDictionary.parse(utf16).entries.single().code)
        val gbk = "你好 ni hao\n".toByteArray(charset("GB18030"))
        assertEquals("你好", TxtCellDictionary.parse(gbk).entries.single().word)
    }

    @Test
    fun `english lines of a mixed list are kept as written`() {
        val text = "敌法师 di fa shi\nAme\nTeam Spirit\tteamspirit\t100\n33\nx\n"
        val parsed = TxtCellDictionary.parse(text.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("敌法师"), parsed.entries.map { it.word })
        assertEquals(listOf("Ame", "Team Spirit"), parsed.english)
        assertEquals(2, parsed.skipped)  // a bare number and one letter are no words
        assertEquals(listOf("Ame"), TxtCellDictionary.parse("Ame\n".toByteArray()).english)
    }

    @Test
    fun `tone marks as in wanxiang`() {
        assertEquals("阿爸|a ba", code("阿爸\tā bà\t716"))
        assertEquals("绿色|lv se", code("绿色\tlǜ sè\t100"))
        assertEquals("女儿|nv er", code("女儿\tnǚ ér\t100"))
        assertEquals("略微|lve wei", code("略微\tlüè wēi\t100"))
        assertEquals("你好|ni hao", code("你好 nǐhǎo"))
    }

    @Test
    fun `duplicates are dropped in file order without a map of all words`() {
        val text = "---\nname: x\n...\n你好\tnǐ hǎo\t9\n世界\tshì jiè\t8\n你好\tni hao\t1\n你好\tnǐ hāo\t1\n"
        val words = mutableListOf<String>()
        val summary = TxtCellDictionary.read({ text.byteInputStream() }) { words += it.word + "|" + it.code }
        assertEquals(listOf("你好|ni hao", "世界|shi jie"), words)
        assertEquals(2, summary.count)
        assertEquals(0, summary.skipped)
    }

    @Test
    fun `key set keeps every distinct key across growth`() {
        val set = TxtCellDictionary.LongSet()
        for (i in 0L until 100_000L) assertTrue(set.add(i * 0x9E3779B97F4A7C15uL.toLong()))
        for (i in 0L until 100_000L) assertFalse(set.add(i * 0x9E3779B97F4A7C15uL.toLong()))
        assertEquals(100_000, set.size)
    }
}
