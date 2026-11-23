package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.plugin.core.lua.OfflineTranslateHostApi
import com.kingzcheung.xime.plugin.core.lua.OfflineTranslateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻译插件设置页「下载离线模型」下面每个模型的一行：进度就在这页看，不再指去「扩展 › 模型」。 */
class OfflineTranslateModelRowsTest {
    private fun status(state: String, size: Int = 52) = OfflineTranslateStatus(state, sizeMb = size)

    @Test fun downloadingShowsPercentEvenBeforeStatusIsKnown() {
        assertEquals("中译英：下载中 37%", translateModelLine("中译英", ModelDownloadState.Downloading(0.374f, 1, 2), null))
    }

    @Test fun eachStateHasItsOwnLine() {
        assertNull(translateModelLine("中译英", null, null))
        assertEquals("中译英：已下载，可以离线翻译",
            translateModelLine("中译英", ModelDownloadState.Complete, status(OfflineTranslateHostApi.STATE_READY)))
        assertEquals("中译英：没下载（约 52 MB）", translateModelLine("中译英", null, status(OfflineTranslateHostApi.STATE_MISSING)))
        assertEquals("中译英：这台手机用不了离线翻译",
            translateModelLine("中译英", null, status(OfflineTranslateHostApi.STATE_UNSUPPORTED)))
        assertEquals("英译中：下载失败（断网），再点上面的按钮重试",
            translateModelLine("英译中", ModelDownloadState.Error("断网"), status(OfflineTranslateHostApi.STATE_MISSING)))
    }

    @Test fun noLineSendsTheUserToAnotherPage() {
        val lines = listOf(
            translateModelLine("中译英", ModelDownloadState.Downloading(0.5f, 1, 2), null),
            translateModelLine("中译英", null, status(OfflineTranslateHostApi.STATE_MISSING)),
            translateModelLine("中译英", ModelDownloadState.Error("x"), status(OfflineTranslateHostApi.STATE_MISSING)),
        )
        lines.forEach { assertFalse(it!!.contains("扩展")) }
    }

    @Test fun onlyTheTranslatePluginsDownloadButtonGetsRows() {
        assertTrue(pluginButtonShowsTranslateModels(TRANSLATE_PLUGIN_ID, "downloadOffline"))
        assertFalse(pluginButtonShowsTranslateModels(TRANSLATE_PLUGIN_ID, "testConnection"))
        assertFalse(pluginButtonShowsTranslateModels("com.kingzcheung.xime.plugin.ai_rewrite", "downloadOffline"))
    }
}
