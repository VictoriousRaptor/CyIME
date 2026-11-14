package com.kingzcheung.xime.model

import com.kingzcheung.xime.runtime.adaptation.PerformanceTier
import com.kingzcheung.xime.speech.SpeechModelCatalog
import kotlin.math.roundToInt

/** 语音输入打算怎么用：向导里的三个选项，说法面向不懂模型名的人。 */
enum class VoiceUse { OFTEN, SOMETIMES, NEVER }

/** 打中文的方式。[KEEP] 只在现在用的是别的方式（仓颉、注音、笔画等）时出现，表示不改。 */
enum class TypingWay { FULL_PINYIN, DOUBLE_PINYIN, WUBI, KEEP }

/**
 * 打中文用哪种键盘（用户 10-31：引导里要问 26 键、14 键还是九键）。
 * [KEEP] 只在现在用的是别的键盘（17/18 键、手写、笔画、自己合并的布局等）或不是中文时出现，表示不改。
 */
enum class KeyboardWay(val title: String) { QWERTY("26 键"), K14("14 键"), T9("九键"), KEEP("") }

/**
 * 常见的分不清的读音，一项对应模糊音设置里的一组或几组（[SpellingPatches.Fuzzy] 的 id）。
 * r 和 l 不常见，不在这里问，向导不动它。
 */
enum class FuzzyHabit(val title: String, val example: String, val fuzzyIds: List<String>) {
    RETROFLEX("平翘舌：z 和 zh、c 和 ch、s 和 sh", "zi 也出“知”，si 也出“是”", listOf("z_zh", "c_ch", "s_sh")),
    NL("n 和 l", "nan 也出“蓝”，lan 也出“南”", listOf("n_l")),
    NASAL("前后鼻音：an 和 ang、en 和 eng、in 和 ing", "fan 也出“方”，xin 也出“星”",
        listOf("an_ang", "en_eng", "in_ing", "ian_iang", "uan_uang")),
    FH("f 和 h", "fu 也出“湖”，hu 也出“福”", listOf("f_h")),
}

/** 一种可选的双拼：[example] 是“双拼”两个字的打法，如 ul'pb。 */
data class DoublePinyinChoice(val id: String, val name: String, val example: String, val builtin: Boolean = true,
    /** 只有 26 键（ing 在分号键上的微软双拼）：14 键、九键没有分号键，不生成这两个版本。 */
    val lettersOnly: Boolean = false)

/**
 * 「有内存焦虑」时还能关掉的一项（用户 11-12：选项太少）。[id] 是「性能与省电」代价表的键（PerformanceCosts.Item），
 * 关的时候和设置页写同一个值；[memoryMb] 取同一张表，没有就不写数。只列现在开着的。
 */
data class MemoryExtra(val id: String, val title: String, val loss: String, val memoryMb: Double? = null)

/** 一台设备在向导眼里的样子；大小都是字节，读不到的为 null。 */
data class SetupFacts(
    /** 选模型用的档（6 GB 起算较强）：只决定推荐哪些模型。 */
    val tier: DeviceModelTier,
    /**
     * 给人看的档位，与「性能与省电」的“本机配置推荐”是同一个判断（[com.kingzcheung.xime.runtime.adaptation.PerformanceTiers]），
     * 两页对同一台设备说同一句话。
     */
    val performanceTier: PerformanceTier = PerformanceTier.MEDIUM,
    /** 给人看的设备叫法：「手机」或「平板」。 */
    val device: String = "手机",
    val memoryBytes: Long? = null,
    val cores: Int? = null,
    val maxCpuKHz: Long? = null,
    /** CPU 实测；没测过为 null。 */
    val cpu: com.kingzcheung.xime.runtime.adaptation.CpuMeasurement? = null,
    val soc: String? = null,
    /** 系统语言的名字，只用来显示。 */
    val language: String? = null,
    /** 应用数据所在分区的剩余空间。 */
    val freeBytes: Long? = null,
    val online: Boolean = true,
    /** 移动数据、热点这类按流量计费的网络。 */
    val metered: Boolean = false,
    /** 启用了日语，或系统语言是日语。 */
    val japanese: Boolean = false,
    val sttEnabled: Boolean = true,
    /** 中文语音当前是否边说边出字（流式预览＋校正）。 */
    val streamingVoice: Boolean = false,
    val usesT9: Boolean = false,
    val usesCaption: Boolean = false,
    /** 已经在手机上的模型：id → 实际占用字节。 */
    val installed: Map<String, Long> = emptyMap(),
    /** 安装包里带着、不联网就能取回的模型 id（完整版）。 */
    val inPackage: Set<String> = emptySet(),
    /** 各模型下载（或从安装包取出）后的大小。 */
    val sizes: Map<String, Long> = emptyMap(),
    /** 现在打中文的方式；九键拼音算全拼，九键、14 键双拼算双拼。 */
    val typing: TypingWay = TypingWay.FULL_PINYIN,
    /** [typing] 是 [TypingWay.KEEP] 时现在那种方式的名字（如“仓颉”）。 */
    val typingName: String = "",
    /** 现在用的双拼（26 键那个方案的 id）；没在用双拼时为 null。 */
    val doublePinyin: String? = null,
    /**
     * 现在打中文的键盘：中文 26 键（含 QWJRTK 等字母排列）、14 键、九键；别的键盘或现在不是中文为 [KeyboardWay.KEEP]。
     * 回答和这里一样就不切方案，自己的字母排列、自定义布局都不会被换掉。
     */
    val keyboard: KeyboardWay = KeyboardWay.QWERTY,
    /** [keyboard] 是 [KeyboardWay.KEEP] 时现在那种键盘的名字（如“17键”“手写”）。 */
    val keyboardName: String = "",
    /** 方案栏里显示着的内置双拼（26 键）。 */
    val shownDoublePinyin: Set<String> = emptySet(),
    /** 能选的双拼：内置的在前，自己建的在后。 */
    val doublePinyinChoices: List<DoublePinyinChoice> = emptyList(),
    /** 五笔86 已经装在手机上。 */
    val wubiInstalled: Boolean = false,
    /** 五笔98 已经装在手机上。 */
    val wubi98Installed: Boolean = false,
    /** 「双拼键位提示」已经开着。 */
    val doublePinyinKeyHints: Boolean = false,
    /** 当前角标设置允许额外显示双拼键位提示。 */
    val canEnableDoublePinyinKeyHints: Boolean = false,
    /** 已打开的模糊音（[SpellingPatches.Fuzzy] 的 id）。 */
    val fuzzy: Set<String> = emptySet(),
    /** 能不能替人加、去桌面「字幕」图标；老人包固定有图标。 */
    val captionIconChangeable: Boolean = true,
    /** 系统现在可用的内存，只用来显示。 */
    val availMemoryBytes: Long? = null,
    /** 上次在引导里答了“有内存焦虑”；没答过为 false，所以默认推荐不变。 */
    val memorySaver: Boolean = false,
    /** 现在开着、关掉能省内存的功能（[SetupGuidePlanner.memoryExtras] 里列的）。 */
    val memoryExtras: List<MemoryExtra> = emptyList(),
)

