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
import com.kingzcheung.xime.handwriting.training.ContributePromptDeck
import com.kingzcheung.xime.handwriting.training.HandwritingContribution
import com.kingzcheung.xime.ui.common.CompactDropdownMenu
import com.kingzcheung.xime.ui.common.CompactMenuRow
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * 和原来练习板的一样（26 键中英文都是 qwerty）。题目见 [deck]；[prompts] 只在词表读不到时用。
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

    /**
     * 这种键盘的题，每人随机（用户 11-12：原来每人都是同样 8 个词，几十个人交上来全一样）。
     * 英文从 english.txt 常用词出，中文、九键从贡献题库的两字词出、滑全拼；按贡献编号打乱，中文和九键顺序也不同。
     */
    fun deck(context: Context): List<ContributePromptDeck.GlidePrompt> = runCatching {
        val seed = ContributePromptDeck.seedOf(HandwritingContribution.writerId(context), "glide-$name")
        when (this) {
            English -> context.assets.open("english.txt").bufferedReader(Charsets.UTF_8)
                .useLines { ContributePromptDeck.englishGlide(it, seed) }
            Chinese, Nine -> context.assets.open(ContributePromptDeck.POOL_ASSET).bufferedReader(Charsets.UTF_8)
                .useLines { ContributePromptDeck.pinyinGlide(it, seed) }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: prompts.map { ContributePromptDeck.GlidePrompt(it) }

    /** 滑到第几题，按键盘分开记，随机题和自己输入的也分开，下次进来接着滑。 */
    fun position(context: Context, own: Boolean = false): Int =
        context.getSharedPreferences(GLIDE_PREFS, Context.MODE_PRIVATE).getInt(positionKey(own), 0)

    fun setPosition(context: Context, index: Int, own: Boolean = false) {
        context.getSharedPreferences(GLIDE_PREFS, Context.MODE_PRIVATE).edit().putInt(positionKey(own), index).apply()
    }

    private fun positionKey(own: Boolean) = if (own) "pos_own_$name" else "pos_$name"

    /** 「自己输入」填的词，按键盘分开存。 */
    fun ownText(context: Context): String =
        context.getSharedPreferences(GLIDE_PREFS, Context.MODE_PRIVATE).getString("own_$name", null).orEmpty()

    fun setOwnText(context: Context, text: String) {
        context.getSharedPreferences(GLIDE_PREFS, Context.MODE_PRIVATE).edit().putString("own_$name", text)
            .putInt(positionKey(true), 0).apply()
    }

    /** 把自己输入的一段变成题：英文只收英文词；中文、九键收拼音，汉字词查出拼音。在 IO 线程调用。 */
    fun parseOwn(context: Context, text: String): ContributePromptDeck.Own<ContributePromptDeck.GlidePrompt> =
        ContributePromptDeck.ownGlide(text, english = this == English,
            pinyinOf = if (this == English) { _: String -> null } else glidePinyin(context))

    /** 自己输入的题；没填过为空。在 IO 线程调用。 */
    fun ownDeck(context: Context): List<ContributePromptDeck.GlidePrompt> =
        ownText(context).takeIf { it.isNotBlank() }?.let { parseOwn(context, it).prompts }.orEmpty()

    val keyboardHint: String get() = when (this) {
        English -> "把键盘换成英文 26 键，从题目第一个字母滑到最后一个字母。"
        Chinese -> "把键盘换成中文全拼 26 键，按全拼滑，从第一个字母滑到最后一个字母。"
        Nine -> "把键盘换成九键，从题目第一个字母的键滑到最后一个字母的键。"
    }
}

private const val GLIDE_PREFS = "glide_contribute"

/** 汉字词查拼音（第一次用到时读题库和字表）。 */
@Volatile private var glidePinyinCache: ((String) -> String?)? = null

private fun glidePinyin(context: Context): (String) -> String? = glidePinyinCache ?: runCatching {
    context.assets.open(ContributePromptDeck.POOL_ASSET).bufferedReader(Charsets.UTF_8).useLines { pool ->
        context.assets.open("handwriting/hanzi_pinyin.tsv").bufferedReader(Charsets.UTF_8).useLines { chars ->
            ContributePromptDeck.pinyinLookup(pool, chars)
        }
    }
}.getOrElse { { _: String -> null } }.also { glidePinyinCache = it }

/** 滑什么：每人随机，或者自己输入（用户 11-12：要能自己选）。 */
internal enum class GlideChoice(val title: String) { Random("随机"), Own("自己输入") }

private fun glideChoice(context: Context): GlideChoice {
    val name = context.getSharedPreferences(GLIDE_PREFS, Context.MODE_PRIVATE).getString("choice", null)
    return GlideChoice.entries.firstOrNull { it.name == name } ?: GlideChoice.Random
}

private fun setGlideChoice(context: Context, choice: GlideChoice) {
    context.getSharedPreferences(GLIDE_PREFS, Context.MODE_PRIVATE).edit().putString("choice", choice.name).apply()
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
internal fun GlideContributeScreen(
    onBack: () -> Unit,
    upload: suspend (Context, List<String>) -> Unit = ::uploadPractice,
    deckOf: (Context, GlideContributeMode) -> List<ContributePromptDeck.GlidePrompt> = { c, m -> m.deck(c) },
) {
    val context = LocalContext.current.applicationContext
    val prefs = rememberKeyboardInputPreferences()
    var settings by remember(prefs) { mutableStateOf(prefs) }
    var mode by remember { mutableStateOf(GlideContributeMode.English) }
    var modeMenu by remember { mutableStateOf(false) }
    var deck by remember { mutableStateOf(emptyList<ContributePromptDeck.GlidePrompt>()) }
    var choice by remember { mutableStateOf(glideChoice(context)) }
    var choiceMenu by remember { mutableStateOf(false) }
    // 题读完了没有：自己输入还没填时 deck 为空，要显示填词的框而不是「正在出题」
    var ready by remember { mutableStateOf(false) }
    var ownDraft by remember { mutableStateOf("") }
    var ownVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
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
    val own = choice == GlideChoice.Own
    LaunchedEffect(mode, choice, ownVersion) {
        ready = false
        deck = emptyList()
        val loaded = withContext(Dispatchers.IO) { if (own) mode.ownDeck(context) else deckOf(context, mode) }
        index = mode.position(context, own)
        deck = loaded
        ready = true
    }
    val current = if (deck.isEmpty()) null else deck[index % deck.size]
    val prompt = current?.letters.orEmpty()

    val onStroke by rememberUpdatedState<(GlideSampleRecorder.Stroke) -> Unit> { stroke ->
        if (focusedRound != round || prompt.isEmpty()) return@rememberUpdatedState
        val sample = if (stroke.layout == mode.layout) GlideSampleRecorder.sample(stroke, prompt) else null
        when {
            stroke.layout != mode.layout -> { hint = "现在是${if (stroke.layout == "t9") "九键" else "26 键"}，这一页要${mode.title}。" }
            sample == null -> { hint = "没算上：请从 ${prompt.first()} 滑到 ${prompt.last()}。" }
            enabled && pending.size >= 40 -> { hint = "待传已满，请先重试上传或关闭贡献。" }
            else -> {
                if (enabled) pending.add(sample)
                accepted++
                index++
                mode.setPosition(context, index, own)
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
                            onClick = { modeMenu = false; if (m != mode) { mode = m; round++; hint = "" } })
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(if (deck.isEmpty()) "已滑 $accepted 条" else "第 ${index % deck.size + 1} 题 · 已滑 $accepted 条", fontSize = 13.sp,
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
            // 滑什么：随机，或者自己输入的词
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("滑什么", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { choiceMenu = true }, modifier = Modifier.testTag("glide-contribute-choice")) {
                        Text(choice.title)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择滑什么")
                    }
                    CompactDropdownMenu(expanded = choiceMenu, onDismissRequest = { choiceMenu = false }) {
                        for (c in GlideChoice.entries) CompactMenuRow(label = c.title, selected = c == choice,
                            supportingText = if (c == GlideChoice.Random) "常用词，每人不同" else "输入想滑的词",
                            onClick = {
                                choiceMenu = false
                                if (c != choice) { choice = c; setGlideChoice(context, c); round++; hint = "" }
                            })
                    }
                }
                Spacer(Modifier.weight(1f))
                if (own && deck.isNotEmpty()) TextButton(onClick = {
                    mode.setOwnText(context, ""); ownDraft = ""; ownVersion++; round++; hint = ""
                }, modifier = Modifier.testTag("glide-contribute-own-clear")) { Text("换一批") }
            }
            if (own && ready && deck.isEmpty()) {
                // 自己输入还没填：先填词，再出题
                Text(if (mode == GlideContributeMode.English) "输入想滑的英文词，空格隔开"
                    else "输入想滑的词（汉字或拼音），空格隔开", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(ownDraft, { ownDraft = it }, Modifier.weight(1f).testTag("glide-contribute-own-input"),
                        singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = ownDraft.isNotBlank(), onClick = {
                        val text = ownDraft.trim()
                        val forMode = mode
                        scope.launch {
                            val parsed = withContext(Dispatchers.IO) { forMode.parseOwn(context, text) }
                            if (forMode != mode) return@launch
                            hint = listOfNotNull(
                                "${parsed.unknown} 个查不到，请改输拼音或英文".takeIf { parsed.unknown > 0 },
                                "${parsed.tooLong} 个太长".takeIf { parsed.tooLong > 0 },
                            ).joinToString("；")
                            if (parsed.prompts.isEmpty()) { if (hint.isEmpty()) hint = "先输入想滑的词"; return@launch }
                            mode.setOwnText(context, text)
                            ownVersion++
                            round++
                        }
                    }) { Text("开始") }
                }
            } else {
                Text(when {
                    current == null -> "正在出题…"
                    current.label.isEmpty() -> "请滑出 $prompt"
                    else -> "请滑出 $prompt（${current.label}）"
                }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
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
