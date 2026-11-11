package com.kingzcheung.xime.settings

import android.content.Context
import android.os.Build
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

/**
 * 耗性能的开关各自的实测代价，以及当前开着的这些加起来的总代价（用户 11-02：每个开关独立标一个代价，最后写总代价）。
 *
 * 实测：`tools/ime_lab/perf_cost_bench.py` 在设备上一次只开一个开关，用同一段拼音按固定节奏打，
 * 记每键从触摸到候选画完的中位耗时和输入法进程内存，减去全关时的基线。结果两处来源：
 * - 本机实测：脚本推到 `Android/data/<包名>/files/perf-costs.json`，只有本机读；
 * - 随安装包的表：`assets/perf_costs.json`，作者平板的数，其他机型同档位直接参考，不同档位按 [TIER_SCALE] 折算，并写明是哪台机器测的。
 * 用户 11-03：每个开关装好就要有代价，不能等本机测。表里还没实测的项写估算（[Cost.estimated]，按做的事和相近已测项估），
 * 显示时标“估算”；本机实测缺的项用安装包表补上，也标“估算”。所以登记过的开关都有一行，不会空着。
 *
 * 总代价不是简单相加（几项抢同一个线程、同一块内存）：脚本还实测几组组合，按最接近当前组合的那组校准；
 * 当前组合正好实测过就直接用实测值。
 */
object PerformanceCosts {

    /**
     * 登记了代价的开关。[id] 是表里的键，也是测量脚本切换开关用的名字，改了要同步改表和脚本。
     * 默认值不在这里定，读写都走原来的设置。
     */
    enum class Item(val id: String, val title: String, val redeploy: Boolean = false) {
        NEIGHBOR_CORRECTION("neighbor_correction", "邻键纠错"),
        DOUBLE_PINYIN_CORRECTION("double_pinyin_correction", "双拼邻键纠错", redeploy = true),
        // 26 键全拼另外三种纠错（[SpellingPatches.Typo]，11-03 拆出来单独开关）。顺序、多键要用邻键纠错的键位表：
        // 开着 = 这一项和邻键纠错都开；单开它会连邻键纠错一起开，实测值要减去邻键纠错那一项再写进表。
        TRANSPOSITION_CORRECTION("transposition_correction", "顺序纠错", redeploy = true),
        EXTRA_LETTER_CORRECTION("extra_letter_correction", "多键纠错", redeploy = true),
        // 打字途中只多查重复字母那一处，主要的活在停顿 180 ms 后补查，那次不算进每键耗时。
        OMISSION_CORRECTION("omission_correction", "漏键纠错", redeploy = true),
        DOUBLE_PINYIN_ABBREVIATION("double_pinyin_abbreviation", "双拼简拼", redeploy = true),
        MIXED_INPUT("mixed_input", "中英混输"),
        CHINESE_GLIDE("chinese_glide", "中文滑行"),
        TRADITIONAL("traditional_output", "繁体输出"),
        RARE_CHARACTERS("rare_characters", "生僻字"),
        DATETIME("datetime_candidates", "时间日期候选"),
        EMOJI("candidate_emoji", "候选里显示表情"),
        SYMBOL_NAMES("symbol_names", "打符号名出符号"),
        GREEK_LETTERS("greek_letters", "希腊字母"),
        PREDICTION("smart_prediction", "智能联想"),
        GLASS("glass", "玻璃效果"),
        KEY_ANIMATION("key_animation", "按键动效"),
        KEY_GLOW("key_glow", "键内炫光"),
        // 只换长按语言键弹出的东西，打字不经过它；照规矩登记，实测后才显示代价。
        LANGUAGE_KEY_IME_PICKER("language_key_ime_picker", "长按语言键切换输入法"),
        // 剪贴板读相册里的截图；只在有相册权限时算开着。测它的设备要先授权，否则 perf_cost_bench --skip recent_screenshots。
        RECENT_SCREENSHOTS("recent_screenshots", "最近截图"),
        // 只少画几行字，按键处理不变；照规矩登记，实测后才显示代价。
        HIDE_FUNCTION_KEY_HINTS("hide_function_key_hints", "功能键不显示提示字"),
        FUZZY_Z("fuzzy_z_zh", "模糊音 z 和 zh", redeploy = true),
        FUZZY_C("fuzzy_c_ch", "模糊音 c 和 ch", redeploy = true),
        FUZZY_S("fuzzy_s_sh", "模糊音 s 和 sh", redeploy = true),
        FUZZY_NL("fuzzy_n_l", "模糊音 n 和 l", redeploy = true),
        FUZZY_FH("fuzzy_f_h", "模糊音 f 和 h", redeploy = true),
        FUZZY_RL("fuzzy_r_l", "模糊音 r 和 l", redeploy = true),
        FUZZY_AN("fuzzy_an_ang", "模糊音 an 和 ang", redeploy = true),
        FUZZY_EN("fuzzy_en_eng", "模糊音 en 和 eng", redeploy = true),
        FUZZY_IN("fuzzy_in_ing", "模糊音 in 和 ing", redeploy = true),
        FUZZY_IAN("fuzzy_ian_iang", "模糊音 ian 和 iang", redeploy = true),
        FUZZY_UAN("fuzzy_uan_uang", "模糊音 uan 和 uang", redeploy = true),
        // 九键按数字也模糊（[SpellingPatches.FUZZY_T9_DIGITS_KEY]）：同一组在九键上的代价另测，在九键上测（perf_cost_bench --layout t9）。
        // 开着 = 「九键按数字也模糊」开着且这一组开着。
        T9_FUZZY_Z("t9_fuzzy_z_zh", "九键模糊音 z 和 zh", redeploy = true),
        T9_FUZZY_C("t9_fuzzy_c_ch", "九键模糊音 c 和 ch", redeploy = true),
        T9_FUZZY_S("t9_fuzzy_s_sh", "九键模糊音 s 和 sh", redeploy = true),
        T9_FUZZY_NL("t9_fuzzy_n_l", "九键模糊音 n 和 l", redeploy = true),
        T9_FUZZY_FH("t9_fuzzy_f_h", "九键模糊音 f 和 h", redeploy = true),
        T9_FUZZY_RL("t9_fuzzy_r_l", "九键模糊音 r 和 l", redeploy = true),
        T9_FUZZY_AN("t9_fuzzy_an_ang", "九键模糊音 an 和 ang", redeploy = true),
        T9_FUZZY_EN("t9_fuzzy_en_eng", "九键模糊音 en 和 eng", redeploy = true),
        T9_FUZZY_IN("t9_fuzzy_in_ing", "九键模糊音 in 和 ing", redeploy = true),
        T9_FUZZY_IAN("t9_fuzzy_ian_iang", "九键模糊音 ian 和 iang", redeploy = true),
        T9_FUZZY_UAN("t9_fuzzy_uan_uang", "九键模糊音 uan 和 uang", redeploy = true),
        // 11-03 补登记（用户：每个开关都要有代价）。滑行类只在划的时候干活，点按打字基本不经过它们。
        GLIDE_TYPING("glide_typing", "英文滑行"),
        GLIDE_PREVIEW("glide_preview", "滑行时实时显示词"),
        // t9_ 开头：九键上的手势，按九键测、按九键基线算百分比。
        T9_GLIDE("t9_glide_typing", "九键滑行"),
        // 开着 = 玻璃和液态玻璃都开；单开它会连玻璃一起开，实测值要减去玻璃那一项再写进表。
        LIQUID_GLASS("liquid_glass", "液态玻璃"),
        PRESS_BUBBLE("press_bubble", "点按弹出气泡"),
        DOUBLE_PINYIN_KEY_HINTS("double_pinyin_key_hints", "双拼键位提示"),
        DELETE_RECOVERY("delete_recovery", "删字后给同音字"),
        HANDWRITING_LIVE("handwriting_recognize_while_writing", "手写边写边识别"),
        TYPING_STATS("typing_stats", "记录打字统计"),
        // 只在数字上屏后读一次光标前的字、查一遍编码是数字的短语；打拼音不经过它。
        DIGIT_PHRASES("digit_phrase_candidates", "数字出短语"),
        // 打字不经过它，代价在键盘显示时每秒 20 帧的合成；测它要用图片背景（我的图片或内置图片主题），纯色、渐变不动。
        BACKGROUND_MOTION("background_motion", "动态背景");

