package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.DoublePinyinSchemes
import com.kingzcheung.xime.settings.CustomKeyboardLayouts
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 韵母按键上的写法：v 写作 ü（lü、lüe），与常见双拼键位图、键盘上的双拼键位提示一致。 */
private fun finalLabel(final: String) = com.kingzcheung.xime.ui.keyboard.doublePinyinFinalLabel(final)

/** 两个设置入口共用同一项偏好，并响应另一处或备份恢复带来的更改。 */
@Composable
internal fun DoublePinyinRawPreeditToggle() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(context) { mutableStateOf(!SpellingPatches.doublePinyinRawPreedit(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SpellingPatches.DOUBLE_PINYIN_RAW_PREEDIT_KEY) {
                enabled = !SpellingPatches.doublePinyinRawPreedit(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsToggleItem(Icons.TwoTone.Keyboard, "双拼编码显示全拼",
        "开启后显示全拼（如 shuang pin），关闭时显示双拼键（如 ul pb）。改完要几秒重新部署",
        checked = enabled, onCheckedChange = { on ->
            SpellingPatches.setDoublePinyinRawPreedit(context, !on)
            enabled = on
            scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
        })
}

/** 双拼方案：内置的小鹤、首道、自然码等可复制修改；自定义方案改键位后保存。符号与中文26键（14 键版本与中文14键）相同，字母排列随所选字母布局。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DoublePinyinSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var schemes by remember { mutableStateOf(DoublePinyinScheme.BUILTINS) }
    var enabled by remember { mutableStateOf(emptySet<String>()) }
    var draft by remember { mutableStateOf<DoublePinyinScheme?>(null) }
    var original by remember { mutableStateOf<DoublePinyinScheme?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun edit(scheme: DoublePinyinScheme) { draft = scheme; original = scheme; picked = null; message = "" }
    fun copyOf(scheme: DoublePinyinScheme): DoublePinyinScheme {
        val names = schemes.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "我的双拼" else "我的双拼 $it" }.first { it !in names }
        return DoublePinyinScheme.fresh(scheme, name)
    }
    fun back() { if (draft != null && draft != original) discard = true else if (draft != null) draft = null else onBack() }
    BackHandler { back() }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { DoublePinyinSchemes.all(context) } }
            .onSuccess { schemes = it }.onFailure { message = "无法读取双拼方案：${it.message}" }
        enabled = withContext(Dispatchers.IO) { SchemaManager.getEnabledSchemas(context).toSet() }
    }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != CustomKeyboardLayouts.STATUS) return@OnSharedPreferenceChangeListener
            val status = prefs.getString(key, "").orEmpty()
            message = if (status.startsWith("已保存，但应用失败")) status
                else if (status.startsWith("布局已应用")) "已应用，可在「语言与方案」的方案一栏选择" else status
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    KeyboardSettingsScaffold(if (draft == null) "双拼方案" else "编辑双拼方案", { if (!busy) back() },
        preview = draft == null, example = com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.DoublePinyin,
        modifier = Modifier.testTag("double-pinyin-settings")) { modifier ->
        Column(modifier.then(if (draft != null) Modifier.imePadding() else Modifier).settingsVerticalScroll()
            .padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val current = draft
            val keyboardPrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
            if (current == null) {
                SettingsJumpText("在「语言与方案」的方案一栏切换双拼。内置方案可以复制后修改键位，保存为自己的方案；" +
                    "符号和词库与中文26键相同。在「语言与方案」点 QWJRTK、Colemak 或自己的字母布局，双拼也按那个排列打；" +
                    "启用了中文14键或自己合并过按键的布局(如自己排的20键)时，在方案栏显示的双拼方案在那个布局上也能用：点合并键打键上第一个字母，长按选另一个字母。",
                    jumps = listOf("语言与方案"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 双拼键位提示 11-01 搬到「主题与外观 › 角标与气泡」，和上下角标放一起；这里只指路。
                SettingsNote("键盘上的双拼键位提示在「主题与外观 › 角标与气泡」", jumps = listOf("主题与外观 › 角标与气泡"))
                // 双拼简拼（别人提的需求，用户 11-02 转述）：默认关，所有双拼 26 键方案共用；改完在后台写拼写补丁再重新部署。
                var abbreviation by remember { mutableStateOf(SpellingPatches.doublePinyinAbbreviation(context)) }
                Column(Modifier.settingsSearchAnchor("双拼简拼")) {
                    SettingsSection(title = "通用") {
                        SettingsToggleItem(Icons.TwoTone.ContentCut, "双拼简拼",
                            "26 键只打声母也出词，如 wxzf 出“微信支付”；zh、ch、sh 也可打 z、c、s。改完要几秒重新部署",
                            checked = abbreviation, onCheckedChange = { on ->
                                SpellingPatches.setDoublePinyinAbbreviation(context, on)
                                abbreviation = on
                                scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                            }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DOUBLE_PINYIN_ABBREVIATION)
                        DoublePinyinAbbreviationLegend(abbreviation)
                        DoublePinyinRawPreeditToggle()
                    }
                }
                Button(onClick = { edit(copyOf(DoublePinyinScheme.FLYPY)) }, enabled = !busy,
                    modifier = Modifier.testTag("double-pinyin-new")) { Text("新建双拼方案") }
                schemes.forEach { scheme ->
                    val custom = DoublePinyinSchemes.isCustom(scheme.id)
                    SettingsSection(title = scheme.name) {
                        Column(Modifier.padding(16.dp).testTag("double-pinyin:${scheme.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text((if (custom) "自定义" else "内置") + " · “双拼”打作 ${scheme.preview()}" +
                                scheme.collisions().size.let { if (it > 0) " · $it 组同码" else "" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DoublePinyinKeyMap(scheme)
                            // 九键没有分号键，ing 在分号上的方案（微软双拼）没有九键版本。
                            listOfNotNull(Triple(scheme.id, "在方案栏显示", "打开后可在「语言与方案」里选它"),
                                Triple(DoublePinyinScheme.t9Id(scheme.id), "九键版本", "用九键打${scheme.name}：每个字按两个双拼键所在的数字键")
                                    .takeUnless { scheme.usesSemicolon }
                            ).forEach { (id, title, subtitle) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.bodyLarge)
                                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(id in enabled, enabled = !busy, colors = settingsSwitchColors(), modifier = Modifier.testTag("double-pinyin-enabled:$id"),
                                        onCheckedChange = { on ->
                                            busy = true
                                            scope.launch {
                                                runCatching { withContext(Dispatchers.IO) {
                                                    DoublePinyinSchemes.setEnabled(context, id, on)
                                                    SchemaManager.getEnabledSchemas(context).toSet()
                                                } }.onSuccess { enabled = it; message = if (on) "已打开，正在应用" else "已从方案栏隐藏" }
                                                    .onFailure { message = "设置失败：${it.message}" }
                                                busy = false
                                            }
                                        })
                                }
                            }
                            // ing 在分号上的方案（微软双拼）：ing 改放 Shift 键，多数手机输入法这样做；所有这类方案共用这一个开关。
                            // 和「Shift 键分词」互锁：打字时都要占 Shift 键（InputGestureLocks）。
                            val ingLock = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(keyboardPrefs, com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.SEMICOLON_ON_SHIFT, true)
                                ?.takeIf { !keyboardPrefs.doublePinyinSemicolonOnShift }
                            if (scheme.usesSemicolon) Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("ing 放在 Shift 键上", style = MaterialTheme.typography.bodyLarge)
                                    Text("不加分号键，L 在第二排最右边；打了声母后左下角的 Shift 键变成 ing",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (ingLock != null) SettingsLockNote(ingLock.message)
                                }
                                Switch(keyboardPrefs.doublePinyinSemicolonOnShift, enabled = !busy && ingLock == null, colors = settingsSwitchColors(),
                                    modifier = Modifier.testTag("double-pinyin-semicolon-on-shift:${scheme.id}"),
                                    onCheckedChange = { keyboardPrefs.copy(doublePinyinSemicolonOnShift = it).save(context) })
                            }
                            if (scheme.usesSemicolon) SemicolonOnShiftLegend(keyboardPrefs.doublePinyinSemicolonOnShift)
                            Row {
                                if (custom) TextButton(onClick = { edit(scheme) }, enabled = !busy) { Text("编辑") }
                                TextButton(onClick = { edit(copyOf(scheme)) }, enabled = !busy) { Text(if (custom) "复制" else "复制修改") }
                                if (custom) TextButton(onClick = { deleteId = scheme.id }, enabled = !busy) { Text("删除") }
                            }
                        }
                    }
                }
            } else {
                SettingsSection(title = "方案名称") {
                    OutlinedTextField(value = current.name, onValueChange = { draft = current.copy(name = it.take(32)) },
                        label = { Text("方案名称") }, singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("double-pinyin-name"))
                }
                SettingsSection(title = "键位") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("先点下面的声母或韵母，再点键盘上的键，把它放到那个键上。", style = MaterialTheme.typography.bodySmall)
                        DoublePinyinKeyMap(current, highlight = picked?.let { current.initials[it] ?: current.finals[it] }) { key ->
                            val item = picked ?: return@DoublePinyinKeyMap
                            draft = if (item in DoublePinyinScheme.SPECIAL_INITIALS) current.copy(initials = current.initials + (item to key))
                                else current.copy(finals = current.finals + (item to key))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            (DoublePinyinScheme.SPECIAL_INITIALS + DoublePinyinScheme.FINALS).forEach { item ->
                                val key = current.initials[item] ?: current.finals[item]
                                FilterChip(selected = picked == item, onClick = { picked = if (picked == item) null else item },
                                    enabled = !busy, modifier = Modifier.testTag("double-pinyin-item:$item"),
                                    label = { Text("${finalLabel(item)} · ${key?.uppercaseChar() ?: "?"}") })
                            }
                        }
                        Text("zh、ch、sh 以外的声母就在本字母键上；ue 是 jue、yue 的韵母，üe 是 lüe、nüe 的韵母。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsSection(title = "零声母音节") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("没有声母的音节，每个打两个字母。", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DoublePinyinScheme.ZERO_SYLLABLES.forEach { syllable ->
                                OutlinedTextField(value = current.zero[syllable].orEmpty(), enabled = !busy, singleLine = true,
                                    onValueChange = { text ->
                                        val code = text.lowercase().filter { it in 'a'..'z' }.take(2)
                                        draft = current.copy(zero = current.zero + (syllable to code))
                                    },
                                    label = { Text(syllable) }, modifier = Modifier.width(96.dp).testTag("double-pinyin-zero:$syllable"),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                            }
                        }
                    }
                }
                val problems = current.copy(name = current.name.trim()).problems()
                val collisions = current.collisions()
                if (problems.isNotEmpty() || collisions.isNotEmpty()) SettingsSection(title = "检查") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        if (collisions.isNotEmpty()) Text("同码音节(由词库区分候选)：" + collisions.entries.joinToString("；") { (code, syllables) ->
                            "${code.uppercase()} = ${syllables.joinToString(" / ")}" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(modifier = Modifier.fillMaxWidth().testTag("double-pinyin-save"), enabled = !busy && problems.isEmpty(), onClick = {
                    busy = true; message = "正在保存…"
                    val saving = current.copy(name = current.name.trim())
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { DoublePinyinSchemes.save(context, saving); DoublePinyinSchemes.all(context) } }
                            .onSuccess { schemes = it; draft = null; enabled = enabled + saving.id
                                message = "已保存，正在应用；之后可在「语言与方案」的方案一栏选择" }
                            .onFailure { message = "保存失败：${it.message}" }
                        busy = false
                    }
                }) { Text(if (busy) "保存中…" else "保存方案") }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { discard = false; draft = null }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    deleteId?.let { target -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除这个双拼方案？") },
        text = { Text("只删除键位方案，不删除词库和学习记录。正在使用它时会切回小鹤双拼。") },
        confirmButton = { TextButton(onClick = {
            deleteId = null; busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { DoublePinyinSchemes.delete(context, target); DoublePinyinSchemes.all(context) } }
                    .onSuccess { schemes = it; message = "已删除" }.onFailure { message = "删除失败：${it.message}" }
                busy = false
            }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }) }
}

/** 26 键键位图：键上是字母、翘舌声母（强调色）和韵母；[onKey] 为空时只显示。 */
@Composable
internal fun DoublePinyinKeyMap(scheme: DoublePinyinScheme, highlight: Char? = null, onKey: ((Char) -> Unit)? = null) {
    val labels = scheme.keyLabels()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 3.dp
        val slot = (maxWidth - gap * 9) / 10
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            // ing 在分号上的方案第二排多一个分号键，和键盘上一样排满十格。
            listOf("qwertyuiop", if (';' in labels) "asdfghjkl;" else "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
                Row(Modifier.padding(start = (slot + gap) * (if (letters.length == 10) 0f else row * 0.5f)),
                    horizontalArrangement = Arrangement.spacedBy(gap)) {
                    letters.forEach { key ->
                        val (initial, finals) = labels.getValue(key)
                        val chosen = key == highlight
                        Surface(shape = RoundedCornerShape(7.dp),
                            color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(if (chosen) 2.dp else 1.dp,
                                if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(slot).height(60.dp)
                                .semantics { contentDescription = "${key.uppercaseChar()}键：" +
                                    listOfNotNull(initial).plus(finals.map(::finalLabel)).joinToString("、") }
                                .then(if (onKey != null) Modifier.clickable { onKey(key) }.testTag("double-pinyin-key:$key") else Modifier)) {
                            Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(key.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    if (initial != null) Text(initial, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 1.dp), maxLines = 1)
                                }
                                Text(finals.joinToString("\n", transform = ::finalLabel), fontSize = 9.sp, lineHeight = 10.sp,
                                    textAlign = TextAlign.Center, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
