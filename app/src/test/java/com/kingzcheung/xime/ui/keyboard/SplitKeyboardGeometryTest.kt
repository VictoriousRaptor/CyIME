package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分体键盘 = 正常 26 键 + 每行中线处同宽的一条缝。
 * 每个键只出现一次，左右两半各自保持正常键盘的上下错位。
 */
class SplitKeyboardGeometryTest {
    private val top = "qwertyuiop".map { it.toString() }
    private val middle = "asdfghjkl".map { it.toString() }
    private val bottom = "zxcvbnm".map { it.toString() }

    @Test fun everyKeyAppearsOnceAndRowsSplitAtTheMidline() {
        for (stagger in listOf(0f, 0.5f, 1f)) {
            val g = LetterKeyboardFrame(listOf(10, 9, 7)).geometry(1f, stagger)
            val counts = listOf(
                SplitKeyboardGeometry.leftCount(top, 0f, false),
                SplitKeyboardGeometry.leftCount(middle, g.middleRowInsetDp, false),
                SplitKeyboardGeometry.leftCount(bottom, g.outerKeyWeight, false),
            )
            // qwert|yuiop, asdfg|hjkl, zxcv|bnm：没有重复的 g / v。
            assertEquals("stagger=$stagger", listOf(5, 5, 4), counts)
        }
    }

    /** 屏幕太窄（手机竖屏）时分体自动用完整键盘；手机横屏、平板竖屏和横屏照旧分体（用户 11-05）。 */
    @Test fun narrowScreensFallBackToTheFullKeyboard() {
        for (phonePortrait in listOf(320, 360, 393, 412, 480, 599))
            assertEquals("width=$phonePortrait", false, SplitKeyboardGeometry.fitsScreen(phonePortrait))
        for (wide in listOf(600, 640, 732, 853, 915, 1280))
            assertEquals("width=$wide", true, SplitKeyboardGeometry.fitsScreen(wide))
    }

    @Test fun customTenKeyRowsUseTheSameRule() {
        val colemakMiddle = "arstdhneio".map { it.toString() }
        assertEquals(5, SplitKeyboardGeometry.leftCount(colemakMiddle, 0f, false))
        assertEquals(4, SplitKeyboardGeometry.leftCount("zxcvbkm".map { it.toString() }, 1.5f, false))
        // 合并键按实际占位计算；中心正好压在中线上的键归左半（与 g 留在左边同一规则）。
        assertEquals(3, SplitKeyboardGeometry.leftCount(listOf("qw", "er", "ty", "ui", "op"), 0f, true))
        assertEquals(2, SplitKeyboardGeometry.leftCount(listOf("qw", "er", "ty", "ui", "op"), 0.5f, true))
    }

    @Test fun customWidthsMoveTheSplitToTheActualFingerBoundary() {
        // 默认每键一格时 qwert|yuiop；q 调到 2.5 格后 r 的中心已越过中线，缝落在 r 之前。
        assertEquals(5, SplitKeyboardGeometry.leftCount(top, 0f, true, top.map { 1f }))
        val widths = top.map { if (it == "q") 2.5f else 1f }
        assertEquals(4, SplitKeyboardGeometry.leftCount(top, 0f, true, widths))
        // 没有宽度的行仍按字母数，传入的 null 不改原有结果。
        assertEquals(5, SplitKeyboardGeometry.leftCount(top, 0f, true, null))
    }

    @Test fun gapKeepsEachHalfIdenticalToTheNormalKeyboard() {
        val g = LetterKeyboardFrame(listOf(10, 9, 7)).geometry(1f, 1f)
        val gap = 3.2f
        fun centers(keys: List<String>, start: Float, split: Int) = keys.indices.map { i ->
            start + i + 0.5f + if (split in 0..i) gap else 0f
        }
        val rows = listOf(
            Triple(top, 0f, SplitKeyboardGeometry.leftCount(top, 0f, false)),
            Triple(middle, g.middleRowInsetDp, SplitKeyboardGeometry.leftCount(middle, g.middleRowInsetDp, false)),
            Triple(bottom, g.outerKeyWeight, SplitKeyboardGeometry.leftCount(bottom, g.outerKeyWeight, false)),
        )
        for ((keys, start, split) in rows) {
            val normal = centers(keys, start, -1)
            val shifted = centers(keys, start, split)
            keys.indices.forEach { i ->
                // 左半位置不变，右半整体右移同一条缝宽：行间错位与正常键盘完全相同。
                assertEquals(keys[i], if (i < split) 0f else gap, shifted[i] - normal[i], 0.0001f)
            }
        }
    }

