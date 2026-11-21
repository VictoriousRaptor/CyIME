package com.kingzcheung.xime.glide

import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.InputProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #303：导入的自定义编码方案（万象拼音自然码 26 键等）选了编码才滑行，没选的照旧不滑行；内置方案不受影响。 */
class ExternalGlideEncodingTest {
    private val imported = "wanxiang_ziranma_test"

    @Test fun importedSchemeGlidesOnlyWithAChosenEncoding() {
        val profile = InputProfiles.describe(imported)
        assertNull(chineseGlideEncoding(profile, imported))
        assertNull(chineseGlideEncoding(profile, imported, mapOf("other" to ExternalGlideEncodings.PINYIN)))
        val pinyin = chineseGlideEncoding(profile, imported, mapOf(imported to ExternalGlideEncodings.PINYIN))
        assertNotNull(pinyin); assertNull(pinyin!!.doublePinyin)
        val ziranma = chineseGlideEncoding(profile, imported, mapOf(imported to DoublePinyinScheme.ZIRANMA.id))
        assertSame(DoublePinyinScheme.ZIRANMA, ziranma?.doublePinyin)
        // 选的双拼方案已经删了：不滑行，不按全拼猜。
        assertNull(chineseGlideEncoding(profile, imported, mapOf(imported to "double_pinyin_custom_gone")))
    }

    @Test fun builtInSchemesAreUnchanged() {
        val anything = mapOf("rime_ice" to DoublePinyinScheme.ZIRANMA.id, "double_pinyin_flypy" to ExternalGlideEncodings.PINYIN)
        for (external in listOf(emptyMap(), anything)) {
            assertNull(chineseGlideEncoding(InputProfiles.describe("rime_ice"), "rime_ice", external)!!.doublePinyin)
            assertSame(DoublePinyinScheme.FLYPY,
                chineseGlideEncoding(InputProfiles.describe("double_pinyin_flypy"), "double_pinyin_flypy", external)!!.doublePinyin)
            assertNull(chineseGlideEncoding(InputProfiles.describe("t9_pinyin"), "t9_pinyin", external))
        }
    }

    @Test fun choicesRoundTrip() {
        var map = ExternalGlideEncodings.with(emptyMap(), "b", ExternalGlideEncodings.PINYIN)
        map = ExternalGlideEncodings.with(map, "a", "double_pinyin_flypy")
        assertEquals(map, ExternalGlideEncodings.parse(ExternalGlideEncodings.format(map)))
        assertEquals(mapOf("a" to "double_pinyin_flypy"), ExternalGlideEncodings.with(map, "b", null))
        assertEquals(emptyMap<String, String>(), ExternalGlideEncodings.parse(""))
        assertEquals(emptyMap<String, String>(), ExternalGlideEncodings.parse(null))
        assertEquals(mapOf("a" to "x"), ExternalGlideEncodings.parse("a\tx\n\tbad\nnotab\nc\t"))
    }
}
