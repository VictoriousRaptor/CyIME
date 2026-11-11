package com.kingzcheung.xime.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import com.kingzcheung.xime.ui.keyboard.normalizeHandwritingPause
import com.kingzcheung.xime.ui.keyboard.normalizeCursorHoldSeconds
import com.kingzcheung.xime.ui.keyboard.normalizeLanguageMenuHoldSeconds
import com.kingzcheung.xime.ui.keyboard.normalizeSymbolKeyHoldSeconds
import com.kingzcheung.xime.ui.keyboard.normalizeSymbolLongPressSeconds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import com.kingzcheung.xime.ui.keyboard.FormulaDelimiter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.kingzcheung.xime.ui.keyboard.normalizeCursorMagnifierWidth
import com.kingzcheung.xime.ui.keyboard.CursorGestureMode
import com.kingzcheung.xime.ui.keyboard.cursorDistanceMillimeters
import com.kingzcheung.xime.ui.keyboard.normalizeCursorSwipeLines
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.InputGestureLock
import com.kingzcheung.xime.ui.keyboard.InputGestureLocks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.settings.SettingsPreferences

/** Groups of [InputExperienceSettings]; the 布局与显示 sub-pages each show some of them. */
internal enum class InputExperiencePart { SYMBOLS, DELETE, FLICK_DISC, KANA, CURSOR, TIMING, HANDWRITING, KEY_TEXT }

/** 「时长与距离」里的几项（设置搜索点中其中一项时自动展开，见 [InputExperienceSettings] 的 collapsedSummary）。 */
internal val TIMING_SEARCH_KEYS = setOf("长按空格多久开始", "从中间滑到边缘走几行", "光标每移动一字所需距离",
    "长按语言键弹出列表", "长按语言键切换输入法", "符号键松手打开符号的时限", "时长与距离")

