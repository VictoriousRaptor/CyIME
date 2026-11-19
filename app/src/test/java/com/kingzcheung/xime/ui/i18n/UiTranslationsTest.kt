package com.kingzcheung.xime.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiTranslationsTest {
    private val table = UiTranslations.parse(
        """
        # 注释行
        设置	Settings
        九键	9-key
        方案	Scheme
        已切换到 {0}	Switched to {0}
        已下载 {0} 个	Downloaded {0}
        {0} 个	{0} items
        {0}：{1}	{0}: {1}
        成语示例	=
        第一行\n第二行	Line one\nLine two
        \# 开头的行跳过	Lines starting with # are skipped
        """.trimIndent()
    )

    @Test fun `exact sentence is replaced`() {
        assertEquals("Settings", table.translate("设置"))
    }

    @Test fun `unknown Chinese stays as it is`() {
        assertEquals("没有这一句", table.translate("没有这一句"))
        assertNull(table.lookup("没有这一句"))
    }

    @Test fun `text without Chinese is untouched`() {
        assertEquals("Rime 0.9", table.translate("Rime 0.9"))
    }

    @Test fun `template keeps the value and translates a Chinese value`() {
        assertEquals("Switched to rime_ice", table.translate("已切换到 rime_ice"))
        assertEquals("Switched to 9-key", table.translate("已切换到 九键"))
    }

    @Test fun `longer literal template wins`() {
        assertEquals("Downloaded 3", table.translate("已下载 3 个"))
        assertEquals("5 items", table.translate("5 个"))
    }

    @Test fun `template without Chinese literal is not loaded`() {
        // 「{0}：{1}」会套住任何文字，不收；这里按句末/分隔规则也没有能翻的段，原样返回。
        assertEquals("甲：乙", table.translate("甲：乙"))
    }

    @Test fun `kept entries stay Chinese`() {
        assertEquals("成语示例", table.translate("成语示例"))
    }

    @Test fun `escapes are read`() {
        assertEquals("Line one\nLine two", table.translate("第一行\n第二行"))
        assertEquals("Lines starting with # are skipped", table.translate("# 开头的行跳过"))
    }

    @Test fun `trailing punctuation and joined parts`() {
        assertEquals("Settings: ", table.translate("设置："))
        assertEquals("9-key · Scheme", table.translate("九键 · 方案"))
        assertEquals("9-key, Scheme", table.translate("九键、方案"))
        assertEquals("  Settings ", table.translate("  设置 "))
    }

    @Test fun `shipped English table parses and keeps placeholders`() {
        val file = File("src/main/assets/i18n/en.tsv")
        val lines = file.readLines().filter { it.isNotEmpty() && !it.startsWith("#") && '\t' in it }
        assertTrue(lines.size > 3000)
        val slot = Regex("\\{(\\d+)\\}")
        for (line in lines) {
            val (zh, en) = line.split('\t', limit = 2)
            if (en == "=") continue
            assertEquals(line, slot.findAll(zh).map { it.value }.toSet(), slot.findAll(en).map { it.value }.toSet())
        }
        val parsed = UiTranslations.parse(file.readText())
        assertTrue(parsed.size > 3000)
        assertEquals("Follow system", parsed.translate("跟随系统"))
    }
}
