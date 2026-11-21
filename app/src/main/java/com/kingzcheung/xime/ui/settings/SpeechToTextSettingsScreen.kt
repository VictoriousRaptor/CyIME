package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GraphicEq
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material.icons.twotone.RecordVoiceOver
import androidx.compose.material.icons.twotone.Computer
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.VolumeOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import com.kingzcheung.xime.speech.VoiceOtherAudio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「屏蔽外放音」一行：右边写当前选的，点开单选框（样式同首页「界面语言」）。 */
@Composable
private fun VoiceOtherAudioItem() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(SettingsPreferences.getSttOtherAudio(context)) }
    var picking by remember { mutableStateOf(false) }
    SettingsItem(icon = Icons.AutoMirrored.TwoTone.VolumeOff, title = "屏蔽外放音",
        subtitle = "说话时压低或暂停正在放的音乐、视频", status = current.label, onClick = { picking = true })
    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("屏蔽外放音") },
        text = {
            Column {
                VoiceOtherAudio.entries.forEach { mode ->
                    val choose = {
                        picking = false
                        if (mode != current) { current = mode; SettingsPreferences.setSttOtherAudio(context, mode) }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = choose)
                        .testTag("voice-other-audio:${mode.id}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == current, onClick = choose)
                        Spacer(Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (mode == current) FontWeight.Medium else FontWeight.Normal)
                    }
                }
                // 用户 11-12 转来：抖音暂停后不会接着放。接着放要播放的应用自己做，输入法替不了。
                Text("暂停播放：说完由播放的应用自己接着放，抖音等部分应用不会接着放，用它们时选「压低音量」。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text("关闭") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextSettingsContent(
    onBack: () -> Unit,
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToSetupGuide: () -> Unit = {},
) {
    val context = LocalContext.current


    var saveTuningAudio by remember {
        mutableStateOf(SettingsPreferences.isSttDebugRecord(context))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("语音转文本", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .settingsVerticalScroll()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 语音总开关放在页内最上面（以前挂在首页那一行上，进来反而找不到）。
            var sttEnabled by remember { mutableStateOf(SettingsPreferences.isSttEnabled(context)) }
            SettingsSection(title = "语音输入") {
                SettingsToggleItem(icon = Icons.TwoTone.GraphicEq, title = "语音转文本",
                    subtitle = if (sttEnabled) "点工具栏麦克风说话，转成文字"
                        else "关着时不显示麦克风，也不下载语音模型",
                    subtitleJumps = listOf("按键与手势 › 长按空格"),
                    checked = sttEnabled, onCheckedChange = { sttEnabled = it; SettingsPreferences.setSttEnabled(context, it) })
                // 连续语音：别人提的需求，默认关（VoicePillResume）。只在语音打开时生效，关着语音就不显示。
                // 原名「开车连续语音」：开车只是用户举的例子（11-12），改叫连续语音、换掉汽车图标；设置键名不变。
                if (sttEnabled) {
                    // 屏蔽外放音：用户 11-04 要的，选项照他的截图，默认不屏蔽（VoiceAudioFocus）。
                    VoiceOtherAudioItem()
                    var pillResume by remember { mutableStateOf(SettingsPreferences.isSttPillResume(context)) }
                    SettingsToggleItem(icon = Icons.TwoTone.RecordVoiceOver, title = "连续语音",
                        subtitle = "点麦克风说话时，发完消息在同一个输入框里接着听；收成悬浮条时回到这个应用也接着听",
                        checked = pillResume, onCheckedChange = { pillResume = it; SettingsPreferences.setSttPillResume(context, it) })
                    // 隔空输入（用户 11-02 要的，参照微信）：默认关；走剪贴板同步已配对的电脑。
                    var remoteTyping by remember { mutableStateOf(SettingsPreferences.isSttRemoteTyping(context)) }
                    val computers = com.kingzcheung.xime.clipboard.sync.WindowsDevices.saved.value
                    SettingsToggleItem(icon = Icons.TwoTone.Computer, title = "隔空输入",
                        subtitle = if (computers.isEmpty()) "说的话发到电脑上，需先在「剪贴板同步」连上电脑"
                            else "说的话发到电脑上，不在手机上屏",
                        checked = remoteTyping, onCheckedChange = { remoteTyping = it; SettingsPreferences.setSttRemoteTyping(context, it) })
                }
            }

            // 模型名（Paraformer、SenseVoice…）多数人看不懂：先给一个按手机和用法自动选好的入口。
            SettingsSection(title = "不知道怎么选") {
                SettingsItem(icon = Icons.TwoTone.Tune, title = "按设备推荐配置",
                    subtitle = "回答几个问题，选好语音模型",
                    onClick = onNavigateToSetupGuide, showArrow = true)
            }

            SettingsSection(title = "语音标点") { SpeechPunctuationSettings() }

            SettingsSection(title = "数字") { SpeechNumberSettings() }

            SettingsSection(title = "纠错") { SpeechCorrectionSettings() }

            val scope = rememberCoroutineScope()

            if (OfflineAsrSettings.isSupported()) {
                // 不再有「使用本地模型」开关：一律先用本地模型；在线接口测试通过后才可选「优先使用在线接口」。
                run {
                    // 「本地识别」一组公开版里只剩一句「常驻、释放在性能与省电里设置」的提示，什么也改不了，排版还上松下紧
                    // （用户 11-12「不明所以」）：去掉。常驻与释放照旧只在「性能与省电」设置，那页搜「语音」能找到。
                    // 调优录音是开发者工具：只在自用测试包和调试包里显示，这组也只在那时出现。
                    if (com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG) SettingsSection(title = "本地识别") {
                        Column(Modifier.settingsSearchAnchor("保存语音调优录音").fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "保存语音调优录音",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = saveTuningAudio,
                                    onCheckedChange = {
                                        saveTuningAudio = it
                                        SettingsPreferences.setSttDebugRecord(context, it)
                                    },
                                )
                            }
                            Text(
                                text = "录音只存在本机，用来改进断句和模型；最多留 300 段或 500 MB，不会上传。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 本地模型下载与管理
                    OfflineAsrSettings.ModelSection()
                }
            }

            OnlineSpeechApiSection()
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 内置在线语音：接口地址、密钥、模型直接填在这里，一个测试按钮和接口状态，不用装插件。
 * 密钥只存本机、不进备份。打开「优先使用在线语音」后说话先走在线，失败时这段改用本地模型。
 */
@Composable
internal fun OnlineSpeechApiSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = com.kingzcheung.xime.speech.OnlineSpeechApi
    val saved = remember { api.migrateFromPlugin(context); api.config(context) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var appId by remember { mutableStateOf(saved.appId) }
    var bodyTemplate by remember { mutableStateOf(saved.bodyTemplate) }
    var textPath by remember { mutableStateOf(saved.textPath) }
    var expanded by remember { mutableStateOf(saved.bodyTemplate.isNotBlank() || api.presets.none { it.url == saved.baseUrl && it.model == saved.model }) }
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(api.presets.none { it.url == saved.baseUrl }) }
    var status by remember { mutableStateOf(api.status(context)) }
    var testing by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(api.isConfigured(context)) }
    var preferOnline by remember { mutableStateOf(api.isActive(context)) }
    fun save() {
        api.save(context, com.kingzcheung.xime.speech.OnlineSpeechApi.Config(baseUrl, apiKey, model, appId, bodyTemplate, textPath))
        status = api.status(context)
        configured = api.isConfigured(context)
        if (!configured && preferOnline) {
            preferOnline = false
            SettingsPreferences.setSttUseLocal(context, true)
        }
    }
    // 火山引擎(豆包)：密钥一栏是 API Key 或 Access Token，模型一栏是资源 ID，旧版鉴权多一栏 App ID。
    val volcengine = com.kingzcheung.xime.speech.VolcengineSpeechApi.isVolcengine(baseUrl)
    SettingsSection(title = "在线语音") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                androidx.compose.material3.OutlinedButton(onClick = { menu = true }, enabled = !testing) {
                    Text(if (custom) "自定义接口" else api.presets.firstOrNull { it.url == baseUrl }?.name ?: "自定义接口")
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    api.presets.forEach { preset ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                            if (baseUrl != preset.url) { apiKey = ""; appId = "" }
                            baseUrl = preset.url; model = preset.model; bodyTemplate = ""; textPath = ""
                            custom = false; expanded = false; menu = false; save()
                        })
                    }
                    androidx.compose.material3.DropdownMenuItem(text = { Text("自定义接口") }, onClick = {
                        custom = true; expanded = true; menu = false
                    })
                }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起接口配置" else "修改接口配置") }
            if (expanded) androidx.compose.material3.OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; save() },
                label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("speech-api-url"))
            if (volcengine && (expanded || appId.isNotBlank())) androidx.compose.material3.OutlinedTextField(value = appId, onValueChange = { appId = it; save() },
                label = { Text("App ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("speech-api-app-id"))
            androidx.compose.material3.OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; save() },
                label = { Text(if (!volcengine) "API 密钥" else if (appId.isBlank()) "API Key" else "Access Token") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("speech-api-key"))
            if (expanded) androidx.compose.material3.OutlinedTextField(value = model, onValueChange = { model = it; save() },
                label = { Text(if (volcengine) "资源 ID" else "模型") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("speech-api-model"))
            if (expanded && !volcengine) {
                androidx.compose.material3.OutlinedTextField(value = bodyTemplate, onValueChange = { bodyTemplate = it; save() },
                    label = { Text("自定义 POST 请求体（可选）") }, supportingText = { Text("留空沿用服务商格式。自定义时填完整地址，JSON 可用 {{audio}}、{{model}} 和 {{language}} 作为字符串值。") },
                    modifier = Modifier.fillMaxWidth())
                if (bodyTemplate.isNotBlank()) androidx.compose.material3.OutlinedTextField(value = textPath, onValueChange = { textPath = it; save() },
                    label = { Text("响应文字路径（可选）") }, placeholder = { Text("例如 data.text") }, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("状态：$status", style = MaterialTheme.typography.bodyMedium,
                    color = if (status.startsWith("可用")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("speech-api-status"))
                androidx.compose.material3.OutlinedButton(enabled = !testing && configured, onClick = {
                    save(); testing = true; status = "测试中…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) { api.test(context) }
                        testing = false
                    }
                }, modifier = Modifier.testTag("speech-api-test")) { Text("检测连接") }
            }
            Text(if (volcengine) "新版填 API Key。旧版在接口配置里填写 App ID 和 Access Token。" else "密钥保存在本机。检测连接会向所选服务发送一秒测试静音。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (configured) SettingsToggleItem(icon = Icons.TwoTone.GraphicEq, title = "优先使用在线语音",
            subtitle = "在线识别失败时改用本地模型",
            checked = preferOnline, onCheckedChange = {
                preferOnline = it
                if (it) SettingsPreferences.setSttOnlinePluginId(context, com.kingzcheung.xime.speech.OnlineSpeechApi.BUILTIN_ID)
                SettingsPreferences.setSttUseLocal(context, !it)
            })
    }
}
