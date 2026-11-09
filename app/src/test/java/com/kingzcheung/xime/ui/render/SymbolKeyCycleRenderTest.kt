package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.settings.SettingsSearchFocus
import com.kingzcheung.xime.ui.settings.SettingsSearchIndex
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Touch the production keyboard and persisted settings, including return destinations. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw400dp-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolKeyCycleRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var vm: KeyboardViewModel
    private val pressed = mutableListOf<String>()

    @Before fun prepare() {
        val app = rule.activity.application
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
    }

    private fun mount(schema: String = "rime_ice", english: Boolean = false, cycle: Boolean = true,
        handwriting: Boolean = false, width: Int = 400, split: Boolean = false, floating: Boolean = false,
        onGestureAction: ((GestureAction, String) -> Unit)? = null) {
        val app = rule.activity.application
        KeyboardInputPreferences.read(app).copy(symbolKeyCycle = cycle).save(app)
        SettingsPreferences.setSplitKeyboardEnabled(app, split)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        val callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> pressed += key }, onCandidateSelect = {},
            onGestureAction = onGestureAction)
        vm = spy(KeyboardViewModel(app))
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(vm).bindT9Controller(callbacks)
        vm.dispatch(KeyboardDispatchAction.InputSessionStarted(english, schema))
        rule.setContent {
            XimeTheme(darkTheme = false, themeId = "soft_blue") {
                Box(Modifier.size(width.dp, 330.dp)) {
                    KeyboardView(vm, KeyboardUiState(currentSchemaId = schema, isAsciiMode = english,
                        keyboardHeightDp = 330, isFloatingMode = floating, floatingScreenHeightDp = 850),
                        callbacks, modifier = Modifier.fillMaxSize(), inputSessionManagedByHost = true)
                }
            }
        }
        rule.waitForIdle()
        // Enter handwriting after the initial host ASCII synchronization, as a user would.
        if (handwriting) {
            rule.runOnIdle { vm.switchMain(MainType.HANDWRITING) }
            rule.onNodeWithTag("handwriting-bottom-row").assertIsDisplayed()
            assertEquals(KeyboardPage.Main(MainType.HANDWRITING), vm.page.value)
        }
        if (split) rule.onNodeWithTag("split-keyboard").assertIsDisplayed()
        if (floating) rule.onNodeWithTag("floating-keyboard-card").assertIsDisplayed()
    }

    private fun singleKey(label: String) {
        rule.onNode(hasTestTag("mode-slot-1") and hasAnyDescendant(hasText(label))).assertIsDisplayed()
        rule.onNodeWithTag("mode-slot-2").assertDoesNotExist()
    }

    private fun tapMode() = rule.onNodeWithTag("mode-slot-1").performTouchInput { click(center) }

    private fun verifyCycle(label: String, name: String) {
        val originalPage = vm.page.value
        val originalLayout = vm.keyboardState.value
        repeat(2) { turn ->
            singleKey("?123")
            if (turn == 0) save("$name-text")
            tapMode()
            singleKey("123")
            assertEquals(OverlayRoute.Symbol, (vm.page.value as KeyboardPage.Overlay).route)
            if (turn == 0) save("$name-symbols")
            tapMode()
            singleKey(label)
            assertEquals(PanelType.NUMBER, (vm.page.value as KeyboardPage.Panel).type)
            if (turn == 0) save("$name-numbers")
            tapMode()
            assertEquals(originalPage, vm.page.value)
            assertEquals(originalLayout, vm.keyboardState.value)
            assertTrue("Mode commands must not be sent as literal input", pressed.isEmpty())
        }
    }

    @Test fun chineseCyclesBackTo26Keys() { mount(); verifyCycle("中文", "qwerty") }
    @Test @Config(qualifiers = "sw360dp-w360dp-h800dp-mdpi")
    fun compactPhoneCyclesWithoutLosingTheModeKey() { mount(width = 360); verifyCycle("中文", "compact") }
    @Test fun englishCyclesBackToEnglish() { mount(english = true); verifyCycle("ABC", "english") }
    @Test fun mergedLettersCycleBackTo14Keys() { mount("pinyin_14jian"); verifyCycle("中文", "fourteen") }
    @Test fun nineKeyCyclesBackToNineKeys() { mount("t9_pinyin"); verifyCycle("中文", "t9") }
    @Test fun strokeCyclesBackToStroke() { mount("stroke"); verifyCycle("中文", "stroke") }
    @Test fun kanaCyclesThroughItsNumericKeypad() { mount("japanese_kana"); verifyCycle("あいう", "kana") }
    @Test fun kanaCycleModeKeyPreservesLongHoldCtrlEnter() { verifyKanaCtrlEnter(cycle = true) }
    @Test fun kanaSeparateSymbolKeyPreservesLongHoldCtrlEnter() { verifyKanaCtrlEnter(cycle = false) }

    private fun verifyKanaCtrlEnter(cycle: Boolean) {
        val commands = mutableListOf<String>()
        mount("japanese_kana", cycle = cycle, onGestureAction = { action, value ->
            if (action == GestureAction.COMMAND) commands += value
        })
        val originalPage = vm.page.value
        val modeTag = if (cycle) "mode-slot-1" else "kana-symbol"
        val symbol = rule.onNodeWithTag(modeTag, true).fetchSemanticsNode().boundsInRoot.center
        val enter = rule.onNodeWithTag("kana-enter", true).fetchSemanticsNode().boundsInRoot.center
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(0, symbol) }
        rule.mainClock.advanceTimeBy(1_200)
        rule.onRoot().performTouchInput { down(1, enter); up(1) }
        rule.onRoot().performTouchInput { up(0) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND), commands)
        assertTrue("Ctrl+Enter must not commit a plain Enter", pressed.isEmpty())
        assertEquals("Releasing Ctrl must not open symbols", originalPage, vm.page.value)
        rule.onNodeWithTag(modeTag, true).performTouchInput { click(center) }
        assertEquals(OverlayRoute.Symbol, (vm.page.value as KeyboardPage.Overlay).route)
    }

    @Test fun handwritingCyclesBackToCanvas() { mount(handwriting = true); verifyCycle("中文", "handwriting") }
    @Test fun handwritingCycleCanBeActivatedThroughAccessibility() {
        mount(handwriting = true)
        rule.onNodeWithTag("mode-slot-1").performClick()
        assertEquals(OverlayRoute.Symbol, (vm.page.value as KeyboardPage.Overlay).route)
        rule.onNodeWithTag("mode-slot-1").performClick()
        assertEquals(PanelType.NUMBER, (vm.page.value as KeyboardPage.Panel).type)
        rule.onNodeWithTag("mode-slot-1").performClick()
        assertEquals(KeyboardPage.Main(MainType.HANDWRITING), vm.page.value)
        assertTrue(pressed.isEmpty())
    }
    @Test fun floatingKeyboardCyclesBackToText() { mount(floating = true); verifyCycle("中文", "floating") }
    @Test @Config(qualifiers = "sw1000dp-w1000dp-h850dp-mdpi")
    fun splitKeyboardCyclesBackToSplitLetters() { mount(width = 1000, split = true); verifyCycle("中文", "split") }

    @Test fun changingPreferenceOnSymbolsAndNumbersTakesEffectImmediately() {
        mount()
        tapMode()
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = false).save(rule.activity) }
        rule.onNodeWithTag("mode-slot-2").assertIsDisplayed()
        rule.onNodeWithTag("mode-slot-2").performTouchInput { click(center) }
        rule.onNodeWithTag("mode-slot-2").assertIsDisplayed()
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = true).save(rule.activity) }
        singleKey("中文")
        tapMode()
        singleKey("?123")
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = false).save(rule.activity) }
        rule.onNodeWithTag("mode-slot-2").assertIsDisplayed()
        rule.onNode(hasTestTag("mode-slot-1") and hasAnyDescendant(hasText("!@#"))).assertIsDisplayed()
        save("disabled-two-keys")
    }

    @Test fun settingIsSearchableAndTheWholeRowTogglesPersistedChoice() {
        val title = "符号键循环切换"
        val entry = SettingsSearchIndex.single { it.title == title }
        SettingsSearchFocus.open(entry)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {})
        } }
        val row = rule.onNodeWithTag("settings-search-anchor:$title")
        rule.waitUntil(4_000) { row.isDisplayed() }
        assertFalse(KeyboardInputPreferences.read(rule.activity).symbolKeyCycle)
        row.performClick()
        assertTrue(KeyboardInputPreferences.read(rule.activity).symbolKeyCycle)
        save("setting-enabled")
        row.performClick()
        assertFalse(KeyboardInputPreferences.read(rule.activity).symbolKeyCycle)
    }

    private fun save(name: String) {
        val dir = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(dir).mkdirs()
        File(dir, "cycle-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
