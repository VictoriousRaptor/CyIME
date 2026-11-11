package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.kingzcheung.xime.service.internalCursorMagnifierAnchor
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw400dp-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CursorMagnifierRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private var reads = 0
    private var moves = 0
    private val keys = mutableListOf<String>()
    private var caret = 4
    private var text = "长按空格移动光标😀预览"
    private val session = mutableStateOf(1L)
    private val inputViewVisible = mutableStateOf(true)
    private val inputViewEpoch = mutableLongStateOf(0L)
    private lateinit var editor: android.widget.EditText
    private var missingAnchor = false
    private val editorY = mutableStateOf(260)
    private val tracking = mutableListOf<Boolean>()
    private lateinit var vm: KeyboardViewModel
    private lateinit var callbacks: KeyboardCallbacks

    @Before fun prepare() {
        val app = rule.activity.application
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        SpaceAfterCursorGuard.resetForTest()
        IncognitoState.passwordField = false
    }

    @After fun reset() { IncognitoState.passwordField = false; SpaceAfterCursorGuard.resetForTest() }

    private fun mount(schema: String = "rime_ice", floating: Boolean = false,
        enabled: Boolean = true, width: Float = 70f,
        gesture: CursorGestureMode = CursorGestureMode.SPACE, bottomLift: Boolean = false,
        preedit: com.kingzcheung.xime.rime.PinyinEditSession? = null,
        wrapEditor: Boolean = false, editorWidth: Int = 300,
        reader: suspend () -> CursorMagnifierText? = { reads++; CursorMagnifierText.from(text, caret) }) {
        val app = rule.activity.application
        KeyboardInputPreferences.read(app).copy(cursorGesture = gesture, bottomLiftCursor = bottomLift,
            spaceHold = SpaceHoldAction.CURSOR, cursorHoldSeconds = 0.2f, cursorStepDp = 10f,
            cursorMagnifierEnabled = enabled, cursorMagnifierWidthDp = width).save(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> keys += key }, onCandidateSelect = {},
            onOpenPreeditEditor = preedit?.let { frame -> { reply -> reply(frame) } },
            onCursorMove = { moves++; caret = (caret + it).coerceIn(0, text.length); editor.setSelection(caret) },
            readCursorMagnifierText = reader,
            readCursorMagnifierAnchor = { if (missingAnchor) null else internalCursorMagnifierAnchor(editor, caret) },
            onCursorMagnifierTracking = { _, active -> tracking += active })
        vm = spy(KeyboardViewModel(app))
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(vm).bindT9Controller(callbacks)
        vm.dispatch(KeyboardDispatchAction.InputSessionStarted(false, schema))
        rule.setContent {
            XimeTheme(darkTheme = false, themeId = "soft_blue") {
                Box(Modifier.fillMaxSize().background(Color(0xFFF2F3F6)), contentAlignment = Alignment.BottomCenter) {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                        android.widget.EditText(context).also {
                            editor = it
                            it.showSoftInputOnFocus = false
                            it.setSingleLine(!wrapEditor && !text.contains('\n'))
                            it.textSize = 18f
                            it.setTextColor(android.graphics.Color.BLACK)
                            it.setText(text)
                            it.setSelection(caret)
                        }
                    }, modifier = Modifier.align(Alignment.TopStart).offset(x = 80.dp, y = editorY.value.dp).size(editorWidth.dp, 64.dp))
                    Box(Modifier.size(400.dp, 330.dp)) {
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = schema, inputSessionId = session.value,
                            keyboardHeightDp = 330, keyboardBottomPaddingDp = if (bottomLift) 40 else 0,
                            isFloatingMode = floating, floatingScreenHeightDp = 850),
                            callbacks, modifier = Modifier.fillMaxSize(), inputSessionManagedByHost = true,
                            candidateState = androidx.compose.runtime.remember { mutableStateOf(
                                if (preedit == null) com.kingzcheung.xime.service.CandidateState() else
                                    com.kingzcheung.xime.service.CandidateState(inputText = preedit.text,
                                        preeditText = preedit.text, isComposing = true)) },
                            inputViewVisible = inputViewVisible.value, inputViewEpoch = inputViewEpoch.longValue)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun hold() {
        rule.onNodeWithTag("space-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
    }

    @Test fun bottomLiftSwipeShowsPreviewOnlyAfterActivationAndStopsOnRelease() {
        mount(bottomLift = true, gesture = CursorGestureMode.NONE)
        val strip = rule.onNodeWithTag("bottom-lift-cursor")
        strip.performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        strip.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(2f, 0f)) }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        strip.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(32f, 0f)) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("gesture tracking: reads=$reads moves=$moves keys=$keys", listOf(true), tracking) }
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        strip.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(28f, 0f)) }
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle { assertTrue(moves > 0); assertEquals(listOf(true), tracking) }
        rule.onNodeWithTag("cursor-control-overlay").assertDoesNotExist()
        shot("bottom-lift-swipe")
        strip.performTouchInput { up() }
        assertGestureStopped()
    }

    @Test fun verticalBottomLiftSwipeDoesNotStartMagnifierTracking() {
        mount(bottomLift = true, gesture = CursorGestureMode.NONE)
        rule.onNodeWithTag("bottom-lift-cursor").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -32f)); up()
        }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, reads); assertEquals(0, moves); assertTrue(tracking.isEmpty()) }
    }

    @Test fun removingBottomLiftGestureWhileDraggingStopsTheMagnifier() {
        mount(bottomLift = true, gesture = CursorGestureMode.NONE)
        val strip = rule.onNodeWithTag("bottom-lift-cursor")
        strip.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(32f, 0f)) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("gesture tracking: reads=$reads moves=$moves keys=$keys", listOf(true), tracking) }
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(bottomLiftCursor = false).save(rule.activity) }
        assertGestureStopped()
        rule.onRoot().performTouchInput { up() }
    }

    private fun assertGestureStopped() {
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        val stopped = reads
        rule.mainClock.advanceTimeBy(240)
        rule.runOnIdle { assertEquals(stopped, reads); assertEquals(listOf(true, false), tracking) }
    }

    private fun verifyPanelSwipe(schema: String = "rime_ice", panel: PanelType? = null, cancel: Boolean = false) {
        mount(schema = schema, gesture = CursorGestureMode.KEYBOARD)
        if (panel != null) {
            rule.runOnIdle { vm.enterPanel(panel) }
            rule.mainClock.advanceTimeBy(320)
        }
        val target = if (panel == PanelType.NUMBER) rule.onNodeWithTag("number-zero")
            else if (schema == "rime_ice" && panel == null) rule.onNodeWithText("a", ignoreCase = true, useUnmergedTree = true)
            else rule.onNodeWithContentDescription("发送", useUnmergedTree = true)
        val direction = if (panel == PanelType.NUMBER || schema == "rime_ice" && panel == null) 1f else -1f
        target.performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        target.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(direction * 70f, 0f)) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("gesture tracking: reads=$reads moves=$moves keys=$keys", listOf(true), tracking) }
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.onNodeWithTag("cursor-control-overlay").assertDoesNotExist()
        target.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(direction * 28f, 0f)) }
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle { assertTrue(moves > 0) }
        shot("panel-swipe-${panel ?: schema}")
        target.performTouchInput { if (cancel) cancel() else up() }
        assertGestureStopped()
    }

    @Test fun keyboardPanelSwipeShowsPreviewAndStopsOnCancel() = verifyPanelSwipe(cancel = true)
    @Test fun fourteenKeyPanelSwipeShowsPreview() = verifyPanelSwipe(schema = "pinyin_14jian")
    @Test fun nineKeyPanelSwipeShowsPreview() = verifyPanelSwipe(schema = "t9_pinyin")
    @Test fun numberPanelSwipeShowsPreview() = verifyPanelSwipe(panel = PanelType.NUMBER)
    @Test fun commonSymbolPanelSwipeShowsPreview() = verifyPanelSwipe(panel = PanelType.COMMON_SYMBOL)

    @Test fun emptyEditorNeverShowsCapsuleAndClearingTextHidesAnExistingCapsule() {
        mount(reader = { reads++; CursorMagnifierText.from(editor.text, editor.selectionEnd) })
        rule.runOnIdle { editor.setText("") }
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { editor.setText(text); editor.setSelection(4) }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle { editor.setText("") }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        assertGestureStopped()
    }

    @Test fun multilineEditorMagnifiesOnlyTheCurrentLineWithoutNewlineMarkers() {
        text = "上一行\n当前行😀文字\n下一行"
        caret = 9
        mount()
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle { assertEquals(CursorMagnifierText("当前行😀文字", 5), CursorMagnifierText.from(text, caret)) }
        shot("current-line-only")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        assertGestureStopped()
    }

    @Test fun disabledMagnifierDoesNotReadOrTrackAndCursorMovementStillWorks() {
        mount(enabled = false)
        hold()
        rule.onNodeWithTag("cursor-control-overlay").assertExists()
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.onNodeWithTag("space-key").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(28f, 0f)) }
        rule.mainClock.advanceTimeBy(160)
        rule.runOnIdle { assertEquals(0, reads); assertTrue(tracking.isEmpty()); assertTrue(moves > 0) }
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test
    @Config(shadows = [MagnifierWindowBlurShadow::class])
    fun hidingHeldKeyboardReleasesPreviewAndBlurWithoutRevivingTheGesture() {
        FrostedGlassPreferences.save(rule.activity, FrostedGlassConfig(enabled = true, backgroundOpacity = .9f), false)
        mount()
        hold()
        popupLocation()
        fun blurWindows() = android.view.inspector.WindowInspector.getGlobalWindowViews().filter {
            (it.layoutParams as? android.view.WindowManager.LayoutParams)?.title?.toString() ==
                "CyIME keyboard blur: cursor-magnifier"
        }
        assertEquals(1, blurWindows().size)
        rule.runOnIdle { inputViewVisible.value = false }
        assertGestureStopped()
        assertTrue(blurWindows().isEmpty())
        rule.runOnIdle { inputViewVisible.value = true }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(true, false), tracking) }
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { assertEquals(listOf(true, false, true, false), tracking) }
    }

    @Test fun hideAndShowWithinOneFrameCannotReviveAHeldGesture() {
        mount()
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle {
            inputViewVisible.value = false
            inputViewEpoch.longValue++
            inputViewVisible.value = true
        }
        assertGestureStopped()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { assertEquals(listOf(true, false, true, false), tracking) }
    }

    @Test fun lateReplyCannotReopenPreviewAfterKeyboardIsHidden() {
        val reply = CompletableDeferred<CursorMagnifierText?>()
        mount(reader = { reads++; reply.await() })
        hold()
        rule.runOnIdle { assertEquals(1, reads); inputViewVisible.value = false }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { reply.complete(CursorMagnifierText.from(text, 4)) }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, reads); assertEquals(listOf(true, false), tracking) }
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun persistedWidthAndSwitchApplyLiveWithinBoundsOnFloatingKeyboard() {
        mount(floating = true, width = 50f)
        hold()
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(50.dp)
        shot("width-50")
        rule.runOnIdle {
            KeyboardInputPreferences.read(rule.activity).copy(cursorMagnifierWidthDp = 480f).save(rule.activity)
        }
        rule.mainClock.advanceTimeBy(160)
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(200.dp)
        shot("width-200")
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(cursorMagnifierEnabled = false).save(rule.activity) }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        val stopped = reads
        rule.mainClock.advanceTimeBy(320)
        rule.runOnIdle {
            assertEquals(stopped, reads)
            assertEquals(listOf(true, false), tracking)
            assertEquals(200f, KeyboardInputPreferences.read(rule.activity).cursorMagnifierWidthDp, 0f)
            KeyboardInputPreferences.read(rule.activity).copy(cursorMagnifierEnabled = true).save(rule.activity)
        }
        rule.mainClock.advanceTimeBy(160)
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(200.dp)
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(true, false, true, false), tracking) }
    }

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        File(out).mkdirs()
        // A newly attached popup needs its first window draw after the semantics are available.
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        // PixelCopy needs a real display frame; Robolectric renders the actual window views instead.
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = android.graphics.Canvas(bitmap)
            root.draw(canvas)
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
            File(out, "$name-windows.txt").writeText(windows.joinToString("\n") { view ->
                val location = IntArray(2).also(view::getLocationOnScreen)
                "${view.javaClass.simpleName}: ${view.width}x${view.height} at ${location.toList()}, visibility=${view.visibility}, params=${view.layoutParams}"
            })
            val popup = windows.last { it.javaClass.simpleName == "PopupLayout" }
            assertTrue("Popup window must have completed layout", popup.width > 0 && popup.height > 0)
            val origin = IntArray(2).also(root::getLocationOnScreen)
            val location = IntArray(2).also(popup::getLocationOnScreen)
            val sampleX = location[0] - origin[0] + popup.width / 2
            val sampleY = location[1] - origin[1] + 4
            val underneath = bitmap.getPixel(sampleX, sampleY)
            canvas.save()
            canvas.translate((location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat())
            popup.draw(canvas)
            canvas.restore()
            assertNotEquals("The capsule must actually paint near the editor", underneath, bitmap.getPixel(sampleX, sampleY))
        }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun holdShowsPreviewDragStillMovesAndReleaseStopsReading() {
        mount()
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.onNodeWithTag("space-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("space"), keys); assertEquals(0, reads) }
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        shot("qwerty-before")
        val before = popupLocation()
        rule.onNodeWithTag("space-key").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(28f, 0f)) }
        rule.mainClock.advanceTimeBy(160)
        shot("qwerty-moved")
        val after = popupLocation()
        rule.runOnIdle {
            val anchor = internalCursorMagnifierAnchor(editor, caret)!!
            assertTrue("Capsule stays above the text", after.second + 56 + 8 <= anchor.top + 1)
            assertTrue("Capsule follows the caret horizontally", after.first > before.first)
        }
        rule.runOnIdle { assertTrue(moves > 0); assertTrue(caret > 4); assertEquals(listOf("space"), keys) }
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        val stopped = reads
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(stopped, reads) }
        rule.runOnIdle { assertEquals(listOf(true, false), tracking) }
    }

    @Test
    @Config(shadows = [MagnifierWindowBlurShadow::class])
    fun glassSpaceHoldStillMovesTheCaretAndReleasesItsBlurWindow() {
        FrostedGlassPreferences.save(rule.activity, FrostedGlassConfig(enabled = true, backgroundOpacity = .9f), false)
        mount()
        hold()
        popupLocation()
        fun blurWindows() = android.view.inspector.WindowInspector.getGlobalWindowViews().filter {
            (it.layoutParams as? android.view.WindowManager.LayoutParams)?.title?.toString() ==
                "CyIME keyboard blur: cursor-magnifier"
        }
        val blur = blurWindows().single()
        rule.onNodeWithTag("space-key").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(28f, 0f)) }
        rule.mainClock.advanceTimeBy(160)
        popupLocation()
        assertSame(blur, blurWindows().single())
        rule.runOnIdle { assertTrue(moves > 0); assertTrue(caret > 4); assertEquals(listOf(true), tracking) }
        shot("glass-space-hold")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        assertGestureStopped()
        assertTrue(blurWindows().isEmpty())
    }

    private fun popupLocation(): Pair<Int, Int> {
        repeat(3) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        var location = 0 to 0
        rule.runOnIdle {
            val popup = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.javaClass.simpleName == "PopupLayout" }
            val at = IntArray(2).also(popup::getLocationOnScreen)
            location = at[0] to at[1]
        }
        return location
    }

    @Test fun previewFollowsTextScrollingAndEditorRelayout() {
        mount()
        hold()
        rule.runOnIdle { caret = 8; editor.setSelection(caret) }
        rule.mainClock.advanceTimeBy(160)
        val before = popupLocation()
        rule.runOnIdle { editor.scrollTo(editor.scrollX + 24, editor.scrollY) }
        rule.mainClock.advanceTimeBy(96)
        val scrolled = popupLocation()
        assertTrue(scrolled.first < before.first)
        rule.runOnIdle { editorY.value += 80 }
        rule.mainClock.advanceTimeBy(96)
        val moved = popupLocation()
        assertEquals(scrolled.second + 80, moved.second)
        shot("editor-scrolled-and-moved")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun missingTextCoordinatesNeverFallBackToTheKeyboardEdge() {
        missingAnchor = true
        mount()
        hold()
        rule.onNodeWithTag("cursor-control-overlay").assertExists()
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun hostInlinePreeditKeepsUsingTheHostsVisualLineReader() {
        val frame = com.kingzcheung.xime.rime.PinyinEditSession(1L, "rime_ice", "nihaoshijie",
            "", "", "ni'hao'shi'jie", 3, false)
        mount(preedit = frame, reader = { reads++; CursorMagnifierText("宿主当前行", 2) })
        rule.runOnIdle { SettingsPreferences.setInputTextLocation(rule.activity, SettingsPreferences.INPUT_TEXT_INPUT_BOX) }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { assertTrue(callbacks.onPreeditCursorMove?.invoke("preedit_cursor:1") == true) }
        rule.mainClock.advanceTimeBy(32)
        hold()
        rule.onNodeWithTag("preedit-editor").assertDoesNotExist()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle { assertTrue("Inline composition must use actual host layout", reads > 0) }
        shot("host-inline-preedit")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun softWrappedHostShowsOnlyFourCharactersPerSideFromTheActualLine() {
        text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        var shown: CursorMagnifierText? = null
        mount(wrapEditor = true, editorWidth = 120, reader = {
            reads++
            com.kingzcheung.xime.service.internalCursorMagnifierText(editor, caret).also { shown = it }
        })
        var expected: CursorMagnifierText? = null
        rule.runOnIdle {
            assertTrue(editor.layout.lineCount > 2)
            val start = editor.layout.getLineStart(1)
            val end = editor.layout.getLineEnd(1)
            caret = start + 2
            editor.setSelection(caret)
            expected = CursorMagnifierText(text.substring(start, minOf(caret + 4, end)), 2)
        }
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        rule.runOnIdle { assertEquals(expected, shown) }
        shot("soft-wrapped-current-line")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun preeditCapsuleFollowsTheDisplayedCodeCaret() {
        val frame = mutableStateOf(com.kingzcheung.xime.rime.PinyinEditSession(1L, "rime_ice", "nihaoshijie",
            "", "", "ni'hao'shi'jie", 3, false))
        val anchor = mutableStateOf<com.kingzcheung.xime.service.HardwareCursorAnchor?>(null)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFFF2F3F6))) {
                Box(Modifier.offset(x = 80.dp, y = 300.dp).width(300.dp)) {
                    PreeditEditorBar(frame.value, Color.White, Color.Black, Color.Blue, {}, {}, {},
                        inline = true, onCaretBounds = { anchor.value = it })
                }
                CursorMagnifier(1L, null, Color.White, Color.Black, Color.Blue,
                    composition = CursorMagnifierText.from(frame.value.text, frame.value.caret), readAnchor = { anchor.value })
            }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(160)
        val before = popupLocation()
        rule.runOnIdle { frame.value = frame.value.copy(caret = 8) }
        rule.mainClock.advanceTimeBy(160)
        val after = popupLocation()
        assertTrue(after.first > before.first)
        rule.runOnIdle { assertTrue(after.second + 56 + 8 <= anchor.value!!.top + 1) }
        shot("preedit-caret")
    }

    @Test fun nineKeyAndFloatingKeyboardShareThePreview() {
        mount(schema = "t9_pinyin", floating = true)
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        shot("nine-key-floating")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun fourteenKeyKeyboardSharesThePreview() {
        mount(schema = "pinyin_14jian")
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        shot("fourteen-key")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun strokeKeyboardSharesThePreview() {
        mount(schema = "stroke")
        hold()
        rule.onNodeWithTag("cursor-magnifier").assertIsDisplayed()
        shot("stroke")
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun passwordStillAllowsCursorGestureWithoutReadingText() {
        IncognitoState.passwordField = true
        mount()
        hold()
        rule.onNodeWithTag("cursor-control-overlay").assertExists()
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, reads) }
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun lateReplyCannotReopenPreviewAfterReleaseOrEditorSwitch() {
        val reply = CompletableDeferred<CursorMagnifierText?>()
        mount(reader = { reads++; reply.await() })
        hold()
        rule.runOnIdle { assertEquals(1, reads); session.value = 2L }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { reply.complete(CursorMagnifierText.from(text, 4)) }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        rule.onNodeWithTag("space-key").performTouchInput { up() }
    }

    @Test fun capsuleFitsNarrowWidthsLargeFontsAndBothThemes() {
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color.Gray).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (dark in listOf(false, true)) for (width in listOf(10, 160, 280)) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)) {
                        CursorMagnifierCapsule(CursorMagnifierText.from("甲😀 العربية 光标左右预览", 11)!!,
                            if (dark) Color(0xFF202124) else Color.White,
                            if (dark) Color.White else Color.Black, Color(0xFF4285F4), Modifier.width(width.dp))
                    }
                }
            }
        }
        rule.onAllNodesWithTag("cursor-magnifier").assertCountEquals(6)
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        File(out).mkdirs()
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out, "capsules-dark-light-narrow-large-font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