data class SetupAnswers(
    val voice: VoiceUse,
    val t9: Boolean,
    val caption: Boolean,
    val typing: TypingWay = TypingWay.FULL_PINYIN,
    /** 选双拼时用哪一种。 */
    val doublePinyin: String = FLYPY,
    /** 全拼时分不清的读音（打开模糊音）。 */
    val fuzzy: Set<FuzzyHabit> = emptySet(),
    /** 打中文用哪种键盘。 */
    val keyboard: KeyboardWay = KeyboardWay.QWERTY,
    /** 有内存焦虑（用户 11-04）：只留必需的模型、九键用精简版、语音空闲很快卸掉。 */
    val memorySaver: Boolean = false,
    /** 选五笔时用 86 还是 98（[SetupGuidePlanner.WUBI]、[SetupGuidePlanner.WUBI98]）；默认 86。 */
    val wubi: String = SetupGuidePlanner.WUBI,
    /** 有内存焦虑时另外选了要关掉的功能（[MemoryExtra.id]）；默认一个不选，点了才关。 */
    val memoryOff: Set<String> = emptySet(),
) {
    companion object { const val FLYPY = "double_pinyin_flypy" }
}

/**
 * 打字方式这一块要改什么。全是“和现在不一样才改”：回答没变的项不动，不会把用户细调过的设置冲掉。
 * [lines] 是给人看的每一项改动，[hideLine] 是“方案栏里隐藏用不到的双拼”那一行（页面上单独一个开关）。
 */
data class InputPlan(
    /** 设成当前方案；null 不切。 */
    val switchTo: String? = null,
    /** 要在方案栏打开的布局（中文14键、九键、某种双拼的九键版本）；已经开着的不动。双拼本身由 [showDoublePinyin] 打开。 */
    val enable: List<String> = emptyList(),
    /** 要在方案栏打开的双拼。 */
    val showDoublePinyin: String? = null,
    /** 可以从方案栏隐藏的内置双拼。 */
    val hideDoublePinyin: List<String> = emptyList(),
    /** 打字方式选了五笔：没装就下载，没在方案栏就打开。 */
    val useWubi: Boolean = false,
    val downloadWubi: Boolean = false,
    /** 用哪一份五笔：86 或 98。 */
    val wubiId: String = SetupGuidePlanner.WUBI,
    val fuzzyOn: Set<String> = emptySet(),
    val fuzzyOff: Set<String> = emptySet(),
    /** 从别的打法改成双拼时可打开键位提示，前提是不会挤掉现有角标。 */
    val keyHintsOn: Boolean = false,
    /** 加（true）或去掉（false）桌面「字幕」图标；null 不动。 */
    val captionIcon: Boolean? = null,
    val lines: List<String> = emptyList(),
    val hideLine: String? = null,
)

enum class SetupSource { ON_PHONE, IN_PACKAGE, DOWNLOAD }

