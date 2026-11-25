package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Insets
import android.provider.Settings
import android.view.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.extractBottomInset
import com.kingzcheung.xime.service.fixedKeyboardBottomInsetPx
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.settings.SettingsSearchFocus
import com.kingzcheung.xime.ui.settings.SettingsSearchIndex
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw360dp-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardBottomEdgeRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun bottomPositionIsReachableFromSettingsSearch() {
        val context = rule.activity
        KeyboardThemes.initFromConfig(context)
        AppFonts.initialize(context)
        val title = "底部位置"
        SettingsSearchFocus.open(SettingsSearchIndex.first { it.title == title })
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {}, initialPage = "bottom_gap")
        } }
        val anchor = rule.onNodeWithTag("settings-search-anchor:$title")
        rule.waitUntil(4_000) { anchor.isDisplayed() }
        assertEquals(SettingsPreferences.KeyboardBottomPosition.AUTO, SettingsPreferences.keyboardBottomPosition(context))
        rule.onNodeWithTag("bottom-position:FLUSH").performClick()
        assertEquals(SettingsPreferences.KeyboardBottomPosition.FLUSH, SettingsPreferences.keyboardBottomPosition(context))
        assertTrue(SettingsPreferences.edgeToEdgeKeyboard(context))
        save("bottom-position-setting")
        rule.onNodeWithTag("bottom-position:KEEP_BUTTONS").performClick()
        assertEquals(SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS, SettingsPreferences.keyboardBottomPosition(context))
        assertEquals(false, SettingsPreferences.edgeToEdgeKeyboard(context))
    }

    @Test fun anOldEdgeOffUserStartsOnKeepButtons() {
        val context = rule.activity
        SettingsPreferences.setEdgeToEdgeKeyboard(context, false)
        assertEquals(SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS, SettingsPreferences.keyboardBottomPosition(context))
    }

    @Test fun eachPositionCanBeFineTunedDownToZeroAndResetGoesBackToAuto() {
        val app = rule.activity.application
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        SettingsPreferences.setKeyboardBottomPosition(app, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        SettingsPreferences.setKeyboardBottomAdjustmentDp(app, 2)
        val entry = SettingsSearchIndex.first { it.title == "底部留白校准" }
        rule.setContent {
            XimeTheme(darkTheme = false, themeId = "soft_blue") {
                LayoutDisplaySettingsContent(onBack = {}, initialPage = entry.target.substringAfter("?page="))
            }
        }
        val section = rule.onNodeWithTag("settings-search-anchor:固定键盘")
        rule.waitUntil(4_000) { section.isDisplayed() }
        val down = rule.onNodeWithText("调低")
        rule.onNodeWithText("从「留出导航按钮」的位置抬高 2 dp").assertIsDisplayed()
        down.performTouchInput { click(center) }
        rule.onNodeWithText("底部留白 0 dp").assertIsDisplayed()
        down.performTouchInput { click(center) }
        rule.onNodeWithText("从「留出导航按钮」的位置调低 2 dp").assertIsDisplayed()
        assertEquals(-2, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        // 固定的两档不受键盘量到的下限限制：一直能调到底（键盘那边到 0 为止）。
        rule.runOnIdle { SettingsPreferences.setKeyboardBottomLoweringLimitDp(app, 0) }
        down.assertIsEnabled()
        if (System.getenv("CYIME_RENDER_OUT") != null) {
            rule.mainClock.advanceTimeBy(3_000)
            rule.waitForIdle()
            save("bottom-recovery-settings")
        }
        rule.onNodeWithTag("bottom-calibration-reset").performClick()
        assertEquals(SettingsPreferences.KeyboardBottomPosition.AUTO, SettingsPreferences.keyboardBottomPosition(app))
        assertEquals(true, SettingsPreferences.edgeToEdgeKeyboard(app))
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(app))
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(null, KeyboardPanelLaunch.take())
    }

    @Test fun aShortFixedKeyboardMovesDownWithoutShrinkingKeysAndEnterStillWorks() {
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val pressed = mutableListOf<String>()
        val callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> pressed.add(key) }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(viewModel).bindT9Controller(callbacks)
        val candidates = mutableStateOf(CandidateState(inputText = "ntm",
            candidates = listOf("南天门", "你听吗", "你听么"), isComposing = true))
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build()
        val win = rule.activity.window
        Settings.Secure.putInt(app.contentResolver, "navigation_mode", 2)
        SettingsPreferences.setKeyboardBottomPosition(app, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        val normal = extractBottomInset(insets, win)
        val bottom = mutableIntStateOf(fixedKeyboardBottomInsetPx(insets, win, normal))
        val schema = mutableStateOf("rime_ice")
        rule.setContent {
            Box(Modifier.size(360.dp, 300.dp).background(Color.DarkGray).testTag("viewport")) {
                KeyboardView(viewModel, KeyboardUiState(keyboardHeightDp = 244, currentSchemaId = schema.value,
                    isDarkTheme = true), callbacks, candidateState = candidates, inputSessionManagedByHost = true,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = -bottom.intValue.dp)
                        .size(360.dp, 244.dp))
            }
        }
        val enter = rule.onNodeWithTag("qwerty-enter-key", true)
        val initial = enter.fetchSemanticsNode().boundsInRoot
        val word = rule.onNodeWithText("南天门", useUnmergedTree = true)
        val initialWord = word.fetchSemanticsNode().boundsInRoot.width
        save("bottom-automatic", "viewport")
        rule.runOnIdle {
            SettingsPreferences.setKeyboardBottomPosition(app, SettingsPreferences.KeyboardBottomPosition.AUTO)
            bottom.intValue = fixedKeyboardBottomInsetPx(insets, win, normal)
            SettingsPreferences.setCandidateTextSize(app, 14)
        }
        val lowered = enter.fetchSemanticsNode().boundsInRoot
        assertEquals(0, bottom.intValue)
        assertEquals(48f, lowered.bottom - initial.bottom, 0.5f)
        assertEquals(initial.height, lowered.height, 0.5f)
        assertTrue(word.fetchSemanticsNode().boundsInRoot.width < initialWord)
        enter.performTouchInput { click(center) }
        assertEquals(listOf("enter"), pressed)
        save("bottom-edge-14sp", "viewport")

        rule.runOnIdle {
            val more = listOf("能听吗", "你听没", "难听吗", "能听么", "你听嘛", "你太慢", "你同意吗")
            candidates.value = candidates.value.copy(expandedCandidatesLoaded = true,
                expandedCandidates = (candidates.value.candidates + more).map { RimeCandidate(it, "") })
            viewModel.setCandidatePageExpanded(true)
        }
        // The first few words remain in the strip. Later ones appear in the expanded grid.
        rule.onNodeWithTag("expanded-candidate:8", true).assertIsDisplayed()
        rule.onNodeWithTag("expanded-enter-key", true).assertIsDisplayed()
        save("bottom-expanded", "viewport")
        rule.runOnIdle { viewModel.setCandidatePageExpanded(false) }
        for ((id, bottomKey) in listOf("pinyin_14jian" to "qwerty-enter-key", "t9_pinyin" to "t9-zero-key")) {
            rule.runOnIdle {
                schema.value = id
                viewModel.dispatch(KeyboardDispatchAction.InputSessionStarted(false, id))
            }
            val bounds = rule.onNodeWithTag(bottomKey, true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.bottom <= rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot.bottom)
            save("bottom-$id", "viewport")
        }
        rule.runOnIdle { viewModel.dispatch(KeyboardDispatchAction.ShowNumber) }
        rule.onNodeWithTag("number-zero", true).assertIsDisplayed()
        save("bottom-number", "viewport")
        rule.runOnIdle { viewModel.dispatch(KeyboardDispatchAction.ShowCommonSymbol) }
        rule.waitForIdle()
        save("bottom-symbol", "viewport")
    }

    @Test fun floatingDragBarLeavesTheBottomRowTappableAtItsLowerEdge() {
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val pressed = mutableListOf<String>()
        val callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> pressed.add(key) }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(viewModel).bindT9Controller(callbacks)
        val schema = mutableStateOf("rime_ice")
        rule.setContent {
            KeyboardView(viewModel, KeyboardUiState(isFloatingMode = true, keyboardWidthDp = 320, keyboardHeightDp = 244,
                floatingOffsetY = 18, floatingMinOffsetY = 18, currentSchemaId = schema.value, isDarkTheme = true),
                callbacks, inputSessionManagedByHost = true, modifier = Modifier.size(360.dp, 400.dp).testTag("float-host"))
        }
        for ((id, tag) in listOf("rime_ice" to "qwerty-enter-key", "pinyin_14jian" to "qwerty-enter-key",
            "t9_pinyin" to "t9-zero-key")) {
            rule.runOnIdle {
                viewModel.dispatch(KeyboardDispatchAction.InputSessionStarted(false, id))
                schema.value = id
            }
            val key = rule.onNodeWithTag(tag, true)
            val bounds = key.fetchSemanticsNode().boundsInRoot
            val bar = rule.onNodeWithTag("floating-drag-bar", true).fetchSemanticsNode().boundsInRoot
            assertTrue("$id key=$bounds drag=$bar", bounds.bottom <= bar.top + 1f)
            assertDragIndicatorCentered(id, 16)
            if (tag == "qwerty-enter-key") {
                pressed.clear()
                key.performTouchInput { click(Offset(center.x, height - 2f)) }
                rule.runOnIdle { assertEquals(listOf("enter"), pressed) }
            } else {
                pressed.clear()
                val host = rule.onNodeWithTag("float-host", true)
                val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
                host.performTouchInput { click(Offset(bar.center.x - origin.x, bar.top - origin.y - 10f)) }
                rule.runOnIdle { assertEquals(listOf("space"), pressed) }
            }
        }
        save("floating-bottom-hit-areas", "float-host")
    }

    /** 平板尺寸的悬浮键盘：保留原有底栏高度，短横条与底栏中心重合。 */
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-mdpi")
    @Test fun floatingDragIndicatorSitsMidwayBelowTheKeysOnTablet() {
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(viewModel).bindT9Controller(callbacks)
        val schema = mutableStateOf("rime_ice")
        rule.setContent {
            KeyboardView(viewModel, KeyboardUiState(isFloatingMode = true, keyboardWidthDp = 560, keyboardHeightDp = 300,
                floatingOffsetY = 18, floatingMinOffsetY = 18, currentSchemaId = schema.value, isDarkTheme = true),
                callbacks, inputSessionManagedByHost = true, modifier = Modifier.size(1280.dp, 800.dp).testTag("float-host"))
        }
        for (id in listOf("rime_ice", "t9_pinyin")) {
            rule.runOnIdle {
                viewModel.dispatch(KeyboardDispatchAction.InputSessionStarted(false, id))
                schema.value = id
            }
            assertDragIndicatorCentered(id, 28)
        }
        save("floating-drag-indicator-tablet", "float-host")
    }

    /** The colored footer is a separate painted surface from the keyboard row above it. */
    private fun assertDragIndicatorCentered(id: String, expectedHeightDp: Int) {
        val footer = rule.onNodeWithTag("floating-footer", true).fetchSemanticsNode().boundsInWindow
        val indicator = rule.onNodeWithTag("floating-drag-indicator", true).fetchSemanticsNode().boundsInWindow
        val card = rule.onNodeWithTag("floating-keyboard-card", true).fetchSemanticsNode().boundsInWindow
        val hit = rule.onNodeWithTag("floating-drag-bar", true).fetchSemanticsNode().boundsInWindow
        assertTrue("$id footer includes row clearance", footer.height >= with(rule.density) { expectedHeightDp.dp.toPx() })
        val keyTag = if (id == "t9_pinyin") "t9-enter-key" else "qwerty-enter-key"
        val key = rule.onNodeWithTag(keyTag, true).fetchSemanticsNode().boundsInWindow
        assertTrue("$id lower key=$key painted footer=$footer", footer.top <= key.bottom && footer.top > key.center.y)
        assertEquals("$id footer bottom", card.bottom, footer.bottom, 1f)
        assertEquals("$id indicator centre x", card.center.x, indicator.center.x, 1f)
        assertEquals("$id indicator centre y", footer.center.y, indicator.center.y, 1f)
        assertTrue("$id drag starts below the last key", hit.top >= key.bottom - 1f && hit.top <= indicator.center.y)
        // 底色只铺键区下面那段，不和键区背景叠：玻璃时叠两层会多一条更亮的横条（11-19）。
        val paint = rule.onNodeWithTag("floating-footer-background", true).fetchSemanticsNode().boundsInWindow
        assertTrue("$id footer paint=$paint starts below the keys", paint.top >= key.bottom - 1f)
        assertEquals("$id footer paint bottom", card.bottom, paint.bottom, 1f)
    }

    private fun save(name: String, tag: String? = null) {
        val dir = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val visible = tag?.let {
            val r = rule.onNodeWithTag(it).fetchSemanticsNode().boundsInWindow
            Bitmap.createBitmap(bitmap, r.left.roundToInt(), r.top.roundToInt(), r.width.roundToInt(), r.height.roundToInt())
        } ?: bitmap
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { visible.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
