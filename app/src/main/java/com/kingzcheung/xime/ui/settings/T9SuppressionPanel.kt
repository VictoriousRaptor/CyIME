package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 旧版本长按删除会把整条候选永久屏蔽（九键 t9-suppressed 列表、26 键 cyime-hidden 列表）。
 * 现在长按删除只忘掉学习记录；这里只剩下恢复以前屏蔽过的候选。
 */
@Composable
internal fun T9SuppressionPanel() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // word -> true 为九键列表，false 为 26 键列表
    var words by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        try {
            words = withContext(Dispatchers.IO) {
                check(RimeConfigHelper.prepareEngine(context))
                val engine = RimeEngine.getInstance()
                engine.getSuppressedCandidates().map { it to true } + engine.getHiddenCandidates().map { it to false }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "暂时读不到，请稍后再试"
        } finally { busy = false }
    }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.settingsGutterScroll(listState).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp)) } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
        if (!busy && words.isEmpty() && error == null) item { DictionaryEmptyLine("没有删掉的候选") }
        itemsIndexed(words, key = { _, (word, t9) -> (if (t9) "t9:" else "26:") + word }) { row, (word, t9) ->
            SettingsGroupRow(row, words.size) { SettingsListRow(word, if (t9) "九键" else "26 键") {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val restored = withContext(Dispatchers.IO) {
                                val engine = RimeEngine.getInstance()
                                if (t9) engine.restoreSuppressedCandidate(word) else engine.restoreHiddenCandidate(word)
                            }
                            if (restored) { words = words - (word to t9); error = null }
                            else error = "恢复失败，请重试"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "恢复失败，请重试"
                        } finally { busy = false }
                    }
                }) { Text("放回") }
            } }
        }
    }
}
