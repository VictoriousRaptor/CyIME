package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.*
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.speech.SpeechModelSelection
import com.kingzcheung.xime.speech.SpeechLanguages
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 每种语言一组：「说话时」用哪个模型出字，「说完后」怎么定稿（2026-11-09 重排）。
 * 原来的「中文定稿 X-ASR」「语言 → 主模型」「二次校正」三组互相牵连，看不出谁在用、怎么切；
 * 现在一条语言一条流程，选项只是原有存储值的组合，能做的事和默认值都不变。
 */
@Composable
internal fun OfflineModelCard() {
    val context = LocalContext.current
    val manager = remember { AsrModelManager(context) }
    val models by ModelManager.modelsFlow.collectAsState()
    val downloads by ModelManager.downloadStates.collectAsState()
    val selected = remember { mutableStateMapOf<InputLanguage, String>().apply {
        InputLanguage.speech.forEach { put(it, manager.getSelectedModelId(it)) }
    } }
    var editing by remember { mutableStateOf<InputLanguage?>(null) }
    var ready by remember { mutableStateOf(emptySet<String>()) }
    var xasrReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ModelManager.loadFromRemote(context) } }
    LaunchedEffect(models, downloads) {
        ready = withContext(Dispatchers.IO) {
            SpeechModelSelection.primaryIds.filter { runCatching { manager.selection(it).ready }.getOrDefault(false) }.toSet()
        }
        xasrReady = withContext(Dispatchers.IO) { runCatching { manager.isXasrReady() }.getOrDefault(false) }
        // An unchosen SenseVoice default becomes Paraformer + SenseVoice once Paraformer is downloaded.
        InputLanguage.speech.forEach { selected[it] = withContext(Dispatchers.IO) { manager.getSelectedModelId(it) } }
    }
    fun refresh(language: InputLanguage) { selected[language] = manager.getSelectedModelId(language) }
    fun download(id: String) { ModelManager.getModel(id)?.let { ModelManager.downloadModelInBackground(context, it) } }
    val senseReady = SpeechModelCatalog.SENSEVOICE in ready
    var wholeSentence by remember { mutableStateOf(SettingsPreferences.isSttWholeSentence(context)) }
    var wholeFinalizer by remember { mutableStateOf(SettingsPreferences.getSttWholeFinalizer(context)) }
    // 没开日语不列「日语语音」（用户 11-22）；存的日语模型选择不动，开了日语照旧。
    val shownLanguages = remember { InputLanguage.speech.filter {
        it != InputLanguage.JAPANESE || com.kingzcheung.xime.settings.LanguagePreferences.japaneseOn(context) } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        shownLanguages.forEachIndexed { index, language ->
            SettingsSection(title = "${language.displayName}语音") {
                val content = if (index == shownLanguages.lastIndex) Modifier.testTag("speech-correction") else Modifier
                Column(content.fillMaxWidth()) {
                    SpeechPipeline(language, selected.getValue(language), ready, senseReady, xasrReady, wholeSentence,
                        wholeFinalizer, downloads, onPick = { editing = language }, onDownload = ::download,
                        onFinish = { finish, finalizer ->
                            finalizer?.let { wholeFinalizer = it; SettingsPreferences.setSttWholeFinalizer(context, it) }
                            val (mode, whole) = SpeechModelSelection.applyFinish(selected.getValue(language),
                                chinese = language == InputLanguage.CHINESE, finish = finish)
                            manager.setModel(mode, language); refresh(language)
                            whole?.let { wholeSentence = it; SettingsPreferences.setSttWholeSentence(context, it) }
                        })
                }
            }
        }
        Text("下次说话时生效。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { language ->
        SpeechModelPicker(language, SpeechModelSelection.primary(selected.getValue(language)), ready, downloads,
            onSelect = { manager.setFirstPassModel(it, language); refresh(language); editing = null },
            onDownload = ::download, onClose = { editing = null })
    }
}