/** 方案里的一项：[title]、[reason] 是给人看的白话，[modelName] 是设置页里同一个模型的名字。 */
data class SetupItem(
    val id: String,
    val title: String,
    val reason: String,
    val bytes: Long,
    val source: SetupSource,
    val modelName: String = "",
)

data class SetupPlan(
    val answers: SetupAnswers,
    val sttEnabled: Boolean,
    /** 中文、英文语音要用的模式；null 表示不改。 */
    val voiceMode: String?,
    /** 九键词组模型用哪一档；null 表示不用。 */
    val t9Model: String?,
    val keep: List<SetupItem>,
    /** 建议删掉的：已经在手机上的，或安装包里带着、还没解压出来的。 */
    val remove: List<SetupItem>,
    val notes: List<String>,
    val input: InputPlan = InputPlan(),
    /** 语音模型空闲多少分钟后全部卸掉（二次校正同时）；null 不改。 */
    val voiceIdleMinutes: Int? = null,
    /** 有内存焦虑时要关掉的功能；没答“有”时为空。 */
    val turnOff: List<MemoryExtra> = emptyList(),
) {
    val downloadBytes: Long get() = keep.filter { it.source == SetupSource.DOWNLOAD }.sumOf { it.bytes }
    val freedBytes: Long get() = remove.filter { it.source == SetupSource.ON_PHONE }.sumOf { it.bytes }
}

/**
 * 按设备和用法推荐要留哪些模型。只用到事实（内存、核心数、主频、剩余空间、网络、语言、
 * 已装的模型），不跑测速；数字依据见各条注释。纯逻辑，便于单元测试。
 */
object SetupGuidePlanner {
    const val HIDE_OTHER_DOUBLE_PINYIN_BY_DEFAULT = false
    const val GIB = 1024L * 1024 * 1024
    const val HANDWRITING = "ochwpro"

    /** 剩余空间少于这些时改选更省空间的：Android 剩余空间太少时系统本身会变慢、更新也装不上。 */
    const val TIGHT_FREE = 3 * GIB
    const val VERY_TIGHT_FREE = 1 * GIB
    /** 下载后至少还要剩这么多。 */
    const val RESERVE_AFTER_DOWNLOAD = 1536L * 1024 * 1024