    @Test fun bottomRowSplitsInsideTheSpaceKey() {
        val (left, right) = SplitKeyboardGeometry.spaceSplit(QwertyBottomRowWeights.Standard)
        assertEquals(1.6f, left, 0.0001f)
        assertEquals(2.3f, right, 0.0001f)
        val b = QwertyBottomRowWeights.Standard
        assertEquals(5f, b.mode * 2 + b.punctuation + left, 0.0001f)
    }

    @Test fun automaticGapRestoresTheCalibratedPitchWithinLimits() {
        // 平板横屏：两半各五个标定键距，其余留作中缝。
        val wide = SplitKeyboardGeometry.defaultGap(1493f, 8f, 98.4f)
        assertEquals((1493f - 16f - 984f) / 1493f, wide, 0.0001f)
        // 屏幕不够宽：只留最小的自动中缝。
        assertEquals(SplitKeyboardGeometry.AUTO_MIN_GAP_FRACTION, SplitKeyboardGeometry.defaultGap(934f, 8f, 98.4f), 0.0001f)
        assertEquals(SplitKeyboardGeometry.FALLBACK_GAP_FRACTION, SplitKeyboardGeometry.defaultGap(1493f, 8f, null), 0.0001f)
        // 保存值被夹到每半仍能放下最小键宽的范围内。
        val range = SplitKeyboardGeometry.gapRange(600f, 8f)
        assertTrue(range.endInclusive * 600f <= 600f - 16f - 10f * SplitKeyboardGeometry.MIN_UNIT_DP + 0.01f)
        assertEquals(range.endInclusive, SplitKeyboardGeometry.resolvedGap(0.9f, 600f, 8f, null), 0.0001f)
        assertEquals(wide, SplitKeyboardGeometry.resolvedGap(-1f, 1493f, 8f, 98.4f), 0.0001f)
    }

    @Test fun fourteenKeySplitBreaksOnlyThePairsAcrossTheMidline() {
        val phone = listOf(
            SplitKeyboardGeometry.splitStraddlingPairs(listOf("qw", "er", "ty", "ui", "op"), 0f),
            SplitKeyboardGeometry.splitStraddlingPairs(listOf("as", "df", "gh", "jk", "l"), 0.5f),
            SplitKeyboardGeometry.splitStraddlingPairs(listOf("zx", "cv", "bn", "m"), 1.5f))
        assertEquals(listOf("qw", "er", "t", "y", "ui", "op"), phone[0])
        assertEquals(listOf("as", "df", "g", "h", "jk", "l"), phone[1])
        assertEquals(listOf("zx", "c", "v", "bn", "m"), phone[2])
        // 拆开后左右手与 26 键一致：t、g、v 在左，y、h、b 在右。
        assertEquals(3, SplitKeyboardGeometry.leftCount(phone[0], 0f, true))
        assertEquals(3, SplitKeyboardGeometry.leftCount(phone[1], 0.5f, true))
        assertEquals(3, SplitKeyboardGeometry.leftCount(phone[2], 1.5f, true))
        // 实体键盘错位：bn 跨中线而 cv 不跨。
        assertEquals(listOf("zx", "cv", "b", "n", "m"),
            SplitKeyboardGeometry.splitStraddlingPairs(listOf("zx", "cv", "bn", "m"), 0.75f))
        // 正好落在中线两侧的键不拆。
        assertEquals(listOf("as", "df", "gh", "jk"),
            SplitKeyboardGeometry.splitStraddlingPairs(listOf("as", "df", "gh", "jk"), 1f))
    }

    @Test fun keyWidthFractionLeavesExactlyTheGap() {
        val width = 1000f
        val fraction = SplitKeyboardGeometry.keyWidthFraction(width, 0.3f, 8f)
        assertEquals(width - 16f - 300f, (width - 16f) * fraction, 0.01f)
    }

    /**
     * 按 [KeyboardRowWithConfig] 的排法算一行各键的左右边：首键带行首留白，末键带行尾留白，
     * 第 [split] 个键前插入缝。返回 (键, 左, 右) 与缝的左右边。
     */
    private fun layoutRow(keys: List<String>, start: Float, unit: Float, split: Int, gap: Float, end: Float):
        Pair<List<Triple<String, Float, Float>>, Pair<Float, Float>?> {
        var x = start
        var gapEdges: Pair<Float, Float>? = null
        val placed = keys.mapIndexed { i, key ->
            if (i == split) { gapEdges = x to x + gap; x += gap }
            Triple(key, x, (x + unit).also { x = it })
        }
        if (split == keys.size) gapEdges = x to x + gap
        return placed to gapEdges
    }