@Composable
internal fun InputExperienceSettings(
    parts: Set<InputExperiencePart> = InputExperiencePart.entries.toSet(),
    title: String = "共通输入",
    /** 默认收起，点标题行展开（用户 10-31：「按键与手势」的时长、距离滑条收起来）；设置搜索点中里面的项时自动展开。 */
    collapsedSummary: String? = null,
) {
    val context = LocalContext.current
    val saved = rememberKeyboardInputPreferences()
    var settings by remember(saved) { mutableStateOf(saved) }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(collapsedSummary == null) }
    val searchRequest = SettingsSearchFocus.request
    LaunchedEffect(searchRequest) {
        if (searchRequest != null && searchRequest.keys.any { it in TIMING_SEARCH_KEYS } &&
            InputExperiencePart.TIMING in parts) expanded = true
    }
    SettingsSection(title = title, content = {
        if (collapsedSummary != null) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)
                .testTag("settings-fold:$title"), verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "收起" else collapsedSummary, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) androidx.compose.material.icons.Icons.Default.ExpandLess
                    else androidx.compose.material.icons.Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起$title" else "展开$title")
            }
        }
        if (expanded) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var first = true
            @Composable fun divider() { if (!first) HorizontalDivider(); first = false }
            if (InputExperiencePart.SYMBOLS in parts) {
            divider()
            CornerHintVisibilitySettings()
            HorizontalDivider()
            // 原「角标输入方式」两张卡和「长按弹出字母气泡」开关合成三选一（11-01）：两者互锁，管的都是长按字母键做什么。
            Column(Modifier.settingsSearchAnchor("按键角标的符号和数字", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按键角标的符号和数字", style = MaterialTheme.typography.titleSmall)
                val corner = com.kingzcheung.xime.ui.keyboard.CornerInputChoice.of(settings)
                // 26 键滑行开着时角标的滑动输入用不了：两张「滑动」卡置灰，下面写明是哪个滑行锁的，点名字跳过去关掉。
                val glideLock = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.glideCornerLock(settings)
                // 图例跟上面两个角标提示开关走（用户 11-08）：关掉的角标不画、不列。
                val hints = com.kingzcheung.xime.ui.keyboard.rememberSwipeHintsEnabled()
                Row(Modifier.fillMaxWidth().choiceCardRowHeight(176.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kingzcheung.xime.ui.keyboard.CornerInputChoice.entries.forEach { choice ->
                        IllustratedChoiceCard(choice.label, corner == choice,
                            onClick = { settings = choice.applyTo(settings); settings.save(context) },
                            enabled = glideLock == null || choice == com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS,
                            modifier = Modifier.weight(1f).then(if (choice == com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE_BUBBLE)
                                Modifier.settingsSearchAnchor("长按弹出字母气泡") else Modifier),
                            tag = "corner-input:${choice.name}", titleMaxLines = 2, illustrationPadding = 2.dp) {
                            // 被滑行锁成长按时字母键下滑用来滑词，下角标打不出来：长按卡不再画下角标、不列下滑那一行（用户 11-11）。
                            val lowerReachable = glideLock == null || choice != com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS
                            CornerInputIllustration(choice, settings.reverseSymbolSwipe, hints.up, hints.down && lowerReachable)
                        }
                    }
                }
                // 说明跟选中的方式和角标提示开关走（用户 11-08）：方向已画在卡片里，这里只补卡片表达不了的。
                val hiddenNote = if (hints.up && hints.down) "" else "关掉提示的角标键上不显示，手势照样输入。"
                Text(when (corner) {
                    com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE -> "滑动时键内预览，松手输入。"
                    com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE_BUBBLE ->
                        "长按弹出这个字母的大写和带声调的写法；滑得慢时可能先弹出气泡。"
                    com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS ->
                        "按住 ${symbolLongPressLabel(settings.symbolLongPressSeconds)} 秒立刻输入上方角标，不用松手；气泡显示输入的角标，松手消失。" +
                            "九键长按出数字，选字母改用拂动。西班牙语的元音和 n 长按仍出 á é ñ，它们的上角标改用滑动。"
                } + hiddenNote, style = MaterialTheme.typography.bodySmall)
                glideLock?.let { SettingsLockNote("${it.message}：字母键的滑动都用来滑词，角标改为长按输入，只出上角标，下角标不再显示") }
                // 只在「长按输入」（含被滑行锁成长按）时有用，这时才显示（用户 11-08：关闭角标滑动后要能调长按时间）。
                if (corner == com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS) {
                    // 搜索「长按时间」等落到上面「按键角标的符号和数字」（SettingsSearch 的关键词），不单设锚点：没选长按时这里不显示。
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("长按多久输入角标：${symbolLongPressLabel(settings.symbolLongPressSeconds)} 秒")
                        Text("默认 0.3 秒。26、14 键，九键和笔画都用这个时长。", style = MaterialTheme.typography.bodySmall)
                        Slider(value = settings.symbolLongPressSeconds, modifier = Modifier.testTag("symbol-long-press-slider"),
                            onValueChange = { settings = settings.copy(symbolLongPressSeconds = normalizeSymbolLongPressSeconds(it)) },
                            onValueChangeFinished = { settings.save(context) }, valueRange = 0.1f..1f, steps = 17)
                    }
                }
            }
            Row(Modifier.settingsSearchAnchor("反转上下滑方向", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("反转上下滑方向", style = MaterialTheme.typography.titleSmall)
                    // 卡片图例已画出每个方向出什么，这里只写打开后的变化（11-01 统一用「上滑／下滑」，不再混用「上拉／下拉」）。
                    Text(if (settings.reverseSymbolSwipe) "上滑出上角标、下滑出下角标；删除键${if (settings.deleteSwipeReversed) "上滑清空、下滑撤销" else "下滑清空、上滑撤销"}。"
                        else "打开后上滑出上角标、下滑出下角标，删除键也反过来。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.reverseSymbolSwipe,
                    modifier = Modifier.testTag("reverse-symbol-swipe"), onCheckedChange = {
                        settings = settings.copy(reverseSymbolSwipe = it); settings.save(context)
                    })
            }
            }
            if (InputExperiencePart.DELETE in parts) {
            // 删除键的几种行为放一组（11-01，B站评论找「删除跳过候选」找不到）：原来分在「角标与长按」和「智能联想」两页。
            val clearSwipe = if (settings.deleteSwipeReversed) "上滑" else "下滑"
            val undoSwipe = if (settings.deleteSwipeReversed) "下滑" else "上滑"
            divider()
            Row(Modifier.settingsSearchAnchor("左右滑动删除", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("左右滑动删除", style = MaterialTheme.typography.titleSmall)
                    Text("按住删除键左滑删光标前的字、右滑删光标后的字，滑得越远删得越多，往回滑补回；松手后${undoSwipe}删除键撤回。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.swipeDistanceDelete, colors = settingsSwitchColors(),
                    modifier = Modifier.testTag("swipe-distance-delete"), onCheckedChange = {
                        settings = settings.copy(swipeDistanceDelete = it); settings.save(context)
                    })
            }
            divider()
            // 长按删除速度（11-03 别人提的需求）：连删间隔和振动间隔一起换档，默认标准即原来的手感。
            Column(Modifier.settingsSearchAnchor("长按删除速度", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("长按删除速度：${settings.deleteRepeatSpeed.label}")
                Slider(value = settings.deleteRepeatSpeed.ordinal.toFloat(), modifier = Modifier.testTag("delete-repeat-speed"),
                    onValueChange = { settings = settings.copy(deleteRepeatSpeed =
                        com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.fromIndex(kotlin.math.round(it).toInt())) },
                    onValueChangeFinished = { settings.save(context) },
                    valueRange = 0f..com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.entries.lastIndex.toFloat(),
                    steps = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.entries.size - 2)
                Text("按住删除键连删的快慢，振动跟着变密或变疏。默认标准。", style = MaterialTheme.typography.bodySmall)
            }
            divider()
            // 删除键单独反向（11-05 别人的评论）：在「反转上下滑方向」之上再对调删除键的清空和撤回，角标不动；默认关，原来的方向不变。
            Row(Modifier.settingsSearchAnchor("删除键单独反向", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("删除键单独反向", style = MaterialTheme.typography.titleSmall)
                    Text("现在${clearSwipe}清空、${undoSwipe}撤回；只对调删除键，角标方向不变。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.reverseDeleteSwipe, colors = settingsSwitchColors(),
                    modifier = Modifier.testTag("reverse-delete-swipe"), onCheckedChange = {
                        settings = settings.copy(reverseDeleteSwipe = it); settings.save(context)
                    })
            }
            divider()
            // 清空滑动距离（11-05 别人的评论：键盘矮时滑到屏幕边也不够 80dp）：只给 70%–120% 微调，默认 100% 不变。
            Column(Modifier.settingsSearchAnchor("清空滑动距离", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val percent = com.kingzcheung.xime.ui.keyboard.normalizeClearSwipePercent(settings.clearSwipePercent).toInt()
                Text("清空滑动距离：${if (percent == 100) "标准" else "$percent%"}")
                Slider(value = percent.toFloat(), modifier = Modifier.testTag("clear-swipe-percent"),
                    onValueChange = { settings = settings.copy(clearSwipePercent =
                        com.kingzcheung.xime.ui.keyboard.normalizeClearSwipePercent(it)) },
                    onValueChangeFinished = { settings.save(context) }, valueRange = 70f..120f, steps = 4)
                Text("删除键${clearSwipe}清空、${undoSwipe}撤回要滑多远。默认标准。", style = MaterialTheme.typography.bodySmall)
            }
            divider()
            Column(Modifier.settingsSearchAnchor("打字中清空整个输入框", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("打字中清空整个输入框", style = MaterialTheme.typography.titleSmall)
                        Text("还有没上屏的拼音时，${clearSwipe}删除键连输入框里的字一起清；关掉只清拼音。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.clearAllWhileComposing, colors = settingsSwitchColors(),
                        modifier = Modifier.testTag("clear-all-while-composing"), onCheckedChange = {
                            settings = settings.copy(clearAllWhileComposing = it); settings.save(context)
                        })
                }
                ClearWhileComposingLegend(settings.clearAllWhileComposing, settings.deleteSwipeReversed)
            }
            divider()
            Column(Modifier.settingsSearchAnchor("只清空光标前的内容", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("只清空光标前的内容", style = MaterialTheme.typography.titleSmall)
                        Text("清空时光标后面的字留着；撤销照样放回。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.clearBeforeCursorOnly, colors = settingsSwitchColors(),
                        modifier = Modifier.testTag("clear-before-cursor-only"), onCheckedChange = {
                            settings = settings.copy(clearBeforeCursorOnly = it); settings.save(context)
                        })
                }
                ClearBeforeCursorLegend(settings.clearBeforeCursorOnly, settings.deleteSwipeReversed)
            }
            // 下面两项存在 SettingsPreferences（原在「智能联想 › 联想行为」），值不变，只是换了地方。
            // 原「滑动清空时跳过联想」（11-08 用户删掉）并进「退格直接删字」：一个开关同时管退格和滑动清空，旧值由 SettingsPreferences 迁过来。
            var deleteSkipsAssociation by remember { mutableStateOf(SettingsPreferences.isDeleteSkipsAssociation(context)) }
            var deleteRecovery by remember { mutableStateOf(SettingsPreferences.isDeleteRecovery(context)) }
            val predictionOn = remember { SettingsPreferences.isSmartPredictionEnabled(context) }
            divider()
            Column(Modifier.settingsSearchAnchor("退格直接删字", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("退格直接删字", style = MaterialTheme.typography.titleSmall)
                        Text("有联想词时，退格直接删字，${clearSwipe}清空直接清正文；关掉时第一下只去掉联想词。",
                            style = MaterialTheme.typography.bodySmall)
                        if (!predictionOn) Text("「智能联想」关着，没有联想词，这项不起作用。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = deleteSkipsAssociation, colors = settingsSwitchColors(),
                        modifier = Modifier.testTag("delete-skips-association"), onCheckedChange = {
                            deleteSkipsAssociation = it; SettingsPreferences.setDeleteSkipsAssociation(context, it)
                        })
                }
                DeleteSkipsAssociationLegend(deleteSkipsAssociation)
            }
            divider()
            Column(Modifier.settingsSearchAnchor("删字后给同音字", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("删字后给同音字", style = MaterialTheme.typography.titleSmall)
                        Text("退格删字后，候选栏显示删掉的字和它的同音字(点一下上屏)。",
                            style = MaterialTheme.typography.bodySmall)
                        PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.DELETE_RECOVERY)
                    }
                    Switch(checked = deleteRecovery, colors = settingsSwitchColors(),
                        modifier = Modifier.testTag("delete-recovery"), onCheckedChange = {
                            deleteRecovery = it; SettingsPreferences.setDeleteRecovery(context, it)
                        })
                }
                DeleteRecoveryLegend(deleteRecovery)
            }
            }
            if (InputExperiencePart.FLICK_DISC in parts) {
            divider()
            Row(Modifier.settingsSearchAnchor("拂动时显示方向圆盘", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("拂动时显示方向圆盘", style = MaterialTheme.typography.titleSmall)
                    Text("日语假名、九键拂动选字母和 14 键／自建合并键的字母对按住时在键上方显示；关掉后和 26 键一样在键内提示。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.flickDirectionDisc,
                    modifier = Modifier.testTag("flick-direction-disc"), onCheckedChange = {
                        settings = settings.copy(flickDirectionDisc = it); settings.save(context)
                    })
            }
            }
            if (InputExperiencePart.KANA in parts) {
            divider()
            Row(Modifier.settingsSearchAnchor("假名方向提示", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("假名方向提示", style = MaterialTheme.typography.titleSmall)
                    Text("日语九键按键四周显示上下左右的灰色假名；按住时的方向圆盘由「拂动时显示方向圆盘」控制。",
                        style = MaterialTheme.typography.bodySmall)
                    // 日语没启用时没有假名九键，这一项不起作用：写明，免得以为开关坏了。
                    if (com.kingzcheung.xime.settings.InputLanguage.JAPANESE in com.kingzcheung.xime.settings.InputLanguage.supported &&
                        com.kingzcheung.xime.settings.InputLanguage.JAPANESE !in
                        com.kingzcheung.xime.settings.LanguagePreferences.enabled(context))
                        SettingsJumpText("日语未启用：在「语言与方案」打开日语后才有假名九键。", jumps = listOf("语言与方案"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.kanaDirectionHints,
                    modifier = Modifier.testTag("kana-direction-hints"), onCheckedChange = {
                        settings = settings.copy(kanaDirectionHints = it); settings.save(context)
                    })
            }
            }
            if (InputExperiencePart.CURSOR in parts) {
            divider()
            // 原「滑动移动光标」（长按空格／键盘滑动／关闭）和「长按空格」两处都能选“长按空格移动光标”（11-01 合并）：
            // 长按空格三选一 + 「键盘面板滑动光标」开关，存的值不变，见 CursorChoices。
            Column(Modifier.settingsSearchAnchor("长按空格", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("长按空格", style = MaterialTheme.typography.titleSmall)
                val spaceHold = com.kingzcheung.xime.ui.keyboard.CursorChoices.spaceHold(settings)
                Row(Modifier.fillMaxWidth().choiceCardRowHeight(132.dp).testTag("space-hold-options"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kingzcheung.xime.ui.keyboard.CursorChoices.spaceHoldChoices.forEach { action ->
                        IllustratedChoiceCard(action.label, spaceHold == action,
                            onClick = { settings = com.kingzcheung.xime.ui.keyboard.CursorChoices.withSpaceHold(settings, action); settings.save(context) },
                            modifier = Modifier.weight(1f), tag = "space-hold:${action.name}") {
                            SpaceHoldChoiceIllustration(action)
                        }
                    }
                }
                // 语音总开关关着时，这里选了语音也不会开麦（键盘上只提示去打开），写明，免得以为没生效。
                if (spaceHold == SpaceHoldAction.VOICE_TOGGLE && !SettingsPreferences.isSttEnabled(context)) {
                    SettingsLockNote("「语音转文本」关着，按住空格不会说话；在「语音转文本」页打开后生效")
                }
                // 上滑取消只在按住说话时起作用，选了语音开关才显示（别人提的需求，默认关，11-02）。
                if (spaceHold == SpaceHoldAction.VOICE_TOGGLE) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("上滑取消", style = MaterialTheme.typography.titleSmall)
                            Text("按住空格说话时往上滑，松手不上屏。", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = settings.voiceSwipeCancel, colors = settingsSwitchColors(), modifier = Modifier.testTag("voice-swipe-cancel"),
                            onCheckedChange = { settings = settings.copy(voiceSwipeCancel = it); settings.save(context) })
                    }
                }
            }
            HorizontalDivider()
            run {
                val on = com.kingzcheung.xime.ui.keyboard.CursorChoices.keyboardSwipe(settings)
                val lock = com.kingzcheung.xime.ui.keyboard.CursorChoices.keyboardSwipeLock(settings)
                Column(Modifier.settingsSearchAnchor("键盘面板滑动光标", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("键盘面板滑动光标", style = MaterialTheme.typography.titleSmall)
                            Text("在整块按键上左右滑动移光标，不用按住空格。", style = MaterialTheme.typography.bodySmall)
                            if (lock != null) SettingsLockNote(lock)
                        }
                        Switch(checked = on, enabled = lock == null, colors = settingsSwitchColors(),
                            modifier = Modifier.testTag("keyboard-swipe-cursor"), onCheckedChange = {
                                settings = com.kingzcheung.xime.ui.keyboard.CursorChoices.withKeyboardSwipe(settings, it); settings.save(context)
                            })
                    }
                    KeyboardSwipeCursorLegend(on)
                }
            }
            // 底部留白滑动和上面两项互不影响，单独一行开关（用户 10-31 要求新增，默认关）。
            Row(Modifier.settingsSearchAnchor("底部留白滑动移动光标", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("底部留白滑动移动光标", style = MaterialTheme.typography.titleSmall)
                    SettingsJumpText("在「键盘尺寸与位置」里往上拖出底部留白后，在键盘下面那一条左右滑动移光标，按住 Shift 滑是选字。悬浮键盘没有这一条。",
                        jumps = listOf("键盘尺寸与位置"), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.bottomLiftCursor, modifier = Modifier.testTag("bottom-lift-cursor-switch"),
                    onCheckedChange = { settings = settings.copy(bottomLiftCursor = it); settings.save(context) })
            }
            Row(Modifier.settingsSearchAnchor("光标放大镜", bleed = 16.dp, bleedVertical = 6.dp)
                .fillMaxWidth()
                .toggleable(value = settings.cursorMagnifierEnabled, role = Role.Switch, onValueChange = {
                    settings = settings.copy(cursorMagnifierEnabled = it); settings.save(context)
                }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("光标放大镜", style = MaterialTheme.typography.titleSmall)
                    Text("长按空格、滑动底部留白或键盘面板移光标时，放大当前行文字。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.cursorMagnifierEnabled, colors = settingsSwitchColors(), onCheckedChange = null)
            }
            Column(Modifier.settingsSearchAnchor("放大镜宽度", bleed = 16.dp, bleedVertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("放大镜宽度：${settings.cursorMagnifierWidthDp.toInt()} dp", style = MaterialTheme.typography.titleSmall)
                Slider(value = settings.cursorMagnifierWidthDp, modifier = Modifier.testTag("cursor-magnifier-width-slider"),
                    enabled = settings.cursorMagnifierEnabled,
                    onValueChange = { settings = settings.copy(cursorMagnifierWidthDp = normalizeCursorMagnifierWidth(it)) },
                    onValueChangeFinished = { settings.save(context) }, valueRange = 10f..480f, steps = 46)
                Text("以屏幕可用宽度为上限。", style = MaterialTheme.typography.bodySmall)
            }
            if (settings.cursorGesture != CursorGestureMode.NONE || settings.bottomLiftCursor) {
                // 只在总开关打开时显示：「按键效果」里关了振动或按键音，这里的开关不起作用。
                @Composable fun cursorStepSwitch(title: String, description: String, checked: Boolean, tag: String,
                    update: (Boolean) -> KeyboardInputPreferences) {
                    Row(Modifier.settingsSearchAnchor(title, bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = checked, modifier = Modifier.testTag(tag), onCheckedChange = {
                            settings = update(it); settings.save(context)
                        })
                    }
                }
                if (SettingsPreferences.getHapticMode(context) != "disabled") cursorStepSwitch("移动光标时振动",
                    "光标每移动一字轻震一下。", settings.cursorStepHaptic, "cursor-step-haptic") { settings.copy(cursorStepHaptic = it) }
                if (SettingsPreferences.isSoundEnabled(context)) cursorStepSwitch("移动光标时播放按键音",
                    "光标每移动一字响一下按键音。", settings.cursorStepSound, "cursor-step-sound") { settings.copy(cursorStepSound = it) }
            }
            }
            if (InputExperiencePart.TIMING in parts) {
            divider()
            // 触发时长跟着空格当前的长按功能放，标题写明它控制的是哪个功能。
            @Composable fun spaceHoldSlider() {
                val actionText = when (settings.effectiveSpaceHold) {
                    SpaceHoldAction.CURSOR -> "移动光标"; SpaceHoldAction.REPEAT -> "连续输入空格"; SpaceHoldAction.VOICE_TOGGLE -> "说话"
                }
                Column(Modifier.settingsSearchAnchor("长按空格多久开始", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("长按空格多久开始$actionText：${"%.1f".format(settings.cursorHoldSeconds)} 秒")
                    Slider(value = settings.cursorHoldSeconds, modifier = Modifier.testTag("cursor-hold-slider"),
                        onValueChange = { settings = settings.copy(cursorHoldSeconds = normalizeCursorHoldSeconds(it)) },
                        onValueChangeFinished = { settings.save(context) }, valueRange = 0.1f..1f, steps = 8)
                    Text("默认 0.2 秒。", style = MaterialTheme.typography.bodySmall)
                }
            }
            spaceHoldSlider()
            // 只显示当前生效的项：滑动移动光标关闭且底部留白滑动也关着时不显示光标距离。
            if (settings.cursorGesture != CursorGestureMode.NONE || settings.bottomLiftCursor) {
                Column(Modifier.settingsSearchAnchor("从中间滑到边缘走几行", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从中间滑到屏幕边缘走：${"%.2f".format(settings.cursorSwipeLines).trimEnd('0').trimEnd('.')} 行")
                    Text("按输入框一行能放几个字换算，换屏幕、横竖屏不用重调", style = MaterialTheme.typography.bodySmall)
                    Slider(value = settings.cursorSwipeLines, onValueChange = { settings = settings.copy(cursorSwipeLines = normalizeCursorSwipeLines(it)) },
                        onValueChangeFinished = { settings.save(context) }, valueRange = 0.5f..3f, steps = 9)
                }
                Column(Modifier.settingsSearchAnchor("光标每移动一字所需距离", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("光标每移动一字所需距离：${settings.cursorStepDp.toInt()} 档(约 ${"%.1f".format(cursorDistanceMillimeters(settings.cursorStepDp))} 毫米)")
                    Text("只在读不到一行几个字的输入框里用；越小越灵敏", style = MaterialTheme.typography.bodySmall)
                    Slider(value = settings.cursorStepDp, onValueChange = { settings = settings.copy(cursorStepDp = it) },
                        onValueChangeFinished = { settings.save(context) }, valueRange = 6f..24f, steps = 17)
                }
            }
            // 长按语言键切换输入法（GitHub #60 #68，默认关）：打开后长按弹系统的输入法选择框，语言靠点按语言键轮换。
            Row(Modifier.settingsSearchAnchor("长按语言键切换输入法", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("长按语言键切换输入法", style = MaterialTheme.typography.titleSmall)
                    Text("长按弹出系统的输入法选择框，不再弹语言列表", style = MaterialTheme.typography.bodySmall)
                    PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.LANGUAGE_KEY_IME_PICKER)
                }
                Switch(checked = settings.languageKeyImePicker, colors = settingsSwitchColors(),
                    modifier = Modifier.testTag("language-key-ime-picker"), onCheckedChange = {
                        settings = settings.copy(languageKeyImePicker = it); settings.save(context)
                    })
            }
            Column(Modifier.settingsSearchAnchor("长按语言键弹出列表", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 开着「长按语言键切换输入法」时长按弹的是系统输入法框，不是列表；时长照样管它，标题跟着说实话。
                Text("${if (settings.languageKeyImePicker) "长按语言键弹出输入法选择框" else "长按语言键弹出列表"}：${"%.2f".format(settings.languageMenuHoldSeconds)} 秒")
                Text("默认 0.15 秒。", style = MaterialTheme.typography.bodySmall)
                Slider(value = settings.languageMenuHoldSeconds, modifier = Modifier.testTag("language-menu-hold-slider"),
                    onValueChange = { settings = settings.copy(languageMenuHoldSeconds = normalizeLanguageMenuHoldSeconds(it)) },
                    onValueChangeFinished = { settings.save(context) }, valueRange = 0.05f..1f, steps = 18)
            }
            Column(Modifier.settingsSearchAnchor("符号键松手打开符号的时限", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("符号键松手打开符号的时限：${"%.1f".format(settings.symbolKeyHoldSeconds)} 秒")
                Text("默认 1 秒。26 键、九键按住 !@# 是 Ctrl，拖到字母或数字键上松手发快捷键(如 C 复制、九键 8 复制)；超过时限或拖出去再松手不打开符号。按满时限字母键才显示快捷键。", style = MaterialTheme.typography.bodySmall)
                Slider(value = settings.symbolKeyHoldSeconds, modifier = Modifier.testTag("symbol-key-hold-slider"),
                    onValueChange = { settings = settings.copy(symbolKeyHoldSeconds = normalizeSymbolKeyHoldSeconds(it)) },
                    onValueChangeFinished = { settings.save(context) }, valueRange = 0.3f..2f, steps = 16)
            }
            }
            if (InputExperiencePart.HANDWRITING in parts) {
            divider()
            Column(Modifier.settingsSearchAnchor("手写停顿识别", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("手写停顿识别：${"%.1f".format(settings.handwritingPauseSeconds)} 秒")
                Text("默认 0.5 秒；继续书写会重新计时。", style = MaterialTheme.typography.bodySmall)
                Slider(value = settings.handwritingPauseSeconds, modifier = Modifier.testTag("handwriting-pause-slider"),
                    onValueChange = { settings = settings.copy(handwritingPauseSeconds = normalizeHandwritingPause(it)) },
                    onValueChangeFinished = { settings.save(context) }, valueRange = 0.1f..2.5f, steps = 23)
            }
            Row(Modifier.settingsSearchAnchor("手写边写边识别", bleed = 16.dp, bleedVertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("手写边写边识别", style = MaterialTheme.typography.titleSmall)
                    Text("每写完一笔就先认，候选栏马上显示，可以直接点选，不用等停顿。中文、日语手写可用；会多用一点电。",
                        style = MaterialTheme.typography.bodySmall)
                    PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.HANDWRITING_LIVE)
                }
                Switch(checked = settings.handwritingRecognizeWhileWriting, colors = settingsSwitchColors(),
                    modifier = Modifier.testTag("handwriting-recognize-while-writing"), onCheckedChange = {
                        settings = settings.copy(handwritingRecognizeWhileWriting = it); settings.save(context)
                    })
            }
            Column(Modifier.settingsSearchAnchor("手写公式两边的标记", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("手写公式两边的标记", style = MaterialTheme.typography.titleSmall)
                Text("公式模式选中候选后上屏的样子，例如 x^{2} 上屏为 \$x^{2}\$ 或 \\[x^{2}\\]。", style = MaterialTheme.typography.bodySmall)
                // 没装公式手写模型时手写板没有公式模式，这一项暂时不起作用：照常显示并写明。
                val formulaReady = remember { com.kingzcheung.xime.handwriting.FormulaRecognizer.isAvailable(context) }
                if (!formulaReady) SettingsJumpText("公式手写模型还没下载，在「手写 › 已安装的手写模型」下载后英文手写里才有公式模式，这里的标记才用得上。",
                    jumps = listOf("手写 › 已安装的手写模型"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormulaDelimiter.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { settings = settings.copy(formulaDelimiter = option); settings.save(context) }
                        .padding(vertical = 4.dp).testTag("formula-delimiter:${option.name}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.formulaDelimiter == option,
                            onClick = { settings = settings.copy(formulaDelimiter = option); settings.save(context) })
                        Text(option.label)
                    }
                }
            }
            }
            if (InputExperiencePart.KEY_TEXT in parts) {
            divider()
            Column(Modifier.settingsSearchAnchor("按键字号", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按键字号：${(settings.keyTextScale * 100).toInt()}%")
                Text("a b c  A B C  拼音", fontSize = (16f * settings.keyTextScale).sp)
                Slider(value = settings.keyTextScale, onValueChange = { settings = settings.copy(keyTextScale = it) },
                    onValueChangeFinished = { settings.save(context) }, valueRange = 0.8f..1.6f, steps = 15)
            }
            divider()
            // 原「字母键显示大写」「中文键盘字母显示大写」两个互锁开关合成三选一（11-01），存的值不变，见 KeyLabelCaseChoice。
            Column(Modifier.settingsSearchAnchor("键帽字母大小写", bleed = 16.dp, bleedVertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("键帽字母大小写", style = MaterialTheme.typography.titleSmall)
                val case = com.kingzcheung.xime.ui.keyboard.KeyLabelCaseChoice.of(settings)
                Row(Modifier.fillMaxWidth().choiceCardRowHeight(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kingzcheung.xime.ui.keyboard.KeyLabelCaseChoice.entries.forEach { choice ->
                        IllustratedChoiceCard(choice.label, case == choice,
                            onClick = { settings = choice.applyTo(settings); settings.save(context) },
                            modifier = Modifier.weight(1f), tag = "key-label-case:${choice.name}") {
                            KeyLabelCaseIllustration(choice)
                        }
                    }
                }
                if (case != com.kingzcheung.xime.ui.keyboard.KeyLabelCaseChoice.LOWER)
                    Text("只改键帽；打出来的大小写仍看 Shift。", style = MaterialTheme.typography.bodySmall)
            }
            }
        }
    })
}

/** 0.3、0.25、1：去掉多余的 0。 */
internal fun symbolLongPressLabel(seconds: Float): String =
    String.format(java.util.Locale.ROOT, "%.2f", normalizeSymbolLongPressSeconds(seconds)).trimEnd('0').trimEnd('.')
