package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用户 11-15：开没开数字行 × 上下角标显示哪边，每种情形一套独立的角标（共六套），每格可设跟随全角半角，
 * 设置页能整排输入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CornerVariantSymbolsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = SettingsPreferences.getPrefsPublic(app)
    private val letters = QwertySwipeSymbols.keys.map(Char::toString)
    private val firstRow = QwertySwipeSymbols.rows[0].map(Char::toString)

    @Before fun clean() { prefs.edit().clear().commit() }

    @Test fun `the three switches pick one of six layouts`() {
        assertEquals(CornerVariant.BOTH, CornerVariant.of(numberRow = false, up = true, down = true))
        assertEquals(CornerVariant.UP, CornerVariant.of(false, true, false))
        assertEquals(CornerVariant.DOWN, CornerVariant.of(false, false, true))
        assertEquals(CornerVariant.NR_BOTH, CornerVariant.of(true, true, true))
        assertEquals(CornerVariant.NR_UP, CornerVariant.of(true, true, false))
        assertEquals(CornerVariant.NR_DOWN, CornerVariant.of(true, false, true))
        // 上下都不显示：看不见，照样能滑出，用上下都显示的那一套。
        assertEquals(CornerVariant.BOTH, CornerVariant.of(false, false, false))
        assertEquals(CornerVariant.NR_BOTH, CornerVariant.of(true, false, false))
        assertEquals(6, CornerVariant.entries.map { it.id }.toSet().size)
    }

    @Test fun `no shown slot is empty in any layout`() {
        for (english in listOf(false, true)) for (variant in CornerVariant.entries) for (key in letters) {
            if (variant.showsUp) assertTrue("$variant english=$english $key 上方有符号",
                QwertySwipeSymbols.up(english, variant).getValue(key).isNotEmpty())
            if (variant.showsDown) assertTrue("$variant english=$english $key 下方有符号",
                QwertySwipeSymbols.down(english, variant).getValue(key).isNotEmpty())
        }
    }

    @Test fun `single corner layouts keep the digits unless the number row has them`() {
        for (english in listOf(false, true)) {
            assertEquals("1234567890", firstRow.joinToString("") { QwertySwipeSymbols.up(english, CornerVariant.UP).getValue(it) })
            assertEquals("1234567890", firstRow.joinToString("") { QwertySwipeSymbols.down(english, CornerVariant.DOWN).getValue(it) })
            for (variant in listOf(CornerVariant.NR_UP, CornerVariant.NR_DOWN, CornerVariant.NR_BOTH)) for (key in firstRow) {
                assertTrue(QwertySwipeSymbols.up(english, variant).getValue(key).none(Char::isDigit))
                assertTrue(QwertySwipeSymbols.down(english, variant).getValue(key).none(Char::isDigit))
            }
            // 只显示一边时，显示的那一边不管是上方还是下方都是同一套；显示的一边里没有重复的符号。
            assertEquals(QwertySwipeSymbols.up(english, CornerVariant.UP), QwertySwipeSymbols.down(english, CornerVariant.DOWN))
            assertEquals(QwertySwipeSymbols.up(english, CornerVariant.NR_UP), QwertySwipeSymbols.down(english, CornerVariant.NR_DOWN))
            for (variant in listOf(CornerVariant.UP, CornerVariant.NR_UP))
                assertEquals(26, QwertySwipeSymbols.up(english, variant).values.toSet().size)
        }
        // 用户 11-15 定稿：开数字行只显示一边时第一排放常用符号；中文 L 换成 -，免得和 Q 的 “ 重复。
        assertEquals(listOf("“", "”", "‘", "’", "·", "￥", "#", "%", "&", "*"),
            firstRow.map { QwertySwipeSymbols.up(false, CornerVariant.NR_UP).getValue(it) })
        assertEquals("-", QwertySwipeSymbols.up(false, CornerVariant.NR_UP).getValue("l"))
        assertEquals("“", QwertySwipeSymbols.up(false, CornerVariant.UP).getValue("l"))
        assertEquals(listOf("'", "#", "$", "%", "&", "*", "+", "=", "\\", "_"),
            firstRow.map { QwertySwipeSymbols.up(true, CornerVariant.NR_UP).getValue(it) })
        // 第一排固定，不跟随全角半角；第二三排和不开数字行时一样跟随。
        val nrFollow = QwertySwipeSymbols.followPreset(false, CornerVariant.NR_UP)
        assertTrue(firstRow.none { "up.$it" in nrFollow })
        assertTrue("up.g" in nrFollow)
    }

    @Test fun `follow width swaps a paired symbol and leaves the rest alone`() {
        assertEquals("(", QwertySwipeSymbols.width("（", false))
        assertEquals("（", QwertySwipeSymbols.width("（", true))
        assertEquals("（", QwertySwipeSymbols.width("(", true))
        assertEquals("_", QwertySwipeSymbols.width("—", false))
        assertEquals("/", QwertySwipeSymbols.width("、", false))
        for (fixed in listOf("@", "…", "#", "1", "", "你好")) {
            assertFalse(QwertySwipeSymbols.hasWidthPair(fixed))
            assertEquals(fixed, QwertySwipeSymbols.width(fixed, true))
            assertEquals(fixed, QwertySwipeSymbols.width(fixed, false))
        }
        val follows = setOf("up.g")
        fun up(full: Boolean?, set: Set<String> = follows) = QwertySwipeSymbols.apply("g", null, emptyMap(), null,
            variant = CornerVariant.UP, follows = set, fullWidth = full)!!.swipeUp!!
        assertEquals("（", up(true).value)
        assertEquals("(", up(false).value)
        // 键上印的和上屏的是同一个。
        assertEquals("(", up(false).label)
        // 设置页（fullWidth = null）看到存着的原样；没打开跟随的格子不变。
        assertEquals("（", up(null).value)
        assertEquals("（", up(false, emptySet()).value)
    }

    @Test fun `only chinese single corner layouts follow width by default and overrides win`() {
        for (variant in CornerVariant.entries) {
            assertEquals(emptySet<String>(), QwertySwipeSymbols.followPreset(true, variant))
            if (variant.showsUp && variant.showsDown) assertEquals(emptySet<String>(), QwertySwipeSymbols.followPreset(false, variant))
        }
        val preset = QwertySwipeSymbols.followPreset(false, CornerVariant.UP)
        assertTrue("up.g" in preset)
        // @ 和 … 只有一种写法，数字也不跟随。
        assertFalse("up.a" in preset); assertFalse("up.d" in preset); assertFalse("up.q" in preset)
        assertTrue(QwertySwipeSymbols.followPreset(false, CornerVariant.DOWN).all { it.startsWith("down.") })
        val effective = QwertySwipeSymbols.follows(false, CornerVariant.UP, mapOf("up.g" to false, "down.f" to true))
        assertFalse("up.g" in effective)
        assertTrue("down.f" in effective)
        assertEquals(emptySet<String>(), QwertySwipeSymbols.follows(true, CornerVariant.UP, mapOf("up.g" to true)))
    }

    @Test fun `each layout is stored on its own`() {
        prefs.edit().putString("qwerty_symbols_zh_up.a", "★").putString("qwerty_symbols_zh_nr:up.a", "☆")
            .putStringSet("qwerty_symbol_follow_zh_nr_up", setOf("up.g=0", "down.f=1")).commit()
        assertEquals(mapOf("up.a" to "★"), QwertySwipeSymbols.load(app, false))
        assertEquals(mapOf("up.a" to "☆"), QwertySwipeSymbols.load(app, false, CornerVariant.NR_BOTH))
        assertEquals(emptyMap<String, String>(), QwertySwipeSymbols.load(app, false, CornerVariant.UP))
        assertEquals(emptyMap<String, String>(), QwertySwipeSymbols.load(app, true, CornerVariant.NR_BOTH))
        assertEquals(mapOf("up.g" to false, "down.f" to true), QwertySwipeSymbols.loadFollows(app, false, CornerVariant.NR_UP))
        assertEquals(emptyMap<String, Boolean>(), QwertySwipeSymbols.loadFollows(app, false, CornerVariant.UP))
    }

    @Test fun `the old shared edits are carried into the new layouts once`() {
        // 以前只有一份修改：A 上方改成 ★，S 下方改成 ☆；第一排存着旧预设（Q 上 1、Q 下 +）和自己改的（W 上 ♥）。
        prefs.edit().putString("qwerty_symbols_zh_up.a", "★").putString("qwerty_symbols_zh_down.s", "☆")
            .putString("qwerty_symbols_zh_up.q", "1").putString("qwerty_symbols_zh_down.q", "+")
            .putString("qwerty_symbols_zh_up.w", "♥").commit()
        QwertySwipeSymbols.adoptVariants(app)
        // 开数字行、上下都显示：全带上，第一排的旧预设不带。
        assertEquals(mapOf("up.a" to "★", "down.s" to "☆", "up.w" to "♥"), QwertySwipeSymbols.load(app, false, CornerVariant.NR_BOTH))
        // 只显示上方：显示的一边是新排的，只带不显示的下方。
        assertEquals(mapOf("down.s" to "☆", "down.q" to "+"), QwertySwipeSymbols.load(app, false, CornerVariant.UP))
        assertEquals(mapOf("up.a" to "★", "up.q" to "1", "up.w" to "♥"), QwertySwipeSymbols.load(app, false, CornerVariant.DOWN))
        assertEquals(mapOf("down.s" to "☆"), QwertySwipeSymbols.load(app, false, CornerVariant.NR_UP))
        // 原来那一套不动；只做一次，之后在原来那套里的修改不再带过去。
        assertEquals("★", QwertySwipeSymbols.load(app, false).getValue("up.a"))
        prefs.edit().putString("qwerty_symbols_zh_up.d", "◆").commit()
        QwertySwipeSymbols.adoptVariants(app)
        assertFalse("up.d" in QwertySwipeSymbols.load(app, false, CornerVariant.NR_BOTH))
    }

    @Test fun `a whole row is written as one line`() {
        assertEquals("1234567890", SymbolRowText.format("1234567890".map(Char::toString)))
        // 空着的格子就是一个空格（用户 11-19：不用打 □）。
        assertEquals("（） 、", SymbolRowText.format(listOf("（", "）", "", "、")))
        assertEquals(listOf("（", "）", "", "、"), SymbolRowText.parse("（） 、", 4))
        // 前后的空格不去掉，它们也是空位；全角空格一样。
        assertEquals(listOf("", "a", "", ""), SymbolRowText.parse(" a  ", 4))
        assertEquals(listOf("a", "", "b"), SymbolRowText.parse("a\u3000b", 3))
        assertEquals(listOf("", "", ""), SymbolRowText.parse("   ", 3))
        // 写少了后面空着，写多了丢掉。
        assertEquals(listOf("a", "b", ""), SymbolRowText.parse("ab", 3))
        assertEquals(listOf("a", "b"), SymbolRowText.parse("abc", 2))
        assertEquals(4, SymbolRowText.cellCount("a b "))
        // 短语用「」括起来算一格；「」里只有一个字或没有字时，「」是普通符号。
        assertEquals("「你好」! ", SymbolRowText.format(listOf("你好", "!", "")))
        assertEquals(listOf("你好", "!", ""), SymbolRowText.parse("「你好」! ", 3))
        assertEquals(listOf("「", "」", "a"), SymbolRowText.parse("「」a", 3))
        assertEquals(listOf("「", "，", "」"), SymbolRowText.parse("「，」", 3))
        assertEquals(listOf("「", "a", "", "b"), SymbolRowText.parse("「a b」", 4))
        // 表情这类占两个 char 的字符算一格。
        assertEquals(listOf("😀", "a"), SymbolRowText.parse("😀a", 2))
        assertEquals(listOf("", "", ""), SymbolRowText.parse("", 3))
        // 写出来再读回去不变。
        val row = listOf("“", "", "你好", "\\", "", "「", "")
        assertEquals(row, SymbolRowText.parse(SymbolRowText.format(row), row.size))
    }

    @Test fun `a phrase may end with spaces`() {
        // 用户 11-22：半角符号后面加空格（「, 」）不能被吞。
        assertEquals("「, 」a", SymbolRowText.format(listOf(", ", "a")))
        assertEquals(listOf(", ", "a"), SymbolRowText.parse("「, 」a", 2))
        assertEquals(listOf("ok\u3000", "b"), SymbolRowText.parse("「ok\u3000」b", 2))
        val row = listOf(", ", "", ". ", "你好", "")
        assertEquals(row, SymbolRowText.parse(SymbolRowText.format(row), row.size))
        // 空格后面还有字照旧是几格；「 」照旧是符号加空位。
        assertEquals(listOf("「", "a", "", "b"), SymbolRowText.parse("「a b」", 4))
        assertEquals(listOf("「", "", "」"), SymbolRowText.parse("「 」", 3))
        assertEquals(listOf("「", "a", "", ""), SymbolRowText.parse("「a  ", 4))
    }
}
