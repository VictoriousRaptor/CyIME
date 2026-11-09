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
import androidx.compose.material3.Text
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
                    LayoutInputCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        SettingsItem(category.icon, category.title, category.description,
                            onClick = { onOpenLayout(category.route) }, showArrow = true)
                    }
                })
            }
            if (page == "bottom_gap") item {
                FixedBottomCalibrationSettings()
            }
            if (page == null) item {
                SettingsSection(title = "布局与符号", content = {
                    SettingsItem(Icons.TwoTone.DashboardCustomize, "布局管理", "显示或排列切换栏里的布局，编辑自定义按键排列",
                        onClick = { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var perOrientation by remember {
                        mutableStateOf(com.kingzcheung.xime.settings.OrientationLayouts.isEnabled(context))
                    }
                    SettingsToggleItem(Icons.TwoTone.ScreenRotation, "横竖屏分开记布局",
                        "横屏、竖屏各记一个布局 (如横屏 26 键、竖屏九键)，在哪个方向选的就记到哪个方向，转屏后自动换。关闭时横竖屏用同一个布局。",
                        checked = perOrientation, onCheckedChange = {
                            perOrientation = it; com.kingzcheung.xime.settings.OrientationLayouts.setEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.EmojiSymbols, "按键符号", "每个键滑出的符号，侧栏和数字行放什么",
                        onClick = { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)
                            .putExtra("edit_symbols", true)) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val input = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                    SettingsToggleItem(Icons.TwoTone.EmojiSymbols, "符号键循环切换",
                        "合并符号和数字键，点按依次切换符号、数字和原输入法键盘。",
                        checked = input.symbolKeyCycle, onCheckedChange = {
                            input.copy(symbolKeyCycle = it).save(context)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var punctuationCandidate by remember {
                        mutableStateOf(SettingsPreferences.isPunctuationCommitsCandidateEnabled(context))
                    }
                    SettingsToggleItem(Icons.TwoTone.Spellcheck, "标点上屏首选",
                        "打字中点逗号、句号、问号等断句标点时，先上屏首选候选再接标点，nihao 点逗号得到「你好，」。" +
                            "关闭时先上屏打的字母(nihao，)。实体键盘不变。",
                        checked = punctuationCandidate, onCheckedChange = {
                            punctuationCandidate = it; SettingsPreferences.setPunctuationCommitsCandidateEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var pairedSymbols by remember { mutableStateOf(SettingsPreferences.isPairedSymbolsEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.DataObject, "输入成对符号",
                        "点 “ ( 《 【 等开头的一半时连结尾一起输入，光标停在中间；接着点结尾会跳过已有的那个，" +
                            "退格删掉空的一对。实体键盘不变。",
                        checked = pairedSymbols, onCheckedChange = {
                            pairedSymbols = it; SettingsPreferences.setPairedSymbolsEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var enterSends by remember { mutableStateOf(SettingsPreferences.isEnterSendsEnabled(context)) }
                    SettingsToggleItem(Icons.AutoMirrored.TwoTone.Send, "回车发送",
                        "应用把回车设成发送时(如微信「回车键发送消息」)，回车直接发送；关闭时回车换行，发送用 Ctrl+回车。",
                        checked = enterSends, onCheckedChange = {
                            enterSends = it; SettingsPreferences.setEnterSendsEnabled(context, it)
                        })
                })
            }
            if (page == null) item {
                SettingsSection(title = "分体与键宽", content = {
                    val split = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().splitKeyboardEnabled
                    SettingsToggleItem(SplitKeyboardIcon, "分体键盘",
                        "适用于 26 键和 14 键；14 键跨中线的双字母键分体时拆成两个单字母键。屏幕太窄 (手机竖屏) 时自动用完整键盘。间距与尺寸在键盘调整面板中设置。",
                        checked = split, onCheckedChange = { SettingsPreferences.setSplitKeyboardEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val rightG = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().splitRightG
                    SettingsToggleItem(Icons.TwoTone.Keyboard, "右半边也放 G 键",
                        "26 键分体时，右半 h j k l 那排开头再加一个 G，这一排向右对齐到 p 下面，两只手都能按 G。14 键不加。",
                        checked = rightG, onCheckedChange = { SettingsPreferences.setSplitRightGEnabled(context, it) },
                        lockMessage = if (split) null else "打开上面的「分体键盘」后可用")
                    if (split) SplitRightGLegend(rightG)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val filled = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().fillBlankAreas
                    SettingsToggleItem(Icons.TwoTone.FormatColorFill, "填满留空处",
                        "分体中缝和固定键盘两侧的空白用键盘背景铺满，遮住后面的应用；关闭时镂空，透出后面的内容。",
                        checked = filled, onCheckedChange = { SettingsPreferences.setKeyboardBlankFilled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val wideKeys = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().wideKeys
                    SettingsToggleItem(Icons.TwoTone.Straighten, "加宽按键",
                        "键盘两侧留白和按键之间的横向缝变窄，每个键更宽，手机上不容易按到旁边的键；分体键盘不变。",
                        checked = wideKeys, onCheckedChange = { SettingsPreferences.setWideKeysEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val evenMerged = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().evenMergedKeys
                    SettingsToggleItem(Icons.TwoTone.ViewWeek, "合并键均匀排布",
                        "14 键和自己排的合并键布局(如 17、18、20 键)里，合并键只比单字母键宽三分之一，单字母键跟着变宽，" +
                            "每排更匀，小屏上好按。关闭时合并键严格占两个 26 键的位置。分体键盘不变。",
                        checked = evenMerged, onCheckedChange = { SettingsPreferences.setEvenMergedKeysEnabled(context, it) },
                        // 分体按 26 键中线拆左右，均匀排布在分体时不起作用：置灰并说明，不让它看着开着却没用。
                        lockMessage = if (split) "分体键盘开着时不起作用，关掉上面的「分体键盘」后可用" else null)
                    if (!split) EvenMergedKeysLegend(evenMerged)
                })
            }
            if (page == null) item {
                SettingsSection(title = "尺寸与位置", content = {
                    SettingsItem(Icons.TwoTone.AspectRatio, keyboardPanelTitle(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.RESIZE),
                        "高度、宽度、悬浮与分体、透明度，打开键盘菜单里同一个调节面板",
                        onClick = { onOpenLayout(SettingsRoutes.KeyboardResize) }, showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.VerticalAlignBottom, "底部留白校准", "查看真实键盘，调低或抬高底排的位置",
                        onClick = { openPage("bottom_gap") }, showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.Reorder, keyboardPanelTitle(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.ARRANGE),
                        "哪些入口放工具栏、哪些放菜单，以及先后顺序",
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
                                text = "进入号码、验证码等数字输入框时自动弹出数字键盘",
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
                    // 数字键盘左栏（用户 11-09）：进「按键符号 → 数字侧栏」改字、加减格子、调顺序。
                    SettingsItem(Icons.TwoTone.EmojiSymbols, "左栏符号",
                        "数字键盘左侧一列，默认 + - * /。可改字、加减、调顺序，多于 4 个时上下滑。",
                        onClick = { com.kingzcheung.xime.ui.keyboard.openSideSymbolEditor(context,
                            com.kingzcheung.xime.ui.keyboard.SideSymbolTarget.NUMBER) },
                        showArrow = true, searchKey = "数字键盘左栏符号")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 数字行（默认关）：点开关打开，点这一行进「按键符号 → 数字行」改键和顺序。
                    val numberRow = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().numberRow
                    SettingsToggleItem(Icons.TwoTone.FormatListNumbered, "数字行",
                        "26 键、14 键字母上方加一排数字，键盘总高度不变、字母键变矮。点这一行改每个键和顺序。",
                        checked = numberRow, onCheckedChange = { SettingsPreferences.setNumberRowEnabled(context, it) },
                        onClick = { com.kingzcheung.xime.ui.keyboard.openSideSymbolEditor(context,
                            com.kingzcheung.xime.ui.keyboard.SideSymbolTarget.NUMBER_ROW) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 打数字出短语（别人请求，默认关）：见 service/DigitPhrases.kt。
                    var digitPhrases by remember { mutableStateOf(com.kingzcheung.xime.service.DigitPhrases.isEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.Dialpad, "数字出短语",
                        "打的数字是「我的短语」编码的开头时，候选栏给出这些短语，点一下换掉这串数字。",
                        checked = digitPhrases, onCheckedChange = {
                            digitPhrases = it
                            com.kingzcheung.xime.service.DigitPhrases.setEnabled(context, it)
                        },
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DIGIT_PHRASES)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 联想常用数字（用户 11-04 要求，默认开）：见 service/NumberHistory.kt。关掉即清空记下的数字。
                    var numberHistory by remember { mutableStateOf(com.kingzcheung.xime.service.NumberHistory.isEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.Dialpad, "联想常用数字",
                        "打过两次的 4 位以上数字，再打开头两位时给出 (只存本机，密码框、无痕和 16 位以上不记，关掉即清空)",
                        checked = numberHistory, onCheckedChange = {
                            numberHistory = it
                            com.kingzcheung.xime.service.NumberHistory.setEnabled(context, it)
                        })
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
                        Text(
                            text = "候选字大小",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        CandidateTextSizeCard(
                            candidateTextSize = candidateTextSize,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = candidateTextSize,
                            onValueChange = { candidateTextSize = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setCandidateTextSize(context, candidateTextSize.toInt())
                            },
                            valueRange = 12f..22f,
                            steps = 9
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
                            Text("候选之间空出约一个字，中间用浅色竖线隔开",
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
                                text = "${pageSizeSlider.toInt()} 个",
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
                            steps = 29
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "下次开始输入时生效，无需重新部署",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                })
            }
            // 候选栏左右的按钮（11-02 整理：原来 11 行挤在一张「候选词」卡片里，按用途拆成四组，各行原样不变）。
            if (page == "candidates") item {
                SettingsSection(title = "候选栏按钮", content = {
                    var showCandidateCancel by remember {
                        mutableStateOf(SettingsPreferences.shouldShowCandidateCancelButton(context))
                    }
                    // 图例放在开关下面，随开关实时变化；整块都可点。
                    Column(
                        modifier = Modifier.settingsSearchAnchor("候选栏取消按钮")
                            .fillMaxWidth()
                            .testTag("candidate-cancel-setting")
                            .toggleable(
                                value = showCandidateCancel,
                                role = Role.Switch,
                                onValueChange = {
                                    showCandidateCancel = it
                                    SettingsPreferences.setShowCandidateCancelButton(context, it)
                                },
                            )
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("候选栏取消按钮", style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium)
                                Text("点候选栏最左边的左箭头，取消当前输入",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = showCandidateCancel, onCheckedChange = null)
                        }
                        Spacer(Modifier.height(10.dp))
                        CandidateCancelIllustration(showCandidateCancel)
                    }
                    // 联想关闭键用叉号（用户 11-03，默认关）：只在取消按钮开着时有用，关着就不显示。
                    if (showCandidateCancel) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        var associationCancelCross by remember {
                            mutableStateOf(SettingsPreferences.isAssociationCancelCross(context))
                        }
                        Row(
                            modifier = Modifier.settingsSearchAnchor("联想关闭键用叉号")
                                .fillMaxWidth()
                                .testTag("association-cancel-cross-setting")
                                .toggleable(
                                    value = associationCancelCross,
                                    role = Role.Switch,
                                    onValueChange = {
                                        associationCancelCross = it
                                        SettingsPreferences.setAssociationCancelCross(context, it)
                                    },
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("联想关闭键用叉号", style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium)
                                Text("上屏后出联想词时，最左边的左箭头换成 ⊗",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = associationCancelCross, onCheckedChange = null)
                        }
                    }
                    val pressPrefs = remember(context) { SettingsPreferences.getPrefsPublic(context) }
                    var pressHighlight by remember { mutableStateOf(
                        pressPrefs.getBoolean(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT, false)) }
                    SettingsToggleItem(Icons.TwoTone.FormatColorFill, "候选栏按钮按压高亮",
                        "按住取消键或展开箭头时，加深浅色底板或提亮深色底板。", pressHighlight, {
                            pressHighlight = it
                            pressPrefs.edit().putBoolean(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT, it).apply()
                        })
                })
            }
            if (page == "candidates") item {
                SettingsSection(title = "注释", content = {
                    var commentMode by remember { mutableStateOf(SettingsPreferences.candidateCommentMode(context)) }
                    Column(Modifier.settingsSearchAnchor("候选注释")) {
                        Text("候选注释", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                        Text("自动：宽时显示、窄时隐藏，手写里照样显示。中文手写默认显示读音。",
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
                            "使用五笔方案时显示该方案的编码，切回拼音时显示读音。", wubiHints, {
                                wubiHints = it
                                prefs.edit().putBoolean(SettingsPreferences.KEY_HANDWRITING_WUBI_HINTS, it).apply()
                            })
                        var commentContent by remember { mutableStateOf(SettingsPreferences.candidateCommentContent(context)) }
                        Column(Modifier.settingsSearchAnchor("候选注释内容")) {
                            Text("候选注释内容", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                            Text("释义：中文候选显示英文，混出的英文候选显示中文，九键长按「下一项」或按住 Shift 再选候选就上屏英文单词；选释义会自动下载释义词典，并把上面的「自动」改成「显示」，查不到的候选仍显示拼音。",
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
            if (page == "candidates") item {
                SettingsSection(title = "拼音显示", content = {
                    var showCorrectionMarks by remember { mutableStateOf(SettingsPreferences.showCorrectionMarks(context)) }
                    SettingsToggleItem(Icons.TwoTone.Spellcheck, "显示纠错标记",
                        "在纠错候选旁显示修正后的编码；关闭后仍可使用纠错候选。",
                        showCorrectionMarks, { showCorrectionMarks = it; SettingsPreferences.setShowCorrectionMarks(context, it) })
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
                            text = "选择输入编码的显示位置。终端输入框在候选栏显示。",
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

                    DoublePinyinRawPreeditToggle()
                    if (inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR) {
                        var showPreview by remember { mutableStateOf(SettingsPreferences.showPreeditPreview(context)) }
                        SettingsToggleItem(Icons.TwoTone.DataObject, "显示拼音编辑栏", "打字时在候选区显示编码，支持编辑的方案可点按修改。",
                            showPreview, { showPreview = it; SettingsPreferences.setShowPreeditPreview(context, it) })
                        if (showPreview) {
                            var insideKeyboard by remember { mutableStateOf(SettingsPreferences.inlinePreeditPreview(context)) }
                            SettingsToggleItem(Icons.TwoTone.DashboardCustomize, "拼音放在键盘内", "在候选栏左侧显示编码，给应用底部留出空间。",
                                insideKeyboard, { insideKeyboard = it; SettingsPreferences.setInlinePreeditPreview(context, it) })
                        }
                    }
                    // 拼音气泡底色可调透明（用户 11-01「我就喜欢透明度」）；只在键盘上方会浮出气泡时显示。
                    val bubblePrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                    // 选「显示在输入框」时没有气泡；页面上的选择先于键盘偏好刷新，所以也看本页的选择。
                    if (inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR &&
                        !bubblePrefs.preeditInsideKeyboard && bubblePrefs.showCodeInCandidateBar) {
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
                                text = "键盘上方拼音气泡的底色。100% 是实底，后面有画面也看得清；调低更透。",
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
                })
            }

            if (page == "key_tips") item { KeyTipsSettings() }
            if (page == "gestures") item {
                InputExperienceSettings(setOf(InputExperiencePart.SYMBOLS), "角标与长按")
            }
            if (page == "gestures") item { InputExperienceSettings(setOf(InputExperiencePart.DELETE), "删除键") }
            if (page == "gestures") item { InputExperienceSettings(setOf(InputExperiencePart.CURSOR), "光标与空格") }
            // 大多数人不会去调的时长、距离滑条收在一起，默认收起（用户 10-31）；设置搜索点中会自动展开。
            if (page == "gestures") item {
                InputExperienceSettings(setOf(InputExperiencePart.TIMING), "时长与距离",
                    collapsedSummary = "长按空格、光标移动距离、长按语言键、!@# 松手时限")
            }
            // 隐藏操作的说明（用户 10-31）：这一页最下面一行，点进去是「按键技巧」；它不是设置，不占上面的位置。
            if (page == "gestures") item {
                SettingsSection(title = "说明", searchKey = "按键技巧入口", content = {
                    SettingsItem(Icons.TwoTone.Lightbulb, "按键技巧",
                        "Shift、Ctrl(!@#)、空格、删除键的隐藏操作", showArrow = true,
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
    var edge by remember(context) { mutableStateOf(SettingsPreferences.edgeToEdgeKeyboard(context)) }
    var bottomPosition by remember(context) { mutableStateOf(SettingsPreferences.keyboardBottomPosition(context)) }
    var adjustment by remember(context) { mutableIntStateOf(SettingsPreferences.getKeyboardBottomAdjustmentDp(context)) }
    var loweringLimit by remember(context) { mutableStateOf(SettingsPreferences.getKeyboardBottomLoweringLimitDp(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsPreferences.KEY_EDGE_TO_EDGE_KEYBOARD || key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_POSITION ||
                key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_PADDING_DP || key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_LOWERING_DP ||
                key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_LOWERING_LIMIT_DP ||
                key == SettingsPreferences.KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP) {
                edge = SettingsPreferences.edgeToEdgeKeyboard(context)
                bottomPosition = SettingsPreferences.keyboardBottomPosition(context)
                adjustment = SettingsPreferences.getKeyboardBottomAdjustmentDp(context)
                loweringLimit = SettingsPreferences.getKeyboardBottomLoweringLimitDp(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 调低最多到安全下限（键盘量过才知道；没量过仍按 80 dp）：到了下限按钮变灰，数字也不再往下走。
    // 贴底时从贴底位置还能再调低 80 dp（键盘那边到 0 为止），识别不出手势导航的手机也能自己去掉底下那段。
    // 固定的两档（贴到最底、留出导航按钮）从那一档的位置往下调，也能一直调到 0。
    val fixedChoice = bottomPosition != SettingsPreferences.KeyboardBottomPosition.AUTO
    val lowest = if (edge || fixedChoice) -80 else -(loweringLimit ?: 80).coerceIn(0, 80)
    val value = adjustment.coerceAtLeast(lowest)
    val reference = if (fixedChoice) "「${bottomPosition.label}」的位置" else if (edge) "安全下限" else "系统建议"
    val position = when {
        value == 0 -> if (fixedChoice) "底部留白 0 dp" else if (edge) "已选安全下限" else "使用系统建议"
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
            Text("固定键盘下方空白太多就调低，底排被遮住就抬高。每次 2 dp，点按即保存。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { SettingsPreferences.setKeyboardBottomAdjustmentDp(context, (value - 2).coerceAtLeast(lowest)) },
                    enabled = value > lowest) { Text("调低") }
                OutlinedButton(onClick = { SettingsPreferences.setKeyboardBottomAdjustmentDp(context, (value + 2).coerceAtMost(80)) },
                    enabled = value < 80) { Text("抬高") }
            }
            TextButton(onClick = { SettingsPreferences.resetKeyboardBottomCalibration(context) },
                enabled = fixedChoice || !edge || adjustment != 0) { Text("恢复默认（自动、留白 0）") }
        }
    }
}

/** 「键盘布局」之外直接从首页进入的两页：route, title, one-line description. */
internal val LayoutDisplayPages = listOf(
    Triple("bottom_gap", "底部留白校准", "查看键盘底部，调低、抬高或恢复系统建议"),
    Triple("candidates", "候选词", "字号、候选注释(拼音或中英释义)、取消按钮、输入框或候选栏显示"),
    Triple("gestures", "按键与手势", "角标输入方式、删除键、长按空格、光标、长按语言键"),
    // 不在首页列出，从「按键与手势」最下面进入。
    Triple("key_tips", "按键技巧", "Shift、Ctrl(!@#)、空格、删除键的隐藏操作"),
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
                                text = "用双拼时，26 键每个键写出它代表的 zh、ch、sh 和韵母",
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
                        SettingsNote("角标怎么输入(滑动还是长按)在「按键与手势 › 按键角标的符号和数字」",
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
                                text = "空格不显示方案名，删除键不显示「清空」，重输键不显示「重输」",
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
                                text = "按下时在键上方显示按的键，长按换成角标，拂动换成方向盘",
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
                            com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.SQUARE -> "固定键盘顶部是直角，和应用界面平接。"
                            com.kingzcheung.xime.ui.keyboard.KeyboardTopStyle.SOFT -> "固定键盘上沿一小段由透明渐变到玻璃，和应用界面接得更自然。"
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
                                if (rounded) "底部保留圆角，导航栏透明；图标跟随系统明暗，系统可能添加对比度底色"
                                else "底部直角，导航栏跟随键盘背景；图标深浅跟随按键文字",
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

