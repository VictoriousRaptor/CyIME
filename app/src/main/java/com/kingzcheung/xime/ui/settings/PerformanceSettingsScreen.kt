package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Speed
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner
import com.kingzcheung.xime.runtime.adaptation.CpuMeasurement
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilities
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe
import com.kingzcheung.xime.runtime.adaptation.PerformanceTier
import com.kingzcheung.xime.runtime.adaptation.PerformanceTiers
import com.kingzcheung.xime.settings.PerformanceCosts
import com.kingzcheung.xime.settings.PerformanceProfile
import com.kingzcheung.xime.settings.PerformanceSettings
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val tierNames = mapOf(PerformanceTier.HIGH to "高配", PerformanceTier.MEDIUM to "中配", PerformanceTier.LOW to "低配")
/**
 * 每档说明：先写按键效果和档位关掉的几项，语音开着时再写语音模型怎么释放（语音关着时模型不加载，不写）。
 * 用户 11-03「档位要更强」：中配、低配还会关掉液态玻璃等，高配不替用户打开没开过的效果。
 */
private fun tierHint(tier: PerformanceTier, speech: Boolean): String = when (tier) {
    PerformanceTier.HIGH -> "动效和炫光全开，其他效果保持原样" + if (speech) "；语音模型常驻，二次校正空闲 30 分钟后释放" else ""
    PerformanceTier.MEDIUM -> "保留按键动效，关炫光和液态玻璃" + if (speech) "；语音空闲 5 分钟先释放二次校正，30 分钟后全部释放" else ""
    PerformanceTier.LOW -> "关动效、炫光、键缝动效、液态玻璃、点按气泡、滑行实时显示词、手写边写边识别" + if (speech) "；语音用完 1 分钟就释放" else ""
}

/** 开销页要的几样，都要读文件或偏好，在后台读。 */
private data class CostFacts(val on: Set<String>, val nineKey: Boolean, val t9Model: Boolean, val dictionaryWords: Int)

private fun readCostFacts(context: android.content.Context): CostFacts {
    val nineKey = PerformanceCosts.nineKey(context)
    // 已应用的导入词库一共多少词（词库页「应用更改」之后才算）。
    val words = runCatching {
        val state = com.kingzcheung.xime.settings.CellDictionaryManager.store(context).read()
        state.installed.filter { it.id in state.applied }.sumOf { it.count }
    }.getOrDefault(0)
    return CostFacts(PerformanceCosts.enabled(context), nineKey, nineKey && PerformanceCosts.t9ModelActive(context), words)
}
private fun idleLabel(minutes: Int) = if (minutes == 0) "永不" else "$minutes 分钟"

/** 「自动」那一行：选哪档，卡在内存或还没测时括号里写一句。 */
internal fun autoLine(verdict: PerformanceTiers.Verdict, memoryBytes: Long?): String {
    val reason = when (verdict.limit) {
        PerformanceTiers.Limit.UNMEASURED -> "CPU 未测"
        PerformanceTiers.Limit.MEMORY -> memoryBytes?.let {
            if (it < PerformanceTiers.LOW_MEMORY_BYTES) "内存不到 4 GB" else "内存不到 8 GB"
        }
        PerformanceTiers.Limit.CPU, PerformanceTiers.Limit.UNKNOWN -> null
    }
    return "按本机配置选：" + tierNames.getValue(verdict.tier) + (reason?.let { " ($it)" } ?: "")
}

private fun msText(ns: Long) = "%.1f ms".format(ns / 1_000_000.0)

/**
 * 「CPU 实测」那一行的说明：测出来的耗时，分档线定了时前面写算力够哪一档。
 * 没测成写原因；以前测过、这次重测没测成，保留上次的数，后面写这次为什么没测。
 */
