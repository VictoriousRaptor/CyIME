package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.KeysConfigHelper

/**
 * 「按键符号」中按位置设置第二输入的固定栏。每个位置可填一个符号或一个短语。
 * [slots] 为固定格数（手写侧栏 3 格、展开栏 6 格）；null 表示可增删、超出时键盘侧滚动。
 */
enum class SideSymbolTarget(val key: String, val label: String, val where: String, val slots: Int?, val vertical: Boolean) {
    T9("t9", "九键侧栏", "九键左侧一列，空闲时显示", null, true),
    STROKE("stroke", "笔画侧栏", "笔画键盘左侧一列", null, true),
    HANDWRITING("handwriting", "手写侧栏", "手写键盘右侧删除键下方三格", 3, true),
    HANDWRITING_ROW("handwriting_row", "手写展开栏", "全屏手写底部删除键左边一排", 6, false),
    COMMON("common", "常用符号栏", "符号键盘「常用」页最前面", null, false),
    /** 26 键、14 键字母上方的一排（「键盘布局 → 数字行」打开，默认关）；格数不限，所有格平分这一排。 */
    NUMBER_ROW("number_row", "数字行", "字母上方一排，在「键盘布局」里打开", null, false),
    /** 数字键盘左侧一列（用户 11-09：原来固定 + - * /，不能改、不能滑）；多于四格时上下滑。 */
    NUMBER("number", "数字侧栏", "数字键盘左侧一列，多于 4 格时上下滑", null, true);

    val prefKey: String get() = "side_symbols_$key"

    /** 未设置时键盘使用的默认内容（九键、笔画来自 xime.yaml）。 */
    fun defaults(): List<String> = when (this) {
        T9 -> KeysConfigHelper.getT9SideSymbols()
        STROKE -> KeysConfigHelper.getStrokeSideSymbols()
        HANDWRITING -> listOf("？", "，", "。")
        HANDWRITING_ROW -> listOf("；", "：", "！", "？", "，", "。")
        COMMON -> emptyList()
        NUMBER_ROW -> NUMBER_ROW_DEFAULT
        NUMBER -> NUMBER_SIDE_DEFAULT
    }

    /** 旧的共用快捷符号列表只给原来那几条栏兜底；数字行、数字侧栏是新栏，没单独设置就用自己的默认，不能被它顶掉。 */
    val usesLegacyList: Boolean get() = this != NUMBER_ROW && this != NUMBER

    /** 键盘实际显示的内容：固定格数的栏位不足时用默认补齐，多出的截掉。 */
    fun resolve(custom: List<String>?): List<String> = slots?.let { n ->
        val own = custom.orEmpty().take(n)
        own + defaults().drop(own.size).take(n - own.size)
    } ?: (custom ?: defaults())

    companion object {
        val NUMBER_ROW_DEFAULT = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        /** 数字键盘左栏默认的四个运算符，和改动前一样。 */
        val NUMBER_SIDE_DEFAULT = listOf("+", "-", "*", "/")

        fun parse(raw: String?): List<String>? = raw?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }
}
