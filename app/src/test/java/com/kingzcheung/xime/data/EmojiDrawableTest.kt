package com.kingzcheung.xime.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiDrawableTest {
    @Test fun emojiTheSystemFontCannotDrawIsLeftOut() {
        // #387: 系统字体没有新版 emoji 时，面板里不放空白方块。
        val categories = listOf(EmojiCategory("笑脸", "😀", emojis = listOf("😀", "🫠", "😂")))
        val shown = EmojiData.drawable(categories) { it != "🫠" }
        assertEquals(listOf("😀", "😂"), shown.single().emojis)
        assertEquals("笑脸", shown.single().name)
    }
}
