package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.settings.JapanesePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 日语词库和转换引擎的状态（「语言与方案」的日语一行、日语插件页）：
 * 缺的写出大小和「下载」，下载中写进度，失败写原因和「重试」，下好了正在解开写「正在准备」。
 * 都好了不占地方；[showReady] 时（插件页）写一句已装好。
 */
@Composable
internal fun JapanesePackStatus(modifier: Modifier = Modifier, showReady: Boolean = false) {
    val context = LocalContext.current
    val states by ModelManager.downloadStates.collectAsState()
    val installed by ModelManager.installedRevision.collectAsState()
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var missing by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    LaunchedEffect(installed) {
        while (true) {
            val (nowReady, nowMissing) = withContext(Dispatchers.IO) { JapanesePack.ready(context) to JapanesePack.missing(context) }
            ready = nowReady; missing = nowMissing
            // 词库下好后在后台解开，解完不会通知：没有缺的、又还没好时隔一秒再看。
            if (nowReady || nowMissing.isNotEmpty()) break
            delay(1000)
        }
    }
    val isReady = ready ?: return
    val downloading = missing.firstNotNullOfOrNull { states[it.id] as? ModelDownloadState.Downloading }
    val failed = missing.firstNotNullOfOrNull { states[it.id] as? ModelDownloadState.Error }
    if (isReady && !showReady) return
    Column(modifier.testTag("japanese-pack-status")) {
        val text = when {
            isReady -> "日语词库和转换引擎已装好。日语在「语言与方案」里开关。"
            downloading != null -> if (downloading.progress > 0f) "正在下载日语词库 ${(downloading.progress * 100).toInt()}%…" else "正在下载日语词库…"
            failed != null -> "日语词库下载失败：${failed.message}"
            missing.isEmpty() -> "正在准备日语词库…"
            else -> "日语要先下载词库和转换引擎（约 ${formatModelMegabytes(JapanesePack.downloadBytes(missing))}），下好前只显示罗马字。"
        }
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (failed != null && downloading == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isReady && downloading == null && missing.isNotEmpty()) {
            TextButton(onClick = { JapanesePack.request(context) }) { Text(if (failed != null) "重试" else "下载") }
        }
    }
}
