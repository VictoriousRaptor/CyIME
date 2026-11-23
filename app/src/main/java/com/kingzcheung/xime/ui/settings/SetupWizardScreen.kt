package com.kingzcheung.xime.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.kingzcheung.xime.platform.AndroidPlatform
import com.kingzcheung.xime.platform.DeviceVendor
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kingzcheung.xime.rime.PreparationProgress
import com.kingzcheung.xime.rime.PreparationService
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.ChineseSchemas
import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class SetupStatus(
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val preparing: Boolean = true,
    val schemas: List<String> = emptyList(),
    val error: String? = null,
    /** 准备没过、正在等下一次自动重试时的原因（部署问题 + 几秒后重试）；正常准备中为 null。 */
    val detail: String? = null,
) {
    val ready: Boolean get() = !preparing && error == null && schemas.isNotEmpty()
    val complete: Boolean get() = imeEnabled && imeSelected && ready
}

/**
 * 准备词库太久没有动静时向导显示的提示（11-20 小米 HyperOS 4 用户卡在「正在准备词库」，不知道在干什么、卡在哪）：
 * 停了多久、停在哪一步、日志里最近几行，以及用户能做什么。
 */
internal data class PreparationStall(
    val seconds: Long,
    val where: String,
    val logTail: List<String>,
    val advice: List<String>,
)

/** 多久没有新进展算超时。编译大词典时一个文件可能一两分钟不变大，留足余量。 */
internal const val PREPARATION_STALL_MS = 180_000L

/** 纯函数，可单测：日志里和准备词库有关的最近几行（只留时间和内容，太长的截断）。 */
internal fun preparationLogTail(text: String, lines: Int = 8): List<String> {
    val tags = listOf("PreparationProgress", "RimeConfigHelper", "AutomaticPreparation", "SetupWizard", "RimeEngine")
    return text.lineSequence().filter { line -> tags.any { " $it:" in line } }.toList().takeLast(lines)
        .map { line -> line.substringAfter(' ').let { if (it.length > 160) it.take(159) + "…" else it } }
}

private fun readPreparationLogTail(): List<String> = runCatching {
    com.kingzcheung.xime.util.FileLogger.flushNow()
    val file = com.kingzcheung.xime.util.FileLogger.getCurrentLogFile() ?: return emptyList()
    java.io.RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - 64 * 1024).coerceAtLeast(0L)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.seek(start)
        raf.readFully(bytes)
        preparationLogTail(String(bytes, Charsets.UTF_8))
    }
}.getOrDefault(emptyList())

