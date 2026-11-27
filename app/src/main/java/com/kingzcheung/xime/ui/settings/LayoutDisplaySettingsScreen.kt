package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.twotone.Dialpad
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.FormatColorFill
import androidx.compose.material.icons.twotone.AspectRatio
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.RecordVoiceOver
import androidx.compose.material.icons.twotone.Spellcheck
import androidx.compose.material.icons.twotone.EmojiSymbols
import androidx.compose.material.icons.twotone.DashboardCustomize
import androidx.compose.material.icons.twotone.ScreenRotation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Send
import androidx.compose.material.icons.twotone.Straighten
import androidx.compose.material.icons.twotone.ViewWeek
import androidx.compose.material.icons.twotone.Reorder
import androidx.compose.material.icons.twotone.VerticalAlignBottom
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences

/** 「键盘布局」里各布局一行的图标：26 键用键盘，九键与 14 键用拨号盘，滑行输入用手势。 */
private val LayoutInputCategory.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        LayoutInputCategory.Qwerty -> Icons.TwoTone.Keyboard
        LayoutInputCategory.Nine -> Icons.TwoTone.Dialpad
        LayoutInputCategory.Glide -> Icons.TwoTone.Gesture
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutDisplaySettingsContent(
    onBack: () -> Unit,
    onOpenLayout: (String) -> Unit = {},
    /** 首页或设置搜索直接打开某个子页；返回回到进来的地方。 */
    initialPage: String? = null,
) {
    // One index page; each group opens as its own sub-page. Settings themselves are unchanged.
    var openPage by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(initialPage?.takeIf { p -> LayoutDisplayPages.any { it.first == p } })
    }
    // 首页直接进入的子页（候选词、按键与手势）返回首页，不经过「键盘布局」。
    val direct = initialPage != null
    SettingsSubPages(openPage, onBack = { if (direct) onBack() else openPage = null }, handleBack = !direct) { page ->
        LayoutDisplayPage(page, onBack, onOpenLayout, openPage = { openPage = it },
            closePage = { if (direct) onBack() else openPage = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutDisplayPage(
    page: String?,
    onBack: () -> Unit,
    onOpenLayout: (String) -> Unit,
    openPage: (String) -> Unit,
    closePage: () -> Unit,
) {
    val context = LocalContext.current
    val pageTitle = LayoutDisplayPages.firstOrNull { it.first == page }?.second
    // 「左右滑展开候选」放在这一层：「候选栏按钮」里开关它，「候选样式」里的「每页候选词数」跟着改说法（11-22 他：两个要配套）。
    var swipeExpand by remember { mutableStateOf(SettingsPreferences.isCandidateSwipeExpand(context)) }
    // 候选栏两种用法：默认（1.15.101）／经典（右边一格关）。存的还是两个旧开关，名字不改：经典 = 左右滑展开 + 关闭键放右边。
    // 11-24 加第三种「收键盘」（= 1.15.103）。
    var rightKey by remember { mutableStateOf(SettingsPreferences.candidateRightKey(context)) }
    val rightKeyCloses = rightKey == com.kingzcheung.xime.settings.CandidateRightKey.CLOSE

    KeyboardSettingsScaffold(pageTitle ?: "键盘布局", { if (page != null) closePage() else onBack() },
        preview = true, example = if (page == "candidates") com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Candidates
            else com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Keyboard) { modifier ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = modifier.settingsGutterScroll(listState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()
        ) {
            if (page == null) item {
                SettingsSection(title = "各布局", content = {
                    val japanese = com.kingzcheung.xime.settings.LanguagePreferences.japaneseOn(context)
                    LayoutInputCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        SettingsItem(category.icon, category.title, category.description(japanese),
                            onClick = { onOpenLayout(category.route) }, showArrow = true)
                    }
                })
            }
            if (page == "bottom_gap") item {
                FixedBottomCalibrationSettings()
            }
            if (page == null) item {
                SettingsSection(title = "布局与符号", content = {
                    SettingsItem(Icons.TwoTone.DashboardCustomize, "布局管理", "选择切换栏里的布局，编辑自定义布局",
                        onClick = { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var perOrientation by remember {
                        mutableStateOf(com.kingzcheung.xime.settings.OrientationLayouts.isEnabled(context))
                    }
                    SettingsToggleItem(Icons.TwoTone.ScreenRotation, "横竖屏分开记布局",
                        "横屏、竖屏和折叠屏的两块屏各用各的布局",
                        checked = perOrientation, onCheckedChange = {
                            perOrientation = it; com.kingzcheung.xime.settings.OrientationLayouts.setEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.EmojiSymbols, "按键符号", "按键角标、侧栏和数字行的符号",
                        onClick = { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)
                            .putExtra("edit_symbols", true)) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var punctuationCandidate by remember {
                        mutableStateOf(SettingsPreferences.isPunctuationCommitsCandidateEnabled(context))
                    }
                    SettingsToggleItem(Icons.TwoTone.Spellcheck, "标点上屏首选",
                        "打字时点标点，先上屏首选再接标点",
                        checked = punctuationCandidate, onCheckedChange = {
                            punctuationCandidate = it; SettingsPreferences.setPunctuationCommitsCandidateEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var pairedSymbols by remember { mutableStateOf(SettingsPreferences.isPairedSymbolsEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.DataObject, "输入成对符号",
                        "输入左括号、左引号时自动补上右半边",
                        checked = pairedSymbols, onCheckedChange = {
                            pairedSymbols = it; SettingsPreferences.setPairedSymbolsEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var symbolReturn by remember { mutableStateOf(SettingsPreferences.isSymbolReturnAfterPickEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.EmojiSymbols, "选完符号自动返回",
                        "在符号页点一个符号后回到打字键盘",
                        checked = symbolReturn, onCheckedChange = {
                            symbolReturn = it; SettingsPreferences.setSymbolReturnAfterPickEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var enterSends by remember { mutableStateOf(SettingsPreferences.isEnterSendsEnabled(context)) }
                    SettingsToggleItem(Icons.AutoMirrored.TwoTone.Send, "回车发送",
                        "应用支持时，回车直接发送消息，长按回车换行",
                        checked = enterSends, onCheckedChange = {
                            enterSends = it; SettingsPreferences.setEnterSendsEnabled(context, it)
                        })
                })
            }
            if (page == null) item {
                SettingsSection(title = "分体与键宽", content = {
                    val split = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().splitKeyboardEnabled
                    SettingsToggleItem(SplitKeyboardIcon, "分体键盘",
                        "26 键和 14 键分成左右两半，屏幕窄时不分",
                        checked = split, onCheckedChange = { SettingsPreferences.setSplitKeyboardEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val rightG = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().splitRightG
                    SettingsToggleItem(Icons.TwoTone.Keyboard, "右半边也放 G 键",
                        "分体时右半边也有一个 G 键",
                        checked = rightG, onCheckedChange = { SettingsPreferences.setSplitRightGEnabled(context, it) },
                        lockMessage = if (split) null else "打开上面的「分体键盘」后可用")
                    if (split) SplitRightGLegend(rightG)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val filled = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().fillBlankAreas
                    SettingsToggleItem(Icons.TwoTone.FormatColorFill, "填满留空处",
                        "用键盘背景填满分体中缝和两侧空白",
                        checked = filled, onCheckedChange = { SettingsPreferences.setKeyboardBlankFilled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val wideKeys = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().wideKeys
                    SettingsToggleItem(Icons.TwoTone.Straighten, "加宽按键",
                        "缩小边距和键缝，按键更宽",
                        checked = wideKeys, onCheckedChange = { SettingsPreferences.setWideKeysEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val evenMerged = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().evenMergedKeys
                    SettingsToggleItem(Icons.TwoTone.ViewWeek, "合并键均匀排布",
                        "14 键等合并键布局里，各键宽度更均匀",
                        checked = evenMerged, onCheckedChange = { SettingsPreferences.setEvenMergedKeysEnabled(context, it) },
                        // 分体按 26 键中线拆左右，均匀排布在分体时不起作用：置灰并说明，不让它看着开着却没用。
                        lockMessage = if (split) "关闭「分体键盘」后可用" else null)
                    if (!split) EvenMergedKeysLegend(evenMerged)
                })
            }
            if (page == null) item {
                SettingsSection(title = "尺寸与位置", content = {
                    SettingsItem(Icons.TwoTone.AspectRatio, keyboardPanelTitle(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.RESIZE),
                        "高度、宽度、悬浮、透明度",
                        onClick = { onOpenLayout(SettingsRoutes.KeyboardResize) }, showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.VerticalAlignBottom, "底部留白校准", "调整键盘底排的高度",
                        onClick = { openPage("bottom_gap") }, showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.Reorder, keyboardPanelTitle(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.ARRANGE),
                        "工具栏和菜单里放什么、怎么排",
                        onClick = { onOpenLayout(SettingsRoutes.ToolbarArrange) }, showArrow = true)
                })
            }
            if (page == null) item {
                SettingsSection(title = "数字键盘", content = {
                    var autoNumberKeyboard by remember {
                        mutableStateOf(SettingsPreferences.isAutoNumberKeyboardEnabled(context))
                    }
                    Row(
                        modifier = Modifier.settingsSearchAnchor("数字框自动数字键盘")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "数字框自动数字键盘",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "在号码、验证码等输入框里自动打开数字键盘",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoNumberKeyboard,
                            onCheckedChange = { newValue ->
                                autoNumberKeyboard = newValue
                                SettingsPreferences.setAutoNumberKeyboardEnabled(context, newValue)
                            }
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 数字位置（11-22 群里有人要，默认 1 2 3 在上不变）：两张小键盘图点选，见 NumberPadOrderChoice.kt。
                    NumberPadOrderChoice()
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 数字键盘左栏（用户 11-09）：进「按键符号 → 数字侧栏」改字、加减格子、调顺序。
                    SettingsItem(Icons.TwoTone.EmojiSymbols, "左栏符号",
                        "数字键盘左侧一列的符号",
                        onClick = { com.kingzcheung.xime.ui.keyboard.openSideSymbolEditor(context,
                            com.kingzcheung.xime.ui.keyboard.SideSymbolTarget.NUMBER) },
                        showArrow = true, searchKey = "数字键盘左栏符号")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 数字行（默认关）：点开关打开，点这一行进「按键符号 → 数字行」改键和顺序。
                    val numberRowPrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                    val numberRow = numberRowPrefs.numberRow
                    SettingsToggleItem(Icons.TwoTone.FormatListNumbered, "数字行",
                        "在 26 键、14 键字母上方加一排数字",
                        checked = numberRow, onCheckedChange = { SettingsPreferences.setNumberRowEnabled(context, it) },
                        onClick = { com.kingzcheung.xime.ui.keyboard.openSideSymbolEditor(context,
                            com.kingzcheung.xime.ui.keyboard.SideSymbolTarget.NUMBER_ROW) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 密码框自动数字行（默认开）：在密码输入框使用 26 键时自动弹出数字行，离开后恢复。
                    SettingsToggleItem(Icons.TwoTone.Dialpad, "密码框自动数字行",
                        if (numberRow) "已全局开启数字行；关闭全局数字行后仍会在密码框弹出"
                        else "在密码输入框使用 26 键时自动弹出数字行",
                        checked = numberRowPrefs.passwordAutoNumberRow,
                        onCheckedChange = { SettingsPreferences.setPasswordAutoNumberRowEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 数字行和字母一样高（#444，默认关）：全局数字行或密码框自动数字行开着时显示。
                    if (numberRow || numberRowPrefs.passwordAutoNumberRow) {
                        SettingsToggleItem(Icons.TwoTone.FormatListNumbered, "数字行和字母一样高",
                            "关着时数字行矮一点。键盘总高不变",
                            checked = numberRowPrefs.numberRowFullHeight,
                            onCheckedChange = { SettingsPreferences.setNumberRowFullHeight(context, it) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    // 打数字出短语（别人请求；11-24 起默认开）：见 service/DigitPhrases.kt。
                    var digitPhrases by remember { mutableStateOf(com.kingzcheung.xime.service.DigitPhrases.isEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.Dialpad, "数字出短语",
                        "打数字时，候选里给出编码相符的「我的短语」",
                        checked = digitPhrases, onCheckedChange = {
                            digitPhrases = it
                            com.kingzcheung.xime.service.DigitPhrases.setEnabled(context, it)
                        },
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DIGIT_PHRASES)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 英文出短语（别人请求 #269，默认关）：见 service/EnglishPhrases.kt。
                    var englishPhrases by remember { mutableStateOf(com.kingzcheung.xime.service.EnglishPhrases.isEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.Dialpad, "英文出短语",
                        "英文打出短语编码时，候选里给出「我的短语」",
                        checked = englishPhrases, onCheckedChange = {
                            englishPhrases = it
                            com.kingzcheung.xime.service.EnglishPhrases.setEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 联想常用数字（用户 11-04 要求，默认开）：见 service/NumberHistory.kt。关掉即清空记下的数字。
                    var numberHistory by remember { mutableStateOf(com.kingzcheung.xime.service.NumberHistory.isEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.Dialpad, "联想常用数字",
                        "打过的常用长数字，打开头时直接给出",
                        checked = numberHistory, onCheckedChange = {
                            numberHistory = it
                            com.kingzcheung.xime.service.NumberHistory.setEnabled(context, it)
                        })
                })
            }
            if (page == "candidates") item {
                // 11-22 他：「拼音位置这么重要的东西还是放最后?」候选词页按常用程度排：拼音显示 → 候选样式 → 候选栏按钮 → 注释；
                // 各组里也是常用的在前。只换位置，键和默认值都没动。
                SettingsSection(title = "拼音显示", content = {
                    var inputTextLocation by remember {
                        mutableStateOf(SettingsPreferences.getInputTextLocation(context))
                    }

                    Column(Modifier.settingsSearchAnchor("编码显示")) {
                        Text(
                            text = "编码显示",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                        )
                        Text(
                            text = "未上屏的编码显示在哪里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CodeDisplayCard(
                                title = "显示在输入框",
                                isSelected = inputTextLocation == SettingsPreferences.INPUT_TEXT_INPUT_BOX,
                                showCodeInInputBox = true,
                                onClick = {
                                    inputTextLocation = SettingsPreferences.INPUT_TEXT_INPUT_BOX
                                    SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            CodeDisplayCard(
                                title = "显示在候选栏",
                                isSelected = inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR,
                                showCodeInInputBox = false,
                                onClick = {
                                    inputTextLocation = SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                                    SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR) {
                        var showPreview by remember { mutableStateOf(SettingsPreferences.showPreeditPreview(context)) }
                        SettingsToggleItem(Icons.TwoTone.DataObject, "显示拼音编辑栏", "打字时显示拼音，部分方案可点按修改",
                            showPreview, { showPreview = it; SettingsPreferences.setShowPreeditPreview(context, it) })
                        if (showPreview) {
                            // 拼音位置三选一（11-19）：默认照旧浮在键盘上方的气泡；「紧贴候选上方」是贴在键盘边框上的小签（11-20 他按微信那张图定的）；
                            // 「候选栏左侧」11-20 他要求去掉，存过的（含老开关「拼音放在键盘内」）按小签读。两张图例卡，和上面「编码显示」一个样（11-20「同样需要设置图例」）。
                            // 11-21 他：「总共是 4 种样式，每种都能调整位置，左中右」——四张样式卡（两行），下面三张靠哪边的卡。
                            var preeditStyle by remember { mutableStateOf(SettingsPreferences.preeditStyle(context)) }
                            var preeditAlign by remember { mutableStateOf(SettingsPreferences.preeditAlign(context)) }
                            val styles = listOf(
                                Triple(SettingsPreferences.PREEDIT_STYLE_BUBBLE, "气泡", "浮在键盘上方，会挡住一点输入框"),
                                Triple(SettingsPreferences.PREEDIT_STYLE_LINE, "小标签", "贴着键盘上边，和拼音一样宽，不挡输入框"),
                                Triple(SettingsPreferences.PREEDIT_STYLE_BLOCK, "方块", "比小标签更高、字更大，不挡输入框"),
                                Triple(SettingsPreferences.PREEDIT_STYLE_STRIP, "整行", "键盘加高一行放拼音，和键盘连成一块。"),
                            )
                            val aligns = listOf(
                                SettingsPreferences.PREEDIT_ALIGN_LEFT to "靠左",
                                SettingsPreferences.PREEDIT_ALIGN_CENTER to "居中",
                                SettingsPreferences.PREEDIT_ALIGN_RIGHT to "靠右",
                            )
                            Column(Modifier.fillMaxWidth().settingsSearchAnchor("拼音位置", bleed = 16.dp, bleedVertical = 6.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("拼音位置", style = MaterialTheme.typography.titleSmall)
                                styles.chunked(2).forEach { pair ->
                                    Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        pair.forEach { (style, label, _) ->
                                            PreeditStyleCard(title = label, style = style, isSelected = preeditStyle == style,
                                                onClick = { preeditStyle = style; SettingsPreferences.setPreeditStyle(context, style) },
                                                modifier = Modifier.weight(1f), align = preeditAlign)
                                        }
                                    }
                                }
                                styles.firstOrNull { it.first == preeditStyle }?.let { (_, _, description) ->
                                    Text(description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                            Column(Modifier.fillMaxWidth().settingsSearchAnchor("拼音靠哪边", bleed = 16.dp, bleedVertical = 6.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("拼音靠哪边", style = MaterialTheme.typography.titleSmall)
                                // 140dp：贴边方块比小签高一截（11-21 图例按实际比例画），130dp 时方块会把键盘挤出卡片。
                                Row(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    aligns.forEach { (align, label) ->
                                        PreeditStyleCard(title = label, style = preeditStyle, isSelected = preeditAlign == align,
                                            onClick = { preeditAlign = align; SettingsPreferences.setPreeditAlign(context, align) },
                                            modifier = Modifier.weight(1f), align = align, testTag = "preedit-align:$align")
                                    }
                                }
                            }
                            // 贴边拼音显示纠正读音（#586，默认开 = 原来的样子）：只在小标签、方块、整行时显示，气泡本来就照打的字母。
                            if (preeditStyle != SettingsPreferences.PREEDIT_STYLE_BUBBLE) {
                                var lineCorrected by remember { mutableStateOf(SettingsPreferences.preeditLineCorrected(context)) }
                                SettingsToggleItem(Icons.TwoTone.Spellcheck, "拼音显示改正后的读音",
                                    "首选是纠错来的词时，拼音换成改正后的读音，改动的字母标红。关掉时照打的字母显示",
                                    lineCorrected, { lineCorrected = it; SettingsPreferences.setPreeditLineCorrected(context, it) })
                            }
                            // 拼音气泡底色可调透明（用户 11-01「我就喜欢透明度」）；点开的编辑条用同一个值（11-16）。只在键盘上方会浮出气泡时显示。
                            val bubblePrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                            // 选「显示在输入框」时没有气泡；页面上的选择先于键盘偏好刷新，所以也看本页的选择。
                            if (inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR &&
                                bubblePrefs.preeditStyle == SettingsPreferences.PREEDIT_STYLE_BUBBLE && bubblePrefs.showCodeInCandidateBar) {
                                var bubbleOpacity by remember(bubblePrefs.preeditBubbleOpacity) { mutableStateOf(bubblePrefs.preeditBubbleOpacity) }
                                Column(
                                    modifier = Modifier.settingsSearchAnchor("拼音气泡不透明度")
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "拼音气泡不透明度：${kotlin.math.round(bubbleOpacity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "调低更透明",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = bubbleOpacity,
                                        onValueChange = { bubbleOpacity = SettingsPreferences.normalizePreeditBubbleOpacity(it) },
                                        onValueChangeFinished = { SettingsPreferences.setPreeditBubbleOpacity(context, bubbleOpacity) },
                                        valueRange = 0.2f..1f,
                                        steps = 15
                                    )
                                }
                            }
                            // 拼音标调（用户 11-20「能不能在拼音上显示声调」）：只改气泡／小签里的显示，默认关。
                            var preeditTones by remember { mutableStateOf(SettingsPreferences.preeditTones(context)) }
                            SettingsToggleItem(Icons.TwoTone.RecordVoiceOver, "拼音显示声调",
                                "按首选字标调，如 jiù'shì'zhè'yàng，只改显示",
                                preeditTones, { preeditTones = it; SettingsPreferences.setPreeditTones(context, it) })
                        }
                    }
                    // 不常改的两项放在这一组最后（11-22 他：按常用程度排）。
                    DoublePinyinRawPreeditToggle()
                    var showCorrectionMarks by remember { mutableStateOf(SettingsPreferences.showCorrectionMarks(context)) }
                    SettingsToggleItem(Icons.TwoTone.Spellcheck, "显示纠错标记",
                        "在纠错候选旁显示改正后的拼音",
                        showCorrectionMarks, { showCorrectionMarks = it; SettingsPreferences.setShowCorrectionMarks(context, it) })
                })
            }
            if (page == "candidates") item {
                SettingsSection(title = "候选样式", content = {
                    val candidateTextSizePref = SettingsPreferences.getCandidateTextSize(context)
                    var candidateTextSize by remember(candidateTextSizePref) {
                        mutableStateOf(candidateTextSizePref.toFloat())
                    }

                    Column(
                        modifier = Modifier.settingsSearchAnchor("候选字大小").padding(16.dp)
                    ) {
                        // 当前字号、默认位置和恢复默认（用户 11-22：「没有任何刻度，我甚至都不知道原来默认在哪里」）。
                        val defaultSp = SettingsPreferences.DEFAULT_CANDIDATE_TEXT_SIZE
                        val shownSp = kotlin.math.round(candidateTextSize).toInt()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "候选字大小",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "$shownSp sp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("candidate-text-size-value")
                            )
                            if (shownSp == defaultSp) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "默认",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    candidateTextSize = defaultSp.toFloat()
                                    SettingsPreferences.setCandidateTextSize(context, defaultSp)
                                },
                                enabled = shownSp != defaultSp,
                                modifier = Modifier.testTag("candidate-text-size-reset")
                            ) { Text("恢复默认") }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        CandidateTextSizeCard(
                            candidateTextSize = candidateTextSize,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = candidateTextSize,
                            onValueChange = { candidateTextSize = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setCandidateTextSize(context, kotlin.math.round(candidateTextSize).toInt())
                            },
                            // 上限 22 → 44（用户 11-22）；超过 22 时候选栏跟着变高，默认 19 不变。
                            valueRange = com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MIN_SP.toFloat()..
                                com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MAX_SP.toFloat(),
                            steps = com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MAX_SP -
                                com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MIN_SP - 1
                        )
                        SliderScaleMarks(
                            range = com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MIN_SP.toFloat()..
                                com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MAX_SP.toFloat(),
                            marks = listOf(
                                SliderMark(com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MIN_SP.toFloat(), "12"),
                                SliderMark(defaultSp.toFloat(), "$defaultSp", "默认"),
                                SliderMark(com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_FIT_SP.toFloat(), "22"),
                                SliderMark(33f, "33"),
                                SliderMark(com.kingzcheung.xime.ui.keyboard.CANDIDATE_TEXT_SIZE_MAX_SP.toFloat(), "44"),
                            ),
                            highlight = defaultSp.toFloat(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "超过 22 时候选栏会变高",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var wideCandidateSpacing by remember {
                        mutableStateOf(SettingsPreferences.isWideCandidateSpacing(context))
                    }
                    Row(
                        modifier = Modifier.settingsSearchAnchor("候选词间距加宽")
                            .fillMaxWidth()
                            .testTag("wide-candidate-spacing-setting")
                            .toggleable(
                                value = wideCandidateSpacing,
                                role = Role.Switch,
                                onValueChange = {
                                    wideCandidateSpacing = it
                                    SettingsPreferences.setWideCandidateSpacing(context, it)
                                },
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("候选词间距加宽", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium)
                            Text("候选之间留宽一些，用竖线隔开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = wideCandidateSpacing, onCheckedChange = null)
                    }
                    CandidateSpacingLegend(wideCandidateSpacing)
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 开了「左右滑展开候选」：候选栏只排一屏，露全最后半截那个再往左拉就展开全部，用不上这个数（11-22 他：
                    // 「一个屏幕就是一页」）。存的数不动，关掉开关后照旧按它。
                    val pageSizePref = SettingsPreferences.getPageSize(context)
                    val effectiveValue = if (pageSizePref == 0) 20f else pageSizePref.toFloat()
                    var pageSizeSlider by remember(effectiveValue) {
                        mutableStateOf(effectiveValue)
                    }

                    Column(
                        modifier = Modifier.settingsSearchAnchor("每页候选词数").padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "每页候选词数",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (swipeExpand) "一屏" else "${pageSizeSlider.toInt()} 个",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = pageSizeSlider,
                            onValueChange = { pageSizeSlider = it },
                            onValueChangeFinished = {
                                val intValue = pageSizeSlider.toInt()
                                SettingsPreferences.setPageSize(context, intValue)
                            },
                            valueRange = 20f..50f,
                            steps = 29,
                            enabled = !swipeExpand,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (swipeExpand) "已开「左右滑展开候选」：候选栏显示多少就是一页，滑到头再往外拉就展开全部。关掉后按这里的数"
                                else "下次打字时生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                })
            }
            // 候选栏左右的按钮（11-02 整理：原来 11 行挤在一张「候选词」卡片里，按用途拆成四组）。
            // 11-24 他：「当时不是让你这样做的吗，为什么做出来不一样」——照 11-23 他认可的效果图做（cand-mode-picker-1123/mock.png）：
            // 卡片里一行名字＋标签＋圆点，下面是真样子的候选栏和说明；卡片下面三个开关没有图标。
            // 和效果图不同的只有他后来自己要改的：名字「默认」「经典」、去掉小标题、开关叫「左右滑展开候选」、锁住时能点过去、
            // 去掉「按压高亮」、两边都能拉（「左右拉」），以及第三张卡「收键盘」（= 1.15.103，同一个样子）。
            if (page == "candidates") item {
                Column {
                SettingsSection(title = "候选栏按钮", content = {
                    // 锚点名「候选栏右边的键」留着不显示，给搜索和「左右滑展开候选」锁定说明里的跳转用。
                    Column(Modifier.settingsSearchAnchor("候选栏右边的键").fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 11-25 他：「顺序不对」——默认、经典在前，后加的「右侧收起」放最后。
                        for (mode in listOf(com.kingzcheung.xime.settings.CandidateRightKey.EXPAND,
                            com.kingzcheung.xime.settings.CandidateRightKey.CLOSE,
                            com.kingzcheung.xime.settings.CandidateRightKey.HIDE)) {
                            CandidateModeCard(mode, candidateRightKeyName(mode), rightKey == mode, onClick = {
                                // 「收键盘」「经典」右边不放 ▾/▴，展开只能靠滑，所以一并打开左右滑；选回「默认」时恢复成原来的样子。
                                SettingsPreferences.setCandidateRightKey(context, mode)
                                rightKey = SettingsPreferences.candidateRightKey(context)
                                swipeExpand = SettingsPreferences.isCandidateSwipeExpand(context)
                            })
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    // 11-23 他选：左右滑单独一个开关，「默认」也能滑（▾/▴ 照旧在）；默认关。「收键盘」「经典」靠它展开，锁成开着。
                    CandidateSwitchRow("左右滑展开候选", "滑到头再往外拉就展开，左右划都能收起", swipeExpand, {
                        swipeExpand = it
                        SettingsPreferences.setCandidateSwipeExpand(context, it)
                    // 锁住时说清是谁锁的，「」里点一下跳到上面那张卡并高亮（11-24 他：找不着在哪改、也跳不过去）。
                    }, lockMessage = if (rightKey != com.kingzcheung.xime.settings.CandidateRightKey.EXPAND)
                        "选了「${candidateRightKeyName(rightKey)}」时只能靠滑展开候选，所以一直开着；点「${candidateRightKeyName(rightKey)}」去改回「默认」就能关" else null)
                    // 首选上拖选改局部（11-24 他：默认打开，只给超过 6 个字的长句）；按住不拖、关着时长按首选照旧出固定、删除的卡片。
                    var sentenceDragEdit by remember { mutableStateOf(SettingsPreferences.isSentenceDragEdit(context)) }
                    CandidateSwitchRow("长句拖选改局部", "首选超过 6 个字时，按住拖过一段就只改这一段；按住不拖照旧出固定、删除",
                        sentenceDragEdit, {
                            sentenceDragEdit = it
                            SettingsPreferences.setSentenceDragEdit(context, it)
                        }, tag = "sentence-drag-edit-setting")
                    // 这两个只管左边的键；「经典」时取消在右边，不显示。两个各管各的（11-24 用户反馈：取消键关了联想就没法关）：
                    // 「显示 ‹ 取消键」只管打字时的「‹」，「关联想用 ⊗」开着时联想左边就是 ⊗，不看取消键开没开。存的键名不变。
                    if (!rightKeyCloses) {
                        var showCandidateCancel by remember {
                            mutableStateOf(SettingsPreferences.shouldShowCandidateCancelButton(context))
                        }
                        var associationCancelCross by remember {
                            mutableStateOf(SettingsPreferences.isAssociationCancelCross(context))
                        }
                        CandidateSwitchRow("显示 ‹ 取消键", "", showCandidateCancel, {
                            showCandidateCancel = it
                            SettingsPreferences.setShowCandidateCancelButton(context, it)
                        }, tag = "candidate-cancel-setting")
                        CandidateSwitchRow("关联想用 ⊗", "联想时左边放 ⊗，点它关掉联想；取消键关着也有", associationCancelCross, {
                            associationCancelCross = it
                            SettingsPreferences.setAssociationCancelCross(context, it)
                        }, tag = "association-cancel-cross-setting")
                    }
                    Spacer(Modifier.height(4.dp))
                    // 「候选栏按钮按压高亮」去掉（11-24 他：关着按下去也亮，有没有都一样）。按下时照旧有默认的高亮。
                })
                // 效果图卡片下面那一行小字。选了「收键盘」「经典」时开关上已经写着为什么锁住，不再重复。
                if (rightKey == com.kingzcheung.xime.settings.CandidateRightKey.EXPAND) {
                    GlyphText("左右滑展开候选默认关，开了也保留右边 ▾", Modifier.padding(start = 4.dp, top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                }
            }
            if (page == "candidates") item {
                SettingsSection(title = "注释", content = {
                    var commentMode by remember { mutableStateOf(SettingsPreferences.candidateCommentMode(context)) }
                    Column(Modifier.settingsSearchAnchor("候选注释")) {
                        Text("候选注释", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                        Text("自动：宽时显示，窄时隐藏",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().choiceCardRowHeight(136.dp).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.kingzcheung.xime.settings.CandidateCommentMode.entries.forEach { mode ->
                                IllustratedChoiceCard(when (mode) {
                                    com.kingzcheung.xime.settings.CandidateCommentMode.AUTO -> "自动"
                                    com.kingzcheung.xime.settings.CandidateCommentMode.SHOW -> "显示"
                                    com.kingzcheung.xime.settings.CandidateCommentMode.HIDE -> "隐藏"
                                }, commentMode == mode,
                                    onClick = {
                                        commentMode = mode
                                        SettingsPreferences.setCandidateCommentMode(context, mode)
                                        com.kingzcheung.xime.gloss.CandidateGlossary.prepare(context)
                                    },
                                    modifier = Modifier.weight(1f), tag = "candidate-comments:${mode.name}") {
                                    CandidateCommentIllustration(mode)
                                }
                            }
                        }
                    }

                    // 注释隐藏时内容选项无意义：整段不显示，也不会触发释义词典下载。
                    if (commentMode != com.kingzcheung.xime.settings.CandidateCommentMode.HIDE) {
                        val prefs = SettingsPreferences.getPrefsPublic(context)
                        var wubiHints by remember { mutableStateOf(prefs.getBoolean(SettingsPreferences.KEY_HANDWRITING_WUBI_HINTS, false)) }
                        SettingsToggleItem(Icons.TwoTone.FormatColorFill, "手写候选显示五笔编码",
                            "用五笔方案时，手写候选显示五笔编码", wubiHints, {
                                wubiHints = it
                                prefs.edit().putBoolean(SettingsPreferences.KEY_HANDWRITING_WUBI_HINTS, it).apply()
                            })
                        var commentContent by remember { mutableStateOf(SettingsPreferences.candidateCommentContent(context)) }
                        Column(Modifier.settingsSearchAnchor("候选注释内容")) {
                            Text("候选注释内容", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                            // 九键「去掉 0 键，回车加高」开着时没有「下一项」，不写九键那半句（用户 11-15）。
                            Text("释义：中文候选显示英文，英文候选显示中文",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp))
                            var glossDownloadRequest by remember { mutableStateOf(0) }
                            if (commentContent == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS) {
                                GlossDictionaryStatus(glossDownloadRequest)
                            }
                            Row(Modifier.fillMaxWidth().choiceCardRowHeight(136.dp).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.kingzcheung.xime.settings.CandidateCommentContent.entries.forEach { content ->
                                    IllustratedChoiceCard(when (content) {
                                        com.kingzcheung.xime.settings.CandidateCommentContent.PINYIN -> "拼音"
                                        com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS -> "释义"
                                    }, commentContent == content,
                                        onClick = {
                                            // 刚选释义且词典没下载：直接开始下载，不让用户去商店找。
                                            if (content == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS &&
                                                commentContent != content) glossDownloadRequest++
                                            // 「自动」在手机竖屏（候选栏不到 480dp）不显示注释，选了释义也看不到英文（B 站反馈 10-31）。
                                            // 选释义就是要看释义：自动改成显示，上面的选择跟着变，想窄屏隐藏可以再改回自动。
                                            if (content == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS &&
                                                commentMode == com.kingzcheung.xime.settings.CandidateCommentMode.AUTO) {
                                                commentMode = com.kingzcheung.xime.settings.CandidateCommentMode.SHOW
                                                SettingsPreferences.setCandidateCommentMode(context, commentMode)
                                            }
                                            commentContent = content
                                            SettingsPreferences.setCandidateCommentContent(context, content)
                                            com.kingzcheung.xime.gloss.CandidateGlossary.prepare(context)
                                        },
                                        modifier = Modifier.weight(1f), tag = "candidate-comment-content:${content.name}") {
                                        CandidateCommentContentIllustration(content)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                })
            }

            if (page == "key_tips") item { KeyTipsSettings() }
            if (page == "gestures") item {
                InputExperienceSettings(setOf(InputExperiencePart.SYMBOLS), "角标与长按")
            }
            if (page == "gestures") item { InputExperienceSettings(setOf(InputExperiencePart.DELETE), "删除键") }
            if (page == "gestures") item { InputExperienceSettings(setOf(InputExperiencePart.CURSOR), "光标与空格") }
            if (page == "gestures") item { InputExperienceSettings(setOf(InputExperiencePart.EDGE), "屏幕边缘") }
            // 大多数人不会去调的时长、距离滑条收在一起，默认收起（用户 10-31）；设置搜索点中会自动展开。
            if (page == "gestures") item {
                InputExperienceSettings(setOf(InputExperiencePart.TIMING), "时长与距离",
                    collapsedSummary = "长按时长、光标灵敏度")
            }
            // 隐藏操作的说明（用户 10-31）：这一页最下面一行，点进去是「按键技巧」；它不是设置，不占上面的位置。
            if (page == "gestures") item {
                SettingsSection(title = "说明", searchKey = "按键技巧入口", content = {
                    SettingsItem(Icons.TwoTone.Lightbulb, "按键技巧",
                        "Shift、Ctrl、空格、删除键的小技巧", showArrow = true,
                        onClick = { onOpenLayout("${SettingsRoutes.LayoutDisplay}?page=key_tips") })
                })
            }
        }
    }
}

/** 普通 Activity 中的校准入口，底边手势被系统拦走时仍可点按。 */
@Composable
private fun FixedBottomCalibrationSettings() {
    val context = LocalContext.current
    var bottomPosition by remember(context) { mutableStateOf(SettingsPreferences.keyboardBottomPosition(context)) }
    var adjustment by remember(context) { mutableIntStateOf(SettingsPreferences.getKeyboardBottomAdjustmentDp(context)) }
    var diagnostic by remember(context) { mutableStateOf(SettingsPreferences.getKeyboardBottomDiagnostic(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_POSITION || key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_DIAGNOSTIC ||
                SettingsPreferences.isBottomCalibrationKey(key)) {
                bottomPosition = SettingsPreferences.keyboardBottomPosition(context)
                adjustment = SettingsPreferences.getKeyboardBottomAdjustmentDp(context)
                diagnostic = SettingsPreferences.getKeyboardBottomDiagnostic(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 三档都从所选位置往下调（键盘那边到 0 为止）、往上抬，只存一套（11-19：原来跟着隐藏的贴底开关换存储，切档时数值会变）。
    val lowest = -80
    val value = adjustment.coerceAtLeast(lowest)
    val reference = "「${bottomPosition.label}」的位置"
    val position = when {
        value == 0 -> "底部留白 0 dp"
        value > 0 -> "从${reference}抬高 ${value} dp"
        else -> "从${reference}调低 ${-value} dp"
    }
    SettingsSection("固定键盘") {
        // 「底部位置」三选一（11-08）：原「手势导航时键盘贴底」开关并进来，下面的留白在所选位置上微调。
        Column(Modifier.fillMaxWidth().settingsSearchAnchor("底部位置", bleed = 16.dp, bleedVertical = 6.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("底部位置", style = MaterialTheme.typography.titleSmall)
            SettingsPreferences.KeyboardBottomPosition.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { SettingsPreferences.setKeyboardBottomPosition(context, option) }
                    .testTag("bottom-position:${option.name}").padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = bottomPosition == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        Text(option.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(position, style = MaterialTheme.typography.titleSmall)
            Text("底下空太多就调低，底排被挡住就抬高",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { SettingsPreferences.setKeyboardBottomAdjustmentDp(context, (value - 2).coerceAtLeast(lowest)) },
                    enabled = value > lowest) { Text("调低") }
                OutlinedButton(onClick = { SettingsPreferences.setKeyboardBottomAdjustmentDp(context, (value + 2).coerceAtMost(80)) },
                    enabled = value < 80) { Text("抬高") }
            }
            TextButton(onClick = { SettingsPreferences.resetKeyboardBottomCalibration(context) },
                modifier = Modifier.testTag("bottom-calibration-reset"),
                enabled = bottomPosition != SettingsPreferences.KeyboardBottomPosition.AUTO || adjustment != 0) { Text("恢复默认") }
        }
        // 底部诊断（11-19）：反馈「被挡住」「底下有缝」时把这一行一起发来，就知道是哪种手机、系统报了什么、按哪一步判断的。
        diagnostic?.let { line ->
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(Modifier.fillMaxWidth().settingsSearchAnchor("底部诊断").padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("底部诊断", style = MaterialTheme.typography.titleSmall)
                Text("底部被挡住或有缝时，复制这一行发给作者",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bottom-diagnostic"))
                }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(line)) }) { Text("复制") }
            }
        }
    }
}

/** 「键盘布局」之外直接从首页进入的两页：route, title, one-line description. */
internal val LayoutDisplayPages = listOf(
    Triple("bottom_gap", "底部留白校准", "调整键盘底排的高度"),
    Triple("candidates", "候选词", "字号、注释、按钮、编码显示"),
    Triple("gestures", "按键与手势", "角标、删除键、空格、光标"),
    // 不在首页列出，从「按键与手势」最下面进入。
    Triple("key_tips", "按键技巧", "Shift、Ctrl、空格、删除键的小技巧"),
)

/** 按键显示：字号、键帽大小写、角标与双拼键位提示、点按气泡、顶部样式和底部圆角；放在「主题与外观」。 */
@Composable
internal fun KeyboardAppearanceSettings() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InputExperienceSettings(setOf(InputExperiencePart.KEY_TEXT), "键盘上显示的字")
                SettingsSection(title = "角标与气泡", content = {
                    // 11-01：去掉和「双拼键位提示」的互锁和「和角标一起显示」开关；都开着时键面自动分层（KeyButton 的 dpWithCorners）。
                    val keyPrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                    CornerHintVisibilitySettings(Modifier.padding(16.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 双拼键位提示（11-01 从「双拼方案」页搬来，存的值不变）：和角标同在键面上，放一起。
                    Row(
                        modifier = Modifier.settingsSearchAnchor("双拼键位提示")
                            .fillMaxWidth()
                            .testTag("double-pinyin-key-hints")
                            .toggleable(value = keyPrefs.doublePinyinKeyHints, role = Role.Switch) {
                                keyPrefs.copy(doublePinyinKeyHints = it).save(context)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "双拼键位提示",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "用双拼时，按键上显示声母和韵母",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.DOUBLE_PINYIN_KEY_HINTS)
                        }
                        Switch(checked = keyPrefs.doublePinyinKeyHints, onCheckedChange = null)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 角标不透明度（GitHub #7 #60）：乘在原来的淡化上，100% 就是原样；开了玻璃也起作用。
                    var cornerOpacity by remember(keyPrefs.cornerHintOpacity) { mutableStateOf(keyPrefs.cornerHintOpacity) }
                    Column(
                        modifier = Modifier.settingsSearchAnchor("角标不透明度")
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "角标不透明度：${kotlin.math.round(cornerOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = cornerOpacity,
                            onValueChange = { cornerOpacity = com.kingzcheung.xime.ui.keyboard.normalizeCornerHintOpacity(it) },
                            onValueChangeFinished = {
                                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context)
                                    .copy(cornerHintOpacity = cornerOpacity).save(context)
                            },
                            valueRange = 0.1f..1f,
                            steps = 17,
                            modifier = Modifier.testTag("corner-hint-opacity")
                        )
                        // 图例：上面三个开关和这里的不透明度一起实时变化；怎么输入角标在「按键与手势」。
                        KeyFaceHintsLegend(keyPrefs.swipeUpHints, keyPrefs.swipeDownHints, keyPrefs.doublePinyinKeyHints, cornerOpacity)
                        SettingsNote("角标的输入方式见「按键与手势 › 按键角标的符号和数字」",
                            jumps = listOf("按键与手势 › 按键角标的符号和数字"))
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 功能键不显示提示字（GitHub #11 #60，别人的需求，默认关）：空格的方案名、删除键的「清空」、重输键的「重输」。
                    Row(
                        modifier = Modifier.settingsSearchAnchor("功能键不显示提示字")
                            .fillMaxWidth()
                            .testTag("hide-function-key-hints")
                            .toggleable(value = keyPrefs.hideFunctionKeyHints, role = Role.Switch) {
                                keyPrefs.copy(hideFunctionKeyHints = it).save(context)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "功能键不显示提示字",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "空格、删除、重输键上不显示提示字",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.HIDE_FUNCTION_KEY_HINTS)
                        }
                        Switch(checked = keyPrefs.hideFunctionKeyHints, onCheckedChange = null)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    val showPressBubble = keyPrefs.showPressBubble
                    Row(
                        modifier = Modifier.settingsSearchAnchor("点按弹出气泡")
                            .fillMaxWidth()
                            .toggleable(value = showPressBubble, role = Role.Switch) {
                                SettingsPreferences.setShowPressBubble(context, it)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "点按弹出气泡",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "按下时在键上方显示放大的字",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.PRESS_BUBBLE)
                        }
                        Switch(checked = showPressBubble, onCheckedChange = null)
                    }
                })
                SettingsSection(title = "键盘顶部样式", content = {
                    // 11-01：主题页「柔化顶部边缘」并进来成三选一；存的还是「顶部圆角」「柔化顶部边缘」两个值，见 KeyboardTopStyle。
                    val roundedTop = com.kingzcheung.xime.ui.keyboard.rememberRoundedKeyboardTop()
                    val softTopEdge = com.kingzcheung.xime.ui.keyboard.rememberGlassSoftTopEdge()
                    val glassOn = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().frostedGlass.enabled
                    val topStyle = com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.of(roundedTop, softTopEdge, glassOn)
                    Column(Modifier.settingsSearchAnchor("键盘顶部样式").fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().choiceCardRowHeight(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.entries.forEach { style ->
                                val enabled = style != com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.SOFT || glassOn
                                IllustratedChoiceCard(style.label, topStyle == style, enabled = enabled,
                                    onClick = {
                                        SettingsPreferences.setRoundedKeyboardTop(context, style.roundedTop)
                                        SettingsPreferences.setGlassSoftTopEdge(context, style.softTopEdge)
                                    },
                                    modifier = Modifier.weight(1f), tag = "keyboard-top-style:${style.name}") {
                                    KeyboardTopStyleIllustration(style)
                                }
                            }
                        }
                        Text(when (topStyle) {
                            com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.ROUNDED -> "固定键盘顶部两角是圆角。"
                            com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.SQUARE -> "固定键盘顶部是直角。"
                            com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.SOFT -> "固定键盘上沿渐变过渡。"
                        } + "悬浮键盘不受影响。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!glassOn) SettingsNote("「柔化」要先打开「材质效果 › 玻璃效果」", jumps = listOf("材质效果 › 玻璃效果"))
                    }
                })
                SettingsSection(title = "键盘底部样式", content = {
                    var rounded by remember { mutableStateOf(SettingsPreferences.roundedKeyboardBottom(context)) }
                    Row(
                        Modifier.settingsSearchAnchor("底部圆角与透明导航栏").fillMaxWidth().testTag("rounded-keyboard-bottom-setting")
                            .toggleable(value = rounded, role = Role.Switch, onValueChange = {
                                rounded = it
                                SettingsPreferences.setRoundedKeyboardBottom(context, it)
                            }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("底部圆角与透明导航栏", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (rounded) "底部圆角，导航栏透明"
                                else "底部直角，导航栏跟键盘同色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = rounded, onCheckedChange = null)
                    }
                })
    }
}

/** 手写：停顿识别、公式标记；放在「语音与手写」。 */
@Composable
internal fun HandwritingSettings() {
    InputExperienceSettings(setOf(InputExperiencePart.HANDWRITING), "手写")
}

/**
 * 「候选栏按钮」下面三个开关的一行，照效果图：没有图标，名字和说明里的 ‹ ⊗ 画成候选栏上的图标。
 * 被锁住（[lockMessage]）时开关拨不动，说明下面写原因，「」里点一下跳过去。
 */
@Composable
private fun CandidateSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null,
    lockMessage: String? = null,
) {
    val locked = lockMessage != null
    Row(
        Modifier.settingsSearchAnchor(title).fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .then(if (locked) Modifier else Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GlyphText(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) GlyphText(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            if (lockMessage != null) SettingsLockNote(lockMessage)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = !locked, colors = settingsSwitchColors())
    }
}

/** 候选栏三种用法的卡片名（11-24 他定默认、经典；11-25 他嫌「收键盘」蠢，改「右侧收起」）。 */
internal fun candidateRightKeyName(mode: com.kingzcheung.xime.settings.CandidateRightKey): String = when (mode) {
    com.kingzcheung.xime.settings.CandidateRightKey.EXPAND -> "默认"
    com.kingzcheung.xime.settings.CandidateRightKey.HIDE -> "右侧收起"
    com.kingzcheung.xime.settings.CandidateRightKey.CLOSE -> "经典"
}
