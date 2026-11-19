package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.twotone.SwapHoriz
import androidx.compose.material.icons.twotone.OpenWith
import androidx.compose.material.icons.twotone.SpaceBar
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.Abc
import androidx.compose.material.icons.twotone.Spellcheck
import androidx.compose.material.icons.twotone.Hearing
import androidx.compose.material.icons.automirrored.twotone.KeyboardReturn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import com.kingzcheung.xime.settings.TypoEffects
import com.kingzcheung.xime.settings.TypoStrength
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kingzcheung.xime.ui.keyboard.normalizeLetterSwipeDistance
import com.kingzcheung.xime.ui.keyboard.normalizeCandidateDragPercent
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences

internal enum class LayoutInputCategory(val route: String, val title: String, val description: String) {
    Qwerty("layout_input_qwerty", "26 键", "中英混输、字母错位、底栏顺序、分词键"),
    Nine("layout_input_nine", "九键与 14 键", "拂动选字母、14 键双字母键、触发距离、方向圆盘与假名提示"),
    /** 英文、中文 26 键和九键滑行原来分在 26 键、九键两页三个开关（用户 11-09「三个入口太乱」），合到这一页；存的值不变。 */
    Glide("layout_input_glide", "滑行输入", "英文、中文、九键滑行，实时显示词，逐字删除"),
}

