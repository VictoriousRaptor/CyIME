package com.kingzcheung.xime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 11-16：中文页要有间隔号；中、英两页能对应的符号放在同一格，常用的在前。 */
class SymbolPanelPairingTest {
    private fun page(id: String) = SymbolData.categories.first { it.id == id }.symbols
    private val zh = page("punctuationSymbols")
    private val en = page("englishSymbols")

    @Test fun chinesePageHasTheInterpunctAndEllipsisNearTheFront() {
        assertTrue(zh.indexOf("·") in 0 until 20)
        assertTrue(zh.indexOf("…") in 0 until 20)
    }

    @Test fun sameCellHoldsTheChineseAndEnglishFormOfOneSymbol() {
        val pairs = listOf("，" to ",", "。" to ".", "？" to "?", "！" to "!", "：" to ":", "；" to ";",
            "、" to "\\", "“" to "\"", "‘" to "'", "（" to "(", "）" to ")", "…" to "…", "—" to "-",
            "～" to "~", "·" to "·", "《" to "<", "》" to ">", "【" to "[", "】" to "]", "／" to "/",
            "＠" to "@", "＃" to "#", "￥" to "$", "％" to "%", "＆" to "&", "＊" to "*", "＿" to "_",
            "＝" to "=", "＋" to "+", "｜" to "|", "｀" to "`", "｛" to "{", "｝" to "}")
        for ((c, e) in pairs) assertEquals("$c / $e", zh.indexOf(c), en.indexOf(e))
        // 最常用的句读排在最前面，两页一样。
        assertEquals(listOf("，", "。", "？", "！"), zh.take(4))
        assertEquals(listOf(",", ".", "?", "!"), en.take(4))
    }

    @Test fun pagesHaveNoDuplicateKeys() {
        assertEquals(zh.size, zh.distinct().size)
        assertEquals(en.size, en.distinct().size)
    }

    @Test fun noEarlierSymbolWasDropped() {
        val oldZh = listOf("。", "，", "、", "：", "；", "‘", "’", "“", "”", "〝", "〞", "﹕", "︰", "﹔", "﹖", "﹑",
            "？", "！", "～", "—", "｜", "‖", "＂", "〃", "｀", "﹫", "﹏", "﹋", "︴", "々", "﹟", "﹩", "﹠", "﹪",
            "﹡", "﹢", "﹦", "‐", "￣", "―", "﹨", "﹍", "﹎", "＿", "（", "）", "〈", "〉", "﹛", "﹜", "『", "』",
            "〖", "〗", "［", "］", "《", "》", "〔", "〕", "「", "」", "【", "】", "︵", "︷", "︿", "︹", "︽",
            "︶", "︸", "﹀", "︺", "︾", "﹂", "﹄", "︼", "﹁", "﹃", "︻", "▲", "●", "□")
        val oldEn = listOf(".", ",", ";", ":", "!", "?", "'", "\"", "@", "#", "$", "%", "^", "&", "*", "-",
            "_", "=", "+", "~", "`", "|", "\\", "/", "(", ")", "[", "]", "{", "}", "<", ">",
            "·", "•", "…", "′", "″", "‴", "°", "§", "¶", "¦", "¬", "†", "‡", "‰", "‱", "©",
            "®", "™", "№", "ª", "º", "ˆ", "ˇ", "¨", "¸", "´", "¯", "˜", "ˉ",
            "¡", "¿", "‹", "›", "⎡", "⎦", "⎣", "⎤")
        assertEquals(emptyList<String>(), oldZh.filterNot { it in zh })
        assertEquals(emptyList<String>(), oldEn.filterNot { it in en })
    }
}
