package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.gloss.CandidateGlossary
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager

/**
 * 释义词典未下载时的提示行：直接在这里下载并显示进度，不用去扩展商店里找。
 * [startRequest] 每次递增都会开始下载（用户刚选“释义”时由调用方触发）。
 */
@Composable
internal fun GlossDictionaryStatus(startRequest: Int) {
    val context = LocalContext.current
    val states by ModelManager.downloadStates.collectAsState()
    val state = states[CandidateGlossary.MODEL_ID]
    var installed by remember { mutableStateOf(CandidateGlossary.isInstalled(context)) }
    val start = {
        ModelManager.getModel(CandidateGlossary.MODEL_ID)?.let { ModelManager.downloadModelInBackground(context, it) }
        Unit
    }
    LaunchedEffect(state) {
        if (state is ModelDownloadState.Complete) {
            installed = CandidateGlossary.isInstalled(context)
            CandidateGlossary.prepare(context)
        }
    }
    LaunchedEffect(startRequest) {
        if (startRequest > 0 && !installed && state !is ModelDownloadState.Downloading) start()
    }
    if (installed) return

    val size = ModelManager.getModel(CandidateGlossary.MODEL_ID)?.resolvedVersion()?.size.orEmpty()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).testTag("gloss-dictionary-status"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (state) {
                is ModelDownloadState.Downloading ->
                    if (state.progress > 0f) "正在下载释义词典 ${(state.progress * 100).toInt()}%…" else "正在下载释义词典…"
                is ModelDownloadState.Error -> "释义词典下载失败：${state.message}"
                else -> "释义词典还没下载(约 $size)，现在仍显示拼音。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ModelDownloadState.Downloading) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (state !is ModelDownloadState.Downloading) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = start, modifier = Modifier.testTag("gloss-dictionary-download")) {
                Text(if (state is ModelDownloadState.Error) "重试" else "下载")
            }
        }
    }
}
