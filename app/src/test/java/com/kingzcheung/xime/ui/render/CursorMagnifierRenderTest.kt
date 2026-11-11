package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
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
    private val text = "长按空格移动光标😀预览"
    private val session = mutableStateOf(1L)
    private lateinit var editor: android.widget.EditText
    private var missingAnchor = false
    private val editorY = mutableStateOf(260)
    private val tracking = mutableListOf<Boolean>()

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
        enabled: Boolean = true, width: Float = 280f,
        reader: suspend () -> CursorMagnifierText? = { reads++; CursorMagnifierText.from(text, caret) }) {
        val app = rule.activity.application
        KeyboardInputPreferences.read(app).copy(cursorGesture = CursorGestureMode.SPACE,
            spaceHold = SpaceHoldAction.CURSOR, cursorHoldSeconds = 0.2f, cursorStepDp = 10f,
            cursorMagnifierEnabled = enabled, cursorMagnifierWidthDp = width).save(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        val callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> keys += key }, onCandidateSelect = {},
            onCursorMove = { moves++; caret = (caret + it).coerceIn(0, text.length); editor.setSelection(caret) },
            readCursorMagnifierText = reader,
            readCursorMagnifierAnchor = { if (missingAnchor) null else internalCursorMagnifierAnchor(editor, caret) },
            onCursorMagnifierTracking = { _, active -> tracking += active })
        val vm = spy(KeyboardViewModel(app))
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
                            it.setSingleLine(true)
                            it.textSize = 18f
                            it.setTextColor(android.graphics.Color.BLACK)
                            it.setText(text)
                            it.setSelection(caret)
                        }
                    }, modifier = Modifier.align(Alignment.TopStart).offset(x = 80.dp, y = editorY.value.dp).size(300.dp, 64.dp))
                    Box(Modifier.size(400.dp, 330.dp)) {
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = schema, inputSessionId = session.value,
                            keyboardHeightDp = 330, isFloatingMode = floating, floatingScreenHeightDp = 850),
                            callbacks, modifier = Modifier.fillMaxSize(), inputSessionManagedByHost = true)
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

    @Test fun persistedWidthAndSwitchApplyLiveAndWidthIsLimitedByScreenInsteadOfFloatingKeyboard() {
        mount(floating = true, width = 160f)
        hold()
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(160.dp)
        shot("width-160")
        rule.runOnIdle {
            KeyboardInputPreferences.read(rule.activity).copy(cursorMagnifierWidthDp = 480f).save(rule.activity)
        }
        rule.mainClock.advanceTimeBy(160)
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(384.dp)
        shot("width-480-screen-limited")
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(cursorMagnifierEnabled = false).save(rule.activity) }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("cursor-magnifier").assertDoesNotExist()
        val stopped = reads
        rule.mainClock.advanceTimeBy(320)
        rule.runOnIdle {
            assertEquals(stopped, reads)
            assertEquals(listOf(true, false), tracking)
            assertEquals(480f, KeyboardInputPreferences.read(rule.activity).cursorMagnifierWidthDp, 0f)
            KeyboardInputPreferences.read(rule.activity).copy(cursorMagnifierEnabled = true).save(rule.activity)
        }
        rule.mainClock.advanceTimeBy(160)
        popupLocation()
        rule.onNodeWithTag("cursor-magnifier").assertWidthIsEqualTo(384.dp)
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
                for (dark in listOf(false, true)) for (width in listOf(160, 280)) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)) {
                        CursorMagnifierCapsule(CursorMagnifierText.from("甲😀 العربية 光标左右预览", 11)!!,
                            if (dark) Color(0xFF202124) else Color.White,
                            if (dark) Color.White else Color.Black, Color(0xFF4285F4), Modifier.width(width.dp))
                    }
                }
            }
        }
        rule.onAllNodesWithTag("cursor-magnifier").assertCountEquals(4)
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        File(out).mkdirs()
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out, "capsules-dark-light-narrow-large-font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
