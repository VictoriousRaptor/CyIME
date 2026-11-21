package com.kingzcheung.xime.settings

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 11-20：「设置里切换 不能直接切换主界面吗」——设置页选了正在用的语言的方案/布局，开着的键盘马上换。 */
class ImeSchemaRequestsTest {
    @Test fun keyboardSwitchesOnlyWhenItShowsSomethingElse() {
        // 设置页选了自然码 14 键，键盘还是 26 键全拼：换。
        assertTrue(ImeSchemaRequests.needsSwitch("double_pinyin_ziranma_14jian", "pinyin", englishNow = false))
        // 已经是它：不换，不清掉正在打的字。
        assertFalse(ImeSchemaRequests.needsSwitch("pinyin", "pinyin", englishNow = false))
        // 正在打英文：只存，不把键盘拉回中文。
        assertFalse(ImeSchemaRequests.needsSwitch("double_pinyin_ziranma_14jian", "pinyin", englishNow = true))
        assertFalse(ImeSchemaRequests.needsSwitch("", "pinyin", englishNow = false))
    }

    @Test fun pickReachesTheKeyboard() = runBlocking {
        val got = async { ImeSchemaRequests.picked.first() }
        yield()
        ImeSchemaRequests.pick("double_pinyin_ziranma_14jian")
        assertEquals("double_pinyin_ziranma_14jian", got.await())
    }
}
