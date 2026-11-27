package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.settings.SmartPredictionSettingsContent
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.ShiftMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 密码输入界面 26 键自动弹出数字行：
 * - 全局「数字行」关闭时，进入密码框的 26 键（英文/中文/分体）自动弹出数字行，离开后恢复；
 * - 尊重「数字行和字母一样高」开关，且不改变工具栏与 Q–P 上角标；
 * - 按 Shift 切换到大写模式时，数字行按钮变为数字对应的特殊符号（!@#$%^&*()）；
 * - 14 键等非 26 键布局、或关闭「密码框自动数字行」开关时不弹出；
 * - 设了 CYIME_RENDER_OUT 时导出密码框键盘与设置开关的布局截图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h1200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordNumberRowRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var layout by mutableStateOf("en26")
    private var size by mutableStateOf(360 to 232)
    private var passwordField by mutableStateOf(true)
    private var passwordAutoNumberRow by mutableStateOf(true)
    private var globalNumberRow by mutableStateOf(false)
    private var numberRowFullHeight by mutableStateOf(false)
    private var revision by mutableStateOf(0)
    private var theme by mutableStateOf("soft_blue")
    private var dark by mutableStateOf(false)
    private val committed = mutableListOf<String>()
    private lateinit var vm: KeyboardViewModel
    private lateinit var app: Application

    private fun schemaFor(layout: String) = if (layout == "14") "pinyin_14jian" else "rime_ice"

    private fun showKeyboard() {
        app = ApplicationProvider.getApplicationContext()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(app)
        vm = KeyboardViewModel(app)
        committed.clear()
        rule.setContent {
            @Suppress("UNUSED_EXPRESSION") revision
            val palette = resolveKeyboardPalette(theme, dark)
            val glass = FrostedGlassConfig.defaults(dark, enabled = false).legibleForTheme(theme, dark)
            CompositionLocalProvider(
                LocalKeyboardPalette provides palette,
                LocalKeyCornerRadius provides KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    frostedGlass = glass,
                    numberRow = globalNumberRow,
                    numberRowFullHeight = numberRowFullHeight,
                    passwordAutoNumberRow = passwordAutoNumberRow,
                    splitKeyboardEnabled = layout == "26-split",
                ),
            ) {
                MaterialTheme {
                    Box(
                        Modifier.requiredSize(800.dp, 320.dp)
                            .background(Color(0xFF808080)),
                    ) {
                        Box(
                            Modifier.requiredSize(size.first.dp, size.second.dp)
                                .testTag("password-keyboard-canvas")
                                .keyboardBackground(
                                    KeyboardThemes.getRenderingScheme(theme).keyboardBackground,
                                    dark,
                                    palette.background,
                                    glass,
                                ),
                        ) {
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
                                    isPasswordField = passwordField,
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
        isPassword: Boolean,
        autoNumberRow: Boolean = true,
        globalRow: Boolean = false,
        fullHeight: Boolean = false,
    ) {
        val schema = schemaFor(l)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        KeysConfigHelper.numberRowCorners = globalRow
        rule.runOnIdle {
            vm.resetKeyboard(l == "en26", schema)
            layout = l
            size = w to h
            passwordField = isPassword
            passwordAutoNumberRow = autoNumberRow
            globalNumberRow = globalRow
            numberRowFullHeight = fullHeight
            revision++
        }
        rule.waitForIdle()
    }

    @After
    fun resetCorners() {
        KeysConfigHelper.numberRowCorners = false
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot

    @Test
    fun password26KeyAutoShowsNumberRowAndRestoresOnLeave() {
        showKeyboard()

        // 普通英文 26 键（全局数字行关闭）：不显示数字行。
        select("en26", 360, 232, isPassword = false, autoNumberRow = true, globalRow = false)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()
        val qNormal = bounds("qwerty-key:q")

        // 进入密码框（英文 26 键）：自动弹出数字行，与 Q-P 对齐，不切换 Q-P 上角标，键盘总高不变。
        select("en26", 360, 232, isPassword = true, autoNumberRow = true, globalRow = false)
        val one = bounds("number-row-key:0")
        val zero = bounds("number-row-key:9")
        val qPwd = bounds("qwerty-key:q")
        val pPwd = bounds("qwerty-key:p")
        assertTrue("数字行在 q 行上方", one.bottom <= qPwd.top + 0.5f)
        assertEquals("1 和 q 左对齐", qPwd.left, one.left, 1f)
        assertEquals("0 和 p 右对齐", pPwd.right, zero.right, 1f)
        assertTrue("数字行比字母行矮", one.height < qPwd.height)
        assertTrue("总高不变时字母行变矮", qPwd.height < qNormal.height)
        assertEquals("Q 上角标保持数字 1 不切换", "1", KeysConfigHelper.getSwipeUpLabel("q", true))
        assertEquals("P 上角标保持数字 0 不切换", "0", KeysConfigHelper.getSwipeUpLabel("p", true))

        // 开启「数字行和字母一样高」时，密码框数字行高度与字母行一致。
        select("en26", 360, 232, isPassword = true, autoNumberRow = true, globalRow = false, fullHeight = true)
        val oneFull = bounds("number-row-key:0")
        val qPwdFull = bounds("qwerty-key:q")
        assertEquals("等高数字行与字母行一样高", qPwdFull.height, oneFull.height, 1.5f)

        // 密码框切到中文 26 键：同样弹出数字行。
        select("zh26", 360, 232, isPassword = true, autoNumberRow = true, globalRow = false)
        rule.onNodeWithTag("number-row", true).assertExists()

        // 密码框使用 14 键：不弹出数字行。
        select("14", 360, 232, isPassword = true, autoNumberRow = true, globalRow = false)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()

        // 关闭「密码框自动数字行」开关后，密码框 26 键不再自动弹出。
        select("en26", 360, 232, isPassword = true, autoNumberRow = false, globalRow = false)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()

        // 离开密码框回到普通输入框：数字行自动收起。
        select("en26", 360, 232, isPassword = false, autoNumberRow = true, globalRow = false)
        rule.onNodeWithTag("number-row", true).assertDoesNotExist()

        // 分体 26 键在密码框同样自动弹出且中缝对齐。
        select("26-split", 800, 320, isPassword = true, autoNumberRow = true, globalRow = false)
        val letters = bounds("split-keyboard-gap")
        val digits = bounds("split-keyboard-gap:9")
        assertEquals(letters.left, digits.left, 1f)
        assertEquals(letters.right, digits.right, 1f)
    }

    @Test
    fun shiftSwitchesNumberRowKeysToShiftSymbols() {
        showKeyboard()
        select("en26", 360, 232, isPassword = true, autoNumberRow = true, globalRow = false)

        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val symbols = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
        digits.forEachIndexed { index, digit ->
            rule.onNode(hasText(digit) and hasAnyAncestor(hasTestTag("number-row-key:$index")), useUnmergedTree = true)
                .assertExists()
        }

        // 单次 Shift：数字行键帽切换为上方对应的特殊符号，点按后输入对应符号并回落小写。
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

        // 大写锁定：点按数字行符号后保持大写模式。
        rule.runOnIdle { vm.onShiftDoubleTap() }
        rule.waitForIdle()
        rule.onNodeWithTag("number-row-key:1", true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("!", "@"), committed)
        assertEquals(ShiftMode.CAPS, vm.shiftMode.value)
        rule.onNode(hasText("@") and hasAnyAncestor(hasTestTag("number-row-key:1")), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun renderPasswordNumberRowScreenshots() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        val dir = File(out!!).apply { mkdirs() }
        showKeyboard()

        for ((themeId, isDark, label) in listOf(
            Triple("soft_blue", false, "light"),
            Triple("pure_black", true, "dark"),
        )) {
            SettingsPreferences.getPrefsPublic(app).edit().putString("keyboard_theme", themeId).commit()
            rule.runOnIdle { theme = themeId; dark = isDark }

            // 1. 单独输出手机密码框英文 26 键截图
            select("en26", 360, 232, isPassword = true, autoNumberRow = true, globalRow = false)
            saveBitmap(grab("password-keyboard-canvas"), File(dir, "password-26key-$label.png"))

            // 2. 输出多状态对比图（360x232 手机视角）
            val phoneCases = listOf(
                "normal-en26" to { select("en26", 360, 232, isPassword = false, autoNumberRow = true) },
                "pwd-en26-on" to { select("en26", 360, 232, isPassword = true, autoNumberRow = true) },
                "pwd-zh26-on" to { select("zh26", 360, 232, isPassword = true, autoNumberRow = true) },
                "pwd-en26-off" to { select("en26", 360, 232, isPassword = true, autoNumberRow = false) },
                "pwd-14key" to { select("14", 360, 232, isPassword = true, autoNumberRow = true) },
            )
            val sheet = VerticalSheet(phoneCases.map { it.first })
            phoneCases.forEachIndexed { index, (_, applyCase) ->
                applyCase()
                sheet.put(index, grab("password-keyboard-canvas"))
            }
            sheet.save(File(dir, "password-number-row-comparison-$label.png"))

            // 3. 输出平板分体 26 键密码框截图
            select("26-split", 800, 320, isPassword = true, autoNumberRow = true, globalRow = false)
            saveBitmap(grab("password-keyboard-canvas"), File(dir, "password-26key-split-$label.png"))
        }
    }

    @Test
    fun renderPasswordSettingsTogglesScreenshot() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        val dir = File(out!!).apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        SettingsPreferences.setPasswordAutoNumberRowEnabled(app, true)
        SettingsPreferences.setPasswordDisableLearningEnabled(app, true)

        rule.setContent {
            MaterialTheme {
                Surface(Modifier.requiredSize(780.dp, 720.dp).testTag("settings-toggles-root")) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(Modifier.requiredSize(368.dp, 696.dp).testTag("layout-settings-pane")) {
                            LayoutDisplaySettingsContent(onBack = {})
                        }
                        Box(Modifier.requiredSize(368.dp, 696.dp).testTag("prediction-settings-pane")) {
                            SmartPredictionSettingsContent(onBack = {})
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("layout-settings-pane")))
            .performScrollToNode(hasText("密码框自动数字行"))
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("prediction-settings-pane")))
            .performScrollToNode(hasText("密码框临时关闭学习"))
        rule.waitForIdle()
        saveBitmap(grab("settings-toggles-root"), File(dir, "password-settings-toggles.png"))
    }

    private fun grab(tag: String): Bitmap {
        val b = bounds(tag)
        val root = rule.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(Canvas(full)) }
        val left = b.left.toInt().coerceAtLeast(0)
        val top = b.top.toInt().coerceAtLeast(0)
        val width = b.width.toInt().coerceAtMost(full.width - left)
        val height = b.height.toInt().coerceAtMost(full.height - top)
        val crop = Bitmap.createBitmap(full, left, top, width, height)
        if (crop !== full) full.recycle()
        return crop
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class VerticalSheet(private val labels: List<String>) {
        private var out: Bitmap? = null
        private val labelW = 240
        private val gap = 12
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = android.graphics.Color.WHITE
        }

        fun put(row: Int, cell: Bitmap) {
            val sheet = out ?: Bitmap.createBitmap(
                labelW + cell.width + gap,
                labels.size * (cell.height + gap) + gap,
                Bitmap.Config.ARGB_8888,
            ).also {
                Canvas(it).drawColor(android.graphics.Color.rgb(48, 48, 48))
                out = it
            }
            val top = gap + row * (cell.height + gap)
            val canvas = Canvas(sheet)
            canvas.drawText(labels[row], 16f, (top + cell.height / 2 + 10).toFloat(), paint)
            canvas.drawBitmap(cell, labelW.toFloat(), top.toFloat(), null)
            cell.recycle()
        }

        fun save(file: File) {
            val sheet = out ?: return
            file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
        }
    }
}
