package com.kingzcheung.xime.service

import android.graphics.Matrix
import android.graphics.RectF
import android.view.inputmethod.CursorAnchorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HardwareCursorAnchorTest {
    private fun editor(
        end: Int = -1,
        start: Int = end,
        transform: (FloatArray) -> Unit = {},
    ): CursorAnchorInfo {
        val matrix = mock<Matrix>()
        doAnswer { invocation ->
            transform(invocation.getArgument(0))
            null
        }.whenever(matrix).mapPoints(any<FloatArray>())
        return mock<CursorAnchorInfo>().also {
            whenever(it.matrix).thenReturn(matrix)
            whenever(it.selectionStart).thenReturn(start)
            whenever(it.selectionEnd).thenReturn(end)
            whenever(it.insertionMarkerHorizontal).thenReturn(Float.NaN)
            whenever(it.insertionMarkerTop).thenReturn(Float.NaN)
            whenever(it.insertionMarkerBottom).thenReturn(Float.NaN)
        }
    }

    private fun marker(info: CursorAnchorInfo, x: Float, top: Float, bottom: Float,
        flags: Int = CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION) {
        whenever(info.insertionMarkerHorizontal).thenReturn(x)
        whenever(info.insertionMarkerTop).thenReturn(top)
        whenever(info.insertionMarkerBottom).thenReturn(bottom)
        whenever(info.insertionMarkerFlags).thenReturn(flags)
    }

    private fun character(info: CursorAnchorInfo, index: Int, left: Float, top: Float, right: Float, bottom: Float,
        flags: Int = CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION) {
        // Local Android stubs do not retain RectF constructor parameters.
        val bounds = RectF().apply { this.left = left; this.top = top; this.right = right; this.bottom = bottom }
        whenever(info.getCharacterBounds(index)).thenReturn(bounds)
        whenever(info.getCharacterBoundsFlags(index)).thenReturn(flags)
    }

    @Test fun `insertion marker takes precedence over first character`() {
        val info = editor(end = 80)
        marker(info, 420f, 300f, 324f)
        character(info, 0, 12f, 20f, 26f, 44f)
        assertEquals(HardwareCursorAnchor(420f, 300f, 420f, 324f), resolveHardwareCursorAnchor(info))
        verify(info, never()).getCharacterBounds(0)
    }

    @Test fun `magnifier follows active start of a reverse selection instead of the sorted end marker`() {
        val info = editor(end = 105, start = 100)
        marker(info, 500f, 300f, 324f)
        character(info, 100, 400f, 200f, 420f, 224f)
        assertEquals(HardwareCursorAnchor(400f, 200f, 400f, 224f), resolveHardwareCursorAnchor(info, 100))
        assertEquals(HardwareCursorAnchor(500f, 300f, 500f, 324f), resolveHardwareCursorAnchor(info))
    }

    @Test fun `character fallback uses absolute selection offset and leading edge`() {
        val info = editor(end = 105)
        character(info, 0, 1f, 2f, 3f, 4f)
        character(info, 105, 500f, 200f, 520f, 224f)
        assertEquals(HardwareCursorAnchor(500f, 200f, 500f, 224f), resolveHardwareCursorAnchor(info))
        verify(info, never()).getCharacterBounds(0)
    }

    @Test fun `end of text falls back to preceding character trailing edge`() {
        val info = editor(end = 106)
        character(info, 105, 500f, 200f, 520f, 224f)
        assertEquals(HardwareCursorAnchor(520f, 200f, 520f, 224f), resolveHardwareCursorAnchor(info))
    }

    @Test fun `rtl character fallback chooses opposite leading and trailing edges`() {
        val flags = CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION or CursorAnchorInfo.FLAG_IS_RTL
        val leading = editor(end = 105)
        character(leading, 105, 500f, 200f, 520f, 224f, flags)
        assertEquals(HardwareCursorAnchor(520f, 200f, 520f, 224f), resolveHardwareCursorAnchor(leading))
        val trailing = editor(end = 106)
        character(trailing, 105, 500f, 200f, 520f, 224f, flags)
        assertEquals(HardwareCursorAnchor(500f, 200f, 500f, 224f), resolveHardwareCursorAnchor(trailing))
    }

    @Test fun `selection start is usable when active end is unavailable`() {
        val info = editor(start = 35)
        character(info, 35, 210f, 220f, 230f, 244f)
        assertEquals(HardwareCursorAnchor(210f, 220f, 210f, 244f), resolveHardwareCursorAnchor(info))
    }

    @Test fun `noncollapsed selection uses active end instead of start`() {
        val info = editor(end = 42, start = 35)
        character(info, 35, 110f, 120f, 130f, 144f)
        character(info, 42, 210f, 220f, 230f, 244f)
        assertEquals(HardwareCursorAnchor(210f, 220f, 210f, 244f), resolveHardwareCursorAnchor(info))
    }

    @Test fun `explicitly hidden marker cannot borrow visible character`() {
        val info = editor(end = 42)
        marker(info, 210f, 220f, 244f, CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION)
        character(info, 42, 110f, 120f, 130f, 144f)
        assertNull(resolveHardwareCursorAnchor(info))
    }

    @Test fun `partly visible marker and unspecified flags retain usable coordinates`() {
        val info = editor()
        for (flags in listOf(0, CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION or CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)) {
            marker(info, 10f, 20f, 44f, flags)
            assertEquals(HardwareCursorAnchor(10f, 20f, 10f, 44f), resolveHardwareCursorAnchor(info))
        }
    }

    @Test fun `hidden character fallback is rejected`() {
        val info = editor(end = 42)
        character(info, 42, 110f, 120f, 130f, 144f, CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION)
        assertNull(resolveHardwareCursorAnchor(info))
    }

    @Test fun `matrix transforms caret to screen without display clipping`() {
        val info = editor(transform = { points ->
            for (index in points.indices step 2) {
                points[index] = points[index] * 2f - 100f
                points[index + 1] = points[index + 1] * 3f + 20f
            }
        })
        marker(info, 10f, 20f, 44f)
        assertEquals(HardwareCursorAnchor(-80f, 80f, -80f, 152f), resolveHardwareCursorAnchor(info))
    }

    @Test fun `rotated transformed caret has normalized screen bounds`() {
        val info = editor(transform = { points ->
            for (index in points.indices step 2) {
                val x = points[index]
                points[index] = -points[index + 1]
                points[index + 1] = x
            }
        })
        marker(info, 10f, 20f, 44f)
        assertEquals(HardwareCursorAnchor(-44f, 10f, -20f, 10f), resolveHardwareCursorAnchor(info))
    }

    @Test fun `nonfinite and inverted marker coordinates are rejected`() {
        val info = editor()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            marker(info, invalid, 20f, 44f)
            assertNull(resolveHardwareCursorAnchor(info))
        }
        marker(info, 10f, 44f, 20f)
        assertNull(resolveHardwareCursorAnchor(info))
    }

    @Test fun `invalid matrix output is rejected`() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val info = editor(transform = { it[1] = invalid })
            marker(info, 10f, 20f, 44f)
            assertNull(resolveHardwareCursorAnchor(info))
        }
    }

    @Test fun `infinite marker cannot borrow valid character geometry`() {
        val info = editor(end = 42)
        marker(info, Float.POSITIVE_INFINITY, 20f, 44f)
        character(info, 42, 110f, 120f, 130f, 144f)
        assertNull(resolveHardwareCursorAnchor(info))
    }

    @Test fun `missing marker and selection never attach to arbitrary first character`() {
        val info = editor()
        character(info, 0, 12f, 20f, 26f, 44f)
        assertNull(resolveHardwareCursorAnchor(info))
        verify(info, never()).getCharacterBounds(0)
    }
    @Test fun `zero marker is not a caret and can use valid character geometry`() {
        val info = editor(end = 42)
        marker(info, 0f, 0f, 0f, 0)
        assertNull(resolveHardwareCursorAnchor(info))
        character(info, 42, 110f, 120f, 130f, 144f)
        assertEquals(HardwareCursorAnchor(110f, 120f, 110f, 144f), resolveHardwareCursorAnchor(info))
    }
}