    private val voiceIds = listOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.XASR,
        SpeechModelCatalog.ZIPFORMER, SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA)
    private val t9Ids = listOf(CandidateModelCatalog.NGRAM, CandidateModelCatalog.NGRAM_SMALL, CandidateModelCatalog.GRAMMAR)

    /** 向导会管的全部模型；其他（公式手写、释义词典等）保持原样。 */
    val managedIds: List<String> = voiceIds + BuiltinModelCatalog.SPEAKER_MODEL + t9Ids + HANDWRITING

    /** 白话名字。 */
    fun title(id: String): String = when (id) {
        SpeechModelCatalog.SENSEVOICE -> "语音识别：说完一句出字"
        SpeechModelCatalog.PARAFORMER -> "语音识别：边说边出字"
        SpeechModelCatalog.XASR -> "整句精修(中文)"
        SpeechModelCatalog.ZIPFORMER -> "另一个边说边出字的中文模型"
        SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA -> "日语专用语音模型"
        BuiltinModelCatalog.SPEAKER_MODEL -> "字幕：分清谁在说话"
        CandidateModelCatalog.NGRAM -> "九键整句组词(完整)"
        CandidateModelCatalog.NGRAM_SMALL -> "九键整句组词(精简)"
        CandidateModelCatalog.GRAMMAR -> "九键语法增强(旧)"
        HANDWRITING -> "手写识别"
        else -> id
    }

    fun suggest(facts: SetupFacts): SetupAnswers = SetupAnswers(
        voice = when {
            !facts.sttEnabled -> VoiceUse.NEVER
            facts.streamingVoice && facts.tier != DeviceModelTier.LIGHT -> VoiceUse.OFTEN
            else -> VoiceUse.SOMETIMES
        },
        t9 = facts.usesT9,
        caption = facts.usesCaption,
        typing = facts.typing,
        keyboard = facts.keyboard,
        doublePinyin = facts.doublePinyin ?: facts.doublePinyinChoices.firstOrNull()?.id ?: SetupAnswers.FLYPY,
        fuzzy = FuzzyHabit.entries.filterTo(linkedSetOf()) { habit -> habit.fuzzyIds.any { it in facts.fuzzy } },
        memorySaver = facts.memorySaver,
    )

    /** 有内存焦虑时语音模型空闲多久卸掉：和低配档一样 1 分钟。 */
    const val SAVER_VOICE_IDLE_MINUTES = 1

    /**
     * 有内存焦虑时还能关的功能，按省得多少排（数取「性能与省电」代价表，作者平板，大多是估算）：
     * id 是 PerformanceCosts.Item 的 id，后面是给人看的名字和关掉后少了什么。
     * 不列智能联想：关了就没有联想词，影响打字；表里 60 MB 是联想表压缩（11-10，电脑上 38.6 → 6.9 MB）以前的估算。
     */
    val memoryExtras: List<Triple<String, String, String>> = listOf(
        Triple("glass", "玻璃效果", "键盘不再半透明"),
        Triple("glide_typing", "英文滑行", "英文只能一个键一个键点"),
        Triple("rare_characters", "生僻字", "候选里不再出生僻字"),
        Triple("chinese_glide", "中文滑行", "中文只能一个键一个键点"),
        Triple("t9_glide_typing", "九键滑行", "九键只能一个键一个键点"),
        Triple("traditional_output", "繁体输出", "打出来的是简体字"),
        Triple("candidate_emoji", "候选里显示表情", "候选里不再夹着表情，表情面板照常用"),
        Triple("key_glow", "键内炫光", "按键时键里不再亮一下"),
        Triple("handwriting_recognize_while_writing", "手写边写边识别", "写完停一下才出字"),
        Triple("press_bubble", "点按弹出气泡", "点字母键时上方不再弹出放大的字母"),
        Triple("background_motion", "动态背景", "图片背景不再缓慢平移、缩放"),
    )

    /** 五笔86 的方案 id（「扩展 › 方案」里审过的那份，约 2 MB）。 */
    const val WUBI = "wubi86"
    /** 五笔98（「扩展 › 方案」里的那份，约 1 MB）。用户 11-12：五笔默认 86，也能选 98。 */
    const val WUBI98 = "wubi98"
    val wubiIds = listOf(WUBI, WUBI98)
    fun wubiName(id: String) = if (id == WUBI98) "五笔98" else "五笔86"
    const val FULL_PINYIN = "rime_ice"
    const val K14 = "pinyin_14jian"
    const val T9 = "t9_pinyin"
    /** 与 DoublePinyinScheme 的 14 键、九键版本后缀相同（这里是纯逻辑，不引用设置层）。 */
    private const val K14_SUFFIX = "_14jian"
    private const val T9_SUFFIX = "_t9"

    /** 这种打法能用的键盘：全拼、双拼有 26 键、14 键、九键（微软双拼只有 26 键）；五笔只问打法，不问键盘。 */
    fun keyboards(facts: SetupFacts, answers: SetupAnswers): List<KeyboardWay> {
        val choices = when (answers.typing) {
            TypingWay.FULL_PINYIN -> listOf(KeyboardWay.QWERTY, KeyboardWay.K14, KeyboardWay.T9)
            TypingWay.DOUBLE_PINYIN -> if (facts.doublePinyinChoices.firstOrNull { it.id == answers.doublePinyin }?.lettersOnly == true)
                listOf(KeyboardWay.QWERTY) else listOf(KeyboardWay.QWERTY, KeyboardWay.K14, KeyboardWay.T9)
            TypingWay.WUBI, TypingWay.KEEP -> emptyList()
        }
        return if (choices.isNotEmpty() && facts.keyboard == KeyboardWay.KEEP) choices + KeyboardWay.KEEP else choices
    }

    /** 实际按哪种键盘设：选的键盘这种打法没有（如微软双拼选了九键）时用 26 键。 */
    fun keyboardOf(facts: SetupFacts, answers: SetupAnswers): KeyboardWay =
        keyboards(facts, answers).let { if (answers.keyboard in it) answers.keyboard else it.firstOrNull() ?: KeyboardWay.KEEP }

    /** 打法 × 键盘对应的方案；[KeyboardWay.KEEP] 不切。双拼的 14 键版本是组合配置，双拼和中文14键都开着时自动有。 */
    internal fun schemaFor(typing: TypingWay, doublePinyin: String, keyboard: KeyboardWay): String? = when (typing) {
        TypingWay.FULL_PINYIN -> when (keyboard) {
            KeyboardWay.QWERTY -> FULL_PINYIN
            KeyboardWay.K14 -> K14
            KeyboardWay.T9 -> T9
            KeyboardWay.KEEP -> null
        }
        TypingWay.DOUBLE_PINYIN -> when (keyboard) {
            KeyboardWay.QWERTY -> doublePinyin
            KeyboardWay.K14 -> doublePinyin + K14_SUFFIX
            KeyboardWay.T9 -> doublePinyin + T9_SUFFIX
            KeyboardWay.KEEP -> null
        }
        TypingWay.WUBI -> WUBI
        TypingWay.KEEP -> null
    }

    fun inputPlan(facts: SetupFacts, answers: SetupAnswers): InputPlan {
        val lines = mutableListOf<String>()
        val typing = answers.typing
        val choice = facts.doublePinyinChoices.firstOrNull { it.id == answers.doublePinyin }
        val keyboard = keyboardOf(facts, answers)
        val wubiId = answers.wubi.takeIf { it in wubiIds } ?: WUBI
        val name = when (typing) {
            TypingWay.FULL_PINYIN -> "全拼"
            TypingWay.DOUBLE_PINYIN -> choice?.name ?: "双拼"
            TypingWay.WUBI -> wubiName(wubiId)
            TypingWay.KEEP -> facts.typingName
        }
        // 打法、哪一种双拼、键盘都和现在一样就不切：自己的字母排列（QWJRTK 等）、自定义布局不会被换掉。
        val sameWay = typing == facts.typing && (typing != TypingWay.DOUBLE_PINYIN || answers.doublePinyin == facts.doublePinyin)
        val changed = typing != TypingWay.KEEP && (!sameWay ||
            (typing != TypingWay.WUBI && keyboard != KeyboardWay.KEEP && keyboard != facts.keyboard))
        // 已经显示着时再打开一次什么也不改（DoublePinyinSchemes.setEnabled 内容没变不写）。
        val showDoublePinyin = answers.doublePinyin.takeIf { typing == TypingWay.DOUBLE_PINYIN }
        // 已经在用五笔（86、98 或五笔拼音）就不动；从别的方式改成五笔才装、才打开选的那份（默认 86）。
        val useWubi = typing == TypingWay.WUBI && facts.typing != TypingWay.WUBI
        val downloadWubi = useWubi && !(if (wubiId == WUBI98) facts.wubi98Installed else facts.wubiInstalled)
        if (downloadWubi) lines += "下载${name}方案(约 ${if (wubiId == WUBI98) 1 else 2} MB，要联网)，装好后在方案栏里打开。"
        // 五笔只有 26 键：在 26 键上才替人切过去，九键、14 键、手写不动。全拼、双拼按回答的键盘切。
        val switchTo = if (!changed) null
            else if (typing == TypingWay.WUBI) wubiId.takeIf { facts.keyboard == KeyboardWay.QWERTY }
            else schemaFor(typing, answers.doublePinyin, keyboard)
        // 要用的布局不在方案栏里就打开：中文14键（双拼的 14 键版本也靠它）、九键、双拼的九键版本。
        val enable = buildList {
            if (switchTo == null) return@buildList
            if (keyboard == KeyboardWay.K14) add(K14)
            if (switchTo != showDoublePinyin && switchTo !in wubiIds && !switchTo.endsWith(K14_SUFFIX) && switchTo !in this) add(switchTo)
        }
        when {
            switchTo != null && keyboard == KeyboardWay.QWERTY || switchTo in wubiIds -> lines += "中文 26 键改用$name。"
            switchTo != null -> lines += "中文换成${if (keyboard == KeyboardWay.K14) " " else ""}${keyboard.title}，用${name}打。"
            // 全拼（雾凇）一直在方案栏里，不切时没有要改的。
            changed && typing != TypingWay.FULL_PINYIN -> lines += "方案栏里打开$name，在键盘上切到中文 26 键就能用；现在的键盘不变。"
            !changed && typing != TypingWay.KEEP -> lines += "打字方式还是$name，不变。"
        }
        // 方案栏只留用得到的：内置双拼里除了选中的那种都可以藏起来，自己建的双拼不动。
        val hide = if (typing == TypingWay.KEEP) emptyList()
            else facts.shownDoublePinyin.filter { it != showDoublePinyin }
                .sortedBy { id -> facts.doublePinyinChoices.indexOfFirst { it.id == id } }
        val hideLine = if (hide.isEmpty()) null else {
            val names = hide.map { id -> facts.doublePinyinChoices.firstOrNull { it.id == id }?.name ?: id }
            "方案栏里藏起用不到的 ${hide.size} 种双拼：${names.joinToString("、")}。以后想用，在 设置 → 双拼 里再打开。"
        }

        // 只改回答和现在不一样的那几组读音。
        val fuzzyOn = mutableSetOf<String>()
        val fuzzyOff = mutableSetOf<String>()
        if (typing == TypingWay.FULL_PINYIN) for (habit in FuzzyHabit.entries) {
            val now = habit.fuzzyIds.any { it in facts.fuzzy }
            val wanted = habit in answers.fuzzy
            if (wanted && !now) { fuzzyOn += habit.fuzzyIds; lines += "打开模糊音：${habit.title}。" }
            if (!wanted && now) { fuzzyOff += habit.fuzzyIds.filter { it in facts.fuzzy }; lines += "关掉模糊音：${habit.title}。" }
        }

        // 刚改用双拼的人还没记熟键位：打开双拼键位提示（用户 10-31）。本来就用双拼的、已经开着的不动。
        val keyHintsOn = typing == TypingWay.DOUBLE_PINYIN && facts.typing != TypingWay.DOUBLE_PINYIN &&
            !facts.doublePinyinKeyHints && facts.canEnableDoublePinyinKeyHints
        if (keyHintsOn) lines += "打开双拼键位提示，双拼 26 键的键面会标出 zh、ch、sh 和韵母。"

        val captionIcon = answers.caption.takeIf { facts.captionIconChangeable && it != facts.usesCaption }
        when (captionIcon) {
            true -> lines += "装上「字幕」插件，桌面上加一个「字幕」图标(系统会问一次要不要添加)。"
            false -> lines += "桌面上的「字幕」图标关掉(变灰后可长按移除)。"
            null -> {}
        }
        return InputPlan(switchTo, enable, showDoublePinyin, hide, useWubi, downloadWubi, wubiId, fuzzyOn, fuzzyOff,
            keyHintsOn, captionIcon, lines, hideLine)
    }

    fun plan(facts: SetupFacts, answers: SetupAnswers): SetupPlan {
        val free = facts.freeBytes
        val tight = free != null && free < TIGHT_FREE
        val veryTight = free != null && free < VERY_TIGHT_FREE
        val light = facts.tier == DeviceModelTier.LIGHT
        val saver = answers.memorySaver
        val turnOff = if (!saver) emptyList() else facts.memoryExtras.filter { it.id in answers.memoryOff }
        val notes = mutableListOf<String>()
        fun available(id: String) = id in facts.installed || id in facts.inPackage

        // 语音：说完出字（SenseVoice，错字约为单独边说边出字的一半、带标点）是默认；
        // 边说边出字要再加 226 MB，并且说话时两个模型一起跑，入门机型和空间紧张时不推荐。
        val voiceNeeded = answers.voice != VoiceUse.NEVER || answers.caption
        // 字幕只用说完出字时一整句一下子冒出来，看的人跟不上，所以用字幕也配边说边出字。
        var streaming = voiceNeeded && !light && !tight && !saver && (answers.voice == VoiceUse.OFTEN || answers.caption)
        // 整句精修（X-ASR，136 MB）：用语音就推荐。同一批录音错字 8.5% → 5.5%，比 SenseVoice 定稿还快，
        // 只用 SenseVoice 时也由它定稿、不再加载 SenseVoice（2026-11-01 复测，用户「就按你说的」）。
        // 空间紧张时不新下；已经下好的留着，空间极紧才删。
        // 有内存焦虑：已经下好的也删，以后在语音设置里还能下。
        var xasr = answers.voice != VoiceUse.NEVER && !saver &&
            (!tight || SpeechModelCatalog.XASR in facts.installed && !veryTight)
        if (answers.voice == VoiceUse.OFTEN && !streaming) notes += when {
            light -> "这台${facts.device}${memoryText(facts.memoryBytes)}，边说边出字要同时跑两个语音模型，容易卡，先用「说完一句出字」。"
            saver && !tight -> "选了能省就省，先用「说完一句出字」，少占 226 MB。"
            else -> "${facts.device}剩余空间不到 3 GB，先用「说完一句出字」，少占 226 MB。"
        }

        // 九键词组：完整 203 MB，精简 105 MB（首选约低 3 个百分点）。已经有哪一档就留哪一档。
        // 全拼选了九键就是用九键，“用九键打字吗”那题不再问。
        val usesT9 = answers.t9 || answers.typing == TypingWay.FULL_PINYIN && keyboardOf(facts, answers) == KeyboardWay.T9
        // 有内存焦虑用精简版（小一半）；只有完整版在、又没联网下不了精简版时先留完整版，不让九键没模型可用。
        val smallReachable = available(CandidateModelCatalog.NGRAM_SMALL) || facts.online
        var t9Model = if (!usesT9) null else when {
            saver && (smallReachable || !available(CandidateModelCatalog.NGRAM)) -> CandidateModelCatalog.NGRAM_SMALL
            CandidateModelCatalog.NGRAM in facts.installed && !veryTight -> CandidateModelCatalog.NGRAM
            CandidateModelCatalog.NGRAM_SMALL in facts.installed -> CandidateModelCatalog.NGRAM_SMALL
            CandidateModelCatalog.NGRAM in facts.inPackage && !veryTight -> CandidateModelCatalog.NGRAM
            light || tight -> CandidateModelCatalog.NGRAM_SMALL
            else -> CandidateModelCatalog.NGRAM
        }

        fun needed(): List<String> = buildList {
            add(HANDWRITING)
            if (voiceNeeded) add(SpeechModelCatalog.SENSEVOICE)
            if (streaming) add(SpeechModelCatalog.PARAFORMER)
            if (xasr) add(SpeechModelCatalog.XASR)
            if (voiceNeeded && facts.japanese) {
                // 日语已经单独选了模型就留着；SenseVoice 本身也能听日语。
                listOf(SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA)
                    .filter { it in facts.installed }.forEach(::add)
            }
            if (answers.caption) add(BuiltinModelCatalog.SPEAKER_MODEL)
            t9Model?.let(::add)
        }
        fun downloadBytes() = needed().filterNot(::available).sumOf { facts.sizes[it] ?: 0L }

        // 要下载的放不下：按“少了影响最小”的顺序换成更省空间的。
        if (free != null) {
            val steps = listOf(
                { if (xasr) { xasr = false; true } else false },
                { if (streaming && !available(SpeechModelCatalog.PARAFORMER)) { streaming = false; true } else false },
                { if (t9Model == CandidateModelCatalog.NGRAM && !available(CandidateModelCatalog.NGRAM)) {
                    t9Model = CandidateModelCatalog.NGRAM_SMALL; true } else false },
            )
            var downgraded = false
            for (step in steps) {
                if (free - downloadBytes() >= RESERVE_AFTER_DOWNLOAD) break
                if (step()) downgraded = true
            }
            if (downgraded) notes += "${facts.device}剩余空间只有 ${gbText(free)}，选了更省空间的搭配。"
        }

        val keepIds = needed()
        val keep = keepIds.map { id ->
            val source = when {
                id in facts.installed -> SetupSource.ON_PHONE
                id in facts.inPackage -> SetupSource.IN_PACKAGE
                else -> SetupSource.DOWNLOAD
            }
            SetupItem(id, title(id), keepReason(id, facts, answers, streaming), facts.installed[id] ?: facts.sizes[id] ?: 0L,
                source, modelName(id))
        }
        val remove = managedIds.filter { it !in keepIds && available(it) }.map { id ->
            val onPhone = id in facts.installed
            SetupItem(id, title(id), saverReason(id, answers, voiceNeeded, t9Model) ?: removeReason(id, facts, answers, streaming, t9Model),
                if (onPhone) facts.installed.getValue(id) else facts.sizes[id] ?: 0L,
                if (onPhone) SetupSource.ON_PHONE else SetupSource.IN_PACKAGE, modelName(id))
        }

        val download = keep.filter { it.source == SetupSource.DOWNLOAD }.sumOf { it.bytes }
        if (download > 0) when {
            !facts.online -> notes += "现在没联网，要下载的 ${mbText(download)} 连上网后自动下载。"
            facts.metered -> notes += "现在用的是移动数据，要下载的 ${mbText(download)} 会等连上 Wi-Fi 再下。"
        }
        if (facts.inPackage.isNotEmpty() && remove.isNotEmpty()) {
            notes += "安装包本身占的空间由系统管理，单独删不掉；这里删的是解压到${facts.device}里的那一份。以后要用，直接从安装包取回，不用联网。"
        }
        val voiceMode = when {
            answers.voice == VoiceUse.NEVER -> null
            streaming -> SpeechModelCatalog.TWO_PASS
            else -> SpeechModelCatalog.SENSEVOICE
        }
        // 有内存焦虑：语音空闲 1 分钟就从内存卸掉（性能与省电里同一项）。不用语音、也不用字幕时没有模型可卸，不改。
        val voiceIdle = SAVER_VOICE_IDLE_MINUTES.takeIf { saver && voiceNeeded }
        if (voiceIdle != null) notes += "语音空闲 $voiceIdle 分钟后从内存卸掉，之后第一次说话要多等一两秒。在 性能与省电 里可以改。"
        if (turnOff.isNotEmpty()) notes += "关掉${turnOff.joinToString("、") { it.title }}" +
            (turnOff.mapNotNull { it.memoryMb }.sum().takeIf { it >= 1 }?.let { "，按「性能与省电」里的表约省 ${it.roundToInt()} MB 内存" } ?: "") +
            "。以后想开，在设置里各自的开关打开。"
        return SetupPlan(answers, answers.voice != VoiceUse.NEVER, voiceMode, t9Model, keep, remove, notes,
            inputPlan(facts, answers), voiceIdle, turnOff)
    }

    private fun keepReason(id: String, facts: SetupFacts, answers: SetupAnswers, streaming: Boolean): String = when (id) {
        SpeechModelCatalog.SENSEVOICE -> when {
            answers.voice == VoiceUse.NEVER -> "字幕要用它听别人说话。"
            streaming -> "你停下来后，用它把刚才那句再认一遍，改掉错字、加上标点。"
            else -> "说完一句再出字，错字少，自动加标点；中文、英语、日语、粤语都能听。"
        }
        SpeechModelCatalog.PARAFORMER -> if (answers.voice == VoiceUse.OFTEN) "说话时字就跟着出来，不用等说完。"
            else "字幕边听边出字，看的人跟得上。"
        SpeechModelCatalog.XASR -> "说完后把整句中文再认一遍，错字比只用上面的少三分之一，也更快。"
        SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA -> "你已经给日语选了它，保留。"
        BuiltinModelCatalog.SPEAKER_MODEL -> "字幕里分清是谁在说话。"
        CandidateModelCatalog.NGRAM -> "九键按整句常用说法组词，长句一次打对的更多。"
        CandidateModelCatalog.NGRAM_SMALL -> "九键按整句组词；比完整版小一半，首选略差一点。"
        HANDWRITING -> "手写输入要用，只有 7 MB。"
        else -> ""
    }

    /** 是因为选了“能省就省”才删的，说这个原因；不是就 null，用原来的说法。 */
    private fun saverReason(id: String, answers: SetupAnswers, voiceNeeded: Boolean, t9Model: String?): String? {
        if (!answers.memorySaver) return null
        return when (id) {
            SpeechModelCatalog.PARAFORMER -> "能省就省：先用「说完一句出字」。以后在语音设置里可以再下。".takeIf { voiceNeeded }
            SpeechModelCatalog.XASR -> "能省就省：先不留整句精修(136 MB)。以后在语音设置里可以再下。"
                .takeIf { answers.voice != VoiceUse.NEVER }
            CandidateModelCatalog.NGRAM -> "能省就省：九键改用精简版，小一半。".takeIf { t9Model == CandidateModelCatalog.NGRAM_SMALL }
            else -> null
        }
    }

    private fun removeReason(id: String, facts: SetupFacts, answers: SetupAnswers, streaming: Boolean,
        t9Model: String?): String = when (id) {
        SpeechModelCatalog.SENSEVOICE -> "不用语音，也不用字幕。"
        SpeechModelCatalog.PARAFORMER -> if (answers.voice == VoiceUse.NEVER && !answers.caption) "不用语音，也不用字幕。"
            else "用「说完一句出字」就够了，用不到它。"
        SpeechModelCatalog.XASR -> when {
            answers.voice == VoiceUse.NEVER && !answers.caption -> "不用语音，也不用字幕。"
            answers.voice == VoiceUse.NEVER -> "只用字幕，用不到它。"
            else -> "剩余空间不多，先不多下 136 MB；空间够了再到语音设置里下载，中文整句更准。"
        }
        SpeechModelCatalog.ZIPFORMER -> if (answers.voice == VoiceUse.NEVER && !answers.caption) "不用语音，也不用字幕。"
            else "和「边说边出字」作用重复。"
        SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA -> when {
            answers.voice == VoiceUse.NEVER && !answers.caption -> "不用语音，也不用字幕。"
            facts.japanese -> "日语用「说完一句出字」那个模型就能听。"
            else -> "没有用日语。"
        }
        BuiltinModelCatalog.SPEAKER_MODEL -> "不用字幕。"
        CandidateModelCatalog.NGRAM, CandidateModelCatalog.NGRAM_SMALL ->
            if (t9Model == null) "不用九键。" else "九键只用一档，两档二选一。"
        CandidateModelCatalog.GRAMMAR -> if (t9Model == null) "不用九键。" else "有了九键整句组词，它就不再起作用。"
        else -> ""
    }

    /** 设置页、模型管理里同一个模型的名字，方便对照。 */
    private fun modelName(id: String): String = when (id) {
        SpeechModelCatalog.SENSEVOICE -> "SenseVoice"
        SpeechModelCatalog.PARAFORMER -> "Paraformer"
        SpeechModelCatalog.XASR -> "X-ASR"
        SpeechModelCatalog.ZIPFORMER -> "Zipformer · 中文"
        SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "Zipformer · 日语轻量"
        SpeechModelCatalog.ZIPFORMER_JA -> "Zipformer · 日语原版"
        BuiltinModelCatalog.SPEAKER_MODEL -> "声纹模型"
        CandidateModelCatalog.NGRAM -> "九键词组模型(完整)"
        CandidateModelCatalog.NGRAM_SMALL -> "九键词组模型(精简)"
        CandidateModelCatalog.GRAMMAR -> "九键语法增强"
        HANDWRITING -> "手写模型"
        else -> ""
    }

    fun mbText(bytes: Long): String = "${(bytes + 524_288) / 1_048_576} MB"

    /** 模型属于哪一类，完成页说「语音、手写模型正在下载」用。 */
    fun kind(id: String): String = when (id) {
        SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.XASR, SpeechModelCatalog.ZIPFORMER,
        SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA -> "语音"
        BuiltinModelCatalog.SPEAKER_MODEL -> "字幕"
        CandidateModelCatalog.NGRAM, CandidateModelCatalog.NGRAM_SMALL, CandidateModelCatalog.GRAMMAR -> "九键组词"
        HANDWRITING -> "手写"
        else -> ""
    }

    /** 「语音、手写模型」；认不出类别时就叫「模型」。 */
    fun kindsText(ids: List<String>): String = ids.map(::kind).filter { it.isNotEmpty() }.distinct().joinToString("、") + "模型"

    /**
     * 推荐页底部那句占多大：分开写现在占多少、要下载／取出／删掉多少、设好后共占多少。
     * 不写成「现在占 7 MB，整理后 270 MB」：那样读起来像整理反而变大了（10-31 走查），其实是要下载语音等模型。
     */
    fun sizeSummary(now: Long, download: Long, unpack: Long, removed: Long): String {
        val steps = buildList {
            if (download > 0) add("下载 ${mbText(download)}")
            if (unpack > 0) add("从安装包取出 ${mbText(unpack)}")
            if (removed > 0) add("删掉 ${mbText(removed)}")
        }
        if (steps.isEmpty()) return "模型现在占 ${mbText(now)}，不用下载，也不用删。"
        val after = now - removed + download + unpack
        return "模型现在占 ${mbText(now)}。按推荐要${steps.joinToString("、")}，设好后共占 ${mbText(after)}" +
            (if (after < now) "，比现在少 ${mbText(now - after)}。" else "。")
    }
    fun gbText(bytes: Long): String = "%.1f GB".format(bytes.toDouble() / GIB)
    private fun memoryText(bytes: Long?): String = if (bytes == null) "内存读不到" else "内存 ${(bytes + GIB / 2) / GIB} GB"
}
