package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.KeyEffectPreferences

private const val DEFAULT_KEY_TEXT_SCALE = 1.2f

internal fun normalizeHandwritingPause(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 0.5f).coerceIn(0.1f, 2.5f) * 10).roundToInt() / 10f

internal fun normalizeCursorHoldSeconds(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 0.2f).coerceIn(0.1f, 1f) * 10).roundToInt() / 10f

/** 移光标时从中间滑到边缘走几行：0.5–3 行，每档 0.25 行，默认 1 行。 */
internal fun normalizeCursorSwipeLines(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 1f).coerceIn(0.5f, 3f) * 4).roundToInt() / 4f

/** 长按语言键打开语言列表的等待时间：0.05–1 秒，每档 0.05 秒，默认 0.15 秒。 */
internal fun normalizeLanguageMenuHoldSeconds(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 0.15f).coerceIn(0.05f, 1f) * 20).roundToInt() / 20f

/**
 * 26 键符号键点按的时限：0.1–2 秒，每档 0.1 秒，默认 1 秒；不拖动且在时限内松手才打开符号。
 * 最短原来 0.3 秒，#362 要两手同时按 !@# 和字母当 Ctrl 组合键，放到 0.1 秒；默认不变。
 */
internal fun normalizeSymbolKeyHoldSeconds(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 1f).coerceIn(0.1f, 2f) * 10).roundToInt() / 10f

/**
 * 「长按输入」角标时按住多久输入：0.1–1 秒，每档 0.05 秒，默认 0.5 秒（用户 11-15 定；原来 0.3 秒，11-08 起可调）。
 * 26/14/17/18 键、九键（含拂动时的数字）、笔画和自定义布局共用这一个时长。
 */
internal fun normalizeSymbolLongPressSeconds(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 0.5f).coerceIn(0.1f, 1f) * 20).roundToInt() / 20f

/** 九键「下一项」拖到候选算拖动选词的距离：键盘区宽或高的 20%–80%，每档 10%，默认 50%（用户 10-25 定的一半）。 */
/** 角标不透明度：10%~100%，按 5% 一档；读坏了当 100%（原样）。 */
internal fun normalizeCornerHintOpacity(value: Float): Float =
    if (!value.isFinite()) 1f else (kotlin.math.round(value.coerceIn(0.1f, 1f) * 20f) / 20f)

/** 删除键上下滑清空、撤回的距离：原来 80dp 的 70%–120%，每档 10%，默认 100%（11-05 别人提的需求，范围只够微调）。 */
internal fun normalizeClearSwipePercent(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 100f).coerceIn(70f, 120f) / 10f).roundToInt() * 10f

/** 删除键上下滑要滑过的距离（dp）；100% 就是原来所有按键共用的 80dp。 */
internal const val CLEAR_SWIPE_BASE_DP = 80f

internal fun normalizeCandidateDragPercent(value: Float): Float =
    ((value.takeIf { it.isFinite() } ?: 50f).coerceIn(20f, 80f) / 10f).roundToInt() * 10f

/** 手写公式上屏时两边加的 LaTeX 标记。 */
enum class FormulaDelimiter(val label: String, val open: String, val close: String) {
    NONE("不加", "", ""), DOLLAR("\$…\$", "\$", "\$"), DISPLAY("\\[ … \\]", "\\[", "\\]");
    fun wrap(latex: String): String = open + latex + close
}

enum class SpaceHoldAction(val label: String) {
    /** VOICE_TOGGLE 行为是按住说话、松手结束（10-30 起）；名字「语音开关」不改。 */
    CURSOR("移动光标"), REPEAT("连续空格"), VOICE_TOGGLE("语音开关")
}

enum class CursorGestureMode(val label: String) {
    SPACE("长按空格"), KEYBOARD("键盘滑动"), NONE("关闭")
}