        val nineKey: Boolean get() = id.startsWith(T9_PREFIX)

        companion object {
            fun byId(id: String): Item? = entries.firstOrNull { it.id == id }
            fun fuzzy(item: SpellingPatches.Fuzzy): Item = checkNotNull(byId("fuzzy_" + item.id))
            fun t9Fuzzy(item: SpellingPatches.Fuzzy): Item = checkNotNull(byId(T9_PREFIX + "fuzzy_" + item.id))
            fun typo(item: SpellingPatches.Typo): Item = when (item) {
                SpellingPatches.Typo.TRANSPOSITION -> TRANSPOSITION_CORRECTION
                SpellingPatches.Typo.EXTRA -> EXTRA_LETTER_CORRECTION
                SpellingPatches.Typo.OMISSION -> OMISSION_CORRECTION
            }
        }
    }

    /** 九键项的 id 前缀；测量脚本按它分开 26 键和九键两次测。 */
    const val T9_PREFIX = "t9_"

    private fun fuzzyOf(item: Item): SpellingPatches.Fuzzy? =
        SpellingPatches.Fuzzy.entries.firstOrNull { "fuzzy_" + it.id == item.id }

    private fun t9FuzzyOf(item: Item): SpellingPatches.Fuzzy? =
        SpellingPatches.Fuzzy.entries.firstOrNull { T9_PREFIX + "fuzzy_" + it.id == item.id }

