package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPluginUpdateTest {
    @Test fun onlyAnOlderInstalledPluginIsUpgraded() {
        // 11-15: 装着翻译 0.2.0，安装包里是新版，栏上的语言选择看不到。
        assertTrue(XimeIndexSource.bundledIsNewer("0.2.0", "0.4.0"))
        assertTrue(XimeIndexSource.bundledIsNewer("0.3.0", "0.10.0"))
        assertFalse(XimeIndexSource.bundledIsNewer("0.4.0", "0.4.0"))
        assertFalse("自己装的更新的版本不降级", XimeIndexSource.bundledIsNewer("0.5.0", "0.4.0"))
    }
}
