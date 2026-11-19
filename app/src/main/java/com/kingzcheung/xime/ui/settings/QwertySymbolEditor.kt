package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.rememberSwipeHintsEnabled
import com.kingzcheung.xime.ui.keyboard.SideSymbolTarget
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「按键符号」：每页只有一个和键盘上一样的预览，点哪个键／哪一格就弹小框改它，改完即生效，没有保存按钮。
 * 恢复默认、布局版本收在右上角菜单（11-01 用户：原页面上下重复、说明绕、两种保存方式）。
 *
 * 26 键（用户 11-15：一个一个符号改太弱，各种情形要各有一套）：页面上方是数字行、上方角标、下方角标三个开关，
 * 开关组合出六套布局（[CornerVariant]），现在是哪种情形就编辑哪一套，和键盘上用的是同一套；
 * 「整排输入」一次改一整排，「从另一套复制」把别的情形的一套搬过来，每格可单独设「跟随全角半角」。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun QwertySymbolEditor(onBack: () -> Unit, initialTarget: String? = null) {
    val context = LocalContext.current
    val inputSettings = rememberKeyboardInputPreferences()
    val reverseSwipe = inputSettings.reverseSymbolSwipe
    val topGesture = if (inputSettings.symbolInputMode == SymbolInputMode.LONG_PRESS) "长按"
        else if (reverseSwipe) "上拉" else "下拉"
    val bottomGesture = if (reverseSwipe) "下拉" else "上拉"
    val scope = rememberCoroutineScope()
    var english by rememberSaveable { mutableStateOf(false) }
    // 合并键布局按键编辑：内置 14 键（FourteenKeySymbols）或某个自建双字母布局（CustomLayoutSymbols，存布局 id）；
    // null 是 26 键。不为 null 时 english 一定是 false。
    var merged by rememberSaveable { mutableStateOf<String?>(null) }
    val fourteen = merged == FOURTEEN
    var customLayouts by remember { mutableStateOf<List<CustomKeyboardLayout>>(emptyList()) }
    val customLayout = customLayouts.firstOrNull { it.id == merged }
    // 26 键现在编辑的是哪一套：跟着本页上面三个开关（数字行、上方角标、下方角标）走，和键盘上实际用的是同一套。
    val hints = rememberSwipeHintsEnabled()
    val variant = if (merged == null) CornerVariant.of(inputSettings.numberRow, hints.up, hints.down) else CornerVariant.BOTH
    // 这一套里改过的「跟随全角半角」（格子 → 开或关），没改过的照预设。
    var followOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val follows = if (merged == null) QwertySwipeSymbols.follows(english, variant, followOverrides) else emptySet()
    var rowInput by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    // 正在弹框编辑的键；null 表示没有打开编辑框。
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var swipes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var originalOverrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var originalValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // 26 键之外的固定栏（九键、笔画、手写、常用符号、数字行）；null 表示正在编辑字母键。
    // 手写展开栏和手写侧栏同在「手写」一页：从全屏手写那一排点进来也落到这一页。
    var sideTarget by rememberSaveable { mutableStateOf(initialTarget?.let { key ->
        SideSymbolTarget.entries.firstOrNull { it.key == key }?.let { sideSymbolPage(it).key } }) }
    var reload by remember { mutableStateOf(0) }
    var versions by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var savingVersion by remember { mutableStateOf(false) }
    var choosingVersion by remember { mutableStateOf(false) }
    // 当前固定栏页登记的「恢复默认」。
    var sideReset by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun back() { if (!busy) onBack() }
    BackHandler { back() }
    LaunchedEffect(english, merged, variant, reload) {
        swipes = if (merged == null) LetterSwipeBindings.load(context, english) else emptyMap()
        loaded = false
        val layouts = withContext(Dispatchers.IO) {
            KeysConfigHelper.loadConfig(context)
            runCatching { CustomKeyboardLayouts.load(context).filter(CustomLayoutSymbols::editable) }.getOrDefault(emptyList())
        }
        customLayouts = layouts
        val custom = layouts.firstOrNull { it.id == merged }
        // 正在改的自建布局已经删掉或拆回单字母：回到中文26键。
        if (merged != null && merged != FOURTEEN && custom == null) { merged = null; return@LaunchedEffect }
        // Read the same effective gestures used by the keyboard, including existing YAML overrides.
        values = withContext(Dispatchers.IO) {
            if (merged == FOURTEEN) KeysConfigHelper.fourteenSymbolEditorValues(context)
            else if (custom != null) KeysConfigHelper.customLayoutSymbolEditorValues(context, custom)
            else KeysConfigHelper.qwertySymbolEditorValues(context, english, variant)
        }
        originalOverrides = if (merged == FOURTEEN) FourteenKeySymbols.load(context)
            else if (custom != null) CustomLayoutSymbols.load(context, custom)
            else QwertySwipeSymbols.load(context, english, variant)
        followOverrides = if (merged == null) QwertySwipeSymbols.loadFollows(context, english, variant) else emptyMap()
        val boardKeys = if (merged == FOURTEEN) FourteenKeySymbols.keys
            else custom?.let(CustomLayoutSymbols::keys) ?: QwertySwipeSymbols.keys.map(Char::toString)
        if (editing !in boardKeys) editing = null
        versions = withContext(Dispatchers.IO) { QwertySwipeSymbols.versions(context, english) }
        originalValues = values
        loaded = true
    }
    fun changeTab(nextEnglish: Boolean, nextSide: String?, nextMerged: String? = null) {
        if (nextEnglish == english && nextSide == sideTarget && nextMerged == merged) return
        english = nextEnglish; merged = nextMerged; sideTarget = nextSide; message = ""; editing = null
    }
    // 改完一个键（或载入一个版本）立刻写入，键盘上马上生效；只存和原来不同的位置，其余仍跟预设走。
    fun persist(next: Map<String, String>, nextSwipes: Map<String, String>, done: String = "",
        nextFollows: Map<String, Boolean> = followOverrides) {
        busy = true
        val snapshot = originalOverrides + next.filter { (key, value) -> originalValues[key] != value }
        val board = fourteen
        val custom = customLayout
        val en = english
        val kind = variant
        values = next; swipes = nextSwipes; followOverrides = nextFollows
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                if (board) FourteenKeySymbols.save(context, snapshot)
                else if (custom != null) CustomLayoutSymbols.save(context, custom, snapshot)
                else QwertySwipeSymbols.save(context, en, snapshot, nextSwipes, kind, nextFollows)
            } }
                .onSuccess { originalOverrides = snapshot; originalValues = next; message = done }
                .onFailure { message = "保存失败：${it.message}" }
            busy = false
        }
    }
    // 中英文一致：恢复后立即保存。英文清掉修改即预设；中文写入预设符号（覆盖自定义 YAML）。
    fun resetLetters() {
        busy = true
        val board = fourteen
        val custom = customLayout
        val en = english
        // 自建布局没有 YAML 那一层，清掉修改就是预设。26 键只恢复现在编辑的这一套，别的情形的几套不动。
        val kind = variant
        val preset = if (en || custom != null) emptyMap() else buildMap {
            (if (board) FourteenKeySymbols.up else QwertySwipeSymbols.up(false, kind)).forEach { (k, v) -> put("up.$k", v) }
            (if (board) FourteenKeySymbols.down else QwertySwipeSymbols.down(false, kind)).forEach { (k, v) -> put("down.$k", v) }
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                if (board) FourteenKeySymbols.save(context, preset)
                else if (custom != null) CustomLayoutSymbols.save(context, custom, preset)
                else QwertySwipeSymbols.save(context, en, preset, emptyMap(), kind, emptyMap())
            } }
                .onSuccess { message = "已恢复默认"; reload++ }
                .onFailure { message = "保存失败：${it.message}" }
            busy = false
        }
    }
    // 一页一个页签；手写两条栏合成一页（用户 11-16：手写不该拆成两个）。
    val sideTargets = SideSymbolTarget.entries.filter { sideSymbolPage(it) == it }
    val side = SideSymbolTarget.entries.firstOrNull { it.key == sideTarget }?.let(::sideSymbolPage)
    // 布局版本存的是 26 键 52 个位置，中文26键、英文26键各存各的（11-09 用户：英文布局没设计完没法存）。
    val versionsAvailable = side == null && merged == null
    val tabName = side?.let(::sideSymbolPageLabel) ?: customLayout?.name ?: if (fourteen) "中文14键"
        else "${if (english) "英文26键" else "中文26键"}（${variant.label}）"
    KeyboardSettingsScaffold("按键符号", ::back, preview = true, actions = {
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("symbol-menu")) {
                Icon(Icons.Default.MoreVert, "更多")
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("恢复默认") }, enabled = side != null || loaded,
                    onClick = { menu = false; confirmReset = true })
                // 原「按键与手势」里的 Z X C V 开关（11-02 用户：和 Ctrl 的逻辑不搭，收进这里）：一次填好四个键的滑动动作，之后每个键还能单独改。
                if (side == null && merged == null) {
                    val presetOn = LetterSwipeBindings.hasEditPreset(swipes, reverseSwipe)
                    DropdownMenuItem(text = { Text(if (presetOn) "取消 Z X C V 的撤销、剪切、复制、粘贴" else "Z X C V 设为撤销、剪切、复制、粘贴") },
                        enabled = loaded, modifier = Modifier.testTag("symbol-zxcv-preset"), onClick = {
                            menu = false
                            if (presetOn) persist(values, swipes - LetterSwipeBindings.editPresetActions.keys
                                .map { "$it.${LetterSwipeBindings.upperDirection(reverseSwipe)}" }.toSet(), "Z X C V 已换回符号")
                            else persist(values, LetterSwipeBindings.withEditPreset(swipes, reverseSwipe),
                                "Z X C V ${LetterSwipeBindings.directions[LetterSwipeBindings.upperDirection(reverseSwipe)]}改为撤销、剪切、复制、粘贴")
                        })
                    // 中文才分全角半角；一次把整套设成跟随或固定，单个格子在点键弹出的小框里改。
                    if (!english) {
                        DropdownMenuItem(text = { Text("有两种写法的符号都跟随全角半角") }, enabled = loaded,
                            modifier = Modifier.testTag("symbol-follow-all"), onClick = {
                                menu = false
                                persist(values, swipes, "有全角半角两种写法的符号都已设为跟随",
                                    QwertySwipeSymbols.slotIds.associateWith { QwertySwipeSymbols.hasWidthPair(values[it].orEmpty()) })
                            })
                        DropdownMenuItem(text = { Text("所有符号都不跟随全角半角") }, enabled = loaded,
                            modifier = Modifier.testTag("symbol-follow-none"), onClick = {
                                menu = false
                                persist(values, swipes, "所有符号都已设为固定字符", QwertySwipeSymbols.slotIds.associateWith { false })
                            })
                    }
                }
                if (versionsAvailable) {
                    DropdownMenuItem(text = { Text("存为版本") }, enabled = loaded,
                        onClick = { menu = false; savingVersion = true })
                    DropdownMenuItem(text = { Text("载入版本") }, enabled = loaded && versions.isNotEmpty(),
                        onClick = { menu = false; choosingVersion = true })
                }
            }
        }
    }) { modifier ->
    Column(modifier.settingsVerticalScroll().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val sideIndex = sideTargets.indexOf(side)
    val customIndex = customLayouts.indexOfFirst { it.id == merged }
    PrimaryScrollableTabRow(selectedTabIndex = if (sideIndex >= 0) 3 + customLayouts.size + sideIndex
        else if (customIndex >= 0) 3 + customIndex else if (fourteen) 2 else if (english) 1 else 0,
        edgePadding = 0.dp) {
        Tab(selected = sideTarget == null && !english && merged == null, onClick = { changeTab(false, null) }, enabled = !busy,
            text = { Text("中文26键") })
        Tab(selected = sideTarget == null && english, onClick = { changeTab(true, null) }, enabled = !busy,
            text = { Text("英文26键") })
        Tab(selected = sideTarget == null && fourteen, onClick = { changeTab(false, null, FOURTEEN) }, enabled = !busy,
            text = { Text("中文14键") }, modifier = Modifier.testTag("symbol-target:k14"))
        // 自建的双字母布局各有一页，标签用布局自己的名字；没有合并键的自建布局按 26 键位置，在「中文26键」里改。
        customLayouts.forEach { layout ->
            Tab(selected = sideTarget == null && merged == layout.id, onClick = { changeTab(false, null, layout.id) }, enabled = !busy,
                text = { Text(layout.name) }, modifier = Modifier.testTag("symbol-target:${layout.id}"))
        }
        sideTargets.forEach { target ->
            Tab(selected = side == target, onClick = { changeTab(english, target.key, merged) }, enabled = !busy,
                text = { Text(sideSymbolPageLabel(target)) }, modifier = Modifier.testTag("symbol-target:${target.key}"))
        }
    }
    if (side != null) SideSymbolEditor(side, onResetReady = { sideReset = it })
    else {
        if (merged == null) {
            // 这三个就是键盘本身的开关（和「键盘布局」「按键与手势」里的是同一个）：切到哪种情形，下面就是那种情形的一套。
            SymbolSwitchRow("数字行", "字母上方加一排数字", inputSettings.numberRow, "symbol-switch-number-row") {
                SettingsPreferences.setNumberRowEnabled(context, it) }
            SymbolSwitchRow("上方角标提示", "显示按键右上角的小符号", hints.up, "symbol-switch-up") {
                SettingsPreferences.setSwipeUpHintsEnabled(context, it) }
            SymbolSwitchRow("下方角标提示", "显示按键左下角的小符号", hints.down, "symbol-switch-down") {
                SettingsPreferences.setSwipeDownHintsEnabled(context, it) }
            Text("现在编辑「${variant.label}」这一套。三个开关组合出六套，各自保存，互不影响。", modifier = Modifier.testTag("symbol-variant"),
                style = MaterialTheme.typography.bodyMedium)
        }
        Text("点按键修改，${topGesture}输入上方，${bottomGesture}输入下方" + (if (merged != null) ""
            else if (english) "。颜色淡的一边键盘上不显示，照样能滑出"
            else "。颜色淡的一边键盘上不显示，照样能滑出；带下划线的跟随全角半角"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val boardRows = if (fourteen) FourteenKeySymbols.rows
            else customLayout?.let(CustomLayoutSymbols::rows)
            ?: QwertySwipeSymbols.rows.map { row -> row.map(Char::toString) }
        // 自建布局和键盘上一样按字母数分键宽（合并键两格宽），每排居中；26 键、14 键每键一格。
        // 打开「合并键均匀排布」时同键盘一样，合并键只比单字母键宽三分之一。
        fun width(key: String): Double = com.kingzcheung.xime.ui.keyboard.letterKeySpan(key, customLayout != null,
            inputSettings.evenMergedKeys).toDouble()
        val rowSlots = boardRows.maxOf { row -> row.sumOf(::width) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 开着数字行时把数字行也画出来（只看不改，键和顺序在「数字行」页改），对照着排第一排。
            if (merged == null && variant.numberRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                inputSettings.numberRowKeys().forEach { key ->
                    Surface(Modifier.weight(1f).height(32.dp), shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(key, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            boardRows.forEach { row ->
                val units = row.sumOf(::width)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (units < rowSlots - 1e-3) Spacer(Modifier.weight(((rowSlots - units) / 2).toFloat()))
                    row.forEach { key ->
                        // 设了上下滑动作的方向，预览和键盘一样写动作名（强调色）。
                        val (boundUp, boundDown) = if (merged == null) LetterSwipeBindings.cornerLabels(swipes, key, reverseSwipe)
                            else null to null
                        Surface(modifier = Modifier.weight(width(key).toFloat()).height(64.dp).testTag("symbol-editor-key:$key")
                            .clickable(enabled = loaded && !busy) { editing = key; message = "" },
                            shape = RoundedCornerShape(8.dp),
                            color = if (editing == key) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly) {
                                Text(boundUp ?: values["up.$key"].orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    modifier = Modifier.alpha(if (variant.showsUp) 1f else 0.38f),
                                    textDecoration = TextDecoration.Underline.takeIf { boundUp == null && "up.$key" in follows },
                                    color = if (boundUp != null) MaterialTheme.colorScheme.primary else Color.Unspecified)
                                Text(key.uppercase(), style = MaterialTheme.typography.titleMedium)
                                Text(boundDown ?: values["down.$key"].orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    modifier = Modifier.alpha(if (variant.showsDown) 1f else 0.38f),
                                    textDecoration = TextDecoration.Underline.takeIf { boundDown == null && "down.$key" in follows },
                                    color = if (boundDown != null) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            }
                        }
                    }
                    if (units < rowSlots - 1e-3) Spacer(Modifier.weight(((rowSlots - units) / 2).toFloat()))
                }
            }
        }
        if (merged == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { rowInput = true; message = "" }, enabled = loaded && !busy,
                modifier = Modifier.testTag("symbol-row-input")) { Text("整排输入") }
            OutlinedButton(onClick = { copying = true; message = "" }, enabled = loaded && !busy,
                modifier = Modifier.testTag("symbol-copy")) { Text("从另一套复制") }
        }
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
    editing?.takeIf { side == null && loaded }?.let { key ->
        KeySymbolDialog(key, values["up.$key"].orEmpty(), values["down.$key"].orEmpty(),
            topGesture, bottomGesture, swipes.takeIf { merged == null },
            // 长按输入时上方符号靠长按，滑动动作占的是空着的那个方向，不挡符号。
            upperSlot = LetterSwipeBindings.upperDirection(reverseSwipe)
                .takeUnless { inputSettings.symbolInputMode == SymbolInputMode.LONG_PRESS },
            lowerSlot = LetterSwipeBindings.lowerDirection(reverseSwipe),
            upShown = variant.showsUp, downShown = variant.showsDown,
            followUp = ("up.$key" in follows).takeIf { merged == null && !english },
            followDown = ("down.$key" in follows).takeIf { merged == null && !english },
            onDismiss = { editing = null },
            onDone = { up, down, nextSwipes, followUp, followDown ->
                editing = null
                val next = values + ("up.$key" to up) + ("down.$key" to down)
                var nextFollows = followOverrides
                if (followUp != null && followUp != ("up.$key" in follows)) nextFollows = nextFollows + ("up.$key" to followUp)
                if (followDown != null && followDown != ("down.$key" in follows)) nextFollows = nextFollows + ("down.$key" to followDown)
                if (next != values || (nextSwipes != null && nextSwipes != swipes) || nextFollows != followOverrides)
                    persist(next, nextSwipes ?: swipes, "", nextFollows)
            })
    }
    if (rowInput) SymbolRowsDialog(values, variant, onDismiss = { rowInput = false }) { next ->
        rowInput = false
        if (next != values) persist(next, swipes, "已整排修改")
    }
    if (copying) {
        // 先选一套，再点「覆盖」才执行（用户 11-16：点了之后会怎么样不清楚）。
        var source by remember { mutableStateOf<CornerVariant?>(null) }
        AlertDialog(onDismissRequest = { copying = false }, modifier = Modifier.testTag("symbol-copy-dialog"),
            title = { Text("从另一套复制") },
            text = {
                Column {
                    Text("现在这一套是「${variant.label}」。选一套，把它的全部上下角标和跟随全角半角的设置搬过来，" +
                        "现在这一套原有的符号会被覆盖；被复制的那一套不变。", style = MaterialTheme.typography.bodySmall)
                    CornerVariant.entries.filter { it != variant }.forEach { other ->
                        Row(Modifier.fillMaxWidth().testTag("symbol-copy:${other.id}").clickable { source = other }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = source == other, onClick = null)
                            Text(other.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = source != null, modifier = Modifier.testTag("symbol-copy-confirm"), onClick = {
                val other = source ?: return@TextButton
                copying = false
                val en = english
                scope.launch {
                    val (copied, copiedFollows) = withContext(Dispatchers.IO) {
                        KeysConfigHelper.qwertySymbolEditorValues(context, en, other) to
                            QwertySwipeSymbols.follows(en, other, QwertySwipeSymbols.loadFollows(context, en, other))
                    }
                    persist(copied, swipes, "已把「${other.label}」这一套复制到「${variant.label}」",
                        QwertySwipeSymbols.slotIds.associateWith { it in copiedFollows })
                }
            }) { Text("覆盖现在这一套") } },
            dismissButton = { TextButton(onClick = { copying = false }) { Text("取消") } })
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("「$tabName」恢复默认？") },
        confirmButton = { TextButton(onClick = {
            confirmReset = false
            if (side != null) sideReset?.invoke() else resetLetters()
        }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    if (savingVersion) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { savingVersion = false }, title = { Text("存为版本") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") },
                singleLine = true, modifier = Modifier.testTag("symbol-version-name")) },
            confirmButton = { TextButton(enabled = QwertySwipeSymbols.versionName(name).isNotEmpty(), onClick = {
                val clean = QwertySwipeSymbols.versionName(name)
                savingVersion = false
                runCatching { QwertySwipeSymbols.saveVersion(context, clean, values, english) }
                    .onSuccess { versions = QwertySwipeSymbols.versions(context, english); message = "已存为「$clean」" }
                    .onFailure { message = "保存失败：${it.message}" }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { savingVersion = false }) { Text("取消") } })
    }
    if (choosingVersion) AlertDialog(onDismissRequest = { choosingVersion = false }, title = { Text("载入版本") },
        text = {
            Column {
                versions.keys.forEach { name ->
                    Row(Modifier.fillMaxWidth().testTag("symbol-version:$name"), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f).clickable {
                            choosingVersion = false
                            persist(versions.getValue(name), swipes, "已载入「$name」")
                        }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            runCatching { QwertySwipeSymbols.deleteVersion(context, name, english) }
                                .onSuccess { versions = QwertySwipeSymbols.versions(context, english)
                                    if (versions.isEmpty()) choosingVersion = false }
                                .onFailure { message = "删除失败：${it.message}" }
                        }) { Icon(Icons.Default.Delete, "删除「$name」") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingVersion = false }) { Text("关闭") } })
}

/** [merged] 里表示内置中文 14 键；其他值是自建布局 id。 */
private val FOURTEEN = InputLayout.MERGED14.id

/**
 * 点一个键弹出的小框：上方、下方两个符号，26 键另有滑动动作。点「完成」才写入，「取消」不改。
 * [swipes] 为 null 时（14 键、自建布局）不显示滑动动作。
 */
@Composable
private fun KeySymbolDialog(key: String, up: String, down: String, topGesture: String, bottomGesture: String,
    swipes: Map<String, String>?, upperSlot: String?, lowerSlot: String,
    upShown: Boolean = true, downShown: Boolean = true, followUp: Boolean? = null, followDown: Boolean? = null,
    onDismiss: () -> Unit, onDone: (String, String, Map<String, String>?, Boolean?, Boolean?) -> Unit) {
    var upText by rememberSaveable(key) { mutableStateOf(up) }
    var downText by rememberSaveable(key) { mutableStateOf(down) }
    // 跟随全角半角：null 表示这一页不分全角半角（英文、14 键、自建布局），不显示开关。
    var upFollows by rememberSaveable(key) { mutableStateOf(followUp == true) }
    var downFollows by rememberSaveable(key) { mutableStateOf(followDown == true) }
    var swipeDraft by remember(key) { mutableStateOf(swipes) }
    // 这个方向设了滑动动作时，对应的符号暂时用不上：写在框下，免得以为改了符号没反应。
    fun shadowedBy(slot: String?): String? = slot?.let { swipeDraft?.get("${key.lowercase()}.$it") }
        ?.let { "现在是「${LetterSwipeBindings.label(it)}」，清掉下面的滑动动作才输入符号" }
    val upNote = shadowedBy(upperSlot)
    val downNote = shadowedBy(lowerSlot)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("symbol-editor-dialog"),
        title = { Text(key.uppercase()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = upText, onValueChange = { upText = it }, singleLine = true,
                    label = { Text("上方（$topGesture）" + if (upShown) "" else "，键盘上不显示") }, placeholder = { Text("留空不放符号") },
                    supportingText = upNote?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("symbol-editor-up"))
                if (followUp != null) FollowWidthRow(upText, upFollows, "symbol-editor-up-follow") { upFollows = it }
                OutlinedTextField(value = downText, onValueChange = { downText = it }, singleLine = true,
                    label = { Text("下方（$bottomGesture）" + if (downShown) "" else "，键盘上不显示") }, placeholder = { Text("留空不放符号") },
                    supportingText = downNote?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("symbol-editor-down"))
                if (followDown != null) FollowWidthRow(downText, downFollows, "symbol-editor-down-follow") { downFollows = it }
                swipeDraft?.let { current ->
                    LetterSwipeEditor(key, current) { slot, binding ->
                        swipeDraft = if (binding.isEmpty()) current - slot else current + (slot to binding)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            onDone(upText, downText, swipeDraft, upFollows.takeIf { followUp != null }, downFollows.takeIf { followDown != null })
        }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** 本页的开关一律是「标题 + 一行说明 + 右侧 Switch」的整行开关。 */
@Composable
private fun SymbolSwitchRow(title: String, description: String, checked: Boolean, tag: String, enabled: Boolean = true,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = settingsSwitchColors())
    }
}

/** 一格符号的「跟随全角半角」：有全角、半角两种写法才能开，说明里写出两种写法各是什么。 */
@Composable
private fun FollowWidthRow(symbol: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val paired = QwertySwipeSymbols.hasWidthPair(symbol)
    SymbolSwitchRow("跟随全角半角",
        if (paired) "全角时出 ${QwertySwipeSymbols.width(symbol, true)}，半角时出 ${QwertySwipeSymbols.width(symbol, false)}"
        else "这个符号只有一种写法，全角半角都出它",
        checked && paired, tag, enabled = paired, onChange = onChange)
}

/**
 * 「整排输入」：三排键的上方、下方各一行字，一次改完一整排（格式见 [SymbolRowText]）。显示的那一边排在前面。
 */
@Composable
private fun SymbolRowsDialog(values: Map<String, String>, variant: CornerVariant, onDismiss: () -> Unit,
    onDone: (Map<String, String>) -> Unit) {
    val rows = QwertySwipeSymbols.rows.map { row -> row.map(Char::toString) }
    val names = listOf("第一排 Q 到 P", "第二排 A 到 L", "第三排 Z 到 M")
    fun shown(direction: String) = if (direction == "up") variant.showsUp else variant.showsDown
    val directions = listOf("up" to "上方", "down" to "下方").sortedByDescending { shown(it.first) }
    val drafts = remember {
        mutableStateMapOf<String, String>().apply {
            for ((direction, _) in directions) rows.forEachIndexed { index, row ->
                put("$direction.$index", SymbolRowText.format(row.map { values["$direction.$it"].orEmpty() }))
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("symbol-rows-dialog"),
        title = { Text("整排输入") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("一个字符一格，从左到右对应这一排的键；空着的格子写 ${SymbolRowText.EMPTY}。要放短语时，用空格把各格隔开。",
                    style = MaterialTheme.typography.bodySmall)
                for ((direction, directionName) in directions) rows.indices.forEach { index ->
                    OutlinedTextField(value = drafts.getValue("$direction.$index"), onValueChange = { drafts["$direction.$index"] = it },
                        singleLine = true,
                        label = { Text("${names[index]} $directionName" + if (shown(direction)) "" else "（键盘上不显示）") },
                        modifier = Modifier.fillMaxWidth().testTag("symbol-row:$direction.$index"))
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            onDone(values + buildMap {
                for ((direction, _) in directions) rows.forEachIndexed { index, row ->
                    SymbolRowText.parse(drafts.getValue("$direction.$index"), row.size)
                        .forEachIndexed { column, cell -> put("$direction.${row[column]}", cell) }
                }
            })
        }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun LetterSwipeEditor(key: String, swipes: Map<String, String>, change: (String, String) -> Unit) {
    var direction by rememberSaveable { mutableStateOf("left") }
    var chooseDirection by remember { mutableStateOf(false) }
    var chooseAction by remember { mutableStateOf(false) }
    val slot = "$key.$direction"
    val binding = swipes[slot].orEmpty()
    Text("滑动动作", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { chooseDirection = true }) {
                Text(LetterSwipeBindings.directions.getValue(direction))
            }
            DropdownMenu(chooseDirection, onDismissRequest = { chooseDirection = false }) {
                LetterSwipeBindings.directions.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { direction = id; chooseDirection = false })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { chooseAction = true }) {
                Text(LetterSwipeBindings.actions[binding] ?: if (binding.startsWith("text:")) "输入文字" else "沿用原动作")
            }
            DropdownMenu(chooseAction, onDismissRequest = { chooseAction = false }) {
                (mapOf("" to "沿用原动作", "text:" to "输入文字") + LetterSwipeBindings.actions).forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { change(slot, id); chooseAction = false })
                }
            }
        }
    }
    if (binding.startsWith("text:")) OutlinedTextField(value = binding.removePrefix("text:"),
        onValueChange = { change(slot, "text:$it") }, label = { Text("滑动输入的文字") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
}
