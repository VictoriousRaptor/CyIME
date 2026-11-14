package com.kingzcheung.xime.service

import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译栏开着时，应用输入框的光标回执哪些算用户点了输入框（输入要回到应用），
 * 以及工具栏长按列表从插件节点里取哪一项。
 */
class LiveBarFocusTest {
    @Test fun tappingElsewhereInTheAppFieldGivesInputBack() {
        assertTrue(liveBarCaretMovedByUser(5, 5, 2, 2, -1, -1, wroteComposing = false, sinceLastWriteMs = 5_000))
        // 选了一段字也算
        assertTrue(liveBarCaretMovedByUser(5, 5, 0, 5, -1, -1, wroteComposing = false, sinceLastWriteMs = 5_000))
        // 译文写着时点到别处
        assertTrue(liveBarCaretMovedByUser(9, 9, 1, 1, 4, 9, wroteComposing = true, sinceLastWriteMs = 5_000))
    }

    @Test fun ourOwnTranslationWritesDoNotCount() {
        // 刚写完译文，光标跟着走
        assertFalse(liveBarCaretMovedByUser(4, 4, 9, 9, 4, 9, wroteComposing = true, sinceLastWriteMs = 20))
        // 写完很久才到的回执，光标正好在自己写的组合区末尾
        assertFalse(liveBarCaretMovedByUser(4, 4, 9, 9, 4, 9, wroteComposing = true, sinceLastWriteMs = 5_000))
        // 光标没变
        assertFalse(liveBarCaretMovedByUser(9, 9, 9, 9, 4, 9, wroteComposing = true, sinceLastWriteMs = 5_000))
    }

    @Test fun theOwnWriteWindowIsShort() {
        assertFalse(liveBarCaretMovedByUser(5, 5, 2, 2, -1, -1, false, LiveToolBar.OWN_WRITE_MS - 1))
        assertTrue(liveBarCaretMovedByUser(5, 5, 2, 2, -1, -1, false, LiveToolBar.OWN_WRITE_MS))
    }

    @Test fun selectActionNamesTheSettingAndTheValue() {
        assertEquals("select:targetLang:日文", toolPanelSelectAction("targetLang", "日文"))
    }

    @Test fun quickListIsTheSelectMarkedQuick() {
        val nodes = listOf(
            UiNode(UiNodeType.BUTTON, key = "lang", label = "EN", style = "fallback"),
            UiNode(UiNodeType.SELECT, key = "sourceLang", label = "原文", value = "自动检测", options = listOf("自动检测", "英文")),
            UiNode(UiNodeType.SELECT, key = "targetLang", label = "翻译成", value = "英文", options = listOf("英文", "日文"), style = "quick"),
        )
        val quick = toolPanelQuickNode(nodes)!!
        assertEquals("targetLang", quick.key)
        assertEquals("翻译成", quick.title)
        assertEquals(listOf("英文", "日文"), quick.options)
        assertEquals("英文", quick.selected)
    }

    @Test fun noQuickSelectMeansNoLongPressList() {
        assertNull(toolPanelQuickNode(null))
        assertNull(toolPanelQuickNode(listOf(UiNode(UiNodeType.SELECT, key = "targetLang", options = listOf("英文")))))
        assertNull("没有选项不弹", toolPanelQuickNode(listOf(UiNode(UiNodeType.SELECT, key = "targetLang", style = "quick"))))
    }
}
