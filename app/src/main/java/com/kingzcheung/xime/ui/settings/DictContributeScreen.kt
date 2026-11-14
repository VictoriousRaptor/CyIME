package com.kingzcheung.xime.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.DictContribution
import com.kingzcheung.xime.settings.DictContributionRules
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DictContribute"

/**
 * 词库 › 贡献：列出 [DictContribution.load] 挑出的词，用户取消不想给的，再分享文件或上传。
 * 什么都不会自动发出去；第一次分享或上传前要先同意用途。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictContributeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var loaded by remember { mutableStateOf<DictContribution.Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var unchecked by remember { mutableStateOf(emptySet<DictContributionRules.Word>()) }
    var consentFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var excluded by remember { mutableStateOf(DictContribution.excluded(app)) }

    LaunchedEffect(app) {
        try {
            loaded = withContext(Dispatchers.IO) { DictContribution.load(app) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            FileLogger.e(TAG, "load failed", e)
            error = "暂时读不到词库，打开一次键盘再来"
        } finally { busy = false }
    }

    val allWords = loaded?.selection?.words.orEmpty()
    val words = allWords.filter { DictContribution.excludedKey(it) !in excluded }
    val deletedCount = allWords.size - words.size
    val picked = words.filter { it !in unchecked }
    fun withConsent(action: () -> Unit) {
        if (DictContribution.consented(app)) action() else consentFor = action
    }
    fun writePicked() = DictContribution.writeFile(app, picked, loaded?.schema.orEmpty(), BuildConfig.VERSION_NAME)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("贡献词库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (words.isNotEmpty()) Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(enabled = !busy && picked.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                    withConsent {
                        busy = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { writePicked() }
                                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    clipData = android.content.ClipData.newRawUri(null, uri)
                                }
                                context.startActivity(Intent.createChooser(intent, "分享词库"))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                FileLogger.e(TAG, "share failed", e)
                                snackbarHostState.showSnackbar("分享失败：${e.message ?: "未知错误"}")
                            } finally { busy = false }
                        }
                    }
                }) { Text("分享") }
                if (DictContribution.uploadAvailable()) OutlinedButton(enabled = !busy && picked.isNotEmpty(),
                    modifier = Modifier.weight(1f), onClick = {
                        withConsent {
                            busy = true
                            scope.launch {
                                val message = try {
                                    withContext(Dispatchers.IO) {
                                        DictContribution.upload(app, writePicked(), BuildConfig.VERSION_NAME)
                                    }
                                    "已上传 ${picked.size} 个词"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    FileLogger.e(TAG, "upload failed", e)
                                    "上传失败：${e.message ?: "未知错误"}"
                                } finally { busy = false }
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    }) { Text("上传") }
            }
        }
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LazyColumn(state = listState,
            modifier = Modifier.settingsGutterScroll(listState).fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                SettingsNote("只列出你选过 ${DictContributionRules.MIN_COUNT} 次以上的 2～${DictContributionRules.MAX_CHARS} 字词和次数。" +
                    "整句、带数字字母的、像人名的、你自己加的短语和个人词库都不在里面。" +
                    "不想给的词取消勾选，或点右边删掉，以后不再列出（不会删你自己的词库）。")
            }
            if (busy && loaded == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp)) } }
            loaded?.let { result ->
                if (words.isEmpty()) item { DictionaryEmptyLine(if (deletedCount > 0) "剩下的词都删掉了" else "还没有可贡献的词") }
                else item {
                    SettingsGroupTitle("已选 ${picked.size} / ${words.size} 个词，去掉 ${result.selection.dropped} 条")
                }
            }
            itemsIndexed(words, key = { _, w -> w.text + "\t" + w.pinyin }) { row, word ->
                val checked = word !in unchecked
                fun toggle() { unchecked = if (checked) unchecked + word else unchecked - word }
                SettingsGroupRow(row, words.size) {
                    SettingsListRow(word.text, listOf(word.pinyin, "${word.count} 次").filter { it.isNotEmpty() }.joinToString(" · "),
                        Modifier.clickable(enabled = !busy) { toggle() }) {
                        Checkbox(checked = checked, enabled = !busy, onCheckedChange = { toggle() })
                        IconButton(enabled = !busy, onClick = {
                            excluded = DictContribution.exclude(app, word)
                            unchecked = unchecked - word
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删掉${word.text}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (deletedCount > 0) item {
                TextButton(enabled = !busy, modifier = Modifier.padding(top = 4.dp), onClick = {
                    DictContribution.clearExcluded(app)
                    excluded = emptySet()
                }) { Text("恢复删掉的 $deletedCount 个词") }
            }
        }
    }

    consentFor?.let { pending ->
        AlertDialog(
            onDismissRequest = { consentFor = null },
            title = { Text("词的用途") },
            text = { Text(DictContribution.CONSENT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    DictContribution.setConsented(app)
                    consentFor = null
                    pending()
                }) { Text("同意") }
            },
            dismissButton = { TextButton(onClick = { consentFor = null }) { Text("取消") } },
        )
    }
}
