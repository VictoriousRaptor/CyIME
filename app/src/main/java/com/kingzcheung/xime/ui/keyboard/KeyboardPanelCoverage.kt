package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * Fullscreen handwriting reports its control footer here (window coordinates, null when hidden)
 * so the glass blur covers only the footer and leaves the writing area over the app clear.
 */
internal object HandwritingGlassRegion {
    /** Set by the input method service; null in previews and tests. Main thread only. */
    var reporter: ((android.graphics.Rect?) -> Unit)? = null
}

/** An explicit root modifier, not an inherited ambient: nested menus keep their own surface. */
private object InheritedKeyboardBackground : Modifier.Element

internal fun Modifier.inheritKeyboardBackground(inherit: Boolean = true): Modifier =
    if (inherit) then(InheritedKeyboardBackground) else this

internal fun Modifier.inheritsKeyboardBackground(): Boolean = any { it === InheritedKeyboardBackground }

/**
 * Keep hidden pages measured and composed, but never paint their keys through a replacement panel.
 *
 * [covering] is a panel laid over exactly this page (same slot, same size). It is cut out in this page's own
 * coordinates from its progress alone, and while it is fully drawn the page is not painted at all. The other
 * [panels] are cut out by window bounds and origin, which come from layout callbacks and can lag a frame:
 * the whole keyboard showed through the expanded candidate page while ▴ was held (his video 11-23,
 * image theme with 动态背景 on his phone).
 */
@Composable
internal fun Modifier.excludeRevealedKeyboardPanels(
    enabled: Boolean,
    vararg panels: KeyboardPanelRevealState,
    covering: KeyboardPanelRevealState? = null,
): Modifier {
    if (!enabled) return this
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInWindow() }.drawWithContent {
        val cover = covering?.progress?.value?.coerceIn(0f, 1f) ?: 0f
        if (cover >= 1f) return@drawWithContent
        fun drawOutside(index: Int) {
            if (index == panels.size) {
                drawContent()
                return
            }
            val panel = panels[index]
            val bounds = panel.boundsInWindow
            val progress = panel.progress.value.coerceIn(0f, 1f)
            if (panel === covering || progress <= 0f || bounds.width <= 0f || bounds.height <= 0f) {
                drawOutside(index + 1)
            } else {
                clipRect(bounds.left - origin.x, bounds.top - origin.y,
                    bounds.right - origin.x, bounds.top + bounds.height * progress - origin.y,
                    ClipOp.Difference) { drawOutside(index + 1) }
            }
        }
        if (cover > 0f) clipRect(0f, 0f, size.width, size.height * cover, ClipOp.Difference) { drawOutside(0) }
        else drawOutside(0)
    }
}

/**
 * A page kept composed under a panel must not take touches. Inside its bounds the panel's shield
 * wins, but Compose still hands a key shorter than the 48 dp minimum touch target a touch just
 * outside its bounds: with the number row on, the strip under the toolbar typed digits through
 * the emoji page (B站反馈 11-08). Clipping the covered page ends hit testing at its bounds; an
 * uncovered page keeps its touch targets unchanged.
 */
internal fun Modifier.blockCoveredKeyboardTouches(covered: Boolean): Modifier =
    if (covered) clipToBounds() else this
