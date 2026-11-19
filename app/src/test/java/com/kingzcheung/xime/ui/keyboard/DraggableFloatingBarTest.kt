package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 语音悬浮条用的拖动宿主：拖动按实体键盘浮条的规则改位置并吸边，点按里面的按钮照常生效。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class DraggableFloatingBarTest {
    @get:Rule val rule = createComposeRule()

    private var position by mutableStateOf(HardwareToolbarPosition())
    private var clicks = 0

    private fun setContent() {
        rule.setContent {
            Box(Modifier.size(400.dp, 600.dp)) {
                DraggableFloatingBar(position, { position = it }, 200.dp) {
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("bar").clickable { clicks++ })
                }
            }
        }
    }

    @Test fun `starts docked at the bottom centre like the physical keyboard bar`() {
        setContent()
        assertEquals(HardwareToolbarPosition(0.5f, 1f), position)
    }

    @Test fun `dragging to the left edge snaps there and remembers the position`() {
        setContent()
        rule.onNodeWithTag("bar").performTouchInput {
            down(center)
            moveBy(Offset(-150f, -300f))
            moveBy(Offset(-400f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(0f, position.xFraction)
        assertTrue("moved up, no longer bottom-docked", position.yFraction < 1f)
        assertEquals(0, clicks)
    }

    private var vertical = false

    private fun setSideDockContent() {
        rule.setContent {
            Box(Modifier.size(400.dp, 600.dp)) {
                DraggableFloatingBar(position, { position = it }, 360.dp, sideDockSize = DpSize(56.dp, 200.dp),
                    dockPreviewColor = Color.Blue) { isVertical ->
                    vertical = isVertical
                    Box(Modifier.fillMaxWidth().height(if (isVertical) 200.dp else 56.dp).testTag("bar").clickable { clicks++ })
                }
            }
        }
    }

    @Test fun `like Gboard, pushing the voice bar off the side docks it there as a vertical bar`() {
        setSideDockContent()
        rule.onNodeWithTag("bar").performTouchInput {
            down(center)
            moveBy(Offset(-100f, -200f))
            moveBy(Offset(-250f, 0f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("floating-bar-dock-preview").assertExists()
        rule.onNodeWithTag("bar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0f, position.xFraction)
        assertTrue(vertical)
        rule.onNodeWithTag("floating-bar-dock-preview").assertDoesNotExist()
        assertEquals(0, clicks)
    }

    @Test fun `a small sideways move does not dock the voice bar to the side`() {
        setSideDockContent()
        rule.onNodeWithTag("bar").performTouchInput {
            down(center)
            moveBy(Offset(-30f, -200f))
            up()
        }
        rule.waitForIdle()
        assertTrue(position.xFraction > 0f && position.xFraction < 1f)
        assertTrue(!vertical)
    }

    @Test fun `a tap still reaches the buttons inside`() {
        setContent()
        rule.onNodeWithTag("bar").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
        assertEquals(HardwareToolbarPosition(0.5f, 1f), position)
    }
}
