package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate

/**
 * 展开候选数据过滤与展示分行（纯逻辑，可 JVM 单测）。
 * 展开页只渲染可见行，行分组按字符当量估算、仅作展示分组。
 */
object ExpandedCandidatePager {

    /** 条目固定开销：水平 padding(8dp) + 两侧间隙分摊(6dp)，以 18sp 字宽为 1 单位近似 */
    const val ITEM_FIXED_UNITS = 1.2f

    /** 拼音注释字号相对候选字号的比例；展开页画字和估算宽度共用，跟着候选字号一起缩放（原 11/18 太小看不清，用户 11-02） */
    const val COMMENT_SIZE_RATIO = 0.75f

    /** 单字符宽度（字符当量）：CJK 约 1 个字宽，其余（拉丁/数字/标点）约 0.55 */
    fun charUnits(c: Char): Float = if (c.code > 0x2E80) 1.0f else 0.55f

    /** 条目估算宽度：候选词全字宽 + 注释按小字号折算 + 固定开销 */
    fun estimateItemUnits(candidate: RimeCandidate): Float {
        var units = ITEM_FIXED_UNITS
        candidate.text.forEach { units += charUnits(it) }
        candidate.comment.forEach { units += charUnits(it) * COMMENT_SIZE_RATIO }
        return units
    }

    /** 中间候选区的实际宽度（已扣除侧栏和间距），以 18sp 字宽为一单位。 */
    fun rowWidthUnits(contentWidthPx: Float, scaledDensity: Float): Float =
        (contentWidthPx / (18f * scaledDensity)).coerceAtLeast(1f)

    /**
     * 按字符当量估算贪心分行。分行仅作展示分组：估算偏差只改变每行词数，
     * 行内条目宽度自适应拉伸，不会溢出。
     */
    fun flowRows(
        filtered: List<Int>,
        all: List<RimeCandidate>,
        rowWidthUnits: Float,
    ): List<List<Int>> {
        if (filtered.isEmpty()) return emptyList()
        val rows = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentUnits = 0f
        for (index in filtered) {
            val itemUnits = estimateItemUnits(all[index])
            val withItem = if (current.isEmpty()) itemUnits else currentUnits + itemUnits
            if (current.isNotEmpty() && withItem > rowWidthUnits) {
                rows.add(current)
                current = mutableListOf(index)
                currentUnits = itemUnits
            } else {
                current.add(index)
                currentUnits = withItem
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    /**
     * 过滤后的全局索引列表。
     * @param singleCharOnly true 时只保留单字候选（筛选单字功能）
     * @param fromIndex 起始偏移：跳过候选栏已显示的前若干个候选，避免展开页重复
     */
    fun filterIndices(
        all: List<RimeCandidate>,
        singleCharOnly: Boolean,
        fromIndex: Int = 0,
    ): List<Int> {
        val filtered = if (!singleCharOnly) all.indices.toList()
        else all.indices.filter { all[it].text.isSingleCharacter() }
        return filtered.drop(fromIndex.coerceIn(0, filtered.size))
    }
}

/**
 * “筛选单字”的单字：一个 Unicode 字符，补充平面汉字（𠮷 占两个 UTF-16 单元）也算。
 * 表情和图形符号（🐷、™ 这类 So 类字符）不算字：长编码点「单字」时它们排在词组中间，
 * 算进来就会只剩一排表情、看不到字。
 * 候选栏、展开页、实体键盘候选窗共用，同一个候选在哪里都是同一个判断（审计 F09）。
 */
internal fun String.isSingleCharacter(): Boolean = isNotEmpty() && codePointCount(0, length) == 1 &&
    Character.getType(codePointAt(0)) != Character.OTHER_SYMBOL.toInt()