data class KeyboardInputPreferences(
    val spaceHold: SpaceHoldAction = SpaceHoldAction.CURSOR,
    val cursorStepDp: Float = 10f,
    val keyTextScale: Float = DEFAULT_KEY_TEXT_SCALE,
    /**
     * 角标不透明度（主题与外观 › 角标与气泡），乘在角标原来的淡化上：1 = 原样（不开玻璃时按主字的 50%~70%，开玻璃时不淡化），
     * 调低更淡。开不开玻璃都起作用（GitHub #7 #60）。
     */
    val cornerHintOpacity: Float = 1f,
    /** 功能键不显示提示字：空格不写方案名，删除键不写上滑的「清空」，重输键不写「重输」；只改显示，滑动照旧（GitHub #11 #60，默认关）。 */
    val hideFunctionKeyHints: Boolean = false,
    /** 字母键帽一律显示大写（只改显示，输入照旧跟 Shift）；默认关，保持小写。 */
    val uppercaseKeyLabels: Boolean = false,
    /** 像搜狗：中文时字母键显示大写，英文时照 Shift 显示（平时小写），只改显示（默认关）。 */
    val chineseUppercaseKeyLabels: Boolean = false,
    val fixedSymbols: String = "",
    val keyGlowEnabled: Boolean = false,
    val keyAnimationEnabled: Boolean = false,
    /** 键缝动效（默认关）；各项细调见 [com.kingzcheung.xime.settings.KeyGapLightConfig]。 */
    val gapLight: com.kingzcheung.xime.settings.KeyGapLightConfig = com.kingzcheung.xime.settings.KeyGapLightConfig(),
    val handwritingPauseSeconds: Float = 0.5f,
    /** 手写边写边识别：每写完一笔就在后台先认，停顿一到直接出候选（默认关，见 HandwritingInputSession）。 */
    val handwritingRecognizeWhileWriting: Boolean = false,
    val showPressBubble: Boolean = false,
    val showPreeditPreview: Boolean = true,
    /** 候选滑到头回弹展开（默认关）：关着时展开页里左右划不收回，同 1.15.101，见 [SettingsPreferences.isCandidateSwipeExpand]。 */
    val candidateSwipeExpand: Boolean = false,
    /** 首选上拖选改局部（默认开，只对超过 6 个字的首选），见 [SettingsPreferences.isSentenceDragEdit]。 */
    val sentenceDragEdit: Boolean = true,
    val inputTextLocation: String = SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR,
    /** 「显示在候选栏」时拼音的位置，见 [SettingsPreferences.preeditStyle]。 */
    val preeditStyle: String = SettingsPreferences.PREEDIT_STYLE_BUBBLE,
    /** 拼音靠左、居中还是靠右，四种样式都用，见 [SettingsPreferences.preeditAlign]。 */
    val preeditAlign: String = SettingsPreferences.PREEDIT_ALIGN_LEFT,
    /** 拼音气泡和拼音编辑条底色的不透明度，见 [SettingsPreferences.preeditBubbleOpacity]。 */
    val preeditBubbleOpacity: Float = SettingsPreferences.DEFAULT_PREEDIT_BUBBLE_OPACITY,
    /** 拼音按首选的读音标调，见 [SettingsPreferences.preeditTones]。 */
    val preeditTones: Boolean = false,
    val chineseLetterSwipes: Map<String, String> = emptyMap(),
    val englishLetterSwipes: Map<String, String> = emptyMap(),
    /** 九键数字键下方角标那一滑的动作（#297），见 [com.kingzcheung.xime.settings.LetterSwipeBindings.T9]。默认空。 */
    val t9Swipes: Map<String, String> = emptyMap(),
    val splitKeyboardEnabled: Boolean = false,
    /** 分体 26 键右半中排再放一个 G（默认关）。 */
    val splitRightG: Boolean = false,
    /** 中缝和两侧留空用键盘底色填满（默认镂空）。 */
    val fillBlankAreas: Boolean = false,
    /** 加宽按键：两侧留白和横向键缝变小，键帽更宽（默认关，见 [KeyVisualPolicy.widened]）。 */
    val wideKeys: Boolean = false,
    /** 合并键均匀排布：14 键和自建合并键布局里合并键只比单字母键宽三分之一（默认关，见 [letterKeySpan]）。 */
    val evenMergedKeys: Boolean = false,
    /** 数字行：26 键、14 键字母上方加一排数字（默认关，键和顺序见 [numberRowKeys]）。 */
    val numberRow: Boolean = false,
    /** 数字行和字母一样高（#444，默认关，见 [numberRowHeightWeight]）。 */
    val numberRowFullHeight: Boolean = false,
    /** 密码输入框使用 26 键时自动弹出数字行（默认开，见 [SettingsPreferences.isPasswordAutoNumberRowEnabled]）。 */
    val passwordAutoNumberRow: Boolean = true,
    /** 数字键盘 7 8 9 在上（默认 1 2 3 在上），见 [numberPadRows]、[SettingsPreferences.isNumberPadTop789]。 */
    val numberPadTop789: Boolean = false,
    /** 分体中缝比例（竖屏/横屏），null 为自动。 */
    val splitGapPortrait: Float? = null,
    val splitGapLandscape: Float? = null,
    /** 26 键三行错位；自定义时取 [qwertyStaggerCustom]（0 手机常见，负数向右更多，1 实体键盘）。 */
    val qwertyStagger: QwertyStaggerMode = QwertyStaggerMode.AUTO,
    val qwertyStaggerCustom: Float = 0.5f,
    /** 空格左侧逗号键、右侧句号键。 */
    val qwertyCommaKey: Boolean = true,
    val qwertyPeriodKey: Boolean = true,
    /** 空格两侧标点键、数字键盘点键的角标固定上滑/长按（11-09 起默认开：键在屏幕底边，往下滑不出去）。 */
    val bottomPunctuationSwipeUp: Boolean = true,
    /** 底排 123 在左、!@# 在右（#499，默认关），见 [com.kingzcheung.xime.keyboard.modeSlotOrder]。 */
    val swapModeKeys: Boolean = false,
    val handwritingWubiHints: Boolean = false,
    val englishSentenceCaps: Boolean = false,
    /** 逗号键分词（默认关）：空格旁的标点键左下角放 '，打拼音时它和字母键角标里的 ' 都是分词，见 [syllableSplitActive]。 */
    val syllableSplitKey: Boolean = false,
    /** Shift 键分词（默认关）：打拼音时点 Shift 插入 '，键面写「分词」；没在打拼音时照旧大小写。 */
    val shiftSyllableSplit: Boolean = false,
    /**
     * 点一下 Shift 就锁定大写（默认关，用户 11-23 转群友要的）：开着时点一下一直大写，再点一下回小写。
     * 关着照旧：点一下只大写下一个字母，连点两下才锁定。按住、拖到字母上、打拼音时的分词都不变。
     */
    val shiftTapLocks: Boolean = false,
    /** 日语 26 键长音 ー 放在哪：默认 L 键就是长音；可改成 L 右侧单独键或代替 Shift，见 [JapaneseLongVowelKey]。 */
    val japaneseLongVowelKey: JapaneseLongVowelKey = JapaneseLongVowelKey.L_KEY,
    /** 各固定栏按位置保存的符号（每行一个位置）；未设置的沿用旧的共用列表。 */
    val sideSymbols: Map<String, String> = emptyMap(),
    val cursorGesture: CursorGestureMode = if (spaceHold == SpaceHoldAction.CURSOR) CursorGestureMode.SPACE else CursorGestureMode.NONE,
    val neighborCorrection: Boolean = true,
    /** 双拼 26 键邻键纠错，默认关；方案补丁见 SpellingPatches。 */
    val doublePinyinCorrection: Boolean = false,
    /** 双拼 26 键每个键上写出翘舌声母和韵母（双拼键位提示），默认关；见 DoublePinyinKeyHints。 */
    val doublePinyinKeyHints: Boolean = false,
    /** 双拼键位提示和上下角标一起显示（声母、上角标在上，韵母、下角标在下），默认关；开着时两者不再互锁。 */
    val doublePinyinKeyHintsWithCorners: Boolean = false,
    /**
     * 上方、下方角标提示（主题与外观 › 角标与气泡）。只读：由 SettingsPreferences 自己保存，[save] 不写；
     * 放在这里是为了设置互锁（InputGestureLocks：和双拼键位提示占同一块键面）。
     */
    val swipeUpHints: Boolean = true,
    val swipeDownHints: Boolean = true,
    /**
     * 微软双拼的 ing 放在 Shift 键上（和多数手机输入法一样），默认关：第二排不补分号键、L 在最右边，
     * 打了声母等韵母时左下角的 Shift 键变成 ing；见 KeysConfigHelper.setSemicolonOnShift、[SemicolonShiftKey]。
     */
    val doublePinyinSemicolonOnShift: Boolean = false,
    /**
     * 双拼辅助码开着（双拼方案 › 辅助码，默认关）。只读：由 SpellingPatches 自己保存（选的是哪张码表），[save] 不写；
     * 放在这里是为了设置互锁（InputGestureLocks：打字时和「Shift 键分词」都要占 Shift 键）。
     */
    val doublePinyinAuxCode: Boolean = false,
    /** 中文全拼 26 键中英混输（nihaohello → 你好hello），默认关；见 app/data/cyime_mixed_input.lua。 */
    val mixedInput: Boolean = false,
    /** 中文全拼 26 键、双拼和九键补出中文生僻字（瀺 chan），默认关；见 app/data/cyime_rare_chars.lua。 */
    val rareCharacters: Boolean = false,
    /**
     * 日文汉字（沢 ze、榎本武扬）、韩文汉字（乫 jia）、粤语字（佢 qu），11-20 从生僻字里分出来，各自默认关；同一个 lua。
     * 没存过时跟「生僻字」原来的值（[splitFromRare]），开过生僻字的人更新后照样打得出。
     */
    val japaneseKanji: Boolean = false,
    val koreanHanja: Boolean = false,
    val cantoneseCharacters: Boolean = false,
    /**
     * 中文候选和上屏用台湾正体（台灣、計畫），默认关；Rime 选项 traditionalization，转换表见 assets/rime_chinese/opencc。
     * 注音也跟这一个开关（11-09 合并了原来单独的「注音输出简体」）：关时注音方案开 zhuyin_simplified 转简体。
     */
    val traditionalOutput: Boolean = false,
    /** 打出“时间、日期、星期”时紧跟着给当前时间和日期（15:35、2026-10-31），默认关（更新不改原有习惯，要用在 语言与方案 › 中文输出 打开）；Rime 滤镜 cyime_datetime（librime-t9 datetime_filter.h）。 */
    val datetimeCandidates: Boolean = false,
    /** 英文选词后自动加空格（别人请求，默认关）；见 service/EnglishAutoSpace.kt。 */
    val englishAutoSpace: Boolean = false,
    /** 英文键盘打 , . ? ! : ; 后自动补一个空格（别人请求，默认关）；见 service/EnglishAutoSpace.kt。 */
    val englishPunctuationSpace: Boolean = false,
    /** 打 @ 时候选栏给常用邮箱后缀（qq.com、gmail.com），默认开（用户 11-03 要求）；见 service/EmailSuffixes.kt。 */
    val emailSuffixes: Boolean = true,
    /** 网址、邮箱框一进去就是英文（默认开，原有行为）；关掉后沿用当前语言，密码框照旧锁英文。见 service/EditorSessionInputPolicy.kt。 */
    val addressFieldsEnglish: Boolean = true,
    /** 中文候选在词后面多出对应表情（开心 😄），默认关；Rime 选项 emoji，表情表 opencc/emoji.json（雾凇）。 */
    val candidateEmoji: Boolean = false,
    /** 打符号名出符号（加号 +、摄氏度 ℃、度 °、平方 ²、西塔 θ），默认开（B 站 11-18：用户以为这些打不出来）；
     *  Rime 选项 cyime_symbol_names，符号表 assets/rime_chinese/opencc/cyime_symbols.txt。只在候选整词等于符号名时追加，不挪原词。 */
    val symbolNames: Boolean = true,
    /** 打希腊字母名出希腊字母（xita θ、pi π、alpha α），别人请求，默认关；Rime 选项 cyime_greek_letters，见 app/data/cyime_greek_letters.lua。 */
    val greekLetters: Boolean = false,
    val cylmLongAlternative: Boolean = false,
    val frostedGlass: FrostedGlassConfig = FrostedGlassConfig(),
    val symbolInputMode: SymbolInputMode = SymbolInputMode.SWIPE_UP,
    /** 「长按输入」角标的触发时长，见 [normalizeSymbolLongPressSeconds]；只在角标为长按输入（含被 26 键滑行锁成长按）时起作用。 */
    val symbolLongPressSeconds: Float = 0.5f,
    val reverseSymbolSwipe: Boolean = false,
    /** 打字中（有拼音、候选或联想）清空时连同整个输入框一起清，默认关只清拼音（10-31 别人提的需求）。 */
    val clearAllWhileComposing: Boolean = false,
    /** 删除键清空只清光标前（和选中）的字，光标后的留着，默认关（10-31 用户要求）。 */
    val clearBeforeCursorOnly: Boolean = false,
    /** 删除键左右滑动删字：左滑删光标前、右滑删光标后，滑得越远删得越多，默认开（11-01 用户要求可关）。 */
    val swipeDistanceDelete: Boolean = true,
    /** 按住删除键连删的快慢，振动间隔跟着换档；默认「标准」即原来的手感（11-03 别人提的需求）。 */
    val deleteRepeatSpeed: com.kingzcheung.xime.keyboard.DeleteRepeatSpeed = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.DEFAULT,
    /** 删除键单独反向：在「反转上下滑方向」给的方向上再把删除键的清空、撤回对调，角标不动，默认关（11-05 别人提的需求）。实际方向看 [deleteSwipeReversed]。 */
    val reverseDeleteSwipe: Boolean = false,
    /** 删除键上下滑清空、撤回要滑多远，按原来距离的百分比，见 [normalizeClearSwipePercent]；默认 100% 不变（11-05 别人提的需求）。 */
    val clearSwipePercent: Float = 100f,
    val cursorHoldSeconds: Float = 0.2f,
    /** 移动光标时每走一字轻震一下；原来一直有，默认开保持手感（2026-10-22 起可关）。 */
    val cursorStepHaptic: Boolean = true,
    /** 移动光标时每走一字播放按键音；新加，默认关。 */
    val cursorStepSound: Boolean = false,
    val fourteenLetterSwipe: Boolean = true,
    val fourteenLetterSwipeDp: Float = 24f,
    /** 上下滑输入符号要滑多远（#426，dp）；新加，默认 50 和原来写死的一样。 */
    val symbolSwipeDp: Float = DEFAULT_SYMBOL_SWIPE_DP,
    val kanaDirectionHints: Boolean = false,
    /**
     * 拂动时在按键上方显示四向圆盘并在键上显示方向箭头（日语假名、九键拂动、14 键字母对）；关闭时与 26 键一样在键内渐变提示。
     * 默认开（2026-10-16 用户要求恢复日语拂动浮窗；10.11 曾改为默认关）。
     */
    val flickDirectionDisc: Boolean = true,
    /** 英文 26 键滑行输入；整键盘滑动移动光标时让位。 */
    val glideTyping: Boolean = false,
    /** 中文全拼、双拼 26 键滑行输入，与英文分开、默认关（用户 10-31：开着英文滑行的老用户不能被带着开）。 */
    val chineseGlideTyping: Boolean = false,
    /** Delete the last syllable of an uncommitted Chinese glide as one unit. */
    val deleteChineseGlideCharacter: Boolean = false,
    /** 导入的自定义编码方案滑行用的编码（方案编号 → 全拼或双拼方案编号，#303），没选的不滑行。 */
    val externalGlideEncodings: Map<String, String> = emptyMap(),
    /** 滑行时在输入框实时显示识别结果；滑动中反复识别，多占 CPU，默认关。 */
    val glidePreview: Boolean = false,
    /** 长按弹出字母气泡（26/14 键大小写和带音调字母、九键本键字母）；默认关，免得打断上下拖动输入角标。 */
    val letterLongPressMenu: Boolean = false,
    /**
     * 上下滑（和「长按输入」的长按）输入角标符号；默认开，和以前一样（用户 11-14：要能真的关掉）。
     * 和「上方/下方角标提示」各管各的：提示只管键上画不画。关掉时直接上屏的角标都不出，
     * 用户给字母设的上下滑动作（撤销、复制这类）不算角标，照旧。
     */
    val cornerSymbolInput: Boolean = true,
    /** 九键拂动选字母（左上右下），数字改为长按。 */
    val t9FlickLetters: Boolean = false,
    val t9GlideTyping: Boolean = false,
    /**
     * 九键去掉 0 键、回车加高占两格（11-15 很多人要，默认关，原来的排法不变）。开了以后「下一项」键没了：
     * 下一项/上一项、长按换中英、拖到候选上屏英文都不能用；数字 0 改成在回车上滑（数字键盘照旧有 0）。见 [T9_MERGE_ZERO_ENTER_KEY]。
     */
    val t9MergeZeroIntoEnter: Boolean = false,
    /** 笔画键盘同款：去掉 0 键、回车加高占两格，0 改成在回车上滑（用户 11-17 转来，默认关）。见 [STROKE_MERGE_ZERO_ENTER_KEY]。 */
    val strokeMergeZeroIntoEnter: Boolean = false,
    /**
     * 九键空格上滑输入 0（11-22 别人提、他要进 1.15.105，默认关）。开了以后九键右边那个键一直是「→ 下一项」，
     * 没有候选时点它光标右移、左滑左移；和「上滑取消」互锁，见 [InputGestureLocks]。见 [T9_SPACE_UP_ZERO_KEY]。
     */
    val t9SpaceSwipeUpZero: Boolean = false,
    /** 长按语言键弹出列表，与空格长按分开设置；默认 0.15 秒（2026-10-13 用户要求）。 */
    val languageMenuHoldSeconds: Float = 0.15f,
    /** 长按语言键弹出系统的输入法选择框，不弹语言列表（GitHub #60 #68，别人的需求，默认关）。 */
    val languageKeyImePicker: Boolean = false,
    /** 按住符号键当 Ctrl；不拖动且在这个时限内松手才打开符号面板，超过时限松手什么都不做；默认 1 秒（2026-10-24 用户要求）。 */
    val symbolKeyHoldSeconds: Float = 1f,
    /** 九键从「下一项」拖出超过键盘区宽或高的这个百分比才算拖动选词，没拖这么远仍是键自己的左右滑；默认 50（用户 10-31 要求可调）。 */
    val candidateDragPercent: Float = 50f,
    /** 手写公式上屏时两边的标记，默认 $…$。 */
    val formulaDelimiter: FormulaDelimiter = FormulaDelimiter.DOLLAR,
    /** 移光标时从中间滑到屏幕边缘走几行，按输入框一行能放几个字换算每字距离。 */
    val cursorSwipeLines: Float = 1f,
    /** 固定键盘底部留白（调节键盘里往上拖出的那一条）上左右滑动移动光标；默认关（用户 10-31 要求新增）。 */
    val bottomLiftCursor: Boolean = false,
    /** 按住空格说话时上滑取消：手指往上滑出空格约一个键高再松手，这次说的话不上屏（默认关，别人提的需求，11-02）。 */
    val voiceSwipeCancel: Boolean = false,
    /** 固定键盘按键区左右两边不触发系统边缘手势（返回、侧边栏），默认关（11-20 荣耀用户：边缘手势吞掉贴边按键）。 */
    val edgeGestureExclusion: Boolean = false,
) {
    internal val showCodeInCandidateBar: Boolean get() = showPreeditPreview &&
        com.kingzcheung.xime.service.TerminalEditor.inputTextLocation { inputTextLocation } == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
    /** 拼音贴在键盘边框上（圆角小签、贴边方块、顶上一行三种），不是浮着的气泡。 */
    internal val preeditOnTopLine: Boolean get() = showCodeInCandidateBar && preeditStyle != SettingsPreferences.PREEDIT_STYLE_BUBBLE
    /** 删除键实际是否「上滑清空、下滑撤回」：跟「反转上下滑方向」走，「删除键单独反向」打开再对调一次。 */
    val deleteSwipeReversed: Boolean get() = reverseSymbolSwipe != reverseDeleteSwipe
    /** 删除键上下滑清空、撤回的触发距离（dp）。 */
    val clearSwipeDistanceDp: Float get() = CLEAR_SWIPE_BASE_DP * normalizeClearSwipePercent(clearSwipePercent) / 100f
    val effectiveSpaceHold: SpaceHoldAction get() = if (cursorGesture == CursorGestureMode.SPACE) SpaceHoldAction.CURSOR
        else spaceHold.takeUnless { it == SpaceHoldAction.CURSOR } ?: SpaceHoldAction.REPEAT
    /** 长按空格不再移动光标时，滑动移动光标改成哪种：键盘滑动；滑行输入锁着键盘滑动就关掉。 */
    val cursorGestureAfterLeavingSpace: CursorGestureMode get() =
        if (InputGestureLocks.lockFor(this, InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD) == null)
            CursorGestureMode.KEYBOARD else CursorGestureMode.NONE
    /** 设置页选长按空格连续空格/语音：长按空格正在移动光标时一并把移动光标改成 [cursorGestureAfterLeavingSpace]，选了立刻生效。 */
    fun withSpaceHold(action: SpaceHoldAction): KeyboardInputPreferences = copy(spaceHold = action,
        cursorGesture = if (cursorGesture == CursorGestureMode.SPACE) cursorGestureAfterLeavingSpace else cursorGesture)
    /** 空格的所有长按行为（移动光标、连续空格、语音）共用“空格长按触发”时长，默认 0.2 秒。 */
    val spaceHoldDelayMs: Long get() = (normalizeCursorHoldSeconds(cursorHoldSeconds) * 1000).roundToInt().toLong()
    val symbolKeyHoldMs: Long get() = (normalizeSymbolKeyHoldSeconds(symbolKeyHoldSeconds) * 1000).roundToInt().toLong()
    val symbolLongPressMs: Long get() = (normalizeSymbolLongPressSeconds(symbolLongPressSeconds) * 1000).roundToInt().toLong()
    /**
     * 滑行在按住多久后不再接管：长按到点就已经输入角标，之后再拖动不能又滑出一个词。
     * 角标为长按输入（自己选的，或开着 26 键滑行被锁成长按）时取 [symbolLongPressMs] 和原来 300 毫秒里短的那个；其余不变。
     */
    val glideHoldMs: Long get() = if (CornerInputChoice.of(this) == CornerInputChoice.LONG_PRESS)
        minOf(com.kingzcheung.xime.glide.GlideTracker.HOLD_MS, symbolLongPressMs) else com.kingzcheung.xime.glide.GlideTracker.HOLD_MS
    companion object {
        /** 中英混输开关；输入法每次按字母键读它（ImeKeyRouter），改了下一键就生效。 */
        const val MIXED_INPUT_KEY = "mixed_input"
        /** 点一下 Shift 就锁定大写（[shiftTapLocks]），默认关。 */
        const val SHIFT_TAP_LOCKS_KEY = "shift_tap_locks"
        /** 生僻字开关；同繁体输出，输入法按字母、数字键前读它（ImeKeyRouter）。 */
        const val RARE_CHARACTERS_KEY = "rare_characters"
        /** 日文汉字、韩文汉字、粤语字开关；同生僻字，按键前读。 */
        const val JAPANESE_KANJI_KEY = "japanese_kanji"
        const val KOREAN_HANJA_KEY = "korean_hanja"
        const val CANTONESE_CHARACTERS_KEY = "cantonese_characters"

        /**
         * 11-20 前这些字都在「生僻字」里：新开关没存过时取生僻字的值，开过生僻字的人更新后不少字；没开过的照样是关。
         */
        fun splitFromRare(prefs: SharedPreferences, key: String): Boolean =
            prefs.getBoolean(key, prefs.getBoolean(RARE_CHARACTERS_KEY, false))
        /** 繁体输出开关；同中英混输，输入法按键前读它（ImeKeyRouter）。 */
        const val TRADITIONAL_OUTPUT_KEY = "traditional_output"
        /** 旧的「注音输出简体」，已并入 [TRADITIONAL_OUTPUT_KEY]；不再读，[save] 顺手删掉。 */
        const val LEGACY_ZHUYIN_SIMPLIFIED_KEY = "zhuyin_simplified"
        /** 打字中清空整个输入框；输入法在清空时读它（ImeKeyRouter）。 */
        const val CLEAR_ALL_WHILE_COMPOSING_KEY = "clear_all_while_composing"
        /** 只清空光标前的内容；输入法在清空时读它（ImeKeyRouter）。 */
        const val CLEAR_BEFORE_CURSOR_ONLY_KEY = "clear_before_cursor_only"
        /** 删除键左右滑动删除；键盘界面读它决定删除键接不接横滑删字（KeyboardView）。 */
        const val SWIPE_DISTANCE_DELETE_KEY = "swipe_distance_delete"
        /** 删除键单独反向、清空滑动距离；键盘界面读（KeyButton）。 */
        const val REVERSE_DELETE_SWIPE_KEY = "reverse_delete_swipe"
        const val CLEAR_SWIPE_PERCENT_KEY = "clear_swipe_percent"
        /** 字母键帽显示大写。 */
        const val UPPERCASE_KEY_LABELS_KEY = "uppercase_key_labels"
        const val CHINESE_UPPERCASE_KEY_LABELS_KEY = "chinese_uppercase_key_labels"
        /** 时间日期候选开关；同中英混输，输入法按键前读它（ImeKeyRouter），关掉时设 Rime 选项 _cyime_datetime_off。 */
        const val DATETIME_CANDIDATES_KEY = "datetime_candidates"
        /** 中文滑行输入；新键，旧版本没写过，读不到就是关。 */
        const val CHINESE_GLIDE_KEY = "glide_typing_chinese"
        const val DELETE_CHINESE_GLIDE_CHARACTER_KEY = "delete_chinese_glide_word"
        /** 滑行实时预览；新键，读不到就是关。 */
        const val GLIDE_PREVIEW_KEY = "glide_preview"
        /** 新键（11-14），没存过就是开：老用户升级后行为不变。 */
        const val CORNER_SYMBOL_INPUT_KEY = "corner_symbol_input"
        /** 双拼键位提示开关；设置在「双拼方案」页。 */
        const val DOUBLE_PINYIN_KEY_HINTS_KEY = "double_pinyin_key_hints"
        /** 双拼键位提示和角标一起显示；设置在「双拼方案」页。新键，读不到就是关。 */
        const val DOUBLE_PINYIN_KEY_HINTS_WITH_CORNERS_KEY = "double_pinyin_key_hints_with_corners"
        /** ing 放在 Shift 键上开关；设置在「双拼方案」页的微软双拼一栏。新键，读不到就是关。 */
        const val DOUBLE_PINYIN_SEMICOLON_ON_SHIFT_KEY = "double_pinyin_semicolon_on_shift"
        /** 日语长音键位置（JapaneseLongVowelKey 的名字）；输入法每键前读它写进引擎选项 `_cyime_l_letter`。 */
        const val JAPANESE_LONG_VOWEL_KEY = "japanese_long_vowel_key"
        /** 英文选词后自动加空格开关；输入法选词时读它，改了下一次选词就生效。 */
        const val ENGLISH_AUTO_SPACE_KEY = "english_auto_space"
        /** 英文标点后自动加空格开关；输入法打标点时读它，改了下一个标点就生效。新键，读不到就是关。 */
        const val ENGLISH_PUNCTUATION_SPACE_KEY = "english_punctuation_space"
        /**
         * 打 @ 时给邮箱后缀开关；输入法打 @ 和后面的字时读它。读不到就是开。
         * 1.15.50/51 默认关，存这一页任何开关都会把旧键 email_suffix_candidates 一起写成关；换新键，旧值不再算数。
         */
        const val EMAIL_SUFFIXES_KEY = "email_suffix_candidates_on"
        /** 网址、邮箱框自动切英文开关；输入法进输入框时读它。新键，读不到就是开。 */
        const val ADDRESS_FIELDS_ENGLISH_KEY = "address_fields_english"
        /** 上下滑输入符号的触发距离（#426）。 */
        const val SYMBOL_SWIPE_DP_KEY = "symbol_swipe_dp"
        /** 候选里显示表情开关；同繁体输出，输入法按键前读它（ImeKeyRouter）。 */
        const val CANDIDATE_EMOJI_KEY = "candidate_emoji"
        const val SWAP_MODE_KEYS_KEY = "swap_mode_keys"
        /** 打符号名出符号开关；同表情，输入法按键前读它（ImeKeyRouter），写 Rime 选项 cyime_symbol_names。
         *  默认由关改开（11-18）只改读不到时的默认：存过的值（包括 save() 顺手写下的旧默认 false）一律照旧，不把关掉的翻回开。 */
        const val SYMBOL_NAMES_KEY = "symbol_name_candidates"
        /** 打希腊字母名出希腊字母开关；同符号名，写 Rime 选项 cyime_greek_letters。新键，读不到就是关。 */
        const val GREEK_LETTERS_KEY = "greek_letter_candidates"
        const val CYLM_LONG_ALTERNATIVE_KEY = "cylm_long_alternative"
        /** 底部留白滑动移动光标，见 [bottomLiftCursor]。 */
        const val BOTTOM_LIFT_CURSOR_KEY = "bottom_lift_cursor"
        /** 按住说话上滑取消，见 [voiceSwipeCancel]。 */
        const val VOICE_SWIPE_CANCEL_KEY = "voice_swipe_cancel"
        /** 按键区挡住屏幕边缘手势，见 [edgeGestureExclusion]。新键（11-20），读不到就是关。 */
        const val EDGE_GESTURE_EXCLUSION_KEY = "keyboard_edge_gesture_exclusion"
        const val HANDWRITING_RECOGNIZE_EARLY_KEY = "handwriting_recognize_while_writing"
        const val LANGUAGE_KEY_IME_PICKER_KEY = "language_key_ime_picker"
        /** 九键去掉 0 键合并大回车，见 [t9MergeZeroIntoEnter]。新键（11-15），读不到就是关。 */
        const val T9_MERGE_ZERO_ENTER_KEY = "t9_merge_zero_enter"
        /** 笔画键盘去掉 0 键合并大回车，见 [strokeMergeZeroIntoEnter]。新键（11-17），读不到就是关。 */
        const val STROKE_MERGE_ZERO_ENTER_KEY = "stroke_merge_zero_enter"
        /** 九键空格上滑输入 0，见 [t9SpaceSwipeUpZero]。新键（11-22），读不到就是关。 */
        const val T9_SPACE_UP_ZERO_KEY = "t9_space_up_zero"

        /** 正在显示的键盘监听这些键，改了就重新读取；[save] 写入的每个键都必须在内，否则设置页的改动要等输入法重启才生效。 */
        /** 角标不透明度；新键，读不到就是 1（原样）。 */
        const val CORNER_HINT_OPACITY_KEY = "corner_hint_opacity"
        const val HIDE_FUNCTION_KEY_HINTS_KEY = "hide_function_key_hints"
        internal val watchedKeys: Set<String> by lazy {
            setOf(CORNER_SYMBOL_INPUT_KEY, KeyEffectPreferences.GLOW, KeyEffectPreferences.ANIMATION, SettingsPreferences.KEY_SHOW_PRESS_BUBBLE,
                SettingsPreferences.KEY_SHOW_PREEDIT_PREVIEW, SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND, SettingsPreferences.KEY_SENTENCE_DRAG_EDIT, SettingsPreferences.KEY_INLINE_PREEDIT_PREVIEW, SettingsPreferences.KEY_PREEDIT_STYLE, SettingsPreferences.KEY_PREEDIT_ALIGN, SettingsPreferences.KEY_INPUT_TEXT_LOCATION,
                SettingsPreferences.KEY_PREEDIT_BUBBLE_OPACITY, SettingsPreferences.KEY_PREEDIT_TONES,
                com.kingzcheung.xime.settings.LetterSwipeBindings.CHINESE, com.kingzcheung.xime.settings.LetterSwipeBindings.ENGLISH,
                com.kingzcheung.xime.settings.LetterSwipeBindings.T9,
                SettingsPreferences.KEY_SPLIT_KEYBOARD, SettingsPreferences.KEY_SPLIT_RIGHT_G, SettingsPreferences.KEY_FILL_KEYBOARD_BLANK, SettingsPreferences.KEY_WIDE_KEYS, SettingsPreferences.KEY_EVEN_MERGED_KEYS, SettingsPreferences.KEY_NUMBER_ROW, SettingsPreferences.KEY_NUMBER_ROW_FULL_HEIGHT, SettingsPreferences.KEY_PASSWORD_AUTO_NUMBER_ROW, SettingsPreferences.KEY_NUMBER_PAD_TOP_789, SettingsPreferences.KEY_SPLIT_GAP_PORTRAIT, SettingsPreferences.KEY_SPLIT_GAP_LANDSCAPE, CHINESE_GLIDE_KEY, DELETE_CHINESE_GLIDE_CHARACTER_KEY, com.kingzcheung.xime.glide.ExternalGlideEncodings.KEY, GLIDE_PREVIEW_KEY, ENGLISH_AUTO_SPACE_KEY, ENGLISH_PUNCTUATION_SPACE_KEY, EMAIL_SUFFIXES_KEY, ADDRESS_FIELDS_ENGLISH_KEY, SYMBOL_NAMES_KEY, GREEK_LETTERS_KEY, CYLM_LONG_ALTERNATIVE_KEY,
                "space_hold_action", "cursor_gesture_mode", "cursor_hold_seconds", "neighbor_correction", com.kingzcheung.xime.settings.SpellingPatches.DOUBLE_PINYIN_CORRECTION_KEY, MIXED_INPUT_KEY, RARE_CHARACTERS_KEY, TRADITIONAL_OUTPUT_KEY, CANDIDATE_EMOJI_KEY, DATETIME_CANDIDATES_KEY, "symbol_input_mode", "symbol_long_press_seconds", "symbol_long_press_seconds_v2", "cursor_step_dp", "cursor_swipe_lines", "cursor_step_haptic", "cursor_step_sound", "key_text_scale", CORNER_HINT_OPACITY_KEY, HIDE_FUNCTION_KEY_HINTS_KEY, UPPERCASE_KEY_LABELS_KEY, CHINESE_UPPERCASE_KEY_LABELS_KEY, "fixed_symbols",
                JAPANESE_KANJI_KEY, KOREAN_HANJA_KEY, CANTONESE_CHARACTERS_KEY,
                "fourteen_letter_swipe", "fourteen_letter_swipe_dp", SYMBOL_SWIPE_DP_KEY, "reverse_symbol_swipe", CLEAR_ALL_WHILE_COMPOSING_KEY, CLEAR_BEFORE_CURSOR_ONLY_KEY, SWIPE_DISTANCE_DELETE_KEY, com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.KEY, REVERSE_DELETE_SWIPE_KEY, CLEAR_SWIPE_PERCENT_KEY, JAPANESE_LONG_VOWEL_KEY, "syllable_split_key", "shift_syllable_split", SHIFT_TAP_LOCKS_KEY, "kana_direction_hints", "flick_direction_disc", "flick_direction_disc_v2",
                "glide_typing", "glide_typing_v2", "letter_long_press_menu", "t9_flick_letters", "t9_glide_typing", "language_key_hold_seconds", "language_key_hold_seconds_v2", LANGUAGE_KEY_IME_PICKER_KEY, "symbol_key_hold_seconds", "t9_candidate_drag_percent", T9_MERGE_ZERO_ENTER_KEY, STROKE_MERGE_ZERO_ENTER_KEY, T9_SPACE_UP_ZERO_KEY,
                SettingsPreferences.KEY_HANDWRITING_WUBI_HINTS, "qwerty_stagger_mode", "qwerty_stagger_custom", "qwerty_comma_key", "qwerty_period_key", "qwerty_period_key_v2", "bottom_punctuation_swipe_up_v3", SWAP_MODE_KEYS_KEY, com.kingzcheung.xime.service.ENGLISH_SENTENCE_CAPS,
                DOUBLE_PINYIN_KEY_HINTS_KEY, DOUBLE_PINYIN_KEY_HINTS_WITH_CORNERS_KEY, DOUBLE_PINYIN_SEMICOLON_ON_SHIFT_KEY,
                com.kingzcheung.xime.settings.SpellingPatches.AUX_CODE_KEY,
                SettingsPreferences.KEY_SWIPE_UP_HINTS_ENABLED, SettingsPreferences.KEY_SWIPE_DOWN_HINTS_ENABLED,
                *SideSymbolTarget.entries.map { it.prefKey }.toTypedArray(),
                BOTTOM_LIFT_CURSOR_KEY, VOICE_SWIPE_CANCEL_KEY, EDGE_GESTURE_EXCLUSION_KEY, HANDWRITING_RECOGNIZE_EARLY_KEY,
                "handwriting_pause_seconds", "handwriting_pause_seconds_v2", "handwriting_formula_delimiter") + FrostedGlassPreferences.keys +
                com.kingzcheung.xime.settings.KeyGapLightPreferences.keys
        }

        fun read(context: Context, isDark: Boolean = FrostedGlassPreferences.isDark(context)): KeyboardInputPreferences {
            val prefs = SettingsPreferences.getPrefsPublic(context)
            val legacyPause = prefs.getFloat("handwriting_pause_seconds", 0.5f)
            // 本轮将旧的一秒默认值迁移为半秒；其他已设时长保留。
            val pause = prefs.getFloat("handwriting_pause_seconds_v2", if (legacyPause == 1f) 0.5f else legacyPause)
            // 语言键旧默认 0.1 秒迁移为 0.15 秒；其他已设时长保留。
            val legacyLanguageHold = prefs.getFloat("language_key_hold_seconds", 0.15f)
            val languageHold = prefs.getFloat("language_key_hold_seconds_v2", if (legacyLanguageHold == 0.1f) 0.15f else legacyLanguageHold)
            val hold = SpaceHoldAction.entries.firstOrNull { it.name == prefs.getString("space_hold_action", "CURSOR") }
                ?: SpaceHoldAction.CURSOR
            val numberRow = SettingsPreferences.isNumberRowEnabled(context)
            // 数字行或上下角标提示的开关一变，26 键的角标换成那一套（见 CornerVariant）；键盘随后按新设置重画。
            com.kingzcheung.xime.settings.KeysConfigHelper.refreshCornerVariant(context)
            return KeyboardInputPreferences(
                keyGlowEnabled = KeyEffectPreferences.glowEnabled(prefs),
                keyAnimationEnabled = KeyEffectPreferences.animationEnabled(prefs),
                gapLight = com.kingzcheung.xime.settings.KeyGapLightPreferences.read(prefs),
                // 过透时自动加深到在白底和黑底上都看得清键面文字。
                frostedGlass = FrostedGlassPreferences.read(context, isDark)
                    .legibleForTheme(SettingsPreferences.getKeyboardTheme(context), isDark),
                showPressBubble = SettingsPreferences.shouldShowPressBubble(context),
                showPreeditPreview = SettingsPreferences.showPreeditPreview(context),
                candidateSwipeExpand = SettingsPreferences.isCandidateSwipeExpand(context),
                sentenceDragEdit = SettingsPreferences.isSentenceDragEdit(context),
                inputTextLocation = SettingsPreferences.getInputTextLocation(context),
                preeditStyle = SettingsPreferences.preeditStyle(context),
                preeditAlign = SettingsPreferences.preeditAlign(context),
                preeditBubbleOpacity = SettingsPreferences.preeditBubbleOpacity(context),
                preeditTones = SettingsPreferences.preeditTones(context),
                chineseLetterSwipes = com.kingzcheung.xime.settings.LetterSwipeBindings.load(context, false),
                englishLetterSwipes = com.kingzcheung.xime.settings.LetterSwipeBindings.load(context, true),
                t9Swipes = com.kingzcheung.xime.settings.LetterSwipeBindings.loadT9(context),
                splitKeyboardEnabled = SettingsPreferences.isSplitKeyboardEnabled(context),
                splitRightG = SettingsPreferences.isSplitRightGEnabled(context),
                fillBlankAreas = SettingsPreferences.isKeyboardBlankFilled(context),
                wideKeys = SettingsPreferences.isWideKeysEnabled(context),
                evenMergedKeys = SettingsPreferences.isEvenMergedKeysEnabled(context),
                numberRow = numberRow,
                numberRowFullHeight = SettingsPreferences.isNumberRowFullHeight(context),
                passwordAutoNumberRow = SettingsPreferences.isPasswordAutoNumberRowEnabled(context),
                numberPadTop789 = SettingsPreferences.isNumberPadTop789(context),
                splitGapPortrait = SettingsPreferences.getSplitGapFraction(context, false),
                splitGapLandscape = SettingsPreferences.getSplitGapFraction(context, true),
                qwertyStagger = QwertyStaggerMode.entries.firstOrNull { it.name == prefs.getString("qwerty_stagger_mode", null) }
                    ?: QwertyStaggerMode.AUTO,
                qwertyStaggerCustom = prefs.getFloat("qwerty_stagger_custom", 0.5f).takeIf { it.isFinite() }?.coerceIn(QWERTY_STAGGER_MIN, QWERTY_STAGGER_MAX) ?: 0.5f,
                // 11-09 起默认开。旧键 v2 会被 save() 连同其他设置写成旧默认 false，分不出是不是用户自己关的，只认新键。
                bottomPunctuationSwipeUp = prefs.getBoolean("bottom_punctuation_swipe_up_v3", true),
                swapModeKeys = prefs.getBoolean(SWAP_MODE_KEYS_KEY, false),
                handwritingWubiHints = prefs.getBoolean(SettingsPreferences.KEY_HANDWRITING_WUBI_HINTS, false),
                englishSentenceCaps = prefs.getBoolean(com.kingzcheung.xime.service.ENGLISH_SENTENCE_CAPS, false),
                qwertyCommaKey = prefs.getBoolean("qwerty_comma_key", true),
                // 句号键 10-19 起默认开。旧键会被 save() 连同其他设置一起写成旧默认 false，
                // 分不出是不是用户自己关的，所以只认新键；没有新键就是默认开。
                qwertyPeriodKey = prefs.getBoolean("qwerty_period_key_v2", true),
                syllableSplitKey = prefs.getBoolean("syllable_split_key", false),
                shiftSyllableSplit = prefs.getBoolean("shift_syllable_split", false),
                shiftTapLocks = prefs.getBoolean(SHIFT_TAP_LOCKS_KEY, false),
                japaneseLongVowelKey = JapaneseLongVowelKey.fromName(prefs.getString(JAPANESE_LONG_VOWEL_KEY, null)),
                sideSymbols = SideSymbolTarget.entries.mapNotNull { target ->
                    prefs.getString(target.prefKey, null)?.let { target.key to it }
                }.toMap(),
                spaceHold = hold,
                cursorGesture = CursorGestureMode.entries.firstOrNull { it.name == prefs.getString("cursor_gesture_mode", null) }
                    ?: if (hold == SpaceHoldAction.CURSOR) CursorGestureMode.SPACE else CursorGestureMode.NONE,
                cursorStepDp = prefs.getFloat("cursor_step_dp", 10f).takeIf { it.isFinite() }?.coerceIn(6f, 24f) ?: 10f,
                cursorSwipeLines = normalizeCursorSwipeLines(prefs.getFloat("cursor_swipe_lines", 1f)),
                cursorHoldSeconds = normalizeCursorHoldSeconds(prefs.getFloat("cursor_hold_seconds", 0.2f)),
                cursorStepHaptic = prefs.getBoolean("cursor_step_haptic", true),
                cursorStepSound = prefs.getBoolean("cursor_step_sound", false),
                bottomLiftCursor = prefs.getBoolean(BOTTOM_LIFT_CURSOR_KEY, false),
                voiceSwipeCancel = prefs.getBoolean(VOICE_SWIPE_CANCEL_KEY, false),
                edgeGestureExclusion = prefs.getBoolean(EDGE_GESTURE_EXCLUSION_KEY, false),
                languageMenuHoldSeconds = normalizeLanguageMenuHoldSeconds(languageHold),
                languageKeyImePicker = prefs.getBoolean(LANGUAGE_KEY_IME_PICKER_KEY, false),
                symbolKeyHoldSeconds = normalizeSymbolKeyHoldSeconds(prefs.getFloat("symbol_key_hold_seconds", 1f)),
                candidateDragPercent = normalizeCandidateDragPercent(prefs.getFloat("t9_candidate_drag_percent", 50f)),
                neighborCorrection = prefs.getBoolean("neighbor_correction", true),
                doublePinyinCorrection = prefs.getBoolean(com.kingzcheung.xime.settings.SpellingPatches.DOUBLE_PINYIN_CORRECTION_KEY, false),
                doublePinyinKeyHints = prefs.getBoolean(DOUBLE_PINYIN_KEY_HINTS_KEY, false),
                doublePinyinKeyHintsWithCorners = prefs.getBoolean(DOUBLE_PINYIN_KEY_HINTS_WITH_CORNERS_KEY, false),
                swipeUpHints = SettingsPreferences.isSwipeUpHintsEnabled(context),
                swipeDownHints = SettingsPreferences.isSwipeDownHintsEnabled(context),
                doublePinyinSemicolonOnShift = prefs.getBoolean(DOUBLE_PINYIN_SEMICOLON_ON_SHIFT_KEY, false),
                doublePinyinAuxCode = com.kingzcheung.xime.settings.SpellingPatches.AuxCode.of(
                    prefs.getString(com.kingzcheung.xime.settings.SpellingPatches.AUX_CODE_KEY, null)) != null,
                mixedInput = prefs.getBoolean(MIXED_INPUT_KEY, false),
                rareCharacters = prefs.getBoolean(RARE_CHARACTERS_KEY, false),
                japaneseKanji = splitFromRare(prefs, JAPANESE_KANJI_KEY),
                koreanHanja = splitFromRare(prefs, KOREAN_HANJA_KEY),
                cantoneseCharacters = splitFromRare(prefs, CANTONESE_CHARACTERS_KEY),
                traditionalOutput = prefs.getBoolean(TRADITIONAL_OUTPUT_KEY, false),
                datetimeCandidates = prefs.getBoolean(DATETIME_CANDIDATES_KEY, false),
                englishAutoSpace = prefs.getBoolean(ENGLISH_AUTO_SPACE_KEY, false),
                englishPunctuationSpace = prefs.getBoolean(ENGLISH_PUNCTUATION_SPACE_KEY, false),
                emailSuffixes = prefs.getBoolean(EMAIL_SUFFIXES_KEY, true),
                addressFieldsEnglish = prefs.getBoolean(ADDRESS_FIELDS_ENGLISH_KEY, true),
                candidateEmoji = prefs.getBoolean(CANDIDATE_EMOJI_KEY, false),
                symbolNames = prefs.getBoolean(SYMBOL_NAMES_KEY, true),
                greekLetters = prefs.getBoolean(GREEK_LETTERS_KEY, false),
                cylmLongAlternative = prefs.getBoolean(CYLM_LONG_ALTERNATIVE_KEY, false),
                fourteenLetterSwipe = prefs.getBoolean("fourteen_letter_swipe", true),
                fourteenLetterSwipeDp = normalizeLetterSwipeDistance(prefs.getFloat("fourteen_letter_swipe_dp", 24f)),
                symbolSwipeDp = normalizeSymbolSwipeDistance(prefs.getFloat(SYMBOL_SWIPE_DP_KEY, DEFAULT_SYMBOL_SWIPE_DP)),
                symbolInputMode = SymbolInputMode.entries.firstOrNull {
                    it.name == prefs.getString("symbol_input_mode", null)
                } ?: SymbolInputMode.SWIPE_UP,
                symbolLongPressSeconds = // 旧默认 0.3 秒迁移为 0.5 秒（用户 11-15）；自己调过的时长保留。
                    normalizeSymbolLongPressSeconds(prefs.getFloat("symbol_long_press_seconds_v2",
                        prefs.getFloat("symbol_long_press_seconds", 0.3f).let { if (it == 0.3f) 0.5f else it })),
                reverseSymbolSwipe = prefs.getBoolean("reverse_symbol_swipe", false),
                clearAllWhileComposing = prefs.getBoolean(CLEAR_ALL_WHILE_COMPOSING_KEY, false),
                clearBeforeCursorOnly = prefs.getBoolean(CLEAR_BEFORE_CURSOR_ONLY_KEY, false),
                swipeDistanceDelete = prefs.getBoolean(SWIPE_DISTANCE_DELETE_KEY, true),
                deleteRepeatSpeed = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.fromName(
                    prefs.getString(com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.KEY, null)),
                reverseDeleteSwipe = prefs.getBoolean(REVERSE_DELETE_SWIPE_KEY, false),
                clearSwipePercent = normalizeClearSwipePercent(prefs.getFloat(CLEAR_SWIPE_PERCENT_KEY, 100f)),
                kanaDirectionHints = prefs.getBoolean("kana_direction_hints", false),
                // 10.11 的默认关已写进旧键；v2 一次性恢复为开，之后用户手动关闭的保留。
                flickDirectionDisc = prefs.getBoolean("flick_direction_disc_v2", true),
                // 滑行输入 10-19 起默认关。旧键会被 save() 连同其他设置一起写成旧默认 true，
                // 分不出是不是用户自己开的，所以只认新键；没有新键就是默认关。
                glideTyping = prefs.getBoolean("glide_typing_v2", false),
                chineseGlideTyping = prefs.getBoolean(CHINESE_GLIDE_KEY, false),
                deleteChineseGlideCharacter = prefs.getBoolean(DELETE_CHINESE_GLIDE_CHARACTER_KEY, false),
                externalGlideEncodings = com.kingzcheung.xime.glide.ExternalGlideEncodings.parse(
                    prefs.getString(com.kingzcheung.xime.glide.ExternalGlideEncodings.KEY, null)),
                glidePreview = prefs.getBoolean(GLIDE_PREVIEW_KEY, false),
                letterLongPressMenu = prefs.getBoolean("letter_long_press_menu", false),
                cornerSymbolInput = prefs.getBoolean(CORNER_SYMBOL_INPUT_KEY, true),
                t9FlickLetters = prefs.getBoolean("t9_flick_letters", false),
                t9GlideTyping = prefs.getBoolean("t9_glide_typing", false),
                t9MergeZeroIntoEnter = prefs.getBoolean(T9_MERGE_ZERO_ENTER_KEY, false),
                strokeMergeZeroIntoEnter = prefs.getBoolean(STROKE_MERGE_ZERO_ENTER_KEY, false),
                t9SpaceSwipeUpZero = prefs.getBoolean(T9_SPACE_UP_ZERO_KEY, false),
                keyTextScale = prefs.getFloat("key_text_scale", DEFAULT_KEY_TEXT_SCALE)
                    .takeIf { it.isFinite() }?.coerceIn(0.8f, 1.6f) ?: DEFAULT_KEY_TEXT_SCALE,
                cornerHintOpacity = normalizeCornerHintOpacity(prefs.getFloat(CORNER_HINT_OPACITY_KEY, 1f)),
                hideFunctionKeyHints = prefs.getBoolean(HIDE_FUNCTION_KEY_HINTS_KEY, false),
                uppercaseKeyLabels = prefs.getBoolean(UPPERCASE_KEY_LABELS_KEY, false),
                chineseUppercaseKeyLabels = prefs.getBoolean(CHINESE_UPPERCASE_KEY_LABELS_KEY, false),
                handwritingPauseSeconds = normalizeHandwritingPause(pause),
                handwritingRecognizeWhileWriting = prefs.getBoolean(HANDWRITING_RECOGNIZE_EARLY_KEY, false),
                formulaDelimiter = FormulaDelimiter.entries.firstOrNull { it.name == prefs.getString("handwriting_formula_delimiter", null) }
                    ?: FormulaDelimiter.DOLLAR,
                fixedSymbols = prefs.getString("fixed_symbols", "").orEmpty(),
            )
        }
    }

    fun save(context: Context) {
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString("space_hold_action", spaceHold.name)
            .putString("cursor_gesture_mode", cursorGesture.name)
            .putFloat("cursor_hold_seconds", normalizeCursorHoldSeconds(cursorHoldSeconds))
            .putFloat("language_key_hold_seconds_v2", normalizeLanguageMenuHoldSeconds(languageMenuHoldSeconds))
            .putFloat("language_key_hold_seconds", normalizeLanguageMenuHoldSeconds(languageMenuHoldSeconds))
            .putBoolean(LANGUAGE_KEY_IME_PICKER_KEY, languageKeyImePicker)
            .putFloat("symbol_key_hold_seconds", normalizeSymbolKeyHoldSeconds(symbolKeyHoldSeconds))
            .putFloat("t9_candidate_drag_percent", normalizeCandidateDragPercent(candidateDragPercent))
            .putBoolean("neighbor_correction", neighborCorrection)
            .putBoolean(com.kingzcheung.xime.settings.SpellingPatches.DOUBLE_PINYIN_CORRECTION_KEY, doublePinyinCorrection)
            .putBoolean(DOUBLE_PINYIN_KEY_HINTS_KEY, doublePinyinKeyHints)
            .putBoolean(DOUBLE_PINYIN_KEY_HINTS_WITH_CORNERS_KEY, doublePinyinKeyHintsWithCorners)
            .putBoolean(DOUBLE_PINYIN_SEMICOLON_ON_SHIFT_KEY, doublePinyinSemicolonOnShift)
            .putBoolean(MIXED_INPUT_KEY, mixedInput)
            .putBoolean(RARE_CHARACTERS_KEY, rareCharacters)
            .putBoolean(JAPANESE_KANJI_KEY, japaneseKanji)
            .putBoolean(KOREAN_HANJA_KEY, koreanHanja)
            .putBoolean(CANTONESE_CHARACTERS_KEY, cantoneseCharacters)
            .putBoolean(TRADITIONAL_OUTPUT_KEY, traditionalOutput)
            .remove(LEGACY_ZHUYIN_SIMPLIFIED_KEY)
            .putBoolean(DATETIME_CANDIDATES_KEY, datetimeCandidates)
            .putBoolean(ENGLISH_AUTO_SPACE_KEY, englishAutoSpace)
            .putBoolean(ENGLISH_PUNCTUATION_SPACE_KEY, englishPunctuationSpace)
            .putBoolean(EMAIL_SUFFIXES_KEY, emailSuffixes)
            .putBoolean(ADDRESS_FIELDS_ENGLISH_KEY, addressFieldsEnglish)
            .putBoolean(CANDIDATE_EMOJI_KEY, candidateEmoji)
            .putBoolean(SYMBOL_NAMES_KEY, symbolNames)
            .putBoolean(GREEK_LETTERS_KEY, greekLetters)
            .putBoolean(CYLM_LONG_ALTERNATIVE_KEY, cylmLongAlternative)
            .putBoolean("fourteen_letter_swipe", fourteenLetterSwipe)
            .putFloat("fourteen_letter_swipe_dp", normalizeLetterSwipeDistance(fourteenLetterSwipeDp))
            .putFloat(SYMBOL_SWIPE_DP_KEY, normalizeSymbolSwipeDistance(symbolSwipeDp))
            .putString("symbol_input_mode", symbolInputMode.name)
            .putFloat("symbol_long_press_seconds_v2", normalizeSymbolLongPressSeconds(symbolLongPressSeconds))
            .putBoolean("reverse_symbol_swipe", reverseSymbolSwipe)
            .putBoolean(CLEAR_ALL_WHILE_COMPOSING_KEY, clearAllWhileComposing)
            .putBoolean(CLEAR_BEFORE_CURSOR_ONLY_KEY, clearBeforeCursorOnly)
            .putBoolean(SWIPE_DISTANCE_DELETE_KEY, swipeDistanceDelete)
            .putString(com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.KEY, deleteRepeatSpeed.name)
            .putBoolean(REVERSE_DELETE_SWIPE_KEY, reverseDeleteSwipe)
            .putFloat(CLEAR_SWIPE_PERCENT_KEY, normalizeClearSwipePercent(clearSwipePercent))
            .putBoolean("kana_direction_hints", kanaDirectionHints)
            .putBoolean("flick_direction_disc_v2", flickDirectionDisc)
            .putBoolean("flick_direction_disc", flickDirectionDisc)
            .putBoolean("glide_typing_v2", glideTyping)
            .putBoolean("letter_long_press_menu", letterLongPressMenu)
            .putBoolean(CORNER_SYMBOL_INPUT_KEY, cornerSymbolInput)
            .putBoolean("glide_typing", glideTyping)
            .putBoolean(CHINESE_GLIDE_KEY, chineseGlideTyping)
            .putBoolean(DELETE_CHINESE_GLIDE_CHARACTER_KEY, deleteChineseGlideCharacter)
            .putString(com.kingzcheung.xime.glide.ExternalGlideEncodings.KEY,
                com.kingzcheung.xime.glide.ExternalGlideEncodings.format(externalGlideEncodings))
            .putBoolean(GLIDE_PREVIEW_KEY, glidePreview)
            .putBoolean("t9_flick_letters", t9FlickLetters)
            .putBoolean("t9_glide_typing", t9GlideTyping)
            .putBoolean(T9_MERGE_ZERO_ENTER_KEY, t9MergeZeroIntoEnter)
            .putBoolean(STROKE_MERGE_ZERO_ENTER_KEY, strokeMergeZeroIntoEnter)
            .putBoolean(T9_SPACE_UP_ZERO_KEY, t9SpaceSwipeUpZero)
            .putFloat("cursor_step_dp", cursorStepDp.coerceIn(6f, 24f))
            .putFloat("cursor_swipe_lines", normalizeCursorSwipeLines(cursorSwipeLines))
            .putBoolean("cursor_step_haptic", cursorStepHaptic)
            .putBoolean("cursor_step_sound", cursorStepSound)
            .putBoolean(BOTTOM_LIFT_CURSOR_KEY, bottomLiftCursor)
            .putBoolean(VOICE_SWIPE_CANCEL_KEY, voiceSwipeCancel)
            .putBoolean(EDGE_GESTURE_EXCLUSION_KEY, edgeGestureExclusion)
            .putFloat("key_text_scale", keyTextScale.coerceIn(0.8f, 1.6f))
            .putFloat(CORNER_HINT_OPACITY_KEY, normalizeCornerHintOpacity(cornerHintOpacity))
            .putBoolean(HIDE_FUNCTION_KEY_HINTS_KEY, hideFunctionKeyHints)
            .putBoolean(UPPERCASE_KEY_LABELS_KEY, uppercaseKeyLabels)
            .putBoolean(CHINESE_UPPERCASE_KEY_LABELS_KEY, chineseUppercaseKeyLabels)
            .putFloat("handwriting_pause_seconds_v2", normalizeHandwritingPause(handwritingPauseSeconds))
            .putFloat("handwriting_pause_seconds", normalizeHandwritingPause(handwritingPauseSeconds))
            .putBoolean(HANDWRITING_RECOGNIZE_EARLY_KEY, handwritingRecognizeWhileWriting)
            .putString("handwriting_formula_delimiter", formulaDelimiter.name)
            .putString("fixed_symbols", fixedSymbols)
            .putString("qwerty_stagger_mode", qwertyStagger.name)
            .putFloat("qwerty_stagger_custom", qwertyStaggerCustom.coerceIn(QWERTY_STAGGER_MIN, QWERTY_STAGGER_MAX))
            .putBoolean("bottom_punctuation_swipe_up_v3", bottomPunctuationSwipeUp)
            .putBoolean(SWAP_MODE_KEYS_KEY, swapModeKeys)
            .putBoolean(com.kingzcheung.xime.service.ENGLISH_SENTENCE_CAPS, englishSentenceCaps)
            .putBoolean("qwerty_comma_key", qwertyCommaKey)
            .putBoolean("qwerty_period_key_v2", qwertyPeriodKey)
            .putBoolean("syllable_split_key", syllableSplitKey)
            .putBoolean("shift_syllable_split", shiftSyllableSplit)
            .putBoolean(SHIFT_TAP_LOCKS_KEY, shiftTapLocks)
            .putString(JAPANESE_LONG_VOWEL_KEY, japaneseLongVowelKey.name)
            .putBoolean("qwerty_period_key", qwertyPeriodKey).apply()
    }

    /** 某个固定栏的自定义内容；该栏未单独设置时沿用旧的共用列表，都没有则为 null（用默认）。 */
    fun symbolsFor(target: SideSymbolTarget): List<String>? =
        SideSymbolTarget.parse(sideSymbols[target.key]) ?: symbols()?.takeIf { target.usesLegacyList }

    /** 数字行上的键，从左到右；没在「按键符号 → 数字行」里改过就是 1-0。 */
    fun numberRowKeys(): List<String> = SideSymbolTarget.NUMBER_ROW.resolve(symbolsFor(SideSymbolTarget.NUMBER_ROW))

    fun symbols(): List<String>? = fixedSymbols.lines().map { it.trim() }
        .filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }
}

