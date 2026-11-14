package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.kingzcheung.xime.keyboard.QuickActionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InlineQuickActionEditorTest {
    @Test fun hideAndRestoreAppendInOrderAndPreserveUnavailableEntries() {
        val saved = QuickActionLayout(listOf("emoji"), listOf("settings", "unavailable", "sound"))
        val editor = InlineQuickActionEditor()
        val available = listOf("emoji", "settings", "sound", "ocr", "glass")
        editor.start(saved, listOf("emoji"), listOf("settings", "sound"), available)
        assertEquals(listOf("ocr", "glass"), editor.unused)
        editor.toggleVisibility("sound")
        assertEquals(listOf("settings"), editor.menu)
        assertEquals(listOf("ocr", "glass", "sound"), editor.unused)
        editor.toggleVisibility("ocr")
        assertEquals(listOf("settings", "ocr"), editor.menu)
        assertEquals(listOf("glass", "sound"), editor.unused)
        val result = editor.finish()
        assertEquals(listOf("settings", "unavailable", "ocr"), result.menu)
        editor.start(result, listOf("emoji"), listOf("settings", "ocr"), available)
        editor.toggleVisibility("settings")
        assertEquals("「设置」不能撤掉", listOf("settings", "ocr"), editor.menu)
        assertEquals(listOf("settings", "unavailable", "ocr"), editor.finish().menu)
    }

    @Test fun hoveringShowsOrderBeforeReleaseAndAvoidsJitter() {
        val editor = InlineQuickActionEditor()
        editor.start(QuickActionLayout(emptyList(), listOf("settings", "emoji", "clipboard")),
            emptyList(), listOf("settings", "emoji", "clipboard"))
        editor.register("settings", Rect(0f, 0f, 10f, 10f))
        editor.register("emoji", Rect(10f, 0f, 20f, 10f))
        editor.register("clipboard", Rect(20f, 0f, 30f, 10f))
        editor.begin(Offset(5f, 5f))
        editor.move(Offset(15f, 5f))
        assertEquals(listOf("emoji", "settings", "clipboard"), editor.menu)
        editor.move(Offset(16f, 5f)) // Before layout reports new bounds, do not swap back.
        assertEquals(listOf("emoji", "settings", "clipboard"), editor.menu)
        editor.register("emoji", Rect(0f, 0f, 10f, 10f))
        editor.register("settings", Rect(10f, 0f, 20f, 10f))
        editor.move(Offset(17f, 5f))
        editor.move(Offset(25f, 5f))
        assertEquals(listOf("emoji", "clipboard", "settings"), editor.menu)
        editor.cancelDrag()
        assertEquals(listOf("settings", "emoji", "clipboard"), editor.menu)
    }

    @Test fun hoveringAcrossSectionsPreviewsBeforeSaving() {
        val editor = InlineQuickActionEditor()
        editor.start(QuickActionLayout(listOf("emoji"), listOf("settings")), listOf("emoji"), listOf("settings"))
        editor.register("emoji", Rect(0f, 0f, 10f, 10f))
        editor.register("settings", Rect(0f, 20f, 10f, 30f))
        editor.begin(Offset(5f, 5f))
        assertEquals(true, editor.dragFromToolbar)
        editor.move(Offset(5f, 25f))
        assertEquals(false, editor.dragFromToolbar)
        assertEquals(10f, editor.dragBounds.width)
        editor.register("emoji", Rect(10f, 20f, 20f, 30f))
        editor.toolbarArea = Rect(0f, 0f, 20f, 10f)
        editor.move(Offset(5f, 5f))
        assertEquals(true, editor.dragFromToolbar)
        editor.move(Offset(5f, 25f))
        assertEquals(emptyList<String>(), editor.toolbar)
        assertEquals(listOf("emoji", "settings"), editor.menu)
        editor.drop(Offset(5f, 25f))
        assertEquals(listOf("emoji", "settings"), editor.finish().menu)
    }

    @Test fun draggedItemKeepsTheGrabPointUnderTheFinger() {
        val editor = InlineQuickActionEditor()
        editor.start(QuickActionLayout(listOf("emoji"), listOf("settings")), listOf("emoji"), listOf("settings"))
        editor.register("emoji", Rect(10f, 20f, 50f, 60f))
        editor.begin(Offset(15f, 30f))
        editor.move(Offset(85f, 110f))
        assertEquals(Offset(80f, 100f), editor.dragTopLeft())
        editor.cancelDrag()
    }

    @Test fun droppingOnLaterCardPlacesItAtThatPosition() {
        val editor = InlineQuickActionEditor()
        editor.start(QuickActionLayout(emptyList(), listOf("settings", "emoji", "clipboard")),
            emptyList(), listOf("settings", "emoji", "clipboard"))
        editor.register("settings", Rect(0f, 0f, 10f, 10f))
        editor.register("clipboard", Rect(20f, 0f, 30f, 10f))
        editor.begin(Offset(5f, 5f))
        editor.drop(Offset(25f, 5f))
        assertEquals(listOf("emoji", "clipboard", "settings"), editor.menu)
    }

    @Test fun movesBetweenAreasAndRestoresHiddenEntriesOnSave() {
        val editor = InlineQuickActionEditor()
        editor.start(QuickActionLayout(listOf("emoji", "hidden", "voice"), listOf("settings", "clipboard")),
            listOf("emoji", "voice"), listOf("settings", "clipboard"))
        editor.register("voice", Rect(10f, 0f, 20f, 10f))
        editor.register("clipboard", Rect(10f, 20f, 20f, 30f))
        editor.begin(Offset(15f, 5f))
        editor.drop(Offset(15f, 25f))
        assertEquals(listOf("emoji"), editor.toolbar)
        assertEquals(listOf("settings", "voice", "clipboard"), editor.menu)
        val saved = editor.finish()
        assertEquals(listOf("emoji", "hidden"), saved.toolbar)
        assertEquals(listOf("settings", "voice", "clipboard"), saved.menu)
        assertFalse(editor.active)
    }

    @Test fun emptyToolbarAcceptsDropAndCancelledDragDoesNotMove() {
        val editor = InlineQuickActionEditor()
        editor.start(QuickActionLayout(emptyList(), listOf("settings", "emoji")), emptyList(), listOf("settings", "emoji"))
        editor.toolbarArea = Rect(0f, 0f, 100f, 10f)
        editor.register("emoji", Rect(0f, 20f, 20f, 40f))
        editor.begin(Offset(10f, 30f))
        editor.cancelDrag()
        assertEquals(emptyList<String>(), editor.toolbar)
        editor.begin(Offset(10f, 30f))
        editor.drop(Offset(50f, 5f))
        assertEquals(listOf("emoji"), editor.toolbar)
        assertEquals(listOf("settings"), editor.menu)
    }
}
