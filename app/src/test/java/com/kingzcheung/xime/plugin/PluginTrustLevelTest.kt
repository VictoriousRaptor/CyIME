package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import com.kingzcheung.xime.plugin.core.util.PluginSignatureUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/** 插件管理里的「官方 / 第三方」：内置和自家市场装的是官方，自己导入的文件是第三方（11-09）。 */
class PluginTrustLevelTest {
    @Test fun `内置和市场装的显示官方`() {
        listOf(PluginSource.SYSTEM, PluginSource.ASSET, PluginSource.REMOTE).forEach {
            assertEquals(it.name, TrustLevel.TRUSTED, PluginSignatureUtil.classifyLuaPlugin(it))
        }
    }

    @Test fun `自己导入的文件显示第三方`() {
        assertEquals(TrustLevel.THIRD_PARTY, PluginSignatureUtil.classifyLuaPlugin(PluginSource.FILE))
    }
}