internal fun cpuLine(measurement: CpuMeasurement?, skipped: CpuBenchmarkRunner.Skip?, running: Boolean,
    retested: Boolean = false): String {
    val why = when (skipped) {
        CpuBenchmarkRunner.Skip.KEYBOARD -> "键盘开着"
        CpuBenchmarkRunner.Skip.POWER_SAVE -> "省电模式开着"
        CpuBenchmarkRunner.Skip.THERMAL -> "设备发热"
        CpuBenchmarkRunner.Skip.UNSUPPORTED -> "本机不支持"
        null -> null
    }
    return when {
        running -> "测试中…"
        measurement != null -> listOfNotNull(PerformanceTiers.cpuTier(measurement)?.let { tierNames.getValue(it) },
            "单线程 ${msText(measurement.singleNs)}", "双线程 ${msText(measurement.dualNs)}",
            why?.let { "没重测：$it" } ?: "刚重测".takeIf { retested }).joinToString(" · ")
        else -> "未测" + (why?.let { "：$it" } ?: "")
    }
}

/** 点「重测」没测成时弹的一句（#268：以前没测成只是副标题不变，像点了没反应）。 */
internal fun cpuSkipToast(skipped: CpuBenchmarkRunner.Skip): String = when (skipped) {
    CpuBenchmarkRunner.Skip.KEYBOARD -> "键盘开着，收起键盘再点重测"
    CpuBenchmarkRunner.Skip.POWER_SAVE -> "省电模式开着，测出来会偏低，关掉后再点重测"
    CpuBenchmarkRunner.Skip.THERMAL -> "设备过热，系统在降频，凉下来再点重测"
    CpuBenchmarkRunner.Skip.UNSUPPORTED -> "本机测不了 CPU"
}

private const val CPU_RUNNING_MIN_MS = 800L

private val ladder = listOf(PerformanceTier.LOW, PerformanceTier.MEDIUM, PerformanceTier.HIGH)

