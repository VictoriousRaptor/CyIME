package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 11-16 要的越南语：Telex 照越南手机键盘（三星、Gboard、Laban Key）的打法，光标前的词随打随换。 */
class VietnameseTelexTest {
    private fun typed(keys: String): String {
        val telex = VietnameseTelex()
        var word = ""
        for (key in keys) word = telex.type(word, key)
        return word
    }

    private fun assertTyped(expected: Map<String, String>) =
        expected.forEach { (keys, word) -> assertEquals(keys, word, typed(keys)) }

    @Test fun shapesAndTones() {
        assertTyped(mapOf(
            "aa" to "â", "aw" to "ă", "ee" to "ê", "oo" to "ô", "ow" to "ơ", "uw" to "ư", "dd" to "đ",
            "tooi" to "tôi", "khoong" to "không", "ddaay" to "đây", "nawm" to "năm", "muaw" to "mưa",
            "as" to "á", "af" to "à", "ar" to "ả", "ax" to "ã", "aj" to "ạ", "asz" to "a",
            "cuar" to "của", "ddi" to "đi", "nuwowcs" to "nước", "ruowuj" to "rượu", "huuwx" to "hữu",
        ))
    }

    @Test fun marksCanComeAtTheEndOfTheWord() {
        // 声调、w、第二个元音都可以打在后面，位置自动放。
        assertTyped(mapOf(
            "vieetj" to "việt", "tieengs" to "tiếng", "chuyeenj" to "chuyện", "khuyeen" to "khuyên",
            "nguoiwf" to "người", "nguowif" to "người", "thuongw" to "thương", "dduwowngf" to "đường",
            "hoawcj" to "hoặc", "caan" to "cân", "quawng" to "quăng", "matj" to "mạt",
        ))
    }

    @Test fun toneGoesOnTheTraditionalPlace() {
        // 老写法：hòa、thủy、khỏe；qu、gi 后面的 u、i 算声母。
        assertTyped(mapOf("hoaf" to "hòa", "thuyr" to "thủy", "khoer" to "khỏe", "quys" to "quý", "giaf" to "già",
            "gif" to "gì", "tieeuf" to "tiều"))
        assertEquals("hòa", VietnameseTelex.normalizeTone("hoà"))
        assertEquals("thủy", VietnameseTelex.normalizeTone("thuỷ"))
        assertEquals("người", VietnameseTelex.normalizeTone("người"))
    }

    @Test fun pressingTheSameKeyAgainUndoes() {
        assertTyped(mapOf("ass" to "as", "aaa" to "aa", "uww" to "uw", "ddd" to "dd", "owo" to "ơo"))
    }

    @Test fun otherWordsAreLeftAlone() {
        // 不是越南语音节就不加符号；单独的 w 不变 ư；只认词首刚打的 dd（did 不变 đi）。
        assertTyped(mapOf("class" to "class", "facebook" to "facebook", "window" to "window", "w" to "w",
            "hello" to "hello", "email" to "email", "did" to "did", "maft" to "maft"))
        // 加过符号后接着打成了不是越南语的样子，换回按下的原样字母。
        assertTyped(mapOf("Google" to "Google", "theses" to "theses"))
    }

    @Test fun caseIsKept() {
        assertTyped(mapOf("Vieetj" to "Việt", "VIEETJ" to "VIỆT", "DDi" to "Đi", "Nguoiwf" to "Người"))
    }

    @Test fun startsAgainWhenTheWordBeforeTheCursorChanged() {
        val telex = VietnameseTelex()
        assertEquals("tô", telex.type("to", 'o'))
        // 光标移到了别的词后面：按那个词重新算。
        assertEquals("bá", telex.type("ba", 's'))
        assertEquals("bas", telex.type("bá", 's'))
    }

    @Test fun syllableCheck() {
        assertTrue(VietnameseTelex.plausible("nguo"))
        assertTrue(VietnameseTelex.plausible("tiê"))
        assertTrue(VietnameseTelex.plausible("ngh"))
        assertFalse(VietnameseTelex.plausible("cl"))
        assertFalse(VietnameseTelex.plausible("bus'"))
        // c ch p t 结尾不能是玄、问、跌声。
        assertFalse(VietnameseTelex.plausible("màt"))
        assertTrue(VietnameseTelex.plausible("mát"))
    }
}
