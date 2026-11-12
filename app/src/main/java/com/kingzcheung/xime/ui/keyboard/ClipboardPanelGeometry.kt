package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.OverlayRoute

/** 剪贴板及其拆词页、工具栏排列页共用同一套面板高度。 */
internal fun clipboardPanelCanExpand(page: KeyboardPage, resizing: Boolean, compact: Boolean): Boolean =
    !resizing && !compact && (page as? KeyboardPage.Overlay)?.route.let {
        it is OverlayRoute.Clipboard || it is OverlayRoute.SplitWords || it is OverlayRoute.ToolbarCustomize
    }

internal data class ClipboardPanelBounds(val collapsed: Int, val expanded: Int) {
    fun clamp(height: Float): Float = height.coerceIn(collapsed.toFloat(), expanded.toFloat())

    fun shouldExpand(height: Float, startHeight: Float = collapsed.toFloat(),
        wasExpanded: Boolean = false, velocityY: Float = 0f): Boolean {
        if (expanded <= collapsed) return false
        val distance = (expanded - collapsed) * .2f
        // Project through half of the shared 240ms opening transition. A quick flick
        // can settle without a long drag; slow sub-threshold movement returns home.
        val projectedTravel = height - startHeight - (velocityY.takeIf { it.isFinite() } ?: 0f) * .12f
        return when {
            projectedTravel >= distance -> true
            projectedTravel <= -distance -> false
            else -> wasExpanded
        }
    }
}

/** Reserved space includes system bars, bottom padding and (when floating) the card's bottom offset. */
internal fun clipboardPanelBounds(normal: Int, screen: Int, reserved: Int): ClipboardPanelBounds {
    val available = (screen - reserved.coerceAtLeast(0)).coerceAtLeast(1)
    val collapsed = normal.coerceIn(1, available)
    // Target 80% of the screen, then clamp once for bars and the floating bottom offset.
    val target = minOf(available, screen * 80 / 100)
    return ClipboardPanelBounds(collapsed, maxOf(collapsed, target))
}

/**
 * Clipboard search results take the space the clipboard has when pulled up, above the unchanged
 * keyboard and the search panel; 0 while search is closed. At least [CLIPBOARD_SEARCH_MIN_RESULTS]
 * when the screen has it, and never more than [available] (the height the IME may use: screen less
 * bars and bottom space), so in landscape the window never grows past the screen.
 */
internal fun clipboardSearchResultsTarget(visible: Boolean, bounds: ClipboardPanelBounds, keyboardHeight: Int,
    available: Int = bounds.expanded): Int {
    if (!visible) return 0
    val panel = clipboardSearchPanelHeight(clipboardSearchInline(keyboardHeight, available))
    val room = (available - keyboardHeight - panel).coerceAtLeast(0)
    return (bounds.expanded - keyboardHeight - panel)
        .coerceAtLeast(minOf(CLIPBOARD_SEARCH_MIN_RESULTS, room)).coerceAtMost(room)
}

/**
 * True when the two-row search bar would leave less than [CLIPBOARD_SEARCH_MIN_RESULTS] for results
 * (phone landscape): the bar becomes one row and the results drop their category row.
 */
internal fun clipboardSearchInline(keyboardHeight: Int, available: Int): Boolean =
    available - keyboardHeight - CLIPBOARD_SEARCH_PANEL_HEIGHT < CLIPBOARD_SEARCH_MIN_RESULTS

/**
 * True when even the one-row bar leaves less than one row of results ([CLIPBOARD_SEARCH_MIN_ROW]):
 * search then opens in the minimized floating keyboard instead (user, 2026-10-22).
 */
internal fun clipboardSearchNeedsFloating(keyboardHeight: Int, available: Int): Boolean =
    available - keyboardHeight - CLIPBOARD_SEARCH_INLINE_PANEL_HEIGHT < CLIPBOARD_SEARCH_MIN_ROW

/** One row of result cards. */
internal const val CLIPBOARD_SEARCH_MIN_ROW = 48

/** A minimized floating keyboard for search on a short screen is a little see-through. */
internal const val CLIPBOARD_SEARCH_FLOATING_OPACITY = 0.85f

internal fun clipboardSearchPanelHeight(inline: Boolean): Int =
    if (inline) CLIPBOARD_SEARCH_INLINE_PANEL_HEIGHT else CLIPBOARD_SEARCH_PANEL_HEIGHT

internal const val CLIPBOARD_SEARCH_MIN_RESULTS = 120

/**
 * Height the IME container must add above the keyboard while clipboard search is open: the
 * (animated) results plus the search panel. Without it the keyboard is pushed out of the window.
 * A panel opened meanwhile (symbols, emoji, menu) keeps it: the search bar stays above the bar
 * and the panel only covers the keys (11-10).
 */
internal fun clipboardSearchExtraHeight(visible: Boolean, resultsHeight: Int, inline: Boolean = false): Int =
    if (!visible) 0 else resultsHeight + clipboardSearchPanelHeight(inline)

/** Use screen coordinates: resizing the IME moves the toolbar's local coordinate origin. */
internal class ClipboardPanelDrag(
    private val bounds: ClipboardPanelBounds,
    val startHeight: Float,
    private val startScreenY: Float,
) {
    fun heightAt(screenY: Float): Float = bounds.clamp(startHeight + startScreenY - screenY)
}
