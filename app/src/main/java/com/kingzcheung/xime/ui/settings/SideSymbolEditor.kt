package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.data.SymbolData
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.keyboard.commonSymbolsFor
import com.kingzcheung.xime.keyboard.modeSlotCount
import com.kingzcheung.xime.keyboard.modeSlotTarget
import com.kingzcheung.xime.settings.QwertySwipeSymbols
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.HandwritingFrame
import com.kingzcheung.xime.ui.keyboard.LocalTextModeLabel
import com.kingzcheung.xime.ui.keyboard.NineGridFrame
import com.kingzcheung.xime.ui.keyboard.QwertyBottomRowWeights
import com.kingzcheung.xime.ui.keyboard.SideSymbolTarget
import com.kingzcheung.xime.ui.keyboard.handwritingKeyLabel
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.strokeMainLabels
import com.kingzcheung.xime.ui.keyboard.symbolGridGeometry

/** 「按键符号」里一页编辑哪几条栏：手写侧栏和全屏手写那一排同属手写键盘，合成一页「手写」（用户 11-16）。 */
internal fun sideSymbolPageTargets(target: SideSymbolTarget): List<SideSymbolTarget> = when (target) {
    SideSymbolTarget.HANDWRITING, SideSymbolTarget.HANDWRITING_ROW ->
        listOf(SideSymbolTarget.HANDWRITING, SideSymbolTarget.HANDWRITING_ROW)
    else -> listOf(target)
}

/** 这条栏在「按键符号」里归哪一页（手写展开栏归「手写」页）。 */
internal fun sideSymbolPage(target: SideSymbolTarget): SideSymbolTarget = sideSymbolPageTargets(target).first()

/** 页签名：手写两条栏合成一页，叫「手写」；其余沿用栏名。 */
internal fun sideSymbolPageLabel(target: SideSymbolTarget): String =
    if (sideSymbolPage(target) == SideSymbolTarget.HANDWRITING) "手写" else target.label

/** 示意图里一排键的高度。 */
private val SketchRow = 48.dp

/**
 * 固定栏的编辑（用户 11-16：只有一排格子看不出键在哪）：画出这条栏所在键盘的样子，
 * 可改的格子就在它在键盘上的位置，点哪格弹小框改哪格（改字、前后移、删除），可加格的末尾有「+」。
 * 各键盘的宽高比例取自键盘本身用的 [NineGridFrame]、[HandwritingFrame]、[symbolGridGeometry]、[QwertyBottomRowWeights]。
 * 改动立即保存，存法和设置键都不变。恢复默认由页面右上角菜单调用 [onResetReady] 登记的函数，一页里的几条栏一起恢复。
 */
