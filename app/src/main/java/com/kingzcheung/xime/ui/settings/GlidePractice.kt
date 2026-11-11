package com.kingzcheung.xime.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.twotone.Upload
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.glide.GlideSampleRecorder
import com.kingzcheung.xime.handwriting.training.HandwritingContribution
import com.kingzcheung.xime.ui.common.CompactDropdownMenu
import com.kingzcheung.xime.ui.common.CompactMenuRow
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val practiceClient by lazy { OkHttpClient() }

private suspend fun uploadPractice(context: Context, samples: List<String>) {
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).bufferedWriter(Charsets.UTF_8).use { out ->
        samples.forEach { out.appendLine(HandwritingContribution.licensedSample(it)) }
    }
    val request = Request.Builder().url(HandwritingContribution.UPLOAD_URL)
        .header("User-Agent", "CyIME/${com.kingzcheung.xime.BuildConfig.VERSION_NAME}")
        .header("X-CyIME-Writer", HandwritingContribution.writerId(context))
        .header("X-CyIME-Format", "cyime-glide-practice/1")
        .post(bytes.toByteArray().toRequestBody("application/gzip".toMediaType())).build()
    suspendCancellableCoroutine<Unit> { result ->
        val call = practiceClient.newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val receipt = runCatching { JSONObject(it.body.string()) }.getOrNull()
                    if (it.isSuccessful && receipt?.optBoolean("ok") == true && receipt.optInt("count") == samples.size)
                        result.resume(Unit)
                    else result.resumeWithException(IOException(receipt?.optString("error")?.takeIf(String::isNotBlank)
                        ?: "接收端未确认上传（${it.code}）"))
                }
            }
        })
    }
}

/**
 * 「贡献滑行轨迹」页的三种键盘（用户 11-09：顶上选模式，跟手写贡献一样）。[layout] 是上传样本里的 layout，
 * 和原来练习板的一样（26 键中英文都是 qwerty）。题目同原来练习板，各加了几个常用词。
 */
internal enum class GlideContributeMode(val title: String, val layout: String, val switchTitle: String, val prompts: List<String>) {
    English("英文 26 键", "qwerty", "英文滑行",
        listOf("hello", "world", "good", "after", "thanks", "people", "because", "think")),
    Chinese("中文 26 键", "qwerty", "中文滑行",
        listOf("nihao", "shuru", "women", "xihuan", "jintian", "pengyou", "shijian", "zhongguo")),
    Nine("九键", "t9", "九键滑行",
        listOf("nihao", "shuru", "women", "xihuan", "jintian", "pengyou", "shijian", "zhongguo"));

    fun on(prefs: KeyboardInputPreferences): Boolean = when (this) {
        English -> prefs.glideTyping
        Chinese -> prefs.chineseGlideTyping
        Nine -> prefs.t9GlideTyping
    }

    fun with(prefs: KeyboardInputPreferences, on: Boolean): KeyboardInputPreferences = when (this) {
        English -> prefs.copy(glideTyping = on)
        Chinese -> prefs.copy(chineseGlideTyping = on)
        Nine -> prefs.copy(t9GlideTyping = on)
    }

    val keyboardHint: String get() = when (this) {
        English -> "把键盘换成英文 26 键，从题目第一个字母滑到最后一个字母。"
        Chinese -> "把键盘换成中文全拼 26 键，按全拼滑，从第一个字母滑到最后一个字母。"
        Nine -> "把键盘换成九键，从题目第一个字母的键滑到最后一个字母的键。"
    }
}

/**
 * 「滑行输入」页最底下一行，点开 [GlideContributeScreen]。
 * 用户 11-09：原来的「滑行练习」是另画的小键盘，看不出给谁用、拿来干什么；现在只写清楚是给改进识别收数据。
 */
@Composable
internal fun GlidePracticeItem(onOpen: () -> Unit) {
    SettingsItem(Icons.TwoTone.Gesture, "贡献滑行轨迹", "给改进滑行识别收集数据：出题后用键盘正常滑，自愿上传到魔搭。",
        onOpen, showArrow = true)
}

