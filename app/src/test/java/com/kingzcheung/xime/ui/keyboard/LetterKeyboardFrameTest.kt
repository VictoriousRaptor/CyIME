package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.*
import org.junit.Test

class LetterKeyboardFrameTest {
    private val standard = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { it.map(Char::toString) }
    private val fourteen = listOf(listOf("qw", "er", "ty", "ui", "op"),
        listOf("as", "df", "gh", "jk", "l"), listOf("zx", "cv", "bn", "m"))
    private val seventeen = listOf(listOf("q", "we", "rt", "yu", "i", "op"),
        listOf("a", "sd", "fg", "h", "jk", "l"), listOf("z", "xc", "v", "bn", "m"))
    private val eighteen = listOf(listOf("q", "we", "rt", "y", "u", "io", "p"), seventeen[1], seventeen[2])
    private val custom = listOf(listOf("qw", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "df", "g", "h", "j", "k", "l"), listOf("z", "x", "cv", "b", "n", "m"))
    private val cyletix = listOf("qwdrf;jkyp", "asetghliou", "zxcvbnm").map { it.map(Char::toString) }
    private val spareAtTop = listOf(standard[0].filterNot { it == "e" }, standard[1] + "e", standard[2])
    private val spareAtBottom = listOf(standard[0], standard[1] + "z", standard[2].filterNot { it == "z" })
    private val families = listOf(standard, fourteen, seventeen, eighteen, custom, standard.map { it.reversed() },
        cyletix, spareAtTop, spareAtBottom)

    @Test fun familyComesFromCompleteLettersAndRowSpansNotNumberOfButtonsOrSchemaName() {
        families.forEach { rows ->
            val before = rows.map { it.toList() }
            val frame = requireNotNull(letterKeyboardFrame(rows))
            assertEquals(10f, frame.columns, 0f)
            assertEquals(rows.map { row -> row.sumOf(String::length) }, frame.rowUnits)
            assertEquals(before, rows) // inspection cannot reorder user letters or rewrite groups
            assertEquals(10f, letterKeyboardColumns(rows, false), 0f)
            assertEquals(QwertyBottomRowWeights.Standard, letterKeyboardBottomRow(rows, false))
        }
        for (other in listOf(List(3) { List(3) { "abc" } }, listOf(listOf("あ", "か", "さ")),
            listOf(listOf("1", "2", "3")), standard.dropLast(1), standard.map { row -> row.map { "a" } },
            listOf(standard[0].drop(2), standard[1] + standard[0].take(2), standard[2]),
            listOf(standard[0] + "a", standard[1].drop(1), standard[2]),
            listOf(standard[0], standard[1] + "_", standard[2]))) {
            assertNull(letterKeyboardFrame(other))
            assertNull(standardLetterRowGeometry(other, 98.4f, true, false, 98.4f))
        }
    }

    @Test fun narrowTransitionAndWideFramesHaveContinuousOffsetsAndBoundedRows() {
        for (rows in families) {
            var previousStart = Float.POSITIVE_INFINITY
            var previousShift = Float.POSITIVE_INFINITY
            for (step in 200..1600) {
                val cell = step / 10f
                val frame = letterKeyboardFrame(rows)!!
                val g = standardLetterRowGeometry(rows, cell, true, false, 98.4f)!!
                val start = g.middleRowInsetDp / cell
                assertTrue(start <= previousStart + .00001f)
                assertTrue(g.outerKeyWeight <= previousShift + .00001f)
                if (previousShift.isFinite()) assertTrue(previousShift - g.outerKeyWeight < .01f)
                assertEquals((10 - frame.rowUnits[1]).toFloat(),
                    (g.middleRowInsetDp + g.middleRowEndInsetDp) / cell, .0001f)
                assertEquals(frame.rowUnits[2].toFloat(), g.bottomLetterWeight, 0f)
                assertEquals(10f, g.outerKeyWeight + g.bottomLetterWeight + g.deleteKeyWeight, .0001f)
                assertTrue(g.outerKeyWeight > 0f && g.deleteKeyWeight > 0f && g.bottomLetterWeight > 0f)
                assertTrue(g.middleRowInsetDp >= 0f && g.middleRowEndInsetDp >= 0f)
                if (cell <= 68.8f) {
                    assertEquals((10 - frame.rowUnits[2]) * .5f, g.outerKeyWeight, 0f)
                    assertEquals((10 - frame.rowUnits[1]) * .5f, start, .0001f)
                } else if (cell >= 98.4f) {
                    assertEquals((10 - frame.rowUnits[2]) * .25f, g.outerKeyWeight, 0f)
                    assertEquals((10 - frame.rowUnits[2]) * .75f, g.deleteKeyWeight, 0f)
                    assertEquals((10 - frame.rowUnits[1]) * .25f, start, .0001f)
                }
                previousStart = start; previousShift = g.outerKeyWeight
            }
            assertNull(standardLetterRowGeometry(rows, 100f, true, true, 98.4f))
        }
    }

