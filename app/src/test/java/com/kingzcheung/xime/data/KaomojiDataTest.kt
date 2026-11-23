package com.kingzcheung.xime.data

import org.junit.Assert.*
import org.junit.Test

class KaomojiDataTest {
    @Test fun longDeleteFindsOneCompleteFaceAtTheCursor() {
        for (face in KaomojiData.categories.flatMap { it.faces }) {
            val before = "正文$face".takeLast(KaomojiData.maxFaceLength)
            assertEquals(face, KaomojiData.faceBeforeCursor(before, face))
        }
        assertEquals(":-)", KaomojiData.faceBeforeCursor("正文o:-)", ":-)"))
        assertEquals("o:-)", KaomojiData.faceBeforeCursor("正文o:-)", "o:-)"))
        assertEquals(":-)", KaomojiData.faceBeforeCursor("hello:-)", null))
        assertEquals("(･o･;)", KaomojiData.faceBeforeCursor("正文(･o･;)", null))
        assertEquals("^_^", KaomojiData.faceBeforeCursor("正文:-)^_^", ":-)"))
        assertNull(KaomojiData.faceBeforeCursor("正文(*´▽｀*", "(*´▽｀*)"))
        assertNull(KaomojiData.faceBeforeCursor("正文", "正文"))
        assertNull(KaomojiData.faceBeforeCursor(null, ":-)"))
    }

    @Test fun catalogHasDistinctCompleteUnicodeStringsAndIndependentHistory() {
        val faces = KaomojiData.categories.flatMap { it.faces }
        assertEquals(23, KaomojiData.categories.size)
        assertEquals(611, faces.size)
        assertEquals(faces.size, faces.distinct().size)
        // 11-22 他点名要的那个放在「流行」第一个，原样（全角括号）。
        assertEquals("流行", KaomojiData.categories.first().name)
        assertEquals("\uFF08 \u15DC \u2038 \u15DC \uFF09", KaomojiData.categories.first().faces.first())
        assertTrue(faces.all { it.isNotBlank() && '\n' !in it && '\uFFFD' !in it && '\u2060' !in it })
        assertNotEquals(RecentUsageStore.KEY_RECENT_EMOJIS, RecentUsageStore.KEY_RECENT_KAOMOJI)
        val face = "(づ｡◕‿‿◕｡)づ"
        assertEquals(listOf(face, "(T_T)"), RecentUsageStore.record(listOf("(T_T)", face), face))
    }
}