    /** 「右半边也放 G 键」：右半 g h j k l 与 y u i o p 上下对齐，左半和中缝不动，总宽不变。 */
    @Test fun rightGLinesUpUnderYuiopAndKeepsTheGap() {
        val width = 600f
        for (stagger in listOf(0f, 0.5f, 1f)) {
            val g = LetterKeyboardFrame(listOf(10, 9, 7)).geometry(1f, stagger)
            val gap = 120f
            val unit = (width - gap) / 10f
            val start = g.middleRowInsetDp * unit
            val end = g.middleRowEndInsetDp * unit
            val split = SplitKeyboardGeometry.leftCount(middle, g.middleRowInsetDp, false)
            val row = SplitKeyboardGeometry.homeRowWithRightG(middle, split, true, gap, end, unit)
            assertEquals("asdfgghjkl".map { it.toString() }, row.keys)
            assertEquals(5, row.mirrorIndex)
            assertEquals(0f, row.endInset, 0.0001f)
            val (topKeys, topGap) = layoutRow(top, 0f, unit, 5, gap, 0f)
            val (plainKeys, plainGap) = layoutRow(middle, start, unit, split, gap, end)
            val (keys, rowGap) = layoutRow(row.keys, start, unit, split, row.gap, row.endInset)
            // 左半 a s d f g 位置不变。
            (0 until 5).forEach { i -> assertEquals(plainKeys[i], keys[i]) }
            // 右半五键正好在 y u i o p 下面，l 的右边就是行尾。
            (5 until 10).forEach { i ->
                assertEquals("stagger=$stagger ${keys[i].first}", topKeys[i].second, keys[i].second, 0.001f)
                assertEquals(topKeys[i].third, keys[i].third, 0.001f)
            }
            assertEquals(width, keys.last().third + row.endInset, 0.001f)
            // 缝的左边不变，右边与上一排一致：中缝矩形（各行缝的公共部分）不变。
            assertEquals(plainGap!!.first, rowGap!!.first, 0.001f)
            assertEquals(topGap!!.second, rowGap.second, 0.001f)
            assertTrue(rowGap.second <= plainGap.second + 0.001f)
        }
    }

    /** 选项关着、不分体、14 键、十键中排、缝太窄：原样返回，键、缝宽、行尾留白一个都不变。 */
    @Test fun rightGLeavesEveryOtherRowUntouched() {
        fun plain(keys: List<String>, split: Int, enabled: Boolean, gap: Float = 120f, end: Float = 24f, unit: Float = 48f) {
            val row = SplitKeyboardGeometry.homeRowWithRightG(keys, split, enabled, gap, end, unit)
            assertEquals(SplitHomeRow(keys, -1, gap, end), row)
        }
        plain(middle, 5, enabled = false)
        // 不分体（splitAt = -1）：开关开着也不加。
        plain(middle, -1, enabled = true, gap = 0f)
        plain(listOf("as", "df", "g", "h", "jk", "l"), 3, enabled = true)
        plain("arstdhneio".map { it.toString() }, 5, enabled = true)
        plain("asdfghjkl;".map { it.toString() }, 5, enabled = true)
        plain("qwertyuiop".map { it.toString() }, 5, enabled = true)
        plain("zxcvbnm".map { it.toString() }, 4, enabled = true)
        // 缝加行尾留白放不下一个键：不挤。
        plain(middle, 5, enabled = true, gap = 20f, end = 24f, unit = 48f)
    }

    /** 「填满留空处」：两侧照常分开排布，但中缝不挖空、不让出触摸。 */
    @Test fun filledGapKeepsTheSplitLayoutButLeavesNoHole() {
        val gap = SplitKeyboardGap()
        gap.reportRow(0, androidx.compose.ui.geometry.Rect(400f, 100f, 600f, 160f))
        gap.reportRow(1, androidx.compose.ui.geometry.Rect(380f, 160f, 620f, 220f))
        assertEquals(androidx.compose.ui.geometry.Rect(400f, 100f, 600f, 300f), gap.hole(300f))
        gap.updateFilled(true)
        assertEquals(null, gap.hole(300f))
        assertEquals(400f to 600f, gap.gapX())
        gap.updateFilled(false)
        assertTrue(gap.hole(300f) != null)
    }

    /** 外层要不要离屏图跟着「这一刻挖不挖缝」走：填满、被面板盖住、没有分体时都不挖。 */
    @Test fun cutsKeyboardOnlyWhileAHoleIsCut() {
        val gap = SplitKeyboardGap()
        assertEquals(false, gap.cutsKeyboard)
        gap.reportRow(0, androidx.compose.ui.geometry.Rect(400f, 100f, 600f, 160f))
        assertEquals(true, gap.cutsKeyboard)
        assertEquals(gap.hole(300f) != null, gap.cutsKeyboard)
        gap.updateFilled(true)
        assertEquals(false, gap.cutsKeyboard)
        gap.updateFilled(false)
        gap.updateExposed(false)
        assertEquals(false, gap.cutsKeyboard)
        assertEquals(null, gap.hole(300f))
    }
}
