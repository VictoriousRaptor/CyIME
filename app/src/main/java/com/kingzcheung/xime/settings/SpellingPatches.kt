package com.kingzcheung.xime.settings

import android.content.Context
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.io.File

/**
 * 中文全拼、双拼的模糊音，以及双拼 26 键的邻键纠错和简拼。改的是方案的拼写规则或翻译器配置，只能写进 `<方案>.custom.yaml` 再部署；
 * 这里只增删自己标记的一段，个人词库、分类词库、短语等其他补丁原样保留。
 * 各组默认关闭，关闭时移除曾写入的规则。
 */
object SpellingPatches {
    const val FUZZY_KEY = "fuzzy_pinyin"
    /**
     * 模糊音也用于双拼 26 键：单独的开关。起初默认关；用户 10-31 自己用小鹤双拼开了 in/ing 仍不模糊，
     * 改为默认开：开了哪几组，全拼、双拼一样模糊。各组本身仍默认关，没开模糊音的人双拼不变。
     */
    const val FUZZY_DOUBLE_PINYIN_KEY = "fuzzy_pinyin_double_pinyin"
    /**
     * 九键直接按数字也模糊（用户 11-02：很多人要，但词图变大、每键变慢，做成开关，默认关，开关和各组下面写实测代价）。
     * 关着时九键只在点左侧读音后查近音（拼写规则排在数字映射后面）；开着时把所开各组的派生排在最前，按数字也出近音字。
     */
    const val FUZZY_T9_DIGITS_KEY = "fuzzy_pinyin_t9_digits"
    /**
     * 双拼 26 键只打声母也出词，像全拼的简拼（zgrm 出中国人民）。别人提的需求（用户 11-02 转述），默认关。
     * 雾凇的双拼方案特意关着简拼：打到一半的奇数个键会被拆成简拼，候选变杂，所以只做成开关。
     */
    const val DOUBLE_PINYIN_ABBREVIATION_KEY = "double_pinyin_abbreviation"
    /**
     * 打双拼时编码显示打的键（ul pb），不换回全拼（shuang pin）。默认显示双拼键，保留已保存的选择。
     * 26 键写方案补丁清空编码显示规则；九键在 [com.kingzcheung.xime.rime.RimeEngine] 显示层把读音换成双拼键。
     * 14 键和自建合并键布局上的双拼方案本身显示双拼键，开着「显示全拼」时在 RimeEngine 显示层换成首选的全拼（11-21）。
     */
    const val DOUBLE_PINYIN_RAW_PREEDIT_KEY = "double_pinyin_raw_preedit"
    /**
     * 笔画编码显示成 一丨丿丶乛（11-18 贡献者：编码要么全是笔画、要么全是 hspnz，偏向 hspnz）。默认关：方案本身显示 h s p n z，
     * 打开时给笔画方案写补丁，编码和候选后面的剩余笔画换成笔画（康熙部首，折是乛，#412），
     * 还没对上候选的那段字母在 [com.kingzcheung.xime.rime.RimeEngine] 显示层换，整串一样。
     */
    const val STROKE_GLYPH_PREEDIT_KEY = "stroke_glyph_preedit"
    /** 与 KeyboardInputPreferences.doublePinyinCorrection 同一个键：键盘按它决定是否把键位邻接表交给纠错。 */
    const val DOUBLE_PINYIN_CORRECTION_KEY = "double_pinyin_neighbor_correction"
    /**
     * 26 键全拼除邻键以外的三种纠错（11-03 统一纠错设置）：原来都写死在雾凇方案里、跟着「邻键纠错」一起开关，
     * 设置页看不出有这几种（有人说没有顺序纠错）。默认都开，同以前；关了在方案补丁里关掉对应的键。
     * 顺序、多键要按键位找相邻字母，「邻键纠错」关着时引擎不拿键位，它们也不起作用（设置页灰掉）。
     */
    enum class Typo(val key: String, val title: String) {
        /** 相邻两个字母打反（lnu → 论）：打字途中只看最后三个字母，停顿后整串再查一遍（cyime_letter_swap）。 */
        TRANSPOSITION("typo_transposition_correction", "顺序纠错"),
        /** 多按一下同一个键或多碰旁边的键（zhonng → 中）。 */
        EXTRA("typo_extra_letter_correction", "多键纠错"),
        /** 漏打一个字母：停顿后补漏键读法（cyime_omission），以及两字相接处重复字母只打一次（chengong → 成功）。 */
        OMISSION("typo_omission_correction", "漏键纠错"),
    }

    fun typo(context: Context, item: Typo): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(item.key, true)

