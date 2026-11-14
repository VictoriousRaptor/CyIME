package com.kingzcheung.xime.rime

import com.kingzcheung.xime.service.selectableWithStored
import com.kingzcheung.xime.settings.InputModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub #340：设置里关掉（删掉）正在用的小鹤双拼，键盘还一直用着它，布局栏画不出来，重启也一样。
 * 原因是停用的方案 build 里的编译文件还在，输入法只看「编译过」就照样选它；布局栏只列启用的方案。
 */
class RemovedSchemaSelectionTest {
    private val enabled = listOf("rime_ice", "t9_pinyin", "cyime_k14_english", "japanese")

    @Test
    fun `停用的当前方案换到同语言还开着的方案`() {
        assertEquals("rime_ice", RimeConfigHelper.replacementForDisabled("double_pinyin_flypy", enabled, handwriting = false))
        assertEquals("japanese", RimeConfigHelper.replacementForDisabled("double_pinyin_flypy", listOf("cyime_k14_english", "japanese"),
            handwriting = false))
    }

    @Test
    fun `还开着的方案、手写、英文不换`() {
        assertNull(RimeConfigHelper.replacementForDisabled("t9_pinyin", enabled, handwriting = false))
        assertNull(RimeConfigHelper.replacementForDisabled("handwriting_zh", enabled, handwriting = true))
        assertNull(RimeConfigHelper.replacementForDisabled(InputModes.ENGLISH, enabled, handwriting = false))
        assertNull(RimeConfigHelper.replacementForDisabled("", enabled, handwriting = false))
        // 读不到启用列表时不动，免得误换。
        assertNull(RimeConfigHelper.replacementForDisabled("double_pinyin_flypy", emptyList(), handwriting = false))
    }

    @Test
    fun `编译过但已停用的方案不再可选`() {
        val available = listOf("rime_ice", "t9_pinyin")
        val selectable = selectableWithStored("double_pinyin_flypy", available, { enabled }, { false }, { true })
        assertEquals(available, selectable)
    }

    @Test
    fun `刚启用还没部署完的方案照旧可选`() {
        val available = listOf("rime_ice", "t9_pinyin")
        val selectable = selectableWithStored("double_pinyin_flypy", available,
            { enabled + "double_pinyin_flypy" }, { false }, { true })
        assertEquals(available + "double_pinyin_flypy", selectable)
        // 没编译出来的不加。
        assertEquals(available, selectableWithStored("double_pinyin_flypy", available,
            { enabled + "double_pinyin_flypy" }, { false }, { false }))
    }

    @Test
    fun `手写方案照旧可选，英文不加`() {
        val available = listOf("rime_ice")
        assertEquals(available + "handwriting_zh",
            selectableWithStored("handwriting_zh", available, { enabled }, { it == "handwriting_zh" }, { false }))
        assertEquals(available, selectableWithStored(InputModes.ENGLISH, available, { enabled }, { false }, { true }))
    }
}
