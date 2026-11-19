package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.DictionaryHit
import com.kingzcheung.xime.settings.DictionaryLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查词：一个词在内置词库、导入的词库、我的短语里有没有，各是什么编码（学会的词不在其中）。 */
@Composable
internal fun DictionaryLookupPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var word by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, List<DictionaryHit>>?>(null) }
    fun search() {
        val target = word.trim()
        if (target.isEmpty() || searching) return
        searching = true
        scope.launch {
            val hits = withContext(Dispatchers.IO) { runCatching { DictionaryLookup.find(context, target) }.getOrDefault(emptyList()) }
            result = target to hits
            searching = false
        }
    }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.settingsGutterScroll(listState).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = word, onValueChange = { word = it }, placeholder = { Text("输入要查的词") },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }), modifier = Modifier.weight(1f))
                TextButton(onClick = ::search, enabled = word.isNotBlank() && !searching,
                    modifier = Modifier.padding(start = 8.dp)) { Text("查") }
            }
        }
        if (searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
        result?.let { (target, hits) ->
            if (hits.isEmpty()) item { DictionaryEmptyLine("词库里没有“$target”") }
            itemsIndexed(hits) { row, hit ->
                SettingsGroupRow(row, hits.size) { SettingsListRow(hit.source, hit.codes.joinToString("；")) }
            }
        }
    }
}
