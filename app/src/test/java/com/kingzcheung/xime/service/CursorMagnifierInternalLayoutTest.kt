package com.kingzcheung.xime.service

import android.app.Activity
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CursorMagnifierText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CursorMagnifierInternalLayoutTest {
    @Test fun softWrappedEditorUsesTheRealLineAtTheCaretIncludingTheWrapBoundary() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val editor = EditText(controller.get()).apply {
                textSize = 24f
                setPadding(0, 0, 0, 0)
                setText("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
            }
            controller.get().setContentView(editor)
            editor.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 160, 600)
            val layout = editor.layout!!
            assertTrue("The fixture must wrap without a newline", layout.lineCount > 2)
            val start = layout.getLineStart(1)
            val end = layout.getLineEnd(1)
            assertTrue(end - start >= 4)
            val text = editor.text.toString()
            assertEquals(CursorMagnifierText(text.substring(start, start + 4), 0),
                internalCursorMagnifierText(editor, start))
            assertEquals(CursorMagnifierText(text.substring(maxOf(start, end - 5), end),
                end - 1 - maxOf(start, end - 5)), internalCursorMagnifierText(editor, end - 1))
            val nextEnd = layout.getLineEnd(2)
            assertEquals(CursorMagnifierText(text.substring(end, minOf(end + 4, nextEnd)), 0),
                internalCursorMagnifierText(editor, end))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun unlaidOutEditorsDoNotPretendLogicalTextIsAVisualLine() {
        val editor = EditText(org.robolectric.RuntimeEnvironment.getApplication()).apply { setText("abc") }
        assertNull(internalCursorMagnifierText(editor, 1))
    }
}
