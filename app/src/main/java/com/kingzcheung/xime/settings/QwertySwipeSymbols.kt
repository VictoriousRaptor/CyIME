package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 角标默认是固定字符，印什么上屏什么；打开了「跟随全角半角」的格子按全角或半角换成对应写法（见 [width]）。
 * 中文：上角标是数字和常用全角符号，下角标是半角符号；英文上角标同位置换半角，下角标放补充符号。
 * 开没开数字行、显示哪边角标一共六套布局（[CornerVariant]），各自保存、各有预设。
 */
internal object QwertySwipeSymbols {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val keys = rows.joinToString("")
    private fun preset(vararg values: String) = keys.map(Char::toString).zip(values.toList()).toMap()
    // 用户 11-10 定：用他手机上存的「new」这一套作中文默认。
    val up = preset(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "｀", "～", "…", "—", "（", "）", "【", "】", "｜",
        "！", "？", "：", "；", "《", "》", "、")
    val down = preset(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "`", "~", ".", "_", "'", "\"", "[", "]", "|",
        "-", "=", ":", ";", "<", ">", "/")
    // 英文上角标和中文同一位置，换成对应的半角（没有半角的 … · ¥ 照旧）：中文上滑哪个，英文同一个键就是它的半角。
    val englishUp = preset(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "`", "~", "…", "-", "(", ")", "[", "]", "|",
        "!", "?", ":", ";", "<", ">", "\\")
    // 英文下角标作补充：中文下角标里不和上角标重复的留在原位，其余换成不常用符号（用户 10-19 定）。
    val englishDown = preset(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "≈", "+", "_", "'", "=", "|", "{", "}", "/",
        "\"", "`", "×", "÷", "≤", "≥", "°")
    private fun firstRow(vararg values: String) = rows[0].map(Char::toString).zip(values.toList()).toMap()
    val firstRowKeys: Set<String> = rows[0].map(Char::toString).toSet()
    private fun lowerRows(vararg values: String) = (rows[1] + rows[2]).map(Char::toString).zip(values.toList()).toMap()
    // 开了「数字行」（用户 11-15：开数字行时 Q 到 P 这一排什么都没放）：数字已经在上面一排，第一排下方照旧是 ! @ # …
    // （位置和不开数字行时一样），上方换成第二、三排没有的引号和 · + \ { } ?。第二、三排不变。
    private val numberRowUp = firstRow("“", "”", "‘", "’", "·", "+", "\\", "{", "}", "?")
    // 英文第二、三排已经有 ? \ + { }，第一排上方放弯引号、圆点、连接号、破折号和货币。
    private val numberRowEnglishUp = firstRow("“", "”", "‘", "’", "•", "–", "—", "€", "£", "¥")
    // 只显示一边角标时每个键只有一个符号：不开数字行时第一排是数字，第二、三排是 16 个最常用的标点
    // （中文这 16 个里有全角半角两种写法的默认跟随全角半角，见 followPreset）。
    private val digits = firstRow("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val singleLower = lowerRows("@", "～", "…", "—", "（", "）", "【", "】", "“", "！", "？", "：", "；", "《", "》", "、")
    private val singleEnglishLower = lowerRows("@", "~", "…", "-", "(", ")", "[", "]", "\"", "!", "?", ":", ";", "<", ">", "/")
    // 开数字行又只显示一边（用户 11-15 定稿）：数字在数字行上，第一排放日常常用、第二三排没有的符号，固定不跟随全角半角；
    // 写代码的符号在看不见的那一边，照样能滑出。中文引号成对放在 Q W、E R，L 原来的 “ 换成 -，免得重复。
    private val singleNumberRowFirst = firstRow("“", "”", "‘", "’", "·", "￥", "#", "%", "&", "*")
    private val singleNumberRowLower = mapOf("l" to "-")
    // 英文：撇号最常用放 Q，接着 # $ % & * + = \ _。
    private val singleNumberRowEnglishFirst = firstRow("'", "#", "$", "%", "&", "*", "+", "=", "\\", "_")
    // 11-01 到 11-15 的数字行预设：升级时把旧的共用修改分到各套布局里，存着的这些格子算预设，不带过去（见 adoptVariants）。
    private val oldNumberRowUp = listOf(firstRow("「", "」", "『", "』", "℃", "←", "→", "√", "★", "?"),
        firstRow("「", "」", "『", "』", "℃", "←", "→", "√", "★", "※"), firstRow("", "", "", "", "", "", "", "", "", "?"))
    private val oldNumberRowDown = listOf(firstRow("+", "×", "÷", "≈", "≤", "≥", "°", "\\", "{", "}"),
        firstRow("+", "×", "÷", "≈", "≤", "≥", "°", "|", "{", "}"), firstRow("+", "", "", "", "", "", "", "\\", "{", "}"))
    private val oldNumberRowEnglishDown = listOf(firstRow("€", "£", "±", "≠", "∞", "©", "®", "™", "§", "‰"))
    private fun oldNumberRow(direction: String, english: Boolean, key: String, saved: String?): Boolean = saved != null &&
        (if (direction == "up") oldNumberRowUp else if (english) oldNumberRowEnglishDown else oldNumberRowDown).any { it[key] == saved }

    /** 只显示一边时，显示的那一边放的一套（每键一个符号）。 */
    private fun single(english: Boolean, numberRow: Boolean) =
        (if (!numberRow) digits else if (english) singleNumberRowEnglishFirst else singleNumberRowFirst) +
            (if (english) singleEnglishLower else singleLower) +
            (if (numberRow && !english) singleNumberRowLower else emptyMap())
    /** [variant] 这一套的上方预设。只显示下方的两套里，上方不显示但照样能滑出，沿用上下都显示时的上方。 */
    fun up(english: Boolean, variant: CornerVariant): Map<String, String> =
        if (variant.showsUp && !variant.showsDown) single(english, variant.numberRow)
        else (if (english) englishUp else up) + (if (!variant.numberRow) emptyMap() else if (english) numberRowEnglishUp else numberRowUp)
    /** [variant] 这一套的下方预设；不显示的一边同 [up]。开数字行时第一排下方照旧是 ! @ # …。 */
    fun down(english: Boolean, variant: CornerVariant): Map<String, String> =
        if (variant.showsDown && !variant.showsUp) single(english, variant.numberRow)
        else if (english) englishDown else down
    fun up(english: Boolean, numberRow: Boolean = false) = up(english, if (numberRow) CornerVariant.NR_BOTH else CornerVariant.BOTH)
    fun down(english: Boolean, numberRow: Boolean = false) = down(english, if (numberRow) CornerVariant.NR_BOTH else CornerVariant.BOTH)

    // 「跟随全角半角」：一格符号有全角、半角两种写法时可以打开，全角时出全角那个，半角时出半角那个（键上印的跟着换）。
    // 对应关系照用户自己的上下角标配对（— 对 _，、对 /）；没有另一种写法的符号（… @ # 等）不变。
    private val toHalf = mapOf("，" to ",", "。" to ".", "！" to "!", "？" to "?", "：" to ":", "；" to ";", "（" to "(", "）" to ")",
        "【" to "[", "】" to "]", "《" to "<", "》" to ">", "、" to "/", "～" to "~", "｀" to "`", "｜" to "|", "—" to "_",
        "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'", "￥" to "$", "「" to "{", "」" to "}")
    private val toFull = mapOf("," to "，", "." to "。", "!" to "！", "?" to "？", ":" to "：", ";" to "；", "(" to "（", ")" to "）",
        "[" to "【", "]" to "】", "<" to "《", ">" to "》", "/" to "、", "\\" to "、", "~" to "～", "`" to "｀", "|" to "｜", "_" to "—",
        "\"" to "“", "'" to "‘", "$" to "￥", "{" to "「", "}" to "」", "^" to "…")
    fun hasWidthPair(text: String) = text in toHalf || text in toFull
    /** [text] 在全角（[full]）或半角时的写法；没有另一种写法的原样返回。 */
    fun width(text: String, full: Boolean): String = (if (full) toFull[text] else toHalf[text]) ?: text
    /** 这一套默认跟随全角半角的格子：中文只显示一边时，显示的那一边里有两种写法的标点。英文不分全角半角。 */
    fun followPreset(english: Boolean, variant: CornerVariant): Set<String> {
        if (english || variant.showsUp == variant.showsDown) return emptySet()
        val direction = if (variant.showsUp) "up" else "down"
        return single(false, variant.numberRow).filterKeys { it !in firstRowKeys }.filterValues(::hasWidthPair)
            .keys.map { "$direction.$it" }.toSet()
    }
    private fun followKey(english: Boolean, variant: CornerVariant) = "qwerty_symbol_follow_${if (english) "en" else "zh"}_${variant.id}"
    /** 用户改过的「跟随全角半角」：格子 → 开或关；没改过的格子照 [followPreset]。 */
    fun loadFollows(context: Context, english: Boolean, variant: CornerVariant): Map<String, Boolean> =
        SettingsPreferences.getPrefsPublic(context).getStringSet(followKey(english, variant), null).orEmpty()
            .mapNotNull { entry -> entry.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1] == "1") } }.toMap()
    fun follows(english: Boolean, variant: CornerVariant, overrides: Map<String, Boolean>): Set<String> =
        if (english) emptySet() else followPreset(english, variant) - overrides.filterValues { !it }.keys + overrides.filterValues { it }.keys

    // 2026-10-12 至 10-16 的预设：首次换成固定字符预设时，把当时看到的中文角标另存一份。
    private val v1012Up = preset(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "`", "=", "…", "—", "√", "、", "[", "]", "\\",
        "'", "\"", ":", ";", "<", ">", "?")
    private val v1012Down = preset(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "~", "¥", "·", "_", "°", "！", "{", "}", "|",
        "+", "-", "×", "÷", ",", ".", "/")
    // 2026-10-12 之前的预设，只用于把升级前看到的布局另存为「旧布局」。
    private val legacyUp = keys.map(Char::toString).zip(listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "@", "-", "*", "_", "[", "]", "(", ")", "\\",
        "'", "\"", ":", ";", "!", "?", "/")).toMap()
    private val legacyDown = keys.map(Char::toString).zip(listOf(
        "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
        "`", "~", "·", "—", "…", "°", "{", "}", "|",
        "=", "+", "-", "×", "÷", "<", ">")).toMap()
    private fun prefix(english: Boolean, variant: CornerVariant = CornerVariant.BOTH) =
        "qwerty_symbols_${if (english) "en" else "zh"}_" + if (variant.id.isEmpty()) "" else "${variant.id}:"

    const val LEGACY_VERSION = "旧布局"
    private const val VERSIONS_KEY = "qwerty_symbol_versions"
    /** 英文26键自己的版本表，和中文分开存，两边的版本列表互不混入。 */
    private const val ENGLISH_VERSIONS_KEY = "qwerty_symbol_versions_en"
    private fun versionsKey(english: Boolean) = if (english) ENGLISH_VERSIONS_KEY else VERSIONS_KEY
    private const val LEGACY_SAVED_KEY = "qwerty_symbol_versions_legacy_saved"
    const val V1012_VERSION = "10-12布局"
    private const val FIXED_PRESET_KEY = "qwerty_symbol_fixed_preset_adopted"
    private const val VARIANTS_ADOPTED_KEY = "qwerty_symbol_variants_adopted"
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'
    /** 一个版本的 52 个位置：先全部上方，再全部下方。 */
    val slotIds: List<String> get() = listOf("up", "down").flatMap { direction -> keys.map { "$direction.$it" } }

    /** 用户另存的整套角标，按保存顺序；[english] 为 true 时是英文26键的版本。 */
    fun versions(context: Context, english: Boolean = false): Map<String, Map<String, String>> {
        val raw = SettingsPreferences.getPrefsPublic(context).getString(versionsKey(english), null).orEmpty()
        val ids = slotIds
        return raw.split(RECORD).mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size != ids.size + 1 || fields[0].isEmpty()) null
            else fields[0] to ids.zip(fields.drop(1)).toMap()
        }.toMap(LinkedHashMap<String, Map<String, String>>())
    }

    fun saveVersion(context: Context, name: String, values: Map<String, String>, english: Boolean = false) =
        writeVersions(context, versions(context, english) + (versionName(name) to values), english)

    fun deleteVersion(context: Context, name: String, english: Boolean = false) =
        writeVersions(context, versions(context, english) - name, english)

    fun versionName(name: String) = name.filter { it != RECORD && it != FIELD }.trim()

    private fun writeVersions(context: Context, all: Map<String, Map<String, String>>, english: Boolean = false) {
        val ids = slotIds
        val raw = all.entries.joinToString(RECORD.toString()) { (name, values) ->
            (listOf(name) + ids.map { values[it].orEmpty().filter { c -> c != RECORD && c != FIELD } })
                .joinToString(FIELD.toString())
        }
        check(SettingsPreferences.getPrefsPublic(context).edit().putString(versionsKey(english), raw).commit()) { "无法保存布局版本" }
    }

    /**
     * 升级到新预设时只执行一次：把升级前实际看到的中文角标（用户修改 > 自定义 YAML > 旧预设）
     * 另存为「旧布局」，再清掉中文修改让新预设生效。旧布局随时可在符号设置页载入。
     */
    fun preserveLegacyLayout(context: Context, custom: Map<String, KeyGestureConfig>) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(LEGACY_SAVED_KEY, false)) return
        val overrides = load(context, false)
        fun configured(def: GestureDef?) = def?.let { it.value.ifEmpty { it.label } }
        val legacy = buildMap {
            for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
                put("up.$key", overrides["up.$key"] ?: configured(custom[key]?.swipeUp) ?: legacyUp.getValue(key))
                put("down.$key", overrides["down.$key"] ?: configured(custom[key]?.swipeDown) ?: legacyDown.getValue(key))
            }
        }
        if (LEGACY_VERSION !in versions(context)) saveVersion(context, LEGACY_VERSION, legacy)
        val edit = prefs.edit()
        for (id in slotIds) edit.remove(prefix(false) + id)
        check(edit.putBoolean(LEGACY_SAVED_KEY, true).commit()) { "无法保存旧布局" }
    }

    /**
     * 换成固定字符预设时只执行一次：把当时看到的中文角标另存为「10-12布局」，
     * 再清掉中文和英文的修改，让新的中英预设生效。
     */
    fun adoptFixedPreset(context: Context, custom: Map<String, KeyGestureConfig>) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(FIXED_PRESET_KEY, false)) return
        val overrides = load(context, false)
        fun configured(def: GestureDef?) = def?.let { it.value.ifEmpty { it.label } }
        val previous = buildMap {
            for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
                put("up.$key", overrides["up.$key"] ?: configured(custom[key]?.swipeUp) ?: v1012Up.getValue(key))
                put("down.$key", overrides["down.$key"] ?: configured(custom[key]?.swipeDown) ?: v1012Down.getValue(key))
            }
        }
        if (V1012_VERSION !in versions(context)) saveVersion(context, V1012_VERSION, previous)
        val edit = prefs.edit()
        for (id in slotIds) { edit.remove(prefix(false) + id); edit.remove(prefix(true) + id) }
        check(edit.putBoolean(FIXED_PRESET_KEY, true).commit()) { "无法保存10-12布局" }
    }

    /**
     * 六套布局分开保存时只执行一次：以前只有一份修改，开不开数字行、显示哪边角标都共用。把它带到各套里，改过的格子不丢：
     * 开数字行的三套带上全部修改（第一排存着的数字、! @ # … 和旧数字行预设算预设，不带）；
     * 只显示一边的四套只带不显示的那一边（显示的那一边是新排的一套）。
     */
    fun adoptVariants(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(VARIANTS_ADOPTED_KEY, false)) return
        val edit = prefs.edit()
        for (english in listOf(false, true)) {
            val base = load(context, english)
            val numberRow = base.filter { (id, saved) ->
                val direction = id.substringBefore('.')
                val key = id.substringAfter('.')
                key !in firstRowKeys || !(saved == (if (direction == "up") up(english) else down(english))[key] ||
                    oldNumberRow(direction, english, key, saved))
            }
            for (variant in CornerVariant.entries) {
                if (variant == CornerVariant.BOTH) continue
                val source = if (variant.numberRow) numberRow else base
                val carried = if (variant.showsUp && variant.showsDown) source
                    else source.filterKeys { it.startsWith(if (variant.showsUp) "down." else "up.") }
                for ((id, saved) in carried) edit.putString(prefix(english, variant) + id, saved)
            }
        }
        check(edit.putBoolean(VARIANTS_ADOPTED_KEY, true).commit()) { "无法分开保存各套角标" }
    }

    fun load(context: Context, english: Boolean, variant: CornerVariant = CornerVariant.BOTH): Map<String, String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val prefix = prefix(english, variant)
        return buildMap {
            // buildMap has its own empty `keys`; qualify the fixed alphabet explicitly.
            for (key in QwertySwipeSymbols.keys) for (direction in listOf("up", "down")) {
                val id = "$direction.$key"
                if (prefs.contains(prefix + id)) put(id, prefs.getString(prefix + id, "").orEmpty())
            }
        }
    }

    /** [follows] 不为 null 时一并写入这一套里改过的「跟随全角半角」（格子 → 开或关）。 */
    fun save(context: Context, english: Boolean, values: Map<String, String>, swipes: Map<String, String>? = null,
        variant: CornerVariant = CornerVariant.BOTH, follows: Map<String, Boolean>? = null) {
        val edit = SettingsPreferences.getPrefsPublic(context).edit()
        val prefix = prefix(english, variant)
        for (key in keys) for (direction in listOf("up", "down")) {
            val id = "$direction.$key"
            val value = values[id]
            if (value == null) edit.remove(prefix + id) else edit.putString(prefix + id, value)
        }
        if (swipes != null) edit.putStringSet(LetterSwipeBindings.key(english),
            swipes.map { (slot, value) -> "$slot\t$value" }.toSet())
        if (follows != null) edit.putStringSet(followKey(english, variant),
            follows.map { (id, on) -> "$id=${if (on) 1 else 0}" }.toSet())
        check(edit.commit()) { "无法保存符号设置" }
        KeysConfigHelper.loadConfig(context)
    }

    /**
     * [variant] 是键盘现在的情形（开没开数字行、显示哪边角标），[overrides] 是这一套里用户改过的格子。
     * [fullWidth] 不为 null 时，[follows] 里的格子按全角或半角换成对应写法；null（设置页）时给出存着的原样。
     */
    fun apply(key: String, original: KeyGestureConfig?, overrides: Map<String, String>,
        custom: KeyGestureConfig?, english: Boolean = false, numberRow: Boolean = false,
        variant: CornerVariant = if (numberRow) CornerVariant.NR_BOTH else CornerVariant.BOTH,
        follows: Set<String> = emptySet(), fullWidth: Boolean? = null): KeyGestureConfig? {
        if (key !in up) return original
        fun gesture(direction: String, preset: String, configured: GestureDef?): GestureDef {
            val override = overrides["$direction.$key"]
            if (override == null && configured != null) return configured
            val stored = override ?: preset
            val value = if (fullWidth != null && "$direction.$key" in follows) width(stored, fullWidth) else stored
            return GestureDef(label = value, value = value,
                display = if (direction == "up") original?.swipeUp?.display ?: DisplayMode.BOTH else DisplayMode.BOTH,
                action = if (value.isEmpty()) com.kingzcheung.xime.keyboard.GestureAction.NONE
                    else com.kingzcheung.xime.keyboard.GestureAction.COMMIT)
        }
        return (original ?: KeyGestureConfig()).copy(
            swipeUp = gesture("up", up(english, variant).getValue(key), custom?.swipeUp),
            swipeDown = gesture("down", down(english, variant).getValue(key), custom?.swipeDown))
    }
}

/**
 * 一套角标对应的键盘情形（用户 11-15）：开没开数字行 × 上下角标显示哪边，一共六套，各自保存、各有预设。
 * 上下角标都不显示时用「上下都显示」那一套（看不见，照样能滑出）。
 */
internal enum class CornerVariant(val id: String, val numberRow: Boolean, val showsUp: Boolean, val showsDown: Boolean) {
    BOTH("", false, true, true), UP("up", false, true, false), DOWN("down", false, false, true),
    NR_BOTH("nr", true, true, true), NR_UP("nr_up", true, true, false), NR_DOWN("nr_down", true, false, true);

    val label: String get() = (if (numberRow) "开数字行" else "不开数字行") + "，" +
        (if (showsUp && showsDown) "上下角标都显示" else if (showsUp) "只显示上方角标" else "只显示下方角标")

    companion object {
        fun of(numberRow: Boolean, up: Boolean, down: Boolean): CornerVariant = entries.first {
            it.numberRow == numberRow && (if (up == down) it.showsUp && it.showsDown else it.showsUp == up && it.showsDown == down)
        }
    }
}
