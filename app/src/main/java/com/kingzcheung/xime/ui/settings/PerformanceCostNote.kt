package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.PerformanceCosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本机该用的那组实测数（见 [PerformanceCosts.source]），在后台读；读完前和没有数据时为 null。 */
@Composable
internal fun rememberPerformanceCostSource(): PerformanceCosts.Source? {
    val context = LocalContext.current
    val source by produceState<PerformanceCosts.Source?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { PerformanceCosts.source(context) }.getOrNull() }
    }
    return source
}

/**
 * 开关说明下面那一行“性能代价：每键 +1.2 ms（慢 4%）· 内存 +30 MB”；还没实测的写“性能代价（估算）：每键约 …”。
 * 和「已被…锁定」那行同一位置、同一字号，用次要色，不当成警告。
 */
@Composable
internal fun PerformanceCostNote(item: PerformanceCosts.Item, modifier: Modifier = Modifier, label: String = "性能开销") {
    val source = rememberPerformanceCostSource() ?: return
    val text = PerformanceCosts.note(source, item.id, label) ?: return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 2.dp).testTag("perf-cost:${item.id}"))
}

/** 几项合起来的代价一行（同 [PerformanceCostNote] 的样式），如「九键按数字也模糊」下面已开各组的合计；一组都没开不显示。 */
@Composable
internal fun PerformanceCostTotalNote(items: Set<PerformanceCosts.Item>, label: String, tag: String, modifier: Modifier = Modifier) {
    val source = rememberPerformanceCostSource() ?: return
    val text = PerformanceCosts.totalNote(source, items.mapTo(linkedSetOf()) { it.id }, label) ?: return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 2.dp).testTag("perf-cost-total:$tag"))
}


/**
 * 「性能与省电」里的“性能开销”：当前开着的、耗性能的东西合起来每键慢多少、多占多少内存，下面一项一项列出来，
 * 每项右边就是它的开关（和各自设置页同一个值），在这一页就能关掉最耗的（用户 11-03「把所有的这个性能开销都考虑进来」）。
 * 除了登记过的开关，还算上常驻的语音模型、九键词组模型和导入的词库（这三样不是开关，只列不关）。
 * [shownOff]：这次在本页关掉的项，留在列表里方便开回来。
 */
@Composable
internal fun PerformanceCostTotalSection(
    source: PerformanceCosts.Source?,
    on: Set<String>,
    load: PerformanceCosts.Load?,
    voiceResident: Boolean,
    t9Model: Boolean,
    dictionaryWords: Int,
    shownOff: Set<String>,
    onToggle: (PerformanceCosts.Item, Boolean) -> Unit,
) {
    SettingsSection(title = "性能开销") {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("perf-cost-total"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (source == null || load == null) {
                Text("暂时算不出总开销。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(Modifier.fillMaxWidth()) {
                Text("当前总开销", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(PerformanceCosts.describeLoad(load, source.device, source.here) ?: "没有额外开销",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("perf-cost-total-value"))
            }
            Text(PerformanceCosts.basisLine(source, on + listOfNotNull(
                PerformanceCosts.T9_MODEL_ID.takeIf { t9Model && source.device.t9Model != null })),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (source == null || load == null) return@SettingsSection
        val device = source.device
        // 模糊音按组登记，一组的开销小到不值一提，合成一行：26 键一行（去模糊音页改），九键「按数字也模糊」一行（这里能关）。
        val fuzzy26 = PerformanceCosts.Item.entries.filter { !it.nineKey && it.id.startsWith("fuzzy_") }
        val fuzzy9 = PerformanceCosts.Item.entries.filter { it.nineKey && it.id.startsWith(PerformanceCosts.T9_PREFIX + "fuzzy_") }
        // 键盘停着时一直耗电的（动态背景）排最前：每分钟多 1 秒 CPU 按每键多 1 ms 算。
        fun weight(cost: PerformanceCosts.Cost?) = (cost?.keyMs ?: 0.0) + (cost?.keyP95Ms ?: 0.0) + (cost?.memoryMb ?: 0.0) / 10 +
            (cost?.idleCpuSecPerMin ?: 0.0)
        val listed = PerformanceCosts.Item.entries
            .filter { it !in fuzzy26 && it !in fuzzy9 && (it.id in on || it.id in shownOff) }
            .map { it to device.items[it.id] }
            // 开销小到不值一提的不列（开关下面也不写），本页刚关的照样列着；开着的按耗时从大到小，关了的放后面。
            .filter { (item, cost) -> item.id in shownOff || PerformanceCosts.describe(cost, device) != null }
            .sortedByDescending { (item, cost) -> if (item.id in on) weight(cost) else -1.0 }
        listed.forEach { (item, cost) ->
            val enabled = item.id in on
            CostDivider()
            SettingsListRow(item.title, if (enabled) PerformanceCosts.describe(cost, device, source.here).orEmpty() else "已关",
                modifier = Modifier.testTag("perf-cost-row:${item.id}")) {
                Switch(checked = enabled, onCheckedChange = { onToggle(item, it) }, modifier = Modifier.padding(end = 12.dp))
            }
        }
        fun groupCost(ids: List<PerformanceCosts.Item>) = PerformanceCosts.total(device, ids.mapTo(linkedSetOf()) { it.id }).let {
            PerformanceCosts.Cost(it.keyMs, it.memoryMb, baselineKeyMs = it.baselineKeyMs, keyP95Ms = it.keyP95Ms.takeIf { p -> p > 0 })
        }
        val fuzzyOn = fuzzy26.filter { it.id in on }
        if (fuzzyOn.isNotEmpty()) PerformanceCosts.describe(groupCost(fuzzyOn), device)?.let { text ->
            CostDivider()
            SettingsListRow("模糊音(${fuzzyOn.size} 组)", text, modifier = Modifier.testTag("perf-cost-row:fuzzy"))
        }
        val digitsOn = fuzzy9.filter { it.id in on }
        if (digitsOn.isNotEmpty() || fuzzy9.first().id in shownOff) {
            val text = if (digitsOn.isEmpty()) "已关" else PerformanceCosts.describe(groupCost(digitsOn), device).orEmpty()
            CostDivider()
            SettingsListRow("九键按数字也模糊", text, modifier = Modifier.testTag("perf-cost-row:t9-fuzzy-digits")) {
                Switch(checked = digitsOn.isNotEmpty(), onCheckedChange = { onToggle(fuzzy9.first(), it) },
                    modifier = Modifier.padding(end = 12.dp))
            }
        }
        device.t9Model?.takeIf { t9Model }?.let { PerformanceCosts.describe(it, device) }?.let { text ->
            CostDivider()
            SettingsListRow("九键词组模型", text, modifier = Modifier.testTag("perf-cost-t9-model"))
        }
        device.voiceResident?.takeIf { voiceResident }?.let { PerformanceCosts.describe(it, device) }?.let { text ->
            CostDivider()
            SettingsListRow("语音模型常驻", text, modifier = Modifier.testTag("perf-cost-voice-resident"))
        }
        PerformanceCosts.dictionaryCost(device, dictionaryWords)?.copy(baselineKeyMs = device.dictionaryBaseline?.keyMs)?.let { cost ->
            val text = PerformanceCosts.describe(cost, device) ?: "测不出差别"
            CostDivider()
            SettingsListRow("导入的词库($dictionaryWords 词)", text, modifier = Modifier.testTag("perf-cost-dictionaries"))
        }
        val missing = PerformanceCosts.Item.entries.filter { it.id in on && it.id !in device.items }
        if (missing.isNotEmpty()) Text("开着但没测过：" + missing.joinToString("、") { it.title },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun CostDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