/**
 * 贡献滑行轨迹：出题，用真键盘在下面的输入框里正常滑（26 键或九键滑行，和平时打字同一套），
 * 键盘把这一笔的轨迹交给 [GlideSampleRecorder]，从题目第一个字母的键起、在最后一个字母的键停就算一条样本，换下一题。
 * 打开「自动贡献」并同意 CC0 协议后才上传；关掉或退出丢弃待传的。平时打字不记录轨迹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlideContributeScreen(onBack: () -> Unit, upload: suspend (Context, List<String>) -> Unit = ::uploadPractice) {
    val context = LocalContext.current.applicationContext
    val prefs = rememberKeyboardInputPreferences()
    var settings by remember(prefs) { mutableStateOf(prefs) }
    var mode by remember { mutableStateOf(GlideContributeMode.English) }
    var modeMenu by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    // 每换一题换一个输入框：输入法重新开始，上一题留在键盘里的拼音不带到下一题。
    var round by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var uploaded by remember { mutableIntStateOf(0) }
    var accepted by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    // 哪一题的输入框有焦点：只收本页输入框里滑的（分屏时别的应用里滑的不算）。按题记，旧框失焦晚到也不会把新框算成没焦点。
    var focusedRound by remember { mutableIntStateOf(-1) }
    val pending = remember { mutableStateListOf<String>() }
    val prompt = mode.prompts[index % mode.prompts.size]

    val onStroke by rememberUpdatedState<(GlideSampleRecorder.Stroke) -> Unit> { stroke ->
        if (focusedRound != round) return@rememberUpdatedState
        val sample = if (stroke.layout == mode.layout) GlideSampleRecorder.sample(stroke, prompt) else null
        when {
            stroke.layout != mode.layout -> { hint = "现在是${if (stroke.layout == "t9") "九键" else "26 键"}，这一页要${mode.title}。" }
            sample == null -> { hint = "没算上：请从 ${prompt.first()} 滑到 ${prompt.last()}。" }
            enabled && pending.size >= 40 -> { hint = "待传已满，请先重试上传或关闭贡献。" }
            else -> {
                if (enabled) pending.add(sample)
                accepted++
                index++
                round++
                hint = ""
            }
        }
    }
    DisposableEffect(Unit) {
        val listener: (GlideSampleRecorder.Stroke) -> Unit = { onStroke(it) }
        GlideSampleRecorder.listener = listener
        onDispose { if (GlideSampleRecorder.listener === listener) GlideSampleRecorder.listener = null }
    }
    LaunchedEffect(enabled, error.isEmpty()) {
        if (!enabled || error.isNotEmpty()) return@LaunchedEffect
        while (true) {
            snapshotFlow { pending.isNotEmpty() }.first { it }
            delay(1500)
            val batch = pending.take(40)
            if (batch.isEmpty()) continue
            try {
                upload(context, batch)
                pending.removeAll(batch.toSet())
                uploaded += batch.size
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "上传失败"; break }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 顶栏：退出、键盘切换（同手写贡献的题库切换）、第几题
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出") }
                Box {
                    TextButton(onClick = { modeMenu = true }, modifier = Modifier.testTag("glide-contribute-mode")) {
                        Text(mode.title, fontSize = 16.sp)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换键盘")
                    }
                    CompactDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        for (m in GlideContributeMode.entries) CompactMenuRow(label = m.title, selected = m == mode,
                            supportingText = if (m.on(settings)) null else "${m.switchTitle}没开",
                            onClick = { modeMenu = false; if (m != mode) { mode = m; index = 0; round++; hint = "" } })
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("${index % mode.prompts.size + 1}/${mode.prompts.size} · 已滑 $accepted 条", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            }
            // 这种键盘的滑行没开时就在这里开，不用跑回设置页。
            if (!mode.on(settings)) SettingsToggleItem(Icons.TwoTone.Gesture, mode.switchTitle, "要先打开才能滑。",
                checked = false, onCheckedChange = { settings = mode.with(settings, it); settings.save(context) },
                lockMessage = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                    com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.GLIDE_TYPING, true)?.message)
            SettingsToggleItem(Icons.TwoTone.Upload, "自动贡献",
                "把题目、键盘布局、轨迹和随机贡献编号上传到魔搭，用于改进滑行识别。关闭或退出会丢弃待传轨迹。",
                enabled, { turnOn ->
                    if (turnOn) showLicense = true else { enabled = false; pending.clear(); error = "" }
                })
            Text("请滑出 $prompt", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
            Text(mode.keyboardHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(round) {
                val thisRound = round
                var text by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) focusedRound = thisRound else if (focusedRound == thisRound) focusedRound = -1 }
                    .testTag("glide-contribute-field"),
                    placeholder = { Text("在这里用键盘滑") }, singleLine = true)
                LaunchedEffect(Unit) {
                    runCatching { focus.requestFocus() }
                    keyboard?.show()
                }
            }
            if (hint.isNotEmpty()) Text(hint, color = MaterialTheme.colorScheme.error)
            if (enabled) Text("已上传 $uploaded 条，待传 ${pending.size} 条")
            if (error.isNotBlank()) {
                Text(error)
                TextButton(onClick = { error = "" }) { Text("重试上传") }
            }
        }
    }
    if (showLicense) SampleLicenseDialog(
        "开启后，本页滑的题目、布局、键盘比例、轨迹坐标与时间、随机编号和应用版本会自动发往魔搭。关闭或退出后停止贡献，平时打字的滑行不会上传。",
        onDismiss = { showLicense = false },
    ) { showLicense = false; enabled = true }
}
