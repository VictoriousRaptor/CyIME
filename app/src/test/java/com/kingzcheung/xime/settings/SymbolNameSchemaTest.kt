package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「打符号名出符号」（11-18 起默认开）：打「加号」在词后面多出 +。九键、14 键方案直接带 simplifier@cyime_symbols，
 * 26 键雾凇和双拼由 tasks-chinese.gradle.kts 插入；四种键盘共用 opencc/cyime_symbols.json 一张表。
 */
class SymbolNameSchemaTest {
    private val chinese = File("src/main/assets/rime_chinese")
    private val table = File(chinese, "opencc/cyime_symbols.txt")

    @Test fun nineAndFourteenKeySchemasCarryTheSymbolFilter() {
        for (name in listOf("t9_pinyin", "pinyin_14jian", "double_pinyin_flypy_14jian")) {
            val text = File(chinese, "$name.schema.yaml").readText()
            // 表情之后、繁体转换之前：符号紧跟在词后面，表情排在符号后面；繁体输出开着时词照样转正体。
            assertTrue(name, text.contains("    - simplifier@emoji\n") && text.contains("    - simplifier@cyime_symbols\n"))
            assertTrue(name, text.indexOf("    - simplifier@cyime_symbols\n") < text.indexOf("    - simplifier@traditionalize\n"))
            assertTrue(name, text.indexOf("    - simplifier@emoji\n") < text.indexOf("    - simplifier@cyime_symbols\n"))
            assertTrue(name, text.contains("\ncyime_symbols:\n  option_name: cyime_symbol_names\n  opencc_config: cyime_symbols.json\n"))
            // 方案开关里不登记，选项只由设置写，读不到就是关。
            assertTrue(name, !text.contains("- name: cyime_symbol_names"))
        }
        val gradle = File("build-logic/tasks-chinese.gradle.kts").readText()
        assertTrue(gradle.contains("withSymbolNames(withDatetimeHints(it))"))
    }

    @Test fun tableKeepsTheWordFirstAndHasNoDuplicateNames() {
        val rows = table.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        val names = rows.map { it.substringBefore('\t') }
        assertEquals("同一个符号名只能有一行，OpenCC 文本表遇到重复键会加载失败", names.size, names.toSet().size)
        val byName = rows.associate { row ->
            val (name, values) = row.split('\t').also { assertEquals(row, 2, it.size) }
            val forms = values.split(' ')
            // 第一个值是词本身：词留在原位，符号跟在后面；整句里含符号名时（我要加号码）也不被改写。
            assertEquals(row, name, forms.first())
            assertTrue(row, forms.size >= 2 && forms.none { it.isEmpty() })
            name to forms.drop(1)
        }
        assertEquals(listOf("+"), byName["加号"])
        assertEquals(listOf("="), byName["等号"])
        assertEquals(listOf("？", "?"), byName["问号"])
        assertEquals(listOf("（）", "()"), byName["括号"])
        assertEquals(listOf("%"), byName["百分号"])
        // 11-18 补齐的一类「非中文常用符号」：温度、角度、上下标、数学、分数、单位、希腊字母中文名。
        assertEquals(listOf("℃"), byName["摄氏度"])
        assertEquals(listOf("°"), byName["度"])
        assertEquals(listOf("²"), byName["平方"])
        assertEquals(listOf("³"), byName["立方"])
        assertTrue(byName.getValue("上标").containsAll(listOf("¹", "²", "³", "ⁿ")))
        assertTrue(byName.getValue("下标").containsAll(listOf("₁", "₂", "₃")))
        assertEquals(listOf("∑"), byName["求和"])
        assertEquals(listOf("½"), byName["二分之一"])
        assertEquals(listOf("θ", "Θ"), byName["西塔"])
        assertEquals(listOf("α", "Α"), byName["阿尔法"])
        // 希腊字母只收多字中文名：派、缪、纽这类单字是常打的字，交给希腊字母开关（拼音名）去管。
        assertTrue(listOf("派", "缪", "纽", "肉").none { it in byName })
    }
}
