package com.kingzcheung.xime.service

import com.kingzcheung.xime.model.ModelDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻译栏开着时盯模型下载：什么算进度变了、什么算下完了（下完栏里有字就接着译）。 */
class LiveBarDownloadProgressTest {
    @Test fun signatureKeepsPercentDoneAndFailedOnly() {
        val signature = modelDownloadSignature(mapOf(
            "translate-zh-en" to ModelDownloadState.Downloading(0.374f, 10, 100),
            "translate-en-zh" to ModelDownloadState.Complete,
            "sensevoice" to ModelDownloadState.Error("断网"),
            "paraformer" to ModelDownloadState.Idle,
        ))
        assertEquals(mapOf("translate-zh-en" to 37, "translate-en-zh" to MODEL_DOWNLOAD_DONE,
            "sensevoice" to MODEL_DOWNLOAD_FAILED), signature)
    }

    @Test fun progressOutOfRangeIsClamped() {
        assertEquals(100, modelDownloadSignature(mapOf("a" to ModelDownloadState.Downloading(1.7f, 0, -1)))["a"])
        assertEquals(0, modelDownloadSignature(mapOf("a" to ModelDownloadState.Downloading(-0.2f, 0, -1)))["a"])
    }

    @Test fun finishedMeansWasDownloadingAndNowDone() {
        val before = mapOf("zh-en" to 99, "en-zh" to 40, "old" to MODEL_DOWNLOAD_DONE)
        val now = mapOf("zh-en" to MODEL_DOWNLOAD_DONE, "en-zh" to 41, "old" to MODEL_DOWNLOAD_DONE)
        assertEquals(setOf("zh-en"), finishedDownloads(before, now))
    }

    @Test fun failedOrStillRunningIsNotFinished() {
        assertTrue(finishedDownloads(mapOf("a" to 50), mapOf("a" to MODEL_DOWNLOAD_FAILED)).isEmpty())
        assertTrue(finishedDownloads(mapOf("a" to 50), mapOf("a" to 60)).isEmpty())
        // 一开栏就已经下好的不算刚下完
        assertTrue(finishedDownloads(mapOf("a" to MODEL_DOWNLOAD_DONE), mapOf("a" to MODEL_DOWNLOAD_DONE)).isEmpty())
    }

    @Test fun barProgressLineAveragesOnlyTranslateModelsStillDownloading() {
        val ids = setOf("translate-zh-en", "translate-en-zh")
        assertEquals(0.5f, downloadingProgress(mapOf("translate-zh-en" to 40, "translate-en-zh" to 60), ids)!!, 0.001f)
        // 一个下完了：只看还在下的那个
        assertEquals(0.3f, downloadingProgress(mapOf("translate-zh-en" to MODEL_DOWNLOAD_DONE, "translate-en-zh" to 30), ids)!!, 0.001f)
        // 别的模型在下载不画
        assertNull(downloadingProgress(mapOf("sensevoice" to 50), ids))
        assertNull(downloadingProgress(mapOf("translate-zh-en" to MODEL_DOWNLOAD_FAILED), ids))
    }
}