    fun isOn(context: Context, item: Item): Boolean {
        fuzzyOf(item)?.let { return it in SpellingPatches.fuzzy(context) }
        t9FuzzyOf(item)?.let { return SpellingPatches.fuzzyT9Digits(context) && it in SpellingPatches.fuzzy(context) }
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val input by lazy { KeyboardInputPreferences.read(context) }
        return when (item) {
            Item.NEIGHBOR_CORRECTION -> input.neighborCorrection
            // 11-03 起「邻键纠错」双拼也用：它关着时双拼邻键纠错不起作用。
            Item.DOUBLE_PINYIN_CORRECTION -> input.neighborCorrection && SpellingPatches.doublePinyinCorrection(context)
            Item.TRANSPOSITION_CORRECTION -> input.neighborCorrection && SpellingPatches.typo(context, SpellingPatches.Typo.TRANSPOSITION)
            Item.EXTRA_LETTER_CORRECTION -> input.neighborCorrection && SpellingPatches.typo(context, SpellingPatches.Typo.EXTRA)
            Item.OMISSION_CORRECTION -> SpellingPatches.typo(context, SpellingPatches.Typo.OMISSION)
            Item.DOUBLE_PINYIN_ABBREVIATION -> SpellingPatches.doublePinyinAbbreviation(context)
            Item.MIXED_INPUT -> input.mixedInput
            Item.CHINESE_GLIDE -> input.chineseGlideTyping
            Item.TRADITIONAL -> input.traditionalOutput
            Item.RARE_CHARACTERS -> input.rareCharacters
            Item.DATETIME -> input.datetimeCandidates
            Item.EMOJI -> input.candidateEmoji
            Item.SYMBOL_NAMES -> input.symbolNames
            Item.GREEK_LETTERS -> input.greekLetters
            Item.PREDICTION -> SettingsPreferences.isSmartPredictionEnabled(context)
            Item.GLASS -> FrostedGlassPreferences.readProfiles(context).dark.enabled
            Item.KEY_ANIMATION -> KeyEffectPreferences.animationEnabled(prefs)
            Item.KEY_GLOW -> KeyEffectPreferences.glowEnabled(prefs)
            Item.LANGUAGE_KEY_IME_PICKER -> input.languageKeyImePicker
            Item.RECENT_SCREENSHOTS -> com.kingzcheung.xime.clipboard.ClipboardImages.getInstance(context).recentScreenshotsEnabled()
            Item.HIDE_FUNCTION_KEY_HINTS -> input.hideFunctionKeyHints
            Item.GLIDE_TYPING -> input.glideTyping
            Item.GLIDE_PREVIEW -> input.glidePreview
            Item.T9_GLIDE -> input.t9GlideTyping
            Item.LIQUID_GLASS -> FrostedGlassPreferences.readProfiles(context).dark.enabled && SettingsPreferences.glassLiquid(context)
            Item.PRESS_BUBBLE -> SettingsPreferences.shouldShowPressBubble(context)
            Item.DOUBLE_PINYIN_KEY_HINTS -> input.doublePinyinKeyHints
            Item.DELETE_RECOVERY -> SettingsPreferences.isDeleteRecovery(context)
            Item.HANDWRITING_LIVE -> input.handwritingRecognizeWhileWriting
            Item.TYPING_STATS -> com.kingzcheung.xime.stats.TypingStats.isEnabled(context)
            Item.DIGIT_PHRASES -> com.kingzcheung.xime.service.DigitPhrases.isEnabled(context)
            Item.BACKGROUND_MOTION -> BackgroundMotionPreferences.read(context).enabled
            else -> false
        }
    }

    /**
     * 只给测量脚本用（开发者工具广播 `op=perf_switch`）：和设置页拨开关写同样的值。
     * 要重新部署的项写完补丁就返回，部署在输入法下次启动时做，脚本会重启输入法再测。
     */
    fun set(context: Context, item: Item, on: Boolean) {
        fuzzyOf(item)?.let { SpellingPatches.setFuzzy(context, it, on); SpellingPatches.apply(context); return }
        // 九键项和 26 键项共用同一组的开关：开 = 这组和「九键按数字也模糊」都开；关只关后者，这组留给 26 键那一项管，
        // 脚本按原状放回时两边不会互相关掉。
        t9FuzzyOf(item)?.let {
            if (on) SpellingPatches.setFuzzy(context, it, true)
            SpellingPatches.setFuzzyT9Digits(context, on)
            SpellingPatches.apply(context)
            return
        }
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val input = KeyboardInputPreferences.read(context)
        when (item) {
            Item.NEIGHBOR_CORRECTION -> input.copy(neighborCorrection = on).save(context)
            Item.DOUBLE_PINYIN_CORRECTION -> {
                input.copy(doublePinyinCorrection = on, neighborCorrection = on || input.neighborCorrection).save(context)
                SpellingPatches.apply(context)
            }
            Item.DOUBLE_PINYIN_ABBREVIATION -> {
                SpellingPatches.setDoublePinyinAbbreviation(context, on); SpellingPatches.apply(context)
            }
            // 同液态玻璃：开顺序、多键时邻键纠错没开就一起开；关只关这一项，邻键纠错留给 NEIGHBOR_CORRECTION 那一项管。
            Item.TRANSPOSITION_CORRECTION, Item.EXTRA_LETTER_CORRECTION, Item.OMISSION_CORRECTION -> {
                val typo = SpellingPatches.Typo.entries.first { Item.typo(it) == item }
                if (on && typo != SpellingPatches.Typo.OMISSION) input.copy(neighborCorrection = true).save(context)
                SpellingPatches.setTypo(context, typo, on); SpellingPatches.apply(context)
            }
            Item.MIXED_INPUT -> input.copy(mixedInput = on).save(context)
            Item.CHINESE_GLIDE -> input.copy(chineseGlideTyping = on).save(context)
            Item.TRADITIONAL -> input.copy(traditionalOutput = on).save(context)
            Item.RARE_CHARACTERS -> input.copy(rareCharacters = on).save(context)
            Item.DATETIME -> input.copy(datetimeCandidates = on).save(context)
            Item.EMOJI -> input.copy(candidateEmoji = on).save(context)
            Item.SYMBOL_NAMES -> input.copy(symbolNames = on).save(context)
            Item.GREEK_LETTERS -> input.copy(greekLetters = on).save(context)
            Item.PREDICTION -> SettingsPreferences.setSmartPredictionEnabled(context, on)
            Item.GLASS -> FrostedGlassPreferences.saveProfiles(context, FrostedGlassPreferences.readProfiles(context).withEnabled(on))
            Item.KEY_ANIMATION -> KeyEffectPreferences.setAnimationEnabled(prefs, on)
            Item.KEY_GLOW -> KeyEffectPreferences.setGlowEnabled(prefs, on)
            Item.LANGUAGE_KEY_IME_PICKER -> input.copy(languageKeyImePicker = on).save(context)
            Item.RECENT_SCREENSHOTS -> com.kingzcheung.xime.clipboard.ClipboardImages.getInstance(context).enableRecentScreenshots(on)
            Item.HIDE_FUNCTION_KEY_HINTS -> input.copy(hideFunctionKeyHints = on).save(context)
            Item.GLIDE_TYPING -> input.copy(glideTyping = on).save(context)
            Item.GLIDE_PREVIEW -> input.copy(glidePreview = on).save(context)
            Item.T9_GLIDE -> input.copy(t9GlideTyping = on).save(context)
            // 和主题页一样：开液态玻璃时玻璃没开就一起开；关只关液态这一层，玻璃留给 GLASS 那一项管。
            Item.LIQUID_GLASS -> {
                if (on) FrostedGlassPreferences.saveProfiles(context, FrostedGlassPreferences.readProfiles(context).withEnabled(true))
                SettingsPreferences.setGlassLiquid(context, on)
            }
            Item.PRESS_BUBBLE -> SettingsPreferences.setShowPressBubble(context, on)
            Item.DOUBLE_PINYIN_KEY_HINTS -> input.copy(doublePinyinKeyHints = on).save(context)
            Item.DELETE_RECOVERY -> SettingsPreferences.setDeleteRecovery(context, on)
            Item.HANDWRITING_LIVE -> input.copy(handwritingRecognizeWhileWriting = on).save(context)
            Item.TYPING_STATS -> com.kingzcheung.xime.stats.TypingStats.setEnabled(context, on)
            Item.DIGIT_PHRASES -> com.kingzcheung.xime.service.DigitPhrases.setEnabled(context, on)
            Item.BACKGROUND_MOTION -> BackgroundMotionPreferences.setEnabled(context, on)
            else -> Unit
        }
    }

