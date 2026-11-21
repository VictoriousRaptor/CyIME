package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.kingzcheung.xime.association.WordCompletion
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.settings.LetterLanguage

/**
 * 补全词表未下载时的提示行（同释义词典的 [GlossDictionaryStatus]）：开启语言时已在后台下载，这里显示进度，
 * 失败了可以重试。下好后不显示。
 */
@Composable
internal fun WordListStatus(language: LetterLanguage, modifier: Modifier = Modifier) {
    val model = language.wordsModel ?: return
    val context = LocalContext.current
    val states by ModelManager.downloadStates.collectAsState()
    val state = states[model]
    var installed by remember(language) { mutableStateOf(WordCompletion.isInstalled(context, language)) }
    LaunchedEffect(state) {
        if (state is ModelDownloadState.Complete) installed = WordCompletion.isInstalled(context, language)
    }
    if (installed) return
    val size = ModelManager.getModel(model)?.resolvedVersion()?.size.orEmpty()
    Row(modifier.fillMaxWidth().testTag("word-list-status:${language.language.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (state) {
                is ModelDownloadState.Downloading ->
                    if (state.progress > 0f) "正在下载补全词表 ${(state.progress * 100).toInt()}%…" else "正在下载补全词表…"
                is ModelDownloadState.Error -> "补全词表下载失败：${state.message}"
                else -> "补全词表还没下载(约 $size)，暂时没有单词补全。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ModelDownloadState.Downloading) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (state !is ModelDownloadState.Downloading) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { WordCompletion.download(context, language) },
                modifier = Modifier.testTag("word-list-download:${language.language.id}")) {
                Text(if (state is ModelDownloadState.Error) "重试" else "下载")
            }
        }
    }
}
