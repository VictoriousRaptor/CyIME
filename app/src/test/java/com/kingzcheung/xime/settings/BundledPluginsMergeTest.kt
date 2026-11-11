package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 线上目录（国内先读魔搭）没同步时，随包插件照样列出、装随包的新版（11-09 用户找不到翻译插件）。 */
class BundledPluginsMergeTest {
    private fun plugin(name: String, vararg versions: String) = MarketPlugin(
        id = "com.kingzcheung.xime.plugin.$name", name = name, currentVersion = versions.first(),
        versions = versions.map { PluginVersion(version = it) },
    )

    private val bundled = PluginsDirectIndex(plugins = listOf(
        plugin("ai_reply", "0.7.0", "0.6.0"), plugin("translate", "0.2.0", "0.1.0"), plugin("caption", "0.1.0"),
    ))

    @Test fun `线上缺的随包插件补在后面`() {
        val remote = PluginsDirectIndex(plugins = listOf(plugin("ai_reply", "0.7.0")))
        val merged = XimeIndexSource.withBundledPlugins(remote, bundled)
        assertEquals(listOf("ai_reply", "translate", "caption"), merged.plugins.map { it.name })
        // 线上已有且不比随包旧：用线上那条。
        assertEquals(listOf("0.7.0"), merged.plugins[0].versions.map { it.version })
    }

    @Test fun `随包版本更新时用随包那条，线上独有的旧版本留着`() {
        val remote = PluginsDirectIndex(plugins = listOf(plugin("translate", "0.1.0", "0.0.9")))
        val translate = XimeIndexSource.withBundledPlugins(remote, bundled).plugins.first { it.name == "translate" }
        assertEquals("0.2.0", translate.currentVersion)
        assertEquals(listOf("0.2.0", "0.1.0", "0.0.9"), translate.versions.map { it.version })
    }

    @Test fun `线上版本更新时不被随包旧版顶掉`() {
        val remote = PluginsDirectIndex(plugins = listOf(plugin("translate", "0.3.0")))
        val translate = XimeIndexSource.withBundledPlugins(remote, bundled).plugins.first { it.name == "translate" }
        assertEquals("0.3.0", translate.currentVersion)
        assertEquals(listOf("0.3.0"), translate.versions.map { it.version })
    }
}
