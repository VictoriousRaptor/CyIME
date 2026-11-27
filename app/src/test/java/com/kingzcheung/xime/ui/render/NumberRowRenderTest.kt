package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayout
import com.kingzcheung.xime.ui.keyboard.LocalKeyCornerRadius
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.legibleForTheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.ShiftMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「数字行」：打开后数字行在字母上方、和 q 行同一列宽，分体时中缝和字母行对齐；关着时键盘和原来一样。
 * 设了 CYIME_RENDER_OUT 时另出一张对比图：行是 26 键、14 键、英文 26 键、26 键分体，
 * 列是手机 360x232 关/开、平板 800x320 关/开。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberRowRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val layouts = listOf("26", "14", "en26", "26-split")
    private val columns = listOf(Triple(360, 232, false), Triple(360, 232, true), Triple(800, 320, false), Triple(800, 320, true))

    private var layout by mutableStateOf("26")
    private var size by mutableStateOf(360 to 232)
    private var numberRow by mutableStateOf(false)
    private var numberRowFullHeight by mutableStateOf(false)
    private var passwordNumberRowField by mutableStateOf(false)
    private var passwordAutoNumberRow by mutableStateOf(true)
    private var revision by mutableStateOf(0)
    private var theme by mutableStateOf("soft_blue")
    private var dark by mutableStateOf(false)
    private val committed = mutableListOf<String>()
    private lateinit var vm: KeyboardViewModel
    private lateinit var app: Application

    private fun schemaFor(layout: String) = if (layout == "14") "pinyin_14jian" else "rime_ice"

    private fun show() {
        app = ApplicationProvider.getApplicationContext()
        KeysConfigHelper.loadConfig(app); KeyboardThemes.initFromConfig(app)
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(app)
        vm = KeyboardViewModel(app)
        committed.clear()
        rule.setContent {
            @Suppress("UNUSED_EXPRESSION") revision
            val palette = resolveKeyboardPalette(theme, dark)
            val glass = FrostedGlassConfig.defaults(dark, enabled = false).legibleForTheme(theme, dark)
            CompositionLocalProvider(LocalKeyboardPalette provides palette,
                LocalKeyCornerRadius provides KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = glass,
                    numberRow = numberRow,
                    numberRowFullHeight = numberRowFullHeight,
                    passwordAutoNumberRow = passwordAutoNumberRow,
                    splitKeyboardEnabled = layout == "26-split")) {
                MaterialTheme {
                    Box(Modifier.requiredSize(800.dp, 320.dp).testTag("number-row-canvas").background(Color(0xFF808080))) {
                        Box(Modifier.requiredSize(size.first.dp, size.second.dp)
                            .keyboardBackground(KeyboardThemes.getRenderingScheme(theme).keyboardBackground, dark,
                                palette.background, glass)) {
                            val ascii = layout == "en26"
                            KeyboardLayout(
                                onKeyPress = {},
                                viewModel = vm,
                                callbacks = KeyboardCallbacks(
                                    onKeyPress = { _, _ -> },
                                    onCommitText = { committed += it },
                                    onCandidateSelect = {},
                                ),
                                uiState = KeyboardUiState(
                                    currentSchemaId = schemaFor(layout),
                                    themeId = theme,
                                    isDarkTheme = dark,
                                    isAsciiMode = ascii,
                                    passwordNumberRowField = passwordNumberRowField,
                                ),
                                isAsciiMode = ascii,
                                modifier = Modifier.requiredSize(size.first.dp, size.second.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun select(
        l: String,
        w: Int,
        h: Int,
        on: Boolean,
        passwordField: Boolean = false,
        autoNumberRow: Boolean = true,
        fullHeight: Boolean = false,
    ) {
        val s = schemaFor(l)
        KeysConfigHelper.setActiveKeyboardSchema(s)
        // 输入法里由 KeyboardInputPreferences.read 跟着开关设；这里直接给出偏好，同步设上，第一排角标才会换。
        KeysConfigHelper.numberRowCorners = on
        rule.runOnIdle {
            vm.resetKeyboard(l == "en26", s)
            layout = l
            size = w to h
            numberRow = on
            passwordNumberRowField = passwordField
            passwordAutoNumberRow = autoNumberRow
            numberRowFullHeight = fullHeight
            revision++
        }
        rule.waitForIdle()
    }

    @org.junit.After fun resetCorners() { KeysConfigHelper.numberRowCorners = false }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot

    @Test fun numberRowSitsAboveTheLettersOnTheSameGrid() {
        show()
        for ((w, h) in listOf(360 to 232, 800 to 320)) {
            select("26", w, h, true)
            val one = bounds("number-row-key:0")
            val zero = bounds("number-row-key:9")
            val q = bounds("qwerty-key:q")
            val p = bounds("qwerty-key:p")
            assertTrue("数字行在 q 行上面", one.bottom <= q.top + 0.5f)
            assertEquals("1 和 q 同列", q.left, one.left, 1f)
            assertEquals("0 和 p 同列", p.right, zero.right, 1f)
            assertTrue("数字行比字母行矮", one.height < q.height)
            // 键盘总高度不变：字母行比关着时矮。
            select("26", w, h, false)
            val qOff = bounds("qwerty-key:q")
            rule.onNodeWithTag("number-row", true).assertDoesNotExist()
            assertTrue(q.height < qOff.height)
        }
    }

    @Test fun splitGapLinesUpWithTheLetterRows() {
        show()
        select("26-split", 800, 320, true)
        val letters = bounds("split-keyboard-gap")
        val digits = bounds("split-keyboard-gap:9")
        assertEquals(letters.left, digits.left, 1f)
        assertEquals(letters.right, digits.right, 1f)
    }

    @Test fun password26KeyAutoShowsNumberRowFullHeightAndRestoresOnLeave() {
        show()
        select("en26", 360, 232, on = false, passwordField = false, autoNumberRow = true)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()

        select("en26", 360, 232, on = false, passwordField = true, autoNumberRow = true, fullHeight = false)
        assertTrue(bounds("number-row-key:0").height < bounds("qwerty-key:q").height)

        select("en26", 360, 232, on = false, passwordField = true, autoNumberRow = true, fullHeight = true)
        assertEquals(bounds("qwerty-key:q").height, bounds("number-row-key:0").height, 1.5f)

        select("26", 360, 232, on = false, passwordField = true, autoNumberRow = true)
        rule.onNodeWithTag("number-row", true).assertExists()

        select("14", 360, 232, on = false, passwordField = true, autoNumberRow = true)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()

        select("en26", 360, 232, on = false, passwordField = true, autoNumberRow = false)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()

        select("en26", 360, 232, on = false, passwordField = false, autoNumberRow = true)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()
    }

    @Test fun shiftSwitchesNumberRowKeysToShiftSymbols() {
        show()
        select("en26", 360, 232, on = false, passwordField = true, autoNumberRow = true)

        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val symbols = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
        digits.forEachIndexed { index, digit ->
            rule.onNode(hasText(digit) and hasAnyAncestor(hasTestTag("number-row-key:$index")), useUnmergedTree = true)
                .assertExists()
        }

        rule.runOnIdle { vm.toggleShift() }
        rule.waitForIdle()
        symbols.forEachIndexed { index, symbol ->
            rule.onNode(hasText(symbol) and hasAnyAncestor(hasTestTag("number-row-key:$index")), useUnmergedTree = true)
                .assertExists()
        }
        rule.onNodeWithTag("number-row-key:0", true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("!"), committed)
        assertEquals(ShiftMode.OFF, vm.shiftMode.value)
        rule.onNode(hasText("1") and hasAnyAncestor(hasTestTag("number-row-key:0")), useUnmergedTree = true)
            .assertExists()

        rule.runOnIdle { vm.doubleTapShift() }
        rule.waitForIdle()
        rule.onNodeWithTag("number-row-key:1", true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("!", "@"), committed)
        assertEquals(ShiftMode.CAPS, vm.shiftMode.value)
        rule.onNode(hasText("@") and hasAnyAncestor(hasTestTag("number-row-key:1")), useUnmergedTree = true)
            .assertExists()
    }

    @Test fun renderNumberRow() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        val dir = File(out!!).apply { mkdirs() }
        show()
        for ((t, d) in listOf("soft_blue" to false, "pure_black" to true)) {
            SettingsPreferences.getPrefsPublic(app).edit().putString("keyboard_theme", t).commit()
            rule.runOnIdle { theme = t; dark = d }
            val sheet = Sheet(layouts, columns.map { (w, h, on) -> "${w}x$h-${if (on) "on" else "off"}" })
            layouts.forEachIndexed { r, l -> columns.forEachIndexed { c, (w, h, on) ->
                select(l, w, h, on)
                sheet.put(r, c, grab())
            } }
            sheet.save(File(dir, "number-row-$t-${if (d) "dark" else "light"}.png"))
        }
    }

    private fun grab(): Bitmap {
        val b = bounds("number-row-canvas")
        val root = rule.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(Canvas(full)) }
        val crop = Bitmap.createBitmap(full, b.left.toInt().coerceAtLeast(0), b.top.toInt().coerceAtLeast(0),
            b.width.toInt().coerceAtMost(full.width), b.height.toInt().coerceAtMost(full.height))
        if (crop !== full) full.recycle()
        return crop
    }

    /** Rows × columns of cells; ASCII labels (Paint has no CJK font here). */
    private class Sheet(private val rows: List<String>, private val columns: List<String>) {
        private var out: Bitmap? = null
        private var w = 0
        private var h = 0
        private val label = 160
        private val head = 48
        private val gap = 8
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = android.graphics.Color.WHITE }

        fun put(row: Int, column: Int, cell: Bitmap) {
            val sheet = out ?: run {
                w = cell.width; h = cell.height
                Bitmap.createBitmap(label + columns.size * (w + gap), head + rows.size * (h + gap), Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    canvas.drawColor(android.graphics.Color.rgb(64, 64, 64))
                    columns.forEachIndexed { c, name -> canvas.drawText(name, (label + c * (w + gap) + 12).toFloat(), 36f, paint) }
                    rows.forEachIndexed { r, name -> canvas.drawText(name, 8f, (head + r * (h + gap) + 40).toFloat(), paint) }
                    out = it
                }
            }
            Canvas(sheet).drawBitmap(cell, (label + column * (w + gap)).toFloat(), (head + row * (h + gap)).toFloat(), null)
            cell.recycle()
        }

        fun save(file: File) {
            val sheet = out ?: return
            file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
        }
    }
}