    /**
     * 现在开着、并且对当前键盘起作用的项（总代价按它算）：同一组模糊音在 26 键和九键各有一项，代价分开测，
     * 打九键时算九键那一项，打别的键盘时算 26 键那一项，不重复相加。
     */
    fun enabled(context: Context): Set<String> {
        val nineKey = nineKey(context)
        return Item.entries.filter { item -> if (nineKey) item.nineKey || fuzzyOf(item) == null else !item.nineKey }
            .filterTo(linkedSetOf()) { runCatching { isOn(context, it) }.getOrDefault(false) }
            .mapTo(linkedSetOf()) { it.id }
    }

    // ---- 表 ----

    @Serializable
    data class Cost(
        /** 每键中位耗时比基线多几毫秒（触摸到候选画完）。 */
        val keyMs: Double? = null,
        /** 输入法进程内存（PSS）比基线多几 MB。 */
        val memoryMb: Double? = null,
        /** 这一项在另一个键盘上另测时（九键项）那次的全关基线，“慢百分之几”按它算；空时用设备的基线。 */
        val baselineKeyMs: Double? = null,
        /** true = 还没实测，是估算（安装包表里写的，或本机表缺这项时从安装包表补的）；显示时标“估算”。 */
        val estimated: Boolean = false,
        /** 不按每键算的开销（只在用到时才有，如滑行预览只在滑动中占 CPU），原样写在数字后面。 */
        val note: String? = null,
        /**
         * 最慢 5% 的按键多几毫秒（卡不卡主要看它）。引擎类开关平时每键只多零点几毫秒，慢键多出几毫秒，
         * 只写中位数会显得没影响；空时不写。
         */
        val keyP95Ms: Double? = null,
        /**
         * 数从哪来：空 = 这台设备触摸到出字实测（或估算，看 [estimated]）；[BASIS_ENGINE] = 电脑上用同一份输入法引擎
         * 逐键实测（`/mnt/project-files/perf-matrix-1103`，只含引擎查词排序，不含画面），不是这台设备测的。
         */
        val basis: String? = null,
    )

    /** [Cost.basis]：电脑同源引擎实测。 */
    const val BASIS_ENGINE = "engine"

    @Serializable
    data class Combo(val name: String = "", val on: List<String> = emptyList(), val keyMs: Double? = null, val memoryMb: Double? = null)

    @Serializable
    data class Baseline(
        val keyMs: Double, val memoryMb: Double? = null,
        /** 最慢 5% 按键的耗时；词库曲线（[DictionaryPoint.keyP95Ms]）按它算百分比。 */
        val keyP95Ms: Double? = null,
    )