/** 日语 26 键的长音 ー 放在哪：三张带图例的卡片，默认 L 键（见 JapaneseLongVowelKey）。 */
@Composable
internal fun JapaneseLongVowelKeySettings(
    settings: com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences,
    onChange: (com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences) -> Unit,
) {
    Column(Modifier.settingsSearchAnchor("长音键").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("长音键", style = MaterialTheme.typography.titleSmall)
        Text("日语 26 键打 ー 的键。选单独键或 Shift 时 L 是字母 l，代替 Shift 时按住仍是 Shift。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().choiceCardRowHeight(108.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey.entries.forEach { mode ->
                IllustratedChoiceCard(mode.label, settings.japaneseLongVowelKey == mode,
                    onClick = { onChange(settings.copy(japaneseLongVowelKey = mode)) },
                    modifier = Modifier.weight(1f), tag = "long-vowel-key:${mode.name}") {
                    JapaneseLongVowelIllustration(mode)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutInputSettingsContent(category: LayoutInputCategory, onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val saved = rememberKeyboardInputPreferences()
    var settings by remember(saved) { mutableStateOf(saved) }
    KeyboardSettingsScaffold(category.title, onBack, preview = true, example = when (category) {
        LayoutInputCategory.Qwerty -> com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Letters
        LayoutInputCategory.Nine -> com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Keypad
        LayoutInputCategory.Glide -> com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Letters
    }) { modifier ->
        Column(modifier.settingsVerticalScroll().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (category) {
                LayoutInputCategory.Qwerty -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 邻键纠错、模糊音 11-01 搬到「语言与方案」（跟着方案走，九键和 26 键页不再各放一份）。
                SettingsSection(title = "大小写", content = {
                    SettingsToggleItem(Icons.TwoTone.Abc, "首字母大写",
                        "英文(含英文单词方案)和西、德、法、意、俄、越南、马来语键盘上，新句子和新段落的第一个字母自动大写。",
                        checked = settings.englishSentenceCaps, onCheckedChange = {
                            settings = settings.copy(englishSentenceCaps = it); settings.save(context)
                        })
                })
                SettingsSection(title = "混输", content = {
                    SettingsToggleItem(Icons.TwoTone.Abc, "中英混输",
                        "仅用于中文全拼 26 键；拼音里夹着英文词(nihaohello)时首选“你好hello”。本身像拼音的英文词(game、nice)认不出。",
                        checked = settings.mixedInput, onCheckedChange = {
                            settings = settings.copy(mixedInput = it); settings.save(context)
                        }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.MIXED_INPUT)
                })
                SettingsSection(title = "按键排列与分词", content = {
                    QwertyStaggerSettings(settings) { settings = it; it.save(context) }
                    HorizontalDivider()
                    SettingsItem(Icons.TwoTone.SwapHoriz, "26 键底栏", "调整底栏顺序，添加或移除逗号、句号",
                        onClick = { onNavigate(SettingsRoutes.QwertyBottomRow) }, showArrow = true)
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Gesture, "底排标点固定上滑",
                        "底栏的逗号、句号键和数字键盘的点键：上滑或长按输入右上角的符号。关闭后跟随全局角标设置。",
                        checked = settings.bottomPunctuationSwipeUp, onCheckedChange = {
                            settings = settings.copy(bottomPunctuationSwipeUp = it); settings.save(context)
                        })
                    HorizontalDivider()
                    // 逗号键分词（默认关）：下角标的方向跟「反转上下滑方向」走，说明照实际方向写。
                    SettingsToggleItem(Icons.TwoTone.ContentCut, "逗号键分词",
                        "中文全拼、双拼的 26 键和 14 键：逗号键左下角加 '(只开句号键时在句号键上)，" +
                            "打拼音时${if (settings.bottomPunctuationSwipeUp || settings.reverseSymbolSwipe) "下滑" else "上滑"}插入分词(xi'an)，F 键的 ' 仍跟随字母键手势；" +
                            "没在打拼音时输入 '。英文和西、德、法、意、马来语也有这个 '，打单词时接进词里(what's、l'eau)。九键照旧用 1 键分词。",
                        checked = settings.syllableSplitKey, onCheckedChange = {
                            settings = settings.copy(syllableSplitKey = it); settings.save(context)
                        })
                    SyllableSplitKeyLegend(settings.syllableSplitKey,
                        com.kingzcheung.xime.ui.keyboard.QwertyBottomKey.COMMA in settings.qwertyBottomRow.keys,
                        com.kingzcheung.xime.ui.keyboard.QwertyBottomKey.PERIOD in settings.qwertyBottomRow.keys,
                        settings.bottomPunctuationSwipeUp || settings.reverseSymbolSwipe)
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.ContentCut, "Shift 键分词",
                        "中文全拼、双拼的 26 键和 14 键：打拼音时 Shift 键写「分词」，点一下插入分词(xi'an)；" +
                            "没在打拼音时照旧切换大小写，按住 Shift 打大写也不变。" +
                            "英文和西、德、法、意、马来语打单词时 Shift 键写 '，点一下打撇号(what's、l'eau)。",
                        checked = settings.shiftSyllableSplit, onCheckedChange = {
                            settings = settings.copy(shiftSyllableSplit = it); settings.save(context)
                        },
                        // 和微软双拼的「ing 放在 Shift 键上」互锁：打字时都要占 Shift 键。
                        lockMessage = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings, com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.SHIFT_SYLLABLE_SPLIT, true)
                            ?.takeIf { !settings.shiftSyllableSplit }?.message)
                    ShiftSyllableSplitLegend(settings.shiftSyllableSplit)
                    HorizontalDivider()
                    JapaneseLongVowelKeySettings(settings) { settings = it; it.save(context) }
                })
                }
                LayoutInputCategory.Nine -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsSection(title = "九键按键", content = {
                        // 用户 11-15：很多人要去掉 0 键换大回车。默认关，原来的排法不变；说明照开关和角标设置写实际能做什么。
                        SettingsToggleItem(Icons.AutoMirrored.TwoTone.KeyboardReturn, "去掉 0 键，回车加高",
                            t9MergeZeroSubtitle(settings.cornerSymbolInput, settings.reverseSymbolSwipe),
                            checked = settings.t9MergeZeroIntoEnter, onCheckedChange = {
                                settings = settings.copy(t9MergeZeroIntoEnter = it); settings.save(context)
                            })
                    })
                    // 用户 11-17 转来：笔画键盘的 0 键用的也是九键那个键，给它一个同款开关。默认关，原来的排法不变。
                    SettingsSection(title = "笔画按键", content = {
                        SettingsToggleItem(Icons.AutoMirrored.TwoTone.KeyboardReturn, "笔画去掉 0 键，回车加高",
                            strokeMergeZeroSubtitle(settings.cornerSymbolInput, settings.reverseSymbolSwipe),
                            checked = settings.strokeMergeZeroIntoEnter, onCheckedChange = {
                                settings = settings.copy(strokeMergeZeroIntoEnter = it); settings.save(context)
                            })
                        HorizontalDivider()
                        StrokeGlyphPreeditToggle()
                    })
                    SettingsSection(title = "九键手势", content = {
                        val glideLock = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                            com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.T9_FLICK_LETTERS, true)
                        SettingsToggleItem(Icons.TwoTone.OpenWith, "拂动选字母",
                            "左、上、右、下依次输入键上的第 1～4 个字母(7、9 用到下)，点按仍是九键输入；" +
                                "开启后长按输入数字、不再弹出字母菜单。",
                            checked = settings.t9FlickLetters && glideLock == null, onCheckedChange = {
                                settings = settings.copy(t9FlickLetters = it); settings.save(context)
                            },
                            // 角标“长按”时数字占了长按，拂动必须开着：锁成开，不能关。
                            lockMessage = glideLock?.message ?: com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                                com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.T9_FLICK_LETTERS, false)
                                ?.takeIf { settings.t9FlickLetters }?.message)
                        HorizontalDivider()
                        Column(Modifier.settingsSearchAnchor("拖到候选选词的距离").padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("拖到候选选词的距离：键盘的 ${settings.candidateDragPercent.toInt()}%", style = MaterialTheme.typography.titleSmall)
                            Slider(value = settings.candidateDragPercent,
                                modifier = Modifier.testTag("t9-candidate-drag-percent"),
                                // 去掉了 0 键就没有「下一项」可拖，这项不起作用：置灰并写明。
                                enabled = !settings.t9MergeZeroIntoEnter,
                                valueRange = 20f..80f, steps = 5,
                                onValueChange = { settings = settings.copy(candidateDragPercent = normalizeCandidateDragPercent(it)) },
                                onValueChangeFinished = { settings.save(context) })
                            Text(if (settings.t9MergeZeroIntoEnter) "已打开上面的「去掉 0 键，回车加高」，没有「下一项」键可拖，这项不起作用。"
                                else "默认 50%。按住「下一项」拖过这么远才算拖到候选选词；没拖这么远仍是「下一项」自己的左滑、右滑。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                    SettingsSection(title = "双字母键", content = {
                        SettingsToggleItem(Icons.TwoTone.SwapHoriz, "左右滑动选字母",
                            "例如 qw：左滑输入 q，右滑输入 w，松手确认；点按仍使用合并键。" +
                                "适用于拼音和小鹤双拼 14 键，以及自己合并成一格的两个字母(如自建 18 键的 as)。",
                            checked = settings.fourteenLetterSwipe, onCheckedChange = {
                                settings = settings.copy(fourteenLetterSwipe = it); settings.save(context)
                            })
                        // 原来各自一组、组里只有一行（11-02 整理合并）；距离九键拂动、假名拂动也共用，说明里写着。
                        HorizontalDivider()
                        Column(Modifier.settingsSearchAnchor("滑动触发距离").padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("滑动触发距离：${settings.fourteenLetterSwipeDp.toInt()} dp", style = MaterialTheme.typography.titleSmall)
                            Slider(value = settings.fourteenLetterSwipeDp,
                                modifier = Modifier.testTag("fourteen-letter-distance"),
                                valueRange = 12f..72f, steps = 29,
                                onValueChange = { settings = settings.copy(fourteenLetterSwipeDp = normalizeLetterSwipeDistance(it)) },
                                onValueChangeFinished = { settings.save(context) })
                            Text("默认 24 dp，越大越不易误触。九键拂动选字母、14 键和自建合并键左右滑选字母、日语九键假名拂动共用。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                    InputExperienceSettings(setOf(InputExperiencePart.FLICK_DISC, InputExperiencePart.KANA), "拂动与假名提示")
                }
                LayoutInputCategory.Glide -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 三个布局各一行，存的还是原来三个开关（glide_typing_v2、glide_typing_chinese、t9_glide_typing），默认值不变。
                // 「键盘面板滑动光标」和滑行冲突：照常显示，被锁时置灰并写明被谁锁（见 InputGestureLocks）。
                val glideLock = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                    com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.GLIDE_TYPING, true)
                SettingsSection(title = "布局", content = {
                    SettingsToggleItem(Icons.TwoTone.Gesture, "英文滑行",
                        "英文 26 键手指划过字母出整词，候选栏可换成其他词；字母键向任何方向滑动都用于滑词，到相邻字母即可松手。",
                        checked = settings.glideTyping, onCheckedChange = {
                            settings = settings.copy(glideTyping = it); settings.save(context)
                        },
                        lockMessage = glideLock?.takeIf { !settings.glideTyping }?.message,
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.GLIDE_TYPING)
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Gesture, "中文滑行",
                        "中文全拼、双拼 26 键划一个词的拼音，接着划或点按都接在后面，照常选字；字母键向任何方向滑动都用于滑词，到相邻字母即可松手。",
                        checked = settings.chineseGlideTyping, onCheckedChange = {
                            settings = settings.copy(chineseGlideTyping = it); settings.save(context)
                        },
                        lockMessage = glideLock?.takeIf { !settings.chineseGlideTyping }?.message,
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.CHINESE_GLIDE)
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Gesture, "九键滑行",
                        "一笔划过一个词的几个键，松手后按词库认出这笔划的是哪几个键，再照常选词：划 5→2→4 可以选“来”，从 9 直接划到 4 是 94，路过的键不算。开启后 2～9 键滑动只用于滑词。",
                        checked = settings.t9GlideTyping, onCheckedChange = {
                            settings = settings.copy(t9GlideTyping = it); settings.save(context)
                        },
                        lockMessage = glideLock?.takeIf { !settings.t9GlideTyping }?.message,
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.T9_GLIDE)
                })
                SettingsSection(title = "选项", content = {
                    // 别的用户 11-02 提的（用惯 Gboard）：默认关；英文、中文滑行都没开时没有作用，关着就置灰。
                    SettingsToggleItem(Icons.TwoTone.Gesture, "滑行时实时显示词",
                        "划的过程中按编码显示位置展示识别结果，中文显示拼音；抬手后按最终结果输入。只用于 26 键。",
                        checked = settings.glidePreview, onCheckedChange = {
                            settings = settings.copy(glidePreview = it); settings.save(context)
                        },
                        lockMessage = "需先打开「英文滑行」或「中文滑行」"
                            .takeIf { !settings.glidePreview && !settings.glideTyping && !settings.chineseGlideTyping },
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.GLIDE_PREVIEW)
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Gesture, "逐字删除中文滑行",
                        "开启后，删除键一次删除中文滑行输入中最后一个字对应的编码。",
                        checked = settings.deleteChineseGlideCharacter, onCheckedChange = {
                            settings = settings.copy(deleteChineseGlideCharacter = it); settings.save(context)
                        })
                })
                SettingsSection(title = "贡献", content = { GlidePracticeItem { onNavigate(SettingsRoutes.GlideContribute) } })
                }
            }
            SettingsJumpText((if (category == LayoutInputCategory.Glide) "" else "滑行在「键盘布局 › 滑行输入」；") +
                "分体键盘、按键符号在上一页「键盘布局」；角标、光标与空格在「按键与手势」；字号在「主题与外观 › 按键字号」；" +
                "拼音纠错、模糊音在「语言与方案 › 中文输入」。",
                jumps = listOf("键盘布局 › 滑行输入", "键盘布局", "按键与手势", "主题与外观 › 按键字号", "语言与方案 › 中文输入"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/**
 * 拼音纠错（11-03 统一）：每种纠错一行，全拼、双拼共用，不再各管各的（以前「邻键纠错」只管全拼、双拼另有一行，
 * 顺序、多键、漏键藏在「邻键纠错」里看不见）。存的值不变：「邻键纠错」「双拼邻键纠错」沿用原来的键；顺序、多键、漏键
 * 是新键，默认开，同以前的效果。哪种在哪个键盘起作用见 [TypoCorrectionSupport]；用不上的灰掉并写原因。
 */
@Composable
internal fun NeighborCorrectionSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = rememberKeyboardInputPreferences()
    val support = remember { runCatching { TypoCorrectionSupport.of(context) }.getOrDefault(TypoCorrectionSupport.ALL) }
    val defaults = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences()
    // 「双拼邻键纠错」只给用双拼的人，或改过它的人（方便关回去）。
    val showDouble = support.doublePinyin || settings.doublePinyinCorrection != defaults.doublePinyinCorrection
    var typos by remember { mutableStateOf(SpellingPatches.Typo.entries.associateWith { SpellingPatches.typo(context, it) }) }
    val needsNeighbor = if (settings.neighborCorrection) null else "要先打开「邻键纠错」"
    // 双拼 26 键停一下后也补顺序、多键（只有同一个键多按一下）、漏键三种（11-16）。
    val fullOrDouble = if (support.fullPinyin || support.doublePinyin) null else "只用于 26 键全拼、双拼"
    // 九键全拼停一下后也补邻键、顺序、漏键三种（多键没有）。
    val anyKeyboard = if (support.fullPinyin || support.doublePinyin || support.nineKey) null else "只用于 26 键全拼、双拼、九键"
    // 强度（11-16）：邻键纠错和九键各一条滑条，每一档下面写实测效果；最后几行是现在这种开关和强度组合测出来的效果。
    val on = typos.filterValues { it }.keys
    var lead by remember { mutableStateOf(TypoStrength.lead(context)) }
    var digit by remember { mutableStateOf(TypoStrength.digit(context)) }
    val effects by produceState<TypoEffects.Table?>(null) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { TypoEffects.table(context) }.getOrNull() }
    }
    SettingsSection(title = "拼音纠错", content = {
        Text("26 键全拼四种都有；双拼 26 键有邻键纠错，停一下后还补顺序、多键(只有同一个键多按一下)、漏键；九键有邻键、顺序、漏键三种(停一下才出)；14 键不纠错。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SettingsToggleItem(Icons.TwoTone.Spellcheck, "邻键纠错",
            "按偏到旁边的键时，也给出本来想打的词，原来的候选还在",
            checked = settings.neighborCorrection, onCheckedChange = {
                settings.copy(neighborCorrection = it).save(context)
            }, lockMessage = anyKeyboard,
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.NEIGHBOR_CORRECTION)
        if (settings.neighborCorrection && support.fullPinyin) {
            TypoLevelSlider("邻键纠错强度", TypoStrength.Lead.entries, lead, { it.title },
                "纠正出来的词什么时候排到你打的词前面。越保守越少抢第一，纠正的词仍在后面几个候选里。只用于 26 键全拼；改完要几秒重新部署",
                effect = { level -> effects?.let { TypoEffects.leadText(it.fullPinyin, level, on) } }, tag = "typo-lead-strength") { chosen ->
                TypoStrength.setLead(context, chosen)
                lead = chosen
                scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
            }
        }
        if (showDouble) {
            HorizontalDivider()
            SettingsToggleItem(Icons.TwoTone.Spellcheck, "双拼邻键纠错",
                "双拼 26 键按偏到旁边的键时，也给出本来想打的词(关着时只纠拼不成字的两个键)；改完要几秒重新部署",
                checked = settings.doublePinyinCorrection, onCheckedChange = {
                    settings.copy(doublePinyinCorrection = it).save(context)
                    scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                }, lockMessage = needsNeighbor,
                costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DOUBLE_PINYIN_CORRECTION)
        }
        SpellingPatches.Typo.entries.forEach { typo ->
            HorizontalDivider()
            SettingsToggleItem(Icons.TwoTone.Spellcheck, typo.title, typoCaption(typo),
                checked = typos.getValue(typo), onCheckedChange = { on ->
                    SpellingPatches.setTypo(context, typo, on)
                    typos = typos + (typo to on)
                    scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                }, lockMessage = (if (typo == SpellingPatches.Typo.EXTRA) fullOrDouble else anyKeyboard)
                    ?: if (typo == SpellingPatches.Typo.OMISSION) null else needsNeighbor,
                costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.typo(typo),
                extraCost = {
                    val text = if (support.fullPinyin) effects?.let { TypoEffects.typoText(it.fullPinyin, typo, settings.neighborCorrection, lead, on) } else null
                    if (text != null) TypoEffectNote(text, "typo-effect:${typo.key}")
                })
        }
        if (support.nineKey) {
            HorizontalDivider()
            TypoLevelSlider("九键纠错强度", TypoStrength.Digit.entries, digit, { it.title },
                "九键停一下后补出来的纠正给得多还是少(按到上下左右的键、少按一个、两个按反，各跟上面的邻键、漏键、顺序开关)；改完要几秒重新部署",
                effect = { level -> effects?.let { TypoEffects.digitText(it.nineKey, level) } }, tag = "typo-t9-strength") { chosen ->
                TypoStrength.setDigit(context, chosen)
                digit = chosen
                scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
            }
        }
        val summary = if (!support.fullPinyin) emptyList<String>() else effects?.let {
            TypoEffects.summary(it.fullPinyin, settings.neighborCorrection, lead, on)
        }.orEmpty()
        if (summary.isNotEmpty()) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("typo-effect-summary"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("按现在的设置(26 键全拼，实测)", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // 速度只跟开关有关，强度不改每键的工作量（各档实测每键耗时一样）。
                val costs = buildSet<com.kingzcheung.xime.settings.PerformanceCosts.Item> {
                    if (settings.neighborCorrection) add(com.kingzcheung.xime.settings.PerformanceCosts.Item.NEIGHBOR_CORRECTION)
                    on.forEach { typo ->
                        if (typo == SpellingPatches.Typo.OMISSION || settings.neighborCorrection)
                            add(com.kingzcheung.xime.settings.PerformanceCosts.Item.typo(typo))
                    }
                }
                PerformanceCostTotalNote(costs, label = "性能开销(开着的 ${costs.size} 种合计)", tag = "typo-correction")
            }
        }
    })
}