@Composable
internal fun SideSymbolEditor(target: SideSymbolTarget, onResetReady: ((() -> Unit)?) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = rememberKeyboardInputPreferences()
    val page = sideSymbolPage(target)
    val targets = sideSymbolPageTargets(target)
    var lists by remember(page) { mutableStateOf(targets.associateWith { it.resolve(prefs.symbolsFor(it)) }) }
    // 正在弹框编辑的栏和格；格号等于这条栏的格数表示正在新加一格。
    var editingKey by rememberSaveable(page) { mutableStateOf<String?>(null) }
    var editingIndex by rememberSaveable(page) { mutableStateOf(0) }
    // 小框里还没点「完成」的字；前后移动这一格时不丢。
    var draft by rememberSaveable(page) { mutableStateOf("") }
    val editingTarget = targets.firstOrNull { it.key == editingKey }

    fun items(of: SideSymbolTarget): List<String> = lists[of].orEmpty()

    fun save(of: SideSymbolTarget, next: List<String>) {
        lists = lists + (of to next)
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString(of.prefKey, next.joinToString("\n") { it.trim() }).apply()
    }

    fun reset() {
        // 旧的共用列表会让“恢复默认”退回旧内容：先把它原样分给页外其他还没单独设置的栏，再删掉，
        // 这样其他栏保持不变，这一页的栏回到真正的默认。
        val edit = SettingsPreferences.getPrefsPublic(context).edit()
        val legacy = prefs.symbols()
        if (legacy != null) {
            SideSymbolTarget.entries.filter { it !in targets && it.usesLegacyList &&
                SideSymbolTarget.parse(prefs.sideSymbols[it.key]) == null }
                .forEach { edit.putString(it.prefKey, legacy.joinToString("\n")) }
            edit.putString("fixed_symbols", "")
        }
        targets.forEach { edit.remove(it.prefKey) }
        edit.apply()
        lists = targets.associateWith { it.resolve(null) }
        editingKey = null
    }
    DisposableEffect(page, prefs) {
        onResetReady(::reset)
        onDispose { onResetReady(null) }
    }

    // 一个可改的格：index 等于格数时是末尾的「+」。
    val cell: @Composable (SideSymbolTarget, Int, Modifier) -> Unit = { of, index, modifier ->
        val list = items(of)
        SlotCell(text = list.getOrNull(index), active = editingTarget == of && editingIndex == index,
            tag = if (index < list.size) "side-symbol-cell:${of.key}:$index" else "side-symbol-add:${of.key}",
            modifier = modifier) { draft = list.getOrNull(index).orEmpty(); editingKey = of.key; editingIndex = index }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("点有颜色的格子修改", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (page) {
            SideSymbolTarget.T9, SideSymbolTarget.STROKE -> {
                SketchCaption(page.where)
                NineGridSketch(page, items(page).size, page.slots == null) { index, modifier -> cell(page, index, modifier) }
            }
            SideSymbolTarget.NUMBER -> {
                SketchCaption(page.where)
                NumberSketch(items(page).size) { index, modifier -> cell(page, index, modifier) }
            }
            SideSymbolTarget.HANDWRITING -> {
                SketchCaption("手写键盘：右侧删除键下面三格")
                HandwritingSketch { index, modifier -> cell(SideSymbolTarget.HANDWRITING, index, modifier) }
                Spacer(Modifier.height(8.dp))
                SketchCaption("全屏手写：底部删除键左边一排")
                ExpandedHandwritingSketch { index, modifier -> cell(SideSymbolTarget.HANDWRITING_ROW, index, modifier) }
            }
            SideSymbolTarget.COMMON -> {
                SketchCaption(page.where + "，后面浅色的是自带的常用符号")
                CommonSymbolSketch(items(page)) { index, modifier -> cell(page, index, modifier) }
            }
            SideSymbolTarget.NUMBER_ROW -> {
                SketchCaption(page.where)
                NumberRowSketch(items(page).size) { index, modifier -> cell(page, index, modifier) }
            }
            else -> Unit
        }
    }

    editingTarget?.let { of ->
        val index = editingIndex
        val list = items(of)
        val adding = index >= list.size
        val canGrow = of.slots == null
        fun finish() {
            editingKey = null
            val value = draft.trim()
            if (adding) { if (value.isNotEmpty()) save(of, list + value) }
            else if (value != list[index]) save(of, list.toMutableList().also { it[index] = value })
        }
        // 框里改了字还没点「完成」就点了框外或返回：先问，不直接丢（用户 11-19）。
        var leaving by remember(editingKey, index) { mutableStateOf(false) }
        val changed = draft.trim() != (if (adding) "" else list[index])
        AlertDialog(onDismissRequest = { if (changed) leaving = true else editingKey = null },
            modifier = Modifier.testTag("side-symbol-dialog"),
            title = { Text(if (adding) "加一格" else if (targets.size > 1) "${of.label}第 ${index + 1} 格" else "第 ${index + 1} 格") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                        placeholder = { Text("符号或短语") },
                        modifier = Modifier.fillMaxWidth().testTag("side-symbol-field"))
                    // 前后移动、删除马上生效，背后的键盘跟着变；框里的字点「完成」才写入。
                    if (!adding) Row(verticalAlignment = Alignment.CenterVertically) {
                        val vertical = of.vertical
                        MoveButton(enabled = index > 0, vertical = vertical, earlier = true) {
                            save(of, list.toMutableList().also { it.add(index - 1, it.removeAt(index)) }); editingIndex = index - 1
                        }
                        MoveButton(enabled = index < list.lastIndex, vertical = vertical, earlier = false) {
                            save(of, list.toMutableList().also { it.add(index + 1, it.removeAt(index)) }); editingIndex = index + 1
                        }
                        // 常用符号栏可以删空（只剩默认符号）；其他栏删空会退回默认内容，至少留一格。
                        if (canGrow) IconButton(enabled = list.size > 1 || of == SideSymbolTarget.COMMON, onClick = {
                            save(of, list.toMutableList().also { it.removeAt(index) }); editingKey = null
                        }, modifier = Modifier.testTag("side-symbol-delete")) { Icon(Icons.Default.Delete, "删除这一格") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { finish() }) { Text("完成") } },
            dismissButton = { TextButton(onClick = { editingKey = null }) { Text("取消") } })
        if (leaving) UnsavedChangesDialog(onSave = { leaving = false; finish() },
            onDiscard = { leaving = false; editingKey = null }, onKeepEditing = { leaving = false })
    }
}

@Composable
private fun SketchCaption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** 可改的格：深色底；正在改的描强调色边；[text] 为 null 是末尾的「+」。 */
@Composable
private fun SlotCell(text: String?, active: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.padding(2.dp).clip(shape)
        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.secondaryContainer)
        .border(if (active) 2.dp else 0.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, shape)
        .clickable(onClick = onClick).padding(horizontal = 4.dp).testTag(tag),
        contentAlignment = Alignment.Center) {
        if (text == null) Icon(Icons.Default.Add, "加一格", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        else androidx.compose.material3.Text(if (text.isBlank()) com.kingzcheung.xime.ui.i18n.tr("空") else text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** 键盘上不能在这里改的键：浅色，只标出位置。 */
@Composable
private fun FixedKey(label: String, modifier: Modifier, dim: Boolean = false) {
    Box(modifier.padding(2.dp).clip(RoundedCornerShape(7.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (dim) 0.3f else 0.55f)),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dim) 0.45f else 0.7f))
    }
}

