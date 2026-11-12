package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w440dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T9GlideInputTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val controller = mock<T9InputController> {
        on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
    }
    private var editor = 1L
    private var input by mutableStateOf("")

    private fun show(enabled: Boolean, inPreeditEditor: Boolean = true) {
        AppFonts.initialize(rule.activity.application)
        val prefs = KeyboardInputPreferences(t9GlideTyping = enabled, t9FlickLetters = true)
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            inputAdmissionTicket = { editor }, onPreeditKeyInput = {
                if (inPreeditEditor) input += it
                inPreeditEditor
            })
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides prefs) {
                Column(Modifier.width(380.dp).testTag("keyboard")) {
                    CandidateBar(if (input.isEmpty()) CandidateBarState.Idle else
                        CandidateBarState.ChineseCandidates(listOf("来", "赖", "莱"), inputText = input),
                        visuals = CandidateBarVisuals(Color.DarkGray, Color.White, Color.Gray),
                        callbacks = CandidateBarCallbacks(onCandidateSelect = {}))
                    T9KeyboardLayout({}, callbacks, KeyboardUiState(currentSchemaId = "t9_pinyin"),
                        controller, Color.DarkGray, Color.White, Color.Gray, modifier = Modifier.height(250.dp))
                }
            }
        }
    }
    private val board get() = rule.onNodeWithTag("keyboard")
    private fun centerOf(text: String) = rule.onNodeWithText(text, ignoreCase = true, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.center - board.fetchSemanticsNode().boundsInRoot.topLeft

    @Test fun tapAndCrossKeyStrokeComposeExactlyOnce() {
        show(true)
        val five = centerOf("JKL"); val two = centerOf("ABC"); val four = centerOf("GHI")
        board.performTouchInput { down(five); moveTo(two); moveTo(four) }
        assertEquals("", input)
        System.getenv("CYIME_RENDER_OUT")?.let { dir ->
            val root = rule.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(image)) }
            val bounds = board.fetchSemanticsNode().boundsInWindow
            File(dir, "nine-glide.png").outputStream().use {
                Bitmap.createBitmap(image, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(),
                    bounds.height.toInt()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        board.performTouchInput { up() }
        // 抬手后在后台按词表比对整条线（来 lai = 524），再按点按同一路径送进去。
        rule.waitUntil(5000) { input.isNotEmpty() }
        assertEquals("524", input)
        board.performTouchInput { click(four) }
        assertEquals("5244", input)
        // 别的用户 11-03：从 9 直线划到 4 会路过 8 或 5，以前都算进去；现在只出 94（xi、yi、zi）。
        val nine = centerOf("WXYZ")
        board.performTouchInput { down(nine); moveTo(lerp(nine, four, 0.5f)); moveTo(four); up() }
        rule.waitUntil(5000) { input.length > 4 }
        assertEquals("524494", input)
    }

    /**
     * 用户 11-11「先打一个字再划…划了就没了」：点 9 的手指还没抬，另一根手指已经从 5 划向 2、4。
     * 以前第二根手指一按下整次触摸就放弃，这一划什么都不出；现在 9 照常出，划的 524（来）接在后面。
     */
    @Test fun glideRightAfterATapWithTheOtherThumbStillGlides() {
        show(true)
        val five = centerOf("JKL"); val two = centerOf("ABC"); val four = centerOf("GHI"); val nine = centerOf("WXYZ")
        board.performTouchInput { down(0, nine); advanceEventTime(30); down(1, five); moveTo(1, two); up(0); moveTo(1, four); up(1) }
        rule.waitUntil(5000) { input.length >= 4 }
        assertEquals("9524", input)
        // 点按的手指等划完才抬：一样两样都出。
        board.performTouchInput { down(0, nine); advanceEventTime(30); down(1, five); moveTo(1, two); moveTo(1, four); up(1); up(0) }
        rule.waitUntil(5000) { input.length >= 8 }
        // 划的词在后台比对，和后抬起的点按谁先到都行，两样都不能丢。
        org.junit.Assert.assertTrue(input, input == "95249524" || input == "95245249")
    }

    @Test fun leavingDigitsCancellingOrChangingEditorDropsTheStroke() {
        show(true)
        val five = centerOf("JKL"); val six = centerOf("MNO")
        val rail = rule.onNodeWithTag("t9-left-rail").fetchSemanticsNode().boundsInRoot.center -
            board.fetchSemanticsNode().boundsInRoot.topLeft
        board.performTouchInput { down(five); moveTo(six); moveTo(rail); up() }
        assertEquals("left digit grid", "", input)
        board.performTouchInput { down(five); moveTo(six); cancel() }
        assertEquals("cancelled", "", input)
        board.performTouchInput { down(five); moveTo(six) }
        rule.runOnIdle { editor++ }
        board.performTouchInput { up() }
        assertEquals("editor changed", "", input)
        board.performTouchInput { down(five); moveTo(six); down(1, five + Offset(1f, 0f)); up(1); up() }
        assertEquals("", input)
    }

    /** 用户 11-09：九键滑行开着打不了字。落在 5 的上沿、手指往上抖进 2 一点点，不能被当成滑行吞掉。 */
    @Test fun shortDriftFromAKeyEdgeIsNotAGlide() {
        show(true)
        val five = centerOf("JKL"); val two = centerOf("ABC")
        board.performTouchInput { down(lerp(five, two, 0.35f)); moveTo(lerp(five, two, 0.75f)); up() }
        val glided = runCatching { rule.waitUntil(1500) { input.contains("52") } }.isSuccess
        org.junit.Assert.assertFalse("短短一抖不该滑出 52：$input", glided)
        // 用户 11-10：开着九键滑行快打一句吞一半的键。没成滑行的这一下要照常出 5，不能两头都不算。
        assertEquals("没成滑行就是点了 5", "5", input)
        // 从 5 中间划到 2 中间仍是滑行。
        input = ""
        board.performTouchInput { down(five); moveTo(two); up() }
        rule.waitUntil(5000) { input.length >= 2 }
        assertEquals("从 5 起滑出两个键", "5", input.take(1))
    }

    /** 用户 11-10：快打时手指在键上挪了一点（过了触摸阈值、没进别的键），这一下照常出字。 */
    @Test fun driftInsideOneKeyIsStillATap() {
        show(true)
        val five = centerOf("JKL")
        board.performTouchInput { down(five); moveTo(five + Offset(20f, 6f)); up() }
        assertEquals("5", input)
        board.performTouchInput { down(five + Offset(-6f, 4f)); moveTo(five + Offset(14f, -10f)); moveTo(five + Offset(18f, -8f)); up() }
        assertEquals("55", input)
        // 第二根手指在别的键上按下时，前一下照样算（两手交替快打）。
        val six = centerOf("MNO")
        board.performTouchInput { down(0, five); moveTo(0, five + Offset(15f, 0f)); down(1, six); up(0); up(1) }
        assertEquals("5556", input)
    }

    /** 从数字键拖到左栏没成滑行：和以前一样不出字（滑出键外取消）。 */
    @Test fun dragFromADigitOntoTheRailEntersNothing() {
        show(true)
        val four = centerOf("GHI")
        val rail = rule.onNodeWithTag("t9-left-rail").fetchSemanticsNode().boundsInRoot.center -
            board.fetchSemanticsNode().boundsInRoot.topLeft
        board.performTouchInput { down(four); moveTo(lerp(four, rail, 0.5f)); moveTo(rail); up() }
        rule.waitForIdle()
        assertEquals("", input)
    }

    @Test fun disabledGlideDoesNotFeedCrossedDigits() {
        show(false)
        val five = centerOf("JKL"); val six = centerOf("MNO")
        board.performTouchInput { down(five); moveTo(six); up() }
        org.junit.Assert.assertFalse(input.contains("56"))
    }

    @Test fun ordinaryFieldUsesNormalDigitQueueWithoutAFlick() {
        show(true, inPreeditEditor = false)
        val five = centerOf("JKL"); val two = centerOf("ABC"); val four = centerOf("GHI")
        board.performTouchInput { down(five); moveTo(two); moveTo(four); up() }
        rule.waitUntil(5000) { org.mockito.Mockito.mockingDetails(controller).invocations.count { it.method.name == "onDigitPressed" } >= 3 }
        inOrder(controller) {
            verify(controller).onDigitPressed("5")
            verify(controller).onDigitPressed("2")
            verify(controller).onDigitPressed("4")
        }
        board.performTouchInput { down(five); moveTo(five + Offset(28f, 0f)); up() }
        org.mockito.Mockito.verify(controller, org.mockito.Mockito.never()).onLetterFlicked("L")
    }

    @Test fun nineKeySettingsAreIndependentOfLetterKeyboardGlide() {
        rule.setContent { com.kingzcheung.xime.ui.settings.LayoutInputSettingsContent(
            com.kingzcheung.xime.ui.settings.LayoutInputCategory.Glide, onBack = {}) }
        val row = rule.onNodeWithText("九键滑行", useUnmergedTree = true).performScrollTo()
        org.junit.Assert.assertFalse(KeyboardInputPreferences.read(rule.activity).t9GlideTyping)
        row.performTouchInput { click() }
        rule.runOnIdle {
            val prefs = KeyboardInputPreferences.read(rule.activity)
            org.junit.Assert.assertTrue(prefs.t9GlideTyping)
            org.junit.Assert.assertFalse(prefs.chineseGlideTyping)
            org.junit.Assert.assertNotNull(InputGestureLocks.lockFor(prefs, InputGestureLock.Option.CURSOR_GESTURE,
                CursorGestureMode.KEYBOARD))
        }
    }
}