    @Test fun fourteenMMatchesQwertyOnPhonesAndWidensOnlyFromStaggerSurplus() {
        val phone = standardLetterRowGeometry(fourteen, 40f, false, false, 98.4f)!!
        assertEquals(0f, fourteenMBorrowedWeight(phone.outerKeyWeight, phone.deleteKeyWeight), 0f)
        val tablet = standardLetterRowGeometry(fourteen, 98.4f, false, false, 98.4f)!!
        val borrowed = fourteenMBorrowedWeight(tablet.outerKeyWeight, tablet.deleteKeyWeight)
        assertEquals(.75f, borrowed, .0001f)
        // 删除键不窄于手机上的宽度。
        assertEquals(phone.deleteKeyWeight, tablet.deleteKeyWeight - borrowed, .0001f)
    }

    @Test fun defaultAndTallResizedFramesShareAllGapsRowsAndBottomBoundaries() {
        for (width in listOf(220f, 360f, 800f, 1000f, 1600f))
            for (height in listOf(180f, 260f, 500f, 900f)) for (floating in listOf(false, true)) {
                val policy = KeyVisualPolicy.Qwerty.copy(maxKeyWidth = Float.MAX_VALUE)
                val expected = keyboardGridMetrics(policy, width, height, 10f,
                    allowShrink = floating, growthSpacing = null to null)
                val referenceBottom = candidateQwertyGeometry(standard, false, false, false)
                for (rows in families) {
                    val actual = keyboardGridMetrics(policy, width, height, letterKeyboardColumns(rows, false),
                        allowShrink = floating, growthSpacing = null to null)
                    assertEquals(expected, actual)
                    val bottom = candidateQwertyGeometry(rows, false, true, false)
                    assertEquals(referenceBottom, bottom)
                    assertEquals(.17f * (width - actual.gutterX * 2),
                        bottom.deleteWidth(width - actual.gutterX * 2), .001f)
                }
            }
    }

    /** 「合并键均匀排布」关：与 26 键键缝严格对齐，合并键占字母数个格；26 键、非加权行每键一份。 */
    @Test fun strictSpanIsLetterCount() {
        (families.flatten().flatten()).forEach { key ->
            assertEquals(key.length.toFloat(), letterKeySpan(key, weighted = true), 0f)
            assertEquals(1f, letterKeySpan(key, weighted = false, evenMerged = true), 0f)
        }
    }

