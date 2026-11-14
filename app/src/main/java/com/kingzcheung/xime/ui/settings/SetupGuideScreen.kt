package com.kingzcheung.xime.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.ui.theme.CyMotion
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.FuzzyHabit
import com.kingzcheung.xime.model.InputPlan
import com.kingzcheung.xime.model.KeyboardWay
import com.kingzcheung.xime.model.SetupAnswers
import com.kingzcheung.xime.model.SetupFacts
import com.kingzcheung.xime.model.SetupGuide
import com.kingzcheung.xime.model.SetupGuidePlanner
import com.kingzcheung.xime.model.SetupItem
import com.kingzcheung.xime.model.SetupMore
import com.kingzcheung.xime.model.SetupMoreChoices
import com.kingzcheung.xime.model.SetupMoreFacts
import com.kingzcheung.xime.model.TextSizeChoice
import com.kingzcheung.xime.model.SetupPlan
import com.kingzcheung.xime.model.SetupSource
import com.kingzcheung.xime.model.TypingWay
import com.kingzcheung.xime.model.VoiceUse
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按设备推荐配置：首次设置完成后出现一次，之后从设置首页「配置选择」进入。
 * 先问打字习惯（全拼／双拼哪一种／五笔、26 键／14 键／九键、分不清的读音）和用法（语音、字幕），再按设备情况给出
 * 打字方式要改的几项和要留、要下载、可以删的模型，全部用白话说明；只有点了“按推荐设置”才改设置和删文件。
 *
 * 引导页原则（用户 10-30）：字要大，选项卡要大，选中的那个一目了然（底色、粗边框、打勾三样一起变），
 * 过渡放慢到看得清（[CyMotion.GUIDE]），不靠小圆点或开关位置让人猜选了哪个。
 */
@Composable
fun SetupGuideScreen(firstRun: Boolean, onDone: () -> Unit, onOpenPerformance: (() -> Unit)? = null,
    /** 「更多配置」里的几行跳转（剪贴板同步、键盘尺寸、性能与省电）；不给就不列。 */
    onOpenRoute: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 从这页点进「性能与省电」等页面再返回时，这个界面会重新组合，remember 的回答全没了（用户 11-12）。
    // 回答、要删的模型、开关和完成页存在跟着这一页走的 ViewModel 里，回来照原样接着显示。
    val draft: SetupGuideDraft = viewModel()
    var facts by remember { mutableStateOf(draft.facts) }
    var answers by remember { mutableStateOf(draft.answers) }
    var applying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(draft.result) }
    var allowMetered by remember { mutableStateOf(draft.allowMetered) }
    var hideDoublePinyin by remember { mutableStateOf(draft.hideDoublePinyin) }
    // 「更多配置」：从跳转页回来仍停在这一页；回答每次打开都从现在的设置读起。
    var showMore by rememberSaveable { mutableStateOf(false) }
    var moreFacts by remember { mutableStateOf<SetupMoreFacts?>(null) }
    var moreAnswers by remember { mutableStateOf<SetupMoreChoices?>(null) }
    var moreResult by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(showMore) {
        if (!showMore) return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { SetupGuide.readMore(context) }
        moreFacts = read
        moreAnswers = read.current
        moreResult = null
    }
    LaunchedEffect(Unit) {
        val read = withContext(Dispatchers.IO) { SetupGuide.readFacts(context) }
        facts = read
        // 回来时设备情况重新读（期间可能下好了模型），已经选过的回答不换成推荐值。
        if (answers == null) answers = SetupGuidePlanner.suggest(read)
    }
    val plan = remember(facts, answers) {
        val f = facts; val a = answers
        if (f != null && a != null) SetupGuidePlanner.plan(f, a) else null
    }
    // 默认全部勾上“删掉”；换了回答，可删的那几项变了就重新全选；没变（如从别的页面回来）留着原来的勾选。
    val removable = plan?.remove?.map { it.id }?.toSet().orEmpty()
    var removeIds by remember(removable) { mutableStateOf(draft.removeIdsFor(removable) ?: removable) }
    SideEffect {
        draft.facts = facts
        draft.answers = answers
        draft.result = result
        draft.allowMetered = allowMetered
        draft.hideDoublePinyin = hideDoublePinyin
        draft.keepRemoveIds(removable, removeIds)
    }
    if (showMore) {
        BackHandler { showMore = false }
        // 改了的先存下，再去别的页面，回来不丢。
        fun applyThen(next: () -> Unit) {
            val from = moreFacts?.current; val to = moreAnswers
            if (from == null || to == null || from == to) { next(); return }
            scope.launch {
                withContext(Dispatchers.IO) { SetupGuide.applyMore(context, from, to) }
                next()
            }
        }
        SetupMoreContent(
            facts = moreFacts, answers = moreAnswers, result = moreResult,
            onAnswers = { moreAnswers = it },
            onBack = { showMore = false },
            onSkip = onDone,
            onOpenRoute = onOpenRoute?.let { open -> { route: String -> applyThen { open(route) } } },
            onApply = {
                val from = moreFacts?.current; val to = moreAnswers
                if (from == null || to == null || from == to) onDone()
                else scope.launch {
                    moreResult = withContext(Dispatchers.IO) { SetupGuide.applyMore(context, from, to) }
                }
            },
            onDone = onDone,
        )
        return
    }
    SetupGuideContent(
        facts = facts, plan = plan, removeIds = removeIds, allowMetered = allowMetered,
        applying = applying, result = result, firstRun = firstRun,
        hideDoublePinyin = hideDoublePinyin, onHideDoublePinyin = { hideDoublePinyin = it },
        onOpenPerformance = onOpenPerformance,
        onAnswers = { answers = it },
        onToggleRemove = { id, on -> removeIds = if (on) removeIds + id else removeIds - id },
        onAllowMetered = { allowMetered = it },
        onApply = {
            plan?.let { current ->
                applying = true
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        SetupGuide.apply(context, current, removeIds, allowMetered, hideDoublePinyin)
                    }
                    applying = false
                }
            }
        },
        onSkip = { SetupGuide.markShown(context); onDone() },
        onDone = onDone,
        // 没按推荐设置就直接去「更多配置」，也算看过这一页，下次不再自动弹出。
        onOpenMore = { SetupGuide.markShown(context); showMore = true },
    )
}

