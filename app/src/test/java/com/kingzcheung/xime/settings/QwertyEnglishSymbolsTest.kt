package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QwertyEnglishSymbolsTest {
    /** 中文上角标全角 → 英文同位置的半角；没有半角的照旧。 */
    private val halfWidth = mapOf("～" to "~", "—" to "-", "、" to "\\", "【" to "[", "】" to "]", "！" to "!",
        "（" to "(", "）" to ")", "：" to ":", "；" to ";", "《" to "<", "》" to ">", "？" to "?", "｀" to "`", "｜" to "|")

    @Test fun `english top corners are the half-width form of the chinese ones on the same key`() {
        for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
            val chinese = QwertySwipeSymbols.up(false).getValue(key)
            assertEquals(key, halfWidth[chinese] ?: chinese, QwertySwipeSymbols.up(true).getValue(key))
        }
    }

    @Test fun `english bottom corners add symbols instead of repeating the top corner`() {
        for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
            assertNotEquals(key, QwertySwipeSymbols.up(true).getValue(key), QwertySwipeSymbols.down(true).getValue(key))
        }
        // 第一排下角标中英一样。
        for (key in "qwertyuiop".map(Char::toString)) {
            assertEquals(QwertySwipeSymbols.down(false).getValue(key), QwertySwipeSymbols.down(true).getValue(key))
        }
        assertEquals(listOf("{", "}", "|", "+"), listOf("j", "k", "h", "s").map { QwertySwipeSymbols.down(true).getValue(it) })
    }
}