data class KeyboardInputActions(
    val englishLetters: Boolean = false,
    val onLetterSwipeAction: ((String) -> Unit)? = null,
    val onCursorMove: ((Int) -> Unit)? = null,
    val onCursorMoveVertical: ((Int) -> Unit)? = null,
    val onCursorModeChange: ((Boolean) -> Unit)? = null,
    val onVoiceModeChange: ((Boolean) -> Unit)? = null,
    val onVoiceToggle: (() -> Unit)? = null,
    /** 按住空格说话松手：还在听写就结束并上屏，没开起来就什么都不做（不能像 [onVoiceToggle] 那样反过来开麦）。 */
    val onVoiceHoldEnd: (() -> Unit)? = null,
    /** 按住空格说话上滑后松手：停麦，这次说的字全部撤掉，什么都不上屏。 */
    val onVoiceHoldCancel: (() -> Unit)? = null,
    val isSttEnabled: Boolean = false,
    val schemas: List<SchemaInfo> = emptyList(),
    val currentInputModeId: String = "",
    val fieldNativeModeId: String? = null,
    val onSwitchSchema: ((String) -> Unit)? = null,
    val onCommitText: ((String) -> Unit)? = null,
    val isVoiceMode: Boolean = false,
    val voiceSticky: Boolean = false,
    val onCommitExactText: ((String) -> Unit)? = null,
)