    /**
     * 打开后合并键只比单字母键宽三分之一（参考图里 WE 键帽约是 Q 的 1.32 倍）。
     * 整行宽度仍是 26 键网格给这一行的那份，单字母键因此变宽：18 键第一行 Q 从 1 格到 1.25 格，WE 从 2 格到 1.67 格。
     */
    @Test fun evenSpanNarrowsMergedKeysAndWidensSingles() {
        assertEquals(1f, letterKeySpan("q", true, evenMerged = true), 0f)
        assertEquals(4f / 3f, letterKeySpan("we", true, evenMerged = true), 1e-6f)
        val row = eighteen[0]
        val units = row.sumOf { it.length }.toFloat()
        val spans = row.map { letterKeySpan(it, true, evenMerged = true) }
        val widths = spans.map { it / spans.sum() * units }
        assertEquals(10f, widths.sum(), 1e-4f)
        assertEquals(1.25f, widths[row.indexOf("q")], 1e-4f)
        assertEquals(1.25f * 4f / 3f, widths[row.indexOf("we")], 1e-4f)
        // 每排单字母键宽度接近：第一排 1.25 格，第二排 9/7 格，第三排 7/(17/3) 格，差不到 5%。
        val singles = eighteen.map { r ->
            val s = r.map { letterKeySpan(it, true, evenMerged = true) }
            r.sumOf { it.length } / s.sum()
        }
        assertTrue(singles.max() / singles.min() < 1.05f)
    }

    /** 14 键大写时拆键：均匀排布下拆开的两个字母各占合并键的一半，每个字母的左边缘与拆开前同位；严格对齐时各 1 份。 */
    @Test fun fourteenSplitKeepsLetterPositions() {
        fourteen.forEach { row ->
            for (even in listOf(false, true)) {
                val whole = row.map { letterKeySpan(it, true, even) }
                val split = splitPairSpans(row, even)
                assertEquals(whole.sum(), split.sum(), 1e-5f)
                val letterStarts = mutableListOf<Float>()
                var x = 0f
                row.forEachIndexed { i, key -> key.forEachIndexed { j, _ -> letterStarts += x + whole[i] / key.length * j }; x += whole[i] }
                val splitStarts = split.runningFold(0f) { acc, w -> acc + w }.dropLast(1)
                letterStarts.zip(splitStarts).forEach { (a, b) -> assertEquals(a, b, 1e-5f) }
                if (!even) split.forEach { assertEquals(1f, it, 0f) }
            }
        }
        // 第二排 as df gh jk l：l 从 1/9 行宽变成 1/(4×4/3+1)。
        val spans = fourteen[1].map { letterKeySpan(it, true, true) }
        assertEquals(1f / (16f / 3f + 1f), spans.last() / spans.sum(), 1e-5f)
    }

    @Test fun fourteenHomeRowLastKeyReachesEdgeOnlyWhenStrict() {
        // 严格对齐：l 画到行尾，与 p 右边对齐。
        assertTrue(fourteenHomeRowLastKeyToEdge(true, fourteen[1], evenMerged = false))
        // 均匀排布：l 右边留空和 a 左边一样宽。
        assertFalse(fourteenHomeRowLastKeyToEdge(true, fourteen[1], evenMerged = true))
        // Shift 拆开后最后一个仍是 l，规则相同。
        val split = fourteen[1].flatMap { k -> if (k.length == 2) listOf(k.take(1), k.drop(1)) else listOf(k) }
        assertTrue(fourteenHomeRowLastKeyToEdge(true, split, evenMerged = false))
        assertFalse(fourteenHomeRowLastKeyToEdge(true, split, evenMerged = true))
        // 末尾是合并键、或不是 14 键，都不画到行尾。
        assertFalse(fourteenHomeRowLastKeyToEdge(true, listOf("as", "df", "gh", "jkl"), evenMerged = false))
        assertFalse(fourteenHomeRowLastKeyToEdge(false, standard[1], evenMerged = false))
    }

    @Test
    fun evenMergedFourteenKeyHomeRowGapsAreEqualOnStaggeredTablets() {
        // 平板 26 键第二排错位：左 16、右 48。均匀排布的 14 键两边各 32，总留空不变。
        assertEquals(32f to 32f, fourteenHomeRowInsets(true, evenMerged = true, start = 16f, end = 48f))
        // 严格对齐、或不是 14 键，照旧。
        assertEquals(16f to 48f, fourteenHomeRowInsets(true, evenMerged = false, start = 16f, end = 48f))
        assertEquals(16f to 48f, fourteenHomeRowInsets(false, evenMerged = true, start = 16f, end = 48f))
    }
}