/** 把今天的日志交给分享面板（发微信、存文件都行），反馈时不用再去设置里找日志页。 */
private fun exportPreparationLog(context: Context) {
    try {
        com.kingzcheung.xime.util.FileLogger.flushNow()
        val file = com.kingzcheung.xime.util.FileLogger.getCurrentLogFile() ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CyIME 准备词库日志 - ${file.name}")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        context.startActivity(Intent.createChooser(send, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Log.e("SetupWizard", "Could not export the log", e)
        android.widget.Toast.makeText(context, "导出日志失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * 超时提示里的「重新开始准备」：关掉 CyIME 再打开，和从最近任务里划掉重开一样。
 * 原生编译卡住时没法在进程里打断，只有重开进程；librime 按校验和判断，已经编完的词库一般不重编。
 */
private fun restartForPreparation(context: Context) {
    com.kingzcheung.xime.util.FileLogger.w("SetupWizard", "User restarted CyIME after preparation stalled")
    com.kingzcheung.xime.util.FileLogger.flushNow()
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
    PreparationService.stop(context)
    context.startActivity(launch)
    android.os.Process.killProcess(android.os.Process.myPid())
}

/**
 * vivo（OriginOS / Funtouch，含 iQOO）上 [InputMethodManager.showInputMethodPicker] 弹出的
 * 「选择输入法」框可能一个输入法都不列，只剩「取消」（OriginOS 6 用户反馈，CyIME 已启用）。
 * 这类系统上第 3 步改为先去系统输入法设置里选默认输入法，返回 null 表示系统选择框可用、保持原样。
 */
internal fun imePickerFallbackHint(vendor: DeviceVendor): String? {
    return if (vendor == DeviceVendor.VIVO) "vivo / iQOO(OriginOS)的输入法选择框可能是空的。请点下面按钮打开系统输入法设置，" +
        "在「默认输入法」(或「当前输入法」)里选 CyIME；如果打开的页面里没有，就进" +
        "「设置 → 系统管理(或更多设置)→ 语言与输入法 → 默认输入法」，也可以在设置顶部搜索「默认输入法」。"
    else null
}

/** 打开系统输入法设置；个别系统没有这个页面时退回设置首页。 */
private fun openImeSettings(context: Context) {
    try { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        Log.w("SetupWizard", "No input method settings page", e)
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
fun SetupWizardScreen(
    visible: Boolean = true,
    onNavigateToSchemaSettings: () -> Unit,
    onCompleted: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(SetupStatus()) }
    var attempt by remember { mutableIntStateOf(0) }
    // 后台线程写的重试原因单独放一个状态：status 由主线程每 500ms 整体改写，混写会互相覆盖。
    var preparationDetail by remember { mutableStateOf<String?>(null) }
    val pickerFallbackHint = remember { imePickerFallbackHint(AndroidPlatform.vendor) }
    // 准备到哪一步（应用启动时的预准备和这里共用一份），每秒刷新一次已用时间；很久没动静就提示超时。
    val progress by PreparationProgress.state.collectAsState()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var preparingSince by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var stall by remember { mutableStateOf<PreparationStall?>(null) }

    // 用户去过系统输入法设置（点过启用 / 选择）：回来以后还认不出，就给「直接开始使用」的出口（#508）。
    var visitedSystemSettings by remember { mutableStateOf(false) }
    var lastImeReport by remember { mutableStateOf("") }
    fun refreshImeState() {
        status = status.copy(
            imeEnabled = isCyimeEnabled(context),
            imeSelected = isCyimeSelected(context),
        )
        if (!status.imeEnabled || !status.imeSelected) {
            val report = imeStatusReport(context)
            if (report != lastImeReport) {
                lastImeReport = report
                Log.w("SetupWizard", "IME not detected enabled=${status.imeEnabled} selected=${status.imeSelected} $report")
                com.kingzcheung.xime.util.FileLogger.w("SetupWizard",
                    "IME not detected enabled=${status.imeEnabled} selected=${status.imeSelected} $report")
            }
        }
    }
    fun finishSetup() {
        val enabled = SchemaManager.getEnabledSchemas(context)
        if (enabled.isNotEmpty()) {
            if (SettingsPreferences.getCurrentSchema(context) !in enabled)
                SettingsPreferences.setCurrentSchema(context, enabled.first())
            SettingsPreferences.setSetupCompleted(context, true)
            onCompleted()
        }
    }
    LaunchedEffect(visible, lifecycle) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The system picker can close without a new activity resume event.
            while (true) { refreshImeState(); delay(500) }
        }
    }
    LaunchedEffect(visible, attempt) {
        if (!visible) return@LaunchedEffect
        status = status.copy(preparing = true, error = null)
        preparationDetail = null
        preparingSince = android.os.SystemClock.elapsedRealtime()
        try {
            val schemas = withContext(Dispatchers.IO) {
                // 准备没过时会一直自动重试；把原因摆出来，不再只有一根进度条转十几分钟看不出卡在哪（GitHub #36）。
                RimeConfigHelper.prepareAutomatically(context) { message ->
                    preparationDetail = message.takeIf { "重试" in it }
                }
                // 用方案文件里的名字（小鹤双拼等），和「语言与方案」页一致；读不到才退回内置名或 ID。
                val names = SchemaManager.discoverSchemas(context).associate { it.schemaId to it.name }
                // 双拼的 14 键版本是组合配置（双拼 × 14 键），不单独列一项。
                SchemaManager.getEnabledSchemas(context).filterNot(com.kingzcheung.xime.settings.CyimeInputDefaults::isCompanion).map { id ->
                    names[id] ?: DoublePinyinScheme.BUILTINS.firstOrNull { it.id == id }?.name ?: ChineseSchemas.displayName(id, id)
                }
            }
            preparationDetail = null
            status = status.copy(preparing = false, schemas = schemas,
                error = if (schemas.isEmpty()) "请至少启用一个输入方案" else null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.e("SetupWizard", "Automatic preparation failed", e)
            status = status.copy(preparing = false, error = "词库准备失败，请重试")
        }
    }
    LaunchedEffect(visible, status.preparing) {
        while (visible && status.preparing) { now = android.os.SystemClock.elapsedRealtime(); delay(1_000L) }
    }
    // 等准备时开前台服务、亮着屏幕（11-22 用户卡了半小时）：以前准备只是后台线程，切到系统设置去启用、熄屏时
    // 系统会冻结或先杀掉这个占着近 1 GB 内存的进程，回来再从头编。等 1 秒再开：早已准备好的不闪一下通知。
    // 只能在界面在前台时开（系统不许后台开前台服务），所以每次回到这一页都再开一次；已开着时只是刷新通知。
    LaunchedEffect(status.preparing, lifecycle) {
        if (!status.preparing) { PreparationService.stop(context); return@LaunchedEffect }
        delay(1_000L)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { PreparationService.start(context) }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, visible && status.preparing) {
        view.keepScreenOn = visible && status.preparing
        onDispose { view.keepScreenOn = false }
    }
    val lastChange = maxOf(progress.lastChangeAt, preparingSince)
    val quiet = status.preparing && now - lastChange >= PREPARATION_STALL_MS
    LaunchedEffect(quiet, lastChange) {
        if (!quiet) { stall = null; return@LaunchedEffect }
        val seconds = (now - lastChange) / 1000
        val where = progress.step?.let { "${it.label}${progress.detail.takeIf { d -> d.isNotEmpty() }?.let { d -> "：$d" }.orEmpty()}" }
            ?: "还没开始（在等另一项准备结束）"
        val lowStorage = context.filesDir.usableSpace < 300L * 1024 * 1024
        stall = withContext(Dispatchers.IO) {
            PreparationProgress.logStall(seconds)
            Log.w("SetupWizard", "Preparation stalled ${seconds}s at $where")
            PreparationStall(seconds, where, readPreparationLogTail(),
                PreparationProgress.stallAdvice(progress.step, AndroidPlatform.vendor, lowStorage))
        }
    }
    if (visible) SetupWizardContent(
        status = status.copy(detail = preparationDetail.takeIf { status.preparing }),
        progress = progress,
        stepElapsedMs = if (progress.stepStartedAt > 0) now - progress.stepStartedAt else now - preparingSince,
        stall = stall?.takeIf { status.preparing }?.copy(seconds = (now - lastChange) / 1000),
        onExportLog = { exportPreparationLog(context) },
        onRestart = { restartForPreparation(context) },
        onEnable = { visitedSystemSettings = true; openImeSettings(context) },
        onSchemas = onNavigateToSchemaSettings,
        onRetry = { attempt++ },
        onSwitch = {
            visitedSystemSettings = true
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm != null) imm.showInputMethodPicker() else openImeSettings(context)
        },
        pickerFallbackHint = pickerFallbackHint,
        onOpenImeSettings = { visitedSystemSettings = true; openImeSettings(context) },
        onFinish = {
            refreshImeState()
            if (status.complete) finishSetup()
        },
        showSkipDetection = showSkipDetection(visitedSystemSettings, status),
        onSkipDetection = {
            com.kingzcheung.xime.util.FileLogger.w("SetupWizard", "User skipped IME detection: ${imeStatusReport(context)}")
            finishSetup()
        },
    )
}

/**
 * 纯函数，可单测：去过系统设置、词库已准备好、还是认不出启用或切换时，给出口（#508 红米 Turbo5 / Android 17：
 * 已启用、已设默认、能打字，引导还说没启用，进不了设置）。没去过系统设置的新用户看不到，照常按三步走。
 */
internal fun showSkipDetection(visitedSystemSettings: Boolean, status: SetupStatus): Boolean =
    visitedSystemSettings && status.ready && !status.complete

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupWizardContent(
    status: SetupStatus,
    onEnable: () -> Unit,
    onSchemas: () -> Unit,
    onRetry: () -> Unit,
    onSwitch: () -> Unit,
    onFinish: () -> Unit,
    pickerFallbackHint: String? = null,
    onOpenImeSettings: () -> Unit = {},
    progress: PreparationProgress.State = PreparationProgress.State(),
    stepElapsedMs: Long = 0L,
    stall: PreparationStall? = null,
    onExportLog: () -> Unit = {},
    onRestart: () -> Unit = {},
    showSkipDetection: Boolean = false,
    onSkipDetection: () -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text("设置向导") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).settingsVerticalScroll()
            .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按顺序完成下面三项，即可开始输入。", style = MaterialTheme.typography.bodyLarge)
            SetupCard(1, "启用 CyIME", status.imeEnabled,
                if (status.imeEnabled) "已启用" else "待启用") {
                if (!status.imeEnabled) {
                    Text("在系统输入法列表中打开 CyIME，返回后会自动检查。")
                    Button(onClick = onEnable) { Text("去系统设置启用") }
                }
            }
            SetupCard(2, "准备输入方案", status.ready,
                when { status.preparing -> "准备中"; status.error != null -> "需要处理"; status.ready -> "已就绪"; else -> "待准备" }) {
                when {
                    status.preparing -> {
                        val step = progress.step?.takeIf { it != PreparationProgress.Step.DONE }
                        if (step == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { PreparationProgress.fraction(progress) }, modifier = Modifier.fillMaxWidth())
                        Text("正在准备词库，首次使用可能需要几分钟…")
                        // 现在在做哪一步、哪个文件、这一步用了多久：卡住时一眼看出停在哪（11-20 用户反馈）。
                        if (step != null) {
                            Text("第 ${step.ordinal + 1}/${PreparationProgress.Step.entries.size - 1} 步：${step.label}（${stepElapsedMs / 1000} 秒）",
                                style = MaterialTheme.typography.bodyMedium)
                            if (step == PreparationProgress.Step.COMPILE && progress.total > 0)
                                Text("已编译词典 ${progress.done}/${progress.total}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (progress.detail.isNotEmpty()) Text(progress.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        status.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        stall?.let { PreparationStallNotice(it, onExportLog, onRestart) }
                    }
                    status.error != null -> {
                        Text(status.error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry) { Text("重试准备") }
                    }
                    else -> Text(status.schemas.joinToString(" · "))
                }
                // 老人包不出下一页（按设备推荐配置）。
                if (!com.kingzcheung.xime.BuildConfig.ELDER) Text("用全拼、双拼还是五笔，26 键、14 键还是九键，完成后下一页会问你。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSchemas, enabled = !status.preparing) { Text("选择其他方案(可选)") }
            }
            SetupCard(3, "切换到 CyIME", status.imeEnabled && status.imeSelected,
                if (status.imeEnabled && status.imeSelected) "已切换" else "待切换") {
                if (!status.imeEnabled || !status.imeSelected) {
                    val canSwitch = status.imeEnabled && status.ready
                    if (pickerFallbackHint == null) {
                        Text("启用并准备完成后，在输入法选择器中选中 CyIME。")
                        Button(onClick = onSwitch, enabled = canSwitch) { Text("选择 CyIME") }
                    } else {
                        Text(pickerFallbackHint)
                        Button(onClick = onOpenImeSettings, enabled = canSwitch) { Text("去系统设置选择 CyIME") }
                        TextButton(onClick = onSwitch, enabled = canSwitch) { Text("仍用输入法选择框") }
                    }
                }
            }
            if (status.complete) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("✓ 设置成功，可以开始输入了", Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(onClick = onFinish, enabled = status.complete, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.complete) "开始使用" else "完成三项后即可开始使用")
            }
            if (showSkipDetection) {
                Text("已经在系统设置里启用、也能用 CyIME 打字，这里还是没变？有的手机读不到启用状态。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSkipDetection, modifier = Modifier.fillMaxWidth()) { Text("已经能用了，直接开始使用") }
            }
        }
    }
}

@Composable
private fun PreparationStallNotice(stall: PreparationStall, onExportLog: () -> Unit, onRestart: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val minutes = stall.seconds / 60
            Text(if (minutes > 0) "超时了：已经 $minutes 分钟没有新进展" else "超时了：已经 ${stall.seconds} 秒没有新进展",
                color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            Text("停在：${stall.where}", color = MaterialTheme.colorScheme.onErrorContainer)
            if (stall.logTail.isNotEmpty()) {
                Text("最近的日志：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stall.logTail.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text("可以这样做：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            stall.advice.forEach { line ->
                Row {
                    Text("•", Modifier.padding(end = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRestart) { Text("重新开始准备") }
                OutlinedButton(onClick = onExportLog) { Text("导出日志") }
            }
        }
    }
}

@Composable
private fun SetupCard(number: Int, title: String, complete: Boolean, state: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (complete)
        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (complete) "✓" else number.toString(), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp))
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(state, style = MaterialTheme.typography.labelLarge)
            }
            content()
        }
    }
}
