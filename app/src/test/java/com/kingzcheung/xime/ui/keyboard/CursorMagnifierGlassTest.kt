package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.*
import com.kingzcheung.xime.service.HardwareCursorAnchor
import com.kingzcheung.xime.settings.FrostedGlassConfig
import org.junit.Assert.*
import org.junit.Test

class CursorMagnifierGlassTest {
    @Test fun disabledGlassAndUnavailableBlurKeepTheOriginalSolidThemeColor() {
        val surface = Color(0x80202124)
        val glass = FrostedGlassConfig(enabled = true, backgroundOpacity = .5f)
        assertEquals(surface.copy(alpha = 1f), cursorMagnifierBackground(surface, Color.White, glass, false))
        assertEquals(surface.copy(alpha = 1f), cursorMagnifierBackground(surface, Color.White, glass.copy(enabled = false), true))
    }

    @Test fun readableOpacityIsPreservedAndKeyOpacityDoesNotAffectTheLens() {
        val glass = FrostedGlassConfig(enabled = true, backgroundOpacity = .9f)
        for (keyOpacity in listOf(0f, .2f, 1f)) {
            assertEquals(Color.Black.copy(alpha = .9f),
                cursorMagnifierBackground(Color.Black, Color.White, glass.copy(keyOpacity = keyOpacity), true))
        }
    }

    @Test fun bothAppearancesRemainReadableOverWhiteAndBlackForEveryOpacity() {
        for ((surface, text) in listOf(Color.White to Color.Black, Color(0xFF202124) to Color.White)) {
            for (opacity in listOf(0f, .2f, .5f, .8f, 1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val glass = FrostedGlassConfig(enabled = true, backgroundOpacity = opacity)
                val shown = cursorMagnifierBackground(surface, text, glass, true)
                assertTrue(shown.alpha.isFinite())
                assertTrue(shown.alpha >= glass.normalized().backgroundOpacity - .0001f)
                for (host in listOf(Color.White, Color.Black)) {
                    val under = shown.compositeOver(host).luminance()
                    val label = text.luminance()
                    val contrast = (maxOf(label, under) + .05f) / (minOf(label, under) + .05f)
                    assertTrue("surface=$surface opacity=$opacity host=$host contrast=$contrast", contrast >= GLASS_LABEL_CONTRAST)
                }
            }
        }
    }

    @Test fun blurReceivesTheExactFinalPopupBoundsIncludingNegativeImeCoordinates() {
        var reported: IntRect? = null
        val position = CursorMagnifierPosition(HardwareCursorAnchor(390f, 300f, 390f, 324f),
            IntRect(0, 0, 400, 850), IntOffset(0, 520), 8) { reported = it }
        for (size in listOf(IntSize(280, 56), IntSize(10, 56), IntSize(384, 56))) {
            val offset = position.calculatePosition(IntRect.Zero, IntSize(400, 330), LayoutDirection.Ltr, size)
            assertEquals(IntRect(offset, size), reported)
            assertEquals(-284, reported!!.top)
            assertEquals(56, reported!!.height)
            assertTrue(reported!!.right <= 400)
        }
    }
}
