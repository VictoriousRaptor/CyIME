package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection
import android.view.inputmethod.BaseInputConnection
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GlideInputPreviewTest {
    @Test fun previewUsesOnlyTheSelectedDisplayLocation() {
        var session = 1L
        var inputBox = false
        var inputText = ""
        var candidateText = ""
        var inputWrites = 0
        val router = GlidePreviewRouter({ session }, { inputBox }, { text, _ -> inputWrites++; inputText = text },
            { inputText = "" }, { candidateText = it })
        router.show("ni hao", 1L)
        assertEquals("ni hao", candidateText)
        assertEquals(0, inputWrites)
        inputBox = true
        router.show("hello", 1L)
        assertEquals("", candidateText)
        assertEquals("hello", inputText)
        router.end()
        assertEquals("", inputText)
        session = 2L
        router.show("stale", 1L)
        assertEquals("", inputText)
    }
    @Test fun composingPreviewIsReplacedAndRemovedWithoutDuplicatingText() {
        val editable = SpannableStringBuilder("before ")
        Selection.setSelection(editable, editable.length)
        val view = android.view.View(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val ic = object : BaseInputConnection(view, true) {
            override fun getEditable(): Editable = editable
        }
        var written: String? = null
        val preview = GlideInputPreview({ ic }, { 1L }, { null }, { true }, { written = it },
            { written == it }, { ic.setComposingText("", 1); ic.finishComposingText(); written = null })
        preview.show("hel", 1L)
        assertEquals("before hel", editable.toString())
        preview.show("hello", 1L)
        assertEquals("before hello", editable.toString())
        preview.end()
        assertEquals("before ", editable.toString())
        ic.commitText("hello", 1)
        assertEquals("before hello", editable.toString())
    }
    @Test fun previewReplacesItselfAndClearsOnEnd() {
        val ic = mock<InputConnection>()
        whenever(ic.setComposingText(any(), eq(1))).thenReturn(true)
        var current: String? = null
        var cleared = 0
        val preview = GlideInputPreview({ ic }, { 1L }, { null }, { true }, { current = it },
            { current == it }, { cleared++; current = null })
        preview.show("hel", 1L)
        preview.show("hello", 1L)
        assertEquals("hello", current)
        preview.end()
        preview.end()
        assertEquals(1, cleared)
        verify(ic).setComposingText("hel", 1)
        verify(ic).setComposingText("hello", 1)
    }

    @Test fun previousCompositionIsRestoredAndOldSessionCannotWrite() {
        val ic = mock<InputConnection>()
        whenever(ic.setComposingText(any(), eq(1))).thenReturn(true)
        var current: String? = "xi"
        var session = 1L
        val preview = GlideInputPreview({ ic }, { session }, { current }, { true }, { current = it },
            { current == it }, { current = null })
        preview.show("an", 1L)
        assertEquals("xi an", current)
        preview.end()
        assertEquals("xi", current)
        preview.showComplete("xi men", 1L)
        assertEquals("完整编码不能重复拼接旧内容", "xi men", current)
        preview.end()
        assertEquals("xi", current)
        session = 2L
        preview.show("stale", 1L)
        verify(ic, never()).setComposingText("stale", 1)
    }

    @Test fun selectionAndChangedEditorAreNeverCleared() {
        val ic = mock<InputConnection>()
        whenever(ic.getSelectedText(0)).thenReturn("selected")
        var cleared = false
        var session = 1L
        val preview = GlideInputPreview({ ic }, { session }, { null }, { true }, {}, { true }, { cleared = true })
        preview.show("word", 1L)
        verify(ic, never()).setComposingText(any(), eq(1))
        whenever(ic.getSelectedText(0)).thenReturn(null)
        whenever(ic.setComposingText(any(), eq(1))).thenReturn(true)
        preview.show("word", 1L)
        session = 2L
        preview.end()
        assertEquals(false, cleared)
    }
}