/** 一种语言的完整流程：说话时（点开换模型）→ 说完后（单选定稿方式）。 */
@Composable
private fun SpeechPipeline(language: InputLanguage, mode: String, ready: Set<String>, senseReady: Boolean,
    xasrReady: Boolean, wholeSentence: Boolean, wholeFinalizer: String, downloads: Map<String, ModelDownloadState>,
    onPick: () -> Unit, onDownload: (String) -> Unit, onFinish: (SpeechModelSelection.Finish, String?) -> Unit) {
    val chinese = language == InputLanguage.CHINESE
    val primary = SpeechModelSelection.primary(mode)
    val senseVoiceAlone = primary == SpeechModelCatalog.SENSEVOICE
    val divider = @Composable { HorizontalDivider(Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)) }
    Row(Modifier.fillMaxWidth().testTag("speech-language:${language.id}").clickable(onClick = onPick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("说话时：${SpeechModelSelection.displayName(primary).orEmpty()}", style = MaterialTheme.typography.bodyLarge)
            Text(speechModelTiming(primary) + if (primary in ready) "" else " (未下载)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "换${language.displayName}说话时用的模型")
    }
    divider()
    val options = SpeechModelSelection.finishOptions(mode, chinese)
    if (options.size == 1) {
        // 没得选时也写出来，整条流程一眼看全。
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("说完后：不再校正", style = MaterialTheme.typography.bodyLarge)
            Text(if (senseVoiceAlone) "SenseVoice 停顿出的字就是定稿" else "${language.displayName}没有二次校正",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val current = SpeechModelSelection.finish(mode, chinese, wholeSentence)
    Text("说完后", style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    // 整句定稿：X-ASR 下载了就分成两项（SenseVoice / X-ASR），各人按自己实际说话的效果选（2026-11-22）。
    val wholeModels = if (xasrReady) listOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.XASR) else listOf(SpeechModelCatalog.SENSEVOICE)
    val rows = options.flatMap { finish ->
        if (finish == SpeechModelSelection.Finish.WHOLE) wholeModels.map { finish to it } else listOf(finish to null)
    }
    val usedFinalizer = if (xasrReady) wholeFinalizer else SpeechModelCatalog.SENSEVOICE
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        rows.forEach { (finish, finalizer) ->
            val (title, caption) = when (finish) {
                SpeechModelSelection.Finish.NONE -> "不校正" to
                    (if (senseVoiceAlone) "每次停顿由 SenseVoice 直接出字" else "直接用说话时出的字")
                SpeechModelSelection.Finish.SENSEVOICE -> "SenseVoice 校正" to
                    ("每次停顿时再认一遍" + if (senseReady) "" else " (需下载 SenseVoice)")
                SpeechModelSelection.Finish.WHOLE -> if (finalizer == SpeechModelCatalog.XASR)
                    "整句定稿 · X-ASR" to "说完后用 X-ASR 整句再认一遍" else
                    "整句定稿 · SenseVoice" to ("说完后整句再认一遍，更准但稍慢" +
                        if (xasrReady) "" else "; 也可下载 X-ASR 对比")
            }
            val checked = finish == current && (finalizer == null || finalizer == usedFinalizer)
            val enabled = checked || when (finish) {
                SpeechModelSelection.Finish.NONE -> true
                SpeechModelSelection.Finish.SENSEVOICE -> senseReady
                SpeechModelSelection.Finish.WHOLE -> senseVoiceAlone || senseReady
            }
            val anchor = if (finish == SpeechModelSelection.Finish.WHOLE && finalizer == SpeechModelCatalog.SENSEVOICE)
                Modifier.settingsSearchAnchor("整句定稿") else Modifier
            val tag = "speech-finish:${language.id}:${finish.name.lowercase()}" +
                if (finalizer == SpeechModelCatalog.XASR) "-xasr" else ""
            Row(Modifier.fillMaxWidth().then(anchor).testTag(tag)
                .selectable(checked, enabled = enabled, role = Role.RadioButton, onClick = { onFinish(finish, finalizer) })
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(checked, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // 下载按钮放在用到它的语言下面：SenseVoice（中英校正、中文整句的前提），X-ASR（只有中文）。
    val needsSense = !senseReady && SpeechModelSelection.Finish.SENSEVOICE in options
    val needsXasr = chinese && !xasrReady && SpeechModelSelection.Finish.WHOLE in options
    if (needsSense || needsXasr) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (needsSense) ModelDownloadAction(SpeechModelCatalog.SENSEVOICE, downloads) { onDownload(SpeechModelCatalog.SENSEVOICE) }
        if (needsXasr) ModelDownloadAction(SpeechModelCatalog.XASR, downloads) { onDownload(SpeechModelCatalog.XASR) }
    }
    Spacer(Modifier.height(8.dp))
}

/** 说话时出字的方式和大小，括号用 ASCII。 */
internal fun speechModelTiming(id: String): String = when (id) {
    SpeechModelCatalog.PARAFORMER -> "边说边出字 (226 MB)"
    SpeechModelCatalog.SENSEVOICE -> "停顿后才出字 (228 MB)"
    SpeechModelCatalog.ZIPFORMER -> "边说边出字 (133 MB)"
    SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "说话中先出预览 (153 MB)"
    SpeechModelCatalog.ZIPFORMER_JA -> "说话中先出预览 (586 MB)"
    else -> speechModelFacts(id)
}

@Composable
internal fun SpeechModelPicker(language: InputLanguage, selected: String, ready: Set<String>,
    downloads: Map<String, ModelDownloadState>, onSelect: (String) -> Unit, onDownload: (String) -> Unit,
    onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("${language.displayName}说话时用的模型") },
        text = {
            SpeechModelChoices(language, selected, ready, downloads, onSelect, onDownload)
        }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}

@Composable
internal fun SpeechModelChoices(language: InputLanguage, selected: String, ready: Set<String>,
    downloads: Map<String, ModelDownloadState>, onSelect: (String) -> Unit, onDownload: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
        SpeechModelSelection.offeredIds(selected).filter { SpeechLanguages.supports(it, language) }
            .forEachIndexed { index, id ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                SpeechModelChoice(language, id, selected == id, id in ready, downloads[id],
                    onSelect = { onSelect(id) }, onDownload = { onDownload(id) })
            }
    }
}

@Composable
private fun SpeechModelChoice(language: InputLanguage, id: String, checked: Boolean, ready: Boolean,
    state: ModelDownloadState?, onSelect: () -> Unit, onDownload: () -> Unit) {
    val name = SpeechModelSelection.displayName(id).orEmpty()
    val progress = (state as? ModelDownloadState.Downloading)?.progress?.coerceIn(0f, 1f)
    val error = state as? ModelDownloadState.Error
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).testTag("speech-choice:${language.id}:$id")
            .selectable(checked, enabled = ready, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(checked, onClick = null, enabled = ready)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(speechModelFacts(id).replace(" MB", "\u00A0MB"), style = MaterialTheme.typography.bodySmall)
                val status = when {
                    ready -> "已下载"
                    progress != null -> "下载中 ${(progress * 100).toInt()}%"
                    error != null -> error.message
                    else -> "未下载"
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color =
                    if (error != null && !ready) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!ready) {
            if (progress != null) {
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                val action = if (error != null) "重试" else "下载"
                TextButton(onClick = onDownload, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("speech-download:$id").semantics { contentDescription = "$action$name" },
                    contentPadding = PaddingValues(horizontal = 6.dp)) { Text(action) }
            }
        }
    }
}

/** Factual file sizes and output timing; do not invent cross-device latency, RAM or accuracy scores. */
internal fun speechModelFacts(id: String): String = when (id) {
    SpeechModelCatalog.PARAFORMER -> "中英 · 边说边出字 · 226 MB"
    SpeechModelCatalog.SENSEVOICE -> "中英日粤韩 · 停顿出字 · 228 MB"
    SpeechModelCatalog.ZIPFORMER -> "中文 · 边说边出字 · 133 MB"
    SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "日语 · 说话中先出预览 · 153 MB"
    SpeechModelCatalog.ZIPFORMER_JA -> "日语 · 说话中先出预览 · 586 MB"
    SpeechModelCatalog.XASR -> "中英 · 说完整句定稿 · 136 MB"
    else -> ""
}

@Composable
private fun ModelDownloadAction(id: String, downloads: Map<String, ModelDownloadState>, onDownload: () -> Unit) {
    // 一种语言下面可能并排两个下载按钮，写明是哪个模型。
    val name = if (id == SpeechModelCatalog.XASR) "X-ASR" else SpeechModelSelection.displayName(id).orEmpty().substringBefore(" · ")
    when (val state = downloads[id]) {
        is ModelDownloadState.Downloading -> {
            LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("$name 下载中 ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }
        else -> {
            TextButton(onClick = onDownload) { Text(if (state is ModelDownloadState.Error) "重试下载 $name"
                else "下载 $name (${speechModelFacts(id).substringAfterLast(" · ")})") }
            if (state is ModelDownloadState.Error) Text(state.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SpeechPunctuationSettings() {
    val context = LocalContext.current
    var autoPunctuation by remember { mutableStateOf(SettingsPreferences.isSttAutoPunctuation(context)) }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().settingsSearchAnchor("自动加标点").testTag("speech-auto-punctuation")
        .toggleable(value = autoPunctuation, role = Role.Switch,
            onValueChange = { autoPunctuation = it; SettingsPreferences.setSttAutoPunctuation(context, it) })
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("自动加标点", style = MaterialTheme.typography.bodyLarge)
            Text("按意思自动加标点。本地中文要在「说完后」选整句定稿",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = autoPunctuation, onCheckedChange = null,
            colors = settingsSwitchColors())
    }
    // 只带一部分标点：自动加标点开着时逐项一行开关，默认全开；关掉的那类按「停顿处加空格」留空格或不留。
    if (autoPunctuation) listOf(
        Triple("逗号、顿号、分号", SettingsPreferences::isSttPunctuationComma, SettingsPreferences::setSttPunctuationComma),
        Triple("句号", SettingsPreferences::isSttPunctuationPeriod, SettingsPreferences::setSttPunctuationPeriod),
        Triple("问号、感叹号", SettingsPreferences::isSttPunctuationQuestion, SettingsPreferences::setSttPunctuationQuestion),
        Triple("末尾的标点", SettingsPreferences::isSttPunctuationTrailing, SettingsPreferences::setSttPunctuationTrailing),
    ).forEach { (title, read, write) ->
        var kept by remember(title) { mutableStateOf(read(context)) }
        HorizontalDivider(modifier = Modifier.padding(start = 32.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().testTag("speech-auto-punctuation:$title")
            .toggleable(value = kept, role = Role.Switch, onValueChange = { kept = it; write(context, it) })
            .padding(start = 32.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(when {
                    title == "末尾的标点" -> if (kept) "整段话最后保留句号、问号等标点。" else "整段话最后不带标点，中间的照留；口述照样出。"
                    kept -> "保留模型打的$title。"
                    else -> "不带$title；口述照样出。"
                },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = kept, onCheckedChange = null, colors = settingsSwitchColors())
        }
    }
    var pauseSpace by remember { mutableStateOf(SettingsPreferences.isSttPauseSpace(context)) }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().settingsSearchAnchor("停顿处加空格").testTag("speech-pause-space")
        .toggleable(value = pauseSpace, role = Role.Switch,
            onValueChange = { pauseSpace = it; SettingsPreferences.setSttPauseSpace(context, it) })
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("停顿处加空格", style = MaterialTheme.typography.bodyLarge)
            Text(if (pauseSpace) "说话停顿、没加标点的地方空一格。" else "中文里不加空格；英文单词之间照常空一格。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = pauseSpace, onCheckedChange = null, colors = settingsSwitchColors())
    }
}

/** 数字转阿拉伯数字：别人提的需求，默认关(VoiceNumbers)。 */
@Composable
internal fun SpeechNumberSettings() {
    val context = LocalContext.current
    var arabic by remember { mutableStateOf(SettingsPreferences.isSttArabicNumbers(context)) }
    Row(Modifier.fillMaxWidth().settingsSearchAnchor("数字转阿拉伯数字").testTag("speech-arabic-numbers")
        .toggleable(value = arabic, role = Role.Switch,
            onValueChange = { arabic = it; SettingsPreferences.setSttArabicNumbers(context, it) })
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("数字转阿拉伯数字", style = MaterialTheme.typography.bodyLarge)
            Text("说的数字写成 325、3.5、50% 这样",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = arabic, onCheckedChange = null, colors = settingsSwitchColors())
    }
}