    /**
     * 开着 [words] 个词的导入词库时比一个都不开多慢多少（`tools/ime_lab/dict_cost_bench.py`，真实搜狗词库），
     * 词库页「最多慢 X%」和这里的总代价都按这条曲线算（[CellDictionaryCost.Curve.fromDevice]、[dictionaryCost]）。
     */
    @Serializable
    data class DictionaryPoint(
        val words: Int,
        val keyMs: Double? = null,
        val keyP95Ms: Double? = null,
        val memoryMb: Double? = null,
        /** 测这一点时开的词库，只给人核对。 */
        val dictionaries: List<String> = emptyList(),
    )

    @Serializable
    data class Device(
        /** Build.MODEL，如 SM-X800。 */
        val model: String,
        /** 给人看的名字，如“Galaxy Tab S8+”；空时用 model。 */
        val name: String = "",
        /** high / medium / low，同 [com.kingzcheung.xime.runtime.adaptation.PerformanceTier]。 */
        val tier: String = "",
        val measuredAt: String = "",
        val appVersion: String = "",
        /** 测的是哪个键盘，如“中文全拼 26 键”。 */
        val layout: String = "",
        val baseline: Baseline,
        /** 基线前后两次之差：低于它的差别算测不出来。 */
        val noiseMs: Double = 0.5,
        val items: Map<String, Cost> = emptyMap(),
        val combos: List<Combo> = emptyList(),
        /** 导入词库的代价曲线（26 键全拼），没测过为空。 */
        val dictionaries: List<DictionaryPoint> = emptyList(),
        /** 测词库曲线那次的基线：开关保持原样、导入词库一个都不开；空时用 [baseline]。 */
        val dictionaryBaseline: Baseline? = null,
        /**
         * 语音模型常驻（「语音模型空闲后全部释放」= 永不）时 :asr 进程一直占的内存。模型在单独的 :asr 进程里，
         * 不在开关基线里，以前总开销漏了它（平板 11-03：总开销写 +82 MB，:asr 实际 605 MB）。
         */
        val voiceResident: Cost? = null,
        /** 九键词组模型（t9_ngram.lm）装着时九键每键多花的时间；不是开关，在九键上且模型装着时计入总开销。 */
        val t9Model: Cost? = null,
    ) {
        /** 一项都还没实测（安装包表里的估算）。 */
        val allEstimated: Boolean get() = items.isNotEmpty() && items.values.all { it.estimated }
    }

