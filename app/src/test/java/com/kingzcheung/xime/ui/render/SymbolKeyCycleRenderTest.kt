package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.QwertyBottomRowConfig
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
    private lateinit var viewModel: MutableState<KeyboardViewModel>
    private val vm get() = viewModel.value
    private lateinit var callbacks: KeyboardCallbacks
    private val pressed = mutableListOf<String>()

    @Before fun prepare() {
        val app = rule.activity.application
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
    }

    private fun mount(schema: String = "rime_ice", english: Boolean = false, cycle: Boolean = true,
        handwriting: Boolean = false, width: Int = 400, split: Boolean = false, floating: Boolean = false, languageOnLeft: Boolean = false, languageMenu: Boolean = false,
        onGestureAction: ((GestureAction, String) -> Unit)? = null) {
        val app = rule.activity.application
        KeyboardInputPreferences.read(app).copy(symbolKeyCycle = cycle, languageKeyOnLeft = languageOnLeft,
            qwertyBottomRow = if (languageOnLeft) QwertyBottomRowConfig.parse("symbols,numbers,language,comma,space,period") else QwertyBottomRowConfig.Default).save(app)
        SettingsPreferences.setSplitKeyboardEnabled(app, split)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> pressed += key }, onCandidateSelect = {},
            onSwitchSchema = if (languageMenu) ({ _: String -> }) else null, onGestureAction = onGestureAction)
        viewModel = mutableStateOf(newViewModel(schema, english))
        rule.setContent {
            XimeTheme(darkTheme = false, themeId = "soft_blue") {
                Box(Modifier.size(width.dp, 330.dp).testTag("keyboard-render-area")) {
                    val model = vm
                    key(model) {
                        KeyboardView(model, KeyboardUiState(currentSchemaId = schema, isAsciiMode = english,
                            schemas = if (languageMenu) listOf(com.kingzcheung.xime.settings.SchemaInfo(schema, "拼音", "", "", "")) else emptyList(),
                            keyboardHeightDp = 330, isFloatingMode = floating, floatingScreenHeightDp = 850),
                            callbacks, modifier = Modifier.fillMaxSize(), inputSessionManagedByHost = true)
                    }
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

    private fun newViewModel(schema: String, english: Boolean): KeyboardViewModel {
        val model = spy(KeyboardViewModel(rule.activity.application))
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(model).bindT9Controller(callbacks)
        model.dispatch(KeyboardDispatchAction.InputSessionStarted(english, schema))
        return model
    }

    private fun assertKeyLabel(tag: String, label: String) {
        rule.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(label))).assertIsDisplayed()
    }

    private fun singleKey() {
        assertKeyLabel("mode-slot-1", "?123")
        rule.onNodeWithTag("mode-slot-2").assertDoesNotExist()
    }

    // The merged tree excludes the keyboard that remains composed behind the symbol overlay.
    private fun tapKey(tag: String) = rule.onNodeWithTag(tag).performTouchInput { click(center) }
    private fun tapMode(slot: Int = 1) = tapKey("mode-slot-$slot")

    private fun assertSymbolNavigation(label: String) {
        assertEquals(OverlayRoute.Symbol, (vm.page.value as KeyboardPage.Overlay).route)
        assertKeyLabel("mode-slot-1", label)
        assertKeyLabel("mode-slot-2", "123")
    }

    private fun assertNumberNavigation(label: String, kana: Boolean = false) {
        assertEquals(PanelType.NUMBER, (vm.page.value as KeyboardPage.Panel).type)
        if (kana) {
            assertKeyLabel("kana-symbol", "!@#")
            assertKeyLabel("kana-number", label)
            rule.onNodeWithTag("number-comma-key").assertDoesNotExist()
        } else {
            assertKeyLabel("mode-slot-1", "!@#")
            assertKeyLabel("mode-slot-2", label)
        }
    }

    private fun verifyNavigation(label: String, name: String, kana: Boolean = false) {
        val originalPage = vm.page.value
        val originalLayout = vm.keyboardState.value
        singleKey()
        save("$name-text")
        tapMode()
        assertSymbolNavigation(label)
        save("$name-symbols")
        tapMode(2)
        assertNumberNavigation(label, kana)
        if (!kana) assertZeroMatchesMiddleDigit()
        save("$name-numbers")
        if (kana) tapKey("kana-symbol") else tapMode()
        assertSymbolNavigation(label)
        tapMode(2)
        if (kana) tapKey("kana-number") else tapMode(2)
        assertEquals(originalPage, vm.page.value)
        assertEquals(originalLayout, vm.keyboardState.value)
        assertTrue("Mode commands must not be sent as literal input", pressed.isEmpty())
    }

    @Test fun chineseReturnsTo26Keys() { mount(); verifyNavigation("中文", "qwerty") }
    @Test @Config(qualifiers = "sw360dp-w360dp-h800dp-mdpi")
    fun compactPhoneRetainsBothSecondaryNavigationKeys() { mount(width = 360); verifyNavigation("中文", "compact") }
    @Test fun englishReturnsToEnglish() { mount(english = true); verifyNavigation("ABC", "english") }
    @Test fun mergedLettersReturnTo14Keys() { mount("pinyin_14jian"); verifyNavigation("中文", "fourteen") }
    @Test fun nineKeyReturnsToNineKeys() { mount("t9_pinyin"); verifyNavigation("中文", "t9") }
    @Test fun strokeReturnsToStroke() { mount("stroke"); verifyNavigation("中文", "stroke") }
    @Test fun kanaKeepsItsNumericKeypadNavigation() { mount("japanese_kana"); verifyNavigation("あいう", "kana", kana = true) }
    @Test fun kanaCycleModeKeyPreservesLongHoldCtrlEnter() { verifyKanaCtrlEnter(cycle = true) }
    @Test fun kanaSeparateSymbolKeyPreservesLongHoldCtrlEnter() { verifyKanaCtrlEnter(cycle = false) }

    private fun verifyKanaCtrlEnter(cycle: Boolean) {
        val commands = mutableListOf<String>()
        mount("japanese_kana", cycle = cycle, onGestureAction = { action, value ->
            if (action == GestureAction.COMMAND) commands += value
        })
        if (cycle) {
            tapMode()
            tapMode(2)
            tapKey("kana-number")
        }
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
        assertEquals("Releasing Ctrl must leave the text page visible", originalPage, vm.page.value)
        tapKey(modeTag)
        if (cycle) assertNumberNavigation("あいう", kana = true) else assertSymbolNavigation("あいう")
    }

    @Test fun handwritingReturnsToCanvas() { mount(handwriting = true); verifyNavigation("中文", "handwriting") }
    @Test fun handwritingModeKeyCanBeActivatedThroughAccessibility() {
        mount(handwriting = true)
        rule.onNodeWithTag("mode-slot-1").performClick()
        assertEquals(OverlayRoute.Symbol, (vm.page.value as KeyboardPage.Overlay).route)
        rule.onNodeWithTag("mode-slot-2").performClick()
        assertEquals(PanelType.NUMBER, (vm.page.value as KeyboardPage.Panel).type)
        rule.onNodeWithTag("mode-slot-2").performClick()
        assertEquals(KeyboardPage.Main(MainType.HANDWRITING), vm.page.value)
        assertTrue(pressed.isEmpty())
    }
    @Test fun floatingKeyboardReturnsToText() { mount(floating = true); verifyNavigation("中文", "floating") }
    @Test @Config(qualifiers = "sw1000dp-w1000dp-h850dp-mdpi")
    fun splitKeyboardReturnsToSplitLetters() { mount(width = 1000, split = true); verifyNavigation("中文", "split") }

    @Test fun mergedKeyRemembersBothReturnPagesWhenKeyboardIsRecreated() {
        mount()
        assertEquals(KeyboardInputPage.SYMBOLS, KeyboardInputPreferences.lastSymbolOrNumberPage(rule.activity))
        tapMode()
        assertSymbolNavigation("中文")
        tapMode(2)
        assertNumberNavigation("中文")
        assertEquals(KeyboardInputPage.SYMBOLS, KeyboardInputPreferences.lastSymbolOrNumberPage(rule.activity))
        tapMode(2)
        assertEquals(KeyboardInputPage.NUMBERS, KeyboardInputPreferences.lastSymbolOrNumberPage(rule.activity))
        rule.runOnIdle { viewModel.value = newViewModel("rime_ice", false) }
        singleKey()
        tapMode()
        assertNumberNavigation("中文")
        tapMode()
        assertSymbolNavigation("中文")
        assertEquals(KeyboardInputPage.NUMBERS, KeyboardInputPreferences.lastSymbolOrNumberPage(rule.activity))
        tapMode()
        assertEquals(KeyboardInputPage.SYMBOLS, KeyboardInputPreferences.lastSymbolOrNumberPage(rule.activity))
        rule.runOnIdle { viewModel.value = newViewModel("rime_ice", false) }
        singleKey()
        tapMode()
        assertSymbolNavigation("中文")
        assertTrue(pressed.isEmpty())
    }

    @Test fun changingPreferenceKeepsBothSecondaryKeysAndOnlyAddsCommaToTheNumberPage() {
        mount()
        tapMode()
        assertSymbolNavigation("中文")
        val symbolKeys = (1..2).map { bounds("mode-slot-$it") }
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = false).save(rule.activity) }
        assertSymbolNavigation("中文")
        assertEquals(symbolKeys, (1..2).map { bounds("mode-slot-$it") })
        tapMode(2)
        assertNumberNavigation("中文")
        rule.onNodeWithTag("number-comma-key").assertDoesNotExist()
        val separateModeWidth = bounds("mode-slot-1").width
        val dot = bounds("number-dot-key")
        val enter = bounds("number-enter-key")
        save("numbers-separate")
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = true).save(rule.activity) }
        assertNumberNavigation("中文")
        assertMergedNumberFooter(first = 1, second = 2)
        assertTrue(bounds("mode-slot-1").width < separateModeWidth)
        assertTrue(bounds("mode-slot-2").width < separateModeWidth)
        assertTrue(bounds("number-dot-key").width < dot.width)
        val mergedEnter = bounds("number-enter-key")
        assertEquals(enter.right, mergedEnter.right, 1f)
        assertTrue(mergedEnter.width > enter.width)
        tapMode(2)
        singleKey()
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = false).save(rule.activity) }
        assertKeyLabel("mode-slot-1", "!@#")
        assertKeyLabel("mode-slot-2", "123")
        save("disabled-two-keys")
    }

    @Test fun swappedSecondaryKeysKeepTheirActionsAndTheCommaStaysBetweenThem() {
        mount()
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(swapModeKeys = true).save(rule.activity) }
        singleKey()
        tapMode()
        assertSymbolNavigation("中文")
        assertTrue(bounds("mode-slot-2").right <= bounds("mode-slot-1").left + 1f)
        save("symbols-swapped")
        tapMode(2)
        assertNumberNavigation("中文")
        assertMergedNumberFooter(first = 2, second = 1)
        save("numbers-swapped")
        tapMode(2)
        assertTrue(vm.page.value is KeyboardPage.Main)
        tapMode()
        assertNumberNavigation("中文")
    }

    @Test fun numberPadHasAtOnTheFirstRowAndDeleteOnTheThirdRow() {
        mount()
        tapMode()
        tapMode(2)
        assertEquals(bounds("number-digit:3").center.y, bounds("number-at-key").center.y, 1f)
        assertEquals(bounds("number-digit:9").center.y, bounds("number-delete-key").center.y, 1f)
        tapKey("number-at-key")
        tapKey("number-delete-key")
        val delete = rule.onNodeWithTag("number-delete-key")
        delete.performTouchInput { swipe(center, center + Offset(0f, 90.dp.toPx()), durationMillis = 180) }
        delete.performTouchInput { swipe(center, center - Offset(0f, 90.dp.toPx()), durationMillis = 180) }
        assertEquals(listOf("@", "delete", "clear_all", "undo_clear"), pressed)
    }

    @Test fun addedCommaAndSmallerNumberKeysKeepTheirLiteralAndGestureActions() {
        mount()
        tapMode()
        tapMode(2)
        assertKeyLabel("number-comma-key", ",")
        tapKey("number-comma-key")
        tapKey("number-zero")
        tapKey("number-dot-key")
        rule.onNodeWithTag("number-dot-key", true).performTouchInput {
            swipe(center, center - Offset(0f, 72f), durationMillis = 180)
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("number-dot-key", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithTag("number-dot-key", true).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        tapKey("number-enter-key")
        assertEquals(listOf(",", "0", ".", ":", ":", "enter"), pressed)
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertZeroMatchesMiddleDigit() {
        val digit = bounds("number-digit:5")
        val zero = bounds("number-zero")
        assertEquals(digit.center.x, zero.center.x, 1f)
        assertEquals(digit.width, zero.width, 1f)
    }

    private fun assertMergedNumberFooter(first: Int, second: Int) {
        val comma = bounds("number-comma-key")
        assertTrue(bounds("mode-slot-$first").right <= comma.left + 1f)
        assertTrue(comma.right <= bounds("mode-slot-$second").left + 1f)
        assertZeroMatchesMiddleDigit()

        val root = rule.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(image)) }
        fun fill(tag: String) = bounds(tag).let { image.getPixel(it.center.x.toInt(), (it.top + 8f).toInt()) }
        val special = fill("number-enter-key")
        val regular = fill("number-digit:5")
        assertNotEquals(special, regular)
        for (tag in listOf("mode-slot-$first", "number-comma-key")) assertEquals(tag, special, fill(tag))
        for (tag in listOf("mode-slot-$second", "number-zero", "number-dot-key")) assertEquals(tag, regular, fill(tag))
        image.recycle()
    }

    @Test fun settingIsSearchableAndTheWholeRowTogglesPersistedChoice() {
        val title = "合并符号和数字键"
        val entry = SettingsSearchIndex.single { it.title == title }
        SettingsSearchFocus.open(entry)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {})
        } }
        val row = rule.onNodeWithTag("settings-search-anchor:$title")
        rule.waitUntil(4_000) { SettingsSearchFocus.request == null && row.isDisplayed() }
        row.performScrollTo()
        rule.waitForIdle()
        assertFalse(KeyboardInputPreferences.read(rule.activity).symbolKeyCycle)
        row.performClick()
        assertTrue(KeyboardInputPreferences.read(rule.activity).symbolKeyCycle)
        save("setting-enabled")
        row.performClick()
        assertFalse(KeyboardInputPreferences.read(rule.activity).symbolKeyCycle)
    }

    private fun assertLanguageBesideModeKey(modeTag: String? = null) {
        val lastModeTag = modeTag ?: if (KeyboardInputPreferences.read(rule.activity).symbolKeyCycle) "mode-slot-1" else "mode-slot-2"
        val mode = rule.onNodeWithTag(lastModeTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val language = rule.onNodeWithTag("language-key-control").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Language key must be to the right of the last mode key", language.left >= mode.right - 1f)
        assertTrue("No other key may sit between mode and language", language.left - mode.right < 16f)
        assertEquals("Language and mode keys must share the bottom edge", mode.bottom, language.bottom, 2f)
    }

    private fun verifyLeftLanguage(name: String, modeTag: String? = null) {
        assertLanguageBesideModeKey(modeTag)
        save("language-left-$name")
        rule.onNodeWithTag("language-key-control").performTouchInput { click(center) }
        assertEquals(listOf("ime_switch"), pressed)
    }

    @Test fun languageMovesImmediatelyAfterSeparateNumberKey() {
        mount(cycle = false, languageOnLeft = true)
        assertLanguageBesideModeKey()
        val language = rule.onNodeWithTag("language-key-control").fetchSemanticsNode().boundsInRoot
        val number = rule.onNodeWithTag("mode-slot-2").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(number.right <= language.left + 1f)
        verifyLeftLanguage("two-mode-keys")
    }

    @Test fun relocatedLanguagePreservesLongPressMenu() {
        mount(languageOnLeft = true, languageMenu = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("language-key-control").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("language-menu").assertIsDisplayed()
        rule.onNodeWithTag("language-key-control").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        assertTrue(pressed.isEmpty())
        save("language-left-menu")
    }

    @Test fun customLanguagePositionChangesImmediatelyAndIgnoresLegacyPosition() {
        mount(cycle = false)
        val original = rule.onNodeWithTag("language-key-control").fetchSemanticsNode().boundsInRoot
        repeat(2) {
            rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(languageKeyOnLeft = true).save(rule.activity) }
            assertEquals(original, rule.onNodeWithTag("language-key-control").fetchSemanticsNode().boundsInRoot)
            rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(qwertyBottomRow = QwertyBottomRowConfig.parse("symbols,numbers,language,comma,space,period")).save(rule.activity) }
            assertLanguageBesideModeKey()
            rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(languageKeyOnLeft = false, qwertyBottomRow = QwertyBottomRowConfig.Default).save(rule.activity) }
            assertEquals(original, rule.onNodeWithTag("language-key-control").fetchSemanticsNode().boundsInRoot)
        }
        save("language-default-restored")
    }

    @Test fun leftLanguageWorksWithModeCycle() {
        mount(languageOnLeft = true)
        assertLanguageBesideModeKey()
        verifyNavigation("中文", "left-language")
        verifyLeftLanguage("qwerty")
    }
    @Test fun leftLanguageWorksInEnglish() { mount(english = true, languageOnLeft = true); verifyLeftLanguage("english") }
    @Test fun leftLanguageWorksWith14Keys() { mount("pinyin_14jian", languageOnLeft = true); verifyLeftLanguage("fourteen") }
    @Test fun leftLanguageWorksWithNineKeys() { mount("t9_pinyin", cycle = false, languageOnLeft = true); verifyLeftLanguage("t9") }
    @Test fun leftLanguageWorksWithStroke() { mount("stroke", cycle = false, languageOnLeft = true); verifyLeftLanguage("stroke") }
    @Test fun leftLanguageWorksWithKana() { mount("japanese_kana", cycle = false, languageOnLeft = true); verifyLeftLanguage("kana", "kana-number") }
    @Test fun leftLanguageWorksWithHandwriting() { mount(handwriting = true, cycle = false, languageOnLeft = true); verifyLeftLanguage("handwriting", "handwriting-key:number") }
    @Test fun leftLanguageWorksWithMergedHandwritingModeKey() { mount(handwriting = true, languageOnLeft = true); verifyLeftLanguage("handwriting-merged") }
    @Test fun leftLanguageWorksWithMergedKanaModeKey() { mount("japanese_kana", languageOnLeft = true); verifyLeftLanguage("kana-merged") }
    @Test fun leftLanguageWorksWhenFloating() { mount(floating = true, languageOnLeft = true); verifyLeftLanguage("floating") }
    @Test @Config(qualifiers = "sw1000dp-w1000dp-h850dp-mdpi")
    fun leftLanguagePreservesSplitGapAcrossBothModeStyles() {
        mount(width = 1000, split = true)
        for (cycle in listOf(true, false)) {
            rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = cycle, languageKeyOnLeft = false, qwertyBottomRow = QwertyBottomRowConfig.Default).save(rule.activity) }
            val original = rule.onNodeWithTag("split-keyboard-gap:3").fetchSemanticsNode().boundsInRoot
            rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(qwertyBottomRow = QwertyBottomRowConfig.parse("symbols,numbers,language,comma,space,period")).save(rule.activity) }
            val moved = rule.onNodeWithTag("split-keyboard-gap:3").fetchSemanticsNode().boundsInRoot
            assertEquals("Moving language must preserve the split gap", original.left, moved.left, 1f)
            assertEquals(original.right, moved.right, 1f)
            assertLanguageBesideModeKey()
            save("language-left-split-cycle-$cycle")
        }
        verifyLeftLanguage("split")
    }
    @Test @Config(qualifiers = "sw360dp-w360dp-h800dp-mdpi")
    fun leftLanguageFitsCompactPhone() { mount(width = 360, languageOnLeft = true); verifyLeftLanguage("compact") }

    @Test fun languagePositionSettingIsSearchableAndPersists() {
        val title = "其他布局语言键放左侧"
        SettingsSearchFocus.open(SettingsSearchIndex.single { it.title == title })
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {})
        } }
        val row = rule.onNodeWithTag("settings-search-anchor:$title")
        rule.waitUntil(4_000) { SettingsSearchFocus.request == null && row.isDisplayed() }
        row.performScrollTo()
        rule.waitForIdle()
        assertFalse(KeyboardInputPreferences.read(rule.activity).languageKeyOnLeft)
        row.performClick()
        assertTrue(KeyboardInputPreferences.read(rule.activity).languageKeyOnLeft)
        save("language-left-setting")
        row.performClick()
        assertFalse(KeyboardInputPreferences.read(rule.activity).languageKeyOnLeft)
    }

    private fun save(name: String) {
        val dir = System.getenv("CYIME_RENDER_OUT") ?: return
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val screenshot = if (::viewModel.isInitialized) {
            val frame = rule.onNodeWithTag("keyboard-render-area").fetchSemanticsNode().boundsInWindow
            Bitmap.createBitmap(bitmap, frame.left.toInt(), frame.top.toInt(), frame.width.toInt(), frame.height.toInt())
        } else bitmap
        File(dir).mkdirs()
        File(dir, "cycle-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (screenshot !== bitmap) screenshot.recycle()
        bitmap.recycle()
    }
}
