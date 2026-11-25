package com.kingzcheung.xime.ui.menubar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #536：剪贴板卡片只把开头一段交给排版，长文不再在主线程上整段测量。 */
class ClipboardPreviewTextTest {
    @Test fun shortTextIsShownAsItIs() {
        assertEquals("你好", clipboardPreviewText("你好"))
        val exact = "字".repeat(CLIPBOARD_PREVIEW_CHARS)
        assertEquals(exact, clipboardPreviewText(exact))
    }

    @Test fun longTextIsCutToTheFirstPart() {
        val preview = clipboardPreviewText("长".repeat(200_000))
        assertEquals(CLIPBOARD_PREVIEW_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test fun anEmojiIsNotCutInHalf() {
        val text = "a".repeat(CLIPBOARD_PREVIEW_CHARS - 1) + "😀" + "b".repeat(10)
        val preview = clipboardPreviewText(text)
        assertEquals("a".repeat(CLIPBOARD_PREVIEW_CHARS - 1) + "…", preview)
    }
}
