package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 11-09：剪贴板图片太矮，可选按图片比例（有上限）或矮。11-17：默认改成完整，不裁剪。 */
class ClipboardImageHeightTest {
    private val width = 160f
    private val records = 500f

    @Test fun fullIsTheDefaultAndAChosenHeightStays() {
        assertEquals(ClipboardImageHeight.FULL, clipboardImageHeight(null))
        assertEquals(ClipboardImageHeight.FULL, clipboardImageHeight("whatever"))
        // Someone who picked a height before keeps it.
        assertEquals(ClipboardImageHeight.STANDARD, clipboardImageHeight("standard"))
        assertEquals(ClipboardImageHeight.RATIO, clipboardImageHeight("ratio"))
        assertEquals(ClipboardImageHeight.LOW, clipboardImageHeight("low"))
    }

    @Test fun standardKeepsTheOldShape() {
        assertEquals(200f, clipboardImageCardHeight(ClipboardImageHeight.STANDARD, width, records, aspect = 3f), .01f)
    }

    @Test fun fullShowsThePictureWholeUpToTheWholeBoard() {
        val full = ClipboardImageHeight.FULL
        assertEquals(120f, clipboardImageCardHeight(full, width, records, aspect = 4f / 3f), .01f)
        // A phone screenshot (9:20) is taller than the six-tenths cap of 「按图片比例」.
        assertEquals(355.56f, clipboardImageCardHeight(full, width, records, aspect = 9f / 20f), .01f)
        // Longer than the records area: as tall as the area (shown smaller, never cropped).
        assertEquals(500f, clipboardImageCardHeight(full, width, records, aspect = .1f), .01f)
        assertEquals(80f, clipboardImageCardHeight(full, width, records, aspect = 4f), .01f)
        assertEquals(200f, clipboardImageCardHeight(full, width, records, aspect = null), .01f)
    }

    @Test fun lowKeepsTheBoundedStrip() {
        assertEquals(clipboardPinnedPreviewHeight(records),
            clipboardImageCardHeight(ClipboardImageHeight.LOW, width, records, null), .01f)
    }

    @Test fun ratioFollowsThePictureWithinBounds() {
        val ratio = ClipboardImageHeight.RATIO
        // A wide picture is short, but never below the tappable minimum.
        assertEquals(80f, clipboardImageCardHeight(ratio, width, records, aspect = 4f), .01f)
        assertEquals(120f, clipboardImageCardHeight(ratio, width, records, aspect = 4f / 3f), .01f)
        // A long screenshot stops at six tenths of the records area.
        assertEquals(300f, clipboardImageCardHeight(ratio, width, records, aspect = .2f), .01f)
        // Size not read yet: the standard shape.
        assertEquals(200f, clipboardImageCardHeight(ratio, width, records, aspect = null), .01f)
    }

    @Test fun aShortBoardCapsTheStandardShapeToo() {
        assertEquals(120f, clipboardImageCardHeight(ClipboardImageHeight.STANDARD, width, 200f, null), .01f)
        assertEquals(80f, clipboardImageCardHeight(ClipboardImageHeight.STANDARD, width, 50f, null), .01f)
    }
}
