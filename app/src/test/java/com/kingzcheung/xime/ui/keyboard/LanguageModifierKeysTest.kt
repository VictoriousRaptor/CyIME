package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.kingzcheung.xime.settings.LetterLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 11-17：俄语键盘上 Shift 拖到字母、按住 !@# 当 Ctrl 都没用（以前只认 a–z）。
 * 各语言键盘的字母键和 26 键一样：Shift 拖过去打大写；按住 !@# 拖过去或另一根手指点它发 Ctrl+字母，
 * 拉丁字母认字母本身，俄、韩语按位置取 26 键同一格的字母（和电脑上一样）。
 */
class LanguageModifierKeysTest {
    private val russian = listOf(
        listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
        listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
        listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю"),
    )
    private val korean = listOf(
        listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ"),
        listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"),
        listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ"),
    )
    private val azerty = listOf("azertyuiop", "qsdfghjklm", "wxcvbn").map { row -> row.map(Char::toString) }

    @Test fun `russian and korean keys send the ctrl letter of the same cell`() {
        assertEquals('c', LetterLanguage.ctrlLetter('с', russian))
        assertEquals('c', LetterLanguage.ctrlLetter('С', russian))
        assertEquals('v', LetterLanguage.ctrlLetter('м', russian))
        assertEquals('a', LetterLanguage.ctrlLetter('ф', russian))
        assertEquals('z', LetterLanguage.ctrlLetter('я', russian))
        assertEquals('q', LetterLanguage.ctrlLetter('й', russian))
        assertEquals('p', LetterLanguage.ctrlLetter('з', russian))
        assertEquals('m', LetterLanguage.ctrlLetter('ь', russian))
        // 超出 26 键那一格的没有：х ж э б ю。
        for (key in "хжэбю") assertNull("$key", LetterLanguage.ctrlLetter(key, russian))
        assertEquals('c', LetterLanguage.ctrlLetter('ㅊ', korean))
        assertEquals('v', LetterLanguage.ctrlLetter('ㅍ', korean))
        assertEquals('z', LetterLanguage.ctrlLetter('ㅋ', korean))
        assertEquals('a', LetterLanguage.ctrlLetter('ㅁ', korean))
        assertEquals('q', LetterLanguage.ctrlLetter('ㅂ', korean))
    }

    @Test fun `latin keyboards keep the letter itself and accents have no ctrl letter`() {
        // AZERTY 的 a 在 q 那一格，照样是 Ctrl+A（电脑上的法语键盘也是认字母）。
        assertEquals('a', LetterLanguage.ctrlLetter('a', azerty))
        assertEquals('q', LetterLanguage.ctrlLetter('Q', azerty))
        assertEquals('w', LetterLanguage.ctrlLetter('w', azerty))
        for (key in "ñäöüßéèàçÑ") assertNull("$key", LetterLanguage.ctrlLetter(key, azerty))
        assertNull(LetterLanguage.ctrlLetter('!', russian))
        assertNull(LetterLanguage.ctrlLetter('中', russian))
    }

    @Test fun `key letters cover every language keyboard but not symbols or kana`() {
        for (key in "aZйЙㅂㄲñÄßé") assertTrue("$key", LetterLanguage.isKeyLetter(key))
        for (key in "!,'1あ中") assertFalse("$key", LetterLanguage.isKeyLetter(key))
    }

    private val keyboard = Rect(0f, 0f, 400f, 280f)
    private val shift = Rect(0f, 160f, 60f, 220f)
    private val symbol = Rect(0f, 220f, 60f, 280f)
    private val c = Rect(100f, 160f, 140f, 220f)
    private val ha = Rect(355f, 10f, 400f, 75f)
    private val commits = mutableListOf<String>()
    private fun russianTargets() = ShiftSlideTargets(ctrlLetterOf = { LetterLanguage.ctrlLetter(it, russian) }).apply {
        keyboardBounds = keyboard
        put("с", "с", c, commits::add)
        put("х", "х", ha, commits::add)
    }

    @Test fun `shift drag onto a russian or korean key commits that key shifted`() {
        val targets = russianTargets()
        targets.move(shift, c.center)!!.commit()
        targets.move(shift, ha.center)!!.commit()
        targets.put("ㄱ", "ㄱ", Rect(200f, 10f, 240f, 75f), commits::add)
        targets.move(shift, Offset(220f, 40f))!!.commit()
        targets.put("ñ", "ñ", Rect(250f, 10f, 290f, 75f), commits::add)
        targets.move(shift, Offset(270f, 40f))!!.commit()
        // 键盘自己的大写交给 Shift（按着 Shift 送出）：ㄱ 由组字换成 ㄲ。
        assertEquals(listOf("С", "Х", "ㄱ", "Ñ"), commits)
    }

    @Test fun `ctrl drag and ctrl tap on a russian key send the letter of its cell`() {
        val targets = russianTargets()
        val sent = mutableListOf<Char>()
        targets.beginCtrl { sent += it }
        val target = targets.move(symbol, c.center)
        assertEquals('c', target?.ctrl)
        // х 那一格超出 26 键：按住 !@# 时不是目标，松手什么都不发。
        assertNull(targets.move(symbol, ha.center))
        assertTrue(targets.ctrlTap("с"))
        assertTrue(targets.ctrlTap("С"))
        assertFalse(targets.ctrlTap("х"))
        assertEquals(listOf('c', 'c'), sent)
        assertEquals('c', targets.ctrlLetter('с'))
        assertNull(targets.ctrlLetter('х'))
        targets.finish()
        assertFalse("松开 !@# 后点字母照常打字", targets.ctrlTap("с"))
        assertTrue(commits.isEmpty())
    }

    @Test fun `default targets still only send latin ctrl letters`() {
        val targets = ShiftSlideTargets().apply { keyboardBounds = keyboard }
        targets.put("q", "q", c) { }
        targets.put("ñ", "ñ", ha) { }
        targets.beginCtrl { }
        assertEquals('q', targets.move(symbol, c.center)?.ctrl)
        assertNull(targets.move(symbol, ha.center))
        assertFalse(targets.ctrlTap("ñ"))
        targets.finish()
    }

    @Test fun `enter target sends ctrl enter`() {
        val targets = russianTargets()
        val enter = Rect(330f, 220f, 400f, 280f)
        targets.putEnter("enter", enter)
        targets.beginCtrl { }
        assertEquals(com.kingzcheung.xime.keyboard.CtrlShortcuts.ENTER, targets.move(symbol, enter.center)?.ctrl)
        targets.finish()
    }
}
