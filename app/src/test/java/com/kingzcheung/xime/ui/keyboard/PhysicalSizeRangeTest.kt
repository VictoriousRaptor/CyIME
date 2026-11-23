package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-22「按物理尺寸放宽键盘和候选大小范围」：只放宽可调范围，默认值和老用户存着的值一个都不动。
 */
class PhysicalSizeRangeTest {
    /** 手机竖横屏、细长屏、小屏、平板竖横屏（screenHeightDp）。 */
    private val screens = listOf(560, 640, 700, 731, 780, 823, 853, 915, 960, 1001,
        320, 360, 384, 411, 412, 552, 600, 800, 934, 1280, 1493)

    private val tabletMmPerDp = KeyboardPhysicalMetrics.from(1.875f, 266.471f)!!.millimetersFromDp(1f)

    @Test fun defaultBoundsAreTheSameAs104() {
        // 算默认高度时不传下限：下限仍是 244dp（可用高度不够时取上限），默认高度因此和 1.15.104 一模一样。
        for (h in screens) for (landscape in listOf(false, true)) {
            val bounds = keyboardHeightBounds(h, landscape)
            assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP.coerceAtMost(bounds.last), bounds.first)
            assertEquals(244, FLOATING_RESIZE_MIN_HEIGHT_DP)
            val max = if (landscape) (h - 24 - FLOATING_DRAG_BAR_HEIGHT_DP - 24).coerceAtLeast(160)
                else (h * 50 / 100).coerceAtLeast(244)
            assertEquals(max, bounds.last)
        }
    }

    @Test fun defaultPortraitAndLandscapeHeightsDoNotMove() {
        // 竖屏默认 = 长边 35%，横屏默认 = 长边 25%，都按 244dp 下限的范围夹（同 SettingsPreferences/KeyboardHeightProfiles.defaultFixed）。
        val cases = mapOf(
            // 长边 to (竖屏默认, 横屏默认)；横屏 screenHeightDp 用短边减状态栏约 24dp。
            823 to (288 to 244), 915 to (320 to 244), 960 to (336 to 244), 640 to (244 to 244), 1493 to (522 to 373),
        )
        for ((long, expected) in cases) {
            val portrait = (long * SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_PERCENT / 100)
                .coerceIn(keyboardHeightBounds(long, false))
            val landscapeH = if (long == 1493) 934 - 24 else 384 - 24
            val landscape = (long * SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE / 100)
                .coerceIn(keyboardHeightBounds(landscapeH, true))
            assertEquals("portrait $long", expected.first, portrait)
            assertEquals("landscape $long", expected.second, landscape)
        }
    }

    @Test fun physicalFloorUsesMillimetres() {
        assertEquals(186, physicalKeyboardFloorDp(tabletMmPerDp))        // SM-X800（他的平板）
        assertEquals(182, physicalKeyboardFloorDp(70.9f / 384f))          // S23 Ultra（他的手机）
        assertEquals(203, physicalKeyboardFloorDp(NOMINAL_MILLIMETERS_PER_DP))
        // 同样 4 排 × 6mm：平板上的按键排高换成毫米和手机一样。
        val tabletRows = (physicalKeyboardFloorDp(tabletMmPerDp) - 52) * tabletMmPerDp
        val phoneRows = (physicalKeyboardFloorDp(70.9f / 384f) - 52) * (70.9f / 384f)
        assertEquals(24f, tabletRows, 0.2f)
        assertEquals(24f, phoneRows, 0.2f)
    }

    @Test fun absurdDisplayReadingsFallBackToNominal() {
        for (bad in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 0.05f, 0.5f, 2f)) {
            assertEquals(NOMINAL_MILLIMETERS_PER_DP, plausibleMillimetersPerDp(bad), 0f)
        }
        assertEquals(203, physicalKeyboardFloorDp(Float.NaN))
    }

    @Test fun floorOnlyEverWidensTheRange() {
        // 下限只会比 244dp 低（老用户存的值都 ≥244，一个都不会被往上推），也不低于读旧悬浮高度的 160dp。
        var mm = 0.11f
        while (mm <= 0.24f) {
            val floor = physicalKeyboardFloorDp(mm)
            assertTrue("$mm → $floor", floor in FLOATING_RESIZE_ABSOLUTE_MIN_HEIGHT_DP..FLOATING_RESIZE_MIN_HEIGHT_DP)
            for (h in screens) for (landscape in listOf(false, true)) {
                val legacy = keyboardHeightBounds(h, landscape)
                val widened = keyboardHeightBounds(h, landscape, minHeightDp = floor)
                assertTrue(widened.first <= legacy.first)
                assertEquals(legacy.last, widened.last)
            }
            mm += 0.005f
        }
        // 传进来再大也不收紧。
        assertEquals(keyboardHeightBounds(823, false), keyboardHeightBounds(823, false, minHeightDp = 400))
    }

    @Test fun tabletMinimumIsLowerAndPhoneMinimumMatchesMillimetres() {
        val tabletFloor = physicalKeyboardFloorDp(tabletMmPerDp)
        // 平板横屏固定键盘：244 → 186dp（约 33mm）。
        assertEquals(186, keyboardHeightBounds(934 - 24, true, minHeightDp = tabletFloor).first)
        // 平板悬浮拖小：原来按短边 300dp，现在和 186dp 取小。
        assertEquals(300, floatingResizeFloorScreenContentDp(934))
        assertEquals(186, floatingResizeFloorScreenContentDp(934, tabletFloor))
        assertEquals(300, floatingResizeFloorWidthDp(934, 2000, tabletFloor))
        // 手机悬浮拖小本来就是 170dp，比毫米下限还低，不变。
        assertEquals(170, floatingResizeFloorScreenContentDp(384, physicalKeyboardFloorDp(70.9f / 384f)))
        assertEquals(270, floatingResizeFloorWidthDp(384, 2000, physicalKeyboardFloorDp(70.9f / 384f)))
        // 细长屏手机（标称密度，长边 960dp）固定键盘最低：244 → 203dp。
        assertEquals(203, keyboardHeightBounds(960, false, minHeightDp = physicalKeyboardFloorDp(NOMINAL_MILLIMETERS_PER_DP)).first)
    }

    @Test fun candidateSizesUpTo22AreExactlyAsBefore() {
        for (sp in CANDIDATE_TEXT_SIZE_MIN_SP..CANDIDATE_TEXT_SIZE_FIT_SP) {
            assertEquals(1f, candidateTextZoom(sp), 0f)
            assertEquals(sp, candidateBarTextSp(sp))
            assertEquals(candidateSizeFactor(sp), candidateSizeFactor(candidateBarTextSp(sp)), 0f)
        }
        assertEquals(19, SettingsPreferences.DEFAULT_CANDIDATE_TEXT_SIZE)
        assertEquals(12, CANDIDATE_TEXT_SIZE_MIN_SP)
    }

    @Test fun largerCandidateSizesGrowTheWholeBar() {
        assertEquals(44, CANDIDATE_TEXT_SIZE_MAX_SP)
        for (sp in CANDIDATE_TEXT_SIZE_FIT_SP..CANDIDATE_TEXT_SIZE_MAX_SP) {
            val zoom = candidateTextZoom(sp)
            // 栏里写的字号 × 整条放大倍数 = 设置的字号。
            assertEquals(sp.toFloat(), candidateBarTextSp(sp) * zoom, 0.001f)
        }
        assertEquals(2f, candidateTextZoom(44), 0f)
        assertEquals(88f, candidateBarHeightDp(1f * candidateTextZoom(44)), 0.001f)
    }

    @Test fun squareSnapIgnoresZoomAtDefaultSizes() {
        val snap = KeyboardSquareSnap.forLayout(com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC, false)!!
        val rect = ResizeRect(0f, 0f, 411f, 288f)
        val spacing = null to null
        assertEquals(snap.capDifference(rect, 1f, 0f, false, spacing),
            snap.copy(candidateZoom = candidateTextZoom(19)).capDifference(rect, 1f, 0f, false, spacing), 0f)
        // 候选栏变高，按键区变矮，方形点跟着变。
        assertTrue(snap.copy(candidateZoom = 2f).capDifference(rect, 1f, 0f, false, spacing) >
            snap.capDifference(rect, 1f, 0f, false, spacing))
    }
}