val LocalKeyboardInputPreferences = staticCompositionLocalOf { KeyboardInputPreferences() }
/** Transient resize material draft; never writes user settings until the resize is confirmed. */
internal val LocalFrostedGlassPreview = compositionLocalOf<com.kingzcheung.xime.settings.FrostedGlassProfiles?> { null }
val LocalKeyboardInputActions = staticCompositionLocalOf { KeyboardInputActions() }

@Composable
fun rememberKeyboardInputPreferences(isDark: Boolean? = null): KeyboardInputPreferences {
    val context = LocalContext.current
    val systemAppearance = LocalConfiguration.current.uiMode
    fun readSettings() = KeyboardInputPreferences.read(context, isDark ?: FrostedGlassPreferences.isDark(context))
    var settings by remember(context, isDark, systemAppearance) { mutableStateOf(readSettings()) }
    DisposableEffect(context, isDark, systemAppearance) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val relevantKeys = KeyboardInputPreferences.watchedKeys
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in relevantKeys) settings = readSettings()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val preview = LocalFrostedGlassPreview.current
    return if (preview != null) {
        val dark = isDark ?: FrostedGlassPreferences.isDark(context)
        settings.copy(frostedGlass = preview.forAppearance(dark)
            .legibleForTheme(SettingsPreferences.getKeyboardTheme(context), dark))
    } else settings
}