    fun setTypo(context: Context, item: Typo, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(item.key, enabled).apply()
    }

    private fun enabledTypos(context: Context): Set<Typo> = Typo.entries.filterTo(linkedSetOf()) { typo(context, it) }

    /** 设置页写好补丁后递增；输入法服务据此重新部署并切回当前方案。 */
    const val REVISION = "spelling_patches_revision"
    private const val START = "# BEGIN CYIME SPELLING"
    private const val END = "# END CYIME SPELLING"
    private fun YamlMap.node(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value

    /**
     * 一组模糊音：两个方向都派生（打 z 也出 zh 的字，打 zh 也出 z 的字），写法同雾凇方案注释里的模糊音。
     * an/ang 不碰 ian、uan，它们各有一组。
     */
    enum class Fuzzy(val id: String, val title: String, val example: String, val rules: List<String>) {
        Z("z_zh", "z 和 zh", "zi 也出“知”，zhi 也出“字”", listOf("derive/^zh/z/", "derive/^z([^h])/zh\$1/")),
        C("c_ch", "c 和 ch", "ci 也出“吃”，chi 也出“次”", listOf("derive/^ch/c/", "derive/^c([^h])/ch\$1/")),
        S("s_sh", "s 和 sh", "si 也出“是”，shi 也出“四”", listOf("derive/^sh/s/", "derive/^s([^h])/sh\$1/")),
        NL("n_l", "n 和 l", "nan 也出“蓝”，lan 也出“南”", listOf("derive/^n/l/", "derive/^l/n/")),
        FH("f_h", "f 和 h", "fu 也出“湖”，hu 也出“福”", listOf("derive/^f/h/", "derive/^h/f/")),
        RL("r_l", "r 和 l", "ruo 也出“落”，luo 也出“弱”", listOf("derive/^r/l/", "derive/^l/r/")),
        AN("an_ang", "an 和 ang", "fan 也出“方”，fang 也出“饭”", listOf("derive/(^|[^iu])ang$/\$1an/", "derive/(^|[^iu])an$/\$1ang/")),
        EN("en_eng", "en 和 eng", "chen 也出“成”，cheng 也出“陈”", listOf("derive/eng$/en/", "derive/en$/eng/")),
        IN("in_ing", "in 和 ing", "xin 也出“星”，xing 也出“新”", listOf("derive/ing$/in/", "derive/in$/ing/")),
        IAN("ian_iang", "ian 和 iang", "xian 也出“想”，xiang 也出“先”", listOf("derive/iang$/ian/", "derive/ian$/iang/")),
        UAN("uan_uang", "uan 和 uang", "zhuan 也出“装”，zhuang 也出“专”", listOf("derive/uang$/uan/", "derive/uan$/uang/"));

        private val replacements = rules.map { rule ->
            val (_, pattern, replacement) = rule.split('/')
            Regex(pattern) to replacement
        }

        /**
         * 26 键全拼、双拼写进方案的规则：fuzz 和 derive 一样两个方向都出字，只是近音降权（每个音节 log 0.5），打准的读音排前面。
         * 用户 11-02：双拼开了 s/sh、z/zh，uuzi 首选是素质（su zhi 权重 644574）不是数字（shu zi 330346）——derive 不降权，只比词频。
         */
        val penalized: List<String> get() = rules.map { it.replaceFirst("derive/", "fuzz/") }

        internal fun spellings(reading: String): List<String> = replacements.mapNotNull { (pattern, replacement) ->
            reading.replaceFirst(pattern, replacement).takeIf { it != reading }
        }
    }

    fun fuzzy(context: Context): Set<Fuzzy> {
        val ids = SettingsPreferences.getPrefsPublic(context).getStringSet(FUZZY_KEY, null).orEmpty()
        return Fuzzy.entries.filterTo(linkedSetOf()) { it.id in ids }
    }

    fun setFuzzy(context: Context, item: Fuzzy, enabled: Boolean) {
        val ids = fuzzy(context).mapTo(HashSet()) { it.id }
        if (enabled) ids += item.id else ids -= item.id
        SettingsPreferences.getPrefsPublic(context).edit().putStringSet(FUZZY_KEY, ids).apply()
    }

    fun fuzzyDoublePinyin(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(FUZZY_DOUBLE_PINYIN_KEY, true)

    fun setFuzzyDoublePinyin(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(FUZZY_DOUBLE_PINYIN_KEY, enabled).apply()
    }

    fun fuzzyT9Digits(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(FUZZY_T9_DIGITS_KEY, false)

    fun setFuzzyT9Digits(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(FUZZY_T9_DIGITS_KEY, enabled).apply()
    }

    fun doublePinyinAbbreviation(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(DOUBLE_PINYIN_ABBREVIATION_KEY, false)

    fun setDoublePinyinAbbreviation(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(DOUBLE_PINYIN_ABBREVIATION_KEY, enabled).apply()
    }

    fun doublePinyinRawPreedit(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(DOUBLE_PINYIN_RAW_PREEDIT_KEY, true)

    fun setDoublePinyinRawPreedit(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(DOUBLE_PINYIN_RAW_PREEDIT_KEY, enabled).apply()
    }

    fun strokeGlyphPreedit(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(STROKE_GLYPH_PREEDIT_KEY, false)
    fun setStrokeGlyphPreedit(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(STROKE_GLYPH_PREEDIT_KEY, enabled).apply()
    }

    fun doublePinyinCorrection(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(DOUBLE_PINYIN_CORRECTION_KEY, false)

    /**
     * 双拼辅助码（用户 11-15：很多人要辅助码，「先做出来个能用的」）：双拼 26 键打完拼音，点「辅」（打字时左下角的 Shift 键，
     * 实体键盘 `）再打这个字的形码，候选只留第一个字形码对得上的。默认关，关着时方案一点不改。
     * 用的是雾凇双拼方案里现成的辅码筛选（lua/search.lua，引导符 key_binder/search 就是 `，已在 speller/alphabet 里、
     * 不在 initials 里）：本来查的是部件拼音（radical_pinyin），开了就换成这里选的形码表，码表方案加进 schema/dependencies 一起编。
     * 筛选是方案最后一道滤镜，纠错、模糊音、模型排序都在它前面照常做完，辅助码只从结果里挑，不进拼音解码。
     */
    const val AUX_CODE_KEY = "double_pinyin_aux_code"
    /** 辅码引导符：雾凇双拼方案的 key_binder/search。屏幕上的「辅」送它。 */
    const val AUX_CODE_TRIGGER = "`"

    enum class AuxCode(val id: String, val title: String, val schemaId: String, val example: String) {
        ZRM("zrm", "自然码辅助码", "cyime_aux_zrm", "取字头、字尾两个部件读音的声母，如“好”（女子）是 nz"),
        FLYPY("flypy", "小鹤形码", "cyime_aux_flypy", "小鹤音形的形码，如“好”是 nz、“我”是 pd");

        companion object {
            fun of(id: String?): AuxCode? = entries.firstOrNull { it.id == id }
            val schemaIds: Set<String> get() = entries.mapTo(linkedSetOf()) { it.schemaId }
        }
    }

    fun auxCode(context: Context): AuxCode? =
        AuxCode.of(SettingsPreferences.getPrefsPublic(context).getString(AUX_CODE_KEY, null))

    /** null 关掉。改完照别的拼写设置一样调 [apply] 写补丁、重新部署。 */
    fun setAuxCode(context: Context, code: AuxCode?) {
        SettingsPreferences.getPrefsPublic(context).edit().apply {
            if (code == null) remove(AUX_CODE_KEY) else putString(AUX_CODE_KEY, code.id)
        }.apply()
    }

    /**
     * 中文全拼：雾凇拼音、内置字母排列、内置 14 键和自建布局。14 键和自建合并键布局把字母换成代表键的规则在拼写规则后面，
     * 模糊音排在最前，仍按拼音派生（fan 先派生出 fang，再合成 14 键的 DAB）。九键另外按自己的数字拼写规则处理。
     * 设置页把模糊音放在「九键与 14 键」页，14 键却一直没写规则，开了也不模糊（11-01 反馈）。
     */
    internal val builtinFullPinyinSchemas: List<String> = listOf("rime_ice", "pinyin_14jian") + BuiltinLetterLayouts.ids

    internal fun fullPinyinSchemas(context: Context): List<String> = (builtinFullPinyinSchemas +
        runCatching { CustomKeyboardLayouts.load(context).map { it.id } }.getOrDefault(emptyList())).distinct()

    /**
     * 别的中文全拼方案（导入的、扩展里装的，如 18 键 pinyin_18jian、朙月拼音）：以前只给上面自带的全拼写模糊音，
     * 这些方案开了模糊音也不生效（11-23 群里反馈 18 键）。认得出是中文全拼、不是九键的，也把模糊音写在原有拼写规则最前面，
     * 和 14 键一样先派生出近音拼音再走方案自己的规则。九键另外处理；只给别的方案查表用的方案不写。
     */
    internal fun otherFullPinyinSchemas(rimeDir: File, known: Set<String>): List<String> = rimeDir.listFiles().orEmpty()
        .mapNotNull { file -> file.name.removeSuffix(".schema.yaml").takeIf { file.isFile && file.name.endsWith(".schema.yaml") } }
        .filter { id ->
            id !in known && id !in CyimeInputDefaults.dependencies && InputProfiles.describe(id).let {
                it.language == InputLanguage.CHINESE && it.scheme == InputScheme.PINYIN && it.layout.kind != LayoutKind.T9
            }
        }.sorted()

    /**
     * 双拼 26 键：小鹤和其他内置、自定义双拼。九键另外写模糊音，不打开邻键纠错。
     * 小鹤方案构建时已带 enable_correction 和 cyime_neighbor_correction，其他双拼由它生成，同样带着；开关关着时键盘
     * 只交出带 "~;" 的键位表（只纠拼不成音节的两个键），开着才整串纠错（KeyboardLayout）。
     */
    internal fun doublePinyinSchemas(context: Context): List<String> = (DoublePinyinScheme.ALL_BUILTINS.map { it.id } +
        runCatching { DoublePinyinSchemes.load(context).map { it.id } }.getOrDefault(emptyList())).distinct()

    /**
     * 双拼在自建合并键布局（如 20 键）上的版本：拼写规则是 26 键双拼的规则再接合并键派生，模糊音照样排在最前。
     * 这些版本按设计不开邻键纠错（[DoublePinyinScheme.layoutSchema]），只写模糊音。
     */
    internal fun doublePinyinLayoutVariants(rimeDir: File): List<String> = rimeDir.listFiles().orEmpty()
        .mapNotNull { file -> file.name.removeSuffix(".schema.yaml").takeIf { file.isFile && file.name.endsWith(".schema.yaml") } }
        .filter { DoublePinyinScheme.splitLayoutVariant(it) != null }.sorted()

    /**
     * 双拼的 14 键版本（小鹤随应用内置，其余由键位表生成）：和合并键布局一样只写模糊音和简拼，模糊音排在最前，
     * 先派生出近音拼音再换成双拼键、合并键。以前只给 26 键双拼写，14 键双拼开了模糊音、简拼也不生效（11-15 实测）。
     */
    internal fun doublePinyinK14Schemas(context: Context): List<String> =
        doublePinyinSchemas(context).filter { DoublePinyinSchemes.find(it)?.ownLayout == null }.map { DoublePinyinScheme.k14Id(it) }

    /** 模糊音设置据此提醒双拼用户打开「模糊音也用于双拼」。 */
    fun usingDoublePinyin(context: Context): Boolean {
        val id = SettingsPreferences.getCurrentSchema(context)
        return id in doublePinyinSchemas(context) || DoublePinyinScheme.splitLayoutVariant(id) != null || DoublePinyinSchemes.isT9(id) || DoublePinyinSchemes.isK14(id)
    }

    /** 设置页改完开关后在后台线程调用：写补丁，有改动就通知输入法重新部署。 */
    @Synchronized fun apply(context: Context) {
        if (!sync(context)) return
        // 输入法服务没在运行时，下次启动按部署 hash 发现补丁变了再部署，不走“已准备好”的捷径。
        SettingsPreferences.setDeploymentDone(context, false)
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().putLong(REVISION, prefs.getLong(REVISION, 0L) + 1L).apply()
    }

    /**
     * 按当前开关写各方案的补丁；内容没变不写（不触发重新部署）。每次初始化也调用，
     * 新建的布局和随版本更新的方案拼写规则都跟上。返回是否改了文件。
     */
    @Synchronized fun sync(context: Context, rimeDir: File = SchemaManager.getRimeDir(context)): Boolean {
        val fuzzy = fuzzy(context)
        val correction = doublePinyinCorrection(context)
        val doublePinyinFuzzy = if (fuzzyDoublePinyin(context)) fuzzy else emptySet()
        val t9Digits = fuzzyT9Digits(context)
        val abbreviation = doublePinyinAbbreviation(context)
        val rawPreedit = doublePinyinRawPreedit(context)
        val strokeGlyphs = strokeGlyphPreedit(context)
        val typos = enabledTypos(context)
        val auxCode = auxCode(context)
        val lead = TypoStrength.lead(context)
        val digit = TypoStrength.digit(context)
        var changed = false
        val fullPinyin = fullPinyinSchemas(context)
        for (id in fullPinyin) {
            changed = write(rimeDir, id) { schema ->
                // 三种都开着（默认）时不读方案文件：什么都不写，同以前。
                listOf(fuzzyPart(schema, fuzzy) ?: return@write null) +
                    (if (typos.size == Typo.entries.size) emptyList<Part>() else typoParts(schema(), typos)) +
                    Part(LEAD_BIAS, leadLines(schema, lead))
            } || changed
        }
        // 导入、扩展里装的全拼方案只写模糊音；错字开关、首字偏好是按自带方案调的，不动它们。
        for (id in otherFullPinyinSchemas(rimeDir, fullPinyin.toSet())) {
            changed = write(rimeDir, id) { schema -> listOf(fuzzyPart(schema, fuzzy) ?: return@write null) } || changed
        }
        // 双拼：拼写规则先按全拼派生模糊音，再换成双拼编码（同雾凇双拼方案注释里的位置），所以两段写在同一个文件里。
        for (id in doublePinyinSchemas(context)) {
            changed = write(rimeDir, id) { schema ->
                listOf(doublePinyinPart(schema, doublePinyinFuzzy, abbreviation, id) ?: return@write null,
                    Part(CORRECTION, if (correction) listOf("\"$CORRECTION\": true") else emptyList()),
                    Part(PREEDIT_FORMAT, if (rawPreedit) rawPreeditLines(preeditFormatOf(schema()).orEmpty()) else emptyList())) +
                    auxCodeParts(if (auxCode == null) null else schema(), auxCode)
            } || changed
        }
        for (id in doublePinyinK14Schemas(context) + doublePinyinLayoutVariants(rimeDir)) {
            changed = write(rimeDir, id) { schema ->
                listOf(doublePinyinPart(schema, doublePinyinFuzzy, abbreviation, id) ?: return@write null)
            } || changed
        }
        // 笔画：关着（默认）时什么都不写，方案自己显示 h s p n z。
        changed = write(rimeDir, STROKE_SCHEMA) { _ -> strokeGlyphParts(strokeGlyphs) } || changed
        for (file in rimeDir.listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".schema.yaml")) continue
            val id = file.name.removeSuffix(".schema.yaml")
            val profile = InputProfiles.describe(id)
            if (profile.language != InputLanguage.CHINESE || profile.layout.kind != LayoutKind.T9) continue
            val groups = when (profile.scheme) {
                InputScheme.PINYIN -> fuzzy
                InputScheme.DOUBLE_PINYIN -> doublePinyinFuzzy
                else -> continue
            }
            val doubleScheme = if (profile.scheme == InputScheme.DOUBLE_PINYIN) DoublePinyinSchemes.find(id) ?: continue else null
            changed = write(rimeDir, id) { schema ->
                val lines = if (groups.isEmpty()) emptyList() else {
                    val algebra = algebraOf(schema()) ?: return@write null
                    val near = if (doubleScheme == null) groups.flatMap { g -> g.rules.map { it.replaceFirst("derive/", "fuzz/") } }
                        else NineKeyFuzzyReadings.doubleRules(doubleScheme, groups)
                    algebraLines(nineKeyRules(algebra, groups, near, t9Digits))
                }
                listOf(Part(ALGEBRA, lines), Part("t9/left_panel_mode",
                    if (doubleScheme != null && groups.isNotEmpty()) listOf("\"t9/left_panel_mode\": pinyin") else emptyList()),
                    Part(DIGIT_MARGIN, if (doubleScheme == null) digitLines(schema, digit) else emptyList()))
            } || changed
        }
        return changed
    }

    /**
     * 九键的整张拼写规则。[near] 放在数字映射之后：只有点选读音传入字母时才查近音，普通数字不扩大词图。
     * [digits] 开着时再把各组派生排在最前（同 26 键全拼、双拼的位置），先派生出近音拼音再换成数字，按数字就模糊。
     */
    internal fun nineKeyRules(algebra: List<String>, groups: Set<Fuzzy>, near: List<String>, digits: Boolean): List<String> =
        (if (digits) groups.flatMap { it.rules } else emptyList()) + algebra + near

    /**
     * 关掉的纠错写成补丁：只写方案里本来开着的键（雾凇和由它生成的字母排列、自建布局），14 键等没有这些键的方案不写，
     * 开着的不写（同以前，不触发重新部署）。停顿后的两个补查方案（漏键、打反）写成空名，引擎就不查。
     * 14 键（11-20）的停顿纠错一个索引管漏键、多键、打反三种（方案里有 cyime_pause_correction），三个开关由键盘
     * 按会话选项交给引擎各管各的，这里不写空名：否则关了漏键，多键、打反也一起没了。
     */
    internal fun typoParts(schema: String, enabled: Set<Typo>): List<Part> {
        val pauseKinds = Regex("(?m)^" + PAUSE_CORRECTION + ":").containsMatchIn(schema)
        return listOf(
            Triple(TRANSPOSITION, Typo.TRANSPOSITION, "false"),
            Triple(SWAP_SCHEMA, Typo.TRANSPOSITION, "\"\""),
            Triple(EXTRA, Typo.EXTRA, "false"),
            Triple(DOUBLED, Typo.OMISSION, "false"),
            Triple(OMISSION_SCHEMA, Typo.OMISSION, "\"\""),
        ).map { (key, typo, off) ->
            val present = Regex("(?m)^\\s*" + Regex.escape(key.substringAfterLast('/')) + ":[ \\t]*\\S").containsMatchIn(schema)
            val sessionOption = key == OMISSION_SCHEMA && pauseKinds
            Part(key, if (present && typo !in enabled && !sessionOption) listOf("\"$key\": $off") else emptyList())
        }
    }

    /**
     * 邻键纠错的强度（[TypoStrength.Lead]）：和出厂方案里的一样（默认档）时不写、不读方案文件；方案里没有这个键的
     * （14 键等不纠错的方案）不写。
     */
    internal fun leadLines(schema: () -> String, lead: TypoStrength.Lead): List<String> =
        if (lead == TypoStrength.LEAD_DEFAULT || !hasKey(schema(), LEAD_BIAS)) emptyList() else listOf("\"$LEAD_BIAS\": ${lead.bias}")

    /** 九键停一下后数字纠错的门槛（[TypoStrength.Digit]），规则同 [leadLines]。 */
    internal fun digitLines(schema: () -> String, digit: TypoStrength.Digit): List<String> =
        if (digit == TypoStrength.DIGIT_DEFAULT || !hasKey(schema(), DIGIT_MARGIN)) emptyList() else listOf("\"$DIGIT_MARGIN\": ${digit.margin}")

    private fun hasKey(schema: String, key: String): Boolean =
        Regex("(?m)^\\s*" + Regex.escape(key.substringAfterLast('/')) + ":[ \\t]*\\S").containsMatchIn(schema)

    private const val LEAD_BIAS = "t9/correction_lead_bias"
    private const val DIGIT_MARGIN = "t9/digit_edit_margin"
    private const val TRANSPOSITION = "translator/cyime_transposition_correction"
    private const val EXTRA = "translator/cyime_extra_letter_correction"
    private const val DOUBLED = "translator/cyime_doubled_letter_correction"
    private const val SWAP_SCHEMA = "cyime_edit_schema"
    private const val OMISSION_SCHEMA = "cyime_omission_schema"
    private const val PAUSE_CORRECTION = "cyime_pause_correction"

    private const val FILTERS = "engine/filters"
    private const val DEPENDENCIES = "schema/dependencies"
    /** 标记这个方案的辅助码开着、用的哪张表；输入法读它决定要不要把 ` 送进 Rime、Shift 键换不换成「辅」（RimeEngine.auxCodeActive）。 */
    internal const val AUX_CODE_MARK = "cyime_aux_code/table"
    /** 雾凇双拼方案原有的辅码筛选：查部件拼音。辅助码开着时换成形码表。 */
    internal const val RADICAL_SEARCH = "lua_filter@*search@radical_pinyin"

    /**
     * 辅助码的三个补丁键。[schema] 为 null（辅助码关着）或方案里没有雾凇的辅码筛选（自己改过的方案）时三个都不写，方案保持原样。
     * 滤镜整张表照抄，只把部件拼音那一项换成形码表；依赖表照抄再加上码表方案（Rime 部署时编出码表）。
     */
    internal fun auxCodeParts(schema: String?, code: AuxCode?): List<Part> {
        val filters = schema?.let { stringListAt(it, "engine", "filters") }
        val usable = code != null && filters != null && RADICAL_SEARCH in filters
        if (!usable) return listOf(Part(FILTERS, emptyList()), Part(DEPENDENCIES, emptyList()), Part(AUX_CODE_MARK, emptyList()))
        code!!
        val dependencies = stringListAt(schema!!, "schema", "dependencies").orEmpty()
        return listOf(
            Part(FILTERS, listLines(FILTERS, filters!!.map { if (it == RADICAL_SEARCH) "lua_filter@*search@${code.schemaId}" else it })),
            Part(DEPENDENCIES, listLines(DEPENDENCIES, (dependencies.filterNot { it in AuxCode.schemaIds } + code.schemaId).distinct())),
            Part(AUX_CODE_MARK, listOf("\"$AUX_CODE_MARK\": ${code.id}")),
        )
    }

    private fun listLines(key: String, items: List<String>): List<String> =
        listOf("\"$key\":") + items.map { "  - " + quote(it) }

    /** 方案里 [path] 这张字符串表；读不出（没有、不是表、有不是字符串的项）时为 null。 */
    internal fun stringListAt(schema: String, vararg path: String): List<String>? {
        var node: YamlNode = runCatching { SchemaManager.yaml.parseToYamlNode(schema) }.getOrNull() ?: return null
        for (key in path) node = (node as? YamlMap)?.node(key) ?: return null
        return (node as? YamlList)?.items?.map { (it as? YamlScalar)?.content ?: return null }
    }

    private const val ALGEBRA = "speller/algebra"
    private const val PREEDIT_FORMAT = "translator/preedit_format"
    private const val COMMENT_FORMAT = "translator/comment_format"
    internal const val STROKE_SCHEMA = "stroke"
    /** 横竖撇点折换成康熙部首，折用键面上的乛（#412）；和 lua 里的 STROKE_GLYPHS 一致。 */
    internal const val STROKE_GLYPH_XLIT = "xlit/hspnz/⼀⼁⼃⼂乛/"

    /** 笔画编码显示成笔画时的补丁：编码和候选注释（剩余笔画）都换；comment_format 里方案原有的 xform/~// 照样留着。 */
    internal fun strokeGlyphParts(glyphs: Boolean): List<Part> = listOf(
        Part(PREEDIT_FORMAT, if (glyphs) listOf("\"$PREEDIT_FORMAT\":", "  - " + quote(STROKE_GLYPH_XLIT)) else emptyList()),
        Part(COMMENT_FORMAT, if (glyphs) listOf("\"$COMMENT_FORMAT\":", "  - " + quote("xform/~//"),
            "  - " + quote(STROKE_GLYPH_XLIT)) else emptyList()),
    )
    /** 原来的编码显示规则抄在这里：希腊字母（cyime_greek_letters.lua）按它把双拼键换回全拼再比对名字。 */
    internal const val DOUBLE_PINYIN_READING = "cyime_double_pinyin_reading"
    private const val CORRECTION = "translator/enable_correction"

    /** 补丁里的一个键：[lines] 为空表示不写这个键。 */
    internal class Part(val key: String, val lines: List<String>)

    /** 读不出方案的拼写规则时为 null（整个文件保持原样）。 */
    private fun fuzzyPart(schema: () -> String, fuzzy: Set<Fuzzy>): Part? =
        if (fuzzy.isEmpty()) Part(ALGEBRA, emptyList()) else algebraOf(schema())?.let { Part(ALGEBRA, fuzzyLines(it, fuzzy)) }

    /** 双拼 26 键及其自建布局版本的拼写规则：模糊音和简拼都关着时不写。 */
    private fun doublePinyinPart(schema: () -> String, fuzzy: Set<Fuzzy>, abbreviation: Boolean, id: String): Part? {
        if (!abbreviation) return fuzzyPart(schema, fuzzy)
        val algebra = algebraOf(schema()) ?: return null
        return Part(ALGEBRA, doublePinyinLines(algebra, fuzzy, abbreviationRules(DoublePinyinSchemes.find(id))))
    }

    /**
     * 双拼简拼：按拼音取每个字的第一个字母（zh、ch、sh 取 z、c、s，同全拼的简拼），也取本方案 zh、ch、sh 所在的键
     * （小鹤 v、i、u，同 Rime 双拼方案注释里的简拼）。新拼写先带上 #，双拼规则就不会把 a、o、e 当成零声母音节改掉；
     * 双拼规则（含自建布局的合并键派生）做完后去掉 #。abbrev 派生的拼写权重低，完整的双拼编码仍排在前面。
     * a、o、e 这种一个字母的音节也算（用户 11-19：开了简拼打 a 出不来啊、打 uia 出不来是啊）：a 本来就是 ai、an 等的简拼，
     * 只是啊自己不在里面，开着简拼时单打 a 只查简拼、不再补全成 aa，啊就没了。
     * 返回（排在双拼规则前面的，接在最后的）。
     */
    internal fun abbreviationRules(scheme: DoublePinyinScheme?): Pair<List<String>, List<String>> =
        (listOf("abbrev/^([a-z]).*$/\$1#/") + listOf("zh", "ch", "sh").mapNotNull { initial ->
            scheme?.initials?.get(initial)?.let { key -> "abbrev/^$initial.+$/$key#/" }
        }) to listOf("xform/#$//")

    /** 模糊音在最前，简拼规则夹在原有规则两头。 */
    internal fun doublePinyinLines(algebra: List<String>, fuzzy: Set<Fuzzy>, abbreviation: Pair<List<String>, List<String>>): List<String> =
        algebraLines(fuzzy.flatMap { it.penalized } + abbreviation.first + algebra + abbreviation.second)

    /** 补丁内容：模糊音派生排在原有拼写规则前面（同雾凇注释的位置），整张规则表一起写。 */
    internal fun fuzzyLines(algebra: List<String>, items: Set<Fuzzy>): List<String> = algebraLines(items.flatMap { it.penalized } + algebra)
    private fun algebraLines(rules: List<String>): List<String> =
        listOf("\"speller/algebra\":") + rules.map { "  - " + quote(it) }

    /**
     * 编码显示双拼键：清空编码显示规则，Rime 就显示打的键，音节之间照常隔开（ul pb）；原来的规则抄一份给希腊字母用。
     * 两行同属一个补丁键：方案自定义文件自己改了编码显示规则时整段不写。
     */
    internal fun rawPreeditLines(format: List<String>): List<String> = listOf("\"$PREEDIT_FORMAT\": []") +
        if (format.isEmpty()) emptyList() else listOf("\"$DOUBLE_PINYIN_READING\":") + format.map { "  - " + quote(it) }

    internal fun preeditFormatOf(schema: String): List<String>? {
        val root = runCatching { SchemaManager.yaml.parseToYamlNode(schema) }.getOrNull() as? YamlMap ?: return null
        val list = ((root.node("translator") as? YamlMap)?.node("preedit_format") as? YamlList) ?: return null
        return list.items.map { (it as? YamlScalar)?.content ?: return null }
    }

    internal fun algebraOf(schema: String): List<String>? {
        val root = runCatching { SchemaManager.yaml.parseToYamlNode(schema) }.getOrNull() as? YamlMap ?: return null
        val list = ((root.node("speller") as? YamlMap)?.node("algebra") as? YamlList) ?: return null
        return list.items.map { (it as? YamlScalar)?.content ?: return null }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * [parts] 返回 null 表示读不出方案，保持原样；全空表示去掉自己的那一段。
     * 方案自定义文件在我们这段之外已经改了同一个键时不写这个键，以用户自己写的为准；别的键照写。
     */
    private fun write(rimeDir: File, id: String, parts: (schema: () -> String) -> List<Part>?): Boolean {
        val schema = File(rimeDir, "$id.schema.yaml").takeIf { it.isFile } ?: return false
        val custom = File(rimeDir, "$id.custom.yaml")
        val original = if (custom.isFile) custom.readText() else ""
        val outside = withoutBlock(original)
        val wanted = (parts { schema.readText() } ?: return false)
            .filterNot { it.lines.isNotEmpty() && outside.contains(it.key) }.flatMap { it.lines }
        val updated = patch(original, wanted) ?: return false
        if (updated == original) return false
        if (updated.lineSequence().none { it.isNotBlank() && !it.trimStart().startsWith('#') && it.trim() != "patch:" }) {
            // 只剩空的 patch: 时 librime 会当成无效补丁：文件是我们写的这一段撑起来的，直接删掉。
            return custom.delete()
        }
        CellDictionaryStore.writeAtomic(custom, updated)
        return true
    }

    private val managed = Regex("(?m)^[ \\t]*${Regex.escape(START)}\\r?\\n[\\s\\S]*?^[ \\t]*${Regex.escape(END)}(?:\\r?\\n|$)")

    internal fun withoutBlock(original: String): String = original.replace(managed, "")

    /** 去掉旧的一段，[lines] 不空时紧接在 `patch:` 下面写新的一段。写出的不是合法 YAML 时返回 null。 */
    internal fun patch(original: String, lines: List<String>): String? {
        val starts = original.lineSequence().count { it.trim() == START }
        if (starts != original.lineSequence().count { it.trim() == END } || starts > 1) return null
        val base = withoutBlock(original)
        if (lines.isEmpty()) return base
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val patchLine = Regex("(?m)^patch:[ \\t]*(?:#[^\\r\\n]*)?(?:\\r?\\n|$)").find(base)
        val indentation = patchLine?.let { line ->
            Regex("(?m)^([ ]+)\\S").find(base.substring(line.range.last + 1))?.groupValues?.get(1)
        } ?: "  "
        val block = "$indentation$START$newline" + lines.joinToString("") { "$indentation$it$newline" } +
            "$indentation$END$newline"
        val output = if (patchLine == null) {
            val head = base.trimEnd().removeSuffix("...").trimEnd()
            (if (head.isEmpty()) "" else head + newline) + "patch:$newline$block"
        } else {
            val at = patchLine.range.last + 1
            val before = base.substring(0, at)
            (if (before.endsWith("\n")) before else before + newline) + block + base.substring(at)
        }
        return output.takeIf { runCatching { SchemaManager.yaml.parseToYamlNode(it) }.isSuccess }
    }
}