/** 开关说明下面的实测效果一行，样式同 [PerformanceCostNote]。 */
@Composable
private fun TypoEffectNote(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp).testTag(tag))
}

/**
 * 纠错强度的滑条：标题 + 当前档名，一句说明，滑条，两端档名，最下面是滑到的那一档的实测效果（拖动中就换）。
 * 写法同「性能与省电」里空闲释放的滑条（松手时按滑块位置取值）。
 */
@Composable
private fun <T> TypoLevelSlider(
    title: String, levels: List<T>, current: T, label: (T) -> String, description: String,
    effect: (T) -> String?, tag: String, onChoose: (T) -> Unit,
) {
    var position by remember(current) { mutableStateOf(levels.indexOf(current).coerceAtLeast(0).toFloat()) }
    val shown = levels[position.roundToInt().coerceIn(levels.indices)]
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(label(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = position, onValueChange = { position = it }, modifier = Modifier.testTag(tag),
            onValueChangeFinished = {
                val chosen = levels[position.roundToInt().coerceIn(levels.indices)]
                if (chosen != current) onChoose(chosen)
            },
            valueRange = 0f..(levels.size - 1).toFloat(), steps = levels.size - 2)
        Row(Modifier.fillMaxWidth()) {
            Text(label(levels.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(label(levels.last()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        effect(shown)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$tag-effect"))
        }
    }
}

private fun typoCaption(typo: SpellingPatches.Typo): String = when (typo) {
    SpellingPatches.Typo.TRANSPOSITION -> "相邻两个字母打反也出想打的词(如 shenem 出“什么”)，双拼是两个键按反、九键是两个数字按反，这两种停一下才出；改完要几秒重新部署"
    SpellingPatches.Typo.EXTRA -> "多按一下或多碰到旁边的键也出想打的词(如 woomen 出“我们”)，双拼只补同一个键多按一下、停一下才出；改完要几秒重新部署"
    SpellingPatches.Typo.OMISSION -> "漏打一个字母也出想打的词(如 chengong 出“成功”)，有的停一下才出，双拼是少按一个键、停一下才出；改完要几秒重新部署"
}

/** 已启用的中文方案里有没有 26 键全拼、26 键双拼、九键全拼：纠错只在这三种上起作用（九键只有邻键、顺序、漏键，停一下才出；双拼的顺序、多键、漏键也是停一下才出），都没有时对应的开关灰掉。 */
internal data class TypoCorrectionSupport(val fullPinyin: Boolean, val doublePinyin: Boolean, val nineKey: Boolean = false) {
    companion object {
        val ALL = TypoCorrectionSupport(fullPinyin = true, doublePinyin = true, nineKey = true)

        fun of(context: android.content.Context): TypoCorrectionSupport =
            of(com.kingzcheung.xime.settings.SchemaManager.getEnabledSchemas(context) + SettingsPreferences.getCurrentSchema(context))

        fun of(schemas: Collection<String>): TypoCorrectionSupport {
            val chinese = schemas.distinct().map { com.kingzcheung.xime.settings.InputProfiles.describe(it) }
                .filter { it.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE }
            val profiles = chinese.filter { it.layout.kind == com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC }
            return TypoCorrectionSupport(
                fullPinyin = profiles.any { it.scheme == com.kingzcheung.xime.settings.InputScheme.PINYIN },
                doublePinyin = profiles.any { it.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN },
                // 九键双拼不算：它不用九键全拼的数字纠错表。
                nineKey = chinese.any { it.layout.kind == com.kingzcheung.xime.settings.LayoutKind.T9 &&
                    it.scheme == com.kingzcheung.xime.settings.InputScheme.PINYIN })
        }
    }
}

/** 模糊音：每组一行开关，默认全关；改了在后台写方案补丁，输入法重新部署后生效（见 [SpellingPatches]）。放在「语言与方案」。 */
@Composable
internal fun FuzzyPinyinSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(SpellingPatches.fuzzy(context)) }
    var doublePinyin by remember { mutableStateOf(SpellingPatches.fuzzyDoublePinyin(context)) }
    var t9Digits by remember { mutableStateOf(SpellingPatches.fuzzyT9Digits(context)) }
    // 现在打的是双拼而开关关着：下面各组对双拼不起作用（10-31 反馈小鹤 bkgr 出不来宾馆）。
    val usingDoublePinyin = remember { runCatching { SpellingPatches.usingDoublePinyin(context) }.getOrDefault(false) }
    // 「模糊音也用于双拼」默认开；11-01 起只给关掉过它的人显示，好让他们开回来（用户 11-01 批准）。
    val showDoublePinyinSwitch = remember { !doublePinyin }
    Column(Modifier.settingsSearchAnchor("模糊音")) {
        SettingsSection(title = "模糊音", content = {
            Text("14 键双拼不适用。九键按数字后点左侧读音，带下划线、颜色浅一些的是相近读音。" +
                "改完要几秒重新部署。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // 用户 11-02：九键按数字也模糊很耗性能，做成开关、默认关，开关和每组下面写实测代价，卡了自己关。
            SettingsToggleItem(Icons.TwoTone.Hearing, "九键按数字也模糊",
                "九键直接按数字也出下面所开各组的相近读音(如 74 也出“是”)，不用再点左侧读音。" +
                    "候选变多、每键变慢，首选也更容易不是想要的字；开的组越多越明显，打字卡了就关掉。改完要几秒重新部署。",
                checked = t9Digits, onCheckedChange = { on ->
                    SpellingPatches.setFuzzyT9Digits(context, on)
                    t9Digits = on
                    scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                },
                // 它只把下面开着的组用到数字上；一组都没开时没有效果，置灰写明（存的值不变）。
                lockMessage = if (enabled.isEmpty()) "下面各组都没开，开了才起作用" else null,
                extraCost = {
                    PerformanceCostTotalNote(enabled.mapTo(linkedSetOf()) { com.kingzcheung.xime.settings.PerformanceCosts.Item.t9Fuzzy(it) },
                        label = "性能开销(已开的 ${enabled.size} 组合计)", tag = "t9-fuzzy-digits")
                })
            if (showDoublePinyinSwitch) {
                HorizontalDivider()
                SettingsToggleItem(Icons.TwoTone.Hearing, "模糊音也用于双拼",
                    "关着时下面各组只对全拼起作用；打开后双拼也按读音模糊(in 和 ing 互通)",
                    checked = doublePinyin, onCheckedChange = { on ->
                        SpellingPatches.setFuzzyDoublePinyin(context, on)
                        doublePinyin = on
                        scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                    })
                if (usingDoublePinyin && !doublePinyin) Text("你现在用的是双拼：要打开上面这个开关，下面打开的各组才对双拼起作用。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("fuzzy-double-pinyin-hint"))
            }
            SpellingPatches.Fuzzy.entries.forEach { item ->
                HorizontalDivider()
                SettingsToggleItem(Icons.TwoTone.Hearing, item.title, item.example,
                    checked = item in enabled, onCheckedChange = { on ->
                        SpellingPatches.setFuzzy(context, item, on)
                        enabled = SpellingPatches.fuzzy(context)
                        scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                    }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.fuzzy(item),
                    // 「九键按数字也模糊」开着时，再写这一组在九键上的代价（另在九键上实测）。
                    extraCost = if (!t9Digits) null else {
                        { PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.t9Fuzzy(item), label = "九键性能开销") }
                    })
            }
        })
    }
}

@Composable
private fun QwertyStaggerSettings(
    settings: com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences,
    onChange: (com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences) -> Unit,
) {
    val modes = com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.entries
    Column(Modifier.settingsSearchAnchor("三行字母错位").padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("三行字母错位", style = MaterialTheme.typography.titleSmall)
        Text("实体键盘：A 行右移 1/4 键、Z 行右移 3/4 键；手机常见：Z 与 S 上下对齐；按尺寸自动：手机用手机排布，" +
            "平板逐渐过渡到实体排布。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().choiceCardRowHeight(108.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { mode ->
                IllustratedChoiceCard(mode.label, settings.qwertyStagger == mode,
                    onClick = { onChange(settings.copy(qwertyStagger = mode)) },
                    modifier = Modifier.weight(1f), tag = "qwerty-stagger:${mode.name}") {
                    QwertyStaggerIllustration(when (mode) {
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.PHYSICAL -> 1f
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.PHONE -> 0f
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.CUSTOM -> settings.qwertyStaggerCustom
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.AUTO -> 0.5f
                    })
                }
            }
        }
        if (settings.qwertyStagger == com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.CUSTOM) {
            var custom by remember(settings.qwertyStaggerCustom) { mutableStateOf(settings.qwertyStaggerCustom) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手机", style = MaterialTheme.typography.bodySmall)
                Slider(value = custom, onValueChange = { custom = it },
                    onValueChangeFinished = { onChange(settings.copy(qwertyStaggerCustom = custom)) },
                    valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        .testTag("qwerty-stagger-custom"))
                Text("实体", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 「去掉 0 键，回车加高」的说明：照实写开了以后少了什么、0 去哪打。
 * 回车上打 0 和 1～9 的数字角标同一个方向（跟「反转上下滑方向」），关了「上下滑输入符号」就不接。
 */
/** 笔画键盘「去掉 0 键，回车加高」的说明：笔画的 0 键打字时也是「下一项」（11-18），去掉后和九键一样没有。 */
internal fun strokeMergeZeroSubtitle(cornerSymbolInput: Boolean, reverseSymbolSwipe: Boolean): String {
    val zero = if (cornerSymbolInput) "数字 0 在回车上${if (reverseSymbolSwipe) "上滑" else "下滑"}打(和 1～9 的数字一样，打字选词时不接)，数字键盘里也有。"
        else "「上下滑输入符号」关着，回车上不能打 0，到数字键盘里打。"
    return "笔画键盘右边一列只留删除、重输和占两格高的回车。没有「下一项」键：不能点它切下一项、左滑上一项，选词直接点候选。$zero"
}

internal fun t9MergeZeroSubtitle(cornerSymbolInput: Boolean, reverseSymbolSwipe: Boolean): String {
    val zero = if (cornerSymbolInput) "数字 0 在回车上${if (reverseSymbolSwipe) "上滑" else "下滑"}打(和 1～9 的数字一样，打字选词时不接)，数字键盘里也有。"
        else "「上下滑输入符号」关着，回车上不能打 0，到数字键盘里打。"
    return "右边一列只留删除、重输和占两格高的回车。没有「下一项」键：不能点它切下一项、左滑上一项，" +
        "长按换英文、拖到候选上屏英文也用不了，选词直接点候选。$zero"
}

/**
 * 笔画编码显示成 一丨丿丶乛（默认关，显示 h s p n z；11-18 贡献者偏向全用字母）。
 * 存在 [SpellingPatches.STROKE_GLYPH_PREEDIT_KEY]，改完写方案补丁重新部署；另一处改动或备份恢复时跟着变。
 */
@Composable
internal fun StrokeGlyphPreeditToggle() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(context) { mutableStateOf(SpellingPatches.strokeGlyphPreedit(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SpellingPatches.STROKE_GLYPH_PREEDIT_KEY) enabled = SpellingPatches.strokeGlyphPreedit(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsToggleItem(Icons.TwoTone.Abc, "笔画编码显示成 一丨丿丶乛",
        "关着时输入串和候选后面的剩余笔画显示 h s p n z(横竖撇点折)，打开后都显示成 一丨丿丶乛。改完要几秒重新部署",
        checked = enabled, onCheckedChange = { on ->
            SpellingPatches.setStrokeGlyphPreedit(context, on)
            enabled = on
            scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
        })
}
