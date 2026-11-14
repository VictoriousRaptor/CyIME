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
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.QwertyBottomRowSettingsScreen
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw400dp-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QwertyBottomRowRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val pressed = mutableListOf<String>()

    @Before fun prepare() {
        SettingsPreferences.getPrefsPublic(rule.activity).edit().clear().commit()
        KeysConfigHelper.loadConfig(rule.activity)
        KeyboardThemes.initFromConfig(rule.activity)
        AppFonts.initialize(rule.activity)
    }

    private fun mount(order: String, dark: Boolean = false, width: Int = 400, split: Boolean = false) {
        KeyboardInputPreferences.read(rule.activity).copy(qwertyBottomRow = QwertyBottomRowConfig.parse(order),
            qwertyCommaKey = false, qwertyPeriodKey = false, languageKeyOnLeft = true).save(rule.activity)
        SettingsPreferences.setSplitKeyboardEnabled(rule.activity, split)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        val callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> pressed += key }, onCandidateSelect = {})
        val vm = spy(KeyboardViewModel(rule.activity.application))
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(vm).bindT9Controller(callbacks)
        vm.dispatch(KeyboardDispatchAction.InputSessionStarted(false, "rime_ice"))
        rule.setContent {
            XimeTheme(darkTheme = dark, themeId = "soft_blue") {
                Box(Modifier.size(width.dp, 330.dp)) {
                    KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", isAsciiMode = false,
                        isDarkTheme = dark, themeId = "soft_blue", keyboardHeightDp = 330), callbacks,
                        modifier = Modifier.fillMaxSize(), inputSessionManagedByHost = true)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun key(id: String) = rule.onNodeWithTag("qwerty-bottom:$id", useUnmergedTree = true)
    private fun x(id: String) = key(id).fetchSemanticsNode().boundsInRoot.left
    private fun fill(id: String, prefix: String = "qwerty-bottom"): Int {
        val bounds = rule.onNodeWithTag("$prefix:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        // Inside the key cap, away from labels/icons and corner hints.
        val color = bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + 8f).toInt())
        bitmap.recycle()
        return color
    }

    @Test fun reorderedKeysUsePositionColorsAndKeepTheirActions() {
        mount("comma,language,symbols,space,numbers,period")
        val order = listOf("comma", "language", "symbols", "space", "numbers", "period", "enter")
        assertTrue(order.zipWithNext().all { (a, b) -> x(a) < x(b) })
        assertEquals(fill("comma"), fill("language"))
        assertEquals(fill("comma"), fill("period"))
        assertEquals(fill("comma"), fill("enter"))
        assertEquals(fill("symbols"), fill("numbers"))
        assertNotEquals(fill("comma"), fill("symbols"))
        rule.onNodeWithTag("qwerty-punctuation-key").performTouchInput { click(center) }
        assertEquals("，", pressed.last())
        rule.mainClock.advanceTimeBy(1_000)
        save("reordered-light")
        rule.runOnIdle {
            KeyboardInputPreferences.read(rule.activity).copy(qwertyBottomRow =
                QwertyBottomRowConfig.parse("comma,symbols,language,space,numbers,period")).save(rule.activity)
        }
        assertEquals(fill("language"), fill("numbers"))
        assertNotEquals(fill("language"), fill("comma"))
        save("language-middle")
        rule.runOnIdle {
            KeyboardInputPreferences.read(rule.activity).copy(qwertyBottomRow = QwertyBottomRowConfig.Default).save(rule.activity)
        }
        assertEquals(fill("symbols"), fill("numbers"))
        assertNotEquals(fill("symbols"), fill("comma"))
    }

    @Test fun fourVisibleKeysAreAllSpecialInDarkTheme() {
        mount("language,space,symbols,numbers", dark = true)
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = true).save(rule.activity) }
        key("numbers").assertDoesNotExist()
        key("comma").assertDoesNotExist()
        for (id in listOf("space", "symbols", "enter")) assertEquals(fill("language"), fill(id))
        save("four-keys-dark")
        rule.runOnIdle { KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = false).save(rule.activity) }
        assertTrue(x("numbers") > x("symbols"))
    }

    @Test fun editorMovesRemovesRestoresAndResetsImmediately() {
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            QwertyBottomRowSettingsScreen(onBack = {})
        } }
        rule.onNodeWithTag("bottom-editor-key:comma").performClick()
        rule.onNodeWithContentDescription("右移").performClick()
        assertEquals("symbols,numbers,space,comma,period,language", KeyboardInputPreferences.read(rule.activity).qwertyBottomRow.encode())
        save("editor-move-dialog")
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithTag("bottom-editor-key:period").performClick()
        rule.onNodeWithContentDescription("移除").performClick()
        assertFalse(QwertyBottomKey.PERIOD in KeyboardInputPreferences.read(rule.activity).qwertyBottomRow.keys)
        rule.onNodeWithTag("bottom-editor-add").performClick()
        rule.onNodeWithTag("bottom-editor-add:period").performClick()
        assertEquals(1, KeyboardInputPreferences.read(rule.activity).qwertyBottomRow.keys.count { it == QwertyBottomKey.PERIOD })
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("恢复默认").performClick()
        rule.onNodeWithText("恢复", useUnmergedTree = true).performClick()
        assertEquals(QwertyBottomRowConfig.Default, KeyboardInputPreferences.read(rule.activity).qwertyBottomRow)
        save("editor-default")
    }

    @Test fun editorUsesDarkPositionColorsAndReflectsMergedMode() {
        KeyboardInputPreferences.read(rule.activity).copy(symbolKeyCycle = true,
            qwertyBottomRow = QwertyBottomRowConfig.parse("language,comma,space,symbols,numbers,period")).save(rule.activity)
        rule.setContent { XimeTheme(darkTheme = true, themeId = "soft_blue") {
            QwertyBottomRowSettingsScreen(onBack = {})
        } }
        rule.onNodeWithTag("bottom-editor-key:numbers").assertDoesNotExist()
        rule.onNodeWithText("?123").assertIsDisplayed()
        for (id in listOf("comma", "period", "enter")) {
            assertEquals(fill("language", "bottom-editor-key"), fill(id, "bottom-editor-key"))
        }
        assertEquals(fill("space", "bottom-editor-key"), fill("symbols", "bottom-editor-key"))
        assertNotEquals(fill("space", "bottom-editor-key"), fill("language", "bottom-editor-key"))
        save("editor-dark")
    }

    @Test @Config(qualifiers = "sw1000dp-w1000dp-h850dp-mdpi")
    fun splitExtremeOrderKeepsTheGapCenteredAndFunctionsWhole() {
        mount("symbols,numbers,comma,period,language,space", width = 1000, split = true)
        val gap = rule.onNodeWithTag("split-keyboard-gap:3").fetchSemanticsNode().boundsInRoot
        val first = key("symbols").fetchSemanticsNode().boundsInRoot
        val last = key("enter").fetchSemanticsNode().boundsInRoot
        assertEquals((first.left + last.right) / 2, gap.center.x, 1f)
        for (id in listOf("symbols", "numbers", "comma", "period", "language", "enter")) {
            val bounds = key(id).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right <= gap.left + 1 || bounds.left >= gap.right - 1)
        }
        save("split-extreme")
    }

    private fun save(name: String) {
        val output = System.getenv("CYIME_RENDER_OUT") ?: return
        rule.waitForIdle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val root = if (dialog?.isShowing == true) dialog.window!!.decorView else rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, if (name.startsWith("editor")) root.height else 330, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(output).mkdirs()
        File(output, "bottom-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