    @Serializable
    data class Table(val version: Int = 1, val devices: List<Device> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Table? = runCatching { json.decodeFromString(Table.serializer(), text) }.getOrNull()

    /**
     * 用哪台机器的数：本机实测 > 表里同型号 > 表里同档位 > 表里第一台按档位折算（[scaled]）；[local] 表示本机实测。
     * 本机实测缺的项从后面几种补上并标估算，所以只要安装包表在，就不会是 null。
     */
    data class Source(val device: Device, val local: Boolean, val sameModel: Boolean, val scaled: Boolean = false)

    /**
     * 不同档位每键耗时的大致倍数（高配为 1）：作者平板（骁龙 8 Gen 1）对中端（骁龙 7 系、天玑 8000 级）、
     * 低端（4 GB 内存、A55 小核为主）单核性能约 1 : 0.6 : 0.35 的反比。内存差别不按档位变，不折算。
     */
    val TIER_SCALE = mapOf("HIGH" to 1.0, "MEDIUM" to 1.7, "LOW" to 2.8)

    fun tierScale(from: String, to: String): Double {
        val a = TIER_SCALE[from.uppercase()] ?: return 1.0
        val b = TIER_SCALE[to.uppercase()] ?: return 1.0
        return b / a
    }

    fun pick(local: Table?, bundled: Table?, model: String, tier: String): Source? {
        val reference = bundled?.devices?.firstOrNull { it.model.equals(model, ignoreCase = true) }
            ?.let { Source(it, local = false, sameModel = true) }
            ?: bundled?.devices?.firstOrNull { it.tier.equals(tier, ignoreCase = true) }?.let { Source(it, local = false, sameModel = false) }
            ?: bundled?.devices?.firstOrNull()?.let { device ->
                val factor = tierScale(device.tier, tier)
                Source(scale(device, factor), local = false, sameModel = false, scaled = factor != 1.0)
            }
        local?.devices?.firstOrNull { it.model.equals(model, ignoreCase = true) }?.let { mine ->
            return Source(fillFrom(mine, reference?.device), local = true, sameModel = true)
        }
        return reference
    }

    /** [device] 缺的项从 [reference] 补，补上的都标估算（不是这台机器测的）；本机测过的项没写 note 时沿用 [reference] 的。 */
    fun fillFrom(device: Device, reference: Device?): Device {
        reference ?: return device
        val missing = reference.items.filterKeys { it !in device.items }
            .mapValues { (_, c) -> if (c.basis == BASIS_ENGINE) c else c.copy(estimated = true) }
        val noted = device.items.mapValues { (id, c) -> if (c.note == null) c.copy(note = reference.items[id]?.note) else c }
        val voice = device.voiceResident ?: reference.voiceResident?.copy(estimated = true)
        // 引擎实测不是哪台设备的数，补过来照原样标来源，不改成估算。
        val t9 = device.t9Model ?: reference.t9Model?.let { if (it.basis == BASIS_ENGINE) it else it.copy(estimated = true) }
        return if (missing.isEmpty() && noted == device.items && voice === device.voiceResident && t9 === device.t9Model) device
            else device.copy(items = noted + missing, voiceResident = voice, t9Model = t9)
    }

    /** 每键耗时都乘 [factor]（基线一起乘，“慢百分之几”不变）；内存不变。 */
    fun scale(device: Device, factor: Double): Device {
        if (factor == 1.0) return device
        fun Double?.x() = this?.times(factor)
        fun Baseline.x() = copy(keyMs = keyMs * factor, keyP95Ms = keyP95Ms.x())
        return device.copy(
            baseline = device.baseline.x(),
            noiseMs = device.noiseMs * factor,
            // 引擎实测是电脑上的数，不按档位折算（折算系数是按手机芯片定的）。
            items = device.items.mapValues { (_, c) -> if (c.basis == BASIS_ENGINE) c
                else c.copy(keyMs = c.keyMs.x(), keyP95Ms = c.keyP95Ms.x(), baselineKeyMs = c.baselineKeyMs.x()) },
            combos = device.combos.map { it.copy(keyMs = it.keyMs.x()) },
            dictionaries = device.dictionaries.map { it.copy(keyMs = it.keyMs.x(), keyP95Ms = it.keyP95Ms.x()) },
            dictionaryBaseline = device.dictionaryBaseline?.x(),
        )
    }

    const val LOCAL_FILE = "perf-costs.json"
    private const val ASSET = "perf_costs.json"

    @Volatile private var cached: Pair<Long, Source?>? = null

    /** 本机该用的那组数；安装包表和本机表都读不到才返回 null（不显示代价）。本机实测文件变了自动重读。 */
    fun source(context: Context): Source? {
        val file = context.getExternalFilesDir(null)?.let { File(it, LOCAL_FILE) }
        val stamp = file?.takeIf { it.isFile }?.lastModified() ?: 0L
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        val local = file?.takeIf { it.isFile }?.let { runCatching { parse(it.readText()) }.getOrNull() }
        val bundled = runCatching { context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) } }.getOrNull()
        val tier = runCatching {
            com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.classify(
                com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe.read(context)).name
        }.getOrDefault("")
        return pick(local, bundled, Build.MODEL.orEmpty(), tier).also { cached = stamp to it }
    }

    // ---- 文字 ----

    /**
     * “每键 +1.2 ms（慢 4%）· 内存 +30 MB”。每键低于测量误差、内存不到 1 MB 的部分不写；两样都没有返回 null，
     * 开关下面就不出这一行（用户 11-03「这种东西还有必要写性能开销吗」「也不用大约了」）。表里没有这项也返回 null。
     */
    fun describe(cost: Cost?, device: Device): String? {
        cost ?: return null
        val parts = mutableListOf<String>()
        cost.keyMs?.let { ms ->
            if (ms >= device.noiseMs) parts += "每键 +${oneDecimal(ms)} ms(慢 ${percent(ms, cost.baselineKeyMs ?: device.baseline.keyMs)}%)"
        }
        cost.keyP95Ms?.let { ms -> if (ms >= device.noiseMs) parts += "慢键 +${oneDecimal(ms)} ms" }
        cost.memoryMb?.let { mb -> if (mb >= 1) parts += "内存 +${mb.roundToInt()} MB" }
        cost.note?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** 开关下面那一行：“性能开销：每键 +1.2 ms（慢 4%）”，别的机器测的补上是哪台；没有值得说的开销不出这一行。 */
    fun note(source: Source, id: String, label: String = "性能开销"): String? {
        val cost = source.device.items[id]
        val text = describe(cost, source.device) ?: return null
        return "$label：$text" + when {
            cost?.basis == BASIS_ENGINE -> "(电脑引擎实测)"
            cost?.estimated == true -> ""
            else -> reference(source)
        }
    }

    /**
     * 几项合起来的一行（「九键按数字也模糊」下面：已开的各组合计），算法同 [total]；一项都没开或表里都没有返回 null。
     */
    fun totalNote(source: Source, ids: Set<String>, label: String): String? {
        val total = total(source.device, ids)
        if (total.items.isEmpty()) return null
        val estimated = total.estimated.isNotEmpty()
        val text = describe(Cost(total.keyMs, total.memoryMb, total.baselineKeyMs, estimated = estimated), source.device) ?: return null
        return "$label：$text" + if (estimated) "" else reference(source)
    }

    private fun reference(source: Source) = when {
        source.sameModel -> ""
        source.scaled -> "(${deviceName(source.device)} 实测按本机档位折算，仅供参考)"
        else -> "(${deviceName(source.device)} 实测，仅供参考)"
    }

    fun deviceName(device: Device) = device.name.ifBlank { device.model }

    /**
     * 「语音模型空闲后全部释放」选到“永不”时滑条下面的提醒（用户 11-03：设成永不要给足提醒，占多少、内存满了会怎样说清楚）。
     * 内存数取实测表的 [Device.voiceResident]，没有就不写数。重新加载 1.9 秒是平板 11-03 实测 (Paraformer 流式，logcat CyIME-AsrTiming)。
     */
    fun voiceResidentWarning(source: Source?): String {
        val mb = source?.device?.voiceResident?.memoryMb?.takeIf { it >= 1 }
        val amount = if (source == null || mb == null) "语音模型会一直占着内存"
            else "语音模型会一直占着 ${mb.roundToInt()} MB 内存" + if (source.sameModel) "" else "(${deviceName(source.device)} 实测)"
        return "$amount，其他应用退到后台后更容易被系统关掉。系统内存紧张时会自动卸掉语音模型，" +
            "下次按麦克风先重新加载(平板实测 1.9 秒)，打字不受影响。"
    }

    data class Total(
        val keyMs: Double,
        val memoryMb: Double,
        /** true = 这个组合实测过；false = 按单项相加再用最接近的实测组合校准。 */
        val measured: Boolean,
        /** 开着、但表里没有数的项。 */
        val unmeasured: List<String>,
        /** 开着的项按每键耗时从大到小。 */
        val items: List<Pair<String, Cost>>,
        /** 测出耗时的各项都在同一个另测的基线上（如全是九键项）时就是它，“慢百分之几”按它算。 */
        val baselineKeyMs: Double? = null,
        /** 开着、按估算计入的项（还没实测）。 */
        val estimated: List<String> = emptyList(),
        /** 各项慢键多出的毫秒直接相加（只有引擎实测有这个数，不校准）；没有为 0。 */
        val keyP95Ms: Double = 0.0,
    )

    /**
     * 当前开着的 [on] 合起来的代价。组合实测过就用实测；否则单项相加，再乘最接近那组实测组合的
     * “实测 / 单项相加” 比例（按共同开着的项算最接近，没有组合时不校准）。
     */
    fun total(device: Device, on: Set<String>): Total {
        val known = on.mapNotNull { id -> device.items[id]?.let { id to it } }
        val unmeasured = on.filter { it !in device.items }
        val sumKey = known.sumOf { it.second.keyMs ?: 0.0 }
        val sumMemory = known.sumOf { it.second.memoryMb ?: 0.0 }
        val sorted = known.sortedByDescending { it.second.keyMs ?: 0.0 }
        val baselines = known.filter { it.second.keyMs != null }.map { it.second.baselineKeyMs }.distinct()
        val baseline = baselines.singleOrNull()
        val estimated = known.filter { it.second.estimated }.map { it.first }
        val sumP95 = known.sumOf { it.second.keyP95Ms ?: 0.0 }
        device.combos.firstOrNull { it.on.toSet() == on && it.keyMs != null }?.let { exact ->
            return Total(exact.keyMs!!, exact.memoryMb ?: sumMemory, measured = true, unmeasured = unmeasured, items = sorted,
                baselineKeyMs = baseline, keyP95Ms = sumP95)
        }
        fun ratio(measured: Double?, items: Collection<String>, field: (Cost) -> Double?): Double? {
            val sum = items.sumOf { device.items[it]?.let(field) ?: 0.0 }
            return if (measured == null || sum <= 0.0) null else measured / sum
        }
        val nearest = device.combos.filter { it.on.isNotEmpty() }
            .maxByOrNull { combo -> combo.on.count { it in on }.toDouble() / (combo.on.toSet() + on).size }
            ?.takeIf { combo -> combo.on.any { it in on } }
        val keyRatio = nearest?.let { ratio(it.keyMs, it.on) { c -> c.keyMs } } ?: 1.0
        val memoryRatio = nearest?.let { ratio(it.memoryMb, it.on) { c -> c.memoryMb } } ?: 1.0
        return Total(sumKey * keyRatio, sumMemory * memoryRatio, measured = false, unmeasured = unmeasured, items = sorted,
            baselineKeyMs = baseline, estimated = estimated, keyP95Ms = sumP95)
    }

    /**
     * 开着 [words] 个词的导入词库每键多几毫秒、多占多少内存：在实测点之间按词数线性取值，超过最后一点按比例放大；
     * 没测过返回 null（不显示）。
     */
    fun dictionaryCost(device: Device, words: Int): Cost? {
        if (words <= 0 || device.dictionaries.isEmpty()) return null
        val points = (listOf(DictionaryPoint(0, 0.0, 0.0, 0.0)) + device.dictionaries.filter { it.words > 0 }).sortedBy { it.words }
        fun at(field: (DictionaryPoint) -> Double?): Double? {
            val known = points.filter { field(it) != null }
            if (known.size < 2) return null
            val last = known.last()
            if (words >= last.words) return field(last)!! * words / last.words
            val upper = known.indexOfFirst { it.words >= words }
            val a = known[upper - 1]
            val b = known[upper]
            return field(a)!! + (field(b)!! - field(a)!!) * (words - a.words) / (b.words - a.words)
        }
        return Cost(keyMs = at { it.keyMs }?.coerceAtLeast(0.0), memoryMb = at { it.memoryMb }?.coerceAtLeast(0.0))
    }

    // ---- 档位 ----

    /** 「性能与省电」档位管的开关在表里的 id（前两项每档都写，后四项只关不开，见 [PerformanceProfile.turnsOff]）。 */
    private val tierItems = mapOf(
        Item.KEY_ANIMATION.id to { s: PerformanceSettings -> s.keyAnimation },
        Item.KEY_GLOW.id to { s: PerformanceSettings -> s.keyGlow },
        Item.LIQUID_GLASS.id to { s: PerformanceSettings -> s.liquidGlass },
        Item.PRESS_BUBBLE.id to { s: PerformanceSettings -> s.pressBubble },
        Item.GLIDE_PREVIEW.id to { s: PerformanceSettings -> s.glidePreview },
        Item.HANDWRITING_LIVE.id to { s: PerformanceSettings -> s.handwritingLive },
    )

    /** 当前开着的 [on] 换成 [settings] 这组档位值之后哪些开着；液态玻璃要玻璃也开着才算。别的开关原样不动。 */
    fun projected(on: Set<String>, settings: PerformanceSettings): Set<String> =
        (on - tierItems.keys) + tierItems.filter { (id, value) ->
            value(settings) && (id != Item.LIQUID_GLASS.id || Item.GLASS.id in on)
        }.keys

    /** 一组开关加上常驻语音、九键模型、导入词库之后的总开销。 */
    data class Load(val keyMs: Double, val keyP95Ms: Double, val memoryMb: Double)

    fun load(device: Device, on: Set<String>, voiceResident: Boolean, t9Model: Boolean, dictionaryWords: Int): Load {
        val switches = total(device, on)
        val dictionaries = dictionaryCost(device, dictionaryWords)
        val voice = device.voiceResident?.takeIf { voiceResident }
        val t9 = device.t9Model?.takeIf { t9Model }
        val extras = listOfNotNull(dictionaries, voice, t9)
        return Load(switches.keyMs + extras.sumOf { it.keyMs ?: 0.0 },
            switches.keyP95Ms + extras.sumOf { it.keyP95Ms ?: 0.0 },
            switches.memoryMb + extras.sumOf { it.memoryMb ?: 0.0 })
    }

    /** 总开销一行（同 [describe] 的写法）；小到测不出返回 null。 */
    fun describeLoad(load: Load, device: Device): String? =
        describe(Cost(load.keyMs, load.memoryMb, keyP95Ms = load.keyP95Ms.takeIf { it > 0 }), device)

    /**
     * 总开销下面那行“数从哪来”：开着的几项里多少是电脑引擎实测、多少是设备实测、多少还是按同类推算的，
     * 再写全关时的基线。用户 11-03「性能估算也不准」：推算的项数要写出来，不混成实测。
     */
    fun basisLine(source: Source, ids: Collection<String>): String {
        val device = source.device
        val costs = ids.distinct().mapNotNull { id -> if (id == T9_MODEL_ID) device.t9Model else device.items[id] }
        val engine = costs.count { it.basis == BASIS_ENGINE }
        val guessed = costs.count { it.basis != BASIS_ENGINE && it.estimated }
        val measured = costs.size - engine - guessed
        val who = when {
            source.local -> "本机实测"
            source.sameModel -> "同型号实测"
            else -> "${deviceName(device)} 实测"
        }
        val parts = listOfNotNull(
            "电脑引擎实测 $engine 项".takeIf { engine > 0 },
            "$who $measured 项".takeIf { measured > 0 },
            "按同类推算 $guessed 项".takeIf { guessed > 0 },
        )
        return buildString {
            if (parts.isNotEmpty()) append("开着的 ${costs.size} 项：").append(parts.joinToString("，")).append("。")
            append("全关时每键 ${oneDecimal(device.baseline.keyMs)} ms")
            if (source.scaled) append("(${deviceName(device)} 按本机档位折算)")
            append("。")
        }
    }

    /** [basisLine] 里代表九键词组模型的 id（不是开关，不在 [Item] 里）。 */
    const val T9_MODEL_ID = "t9_model"

    /**
     * 开销刻度 0～100：0 = 能关的全关，100 = 表里每一项都开（含常驻语音、九键模型）。
     * 每键耗时和内存各占一半，各按“全开”时的值归一；有慢键数时每键耗时取中位和慢键的平均。
     */
    fun loadScore(load: Load, full: Load): Int {
        fun part(value: Double, max: Double) = if (max <= 0.0) 0.0 else (value / max).coerceIn(0.0, 1.0)
        val time = if (full.keyP95Ms > 0) (part(load.keyMs, full.keyMs) + part(load.keyP95Ms, full.keyP95Ms)) / 2
            else part(load.keyMs, full.keyMs)
        return Math.round(100 * (time + part(load.memoryMb, full.memoryMb)) / 2).toInt().coerceIn(0, 100)
    }

    /** “全开”：表里登记的开关全开（九键上用九键那一组），加常驻语音和九键模型，词库按当前的。 */
    fun fullOn(device: Device, nineKey: Boolean): Set<String> =
        Item.entries.filter { if (nineKey) it.nineKey || fuzzyOf(it) == null else !it.nineKey }
            .map { it.id }.filterTo(linkedSetOf()) { it in device.items }

    /** 本机现在打的是不是九键（模糊音两组按键盘分开算）。 */
    fun nineKey(context: Context): Boolean = runCatching {
        InputProfiles.describe(SettingsPreferences.getCurrentSchema(context)).layout.kind == LayoutKind.T9
    }.getOrDefault(false)

    /** 九键词组模型装着且没被停用（rime/ 里有 t9_ngram.lm）。 */
    fun t9ModelActive(context: Context): Boolean = runCatching {
        java.io.File(context.filesDir, "rime/t9_ngram.lm").isFile &&
            listOf(com.kingzcheung.xime.model.CandidateModelCatalog.NGRAM, com.kingzcheung.xime.model.CandidateModelCatalog.NGRAM_SMALL)
                .none { SettingsPreferences.getPrefsPublic(context).getBoolean("candidate_model_disabled_$it", false) }
    }.getOrDefault(false)

    fun percent(ms: Double, baseline: Double): Int = if (baseline <= 0) 0 else (ms / baseline * 100).roundToInt()

    fun oneDecimal(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)
}
