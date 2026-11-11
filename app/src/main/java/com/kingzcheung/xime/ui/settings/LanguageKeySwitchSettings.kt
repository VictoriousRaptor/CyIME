package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputMode
import com.kingzcheung.xime.settings.LanguageSwitchMode
import com.kingzcheung.xime.settings.LanguageSwitchOptions
import com.kingzcheung.xime.settings.LanguageSwitchPreferences
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SchemaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LanguageKeySwitchSettings(
    schemas: List<SchemaInfo>,
    languageOrder: List<InputLanguage>,
    currentSchema: String,
) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(LanguageSwitchPreferences.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        options = LanguageSwitchPreferences.read(context)
    }
    // 列出所有非英文语言（原来写死只有中文、日语，西班牙语等选不到）；此刻能选的只是已启用且方案已准备好的。
    val choices = LanguageSwitchPreferences.specificChoices(languageOrder)
    val nativeLanguages by produceState<List<InputLanguage>>(emptyList(), context, schemas, languageOrder) {
        value = withContext(Dispatchers.IO) {
            val available = schemas.filter {
                it.profile.mode == InputMode.KEYBOARD && SchemaManager.isSchemaCompiled(context, it.schemaId)
            }.map { it.profile.language }.toSet()
            choices.filter { it in available && it in languageOrder }
        }
    }
    fun save(value: LanguageSwitchOptions) {
        options = value
        LanguageSwitchPreferences.save(context, value)
    }
    // 用户 11-09：有了「两种语言互切」，「当前语言与英文」「指定语言与英文」都只是它的特例，合成一项；
    // 另一项是按顺序循环。存的模式值不变（旧版本的设置照旧生效），默认仍是「当前语言 ↔ 英文」。
    val pairMode = options.mode != LanguageSwitchMode.CYCLE
    // 第一种：null 是「当前语言」（原来的默认）；存的语言没开或方案没准备好时同样按当前语言切。
    val pairFirst = options.language?.takeIf { options.mode != LanguageSwitchMode.CURRENT_ENGLISH && it in nativeLanguages }
    val pairPartner = options.partner?.takeIf {
        options.mode == LanguageSwitchMode.SPECIFIC_PAIR && pairFirst != null && it != pairFirst && it in nativeLanguages
    } ?: InputLanguage.ENGLISH
    // 语言一多，选语言那几列很长（用户 11-05）：默认收起，标题下一行写当前选的；设置搜索点中时自动展开。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val searchRequest = SettingsSearchFocus.request
    LaunchedEffect(searchRequest) {
        if (searchRequest != null && "语言键切换方式" in searchRequest.keys) expanded = true
    }
    val summary = if (!pairMode) "按已启用语言顺序循环切换"
        else "${pairFirst?.displayName ?: "当前语言"} ↔ ${pairPartner.displayName}"
    Surface(modifier = Modifier.settingsSearchAnchor("语言键切换方式").testTag("language-key-switch-settings"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)
            .testTag("settings-fold:语言键切换方式"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("语言键切换方式", style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起语言键切换方式" else "展开语言键切换方式")
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("屏幕键盘和物理浮条共用此设置；点按语言键切换，长按打开语言列表。物理键盘的 Shift 和 Ctrl + 空格快捷键保持各自功能。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                SwitchModeRow("pair", "两种语言互切(默认)", pairMode) {
                    if (!pairMode) save(LanguageSwitchPreferences.pair(pairFirst, pairPartner))
                }
                SwitchModeRow(LanguageSwitchMode.CYCLE.id, "按已启用语言顺序循环切换", !pairMode) {
                    save(options.copy(mode = LanguageSwitchMode.CYCLE))
                }
            }
            if (pairMode) {
                Text("第一种", style = MaterialTheme.typography.labelLarge)
                // 只列已开启、方案准备好的语言；「当前语言」就是原来的默认，跟着正在用的语言走。
                LanguagePairChoices("first", listOf<InputLanguage?>(null) + nativeLanguages, pairFirst) {
                    save(LanguageSwitchPreferences.pair(it, pairPartner))
                }
                Text("另一种", style = MaterialTheme.typography.labelLarge)
                // 第一种是「当前语言」时只能和英文互切：当前语言就是另一种时没法再切。
                LanguagePairChoices("partner", listOf<InputLanguage?>(InputLanguage.ENGLISH) +
                    (if (pairFirst == null) emptyList() else nativeLanguages.filter { it != pairFirst }), pairPartner) {
                    save(LanguageSwitchPreferences.pair(pairFirst, it))
                }
                if (nativeLanguages.isEmpty()) Text("别的语言要先在上面打开，等它的输入方案准备好后才能选。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
}

@Composable
private fun SwitchModeRow(tag: String, title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("language-switch-mode:$tag")
        .selectable(selected, role = Role.RadioButton, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** 「两种语言互切」的一列语言单选；null 显示成「当前语言」。 */
@Composable
private fun LanguagePairChoices(tag: String, languages: List<InputLanguage?>, selected: InputLanguage?,
    onSelect: (InputLanguage?) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (language in languages) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("language-switch-pair-$tag:${language?.id ?: "current"}")
                .selectable(selected == language, role = Role.RadioButton) { onSelect(language) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == language, onClick = null)
                Text(language?.displayName ?: "当前语言", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