/**
 * 性能与省电：一键选档；改任何一项自动变成“自定义”。
 * 和「按设备推荐配置」分工：这页随时调、马上生效（动效、语音模型常驻多久）；用哪些模型、删哪些在那一页，
 * 那里要下载、删文件，点了「按推荐设置」才改。两页互相有入口，设备档位用同一个判断。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettingsContent(onBack: () -> Unit, onOpenSetupGuide: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(PerformanceProfile.read(context)) }
    var auto by remember { mutableStateOf(PerformanceProfile.isAuto(context)) }
    // 每次改动加一，开着的开关、词库、九键模型跟着在后台重读。
    var version by remember { mutableIntStateOf(0) }
    // 本页列表里关掉的项留在列表里，方便开回来。
    var shownOff by remember { mutableStateOf(emptySet<String>()) }
    var device by remember { mutableStateOf(DeviceCapabilityProbe.read(context)) }
    val verdict = PerformanceTiers.explain(device)
    // CPU 实测：没测过就在打开这页时测一次（后台线程，约 0.3～2 秒；键盘开着、省电、发热时不测）。
    var cpuRunning by remember { mutableStateOf(device.cpu == null) }
    var cpuSkipped by remember { mutableStateOf<CpuBenchmarkRunner.Skip?>(null) }
    // 点「重测」测完后写「刚重测」：测得快、数又差不多时，看起来像没反应（GitHub #268）。
    var cpuRetested by remember { mutableStateOf(false) }
    // 语音关着时不开麦，模型不加载：两个语音滑条不显示，常驻的语音模型也不算开销（存的值不变）。
    val speech = remember { SettingsPreferences.isSttEnabled(context) }
    val current = PerformanceProfile.matching(settings)
    val costSource = rememberPerformanceCostSource()
    val facts by produceState<CostFacts?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { readCostFacts(context) }.getOrNull() }
    }
    fun refresh() { settings = PerformanceProfile.read(context); auto = PerformanceProfile.isAuto(context); version++ }
    fun measureCpu(force: Boolean) {
        cpuRunning = true
        cpuRetested = false
        scope.launch {
            val started = android.os.SystemClock.uptimeMillis()
            val outcome = withContext(Dispatchers.Default) {
                val result = if (force) CpuBenchmarkRunner.run(context, manual = true) else CpuBenchmarkRunner.ensure(context)
                // 还在「自动」且实测定出了新档就改套那档；手动选的档、自定义不动。
                if (result is CpuBenchmarkRunner.Outcome.Measured) PerformanceProfile.onCpuMeasured(context)
                result
            }
            // 「测试中…」至少停留一下，不然一闪而过看不到。
            kotlinx.coroutines.delay((CPU_RUNNING_MIN_MS - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0L))
            cpuSkipped = (outcome as? CpuBenchmarkRunner.Outcome.Skipped)?.reason
            if (force) cpuSkipped?.let { android.widget.Toast.makeText(context, cpuSkipToast(it), android.widget.Toast.LENGTH_SHORT).show() }
            cpuRetested = force && outcome is CpuBenchmarkRunner.Outcome.Measured
            device = DeviceCapabilityProbe.read(context)
            cpuRunning = false
            refresh()
        }
    }
    // 等页面先画完再测，免得和打开页面的绘制抢核、把数测高。
    LaunchedEffect(Unit) { if (device.cpu == null) { kotlinx.coroutines.delay(600); measureCpu(force = false) } }
    fun custom(next: PerformanceSettings) { PerformanceProfile.applyCustom(context, next); refresh() }
    fun voiceResident(s: PerformanceSettings) = speech && s.speechEngineIdleMinutes == 0
    // 某组档位值下的总开销：档位管的几项换成这组值，别的开关、词库、九键模型照现在的算。
    fun loadFor(s: PerformanceSettings): PerformanceCosts.Load? {
        val source = costSource ?: return null
        val f = facts ?: return null
        return PerformanceCosts.load(source.device, PerformanceCosts.projected(f.on, s), voiceResident(s), f.t9Model, f.dictionaryWords)
    }
    val currentLoad = facts?.let { f -> costSource?.let { source ->
        PerformanceCosts.load(source.device, f.on, voiceResident(settings), f.t9Model, f.dictionaryWords) } }
    val fullLoad = facts?.let { f -> costSource?.let { source ->
        PerformanceCosts.load(source.device, PerformanceCosts.fullOn(source.device, f.nineKey), speech,
            f.nineKey && source.device.t9Model != null, f.dictionaryWords) } }
    fun tierLine(tier: PerformanceTier): String {
        val load = loadFor(PerformanceProfile.applied(settings, tier)) ?: return tierHint(tier, speech)
        val costDevice = costSource?.device ?: return tierHint(tier, speech)
        return tierHint(tier, speech) + "\n总开销：" + (PerformanceCosts.describeLoad(load, costDevice) ?: "没有额外开销")
    }
    // 开销列表里的开关：和各自设置页写同一个值。
    fun toggle(item: PerformanceCosts.Item, on: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                when {
                    // 九键那一行管的是「九键按数字也模糊」，不动各组本身（各组在模糊音页改）。
                    item.nineKey && item.id.startsWith(PerformanceCosts.T9_PREFIX + "fuzzy_") -> {
                        SpellingPatches.setFuzzyT9Digits(context, on); SpellingPatches.apply(context)
                    }
                    // 和主题页一样：液态玻璃建在玻璃上，关玻璃时一起关。
                    item == PerformanceCosts.Item.GLASS && !on -> {
                        PerformanceCosts.set(context, PerformanceCosts.Item.LIQUID_GLASS, false)
                        PerformanceCosts.set(context, item, false)
                    }
                    else -> PerformanceCosts.set(context, item, on)
                }
            }
            if (!on) shownOff = shownOff + item.id
            refresh()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("性能与省电") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
    ) { padding ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(modifier = Modifier.settingsGutterScroll(listState).fillMaxSize().padding(padding).padding(horizontal = 16.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("本机：${describe(device)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            item {
                SettingsSection(title = "档位") {
                    PerformanceScale(settings, currentLoad, fullLoad,
                        ladder.associateWith { loadFor(PerformanceProfile.applied(settings, it)) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // 还没测时「自动」保留原来套上的那档（老用户升级不会先被降一档），测完再按实测改。
                    val autoShown = if (auto && verdict.limit == PerformanceTiers.Limit.UNMEASURED)
                        PerformanceProfile.matching(settings)?.let { verdict.copy(tier = it) } ?: verdict else verdict
                    TierRow("自动(推荐)", autoLine(autoShown, device.totalMemoryBytes), auto) {
                        PerformanceProfile.applyAuto(context); refresh()
                    }
                    listOf(PerformanceTier.HIGH, PerformanceTier.MEDIUM, PerformanceTier.LOW).forEach { tier ->
                        RowDivider()
                        TierRow(tierNames.getValue(tier), tierLine(tier), !auto && current == tier) {
                            PerformanceProfile.applyPreset(context, tier); refresh()
                        }
                    }
                    // 「自定义」一直列着但不能点：手动改过明细或动效、值对不上任何一档时自动选中，表示当前状态。
                    val isCustom = !auto && current == null
                    RowDivider()
                    TierRow("自定义", "单独改过档位管的任一项后自动选中", isCustom, enabled = false) {}
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // 「自动」按这个定算力档；主频、核数只在顶上显示，不算分。点一下重测（刚玩完游戏发热时测低了可以再测）。
                    SettingsItem(icon = Icons.TwoTone.Speed, title = "CPU 实测",
                        subtitle = cpuLine(device.cpu, cpuSkipped, cpuRunning, cpuRetested),
                        status = if (cpuRunning) null else "重测",
                        onClick = { if (!cpuRunning) measureCpu(force = true) })
                }
            }
            if (speech) item {
                SettingsSection(title = "明细") {
                    // 分档横条，与「输入体验」里长按时长等设置同一样式：标题写当前值，下面一行说明，再是分档滑块。
                    // 二次校正只有「两遍识别」的语音模型才有（SherpaSpeechEngine.hasRefinement）；所有语言都只用一个模型时
                    // 这一项不起作用，就不显示（用户 11-01），存的值不变。读不出来时照常显示。
                    val hasSecondPass = remember {
                        runCatching {
                            val models = com.kingzcheung.xime.speech.AsrModelManager(context)
                            com.kingzcheung.xime.settings.InputLanguage.speech.any { language ->
                                models.getSelectedModelId(language) in setOf(com.kingzcheung.xime.speech.SpeechModelCatalog.TWO_PASS,
                                    com.kingzcheung.xime.speech.SpeechModelCatalog.ZIPFORMER_TWO_PASS)
                            }
                        }.getOrDefault(true)
                    }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (hasSecondPass) {
                            IdleLevelSlider("语音二次校正空闲后释放", settings.speechRefinerIdleMinutes,
                                "先卸掉二次校正模型，再用时第一句定稿稍慢",
                                "speech-refiner-idle-slider") { custom(settings.copy(speechRefinerIdleMinutes = it)) }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                        IdleLevelSlider("语音模型空闲后全部释放", settings.speechEngineIdleMinutes,
                            "“永不”= 一直常驻，按麦克风最快，占内存最多",
                            "speech-engine-idle-slider",
                            neverWarning = PerformanceCosts.voiceResidentWarning(costSource)) {
                            custom(settings.copy(speechEngineIdleMinutes = it))
                        }
                    }
                }
            }
            item {
                // 当前开着的耗性能的东西合起来的开销，和各开关下面那行同一张表（用户 11-02）；每项右边能直接关（用户 11-03）。
                val f = facts
                PerformanceCostTotalSection(costSource, f?.on.orEmpty(), currentLoad.takeIf { f != null },
                    voiceResident = voiceResident(settings), t9Model = f?.t9Model == true, dictionaryWords = f?.dictionaryWords ?: 0,
                    shownOff = shownOff, onToggle = ::toggle)
            }
            onOpenSetupGuide?.let { open ->
                item {
                    SettingsSection(title = "模型") {
                        SettingsItem(icon = Icons.TwoTone.Tune, title = "按设备推荐配置",
                            subtitle = "语音、九键、联想用哪些模型，删掉用不到的",
                            onClick = open, showArrow = true)
                    }
                }
            }
            item {
                Text(if (speech) "语音设置在下次开始说话时生效。字幕模式开着时不会释放语音模型。"
                    else "语音转文本关着，语音模型不加载，语音释放的设置不显示。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

private fun describe(device: DeviceCapabilities): String = buildList {
    device.totalMemoryBytes?.takeIf { it > 0 }?.let { add("${Math.round(it.toDouble() / DeviceCapabilities.GIB)} GB 内存") }
    device.processors?.let { add("$it 核") }
    // 模拟器等读到的值可能不是真实频率，低于 0.5 GHz 不显示。
    device.maxCpuKHz?.takeIf { it >= 500_000 }?.let { add("最高 ${"%.1f".format(it / 1_000_000.0)} GHz") }
}.ifEmpty { listOf("配置未知") }.joinToString(" · ")

@Composable
internal fun RowDivider() = HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
internal fun TierRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            RadioButton(selected = selected, onClick = null, enabled = enabled || selected)
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val idleLevels = PerformanceProfile.idleLevels

@Composable
private fun IdleLevelSlider(
    title: String, minutes: Int, description: String, tag: String,
    /** 滑到“永不”时（拖动中就显示）在滑条下面用醒目色写的提醒；null 不写。 */
    neverWarning: String? = null,
    onChoose: (Int) -> Unit,
) {
    // 旧设置里可能有不在档位上的值：落到最接近的档显示，拖动后才改。
    val index = PerformanceProfile.idleLevelIndex(minutes)
    var position by remember(minutes) { mutableStateOf(index.toFloat()) }
    val shown = idleLevels[position.roundToInt().coerceIn(idleLevels.indices)]
    // 和「按键效果」里的滑条同一写法：标题 + 主题色当前值一行，说明，滑条；两端标出最短和“永不”。
    Column(Modifier.settingsSearchAnchor(title, bleed = 16.dp, bleedVertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(idleLabel(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Slider(value = position, onValueChange = { position = it }, modifier = Modifier.testTag(tag),
            // 松手时按滑块当时的位置取值：点一下滑条时 onValueChange 和这里在同一帧里先后调用，
            // 组合里算的 shown 还是旧值，原来点选的档位不会保存（GitHub #128）。
            onValueChangeFinished = {
                val chosen = idleLevels[position.roundToInt().coerceIn(idleLevels.indices)]
                if (chosen != minutes) onChoose(chosen)
            },
            valueRange = 0f..(idleLevels.size - 1).toFloat(), steps = idleLevels.size - 2)
        Row(Modifier.fillMaxWidth()) {
            Text(idleLabel(idleLevels.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(idleLabel(idleLevels.last()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown == 0 && neverWarning != null) {
            Text(neverWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp).testTag("$tag-never-warning"))
        }
    }
}

/** 刻度条两端各留半个圆点宽，0 分和 100 分的圆点不出界；文字按同一位置对齐。 */
private val scaleInset = 10.dp

/**
 * 档位卡片顶上的刻度条：0 = 能关的全关，100 = 表里每一项都开（[PerformanceCosts.loadScore]，每键耗时和内存各占一半）。
 * 低配、中配、高配的刻度是选了那档之后的总开销（别的开关照现在的），实心圆点是现在的总开销。
 * 读不到开销表时退回旧算法（只按档位管的几项，[PerformanceProfile.score]）。
 */
@Composable
private fun PerformanceScale(
    settings: PerformanceSettings,
    load: PerformanceCosts.Load?,
    full: PerformanceCosts.Load?,
    tierLoads: Map<PerformanceTier, PerformanceCosts.Load?>,
) {
    val colors = MaterialTheme.colorScheme
    val byCost = load != null && full != null && tierLoads.values.all { it != null }
    val score = if (byCost) PerformanceCosts.loadScore(load!!, full!!) else PerformanceProfile.score(settings)
    val current = PerformanceProfile.matching(settings)
    val ticks = ladder.map { tier ->
        tier to if (byCost) PerformanceCosts.loadScore(tierLoads.getValue(tier)!!, full!!)
            else PerformanceProfile.score(PerformanceProfile.presets.getValue(tier))
    }
    Column(Modifier.fillMaxWidth().testTag("performance-scale").padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 刻度条自己就看得出在哪一档，标题后面不再写一句“几分，就是高配”（用户 10-31）。
        Text("配置高低", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        ScaleLabels(listOf(score)) {
            Text("当前", style = MaterialTheme.typography.labelSmall, color = colors.primary, fontWeight = FontWeight.Medium)
        }
        val inactive = colors.secondaryContainer
        val tickIdle = colors.outline
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val inset = scaleInset.toPx()
            val y = size.height / 2
            fun x(points: Int) = inset + (size.width - 2 * inset) * points / 100f
            val line = 4.dp.toPx()
            drawLine(inactive, Offset(x(0), y), Offset(x(100), y), strokeWidth = line, cap = StrokeCap.Round)
            if (score > 0) drawLine(colors.primary, Offset(x(0), y), Offset(x(score), y), strokeWidth = line, cap = StrokeCap.Round)
            ticks.forEach { (_, points) -> drawCircle(if (points <= score) colors.primary else tickIdle, 4.dp.toPx(), Offset(x(points), y)) }
            drawCircle(colors.primary, 9.dp.toPx(), Offset(x(score), y))
            drawCircle(colors.onPrimary, 3.5.dp.toPx(), Offset(x(score), y))
        }
        // 按开销算时几档可能挨得很近（比如没开液态玻璃、语音关着），文字挤在一起时依次往右让开。
        ScaleLabels(ticks.map { it.second }, avoidOverlap = true) {
            ticks.forEach { (tier, _) ->
                val here = tier == current
                Text(tierNames.getValue(tier), style = MaterialTheme.typography.labelSmall,
                    color = if (here) colors.primary else colors.onSurfaceVariant,
                    fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * 一行文字，每个按 [points]（0～100）对准刻度条上的位置居中，靠边时收在行内。
 * [avoidOverlap]：挤在一起的文字错开到第二行，仍各自对准自己的刻度（[staggerLabels]）。
 */
@Composable
private fun ScaleLabels(points: List<Int>, inset: Dp = scaleInset, avoidOverlap: Boolean = false, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val start = inset.roundToPx()
        val track = width - 2 * start
        val lefts = IntArray(placeables.size) { index ->
            val center = start + track * points[index] / 100f
            (center - placeables[index].width / 2f).roundToInt().coerceIn(0, maxOf(0, width - placeables[index].width))
        }
        val rows = if (avoidOverlap) staggerLabels(lefts, IntArray(placeables.size) { placeables[it].width }, width, 8.dp.roundToPx())
            else IntArray(placeables.size)
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, rowHeight * ((rows.maxOrNull() ?: 0) + 1)) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(lefts[index], rows[index] * rowHeight) }
        }
    }
}

/**
 * 刻度文字挤在一起时的排法，返回每个文字放第几行（0 或 1），必要时改 [lefts]。
 * 原来挤在一起时往右推，低配、中配两档刻度挨着时两个字并排挤在第一个刻度下，看不出哪个对哪个（11-04 渲染）。
 * 现在从左到右排：放得下就放第一行；和第一行左边那个重叠就放第二行，仍对准自己的刻度；两行都放不下才在空得早的那行往右推。
 */
internal fun staggerLabels(lefts: IntArray, widths: IntArray, width: Int, gap: Int): IntArray {
    val rows = IntArray(lefts.size)
    val rowEnds = mutableListOf<Int>()
    for (index in lefts.indices.sortedBy { lefts[it] }) {
        var row = rowEnds.indexOfFirst { lefts[index] >= it + gap }
        if (row < 0 && rowEnds.size < 2) {
            row = rowEnds.size
            rowEnds += 0
        } else if (row < 0) {
            row = rowEnds.indices.minBy { rowEnds[it] }
            lefts[index] = minOf(rowEnds[row] + gap, width - widths[index]).coerceAtLeast(0)
        }
        rows[index] = row
        rowEnds[row] = lefts[index] + widths[index]
    }
    return rows
}