/** 示意图里键面写的字：图标键写成文字，其余用键盘本身的名字。 */
@Composable
private fun sketchLabel(action: String): String = when (action) {
    "delete" -> "删除"; "enter" -> "回车"; "ime_switch" -> "语言"; "expand" -> "全屏"; "collapse" -> "收起"
    else -> handwritingKeyLabel(action)
}

/**
 * 九键、笔画左边那一列：上面一块符号面板，一屏 [NineGridFrame.SIDE_VISIBLE_SLOTS] 格，多出的上下滑；
 * 笔画栏不多于四格时平分面板（同键盘）。末尾一格是「+」。
 */
@Composable
private fun SlotRail(count: Int, canGrow: Boolean, fillWhenFew: Boolean, modifier: Modifier,
    cell: @Composable (Int, Modifier) -> Unit) {
    BoxWithConstraints(modifier.padding(2.dp).clip(RoundedCornerShape(7.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        val slots = count + if (canGrow) 1 else 0
        if (fillWhenFew && count <= NineGridFrame.SIDE_VISIBLE_SLOTS) Column(Modifier.fillMaxSize()) {
            repeat(slots) { cell(it, Modifier.fillMaxWidth().weight(1f)) }
        } else {
            val rowHeight = maxHeight / NineGridFrame.SIDE_VISIBLE_SLOTS
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                repeat(slots) { cell(it, Modifier.fillMaxWidth().height(rowHeight)) }
            }
        }
    }
}

/** 九键、笔画键盘：左栏 | 主键区 | 右栏，比例同 [NineGridFrame]。 */
@Composable
private fun NineGridSketch(target: SideSymbolTarget, count: Int, canGrow: Boolean, cell: @Composable (Int, Modifier) -> Unit) {
    val prefs = rememberKeyboardInputPreferences()
    val stroke = target == SideSymbolTarget.STROKE
    val mainRows = if (stroke) strokeMainLabels().chunked(3)
        else listOf(listOf("分词", "ABC", "DEF"), listOf("GHI", "JKL", "MNO"), listOf("PQRS", "TUV", "WXYZ"))
    Row(Modifier.fillMaxWidth().height(SketchRow * 4).testTag("side-symbol-sketch:${target.key}")) {
        Column(Modifier.weight(NineGridFrame.SIDE).fillMaxHeight()) {
            SlotRail(count, canGrow, fillWhenFew = stroke,
                modifier = Modifier.fillMaxWidth().weight(NineGridFrame.SIDE_PANEL_ROWS), cell = cell)
            FixedKey(modeSlotTarget(KeyboardInputPage.TEXT, 1, LocalTextModeLabel.current, prefs.symbolKeyCycle).label,
                Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.weight(NineGridFrame.MAIN).fillMaxHeight()) {
            mainRows.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) { row.forEach { FixedKey(it, Modifier.weight(1f).fillMaxHeight()) } }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (!prefs.symbolKeyCycle) FixedKey(modeSlotTarget(KeyboardInputPage.TEXT, 2, LocalTextModeLabel.current).label,
                    Modifier.weight(NineGridFrame.BOTTOM_MODE).fillMaxHeight())
                if (prefs.languageKeyOnLeft) FixedKey("语言", Modifier.weight(NineGridFrame.BOTTOM_LANGUAGE).fillMaxHeight())
                FixedKey("空格", Modifier.weight(NineGridFrame.spaceWeight(prefs.symbolKeyCycle)).fillMaxHeight())
                if (!prefs.languageKeyOnLeft) FixedKey("语言", Modifier.weight(NineGridFrame.BOTTOM_LANGUAGE).fillMaxHeight())
            }
        }
        Column(Modifier.weight(NineGridFrame.ACTION).fillMaxHeight()) {
            listOf("删除", "重输", "0", "确定").forEach { FixedKey(it, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}

/** 数字键盘：左栏占上面三排，底下一整排；比例同 [NineGridFrame]。 */
@Composable
private fun NumberSketch(count: Int, cell: @Composable (Int, Modifier) -> Unit) {
    val cycle = rememberKeyboardInputPreferences().symbolKeyCycle
    val modeCount = modeSlotCount(cycle)
    val text = LocalTextModeLabel.current
    Column(Modifier.fillMaxWidth().height(SketchRow * 4).testTag("side-symbol-sketch:number")) {
        Row(Modifier.fillMaxWidth().weight(3f)) {
            SlotRail(count, canGrow = true, fillWhenFew = false,
                modifier = Modifier.weight(NineGridFrame.SIDE).fillMaxHeight(), cell = cell)
            Column(Modifier.weight(NineGridFrame.MAIN).fillMaxHeight()) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) { row.forEach { FixedKey(it, Modifier.weight(1f).fillMaxHeight()) } }
                }
            }
            Column(Modifier.weight(NineGridFrame.ACTION).fillMaxHeight()) {
                listOf("删除", "空格", "@").forEach { FixedKey(it, Modifier.fillMaxWidth().weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            for (slot in 1..modeCount) FixedKey(modeSlotTarget(KeyboardInputPage.NUMBERS, slot, text, cycle).label,
                Modifier.weight(NineGridFrame.BOTTOM_MODE).fillMaxHeight())
            FixedKey("0", Modifier.weight(NineGridFrame.MAIN - modeCount * NineGridFrame.BOTTOM_MODE).fillMaxHeight())
            FixedKey(".", Modifier.weight(NineGridFrame.ACTION).fillMaxHeight())
            FixedKey("确定", Modifier.weight(NineGridFrame.ACTION).fillMaxHeight())
        }
    }
}

@Composable
private fun HandwritingPad(label: String, modifier: Modifier) {
    Box(modifier.padding(2.dp).clip(RoundedCornerShape(7.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun HandwritingBottomRow(expanded: Boolean, height: Dp) {
    Row(Modifier.fillMaxWidth().height(height)) {
        val prefs = rememberKeyboardInputPreferences()
        HandwritingFrame.bottomKeys(expanded, prefs.symbolKeyCycle, prefs.languageKeyOnLeft).forEach { (action, weight) ->
            FixedKey(if (action == "symbol" && prefs.symbolKeyCycle) modeSlotTarget(KeyboardInputPage.TEXT, 1, LocalTextModeLabel.current, true).label else sketchLabel(action), Modifier.weight(weight).fillMaxHeight())
        }
    }
}

/** 手写键盘：画板右边一列是删除 + 三格，底下一排；比例同 [HandwritingFrame]。 */
@Composable
private fun HandwritingSketch(cell: @Composable (Int, Modifier) -> Unit) {
    // 整块分五排高：底排一排，右列删除 + 三格各一排。
    val total = SketchRow * 5
    BoxWithConstraints(Modifier.fillMaxWidth().height(total).testTag("side-symbol-sketch:handwriting")) {
        val sideWidth = maxWidth * HandwritingFrame.sideKeyFraction
        val footer = total * HandwritingFrame.FOOTER_FRACTION
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                HandwritingPad("手写区", Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(sideWidth).fillMaxHeight()) {
                    FixedKey(sketchLabel("delete"), Modifier.fillMaxWidth().weight(1f))
                    repeat(SideSymbolTarget.HANDWRITING.slots ?: 0) { cell(it, Modifier.fillMaxWidth().weight(1f)) }
                }
            }
            HandwritingBottomRow(expanded = false, height = footer)
        }
    }
}

/** 全屏手写：画板铺满，底部是候选栏、展开栏六格 + 删除、底排；比例同 [HandwritingFrame]。 */
@Composable
private fun ExpandedHandwritingSketch(cell: @Composable (Int, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("side-symbol-sketch:handwriting_row")) {
        val sideWidth = maxWidth * HandwritingFrame.sideKeyFraction
        Column(Modifier.fillMaxWidth()) {
            HandwritingPad("全屏手写区", Modifier.fillMaxWidth().height(SketchRow * 2))
            FixedKey("候选栏", Modifier.fillMaxWidth().height(28.dp), dim = true)
            Row(Modifier.fillMaxWidth().height(SketchRow)) {
                repeat(SideSymbolTarget.HANDWRITING_ROW.slots ?: 0) { cell(it, Modifier.weight(1f).fillMaxHeight()) }
                FixedKey(sketchLabel("delete"), Modifier.width(sideWidth).fillMaxHeight())
            }
            HandwritingBottomRow(expanded = true, height = SketchRow)
        }
    }
}

/**
 * 符号键盘「常用」页：自己填的排在最前面（可改），后面接自带的常用符号（浅色），列数按键盘本身的
 * [symbolGridGeometry] 算；底下一排是 26 键底排的比例：两个模式键、分类条、删除。
 */
@Composable
private fun CommonSymbolSketch(own: List<String>, cell: @Composable (Int, Modifier) -> Unit) {
    val cycle = rememberKeyboardInputPreferences().symbolKeyCycle
    val modeCount = modeSlotCount(cycle)
    val text = LocalTextModeLabel.current
    val defaults = commonSymbolsFor(text).filterNot { it in own }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("side-symbol-sketch:common")) {
        val grid = symbolGridGeometry(maxWidth.value, SketchRow.value * 3)
        val columns = grid.columns
        val editable = own.size + 1
        val rows = maxOf(3, (editable + columns - 1) / columns)
        Column(Modifier.fillMaxWidth()) {
            for (row in 0 until rows) Row(Modifier.fillMaxWidth().height(grid.rowHeightDp.dp)) {
                for (column in 0 until columns) {
                    val index = row * columns + column
                    val modifier = Modifier.weight(1f).fillMaxHeight()
                    if (index < editable) cell(index, modifier)
                    else defaults.getOrNull(index - editable)?.let { FixedKey(it, modifier, dim = true) }
                        ?: Spacer(modifier)
                }
            }
            val bottom = QwertyBottomRowWeights.Standard
            Row(Modifier.fillMaxWidth().height(SketchRow)) {
                for (slot in 1..modeCount) FixedKey(modeSlotTarget(KeyboardInputPage.SYMBOLS, slot, text, cycle).label,
                    Modifier.weight(bottom.mode).fillMaxHeight())
                FixedKey((listOf("常用", "最近") + SymbolData.categories.map { it.name }).joinToString("  "),
                    Modifier.weight(bottom.total - modeCount * bottom.mode - bottom.enter).fillMaxHeight())
                FixedKey(sketchLabel("delete"), Modifier.weight(bottom.enter).fillMaxHeight())
            }
        }
    }
}

/** 数字行：26 键、14 键字母上方一排，所有格平分；下面浅色画出字母键对照位置。 */
@Composable
private fun NumberRowSketch(count: Int, cell: @Composable (Int, Modifier) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("side-symbol-sketch:number_row")) {
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            repeat(count) { cell(it, Modifier.weight(1f).fillMaxHeight()) }
            cell(count, Modifier.width(44.dp).fillMaxHeight())
        }
        val rows = QwertySwipeSymbols.rows.map { row -> row.map { it.uppercase() } }
        val widest = rows.maxOf { it.size }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(SketchRow)) {
                val margin = (widest - row.size) / 2f
                if (margin > 0f) Spacer(Modifier.weight(margin))
                row.forEach { FixedKey(it, Modifier.weight(1f).fillMaxHeight()) }
                if (margin > 0f) Spacer(Modifier.weight(margin))
            }
        }
    }
}

@Composable
private fun RowScope.MoveButton(enabled: Boolean, vertical: Boolean, earlier: Boolean, onClick: () -> Unit) {
    IconButton(enabled = enabled, onClick = onClick) {
        Icon(when {
            vertical && earlier -> Icons.Default.KeyboardArrowUp
            vertical -> Icons.Default.KeyboardArrowDown
            earlier -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
            else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        }, if (vertical) (if (earlier) "上移" else "下移") else (if (earlier) "左移" else "右移"))
    }
}
