package com.kingzcheung.xime.ui.keyboard

/**
 * 九键、笔画、数字键盘共用的骨架：左栏 | 主键区 | 右栏。
 * 键盘本身和「按键符号」编辑页的布局示意都从这里取宽高比例，改了这里两边一起变，编辑页画出的位置和键盘对得上。
 */
internal object NineGridFrame {
    /** 左栏、主键区、右栏的宽度比。 */
    const val SIDE = 0.8f
    const val MAIN = 3.4f
    const val ACTION = 0.8f
    /** 九键、笔画左栏：上面三排高是符号面板，最下一排是 !@# 键。 */
    const val SIDE_PANEL_ROWS = 3f
    /** 九键、笔画最下一排：123 | 空格 | 语言；数字键盘底排的两个模式键也是这么宽。 */
    const val BOTTOM_MODE = 0.8f
    const val BOTTOM_SPACE = 1.8f
    const val BOTTOM_LANGUAGE = 0.8f
    fun spaceWeight(cycle: Boolean) = BOTTOM_SPACE + if (cycle) BOTTOM_MODE else 0f

    /** 左栏符号面板一屏放几格，多出的上下滑。 */
    const val SIDE_VISIBLE_SLOTS = 4
}

/**
 * 手写键盘的骨架：画板 + 右侧一列（删除 + 手写侧栏三格），底下一排和 26 键底排同权重；
 * 全屏手写时右侧一列收起，底部多一排（手写展开栏六格 + 删除）。键盘和「按键符号 › 手写」的示意都从这里取。
 */
internal object HandwritingFrame {
    val bottom: QwertyBottomRowWeights get() = QwertyBottomRowWeights.Standard
    /** 底排占整块高度的比例；右侧一列每格和它一样高（删除 + 三格 = 画板高）。 */
    const val FOOTER_FRACTION = 0.2f
    /** 右侧一列、全屏时那一排的删除键宽：和回车一样宽，占整宽的比例。 */
    val sideKeyFraction: Float get() = bottom.enter / bottom.total

    /** 底排从左到右：!@#、123、全屏（全屏时是收起，占 26 键标点键的位置）、空格、语言、回车。 */
    fun bottomKeys(expanded: Boolean, cycle: Boolean = false, languageOnLeft: Boolean = false) = listOfNotNull(
        "symbol" to bottom.mode,
        ("number" to bottom.mode).takeUnless { cycle },
        ("ime_switch" to bottom.language).takeIf { languageOnLeft },
        (if (expanded) "collapse" else "expand") to bottom.punctuation,
        "space" to (bottom.space + if (cycle) bottom.mode else 0f),
        ("ime_switch" to bottom.language).takeUnless { languageOnLeft },
        "enter" to bottom.enter)
}
