package com.kingzcheung.xime.ui.render

import android.app.Application
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.PinyinEditSession
import com.kingzcheung.xime.service.HardwareCursorAnchor
import com.kingzcheung.xime.ui.keyboard.PreeditEditorBar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw400dp-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreeditMagnifierBoundsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun settle() { repeat(4) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() } }

    private fun verifyTracking(inline: Boolean) {
        val tracking = mutableStateOf(false)
        val y = mutableStateOf(300)
        val frame = mutableStateOf(PinyinEditSession(1L, "rime_ice", "nihaoshijie",
            "", "", "ni'hao'shi'jie", 0, false))
        val reports = mutableListOf<HardwareCursorAnchor?>()
        var hostTop = Float.NaN
        var inlineOwner: android.view.View? = null
        rule.setContent {
            inlineOwner = LocalView.current
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(x = 40.dp, y = y.value.dp).width(300.dp)
                    .onGloballyPositioned { hostTop = it.positionInWindow().y }) {
                    PreeditEditorBar(frame.value, Color.White, Color.Black, Color.Blue, {}, {}, {},
                        inline = inline, onCaretBounds = if (tracking.value) ({ reports += it }) else null)
                }
            }
        }
        settle()
        assertTrue(reports.isEmpty())
        rule.runOnIdle { tracking.value = true }
        settle()

        fun assertScreenPosition() {
            val text = rule.onNodeWithTag("preedit-editor-code", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val position = text.fetchSemanticsNode().positionInRoot
            val caret = layouts.single().getCursorRect(0)
            rule.runOnIdle {
                val owner = if (inline) inlineOwner!! else
                    WindowInspector.getGlobalWindowViews().single { it.javaClass.simpleName == "PopupLayout" }
                val screen = IntArray(2).also(owner::getLocationOnScreen)
                val window = IntArray(2).also(owner::getLocationInWindow)
                val anchor = reports.last()!!
                if (!inline) {
                    val params = owner.layoutParams as WindowManager.LayoutParams
                    assertEquals("hostTop=$hostTop popup=$params screen=${screen.toList()} window=${window.toList()}",
                        (hostTop - owner.height - 2).coerceAtLeast(0f), params.y.toFloat(), 1f)
                    assertEquals("Popup layout params must be applied to its screen position: $params",
                        params.y, screen[1] - window[1])
                    assertTrue("Popup must exercise a nonzero screen origin", screen[1] > 0)
                }
                assertEquals(position.x + caret.left + screen[0], anchor.left, 1f)
                assertEquals(position.y + caret.top + screen[1], anchor.top, 1f)
                assertEquals(position.y + caret.bottom + screen[1], anchor.bottom, 1f)
            }
        }

        assertScreenPosition()
        val before = reports.last()!!
        rule.runOnIdle { y.value += 40 }
        settle()
        assertScreenPosition()
        val popup = WindowInspector.getGlobalWindowViews().singleOrNull { it.javaClass.simpleName == "PopupLayout" }
        assertEquals("inline=$inline hostTop=$hostTop popup=${popup?.width}x${popup?.height} " +
            "params=${popup?.layoutParams} textPosition=" +
            rule.onNodeWithTag("preedit-editor-code", useUnmergedTree = true).fetchSemanticsNode().positionInRoot,
            before.top + 40, reports.last()!!.top, 1f)
        rule.runOnIdle { tracking.value = false }
        settle()
        assertNull(reports.last())
        val stopped = reports.size
        rule.runOnIdle { frame.value = frame.value.copy(caret = 4); y.value += 40 }
        settle()
        assertEquals(stopped, reports.size)
    }

    @Test fun inlineEditorTracksScreenCoordinatesOnlyWhileRequested() = verifyTracking(inline = true)
    @Test fun popupEditorTracksItsOwnWindowScreenCoordinatesOnlyWhileRequested() = verifyTracking(inline = false)
}
