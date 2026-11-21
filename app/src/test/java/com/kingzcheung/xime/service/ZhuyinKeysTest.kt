package com.kingzcheung.xime.service

import android.view.KeyEvent
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputProfiles
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.LayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 注音（大千）：键盘的注音键帽、ImeKeyRouter 送的大千键码和 cyime_zhuyin 方案三者对得上。 */
class ZhuyinKeysTest {
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }

    private val schema by lazy { repoFile("src/main/assets/rime_chinese/cyime_zhuyin.schema.yaml").readText() }

    /** 方案 preedit_format 的 xlit：大千键码 → 屏上显示的注音符号。 */
    private fun preeditMap(): Map<Char, Char> {
        val (source, target) = Regex("""xlit\|([^|]+)\|([^|]+)\|""").find(schema.substringAfter("preedit_format:"))!!.destructured
        assertEquals(source.length, target.length)
        return source.zip(target).toMap()
    }

    @Test fun `every key cap types a code of the schema that shows the same symbol`() {
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(repoFile("src/main/assets/xime.yaml").readText(), "zhuyin")
            ?: error("xime.yaml 缺少 zhuyin rows")
        assertEquals(listOf(11, 10, 10, 10), rows.map { it.size })
        val alphabet = Regex("""alphabet: "([^"]+)"""").find(schema)!!.groupValues[1]
        val shown = preeditMap()
        val keys = rows.flatten()
        assertEquals("37 个注音符号和 4 个声调各一个键", 41, keys.toSet().size)
        for (key in keys) {
            val code = ZhuyinKeys.code(key) ?: error("$key has no 大千 code")
            assertTrue("$key → $code not in the speller alphabet", code in alphabet)
            assertEquals("$key → $code", key.single(), shown[code])
        }
        // 第一声是空格
        assertEquals('ˉ', shown[' '])
    }

    @Test fun `a tone only follows a syllable`() {
        assertFalse(ZhuyinKeys.takesTone(""))
        assertTrue(ZhuyinKeys.takesTone("su"))
        assertFalse(ZhuyinKeys.takesTone("su3"))
        assertFalse(ZhuyinKeys.takesTone("su "))
        assertFalse(ZhuyinKeys.takesTone("su'"))
        assertTrue(ZhuyinKeys.isTone(ZhuyinKeys.code("ˇ")!!))
        assertFalse(ZhuyinKeys.isTone(ZhuyinKeys.code("ㄅ")!!))
        assertNull(ZhuyinKeys.code("a"))
        assertNull(ZhuyinKeys.code("，"))
    }

    @Test fun `cyime_zhuyin is the Chinese zhuyin scheme on the 大千 layout`() {
        val profile = InputProfiles.describe("cyime_zhuyin")
        assertEquals(InputLanguage.CHINESE, profile.language)
        assertEquals(InputScheme.ZHUYIN, profile.scheme)
        assertEquals(InputLayout.ZHUYIN, profile.layout)
        assertFalse("注音没有拼音编辑", profile.capabilities.editablePinyin)
        assertEquals(LayoutKind.ZHUYIN, InputProfiles.resolveLayout("zhuyin").kind)
    }

    /** 注音用户 11-20：实体键盘打不出标点。, . 是 ㄝ ㄡ，标点在 Shift 上，Shift+, 是「，」（同 Rime 注音）。 */
    @Test fun `shifted punctuation goes to the schema punctuator`() {
        fun punct(key: String, code: Int, shift: Boolean = true, ctrl: Boolean = false) =
            isZhuyinHardwarePunctuation(key, code, shift, ctrl, alt = false, meta = false)
        assertTrue(punct("<", KeyEvent.KEYCODE_COMMA))
        assertTrue(punct(">", KeyEvent.KEYCODE_PERIOD))
        assertTrue(punct("?", KeyEvent.KEYCODE_SLASH))
        assertTrue(punct(":", KeyEvent.KEYCODE_SEMICOLON))
        assertTrue(punct("!", KeyEvent.KEYCODE_1))
        assertFalse("不按 Shift 的 , 是 ㄝ", punct(",", KeyEvent.KEYCODE_COMMA, shift = false))
        assertFalse("大写字母不是标点", punct("A", KeyEvent.KEYCODE_A))
        assertFalse("Ctrl 组合键交给应用", punct("<", KeyEvent.KEYCODE_COMMA, ctrl = true))
        assertFalse("数字小键盘照旧", punct("*", KeyEvent.KEYCODE_NUMPAD_MULTIPLY))
        // , . - 不翻页（是注音键或 Shift 标点），[ ] = 照旧翻页。
        assertFalse(zhuyinKeepsPagingKey(KeyEvent.KEYCODE_COMMA))
        assertFalse(zhuyinKeepsPagingKey(KeyEvent.KEYCODE_PERIOD))
        assertFalse(zhuyinKeepsPagingKey(KeyEvent.KEYCODE_MINUS))
        assertTrue(zhuyinKeepsPagingKey(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertTrue(zhuyinKeepsPagingKey(KeyEvent.KEYCODE_EQUALS))
        val punctuator = schema.substringAfter("\npunctuator:").substringBefore("\nkey_binder:")
        for (shape in listOf("half_shape", "full_shape")) {
            assertTrue(shape, punctuator.contains("$shape/<: { commit: \"，\" }"))
            assertTrue(shape, punctuator.contains("$shape/>: { commit: \"。\" }"))
        }
    }

    @Test fun `enter commits the bopomofo on screen, not the key codes`() {
        assertTrue(usesMergedLetterKeys(InputLayout.ZHUYIN))
        assertEquals("ㄋㄧˇㄏㄠˇ", rawCompositionCommitText("su3cl3", "ㄋㄧˇ ㄏㄠˇ", t9 = false,
            merged14 = usesMergedLetterKeys(InputLayout.ZHUYIN)))
    }

    /** 实体键盘照大千键位打（用户 11-18 jp4wu = ㄨㄣˋㄊㄧ 問題）：数字行和 , . / ; - 都是方案里的注音键码，字母不在这一组。 */
    @Test fun `hardware symbol keys are all zhuyin codes of the schema`() {
        val preedit = preeditMap()
        for (c in "1234567890,./;-") {
            assertTrue("$c", ZhuyinKeys.isHardwareSymbolCode(c.toString()))
            assertTrue("方案要认 $c", c in preedit)
        }
        assertTrue(ZhuyinKeys.isTone('4'))
        assertFalse(ZhuyinKeys.isHardwareSymbolCode("j"))
        assertFalse(ZhuyinKeys.isHardwareSymbolCode("="))
        assertFalse(ZhuyinKeys.isHardwareSymbolCode("44"))
    }

    /** 用户 11-18：轻声 ˙ 是修饰符号，以前被当标点，打 ㄉㄜ 再按 ˙ 把「ㄉㄜ˙」原样上屏。每个注音键帽都要进按键队列，不按标点上屏。 */
    @Test fun `every zhuyin key cap goes to the engine, not the punctuation path`() {
        val zhuyin = InputProfiles.describe("cyime_zhuyin")
        assertTrue(isLiteralPunctuation("˙"))
        assertTrue(zhuyinKeyIsCode("˙", false, zhuyin))
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(repoFile("src/main/assets/xime.yaml").readText(), "zhuyin")!!
        for (key in rows.flatten()) assertTrue(key, zhuyinKeyIsCode(key, false, zhuyin))
        assertFalse(zhuyinKeyIsCode("˙", true, zhuyin))
        assertFalse(zhuyinKeyIsCode("˙", false, InputProfiles.describe("rime_ice")))
        assertFalse(zhuyinKeyIsCode("，", false, zhuyin))
    }
}
