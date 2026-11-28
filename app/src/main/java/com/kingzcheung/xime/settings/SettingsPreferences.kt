package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.plugin.core.runtime.PluginManager

object SettingsPreferences {
    fun isSmsCodeEnabled(context: Context): Boolean = getPrefs(context).getBoolean("sms_code_enabled", false)
    fun setSmsCodeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("sms_code_enabled", enabled).apply()
    }
    /** 验证码发到已配对的电脑、手机、平板（默认关）。 */
    fun isSmsCodeSyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean("sms_code_sync_enabled", false)
    fun setSmsCodeSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("sms_code_sync_enabled", enabled).apply()
    }
    private const val PREFS_NAME = "kime_settings"
    const val KEY_ROUNDED_KEYBOARD_BOTTOM = "rounded_keyboard_bottom"
    fun roundedKeyboardBottom(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ROUNDED_KEYBOARD_BOTTOM, false)
    fun setRoundedKeyboardBottom(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ROUNDED_KEYBOARD_BOTTOM, enabled).apply()
    }
    /** 固定键盘顶部两角是否圆角；默认开（原样）。悬浮键盘是卡片，四角始终圆。 */
    const val KEY_ROUNDED_KEYBOARD_TOP = "rounded_keyboard_top"
    fun roundedKeyboardTop(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ROUNDED_KEYBOARD_TOP, true)
    fun setRoundedKeyboardTop(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ROUNDED_KEYBOARD_TOP, enabled).apply()
    }
    /**
     * 玻璃「柔化顶部边缘」：固定键盘上沿一小段里模糊和底色渐变出来，不再一刀切；默认关（原样）。
     * 和顶部圆角互斥（圆角缺口里没法渐变），两个开关在设置页互锁。
     */
    const val KEY_GLASS_SOFT_TOP_EDGE = "glass_soft_top_edge"
    fun glassSoftTopEdge(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_GLASS_SOFT_TOP_EDGE, false)
    fun setGlassSoftTopEdge(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GLASS_SOFT_TOP_EDGE, enabled).apply()
    }
    /** 玻璃「液态玻璃」：按键边缘一道细亮线，按住变亮；默认关（原样）。见 ui/theme/LiquidGlass.kt。 */
    const val KEY_GLASS_LIQUID = "glass_liquid"
    fun glassLiquid(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_GLASS_LIQUID, false)
    fun setGlassLiquid(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GLASS_LIQUID, enabled).apply()
    }
    /**
     * 「液态玻璃强度」：standard（默认，就是加这一项之前的样子）／strong／strongest，
     * 见 ui/theme/LiquidGlassStrength.kt。只在液态玻璃开着时起作用；没存过或认不得的值按 standard。
     */
    const val KEY_GLASS_LIQUID_STRENGTH = "glass_liquid_strength"
    const val GLASS_LIQUID_STRENGTH_DEFAULT = "standard"
    fun glassLiquidStrength(context: Context): String =
        getPrefs(context).getString(KEY_GLASS_LIQUID_STRENGTH, null) ?: GLASS_LIQUID_STRENGTH_DEFAULT
    fun setGlassLiquidStrength(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_GLASS_LIQUID_STRENGTH, id).apply()
    }
    const val KEY_PUNCTUATION_FULL_WIDTH = "punctuation_full_width"
    fun punctuationFullWidth(context: Context, default: Boolean): Boolean =
        getPrefs(context).getBoolean(KEY_PUNCTUATION_FULL_WIDTH, default)
    fun setPunctuationFullWidth(context: Context, full: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PUNCTUATION_FULL_WIDTH, full).apply()
    }

    /** 输入成对符号：点开头的引号、括号、书名号时连结尾一起上屏，光标停在中间；默认关（用户 10-30：不成对是有意的设计）。 */
    const val KEY_PAIRED_SYMBOLS = "paired_symbols"
    fun isPairedSymbolsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_PAIRED_SYMBOLS, false)
    fun setPairedSymbolsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PAIRED_SYMBOLS, enabled).apply()
    }

    /**
     * 选完符号自动返回：在符号页点一个符号后回到打字键盘；默认关 = 留在符号页接着点（原来的样子）。
     * 只管符号页的符号格子；删除、分类、数字键照旧（群友 11-24：选完要点「中文」才回去）。
     */
    const val KEY_SYMBOL_RETURN_AFTER_PICK = "symbol_return_after_pick"
    fun isSymbolReturnAfterPickEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SYMBOL_RETURN_AFTER_PICK, false)
    fun setSymbolReturnAfterPickEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYMBOL_RETURN_AFTER_PICK, enabled).apply()
    }

    /**
     * 回车发送：应用自己把回车设成发送时（微信「回车键发送消息」等），普通回车直接发送、键面写发送；
     * 默认关 = 回车换行、Ctrl+回车发送（用户 11-05）。见 EnterKeyAction。
     */
    const val KEY_ENTER_SENDS = "enter_sends"
    fun isEnterSendsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ENTER_SENDS, false)
    fun setEnterSendsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENTER_SENDS, enabled).apply()
    }

    /**
     * 标点上屏首选：中文打字中点逗号、句号这类断句标点时，先上屏首选候选再接标点（nihao 点逗号得到「你好，」）。
     * 默认关：关着时照旧先上屏打的字母（Gboard 规则，用户 10-24），这是别的用户要的选项（11-02）。
     */
    const val KEY_PUNCTUATION_COMMITS_CANDIDATE = "punctuation_commits_candidate"
    fun isPunctuationCommitsCandidateEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PUNCTUATION_COMMITS_CANDIDATE, false)
    fun setPunctuationCommitsCandidateEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PUNCTUATION_COMMITS_CANDIDATE, enabled).apply()
    }

    /**
     * 用户是否显式选择过标点宽度。
     *
     * 未选择时按全角（中文默认），引擎只清掉 ascii_punct 与之对齐（#460）；
     * 只有用户显式选择过，全角/半角才是唯一权威状态，切换方案/中英时必须回写。
     */
    fun hasPunctuationFullWidth(context: Context): Boolean =
        getPrefs(context).contains(KEY_PUNCTUATION_FULL_WIDTH)

    private const val KEY_CURRENT_SCHEMA = "current_schema"
    /** 双写标记：仅新版本双写后置 true，本地值才可信（旧版本只写 rime，本地是过时迁移值） */
    private const val KEY_CURRENT_SCHEMA_DUAL = "current_schema_dual"
    private const val KEY_DEPLOYMENT_DONE = "deployment_done"
    private const val KEY_BUILTIN_SCHEMAS_MERGED = "builtin_schemas_merged"
    private const val KEY_DEPLOYMENT_HASH = "deployment_hash"
    private const val KEY_SETUP_COMPLETED = "setup_completed"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_VERBOSE_LOGGING = "verbose_logging"
    private const val KEY_LAUNCH_AD_EGG = "launch_ad_easter_egg"
    
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_SOUND_VOLUME = "sound_volume"
    private const val KEY_KEYBOARD_THEME = "keyboard_theme"
    
    const val KEY_SMART_PREDICTION_ENABLED = "smart_prediction_enabled"
    private const val KEY_SPACE_COMMIT_ASSOCIATION = "space_commit_association"
    internal const val KEY_ASSOCIATION_SINGLE_MODE = "association_single_mode"
    /** 已删的「滑动清空时跳过联想」，只留着给 isDeleteSkipsAssociation 迁移旧值。 */
    private const val KEY_CLEAR_SWIPE_SKIPS_ASSOCIATION = "clear_swipe_skips_association"
    private const val KEY_DELETE_SKIPS_ASSOCIATION = "delete_skips_association"
    private const val KEY_DELETE_RECOVERY = "delete_recovery_candidates"
    private const val KEY_UNLEARN_REJECTED_COMMIT = "unlearn_rejected_commit"
    
    const val KEY_STT_ENABLED = "stt_enabled"
    const val KEY_STT_ONLINE_PLUGIN_ID = "stt_online_plugin_id"
    const val KEY_STT_USE_LOCAL = "stt_use_local"
    const val KEY_STT_KEEP_ENGINE_ALIVE = "stt_keep_engine_alive"
    const val KEY_STT_REFINER_IDLE_MINUTES = "stt_refiner_idle_minutes"
    const val KEY_STT_ENGINE_IDLE_MINUTES = "stt_engine_idle_minutes"
    const val KEY_STT_DEBUG_RECORD = "stt_debug_record"
    const val KEY_STT_WHOLE_SENTENCE = "stt_whole_sentence"
    const val KEY_STT_WHOLE_FINALIZER = "stt_whole_finalizer"
    const val KEY_STT_AUTO_PUNCTUATION = "stt_auto_punctuation"
    const val KEY_STT_PUNCTUATION_COMMA = "stt_punctuation_comma"
    const val KEY_STT_PUNCTUATION_PERIOD = "stt_punctuation_period"
    const val KEY_STT_PUNCTUATION_QUESTION = "stt_punctuation_question"
    const val KEY_STT_PUNCTUATION_TRAILING = "stt_punctuation_trailing"
    const val KEY_STT_PAUSE_SPACE = "stt_pause_space"
    const val KEY_STT_ARABIC_NUMBERS = "stt_arabic_numbers"
    const val KEY_STT_CORRECTIONS = "stt_corrections"
    const val KEY_STT_CORRECTIONS_TEXT = "stt_corrections_text"
    const val KEY_STT_PILL_RESUME = "stt_pill_resume"
    const val KEY_STT_REMOTE_TYPING = "stt_remote_typing"
    const val KEY_STT_OTHER_AUDIO = "stt_other_audio"
    private const val KEY_STT_MUSIC_MUTED_BY_VOICE = "stt_music_muted_by_voice"
    private const val KEY_STT_MUSIC_VOLUME_BEFORE_VOICE = "stt_music_volume_before_voice"
    
    /** 默认主题 ID，可从 xime.yaml 的 style.color_scheme 初始化。 */
    @JvmStatic
    var defaultKeyboardTheme: String = com.kingzcheung.xime.ui.theme.PureBlackTheme.ID

    /** 默认显示模式，可从 xime.yaml 的 style.dark_mode 初始化。 */
    @JvmStatic
    var defaultDarkMode: Int = 1
    
    const val KEY_SWIPE_UP_HINTS_ENABLED = "swipe_up_hints_enabled"
    const val KEY_SWIPE_DOWN_HINTS_ENABLED = "swipe_down_hints_enabled"
    /** 按键浮标默认开（用户 11-09，别家和 Xime 都有）；换键名让 09-24 默认关时存下的值作废一次。 */
    const val KEY_SHOW_PRESS_BUBBLE = "show_press_bubble_v2"

    private const val KEY_AUTO_NUMBER_KEYBOARD = "auto_number_keyboard_for_number_fields"

    /** 进入纯数字输入框（号码/验证码等）时自动弹出数字键盘，默认开启。 */
    fun isAutoNumberKeyboardEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_NUMBER_KEYBOARD, true)
    }

    fun setAutoNumberKeyboardEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_NUMBER_KEYBOARD, enabled).apply()
    }
    
    const val KEY_SPLIT_KEYBOARD = "split_keyboard_enabled"

    /** 完整键盘为默认；仅用户在调节界面选择后启用26键分体。 */
    fun isSplitKeyboardEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SPLIT_KEYBOARD, false)

    fun setSplitKeyboardEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SPLIT_KEYBOARD, enabled).apply()
    }

    /** 这块屏幕上分体键盘是否真的分开：「分体键盘」开着，且屏幕够宽；屏幕太窄时用完整键盘，存着的开关不改。 */
    fun isSplitKeyboardShown(context: Context, screenWidthDp: Int = context.resources.configuration.screenWidthDp): Boolean =
        isSplitKeyboardEnabled(context) && com.kingzcheung.xime.ui.keyboard.SplitKeyboardGeometry.fitsScreen(screenWidthDp)

    const val KEY_SPLIT_RIGHT_G = "split_keyboard_right_g"

    /** 分体 26 键右半中排开头再放一个 G（别人提的需求，默认关）；只影响分体键盘。 */
    fun isSplitRightGEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SPLIT_RIGHT_G, false)

    fun setSplitRightGEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SPLIT_RIGHT_G, enabled).apply()
    }

    const val KEY_FILL_KEYBOARD_BLANK = "fill_keyboard_blank"

    /**
     * 分体中缝、固定键盘两侧的留空默认镂空，透出后面的应用（用户的设计）；
     * 打开后用键盘底色填满，遮住后面的内容，也不再把点按让给后面的应用。
     */
    fun isKeyboardBlankFilled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FILL_KEYBOARD_BLANK, false)

    fun setKeyboardBlankFilled(context: Context, filled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FILL_KEYBOARD_BLANK, filled).apply()
    }

    const val KEY_WIDE_KEYS = "wide_keys"

    /**
     * 「加宽按键」默认关：打开后键盘两侧留白从 8dp 收到 3dp、横向键缝减三分之一，
     * 手机上每个键更宽，触摸键距也更大（10-31 用户反馈手机上按键窄、容易误触）。
     */
    fun isWideKeysEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_WIDE_KEYS, false)

    fun setWideKeysEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WIDE_KEYS, enabled).apply()
    }

    const val KEY_EVEN_MERGED_KEYS = "even_merged_keys"

    /**
     * 「合并键均匀排布」默认关：打开后 14 键和自建的合并键布局（17、18、20 键等）里合并键只比单字母键宽三分之一，
     * 单字母键相应变宽，每行排得更匀，小屏上好按（10-31 别人的需求）。关闭时严格对齐 26 键键缝。
     */
    fun isEvenMergedKeysEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_EVEN_MERGED_KEYS, false)

    fun setEvenMergedKeysEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EVEN_MERGED_KEYS, enabled).apply()
    }

    const val KEY_NUMBER_ROW = "number_row"

    /**
     * 「数字行」默认关：打开后 26 键、14 键（含自建字母布局）字母上方加一排数字，点按直接上屏，
     * 键和顺序在「按键符号 → 数字行」里改（10-31 用户反馈：平板想要一排数字、数字键位置能自己排）。
     * 键盘总高度不变，五排分高度；注音（大千）首排本身就是数字位，不加。
     */
    fun isNumberRowEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_NUMBER_ROW, false)

    fun setNumberRowEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NUMBER_ROW, enabled).apply()
    }

    /** 「数字行和字母一样高」（#444，默认关）：数字行和字母行一样高，键盘总高不变，每排一样分。 */
    const val KEY_NUMBER_ROW_FULL_HEIGHT = "number_row_full_height"

    fun isNumberRowFullHeight(context: Context): Boolean = getPrefs(context).getBoolean(KEY_NUMBER_ROW_FULL_HEIGHT, false)

    fun setNumberRowFullHeight(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NUMBER_ROW_FULL_HEIGHT, enabled).apply()
    }

    const val KEY_NUMBER_PAD_TOP_789 = "number_pad_top_789"

    /**
     * 「数字位置」：数字键盘最上面一排是 1 2 3（默认，和以前一样）还是 7 8 9（同电脑键盘右边的小键盘）。
     * 新键（11-22，群里有人要），读不到就是 1 2 3 在上。管 123 页数字键盘和假名九键的数字页，见 numberPadRows。
     */
    fun isNumberPadTop789(context: Context): Boolean = getPrefs(context).getBoolean(KEY_NUMBER_PAD_TOP_789, false)

    fun setNumberPadTop789(context: Context, top789: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NUMBER_PAD_TOP_789, top789).apply()
    }

    const val KEY_SPLIT_GAP_PORTRAIT = "split_gap_fraction_portrait"
    const val KEY_SPLIT_GAP_LANDSCAPE = "split_gap_fraction_landscape"

    /** 分体中缝占整张键盘宽度的比例；未调整过返回 null，由屏宽和键距自动给出。 */
    fun getSplitGapFraction(context: Context, landscape: Boolean): Float? =
        getPrefs(context).getFloat(if (landscape) KEY_SPLIT_GAP_LANDSCAPE else KEY_SPLIT_GAP_PORTRAIT, -1f)
            .takeIf { it.isFinite() && it > 0f }

    fun setSplitGapFraction(context: Context, landscape: Boolean, fraction: Float?) {
        val key = if (landscape) KEY_SPLIT_GAP_LANDSCAPE else KEY_SPLIT_GAP_PORTRAIT
        getPrefs(context).edit().apply {
            if (fraction == null || !fraction.isFinite() || fraction <= 0f) remove(key) else putFloat(key, fraction)
        }.apply()
    }

    
    private const val KEY_KEYBOARD_HEIGHT_DP = "keyboard_height_dp"
    private const val KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE = "keyboard_height_dp_landscape"
    const val DEFAULT_KEYBOARD_HEIGHT_PERCENT = 35
    /** 横屏默认高度 = 竖屏高度（长边）× 此百分比。约等于旧值（横屏短边 × 49%），但基数稳定、与竖屏语义统一 */
    const val DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE = 25

    private const val KEY_TOOLBAR_BUTTONS = "toolbar_buttons"
    private val DEFAULT_TOOLBAR_BUTTONS = com.kingzcheung.xime.keyboard.ToolbarButton.DEFAULT_VISIBLE.joinToString(",") { it.id }

    fun getToolbarButtons(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_TOOLBAR_BUTTONS, DEFAULT_TOOLBAR_BUTTONS) ?: DEFAULT_TOOLBAR_BUTTONS
        return com.kingzcheung.xime.keyboard.ToolbarButton.normalizeIds(raw.split(",").filter { it.isNotEmpty() })
    }

    fun setToolbarButtons(context: Context, buttons: List<String>) {
        getPrefs(context).edit().putString(KEY_TOOLBAR_BUTTONS,
            com.kingzcheung.xime.keyboard.ToolbarButton.normalizeIds(buttons).joinToString(",")).apply()
    }

    private const val KEY_MENU_BUTTONS = "menu_buttons"

    /** 菜单与工具栏共用一组入口；读出时去掉已在工具栏上的项，并保证“设置”可达。 */
    fun getMenuButtons(context: Context): List<String> {
        val toolbar = getToolbarButtons(context)
        restoreFullShapeMenuItemOnce(context, toolbar)
        // 悬浮、简繁：放在全角/半角后面（不在菜单里就放在“排列”前），与新装默认菜单同一位置。
        var after = com.kingzcheung.xime.keyboard.ToolbarButton.FULL_SHAPE.id
        com.kingzcheung.xime.keyboard.QuickActionLayout.MENU_TOGGLES_1031.forEach {
            insertMenuItemOnce(context, toolbar, it.id, "quick_actions_${it.id}_inserted", after)
            after = it.id
        }
        com.kingzcheung.xime.keyboard.ToolbarButton.LAYOUT_GESTURES.forEach {
            insertMenuItemOnce(context, toolbar, it.id, "quick_actions_${it.id}_inserted")
        }
        val raw = getPrefs(context).getString(KEY_MENU_BUTTONS, null)
        val menu = raw?.split(",")?.filter { it.isNotEmpty() }
            ?: com.kingzcheung.xime.keyboard.QuickActionLayout.defaultMenu(toolbar)
        return com.kingzcheung.xime.keyboard.QuickActionLayout.normalize(toolbar, menu).menu
    }

    /**
     * The menu/toolbar merge left 全角／半角 out of the default menu, so saved layouts from that
     * time hide it. Put it back once, before 排列; a later choice to remove it is kept.
     */
    private fun restoreFullShapeMenuItemOnce(context: Context, toolbar: List<String>) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_FULL_SHAPE_MENU_RESTORED, false)) return
        val id = com.kingzcheung.xime.keyboard.ToolbarButton.FULL_SHAPE.id
        val raw = prefs.getString(KEY_MENU_BUTTONS, null)
        val editor = prefs.edit().putBoolean(KEY_FULL_SHAPE_MENU_RESTORED, true)
        if (raw != null && id !in toolbar) {
            val menu = raw.split(",").filter { it.isNotEmpty() }
            if (id !in menu) {
                val arrange = com.kingzcheung.xime.keyboard.ToolbarButton.ARRANGE.id
                val at = menu.indexOf(arrange).takeIf { it >= 0 } ?: menu.size
                editor.putString(KEY_MENU_BUTTONS, (menu.take(at) + id + menu.drop(at)).joinToString(","))
            }
        }
        editor.apply()
    }
    private const val KEY_FULL_SHAPE_MENU_RESTORED = "quick_actions_full_shape_restored"

    /**
     * A new built-in entry joins an already saved menu once, right after [after] when that is in
     * the menu, otherwise before 排列; removing it later is kept.
     */
    private fun insertMenuItemOnce(context: Context, toolbar: List<String>, id: String, flagKey: String, after: String? = null) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(flagKey, false)) return
        val raw = prefs.getString(KEY_MENU_BUTTONS, null)
        val editor = prefs.edit().putBoolean(flagKey, true)
        if (raw != null && id !in toolbar) {
            val menu = raw.split(",").filter { it.isNotEmpty() }
            if (id !in menu) {
                val arrange = com.kingzcheung.xime.keyboard.ToolbarButton.ARRANGE.id
                val at = after?.let { menu.indexOf(it) }?.takeIf { it >= 0 }?.plus(1)
                    ?: menu.indexOf(arrange).takeIf { it >= 0 } ?: menu.size
                editor.putString(KEY_MENU_BUTTONS, (menu.take(at) + id + menu.drop(at)).joinToString(","))
            }
        }
        editor.apply()
    }
    private const val KEY_PLUGIN_BUTTONS_PLACED = "quick_actions_plugin_buttons_placed"

    /**
     * 插件按钮第一次出现（启用、从市场装好）时放进菜单一次；之后用户拖到哪里都不再动。
     * 返回是否改了菜单。
     */
    fun placeNewPluginButtons(context: Context, ids: List<String>): Boolean {
        val prefs = getPrefs(context)
        val placed = prefs.getString(KEY_PLUGIN_BUTTONS_PLACED, "").orEmpty().split(",").filter { it.isNotEmpty() }
        val fresh = ids.filter { it.isNotEmpty() && it !in placed }.distinct()
        if (fresh.isEmpty()) return false
        val toolbar = getToolbarButtons(context)
        val menu = getMenuButtons(context)
        val updated = com.kingzcheung.xime.keyboard.QuickActionLayout.withPluginButtons(toolbar, menu, fresh)
        val editor = prefs.edit().putString(KEY_PLUGIN_BUTTONS_PLACED, (placed + fresh).joinToString(","))
        if (updated != menu) editor.putString(KEY_MENU_BUTTONS, updated.joinToString(","))
        editor.apply()
        return updated != menu
    }

    /**
     * 卸载插件后把它留下的东西清掉：启用开关、工具栏和菜单里的按钮、「已放过按钮」的记录、语音和剪贴板同步的选用。
     * 以前只删文件，重新装上时旧的「已启用」又回来了（11-03 平板查到 AI 改写、AI 翻译都是这样）。
     */
    fun forgetUninstalledPlugin(context: Context, pluginId: String, buttonIds: Collection<String>) {
        if (getSttOnlinePluginId(context) == pluginId) setSttOnlinePluginId(context, "")
        if (getClipboardSyncPluginId(context) == pluginId) setClipboardSyncPluginId(context, "")
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        com.kingzcheung.xime.plugin.core.config.PluginEnablementStore.clear(context, pluginId)
        // 字幕插件的按钮是内置的 caption：卸载时一起拿掉，再装上时放回菜单「排列」前。
        val caption = pluginId == com.kingzcheung.xime.ui.caption.CaptionPlugin.ID
        val gone = buttonIds.toSet() + listOfNotNull(com.kingzcheung.xime.ui.caption.CaptionPlugin.BUTTON_ID.takeIf { caption })
        for (key in listOf(KEY_TOOLBAR_BUTTONS, KEY_MENU_BUTTONS, KEY_PLUGIN_BUTTONS_PLACED)) {
            val raw = prefs.getString(key, null) ?: continue
            val ids = raw.split(",").filter { it.isNotEmpty() }
            val kept = ids.filter { it !in gone }
            if (kept.size != ids.size) editor.putString(key, kept.joinToString(","))
        }
        editor.apply()
        if (caption) com.kingzcheung.xime.ui.caption.CaptionPlugin.sync(context)
        // 日语插件：卸载就关掉日语、删掉下载的日语词库和引擎（学习记录留着）。
        if (pluginId == JapanesePlugin.ID) JapanesePlugin.onUninstalled(context)
    }

    /** 插件按钮现在在哪：设置里「用法」那一行照这个写。 */
    fun pluginButtonPlace(context: Context, id: String): String = when (id) {
        in getToolbarButtons(context) -> "toolbar"
        in getMenuButtons(context) -> "menu"
        else -> "unused"
    }

    fun setQuickActions(context: Context, layout: com.kingzcheung.xime.keyboard.QuickActionLayout) {
        getPrefs(context).edit()
            .putString(KEY_TOOLBAR_BUTTONS, com.kingzcheung.xime.keyboard.ToolbarButton.normalizeIds(layout.toolbar).joinToString(","))
            .putString(KEY_MENU_BUTTONS, layout.menu.joinToString(","))
            .apply()
    }

    const val KEY_INCOGNITO = "incognito_mode"
    private const val KEY_INCOGNITO_INSTALL = "incognito_initialized_install"

    /** 每次换版本都从关闭开始；同版本重启保留手动选择。 */
    internal fun initializeIncognitoForVersion(context: Context, versionCode: Int) {
        val prefs = getPrefs(context)
        // Android 可能在新装时恢复同版本的设置；还要区分这是哪一次安装。
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        val installVersion = "$installedAt/$versionCode"
        if (prefs.getString(KEY_INCOGNITO_INSTALL, null) == installVersion) return
        prefs.edit().remove(KEY_INCOGNITO).putString(KEY_INCOGNITO_INSTALL, installVersion).apply()
    }

    /** 用户手动开启的无痕：不学新词、不调词频、不记剪贴板与联想；敏感输入框另行自动生效。 */
    fun isIncognito(context: Context): Boolean = getPrefs(context).getBoolean(KEY_INCOGNITO, false)

    fun setIncognito(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_INCOGNITO, enabled).apply()
    }

    /**
     * 「锁定词序」（专业打字手，默认关）：引擎不从上屏学习，候选顺序不再随使用变；置顶、忘记、我的短语照常。
     * 和无痕不同：剪贴板、联想照常记，升级也不重置。
     */
    const val KEY_LOCK_WORD_ORDER = "lock_word_order"

    fun isLockWordOrder(context: Context): Boolean = getPrefs(context).getBoolean(KEY_LOCK_WORD_ORDER, false)

    fun setLockWordOrder(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOCK_WORD_ORDER, enabled).apply()
    }

    /**
     * 「记录密码等敏感内容」(#95)，默认关：密码管理器复制的、在密码框里复制的内容记进剪贴板历史，
     * 显示成圆点、1 小时后删除、不同步不导出。无痕开着时照样不记。
     */
    private const val KEY_CLIPBOARD_SENSITIVE_CAPTURE = "clipboard_sensitive_capture"

    fun isClipboardSensitiveCaptureEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CLIPBOARD_SENSITIVE_CAPTURE, false)

    fun setClipboardSensitiveCaptureEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLIPBOARD_SENSITIVE_CAPTURE, enabled).apply()
    }

    /** 剪贴板历史最多保存几条（0 = 不限）；没存过时由剪贴板按已有条数定一次，见 ClipboardRetention.initialCount。 */
    private const val KEY_CLIPBOARD_MAX_COUNT = "clipboard_max_count"
    /** 剪贴板历史保存几天后自动删除（0 = 不过期，默认）。 */
    private const val KEY_CLIPBOARD_KEEP_DAYS = "clipboard_keep_days"

    fun getClipboardMaxCount(context: Context): Int? =
        getPrefs(context).takeIf { it.contains(KEY_CLIPBOARD_MAX_COUNT) }?.getInt(KEY_CLIPBOARD_MAX_COUNT, 0)

    fun setClipboardMaxCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_CLIPBOARD_MAX_COUNT, count).apply()
    }

    fun getClipboardKeepDays(context: Context): Int = getPrefs(context).getInt(KEY_CLIPBOARD_KEEP_DAYS, 0)

    fun setClipboardKeepDays(context: Context, days: Int) {
        getPrefs(context).edit().putInt(KEY_CLIPBOARD_KEEP_DAYS, days).apply()
    }

    private const val KEY_SCHEMA_IMPORT_WARNING_DISMISSED = "schema_import_warning_dismissed"

    private const val KEY_INSTALLED_MARKET_IDS = "installed_market_ids"
    const val KEY_SHOW_CANDIDATE_COMMENTS = "show_candidate_comments"
    const val KEY_CANDIDATE_COMMENT_MODE = "candidate_comment_mode"
    const val KEY_CANDIDATE_COMMENT_CONTENT = "candidate_comment_content"
    const val KEY_SHOW_CORRECTION_MARKS = "show_correction_marks"
    const val KEY_SHOW_LEARNED_MARK = "show_learned_mark"
    const val KEY_SHOW_CANDIDATE_CANCEL_BUTTON = "show_candidate_cancel_button"
    const val KEY_ASSOCIATION_CANCEL_CROSS = "association_cancel_cross"
    const val KEY_HANDWRITING_WUBI_HINTS = "handwriting_wubi_hints"
    const val KEY_CANDIDATE_PRESS_HIGHLIGHT = "candidate_press_highlight"
    /** 候选滑到头回弹展开（11-21 他：默认关，关着时候选栏和 1.15.101 完全一样）。 */
    const val KEY_CANDIDATE_SWIPE_EXPAND = "candidate_swipe_expand"
    /** 首选上拖选改局部（默认开，见 [isSentenceDragEdit]）。 */
    const val KEY_SENTENCE_DRAG_EDIT = "sentence_drag_edit"
    /** 开了「候选滑到头回弹展开」时，取消／关联想键放右边画成「×」（11-22 他：和收起键盘同一个位置）；默认左边。 */
    const val KEY_CANDIDATE_CLOSE_ON_RIGHT = "candidate_close_on_right"
    /** 「候选栏右边的键」选「关闭」前「左右滑展开」开没开；选回「展开」时恢复成它（11-23 平板实测：切回后不该还开着）。 */
    const val KEY_CANDIDATE_SWIPE_BEFORE_CLOSE = "candidate_swipe_expand_before_close"
    /** 「收键盘」（= 1.15.103，11-24 他：还有人要）：右边一直是收起键盘，左边「‹」取消，展开靠左右滑。默认关。 */
    const val KEY_CANDIDATE_RIGHT_HIDES = "candidate_right_hides"
    const val KEY_INPUT_TEXT_LOCATION = "input_text_location"
    const val KEY_SHOW_PREEDIT_PREVIEW = "show_preedit_preview"
    const val KEY_INLINE_PREEDIT_PREVIEW = "inline_preedit_preview"
    const val KEY_PREEDIT_BUBBLE_OPACITY = "preedit_bubble_opacity"
    /** 「拼音显示声调」（用户 11-20）：气泡／小签里的拼音按首选的读音标调，只改显示。新键，默认关（拼音显示默认照原样）。 */
    const val KEY_PREEDIT_TONES = "preedit_tones"
    fun preeditTones(context: Context) = getPrefs(context).getBoolean(KEY_PREEDIT_TONES, false)
    fun setPreeditTones(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_PREEDIT_TONES, enabled).apply()
    /**
     * 小标签、方块、整行里的拼音在首选是纠错来的词时显示改正后的读音、改动的字母标红（#586）。默认开 = 原来的样子；
     * 关掉时照打的字母显示，和气泡一样。不管候选旁边的纠错标记（那个是「显示纠错标记」）。
     */
    const val KEY_PREEDIT_LINE_CORRECTED = "preedit_line_corrected"
    fun preeditLineCorrected(context: Context) = getPrefs(context).getBoolean(KEY_PREEDIT_LINE_CORRECTED, true)
    fun setPreeditLineCorrected(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_PREEDIT_LINE_CORRECTED, enabled).apply()

    fun showPreeditPreview(context: Context) = getPrefs(context).getBoolean(KEY_SHOW_PREEDIT_PREVIEW, true)
    fun setShowPreeditPreview(context: Context, show: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_SHOW_PREEDIT_PREVIEW, show).apply()
    /**
     * 「显示在候选栏」时拼音放哪：默认跟原来一样是浮在应用上的气泡（用户 11-19：「默认和原来一致」）；
     * [PREEDIT_STYLE_LINE]「紧贴候选上方」：贴在键盘边框上的拼音小签（11-20 他按微信那张图定的），纠错读音也在上面。
     * 新键；没存过时看旧开关「拼音放在键盘内」：亲手开过的算小签，别的都是气泡。
     */
    const val KEY_PREEDIT_STYLE = "preedit_style"
    const val PREEDIT_STYLE_LINE = "line"
    const val PREEDIT_STYLE_BUBBLE = "bubble"
    /** 「贴边方块」（用户 11-21 第二张图）：直角方块贴在键盘边框上，字和气泡一样大。 */
    const val PREEDIT_STYLE_BLOCK = "block"
    /** 「键盘顶上一行」（用户 11-21 第一张图）：整行宽、和键盘连成一体，像键盘往上多出一行；键盘本身高度不变。 */
    const val PREEDIT_STYLE_STRIP = "strip"
    const val PREEDIT_STYLE_LEFT = "left"
    /** 设置里的顺序：气泡（默认）、圆角小签、贴边方块、顶上一行（用户 11-21：「总共是 4 种样式」）。 */
    val PREEDIT_STYLES = listOf(PREEDIT_STYLE_BUBBLE, PREEDIT_STYLE_LINE, PREEDIT_STYLE_BLOCK, PREEDIT_STYLE_STRIP)
    /** 「候选栏左侧」（和老开关「拼音放在键盘内」）11-20 他要求去掉：存过的按「紧贴候选上方」读，同样不浮气泡。只在读的时候换，不改存的值。 */
    fun resolvePreeditStyle(saved: String?, legacyInline: Boolean?): String = when {
        saved in PREEDIT_STYLES -> saved!!
        saved == PREEDIT_STYLE_LEFT -> PREEDIT_STYLE_LINE
        legacyInline == true -> PREEDIT_STYLE_LINE
        else -> PREEDIT_STYLE_BUBBLE
    }
    fun preeditStyle(context: Context): String {
        val prefs = getPrefs(context)
        return resolvePreeditStyle(prefs.getString(KEY_PREEDIT_STYLE, null),
            if (prefs.contains(KEY_INLINE_PREEDIT_PREVIEW)) prefs.getBoolean(KEY_INLINE_PREEDIT_PREVIEW, false) else null)
    }
    fun setPreeditStyle(context: Context, style: String) {
        if (style in PREEDIT_STYLES) getPrefs(context).edit().putString(KEY_PREEDIT_STYLE, style).apply()
    }
    /**
     * 拼音靠哪边：四种样式都能选靠左、居中、靠右（用户 11-21：「每种都能调整位置，左中右」）。
     * 靠左、靠右的小签和方块贴死键盘边缘，卡片圆角在它下面补平；新键，没存过是靠左（气泡原来就靠左）。
     */
    const val KEY_PREEDIT_ALIGN = "preedit_align"
    const val PREEDIT_ALIGN_LEFT = "left"
    const val PREEDIT_ALIGN_CENTER = "center"
    const val PREEDIT_ALIGN_RIGHT = "right"
    val PREEDIT_ALIGNS = listOf(PREEDIT_ALIGN_LEFT, PREEDIT_ALIGN_CENTER, PREEDIT_ALIGN_RIGHT)
    fun preeditAlign(context: Context): String =
        getPrefs(context).getString(KEY_PREEDIT_ALIGN, null)?.takeIf { it in PREEDIT_ALIGNS } ?: PREEDIT_ALIGN_LEFT
    fun setPreeditAlign(context: Context, align: String) {
        if (align in PREEDIT_ALIGNS) getPrefs(context).edit().putString(KEY_PREEDIT_ALIGN, align).apply()
    }
    /**
     * 浮在应用上面的拼音气泡和点开后的拼音编辑条共用这一个底色不透明度：20%~100%，按 5% 一档。
     * 默认 80%：能隐约看到下面的应用，拼音照样清楚（用户 11-16：实底挡住下面，点拼音编辑又变透明，两样不统一）。
     * 原来默认 100%（10-31「透了看着乱」改的实底）；手动拖过滑条的保留自己的值。
     */
    const val DEFAULT_PREEDIT_BUBBLE_OPACITY = 0.8f
    fun normalizePreeditBubbleOpacity(value: Float): Float =
        if (!value.isFinite()) DEFAULT_PREEDIT_BUBBLE_OPACITY else kotlin.math.round(value.coerceIn(0.2f, 1f) * 20f) / 20f
    fun preeditBubbleOpacity(context: Context) =
        normalizePreeditBubbleOpacity(getPrefs(context).getFloat(KEY_PREEDIT_BUBBLE_OPACITY, DEFAULT_PREEDIT_BUBBLE_OPACITY))
    fun setPreeditBubbleOpacity(context: Context, opacity: Float) =
        getPrefs(context).edit().putFloat(KEY_PREEDIT_BUBBLE_OPACITY, normalizePreeditBubbleOpacity(opacity)).apply()
    private const val KEY_PAGE_SIZE = "page_size"
    private const val KEY_CANDIDATE_TEXT_SIZE = "candidate_text_size"
    const val KEY_WIDE_CANDIDATE_SPACING = "wide_candidate_spacing"
    const val INPUT_TEXT_INPUT_BOX = "input_box"
    const val INPUT_TEXT_CANDIDATE_BAR = "candidate_bar"
    const val DEFAULT_PAGE_SIZE = 20 // 手机候选栏每页候选词数；schema 里的 page_size 来自 PC 版（5），太短，默认用 20

    /**
     * 候选旁的纠错读音默认不显示（用户 11-19：「纠错也别显示了他屏幕就这么窄」）；纠错照常，拼音在候选上方一行时读音照样在那一行。
     * 只改没存过时的默认，亲手开过或关过的照旧。11-19 拼音默认改回气泡时（e8e3dc48）把它一起改回了显示，那句只说拼音。
     */
    fun showCorrectionMarks(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SHOW_CORRECTION_MARKS, false)

    fun setShowCorrectionMarks(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_CORRECTION_MARKS, show).apply()
    }

    /** 学到的词在候选右上角画小圆点（原来是小铅笔），默认显示（用户 11-19 要一个开关）；关掉只是不画，照常学习。 */
    fun showLearnedMark(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SHOW_LEARNED_MARK, true)

    fun setShowLearnedMark(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_LEARNED_MARK, show).apply()
    }

    fun candidateCommentMode(context: Context): CandidateCommentMode {
        val prefs = getPrefs(context)
        val stored = prefs.getString(KEY_CANDIDATE_COMMENT_MODE, null)
        CandidateCommentMode.entries.firstOrNull { it.name == stored }?.let { return it }
        // Existing explicit ON means ON, never silently reinterpret it as AUTO.
        return if (prefs.contains(KEY_SHOW_CANDIDATE_COMMENTS)) {
            if (prefs.getBoolean(KEY_SHOW_CANDIDATE_COMMENTS, true)) CandidateCommentMode.SHOW else CandidateCommentMode.HIDE
        } else CandidateCommentMode.AUTO
    }

    fun setCandidateCommentMode(context: Context, mode: CandidateCommentMode) {
        getPrefs(context).edit().putString(KEY_CANDIDATE_COMMENT_MODE, mode.name)
            .putBoolean(KEY_SHOW_CANDIDATE_COMMENTS, mode != CandidateCommentMode.HIDE).apply()
    }

    fun candidateCommentContent(context: Context): CandidateCommentContent {
        val stored = getPrefs(context).getString(KEY_CANDIDATE_COMMENT_CONTENT, null)
        return CandidateCommentContent.entries.firstOrNull { it.name == stored } ?: CandidateCommentContent.PINYIN
    }

    fun setCandidateCommentContent(context: Context, content: CandidateCommentContent) {
        getPrefs(context).edit().putString(KEY_CANDIDATE_COMMENT_CONTENT, content.name).apply()
    }

    fun showCandidateComments(context: Context): Boolean {
        return candidateCommentMode(context) != CandidateCommentMode.HIDE
    }

    fun setShowCandidateComments(context: Context, show: Boolean) {
        setCandidateCommentMode(context, if (show) CandidateCommentMode.SHOW else CandidateCommentMode.HIDE)
    }

    /** 默认打开（用户 10-31）：打字时候选栏最左边有「<」一键取消编码。手动关过的仍按存下的值。 */
    fun shouldShowCandidateCancelButton(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_CANDIDATE_CANCEL_BUTTON, true)
    }

    fun setShowCandidateCancelButton(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_CANDIDATE_CANCEL_BUTTON, show).apply()
    }

    /**
     * 候选滑到头回弹展开（11-21 他：「谁让你直接生效了」，默认关）：打开后候选行滑到最后再往左拉、松手展开，
     * 展开后往右拉收回，展开页里左右划也收回；右边 ▾/▴ 照常。关着时候选栏和 1.15.101 一样，只有 ▾/▴。
     */
    fun isCandidateSwipeExpand(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CANDIDATE_SWIPE_EXPAND, false)

    /**
     * 首选上按住拖选一段改局部。默认开（11-24 他先说默认关，当晚改成「局部修改开开默认打开 你只能在6个字以上生效」），
     * 只在首选满 5 个字时接拖选（11-26 从超过 6 个字放宽），按住不拖照旧出调词频、固定、删除的卡片；关着时长按首选和别的候选一样。
     */
    fun isSentenceDragEdit(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SENTENCE_DRAG_EDIT, true)

    fun setSentenceDragEdit(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SENTENCE_DRAG_EDIT, enabled).apply()
    }

    fun setCandidateSwipeExpand(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CANDIDATE_SWIPE_EXPAND, enabled).apply()
    }

    /**
     * 关闭键放右边（11-22 他：全面屏手势的人要「不管什么时候点右边同一个位置都能收起键盘」）：只在
     * [isCandidateSwipeExpand] 打开时起作用。打开后打字时右边是「×」取消输入、出联想时是「×」关联想、空闲时是收起键盘，
     * 上屏后同一个位置点两下就收起；左边不放按钮。默认关（左边「<」，右边永远收起键盘，同 1.15.104）。
     */
    fun isCandidateCloseOnRight(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CANDIDATE_CLOSE_ON_RIGHT, false)

    fun setCandidateCloseOnRight(context: Context, right: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CANDIDATE_CLOSE_ON_RIGHT, right).apply()
    }

    /**
     * 设置「候选栏右边的键」：[closes] 为真是「关闭」（右边 × 先关候选、同一处再点收键盘），存成关闭键放右边 + 左右滑展开开着；
     * 「关闭」只能靠滑展开，所以选它时把「左右滑展开」打开，并记下原来开没开，选回「展开」时恢复。
     */
    fun setCandidateRightKeyCloses(context: Context, closes: Boolean) =
        setCandidateRightKey(context, if (closes) CandidateRightKey.CLOSE else CandidateRightKey.EXPAND)

    /** 候选栏三种用法，见 [CandidateRightKey]。 */
    fun candidateRightKey(context: Context): CandidateRightKey {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_CANDIDATE_SWIPE_EXPAND, false)) return CandidateRightKey.EXPAND
        return when {
            prefs.getBoolean(KEY_CANDIDATE_CLOSE_ON_RIGHT, false) -> CandidateRightKey.CLOSE
            prefs.getBoolean(KEY_CANDIDATE_RIGHT_HIDES, false) -> CandidateRightKey.HIDE
            else -> CandidateRightKey.EXPAND
        }
    }

    /**
     * 选候选栏用法：「收键盘」「经典」右边不放 ▾/▴，只能靠滑展开，所以把「左右滑展开候选」打开，
     * 并记下原来开没开（从「默认」离开时记），选回「默认」时恢复。存的键都是原来的，只多了 [KEY_CANDIDATE_RIGHT_HIDES]。
     */
    fun setCandidateRightKey(context: Context, mode: CandidateRightKey) {
        val prefs = getPrefs(context)
        val swipe = prefs.getBoolean(KEY_CANDIDATE_SWIPE_EXPAND, false)
        val leavingDefault = candidateRightKey(context) == CandidateRightKey.EXPAND
        val edit = prefs.edit()
            .putBoolean(KEY_CANDIDATE_CLOSE_ON_RIGHT, mode == CandidateRightKey.CLOSE)
            .putBoolean(KEY_CANDIDATE_RIGHT_HIDES, mode == CandidateRightKey.HIDE)
        if (mode != CandidateRightKey.EXPAND) {
            if (leavingDefault) edit.putBoolean(KEY_CANDIDATE_SWIPE_BEFORE_CLOSE, swipe)
            edit.putBoolean(KEY_CANDIDATE_SWIPE_EXPAND, true)
        } else if (prefs.contains(KEY_CANDIDATE_SWIPE_BEFORE_CLOSE)) {
            edit.putBoolean(KEY_CANDIDATE_SWIPE_EXPAND, prefs.getBoolean(KEY_CANDIDATE_SWIPE_BEFORE_CLOSE, swipe))
                .remove(KEY_CANDIDATE_SWIPE_BEFORE_CLOSE)
        }
        edit.apply()
    }

    /**
     * 联想关闭键用叉号（用户 11-03，默认关）：默认上屏后的联想和打字时一样，最左边是「<」；
     * 打开后联想时换成 ⊗。只在取消按钮开着时有用，动作不变。
     */
    fun isAssociationCancelCross(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ASSOCIATION_CANCEL_CROSS, false)

    fun setAssociationCancelCross(context: Context, cross: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ASSOCIATION_CANCEL_CROSS, cross).apply()
    }

    /**
     * 候选词间距加宽（B站反馈 10-31，别人的需求，默认关）：候选栏里两个候选之间约空出一个字，
     * 单字候选不挤在一起。关着时间距不变。
     */
    fun isWideCandidateSpacing(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_WIDE_CANDIDATE_SPACING, false)

    fun setWideCandidateSpacing(context: Context, wide: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WIDE_CANDIDATE_SPACING, wide).apply()
    }

    fun getInputTextLocation(context: Context): String {
        return getPrefs(context).getString(KEY_INPUT_TEXT_LOCATION, INPUT_TEXT_CANDIDATE_BAR) ?: INPUT_TEXT_CANDIDATE_BAR
    }

    fun setInputTextLocation(context: Context, location: String) {
        getPrefs(context).edit().putString(KEY_INPUT_TEXT_LOCATION, location).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getPrefsPublic(context: Context): SharedPreferences {
        return getPrefs(context)
    }
    
    fun getCurrentSchema(context: Context): String {
        val prefs = getPrefs(context)
        // 双写标记已置：本地值是新版本写入的最新方案，进程重建后 rime 未初始化也能恢复
        if (prefs.getBoolean(KEY_CURRENT_SCHEMA_DUAL, false)) {
            val local = prefs.getString(KEY_CURRENT_SCHEMA, null)
            if (!local.isNullOrBlank()) return local
        }
        // 未双写（旧版本升级/首次）：rime user.yaml 为权威，本地仅作迁移兜底
        val fromRime = try {
            if (com.kingzcheung.xime.rime.RimeEngine.isInitialized()) {
                com.kingzcheung.xime.rime.RimeEngine.getInstance()
                    .getUserConfigString("var/previously_selected_schema")
            } else null
        } catch (_: Throwable) {
            null
        }
        if (!fromRime.isNullOrBlank()) {
            // 回写 SharedPreferences 并置双写标记，之后本地优先
            prefs.edit()
                .putString(KEY_CURRENT_SCHEMA, fromRime)
                .putBoolean(KEY_CURRENT_SCHEMA_DUAL, true)
                .apply()
            return fromRime
        }
        val legacy = prefs.getString(KEY_CURRENT_SCHEMA, null)
        if (!legacy.isNullOrBlank()) return legacy
        return "rime_ice"
    }

    fun setCurrentSchema(context: Context, schemaId: String, language: InputLanguage = InputLanguage.forSchema(schemaId)) {
        InputModes.rememberMode(context, schemaId, language)
        // 双写：SharedPreferences 保证进程重建后不依赖 rime 即可恢复，
        // librime user.yaml 保持引擎侧状态一致（switchSchema 后由 librime 持久化）
        getPrefs(context).edit()
            .putString(KEY_CURRENT_SCHEMA, schemaId)
            .putBoolean(KEY_CURRENT_SCHEMA_DUAL, true)
            .apply()
        try {
            if (com.kingzcheung.xime.rime.RimeEngine.isInitialized()) {
                com.kingzcheung.xime.rime.RimeEngine.getInstance()
                    .setUserConfigString("var/previously_selected_schema", schemaId)
            }
        } catch (_: Throwable) {
        }
    }
    
    fun isDeploymentDone(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEPLOYMENT_DONE, false)
    }

    fun setDeploymentDone(context: Context, done: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEPLOYMENT_DONE, done).apply()
    }

    /**
     * 内置方案补齐已执行标记：新版本首次运行时把缺失的内置方案（如 t9_pinyin，
     * 老版本升级用户列表里没有）补进 schema_list 一次；之后用户在方案管理里
     * 移除内置方案是有效选择，getEnabledSchemas 不得再强行补回。
     */
    fun isBuiltinSchemasMerged(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BUILTIN_SCHEMAS_MERGED, false)
    }

    fun setBuiltinSchemasMerged(context: Context, merged: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BUILTIN_SCHEMAS_MERGED, merged).apply()
    }

    fun getDeploymentHash(context: Context): String {
        return getPrefs(context).getString(KEY_DEPLOYMENT_HASH, "") ?: ""
    }

    fun setDeploymentHash(context: Context, hash: String) {
        getPrefs(context).edit().putString(KEY_DEPLOYMENT_HASH, hash).apply()
    }

    /** 上次完成 rime assets 同步的 versionCode（0 表示从未同步）。 */

    /** 调试 verbose 日志总开关（仅 Debug 构建生效，Release 恒关闭）。 */
    fun isVerboseLoggingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    fun setVerboseLoggingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VERBOSE_LOGGING, enabled).apply()
    }

    /** 启动广告彩蛋（「关于 CyIME」）：开着时每次打开设置先放一页假广告。默认关。 */
    fun isLaunchAdEggEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LAUNCH_AD_EGG, false)
    }

    fun setLaunchAdEggEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LAUNCH_AD_EGG, enabled).apply()
    }

    fun isSetupCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun getDarkMode(context: Context): Int {
        // 0 = 浅色, 1 = 深色, 2 = 跟随系统（默认）
        return getPrefs(context).getInt(KEY_DARK_MODE, defaultDarkMode)
    }
    
    fun setDarkMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_DARK_MODE, mode).apply()
    }

    fun getEffectiveDarkMode(context: Context): Int =
        getDarkMode(context)
    
    fun isSoundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SOUND_ENABLED, true)
    }
    
    fun setSoundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }
    
    fun getSoundVolume(context: Context): Int {
        return getPrefs(context).getInt(KEY_SOUND_VOLUME, 20)
    }
    
    fun setSoundVolume(context: Context, volume: Int) {
        getPrefs(context).edit().putInt(KEY_SOUND_VOLUME, volume).apply()
    }
    
    /** 振动实际由「按键效果」的振动模式决定；关闭即“不振动”。 */
    fun isVibrationEnabled(context: Context): Boolean = getHapticMode(context) != "disabled"

    /** 菜单「声音振动」用：关闭时记住原模式，重新打开时恢复（默认跟随系统）。旧 vibration_enabled 键不再使用。 */
    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        val current = getHapticMode(context)
        if (enabled == (current != "disabled")) return
        if (enabled) {
            prefs.edit().putString(KEY_HAPTIC_MODE, prefs.getString(KEY_HAPTIC_MODE_BEFORE_MUTE, null) ?: "following_system")
                .remove(KEY_HAPTIC_MODE_BEFORE_MUTE).apply()
        } else {
            prefs.edit().putString(KEY_HAPTIC_MODE_BEFORE_MUTE, current).putString(KEY_HAPTIC_MODE, "disabled").apply()
        }
    }
    private const val KEY_HAPTIC_MODE_BEFORE_MUTE = "haptic_mode_before_mute"
    private const val KEY_SOUND_BEFORE_MUTE = "sound_before_mute"

    /**
     * 菜单「声音振动」一键开关：关的时候记下声音开没开，再打开时照原样恢复，只开过振动的不会连按键音一起打开
     * （#486）。没有记录（从来没用这个键关过）时两样都打开，同以前。振动的原模式由 [setVibrationEnabled] 记。
     */
    fun setSoundAndVibrationEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        if (!enabled) {
            prefs.edit().putBoolean(KEY_SOUND_BEFORE_MUTE, isSoundEnabled(context)).apply()
            setSoundEnabled(context, false)
            // 振动原来就关着时不留记录（setVibrationEnabled 直接返回），所以有记录就是原来开着。
            setVibrationEnabled(context, false)
            return
        }
        val soundRecorded = prefs.contains(KEY_SOUND_BEFORE_MUTE)
        val soundBefore = prefs.getBoolean(KEY_SOUND_BEFORE_MUTE, true)
        val vibrationBefore = prefs.contains(KEY_HAPTIC_MODE_BEFORE_MUTE)
        prefs.edit().remove(KEY_SOUND_BEFORE_MUTE).apply()
        // 没有记录，或者记录里两样都关着：同以前，两样都打开。
        val both = !soundRecorded || (!soundBefore && !vibrationBefore)
        setSoundEnabled(context, both || soundBefore)
        if (both || vibrationBefore) setVibrationEnabled(context, true)
    }
    
    

    private const val KEY_HAPTIC_MODE = "haptic_mode"
    private const val KEY_HAPTIC_ON_KEYUP = "haptic_on_keyup"
    private const val KEY_VIBRATION_PRESS_DURATION = "vibration_press_duration"
    private const val KEY_VIBRATION_LONG_PRESS_DURATION = "vibration_long_press_duration"
    private const val KEY_VIBRATION_PRESS_AMPLITUDE = "vibration_press_amplitude"
    private const val KEY_VIBRATION_LONG_PRESS_AMPLITUDE = "vibration_long_press_amplitude"

    fun getHapticMode(context: Context): String {
        return getPrefs(context).getString(KEY_HAPTIC_MODE, "following_system") ?: "following_system"
    }

    fun setHapticMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_HAPTIC_MODE, mode).apply()
    }

    fun isHapticOnKeyUp(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTIC_ON_KEYUP, false)
    }

    fun setHapticOnKeyUp(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTIC_ON_KEYUP, enabled).apply()
    }

    fun getVibrationPressDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_PRESS_DURATION, 0)
    }

    fun setVibrationPressDuration(context: Context, duration: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_PRESS_DURATION, duration).apply()
    }

    fun getVibrationLongPressDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_LONG_PRESS_DURATION, 0)
    }

    fun setVibrationLongPressDuration(context: Context, duration: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_LONG_PRESS_DURATION, duration).apply()
    }

    fun getVibrationPressAmplitude(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_PRESS_AMPLITUDE, 0)
    }

    fun setVibrationPressAmplitude(context: Context, amplitude: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_PRESS_AMPLITUDE, amplitude).apply()
    }

    fun getVibrationLongPressAmplitude(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_LONG_PRESS_AMPLITUDE, 0)
    }

    fun setVibrationLongPressAmplitude(context: Context, amplitude: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_LONG_PRESS_AMPLITUDE, amplitude).apply()
    }

    /** 新安装使用配置中的默认配色；保留已有主题选择及原迁移标记。 */
    fun applyForkThemeDefaults(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean("cyime_soft_blue_v1", false)) return
        val theme = prefs.getString(KEY_KEYBOARD_THEME, null)
        prefs.edit().apply {
            if (theme.isNullOrBlank()) putString(KEY_KEYBOARD_THEME, defaultKeyboardTheme)
            if (!prefs.contains(KEY_DARK_MODE)) putInt(KEY_DARK_MODE, defaultDarkMode)
            putBoolean("cyime_soft_blue_v1", true)
        }.apply()
    }

    fun getKeyboardTheme(context: Context): String {
        val prefs = getPrefs(context)
        val stored = prefs.getString(KEY_KEYBOARD_THEME, defaultKeyboardTheme) ?: defaultKeyboardTheme
        // 莫奈取色只在 Android 12+ 有；更早的系统上（如备份恢复过来的选择）改用柔和蓝。
        if (stored == com.kingzcheung.xime.ui.theme.DynamicThemes.THEME_ID &&
            !com.kingzcheung.xime.ui.theme.DynamicThemes.isSupported()) {
            prefs.edit().putString(KEY_KEYBOARD_THEME, com.kingzcheung.xime.ui.theme.SoftBlueTheme.ID).apply()
            return com.kingzcheung.xime.ui.theme.SoftBlueTheme.ID
        }
        if (stored == "858AdvanceColor") {
            // 已移除主题仍沿用原兼容映射，不随新安装默认配色改变。
            prefs.edit().putString(KEY_KEYBOARD_THEME, com.kingzcheung.xime.ui.theme.SoftBlueTheme.ID).apply()
            return com.kingzcheung.xime.ui.theme.SoftBlueTheme.ID
        }
        return stored
    }

    const val KEY_VISUAL_STYLE = "cyime_visual_style"

    /** 选过「流光玻璃」「毛玻璃」的按「原有外观」读（[com.kingzcheung.xime.ui.theme.VisualStyle.offered]）；存的值不改。 */
    fun getVisualStyle(context: Context) = com.kingzcheung.xime.ui.theme.VisualStyle.forKeyboard(
        com.kingzcheung.xime.ui.theme.VisualStyle.fromId(getPrefs(context).getString(KEY_VISUAL_STYLE, null)))

    fun setVisualStyle(context: Context, style: com.kingzcheung.xime.ui.theme.VisualStyle) {
        com.kingzcheung.xime.ui.theme.IconAppearance.setKeyboardStyle(context, style)
    }

    const val KEY_KEY_SHAPE = "cyime_key_shape"

    fun getKeyShape(context: Context) = com.kingzcheung.xime.ui.theme.KeyShape.fromId(
        getPrefs(context).getString(KEY_KEY_SHAPE, null))

    fun setKeyShape(context: Context, shape: com.kingzcheung.xime.ui.theme.KeyShape) {
        getPrefs(context).edit().putString(KEY_KEY_SHAPE, shape.id).apply()
        com.kingzcheung.xime.ui.theme.KeyShapes.current = shape
    }

    
    fun setKeyboardTheme(context: Context, themeId: String) {
        getPrefs(context).edit().putString(KEY_KEYBOARD_THEME, themeId).apply()
        // 桌面图标（开启“跟随主题色”时）和字幕快捷方式随主题换色。
        com.kingzcheung.xime.ui.theme.IconAppearance.onKeyboardThemeChanged(context, themeId)
    }

    
    fun isPluginEnabled(context: Context, pluginId: String): Boolean {
        val installed = PluginManager.getAllInstallPlugins().find { it.id == pluginId }
        return com.kingzcheung.xime.plugin.core.config.PluginEnablementStore.isEnabled(context, pluginId, installed?.enabled ?: true)
    }

    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        com.kingzcheung.xime.plugin.core.config.PluginEnablementStore.setEnabled(context, pluginId, enabled)
        // 字幕插件：开关一拨，键盘菜单、下拉快捷开关、桌面图标、设置里的字幕跟着出现或收起。
        if (pluginId == com.kingzcheung.xime.ui.caption.CaptionPlugin.ID) com.kingzcheung.xime.ui.caption.CaptionPlugin.sync(context)
        // 日语插件：开关一拨，日语跟着打开（缺的词库开始下载）或从语言切换里拿掉。
        if (pluginId == JapanesePlugin.ID) JapanesePlugin.sync(context)
        // 通讯录插件：关掉立刻丢掉内存里的通讯录，打开（已授权）就后台读入。
        if (com.kingzcheung.xime.contacts.ContactSuggestions.isContactsPlugin(pluginId)) {
            val app = context.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.kingzcheung.xime.contacts.ContactSuggestions.sync(app) }
        }
    }
    
    fun isSmartPredictionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMART_PREDICTION_ENABLED, true)
    }
    
    fun setSmartPredictionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMART_PREDICTION_ENABLED, enabled).apply()
    }
    
    
    
    /** 空格上屏联想候选：有联想候选时按空格键直接上屏第一个联想词（而非空格字符） */
    fun isSpaceCommitAssociationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SPACE_COMMIT_ASSOCIATION, false)
    }

    fun setSpaceCommitAssociationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SPACE_COMMIT_ASSOCIATION, enabled).apply()
    }

    /**
     * 联想模式：false = 连续联想（联想上屏后继续推理，可连续上屏），true = 单次联想（只推理一次）。
     * 没存过值时算单次（老用户照旧）；2026-11-08 起新装由 [com.kingzcheung.xime.model.DeviceDefaults] 写入连续。
     */
    fun isSingleAssociationMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ASSOCIATION_SINGLE_MODE, true)
    }

    fun setAssociationSingleMode(context: Context, single: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ASSOCIATION_SINGLE_MODE, single).apply()
    }

    /**
     * 退格直接删字（B站反馈 10-31，别人的需求，默认关）：联想词出现时退格直接删掉刚上屏的字、顺手去掉联想词，
     * 滑动清空也直接按已选范围清正文。关着时照旧：第一下只去掉联想词，第二下才删字或清空。
     *
     * 原「滑动清空时跳过联想」（默认关）11-08 被用户删掉，并进这一项：旧值是开的，第一次读时把这一项打开，
     * 并删掉旧键，之后只看这一项。没开过旧项的人不受影响，默认值不变。
     */
    fun isDeleteSkipsAssociation(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_CLEAR_SWIPE_SKIPS_ASSOCIATION)) {
            val oldOn = prefs.getBoolean(KEY_CLEAR_SWIPE_SKIPS_ASSOCIATION, false)
            val edit = prefs.edit().remove(KEY_CLEAR_SWIPE_SKIPS_ASSOCIATION)
            if (oldOn) edit.putBoolean(KEY_DELETE_SKIPS_ASSOCIATION, true)
            edit.apply()
            if (oldOn) return true
        }
        return prefs.getBoolean(KEY_DELETE_SKIPS_ASSOCIATION, false)
    }

    fun setDeleteSkipsAssociation(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DELETE_SKIPS_ASSOCIATION, enabled).apply()
    }

    /**
     * 删字后给同音字（用户反馈 10-31，别人的需求，默认关）：退格删掉一个字后，候选栏先放回删掉的字，后面跟同音字，
     * 删错了点一下补回来，打错了同音字直接点对的。不挡接着删。见 DeletionRecovery。
     */
    fun isDeleteRecovery(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_DELETE_RECOVERY, false)

    fun setDeleteRecovery(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DELETE_RECOVERY, enabled).apply()
    }

    /**
     * 删掉刚上屏的词不学习（B站反馈 10-31，别人的需求，默认关）：刚上屏的词紧接着被整个删掉（退格或上滑清空），
     * 用户词库减去这一次学习，误触打出的怪词不会因此排到前面。见 RejectedCommit。
     */
    fun isUnlearnRejectedCommit(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_UNLEARN_REJECTED_COMMIT, false)

    fun setUnlearnRejectedCommit(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_UNLEARN_REJECTED_COMMIT, enabled).apply()
    }
    
    fun isSttEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_ENABLED, true)
    }
    
    fun setSttEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_ENABLED, enabled).apply()
    }

    fun getSttOnlinePluginId(context: Context): String {
        return getPrefs(context).getString(KEY_STT_ONLINE_PLUGIN_ID, "") ?: ""
    }

    fun setSttOnlinePluginId(context: Context, pluginId: String) {
        getPrefs(context).edit().putString(KEY_STT_ONLINE_PLUGIN_ID, pluginId).apply()
    }

    /** 语音转文本是否使用本地（离线）识别引擎。 */
    /**
     * 是否用本地模型识别。默认一律本地；只有在语音页选了在线语音插件并打开「优先使用在线语音」时才不是本地。
     * 存的值为 false 但没选插件时仍算本地；选的插件被删或停用时由 SpeechRecognitionManager 退回本地模型。
     */
    fun isSttUseLocal(context: Context): Boolean {
        val stored = getPrefs(context).getBoolean(KEY_STT_USE_LOCAL, getSttOnlinePluginId(context).isBlank())
        return stored || getSttOnlinePluginId(context).isBlank()
    }

    /** 1.15.7 及以前的「使用本地模型」开关已删掉：关过它的老用户升级后改回本地（只做一次）。 */
    fun migrateSttUseLocalOnce(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean("stt_use_local_reset_v1", false)) return
        prefs.edit().putBoolean(KEY_STT_USE_LOCAL, true).putBoolean("stt_use_local_reset_v1", true).apply()
    }

    fun setSttUseLocal(context: Context, useLocal: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_USE_LOCAL, useLocal).apply()
    }

    /**
     * 语音结束后是否保持识别引擎常驻（不随会话结束销毁）。
     * 开启后闲置一段时间再使用无需重新加载引擎，"开始聆听"响应快。
     * 仅本地（离线）模式生效：模型本就常驻 :asr 进程，保留 wrapper 代价极小；
     * 在线插件常驻需保持 WebSocket 长连接（耗电、占用服务端资源），不提供常驻。
     */
    fun isSttKeepEngineAlive(context: Context): Boolean {
        return isSttUseLocal(context) && getSttEngineIdleMinutes(context) == 0
    }

    /** 旧开关：开 = 两级都永不释放；关 = 恢复原来的 1 分钟空闲释放。 */
    fun setSttKeepEngineAlive(context: Context, enabled: Boolean) {
        val minutes = if (enabled) 0 else 1
        getPrefs(context).edit().putBoolean(KEY_STT_KEEP_ENGINE_ALIVE, enabled)
            .putInt(KEY_STT_REFINER_IDLE_MINUTES, minutes).putInt(KEY_STT_ENGINE_IDLE_MINUTES, minutes).apply()
    }

    /**
     * 语音内存阶梯（0 = 永不释放）：空闲这么多分钟后只卸掉 SenseVoice 第二遍，保留 Paraformer。
     * 没设过时沿用旧“保持常驻”开关的效果，升级后行为不变。
     */
    fun getSttRefinerIdleMinutes(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_STT_REFINER_IDLE_MINUTES, if (prefs.getBoolean(KEY_STT_KEEP_ENGINE_ALIVE, false)) 0 else 1)
    }

    /** 空闲这么多分钟后释放全部语音模型（0 = 常驻）。 */
    fun getSttEngineIdleMinutes(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_STT_ENGINE_IDLE_MINUTES, if (prefs.getBoolean(KEY_STT_KEEP_ENGINE_ALIVE, false)) 0 else 1)
    }

    fun setSttIdleLadder(context: Context, refinerMinutes: Int, engineMinutes: Int) {
        getPrefs(context).edit().putInt(KEY_STT_REFINER_IDLE_MINUTES, refinerMinutes.coerceAtLeast(0))
            .putInt(KEY_STT_ENGINE_IDLE_MINUTES, engineMinutes.coerceAtLeast(0))
            .putBoolean(KEY_STT_KEEP_ENGINE_ALIVE, engineMinutes == 0).apply()
    }

    /** 是否把本地语音识别收到的录音与识别过程保存到应用自己的 speech_tuning 目录，供电脑回放调优。 */
    fun isSttDebugRecord(context: Context): Boolean {
        // 开发者工具：公开包即使以前打开过也不再录。
        if (!com.kingzcheung.xime.BuildConfig.DEV_TOOLS && !com.kingzcheung.xime.BuildConfig.DEBUG) return false
        return getPrefs(context).getBoolean(KEY_STT_DEBUG_RECORD, false)
    }

    fun setSttDebugRecord(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_DEBUG_RECORD, enabled).apply()
    }

    /**
     * 整句定稿：在真停顿或结束时校正一整句，结束时整段重识别。默认开：平板实测说完 30 秒的话
     * 约 1.2–1.4 秒出定稿（2026-10-30，SenseVoice / X-ASR，sherpa-onnx 1.13.8 两线程）。
     */
    fun isSttWholeSentence(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_WHOLE_SENTENCE, true)
    }

    fun setSttWholeSentence(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_WHOLE_SENTENCE, enabled).apply()
    }

    /** 整句定稿用哪个模型：SenseVoice 或 X-ASR（X-ASR 下载了才有得选）；没选过时见 [SpeechModelSelection.DEFAULT_WHOLE_FINALIZER]。 */
    fun getSttWholeFinalizer(context: Context): String =
        com.kingzcheung.xime.speech.SpeechModelSelection.wholeFinalizer(getPrefs(context).getString(KEY_STT_WHOLE_FINALIZER, null))

    fun setSttWholeFinalizer(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_STT_WHOLE_FINALIZER,
            com.kingzcheung.xime.speech.SpeechModelSelection.wholeFinalizer(model)).apply()
    }

    /**
     * 语音自动加标点：保留识别结果自带的标点（本地整句定稿的模型标点，以及内置在线接口返回的原文标点）。
     * 默认关，和 1.15.0 以前一样：标点变成空格，只有口述「逗号」「句号」「问号」「感叹号」出标点。
     * 1.15.0–1.15.21 把标点绑在整句定稿上、又默认开，用户没法只去掉标点（2026-10-31 用户：「不要改我的习惯」）。
     */
    fun isSttAutoPunctuation(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_AUTO_PUNCTUATION, false)
    }

    fun setSttAutoPunctuation(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_AUTO_PUNCTUATION, enabled).apply()
    }

    /** 自动加标点开着时逐项选留哪些模型标点；默认全留（打开自动加标点就和原来一样带全部标点）。 */
    fun isSttPunctuationComma(context: Context) = getPrefs(context).getBoolean(KEY_STT_PUNCTUATION_COMMA, true)
    fun setSttPunctuationComma(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_PUNCTUATION_COMMA, enabled).apply()
    fun isSttPunctuationPeriod(context: Context) = getPrefs(context).getBoolean(KEY_STT_PUNCTUATION_PERIOD, true)
    fun setSttPunctuationPeriod(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_PUNCTUATION_PERIOD, enabled).apply()
    fun isSttPunctuationQuestion(context: Context) = getPrefs(context).getBoolean(KEY_STT_PUNCTUATION_QUESTION, true)
    fun setSttPunctuationQuestion(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_PUNCTUATION_QUESTION, enabled).apply()
    /** 整段话最后的标点；默认留（2026-10-31 用户反馈：想要中间有标点、末尾不带）。 */
    fun isSttPunctuationTrailing(context: Context) = getPrefs(context).getBoolean(KEY_STT_PUNCTUATION_TRAILING, true)
    fun setSttPunctuationTrailing(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_PUNCTUATION_TRAILING, enabled).apply()

    /**
     * 停顿处加空格：说话停顿、或去掉的标点处留一个空格。默认开，和以前一样；关掉后中文里不加空格，英文单词之间照常空格
     * （2026-11-18 用户：不想要任何空格，现在没法设置）。
     */
    fun isSttPauseSpace(context: Context) = getPrefs(context).getBoolean(KEY_STT_PAUSE_SPACE, true)
    fun setSttPauseSpace(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_PAUSE_SPACE, enabled).apply()

    /**
     * 数字转阿拉伯数字：中文语音结果里读出来的数字写成 325、3.5、50%(VoiceNumbers)。
     * 别人提的需求(2026-11-04)，默认关；只是一次文字扫描，没有可测的性能开销，不登记。
     */
    fun isSttArabicNumbers(context: Context) = getPrefs(context).getBoolean(KEY_STT_ARABIC_NUMBERS, false)
    fun setSttArabicNumbers(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_ARABIC_NUMBERS, enabled).apply()

    /**
     * 语音纠错：语音结果上屏前把用户列的错词换成正词(VoiceCorrections)。
     * 别人提的需求(2026-11-04)，默认关；只是一次文字扫描，没有可测的性能开销，不登记。词表随设置一起备份。
     */
    fun isSttCorrections(context: Context) = getPrefs(context).getBoolean(KEY_STT_CORRECTIONS, false)
    fun setSttCorrections(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_STT_CORRECTIONS, enabled).apply()
    fun getSttCorrectionsText(context: Context): String = getPrefs(context).getString(KEY_STT_CORRECTIONS_TEXT, "").orEmpty()
    fun setSttCorrectionsText(context: Context, text: String) =
        getPrefs(context).edit().putString(KEY_STT_CORRECTIONS_TEXT, text).apply()

    /**
     * 开车连续语音：语音悬浮条听写时发出消息或离开输入框，回到同一应用的输入框自动接着听。
     * 别人提的需求，默认关（用户 10-30 规则）；见 VoicePillResume。
     */
    /** 「用蓝牙耳机的麦克风」（#309）：默认关，用手机麦克风。 */
    const val KEY_STT_BLUETOOTH_MIC = "stt_bluetooth_mic"
    fun isSttBluetoothMic(context: Context): Boolean = getPrefs(context).getBoolean(KEY_STT_BLUETOOTH_MIC, false)
    fun setSttBluetoothMic(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_BLUETOOTH_MIC, enabled).apply()
    }

    fun isSttPillResume(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_PILL_RESUME, false)
    }

    fun setSttPillResume(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_PILL_RESUME, enabled).apply()
    }

    /**
     * 隔空输入（用户 2026-11-02 要的，参照微信输入法）：说完的话经剪贴板同步的连接发到电脑光标处，
     * 手机输入框只做预览不上屏。默认关；见 RemoteTyping。
     */
    fun isSttRemoteTyping(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_REMOTE_TYPING, false)
    }

    fun setSttRemoteTyping(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_REMOTE_TYPING, enabled).apply()
    }

    /**
     * 屏蔽外放音（用户 2026-11-04 要的，参照他截图的「压低音量 / 暂停播放 / 不屏蔽」，默认压低音量）：
     * 语音开麦时把正在放的音乐、视频压低或暂停，关麦恢复。开关麦各一次系统调用，没有可测的性能开销，不登记；见 VoiceAudioFocus。
     */
    fun getSttOtherAudio(context: Context): com.kingzcheung.xime.speech.VoiceOtherAudio =
        com.kingzcheung.xime.speech.VoiceOtherAudio.fromId(getPrefs(context).getString(KEY_STT_OTHER_AUDIO, null))

    fun setSttOtherAudio(context: Context, mode: com.kingzcheung.xime.speech.VoiceOtherAudio) {
        getPrefs(context).edit().putString(KEY_STT_OTHER_AUDIO, mode.id).apply()
    }

    /** VoiceAudioFocus 退回静音媒体音量时记下，进程中途结束也能在下次补回声音。 */
    internal fun isSttMusicMutedByVoice(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_STT_MUSIC_MUTED_BY_VOICE, false)

    internal fun setSttMusicMutedByVoice(context: Context, muted: Boolean) {
        // 记“静了”要立刻落盘，进程随后被杀也能补回；清除可以慢一点，残留只会多解一次静音。
        val editor = getPrefs(context).edit().putBoolean(KEY_STT_MUSIC_MUTED_BY_VOICE, muted)
        if (muted) editor.commit() else editor.apply()
    }

    /** VoiceAudioFocus 压低媒体音量时记下原音量（-1 表示没压），用途同上。 */
    internal fun getSttMusicVolumeBeforeVoice(context: Context): Int =
        getPrefs(context).getInt(KEY_STT_MUSIC_VOLUME_BEFORE_VOICE, -1)

    internal fun setSttMusicVolumeBeforeVoice(context: Context, volume: Int) {
        val editor = getPrefs(context).edit().putInt(KEY_STT_MUSIC_VOLUME_BEFORE_VOICE, volume)
        if (volume >= 0) editor.commit() else editor.apply()
    }

    // ---- 插件网络授权（per plugin per host） ----

    /** 插件已获用户授权的域名集合。 */
    fun getPluginAuthorizedHosts(context: Context, pluginId: String): Set<String> {
        val raw = getPrefs(context).getString("plugin_net_auth_$pluginId", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 授权插件访问指定域名。 */
    fun authorizePluginHost(context: Context, pluginId: String, host: String) {
        val hosts = getPluginAuthorizedHosts(context, pluginId) + host
        val pending = getPluginPendingHosts(context, pluginId) - host
        getPrefs(context).edit()
            .putString("plugin_net_auth_$pluginId", hosts.joinToString(","))
            .putString("plugin_net_pending_$pluginId", pending.joinToString(","))
            .apply()
    }

    /** 撤销插件对指定域名的授权。 */
    fun revokePluginHost(context: Context, pluginId: String, host: String) {
        val hosts = getPluginAuthorizedHosts(context, pluginId) - host
        getPrefs(context).edit()
            .putString("plugin_net_auth_$pluginId", hosts.joinToString(","))
            .apply()
    }

    /** 插件运行中被拒绝访问、待用户授权的域名集合（供 UI 显眼展示并引导授权）。 */
    fun getPluginPendingHosts(context: Context, pluginId: String): Set<String> {
        val raw = getPrefs(context).getString("plugin_net_pending_$pluginId", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 记录插件运行中被拒绝访问的域名（去重；返回 true 表示首次记录）。 */
    fun addPluginPendingHost(context: Context, pluginId: String, host: String): Boolean {
        val pending = getPluginPendingHosts(context, pluginId)
        if (host in pending) return false
        getPrefs(context).edit()
            .putString("plugin_net_pending_$pluginId", (pending + host).joinToString(","))
            .apply()
        return true
    }

    /** 清除插件的全部待授权记录。 */
    fun clearPluginPendingHosts(context: Context, pluginId: String) {
        getPrefs(context).edit().putString("plugin_net_pending_$pluginId", "").apply()
    }
    
    fun isSwipeUpHintsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SWIPE_UP_HINTS_ENABLED, true)
    }
    
    fun setSwipeUpHintsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SWIPE_UP_HINTS_ENABLED, enabled).apply()
    }
    
    fun isSwipeDownHintsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SWIPE_DOWN_HINTS_ENABLED, true)
    }

    fun setSwipeDownHintsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SWIPE_DOWN_HINTS_ENABLED, enabled).apply()
    }

    fun shouldShowPressBubble(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_PRESS_BUBBLE, true)
    }

    fun setShowPressBubble(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_PRESS_BUBBLE, show).apply()
    }
    
    /** 获取方案偏好的键盘布局，默认全键盘 */
    
    /** 保存方案偏好的键盘布局 */
    
    fun getKeyboardHeightDp(context: Context): Int {
        return getKeyboardHeightDp(context, false)
    }

    fun getKeyboardHeightDp(context: Context, isLandscape: Boolean): Int {
        val stored = storedFixedHeightDp(getPrefs(context), isLandscape, fixedGeometryScreen(context))
        // 横屏存值夹在范围内；下限按物理尺寸算（11-22），横屏调矮的值不被拉回 244dp。
        if (stored > 0) return if (isLandscape) stored.coerceIn(com.kingzcheung.xime.ui.keyboard.keyboardHeightBounds(
            context.resources.configuration.screenHeightDp, true,
            minHeightDp = com.kingzcheung.xime.ui.keyboard.keyboardHeightFloorDp(context))) else stored
        // 横竖屏各存一份：横屏没调过就用横屏默认高度，不再借竖屏存的高度（竖屏 40% 多的高度放到横屏会盖住大半个屏幕，用户 11-02）。
        return getDefaultKeyboardHeightDp(context, isLandscape)
    }

    fun setKeyboardHeightDp(context: Context, heightDp: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE else KEY_KEYBOARD_HEIGHT_DP
        val screen = fixedGeometryScreen(context)
        // 和固定宽度一样（#37）：不带屏的旧键照样写（监听它的地方不变），另存一份带屏的，并记下是哪块屏调的。
        getPrefs(context).edit().putInt(key, heightDp)
            .putInt("${key}_sw$screen", heightDp)
            .putInt(fixedHeightScreenKey(isLandscape), screen).apply()
    }

    private fun fixedHeightScreenKey(isLandscape: Boolean) =
        if (isLandscape) "keyboard_height_screen_landscape" else "keyboard_height_screen"

    /**
     * 这块屏存的固定键盘高度，0 = 没调过（GitHub #396）。折叠屏外屏、内屏都是竖屏，原来共用一份，
     * 外屏调好的高度展开后带到内屏。规则同 [resolveFixedGeometry]：旧版本存的、同一块屏上的照旧用；
     * 换到大小差很多的另一块屏时用那块屏自己的，没调过按默认高度。单屏手机、平板不变。
     */
    internal fun storedFixedHeightDp(prefs: SharedPreferences, isLandscape: Boolean, screen: Int): Int {
        val key = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE else KEY_KEYBOARD_HEIGHT_DP
        val own = "${key}_sw$screen"
        return resolveFixedGeometry(
            ownValue = if (prefs.contains(own)) prefs.getInt(own, 0) else null,
            sharedValue = prefs.getInt(key, 0).coerceAtLeast(0),
            sharedScreen = prefs.getInt(fixedHeightScreenKey(isLandscape), 0),
            screen = screen,
        )
    }

    internal fun storedFixedHeightDp(context: Context, isLandscape: Boolean): Int =
        storedFixedHeightDp(getPrefs(context), isLandscape, fixedGeometryScreen(context))

    fun getDefaultKeyboardHeightDp(context: Context, isLandscape: Boolean = false): Int {
        val config = context.resources.configuration
        if (!isLandscape) return maxOf(config.screenWidthDp, config.screenHeightDp) * DEFAULT_KEYBOARD_HEIGHT_PERCENT / 100
        // 横屏以竖屏高度（长边）为基数：横屏短边随宽高比/系统栏波动大，
        // 长边是设备稳定值，且与悬浮模式 fallback（portraitScreenHeightDp × 百分比）语义一致
        val portraitHeightDp = maxOf(config.screenWidthDp, config.screenHeightDp)
        return (portraitHeightDp * DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE / 100).coerceIn(com.kingzcheung.xime.ui.keyboard.keyboardHeightBounds(config.screenHeightDp, true))
    }

    const val KEY_KEYBOARD_BOTTOM_PADDING_DP = "keyboard_bottom_padding_dp"
    internal const val KEY_KEYBOARD_BOTTOM_LOWERING_DP = "keyboard_bottom_lowering_dp"
    const val KEY_EDGE_TO_EDGE_KEYBOARD = "edge_to_edge_keyboard"

    /**
     * 旧的「手势导航时键盘贴底」（界面已并进「底部位置」）。现在只用来给没选过底部位置的老用户定档：
     * 关过的算「留出导航按钮」，见 [keyboardBottomPosition]。贴底算法和调低都不再看它（11-19 盘点）。
     */
    fun edgeToEdgeKeyboard(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_EDGE_TO_EDGE_KEYBOARD, true)

    fun setEdgeToEdgeKeyboard(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EDGE_TO_EDGE_KEYBOARD, enabled).apply()
    }

    /**
     * 「底部位置」（用户 11-08：贴底改了多少版都有人说坏了，给几个简单选项）：
     * 自动 = 1.15.66 的贴底算法原样不动；贴到最底 = 不给导航按钮留位（全面屏隐藏了切换键盘、收起键盘按钮的机型）；
     * 留出导航按钮 = 整条导航栏都留着，键盘不盖住也不被那两个按钮盖住。原「手势导航时键盘贴底」开关并进这里：
     * 没选过时，关过贴底的老用户算「留出导航按钮」，其余算「自动」。
     */
    enum class KeyboardBottomPosition(val label: String, val description: String) {
        AUTO("自动", "按导航方式自动选择"),
        FLUSH("贴到最底", "键盘贴到屏幕最底边"),
        KEEP_BUTTONS("留出导航按钮", "给底部的导航按钮留出位置"),
    }

    const val KEY_KEYBOARD_BOTTOM_POSITION = "keyboard_bottom_position"

    fun keyboardBottomPosition(context: Context): KeyboardBottomPosition {
        val prefs = getPrefs(context)
        prefs.getString(KEY_KEYBOARD_BOTTOM_POSITION, null)?.let { stored ->
            KeyboardBottomPosition.entries.firstOrNull { it.name == stored }?.let { return it }
        }
        return if (!prefs.getBoolean(KEY_EDGE_TO_EDGE_KEYBOARD, true)) KeyboardBottomPosition.KEEP_BUTTONS
            else KeyboardBottomPosition.AUTO
    }

    /** 选一档同时写贴底开关：自动和贴到最底按贴底算（调低从贴底位置往下），留出导航按钮按不贴底算。 */
    fun setKeyboardBottomPosition(context: Context, position: KeyboardBottomPosition) {
        getPrefs(context).edit().putString(KEY_KEYBOARD_BOTTOM_POSITION, position.name)
            .putBoolean(KEY_EDGE_TO_EDGE_KEYBOARD, position != KeyboardBottomPosition.KEEP_BUTTONS).apply()
    }

    private const val DEFAULT_KEYBOARD_BOTTOM_PADDING_DP = 0

    /**
     * 底部留白、调低按横竖屏分开记（#329：1.15.69 撤回 1.15.68 的贴底算法时连分开记也一起撤了，调竖屏横屏也跟着变）。
     * 只分开存值，贴底算法不变。后缀和 1.15.68 用过的 _portrait/_landscape 不同，那一版留下的旧值不会冒出来；
     * 没调过的方向沿用不带后缀的旧值，升级后两个方向都和升级前一样。
     */
    private fun bottomOrientationSuffix(context: Context): String =
        if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            BOTTOM_LANDSCAPE_SUFFIX else BOTTOM_PORTRAIT_SUFFIX
    private const val BOTTOM_PORTRAIT_SUFFIX = "@portrait"
    private const val BOTTOM_LANDSCAPE_SUFFIX = "@landscape"

    /** 改的是底部留白或调低（哪个方向都算）：键盘和设置页要重新量。 */
    internal fun isBottomCalibrationKey(key: String?): Boolean =
        when (key?.removeSuffix(BOTTOM_PORTRAIT_SUFFIX)?.removeSuffix(BOTTOM_LANDSCAPE_SUFFIX)) {
            KEY_KEYBOARD_BOTTOM_PADDING_DP, KEY_KEYBOARD_BOTTOM_LOWERING_DP, KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP -> true
            else -> false
        }

    private fun bottomCalibrationDp(context: Context, key: String, default: Int = 0): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(key + bottomOrientationSuffix(context), prefs.getInt(key, default)).coerceAtLeast(0)
    }

    fun getKeyboardBottomPaddingDp(context: Context): Int =
        bottomCalibrationDp(context, KEY_KEYBOARD_BOTTOM_PADDING_DP, DEFAULT_KEYBOARD_BOTTOM_PADDING_DP)

    /**
     * 旧字段始终保存非负留白，低于系统建议值的校准单独保存。
     * 贴底时的「调低」另存一项（[KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP]，从贴底位置往下算）：贴底默认打开后，
     * 原来按系统建议调低的值不会被搬到贴底位置下面去，三键导航时也就不会压到系统按钮。
     */
    internal fun getKeyboardBottomAdjustmentDp(context: Context): Int =
        getKeyboardBottomPaddingDp(context).takeIf { it > 0 } ?: -keyboardBottomLoweringDp(context)

    internal const val KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP = "keyboard_bottom_edge_lowering_dp"

    /**
     * 「调低」只存一套（11-19 盘点冲突 2）：三档都从各自位置往下调，都读写 [KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP]。
     * 原来跟着隐藏的贴底开关换键，在「留出导航按钮」和另两档之间切换时调低的值会丢掉或冒回来。
     * 升级前停在「留出导航按钮」、调低存在旧键里的，新键还没写过时接着用旧键的值；之后都写新键。
     */
    private fun keyboardBottomLoweringDp(context: Context): Int {
        val prefs = getPrefs(context)
        val suffix = bottomOrientationSuffix(context)
        val keys = listOf(KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP + suffix, KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP) +
            if (keyboardBottomPosition(context) == KeyboardBottomPosition.KEEP_BUTTONS)
                listOf(KEY_KEYBOARD_BOTTOM_LOWERING_DP + suffix, KEY_KEYBOARD_BOTTOM_LOWERING_DP) else emptyList()
        return keys.firstOrNull(prefs::contains)?.let { prefs.getInt(it, 0) }?.coerceAtLeast(0) ?: 0
    }

    fun setKeyboardBottomPaddingDp(context: Context, paddingDp: Int) {
        setKeyboardBottomAdjustmentDp(context, paddingDp.coerceAtLeast(0))
    }

    internal fun setKeyboardBottomAdjustmentDp(context: Context, adjustmentDp: Int) {
        // 只写当前方向；调低清零时写 0 而不是删掉，不然会退回不带后缀的旧值。
        val suffix = bottomOrientationSuffix(context)
        getPrefs(context).edit().putInt(KEY_KEYBOARD_BOTTOM_PADDING_DP + suffix, adjustmentDp.coerceAtLeast(0))
            .putInt(KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP + suffix, (-adjustmentDp).coerceAtLeast(0)).apply()
    }

    /** 键盘上次量到的「最多还能调低多少 dp」（系统建议值减安全下限，按当前方向）；没量过为 null。 */
    internal const val KEY_KEYBOARD_BOTTOM_LOWERING_LIMIT_DP = "keyboard_bottom_lowering_limit_dp"

    internal fun getKeyboardBottomLoweringLimitDp(context: Context): Int? =
        getPrefs(context).getInt(KEY_KEYBOARD_BOTTOM_LOWERING_LIMIT_DP, -1).takeIf { it >= 0 }

    /** 键盘量到新的下限时写一次；值没变不写，免得每次 insets 回调都触发设置页刷新。 */
    internal fun setKeyboardBottomLoweringLimitDp(context: Context, limitDp: Int) {
        val value = limitDp.coerceAtLeast(0)
        if (getKeyboardBottomLoweringLimitDp(context) != value)
            getPrefs(context).edit().putInt(KEY_KEYBOARD_BOTTOM_LOWERING_LIMIT_DP, value).apply()
    }

    /** 键盘上次量到的底部诊断行（[com.kingzcheung.xime.service.bottomNavDiagnostic]），设置页显示；没量过为 null。 */
    internal const val KEY_KEYBOARD_BOTTOM_DIAGNOSTIC = "keyboard_bottom_diagnostic"

    internal fun getKeyboardBottomDiagnostic(context: Context): String? =
        getPrefs(context).getString(KEY_KEYBOARD_BOTTOM_DIAGNOSTIC, null)

    /** 内容变了才写，免得每次 insets 回调都触发设置页刷新。 */
    internal fun setKeyboardBottomDiagnostic(context: Context, line: String) {
        if (getKeyboardBottomDiagnostic(context) != line)
            getPrefs(context).edit().putString(KEY_KEYBOARD_BOTTOM_DIAGNOSTIC, line).apply()
    }

    internal fun resetKeyboardBottomCalibration(context: Context) {
        // 恢复默认：「底部位置」回到自动，留白和调低清零（贴底开关只为旧版本兼容一起写成开）。
        getPrefs(context).edit().putBoolean(KEY_EDGE_TO_EDGE_KEYBOARD, true)
            .putString(KEY_KEYBOARD_BOTTOM_POSITION, KeyboardBottomPosition.AUTO.name).apply {
                for (key in listOf(KEY_KEYBOARD_BOTTOM_PADDING_DP, KEY_KEYBOARD_BOTTOM_LOWERING_DP, KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP))
                    for (suffix in listOf("", BOTTOM_PORTRAIT_SUFFIX, BOTTOM_LANDSCAPE_SUFFIX)) remove(key + suffix)
            }.apply()
    }

    fun isSchemaImportWarningDismissed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCHEMA_IMPORT_WARNING_DISMISSED, false)
    }

    fun setSchemaImportWarningDismissed(context: Context, dismissed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCHEMA_IMPORT_WARNING_DISMISSED, dismissed).apply()
    }

    // ── 悬浮键盘设置 ──

    private const val KEY_FLOATING_MODE = "floating_mode"
    /** 展开菜单里直接编辑快捷入口（PR #232）；默认关，关着时菜单和以前一样，排列在「排列」面板里改。 */
    internal const val KEY_INLINE_QUICK_ACTION_EDITING = "inline_quick_action_editing"
    fun inlineQuickActionEditing(context: Context): Boolean = getPrefs(context).getBoolean(KEY_INLINE_QUICK_ACTION_EDITING, false)
    fun setInlineQuickActionEditing(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_INLINE_QUICK_ACTION_EDITING, enabled).apply()
    }

    internal const val KEY_FLOATING_GEOMETRY_LOCKED = "floating_geometry_locked"
    fun floatingGeometryLocked(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FLOATING_GEOMETRY_LOCKED, false)
    fun setFloatingGeometryLocked(context: Context, locked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FLOATING_GEOMETRY_LOCKED, locked).apply()
    }
    private const val KEY_FLOATING_MODE_LANDSCAPE = "floating_mode_landscape"
    private const val KEY_FLOATING_OFFSET_X = "floating_offset_x"
    private const val KEY_FLOATING_OFFSET_X_LANDSCAPE = "floating_offset_x_landscape"
    private const val KEY_FLOATING_OFFSET_Y = "floating_offset_y"
    private const val KEY_FLOATING_OFFSET_Y_LANDSCAPE = "floating_offset_y_landscape"
    private const val KEY_FLOATING_ROTATION = "floating_rotation"
    private const val KEY_FLOATING_ROTATION_LANDSCAPE = "floating_rotation_landscape"
    private const val KEY_FLOATING_WIDTH_DP = "floating_width_dp"
    private const val KEY_FLOATING_WIDTH_DP_LANDSCAPE = "floating_width_dp_landscape"
    private const val KEY_KEYBOARD_OPACITY = "keyboard_opacity"

    fun getKeyboardOpacity(context: Context): Float {
        val landscape = context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp
        // 横屏新装和竖屏一样固定、不透明（用户 10-31）。早先版本第一次横屏时写入了半透明悬浮（landscape_layout_v2），
        // 写过的人保留原值；没写过的按这里的默认。
        return getPrefs(context).getFloat(if (landscape) "keyboard_opacity_landscape" else KEY_KEYBOARD_OPACITY, 1f).coerceIn(0.3f, 1f)
    }

    fun setKeyboardOpacity(context: Context, opacity: Float) {
        val landscape = context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp
        getPrefs(context).edit().putFloat(if (landscape) "keyboard_opacity_landscape" else KEY_KEYBOARD_OPACITY, opacity.coerceIn(0.3f, 1f)).apply()
    }

    fun isFloatingMode(context: Context, isLandscape: Boolean = false): Boolean {
        val key = if (isLandscape) KEY_FLOATING_MODE_LANDSCAPE else KEY_FLOATING_MODE
        return getPrefs(context).getBoolean(key, false)
    }

    fun setFloatingMode(context: Context, enabled: Boolean, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_MODE_LANDSCAPE else KEY_FLOATING_MODE
        getPrefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun getFloatingOffsetX(context: Context, isLandscape: Boolean = false): Int {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_X_LANDSCAPE else KEY_FLOATING_OFFSET_X
        return getPrefs(context).getInt(key, 0)
    }

    fun setFloatingOffsetX(context: Context, offset: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_X_LANDSCAPE else KEY_FLOATING_OFFSET_X
        getPrefs(context).edit().putInt(key, offset).apply()
    }

    fun getFloatingOffsetY(context: Context, isLandscape: Boolean = false): Int =
        getFloatingOffsetY(context, isLandscape, 0)

    internal fun getFloatingOffsetY(context: Context, isLandscape: Boolean = false, defaultDp: Int): Int {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_Y_LANDSCAPE else KEY_FLOATING_OFFSET_Y
        return getPrefs(context).getInt(key, defaultDp)
    }

    fun setFloatingOffsetY(context: Context, offset: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_Y_LANDSCAPE else KEY_FLOATING_OFFSET_Y
        getPrefs(context).edit().putInt(key, offset).apply()
    }

    /** 悬浮键盘旋转角（度，顺时针为正，-90..90），和位置一样横竖屏各存一份。 */
    fun getFloatingRotation(context: Context, isLandscape: Boolean = false): Float {
        val key = if (isLandscape) KEY_FLOATING_ROTATION_LANDSCAPE else KEY_FLOATING_ROTATION
        return getPrefs(context).getFloat(key, 0f).coerceIn(-90f, 90f)
    }

    fun setFloatingRotation(context: Context, degrees: Float, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_ROTATION_LANDSCAPE else KEY_FLOATING_ROTATION
        getPrefs(context).edit().putFloat(key, degrees.coerceIn(-90f, 90f)).apply()
    }

    /**
     * 悬浮键盘宽度（dp）；0 = 未设置，按高度自动推导（历史行为）。
     * 用户在键盘调节里拖左右边或四角后写入具体值，之后宽度与高度各自独立，
     * 「点哪个边只调哪个方向」才有意义。
     */
    fun getFloatingWidthDp(context: Context, isLandscape: Boolean = false): Int {
        val key = if (isLandscape) KEY_FLOATING_WIDTH_DP_LANDSCAPE else KEY_FLOATING_WIDTH_DP
        return getPrefs(context).getInt(key, 0)
    }

    fun setFloatingWidthDp(context: Context, widthDp: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_WIDTH_DP_LANDSCAPE else KEY_FLOATING_WIDTH_DP
        getPrefs(context).edit().putInt(key, widthDp).apply()
    }

    /**
     * 固定键盘横竖屏独立，并且每块屏各存一份；0 宽度代表铺满宿主，偏移相对宿主中心。
     * 折叠屏的外屏、内屏都是竖屏，原来共用一份，外屏调好的宽度展开后带到内屏，内屏占不满（GitHub #37）。
     * 屏按最短边 dp 区分（转屏不变，外屏和内屏差得很远）。
     */
    fun getFixedWidthDp(context: Context, landscape: Boolean): Int = fixedGeometry(context, "fixed_width_dp", landscape)

    fun getFixedOffsetX(context: Context, landscape: Boolean): Int = fixedGeometry(context, "fixed_offset_x", landscape)

    fun setFixedHorizontalGeometry(context: Context, width: Int, offset: Int, landscape: Boolean) {
        val suffix = if (landscape) "_landscape" else ""
        val screen = fixedGeometryScreen(context)
        // 不带屏的旧键照样写，并记下是哪块屏调的；另存一份带屏的，换回这块屏时用。
        getPrefs(context).edit().putInt("fixed_width_dp$suffix", width)
            .putInt("fixed_offset_x$suffix", offset)
            .putInt("fixed_width_dp${suffix}_sw$screen", width)
            .putInt("fixed_offset_x${suffix}_sw$screen", offset)
            .putInt("fixed_geometry_screen$suffix", screen).apply()
    }

    private fun fixedGeometryScreen(context: Context): Int = context.resources.configuration.smallestScreenWidthDp

    private fun fixedGeometry(context: Context, name: String, landscape: Boolean): Int {
        val prefs = getPrefs(context)
        val suffix = if (landscape) "_landscape" else ""
        val screen = fixedGeometryScreen(context)
        val own = "$name${suffix}_sw$screen"
        return resolveFixedGeometry(
            ownValue = if (prefs.contains(own)) prefs.getInt(own, 0) else null,
            sharedValue = prefs.getInt("$name$suffix", 0),
            sharedScreen = prefs.getInt("fixed_geometry_screen$suffix", 0),
            screen = screen,
        )
    }

    /**
     * 不带屏的那一份是最后一次调节写的，在调它的那块屏上照旧用它；旧版本存的值不知道是哪块屏，也照旧用，
     * 单屏手机、平板升级后不变，改系统显示大小这类小变化（最短边差不到 15%）也算同一块屏。
     * 换到大小差很多的另一块屏（折叠屏外屏↔内屏）时用那块屏自己存的；那块屏没调过就铺满、居中（默认）。
     */
    internal fun resolveFixedGeometry(ownValue: Int?, sharedValue: Int, sharedScreen: Int, screen: Int): Int {
        if (sharedScreen <= 0 || screen <= 0) return sharedValue
        val sameScreen = kotlin.math.abs(sharedScreen - screen) * 100 <= maxOf(sharedScreen, screen) * 15
        return if (sameScreen) sharedValue else ownValue ?: 0
    }

    fun getPageSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_PAGE_SIZE, DEFAULT_PAGE_SIZE)
    }

    fun setPageSize(context: Context, pageSize: Int) {
        getPrefs(context).edit().putInt(KEY_PAGE_SIZE, pageSize).apply()
    }

    const val DEFAULT_CANDIDATE_TEXT_SIZE = 19

    fun getCandidateTextSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_CANDIDATE_TEXT_SIZE, DEFAULT_CANDIDATE_TEXT_SIZE)
    }

    fun setCandidateTextSize(context: Context, size: Int) {
        getPrefs(context).edit().putInt(KEY_CANDIDATE_TEXT_SIZE, size).apply()
    }

    // ── 方案市场「已安装」的持久记录 ──
    // 记录用户通过市场主动安装过的方案 id；与本地文件存在性解耦（方案可能仅作为依赖落盘，
    // 文件存在不代表用户装过它），且跨重启保持。
    fun getInstalledMarketIds(context: Context): Set<String> =
        getPrefs(context).getStringSet(KEY_INSTALLED_MARKET_IDS, emptySet())?.toSet() ?: emptySet()

    fun addInstalledMarketId(context: Context, id: String) {
        val cur = getInstalledMarketIds(context).toMutableSet()
        if (cur.add(id)) {
            getPrefs(context).edit().putStringSet(KEY_INSTALLED_MARKET_IDS, cur).apply()
        }
    }

    fun removeInstalledMarketId(context: Context, id: String) {
        val cur = getInstalledMarketIds(context).toMutableSet()
        if (cur.remove(id)) {
            getPrefs(context).edit().putStringSet(KEY_INSTALLED_MARKET_IDS, cur).apply()
        }
    }

    const val KEY_CLIPBOARD_SYNC_ENABLED = "clipboard_sync_enabled"
    const val KEY_CLIPBOARD_SYNC_PLUGIN_ID = "clipboard_sync_plugin_id"

    fun isClipboardSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CLIPBOARD_SYNC_ENABLED, false)
    }

    fun setClipboardSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLIPBOARD_SYNC_ENABLED, enabled).apply()
    }

    fun getClipboardSyncPluginId(context: Context): String {
        return getPrefs(context).getString(KEY_CLIPBOARD_SYNC_PLUGIN_ID, "") ?: ""
    }

    fun setClipboardSyncPluginId(context: Context, pluginId: String) {
        getPrefs(context).edit().putString(KEY_CLIPBOARD_SYNC_PLUGIN_ID, pluginId).apply()
    }

    /** 其他同步服务（WebDAV 等）的自动拉取间隔，秒（#45）。默认 30，和以前一样。 */
    const val KEY_CLIPBOARD_SYNC_PULL_INTERVAL_S = "clipboard_sync_pull_interval_s"
    /** 键盘开着时按上面的间隔一直拉取（#45），默认关。 */
    const val KEY_CLIPBOARD_SYNC_KEEP_PULLING = "clipboard_sync_keep_pulling"

    fun getClipboardSyncPullIntervalS(context: Context): Int =
        getPrefs(context).getInt(KEY_CLIPBOARD_SYNC_PULL_INTERVAL_S, 30).coerceIn(5, 60)

    fun setClipboardSyncPullIntervalS(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_CLIPBOARD_SYNC_PULL_INTERVAL_S, seconds.coerceIn(5, 60)).apply()
    }

    fun isClipboardSyncKeepPulling(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CLIPBOARD_SYNC_KEEP_PULLING, false)

    fun setClipboardSyncKeepPulling(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLIPBOARD_SYNC_KEEP_PULLING, enabled).apply()
    }
}

/**
 * 候选栏右边那个键（设置「候选栏按钮」里的三张卡，11-24 他定）：
 * [EXPAND]「默认」= 1.15.101，右 ▾ 展开；[HIDE]「收键盘」= 1.15.103，右 ⌄ 一直收起键盘、左「‹」取消；
 * [CLOSE]「经典」= 右 × 先关候选／联想，同一处再点收起键盘。后两种展开候选靠左右滑。
 */
enum class CandidateRightKey { EXPAND, HIDE, CLOSE }
