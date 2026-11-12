package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 市场隐藏已内置、已合并的插件；AI 改写和 AI 回复照常列出（11-03）。 */
class RetiredPluginsTest {
    private fun id(name: String) = "com.kingzcheung.xime.plugin.$name"

    @Test fun `内置和合并掉的插件不再列出`() {
        listOf("online_asr", "typing_stats", "ai_translate", "ai_write", "kaomoji", "webdav_backup").forEach {
            assertTrue(it, id(it) in XimeIndexSource.RETIRED_PLUGIN_IDS)
        }
    }

    @Test fun `AI 改写和 AI 回复还在市场里`() {
        assertFalse(id("ai_rewrite") in XimeIndexSource.RETIRED_PLUGIN_IDS)
        assertFalse(id("ai_reply") in XimeIndexSource.RETIRED_PLUGIN_IDS)
    }

    @Test fun `已下架的插件不自动往键盘菜单里放按钮`() {
        assertFalse(XimeIndexSource.mayAutoPlaceButtons(id("ai_translate")))
        assertFalse(XimeIndexSource.mayAutoPlaceButtons(id("ai_write")))
        assertTrue(XimeIndexSource.mayAutoPlaceButtons(id("ai_rewrite")))
        assertTrue(XimeIndexSource.mayAutoPlaceButtons(id("ai_reply")))
    }

    @Test fun `老 AI 翻译是已下架插件，新的翻译插件不是`() {
        assertTrue(XimeIndexSource.isRetiredPlugin(id("ai_translate")))
        assertTrue(XimeIndexSource.isRetiredPlugin("ai_translate"))
        assertFalse(XimeIndexSource.isRetiredPlugin(id("translate")))
        assertFalse(XimeIndexSource.isRetiredPlugin(id("caption")))
    }
}
