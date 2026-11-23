package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.plugin.core.lua.OfflineTranslateHostApi
import com.kingzcheung.xime.plugin.core.lua.OfflineTranslateStatus
import com.kingzcheung.xime.translate.OfflineTranslation
import com.kingzcheung.xime.ui.i18n.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 翻译插件设置页「下载离线模型」按钮下面：中译英、英译中各一行，下载中带进度条。
 * 以前点了按钮只说「进度在『扩展 › 模型』」，要跳去别的页面看（用户 11-22）。
 * 在翻译栏里点「下载」开始的下载，打开这一页也看得到进度。
 */
@Composable
internal fun OfflineTranslateModelRows() {
    val context = LocalContext.current
    val downloads by ModelManager.downloadStates.collectAsState()
    var statuses by remember { mutableStateOf<Map<String, OfflineTranslateStatus>>(emptyMap()) }
    // 下载开始、结束时重查一次文件在不在；下载中的百分比直接看 downloads，不用每次都查文件
    val settled = downloads.filterValues { it !is ModelDownloadState.Downloading }.keys +
        downloads.filterValues { it is ModelDownloadState.Downloading }.keys.map { "$it:downloading" }
    LaunchedEffect(settled) {
        statuses = withContext(Dispatchers.IO) {
            OfflineTranslation.directions.associate { it.modelId to OfflineTranslation.status(context, it.from, it.to) }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("translate-model-rows"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OfflineTranslation.directions.forEach { direction ->
            val id = direction.modelId
            val name = ModelManager.getModel(id)?.name?.substringAfter(" · ") ?: id
            val live = downloads[id]
            val status = statuses[id]
            val line = translateModelLine(name, live, status)
            if (line != null) {
                Text(line, style = MaterialTheme.typography.bodyMedium,
                    color = if (live is ModelDownloadState.Error && status?.state != OfflineTranslateHostApi.STATE_READY)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("translate-model:$id"))
            }
            if (live is ModelDownloadState.Downloading) {
                LinearProgressIndicator(progress = { live.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 一个模型的一行字；还没查到状态时 null。 */
internal fun translateModelLine(name: String, live: ModelDownloadState?, status: OfflineTranslateStatus?): String? = when {
    live is ModelDownloadState.Downloading -> "$name：下载中 ${(live.progress * 100).toInt().coerceIn(0, 100)}%"
    status == null -> null
    status.state == OfflineTranslateHostApi.STATE_READY -> "$name：已下载，可以离线翻译"
    status.state == OfflineTranslateHostApi.STATE_UNSUPPORTED -> "$name：这台手机用不了离线翻译"
    live is ModelDownloadState.Error -> "$name：下载失败（${live.message}），再点上面的按钮重试"
    else -> "$name：没下载" + if (status.sizeMb > 0) "（约 ${status.sizeMb} MB）" else ""
}
