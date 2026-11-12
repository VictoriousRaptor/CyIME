package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard panel's heights on the screens it has to fit: phone and tablet, portrait and
 * landscape, at the default keyboard height and at the largest the height control allows. Pulling
 * the panel up and opening search must never ask for more than the IME may use, and search keeps
 * a usable results area wherever the screen has room for one.
 */
class ClipboardPanelMatrixTest {
    /** [screen] is the configuration height (dp) the service sizes against; [bottom] its reserved bottom. */
    private data class Screen(val name: String, val screen: Int, val portraitLongSide: Int, val landscape: Boolean, val bottom: Int)

    private val screens = listOf(
        Screen("手机竖屏", 800, 880, false, 48),
        Screen("手机横屏", 380, 880, true, 0),
        Screen("小手机竖屏", 640, 720, false, 48),
        Screen("小手机横屏", 340, 720, true, 0),
        Screen("平板竖屏", 1240, 1280, false, 48),
        Screen("平板横屏", 760, 1280, true, 48),
    )

    private fun keyboards(s: Screen): List<Int> {
        val bounds = keyboardHeightBounds(s.screen, s.landscape)
        val default = (s.portraitLongSide * (if (s.landscape) 25 else 35) / 100).coerceIn(bounds)
        return listOf(bounds.first, default, bounds.last).distinct()
    }

    @Test fun pullingThePanelUpStaysOnScreen() {
        for (s in screens) for (k in keyboards(s)) {
            val available = s.screen - s.bottom
            val bounds = clipboardPanelBounds(k, s.screen, s.bottom)
            assertTrue("${s.name} 键盘 $k：展开 ${bounds.expanded} > 可用 $available", bounds.expanded <= available)
            assertTrue("${s.name} 键盘 $k：展开比收起还矮", bounds.expanded >= bounds.collapsed)
        }
    }

    @Test fun searchNeverAsksForMoreThanTheScreenHas() {
        for (s in screens) for (k in keyboards(s)) {
            val available = s.screen - s.bottom
            val results = clipboardSearchResultsTarget(true, clipboardPanelBounds(k, s.screen, s.bottom), k, available)
            val inline = clipboardSearchInline(k, available)
            val window = k + clipboardSearchExtraHeight(true, results, inline)
            val room = available - k - clipboardSearchPanelHeight(inline)
            if (room >= 0) assertTrue("${s.name} 键盘 $k：搜索后 $window > 可用 $available", window <= available)
            // Wherever there is room, results get at least one row of cards (or all the room there is).
            assertTrue("${s.name} 键盘 $k：结果区 $results，空间 $room",
                results >= minOf(CLIPBOARD_SEARCH_MIN_RESULTS, room.coerceAtLeast(0)))
        }
    }

    @Test fun defaultKeyboardsLeaveRoomForSearchResultsEverywhere() {
        for (s in screens) {
            val bounds = keyboardHeightBounds(s.screen, s.landscape)
            val k = (s.portraitLongSide * (if (s.landscape) 25 else 35) / 100).coerceIn(bounds)
            val results = clipboardSearchResultsTarget(true, clipboardPanelBounds(k, s.screen, s.bottom), k, s.screen - s.bottom)
            assertTrue("${s.name} 默认键盘 $k：结果区只有 $results dp", results >= 48)
        }
    }
}
