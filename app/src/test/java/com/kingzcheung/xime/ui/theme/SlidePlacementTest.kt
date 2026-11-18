package com.kingzcheung.xime.ui.theme

import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.BackgroundMotionPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 11-15：轮播图片也能像主图一样调大小和位置。 */
class SlidePlacementTest {
    private val main = BackgroundConfig(type = "image", src = "themes/custom-keyboard-bg-1.jpg", fit = "contain",
        zoom = 0.8f, alignX = 0f, alignY = 1f)

    @Test fun placementsSurviveStorage() {
        val map = mapOf("themes/custom-keyboard-bg-slide-1.jpg" to ThemeImagePlacement("contain", 0.6f, 0.2f, 1f),
            "themes/custom-keyboard-bg-slide-2.jpg" to ThemeImagePlacement("fit_height", 1f, 0f, 0.5f))
        assertEquals(map, BackgroundMotionPreferences.decodePlacements(BackgroundMotionPreferences.encodePlacements(map)))
        assertEquals("旧版本没有这一项", emptyMap<String, ThemeImagePlacement>(), BackgroundMotionPreferences.decodePlacements(null))
        assertEquals(emptyMap<String, ThemeImagePlacement>(), BackgroundMotionPreferences.decodePlacements("坏的一行"))
    }

    @Test fun aSlideIsDrawnWithItsOwnPlacementOrCoverWhenNeverAdjusted() {
        val slide = "themes/custom-keyboard-bg-slide-1.jpg"
        val plain = main.withSlide(slide)!!
        assertEquals(slide, plain.src)
        assertEquals("没调过：铺满居中，和以前一样", "cover", plain.fit)
        assertNull(plain.zoom)
        val adjusted = main.withSlide(slide, ThemeImagePlacement("contain", 0.6f, 0.2f, 1f))!!
        assertEquals("contain", adjusted.fit)
        assertEquals(0.6f, adjusted.zoom!!, 0f)
        assertEquals(0.2f, adjusted.alignX!!, 0f)
        assertEquals(1f, adjusted.alignY!!, 0f)
        assertEquals("轮到主图时照主图的摆放", main, main.withSlide(main.src))
    }
}