/**
 * 推荐配置页上已经选了的东西：在设置里跟着这一页的返回栈项，在首次设置里跟着 Activity。
 * 去「性能与省电」「更多配置」里的跳转页再回来不丢；关掉这一页（返回栈弹出）才清掉，下次进来照推荐重新选。
 */
class SetupGuideDraft : ViewModel() {
    var facts: SetupFacts? = null
    var answers: SetupAnswers? = null
    var result: SetupGuide.Result? = null
    var allowMetered: Boolean = false
    var hideDoublePinyin: Boolean = SetupGuidePlanner.HIDE_OTHER_DOUBLE_PINYIN_BY_DEFAULT
    private var removable: Set<String>? = null
    private var removeIds: Set<String> = emptySet()

    /** 可删的还是同一批时返回上次的勾选。 */
    fun removeIdsFor(removable: Set<String>): Set<String>? = removeIds.takeIf { this.removable == removable }
    fun keepRemoveIds(removable: Set<String>, removeIds: Set<String>) {
        this.removable = removable
        this.removeIds = removeIds
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupGuideContent(
    facts: SetupFacts?,
    plan: SetupPlan?,
    removeIds: Set<String>,
    allowMetered: Boolean,
    applying: Boolean,
    result: SetupGuide.Result?,
    firstRun: Boolean,
    onAnswers: (SetupAnswers) -> Unit,
    onToggleRemove: (String, Boolean) -> Unit,
    onAllowMetered: (Boolean) -> Unit,
    onApply: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    hideDoublePinyin: Boolean = SetupGuidePlanner.HIDE_OTHER_DOUBLE_PINYIN_BY_DEFAULT,
    onHideDoublePinyin: (Boolean) -> Unit = {},
    /** 首次设置时不给：先走完这一页。 */
    onOpenPerformance: (() -> Unit)? = null,
    onOpenMore: (() -> Unit)? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("按设备推荐配置", style = GuideText.heading()) },
                navigationIcon = {
                    if (!firstRun) IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { if (firstRun && result == null) TextButton(onClick = onSkip) { Text("以后再说", style = GuideText.body()) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("告诉 CyIME 你平时怎么打字、打算怎么用，它会照你的习惯设好打字方式和键盘，" +
                "再看这台设备的情况推荐留哪些模型、删掉哪些用不到的，让应用少占空间。",
                style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (facts == null || plan == null) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            } else if (result != null) {
                DeviceSection(facts, onOpenPerformance)
                ResultSection(result, onDone, onOpenMore)
                Spacer(Modifier.height(24.dp))
            } else {
                // 先问怎么打字（打开就看到），设备情况放到模型推荐前面。
                AnswerSection(facts, plan.answers, onAnswers)
                InputSection(plan.input, hideDoublePinyin, onHideDoublePinyin)
                DeviceSection(facts, onOpenPerformance)
                KeepSection(plan.keep, facts.device)
                if (plan.remove.isNotEmpty()) RemoveSection(plan.remove, removeIds, onToggleRemove)
                SummarySection(facts, plan, removeIds)
                if (facts.metered && facts.online && plan.downloadBytes > 0) GuideCard {
                    GuideSwitchRow(title = "现在用流量下载",
                        subtitle = "要下载 ${SetupGuidePlanner.mbText(plan.downloadBytes)}；不打开就等连上 Wi-Fi 自动下载",
                        checked = allowMetered, tag = "setup-metered", onCheckedChange = onAllowMetered)
                }
                Button(onClick = onApply, enabled = !applying,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-guide-apply")) {
                    if (applying) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("正在整理…", style = GuideText.optionTitle())
                    } else Text("按推荐设置", style = GuideText.optionTitle())
                }
                Text("以后想换，随时可以在 设置 → 配置选择 → 按设备推荐配置 重新选；删掉的模型在用到时会提示下载。",
                    style = GuideText.note(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                onOpenMore?.let { MoreEntry(it) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 引导页字号：比普通设置页大一档。 */
private object GuideText {
    @Composable fun heading(): TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    @Composable fun optionTitle(): TextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold)
    @Composable fun body(): TextStyle = MaterialTheme.typography.bodyLarge
    @Composable fun note(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)
}

@Composable
private fun GuideHeading(text: String) {
    Text(text, style = GuideText.heading(), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun GuideCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(CyMotion.GUIDE, easing = CyMotion.Standard)), content = content)
    }
}

@Composable
private fun DeviceSection(facts: SetupFacts, onOpenPerformance: (() -> Unit)?) {
    GuideHeading("这台${facts.device}")
    GuideCard {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 和「性能与省电」的“本机配置推荐”同一个判断、同一个叫法。
            Text(tierNames.getValue(facts.performanceTier) +
                if (facts.performanceTier == com.kingzcheung.xime.runtime.adaptation.PerformanceTier.LOW) "，优先省内存" else "",
                style = GuideText.optionTitle())
            val hardware = buildList {
                facts.memoryBytes?.let { add("内存 ${(it + SetupGuidePlanner.GIB / 2) / SetupGuidePlanner.GIB} GB") }
                facts.cores?.let { add("$it 核") }
                facts.maxCpuKHz?.let { add("最高 ${"%.1f".format(it / 1_000_000.0)} GHz") }
            }
            if (hardware.isNotEmpty()) FactLine(hardware.joinToString("，"))
            // 档位按这个定，主频、核数只是显示（和「性能与省电」的「CPU 实测」同一句）。
            FactLine("CPU 实测 " + cpuLine(facts.cpu, null, running = false))
            facts.availMemoryBytes?.let { FactLine("现在可用内存 ${SetupGuidePlanner.gbText(it)}") }
            facts.soc?.let { FactLine("处理器 $it") }
            facts.freeBytes?.let { FactLine("剩余空间 ${SetupGuidePlanner.gbText(it)}") }
            FactLine("网络 " + when { !facts.online -> "未联网"; facts.metered -> "移动数据"; else -> "Wi-Fi" })
            facts.language?.let { FactLine("系统语言 $it") }
        }
        onOpenPerformance?.let { open ->
            GuideDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("setup-open-performance")
                .clickable(role = Role.Button, onClick = open).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("性能与省电", style = GuideText.optionTitle())
                    Text("按键动效、炫光和语音模型常驻多久", style = GuideText.body(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FactLine(text: String) {
    Text(text, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 一个大选项卡：至少 76dp 高，整张卡可点。选中时底色、2dp 主题色边框和右侧打勾一起慢慢变过去，
 * 没选中的是浅底、细边框、空心圆；读屏按单选按钮报“已选中”。
 */
@Composable
private fun GuideOption(selected: Boolean, title: String, subtitle: String, tag: String, multi: Boolean = false,
    extra: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-option-container")
    val edge by animateColorAsState(if (selected) colors.primary else colors.outlineVariant,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-option-edge")
    val edgeWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(CyMotion.GUIDE, easing = CyMotion.Standard),
        label = "guide-option-edge-width")
    val content by animateColorAsState(if (selected) colors.onPrimaryContainer else colors.onSurface,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-option-content")
    val shape = RoundedCornerShape(16.dp)
    Surface(shape = shape, color = container, border = BorderStroke(edgeWidth, edge),
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag(tag).clip(shape)
            .then(if (multi) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick))) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = GuideText.optionTitle(), color = content)
                if (subtitle.isNotEmpty()) Text(subtitle, style = GuideText.body(), color = content.copy(alpha = 0.8f))
                extra?.invoke(content)
            }
            Spacer(Modifier.width(16.dp))
            CheckBadge(selected)
        }
    }
}

@Composable
private fun CheckBadge(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primary else Color.Transparent,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-check-fill")
    val ring by animateColorAsState(if (selected) colors.primary else colors.outline,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-check-ring")
    Box(Modifier.size(32.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center) {
        AnimatedVisibility(selected,
            enter = scaleIn(tween(CyMotion.GUIDE, easing = CyMotion.Enter), initialScale = 0.4f) + fadeIn(tween(CyMotion.GUIDE)),
            exit = scaleOut(tween(CyMotion.GUIDE, easing = CyMotion.Exit), targetScale = 0.4f) + fadeOut(tween(CyMotion.GUIDE))) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

/** 一个问题：标题（可带一段说明）＋竖排的大选项卡（不并排，手机上每张卡都够宽）。 */
@Composable
private fun <T> GuideQuestion(title: String, options: List<Triple<T, String, String>>, selected: T, tagPrefix: String,
    note: String? = null, onSelect: (T) -> Unit) {
    GuideHeading(title)
    note?.let { Text(it, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, optionTitle, subtitle) ->
            GuideOption(selected == value, optionTitle, subtitle, "$tagPrefix:$value") { onSelect(value) }
        }
    }
}

@Composable
private fun AnswerSection(facts: SetupFacts, answers: SetupAnswers, onAnswers: (SetupAnswers) -> Unit) {
    GuideQuestion("打中文用哪种方式？", listOfNotNull(
        Triple(TypingWay.FULL_PINYIN, "全拼", "拼音全部打出来，如“中国”打 zhongguo；不用记键位"),
        Triple(TypingWay.DOUBLE_PINYIN, "双拼", "每个字只按两个键，如小鹤双拼“中国”打 vsgo；打得快，要先记键位"),
        Triple(TypingWay.WUBI, "五笔", "按字形拆成字根打，不看读音；" + when {
            facts.typing == TypingWay.WUBI -> "现在就在用五笔"
            facts.wubiInstalled && facts.wubi98Installed -> "五笔86、98 都已在${facts.device}上"
            facts.wubiInstalled -> "五笔86 已在${facts.device}上"
            facts.wubi98Installed -> "五笔98 已在${facts.device}上"
            else -> "要下载五笔方案，1～2 MB"
        }),
        Triple(TypingWay.KEEP, "保持现在的${facts.typingName}", "不改打字方式").takeIf { facts.typing == TypingWay.KEEP },
    ), answers.typing, "setup-typing") { onAnswers(answers.copy(typing = it)) }
    // 从别的打法改五笔才问哪一种（默认 86，用户 11-12）；已经在用五笔的不动。五笔只有 26 键，不问键盘，写一句为什么。
    if (answers.typing == TypingWay.WUBI && facts.typing != TypingWay.WUBI) {
        fun where(installed: Boolean, mb: Int) = if (installed) "已在${facts.device}上" else "要下载，约 $mb MB"
        GuideQuestion("用哪一种五笔？", listOf(
            Triple(SetupGuidePlanner.WUBI, "五笔86", "用的人最多的一版；" + where(facts.wubiInstalled, 2)),
            Triple(SetupGuidePlanner.WUBI98, "五笔98", "另一版字根，方案带繁体字；" + where(facts.wubi98Installed, 1)),
        ), answers.wubi, "setup-wubi",
            note = "五笔只用 26 键打：14 键、九键一个键上有几组字根，打不了五笔。") { onAnswers(answers.copy(wubi = it)) }
    }
    if (answers.typing == TypingWay.DOUBLE_PINYIN && facts.doublePinyinChoices.isNotEmpty()) {
        GuideQuestion("用哪一种双拼？", facts.doublePinyinChoices.map { choice ->
            Triple(choice.id, choice.name, "“双拼”两个字打 ${choice.example}" + if (choice.builtin) "" else "(你自己建的)")
        }, answers.doublePinyin, "setup-double-pinyin") { onAnswers(answers.copy(doublePinyin = it)) }
    }
    val keyboards = SetupGuidePlanner.keyboards(facts, answers)
    val keyboard = SetupGuidePlanner.keyboardOf(facts, answers)
    if (keyboards.isNotEmpty()) {
        val double = answers.typing == TypingWay.DOUBLE_PINYIN
        GuideQuestion("用哪种键盘打？", keyboards.map { way ->
            Triple(way, if (way == KeyboardWay.KEEP) "保持现在的${facts.keyboardName.ifEmpty { "键盘" }}" else way.title, when (way) {
                KeyboardWay.QWERTY -> "一个字母一个键，和电脑键盘一样" + if (double) "；每个字按两个键" else ""
                KeyboardWay.K14 -> "两个字母合用一个键，键更大、更好按；点按自动组词，左右滑选单个字母"
                KeyboardWay.T9 -> if (double) "按双拼键所在的数字键，每个字按两下；适合单手" else "按字母所在的数字键，像老手机；适合单手"
                KeyboardWay.KEEP -> "不换键盘"
            })
        }, keyboard, "setup-keyboard",
            note = "以后随时可以在键盘上的「输入方式」里换。".takeIf { keyboards.size > 1 }
                ?: "${facts.doublePinyinChoices.firstOrNull { it.id == answers.doublePinyin }?.name ?: "这种双拼"}只有 26 键(ing 在分号键上，14 键、九键没有分号键)。") {
            onAnswers(answers.copy(keyboard = it))
        }
    }
    if (answers.typing == TypingWay.FULL_PINYIN) {
        // 多选：可以同时分不清几组；“都分得清”和其他互斥，让“一个都不选”也是看得见的选择。
        GuideHeading("有分不清的读音吗？")
        Text("选上的打开模糊音，两边的读音都能打出对方的字。全拼 26 键、14 键和九键都可用。",
            style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GuideOption(answers.fuzzy.isEmpty(), "都分得清", "不打开模糊音，候选最准", "setup-fuzzy:none") {
                onAnswers(answers.copy(fuzzy = emptySet()))
            }
            FuzzyHabit.entries.forEach { habit ->
                GuideOption(habit in answers.fuzzy, habit.title, habit.example, "setup-fuzzy:${habit.name}", multi = true) {
                    onAnswers(answers.copy(fuzzy = if (habit in answers.fuzzy) answers.fuzzy - habit else answers.fuzzy + habit))
                }
            }
        }
    }
    // 选了九键就不再问一遍：全拼九键要用九键整句组词模型（自动留下），双拼九键只用双拼词库、用不到它。
    if (keyboard != KeyboardWay.T9) GuideQuestion("也用九键打字吗？", listOf(
        Triple(true, "用九键", "九键按整句常用说法组词，长句更容易一次打对；需要九键整句组词模型"),
        Triple(false, "不用九键", "只用 26 键、14 键或手写，不留九键模型"),
    ), answers.t9, "setup-t9") { onAnswers(answers.copy(t9 = it)) }
    GuideQuestion("语音输入打算怎么用？", listOf(
        Triple(VoiceUse.OFTEN, "经常用，想边说边看到字", "说话时字就跟着出来，停下后再自动改错、加标点"),
        Triple(VoiceUse.SOMETIMES, "偶尔用，说完出字就行", "说完一句再出字，错字少、带标点，占的空间少一半"),
        Triple(VoiceUse.NEVER, "不用语音", "关掉语音输入，不留语音模型(字幕要用的除外)"),
    ), answers.voice, "setup-voice") { onAnswers(answers.copy(voice = it)) }
    val speaker = facts.sizes[BuiltinModelCatalog.SPEAKER_MODEL]?.takeIf { it > 0 }?.let { "(${SetupGuidePlanner.mbText(it)})" }.orEmpty()
    val icon = when {
        !facts.captionIconChangeable -> "桌面上的「字幕」图标保留"
        facts.usesCaption -> "桌面上已有「字幕」图标，点一下就打开"
        else -> "桌面上加一个「字幕」图标(系统会问一次要不要添加)，点一下就打开"
    }
    GuideQuestion("要用「字幕」吗？", listOf(
        Triple(true, "用字幕", "$icon；键盘菜单里也能进。会留下分清谁在说话的模型$speaker"),
        Triple(false, "不用字幕", (if (facts.captionIconChangeable) "桌面上不放「字幕」图标(已有的会变灰，可长按移除)，" else "") +
            "删掉分清谁在说话的模型。以后想用，在 设置 → 扩展 → 插件 里装「字幕」"),
    ), answers.caption, "setup-caption",
        note = "「字幕」是 CyIME 里单独的一个页面：把身边的人说的话实时变成大字显示在屏幕上，还能分清是谁在说。" +
            "适合开会、上课，或和听力不好的人当面说话。用不用都不影响打字和语音输入。") {
        onAnswers(answers.copy(caption = it))
    }
    // 用户 11-04：有人被几百兆吓退、内存不够。选了就只留必需的；默认照上次的回答，没答过是“没有”。
    GuideQuestion("有内存焦虑吗？", listOf(
        Triple(true, "有，能省就省", "只留必需的模型，九键用精简版(首选略差)，语音空闲 ${SetupGuidePlanner.SAVER_VOICE_IDLE_MINUTES} 分钟就从内存卸掉"),
        Triple(false, "没有", "按这台${facts.device}的情况推荐"),
    ), answers.memorySaver, "setup-memory-saver") { onAnswers(answers.copy(memorySaver = it)) }
    // 用户 11-12：选项太少。答“有”时再列现在开着、关掉能省内存的功能，多选，默认一个不选，点了才关。
    if (answers.memorySaver && facts.memoryExtras.isNotEmpty()) {
        Text("还可以关掉这些(现在都开着，点一下选上，按推荐设置时关掉)：",
            style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            facts.memoryExtras.forEach { extra ->
                val selected = extra.id in answers.memoryOff
                GuideOption(selected, "关掉${extra.title}",
                    extra.loss + (extra.memoryMb?.let { "；内存 +${it.roundToInt()} MB" } ?: ""),
                    "setup-memory-off:${extra.id}", multi = true) {
                    onAnswers(answers.copy(memoryOff = if (selected) answers.memoryOff - extra.id else answers.memoryOff + extra.id))
                }
            }
        }
    }
    if (answers.memorySaver) Text("实际占了多少，可以在 设置 → 关于 → 存储空间 最上面的「运行内存」看。",
        style = GuideText.note(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 打字方式这一块要改的：每项一句；只有主动打开开关才收起其他双拼。 */
@Composable
private fun InputSection(input: InputPlan, hideDoublePinyin: Boolean, onHide: (Boolean) -> Unit) {
    if (input.lines.isEmpty() && input.hideLine == null) return
    GuideHeading("打字方式会这样改")
    GuideCard {
        if (input.lines.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            input.lines.forEach { Text(it, style = GuideText.body()) }
        }
        input.hideLine?.let { line ->
            if (input.lines.isNotEmpty()) GuideDivider()
            GuideSwitchRow(title = "方案栏只留用得到的", subtitle = line, checked = hideDoublePinyin,
                tag = "setup-hide-double-pinyin", onCheckedChange = onHide)
        }
    }
}

@Composable
private fun GuideDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun KeepSection(items: List<SetupItem>, device: String) {
    GuideHeading("推荐留下的")
    GuideCard {
        items.forEachIndexed { index, item ->
            if (index > 0) GuideDivider()
            Row(Modifier.fillMaxWidth().testTag("setup-keep:${item.id}").padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = GuideText.optionTitle())
                    Text(item.reason, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.modelName.isNotEmpty()) Text("设置里叫「${item.modelName}」", style = GuideText.note(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
                Spacer(Modifier.width(12.dp))
                val size = SetupGuidePlanner.mbText(item.bytes)
                Text(when (item.source) {
                    SetupSource.ON_PHONE -> "已在${device}上\n$size"
                    SetupSource.IN_PACKAGE -> "从安装包取出\n$size"
                    SetupSource.DOWNLOAD -> "要下载\n$size"
                }, style = GuideText.note(),
                    color = if (item.source == SetupSource.DOWNLOAD) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End)
            }
        }
    }
}

/** 标题＋说明＋开关的整行（设置页规则），字号放大到引导页这一档；说明和开关之间留 12dp。 */
@Composable
private fun GuideSwitchRow(title: String, subtitle: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag(tag)
        .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = GuideText.optionTitle())
            Text(subtitle, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RemoveSection(items: List<SetupItem>, removeIds: Set<String>, onToggle: (String, Boolean) -> Unit) {
    GuideHeading("可以删掉的")
    GuideCard {
        items.forEachIndexed { index, item ->
            if (index > 0) GuideDivider()
            val saving = SetupGuidePlanner.mbText(item.bytes)
            GuideSwitchRow(title = item.title,
                subtitle = buildString {
                    append(item.reason)
                    append(if (item.source == SetupSource.ON_PHONE) "删掉省 $saving。" else "不从安装包解压，省 $saving。")
                    if (item.modelName.isNotEmpty()) append("设置里叫「${item.modelName}」。")
                },
                checked = item.id in removeIds, tag = "setup-remove:${item.id}", onCheckedChange = { onToggle(item.id, it) })
        }
    }
}

@Composable
private fun SummarySection(facts: SetupFacts, plan: SetupPlan, removeIds: Set<String>) {
    val now = facts.installed.values.sum()
    val removed = plan.remove.filter { it.id in removeIds && it.source == SetupSource.ON_PHONE }.sumOf { it.bytes }
    val download = plan.keep.filter { it.source == SetupSource.DOWNLOAD }.sumOf { it.bytes }
    val unpack = plan.keep.filter { it.source == SetupSource.IN_PACKAGE }.sumOf { it.bytes }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SetupGuidePlanner.sizeSummary(now, download, unpack, removed), style = GuideText.optionTitle())
        plan.notes.forEach {
            Text(it, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultSection(result: SetupGuide.Result, onDone: () -> Unit, onOpenMore: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✓ 已按推荐设置好", style = GuideText.heading(), color = MaterialTheme.colorScheme.onPrimaryContainer)
            val lines = buildList {
                if (result.removed > 0) add("删掉了 ${result.removed} 个用不到的模型，空出 ${SetupGuidePlanner.mbText(result.freedBytes)}。")
                // 写明是哪些模型在下载（语音、联想…），不只写多少 MB。
                if (result.downloading > 0) add(if (result.waitingForWifi)
                    "${SetupGuidePlanner.kindsText(result.downloadingIds)}还要下载 ${SetupGuidePlanner.mbText(result.downloading)}，连上 Wi-Fi 后自动下载。"
                    else "${SetupGuidePlanner.kindsText(result.downloadingIds)}正在后台下载(${SetupGuidePlanner.mbText(result.downloading)})，下好就能用。")
                if (result.unpackingIds.isNotEmpty())
                    add("${SetupGuidePlanner.kindsText(result.unpackingIds)}正在从安装包取出，取好就能用。")
                if (result.failed.isNotEmpty()) add("这些没删成：${result.failed.joinToString("、")}，可以在 扩展 → 已安装的模型 里再删。")
                addAll(result.inputDone)
                addAll(result.inputFailed)
            }
            lines.forEach { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer, style = GuideText.body()) }
        }
    }
    onOpenMore?.let { MoreEntry(it) }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("完成", style = GuideText.optionTitle()) }
}

/** 推荐配置页底部、完成页上的入口：主题色边框的整行卡片，和完成按钮分开，看得出是可选的。 */
@Composable
private fun MoreEntry(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("setup-open-more").clip(shape)
            .clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("更多配置", style = GuideText.optionTitle(), color = MaterialTheme.colorScheme.primary)
                Text("滑行输入、角标滑动还是长按、上下滑方向、候选栏取消按钮、长按空格、删除键、字大小、声音振动等。可以不设",
                    style = GuideText.body(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 「更多配置」：大家最常问的几项，一题一组大选项卡。每题先选着现在的设置，不点就不改；点「好了」只改点过的项，
 * 「跳过」什么都不改。说不清的几样（剪贴板同步、键盘尺寸、性能与省电）只放跳转行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupMoreContent(
    facts: SetupMoreFacts?,
    answers: SetupMoreChoices?,
    result: List<String>?,
    onAnswers: (SetupMoreChoices) -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onApply: () -> Unit,
    onDone: () -> Unit,
    onOpenRoute: ((String) -> Unit)? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("更多配置", style = GuideText.heading()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = { if (result == null) TextButton(onClick = onSkip, modifier = Modifier.testTag("setup-more-skip")) {
                    Text("跳过", style = GuideText.body())
                } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (facts == null || answers == null) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            } else if (result != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓ 已改好", style = GuideText.heading(), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        result.forEach { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer, style = GuideText.body()) }
                    }
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-more-done")) {
                    Text("完成", style = GuideText.optionTitle())
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Text("选中的是现在的设置，不点就不改。", style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoreQuestions(facts, answers, onAnswers)
                if (onOpenRoute != null) {
                    GuideHeading("要到单独页面设的")
                    GuideCard {
                        listOf(
                            Triple(SettingsRoutes.ClipboardSync, "剪贴板同步", "这台设备和电脑、平板之间同步复制的内容"),
                            Triple(SettingsRoutes.KeyboardResize, "键盘尺寸与位置", "拖动调整键盘的高度、宽度和位置"),
                            Triple(SettingsRoutes.Performance, "性能与省电", "按键动效、炫光和语音模型常驻多久"),
                        ).forEachIndexed { index, (route, title, subtitle) ->
                            if (index > 0) GuideDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("setup-more-open:$route")
                                .clickable(role = Role.Button) { onOpenRoute(route) }.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(title, style = GuideText.optionTitle())
                                    Text(subtitle, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val changed = facts.current != answers
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-more-apply")) {
                    Text(if (changed) "好了" else "不改了", style = GuideText.optionTitle())
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MoreQuestions(facts: SetupMoreFacts, answers: SetupMoreChoices, onAnswers: (SetupMoreChoices) -> Unit) {
    // 用户 11-12：滑行、角标滑动还是长按、上下滑方向、候选栏取消按钮问的人最多，放最前面。
    // 滑行先问：开了滑行，字母键的滑动都用来滑词，角标只能长按，下面那题跟着变。
    val glideLocked = facts.glideLock != null && answers.spaceHold != com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.CURSOR
    if (glideLocked) {
        GuideHeading("要滑行输入吗？")
        Text("${facts.glideLock}：手指在键盘上拖动已经用来移动光标。下面「长按空格」选「移动光标」，或在 设置 → 输入 → 按键与手势 里关掉它，才能开滑行。",
            style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else GuideQuestion("要滑行输入吗？", SetupMore.glideChoices(facts.current.glide).map { choice ->
        Triple(choice, choice.title, when (choice) {
            com.kingzcheung.xime.model.GlideChoice.OFF -> "一个键一个键点着打"
            com.kingzcheung.xime.model.GlideChoice.ENGLISH -> "英文 26 键手指划过字母出整词"
            com.kingzcheung.xime.model.GlideChoice.CHINESE -> "中文全拼、双拼 26 键划一个词的拼音"
            com.kingzcheung.xime.model.GlideChoice.BOTH -> "英文划出整词，中文全拼、双拼 26 键划一个词的拼音"
        } + if (choice != com.kingzcheung.xime.model.GlideChoice.OFF) "；字母键的滑动都用来滑词，角标改成长按输入" else "")
    }, answers.glide, "setup-more-glide") { onAnswers(answers.copy(glide = it)) }

    val upper = if (answers.reverseSwipe) "上滑" else "下滑"
    val lower = if (answers.reverseSwipe) "下滑" else "上滑"
    if (SetupMore.cornerLockedByGlide(answers.glide)) {
        GuideHeading("按键角标的符号和数字")
        Text("开着滑行，字母键的滑动都用来滑词：按住字母键输入上方角标，下方角标不再显示。",
            style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else GuideQuestion("按键角标的符号和数字怎么输入？", com.kingzcheung.xime.ui.keyboard.CornerInputChoice.entries.map { choice ->
        Triple(choice, choice.label, when (choice) {
            com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE -> "${upper}出上角标，${lower}出下角标；长按没有反应"
            com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE_BUBBLE -> "${upper}出上角标，${lower}出下角标；长按弹出这个字母的大写和带声调的写法"
            com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS -> "按住一下出上角标，${lower}出下角标；九键长按出数字"
        })
    }, answers.corner, "setup-more-corner") { onAnswers(answers.copy(corner = it)) }

    GuideQuestion("上下滑的方向", listOf(
        Triple(false, "下滑出上角标", "上滑出下角标；删除键" + if (facts.reverseDeleteSwipe) "上滑清空、下滑撤回" else "下滑清空、上滑撤回"),
        Triple(true, "上滑出上角标", "下滑出下角标；删除键" + if (facts.reverseDeleteSwipe) "下滑清空、上滑撤回" else "上滑清空、下滑撤回"),
    ), answers.reverseSwipe, "setup-more-reverse",
        note = "就是设置里的「反转上下滑方向」。") { onAnswers(answers.copy(reverseSwipe = it)) }

    GuideQuestion("候选栏左边要取消按钮吗？", listOf(
        Triple(true, "要", "打字时候选栏最左边有个左箭头，点它取消当前输入"),
        Triple(false, "不要", "候选词从最左边开始，能多放一个词"),
    ), answers.cancelButton, "setup-more-cancel") { onAnswers(answers.copy(cancelButton = it)) }

    GuideQuestion("长按空格做什么？", com.kingzcheung.xime.ui.keyboard.CursorChoices.spaceHoldChoices.map { action ->
        Triple(action, action.label, when (action) {
            com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.CURSOR -> "按住空格左右滑，光标跟着走"
            com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.REPEAT -> "按住一直出空格"
            com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.VOICE_TOGGLE -> "按住空格说话，松手上屏" +
                if (facts.sttEnabled) "" else "；「语音转文本」关着，要先打开"
        })
    }, answers.spaceHold, "setup-more-space") { onAnswers(answers.copy(spaceHold = it)) }

    // 智能联想关着就没有联想词，这题没意义。
    if (facts.predictionEnabled) GuideQuestion("打完字出了联想词，按删除键", listOf(
        Triple(false, "先收起联想词", "第二下才删字"),
        Triple(true, "退格直接删字", "第一下就删字"),
    ), answers.deleteSkipsAssociation, "setup-more-delete") { onAnswers(answers.copy(deleteSkipsAssociation = it)) }

    // 跟着上面「上下滑的方向」那题走；删除键单独反向开着时再反过来。
    val deleteReversed = answers.reverseSwipe != facts.reverseDeleteSwipe
    val clear = if (deleteReversed) "上滑" else "下滑"
    val undo = if (deleteReversed) "下滑" else "上滑"
    GuideQuestion("删除键${clear}清空时", listOf(
        Triple(false, "清空整个输入框", "光标前后的字都清；清完立刻${undo}能撤回"),
        Triple(true, "只清空光标前的内容", "光标后面的字留着"),
    ), answers.clearBeforeCursorOnly, "setup-more-clear") { onAnswers(answers.copy(clearBeforeCursorOnly = it)) }

    GuideHeading("字大小")
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SetupMore.textSizeChoices(facts.current.textSize).forEach { size ->
            val candidateSp = if (size == TextSizeChoice.CUSTOM) facts.candidateSp else size.candidateSp
            val keyPercent = if (size == TextSizeChoice.CUSTOM) facts.keyScalePercent else (size.keyScale * 100).toInt()
            GuideOption(answers.textSize == size, size.title, "候选字 $candidateSp，按键字号 $keyPercent%", "setup-more-text:$size",
                extra = { color -> Text("中国 中 种", fontSize = candidateSp.sp, color = color) }) {
                onAnswers(answers.copy(textSize = size))
            }
        }
    }

    GuideQuestion("按键声音和振动", SetupMore.feedbackChoices(facts.current.feedback).map { Triple(it, it.title, "") },
        answers.feedback, "setup-more-feedback") { onAnswers(answers.copy(feedback = it)) }

    GuideQuestion("要玻璃效果吗？", listOf(
        Triple(false, "不要", "键盘不透明"),
        Triple(true, "玻璃效果", "键盘半透明，透出后面的内容" + (facts.glassCost?.let { "。$it" } ?: "")),
    ), answers.glass, "setup-more-glass") { onAnswers(answers.copy(glass = it)) }

    // 语音转文本关着时不问标点。
    if (facts.sttEnabled) GuideQuestion("语音输入要自动加标点吗？", listOf(
        Triple(false, "不自动加", "停顿处留空格；说“逗号”“句号”照样出标点"),
        Triple(true, "自动加标点", "保留识别模型加上的标点"),
    ), answers.autoPunctuation, "setup-more-punctuation") { onAnswers(answers.copy(autoPunctuation = it)) }
}
