package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.CornerVariant
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.QwertySwipeSymbols
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.QwertySymbolEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「按键符号」26 键页（用户 11-15）：页上三个开关切到哪种情形就编辑哪一套。设了 CYIME_RENDER_OUT 时另存截图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w420dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolEditorVariantsRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val bounds = rule.onNodeWithTag("settings-root").fetchSemanticsNode().boundsInRoot
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val crop = Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(),
            bounds.width.toInt().coerceAtMost(bitmap.width - bounds.left.toInt()),
            bounds.height.toInt().coerceAtMost(bitmap.height - bounds.top.toInt()))
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle(); bitmap.recycle()
    }

    private fun waitForVariant(variant: CornerVariant) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(variant.label, substring = true).fetchSemanticsNodes().isNotEmpty() &&
            rule.onAllNodesWithTag("symbol-row-input").fetchSemanticsNodes().isNotEmpty() &&
            runCatching { rule.onNodeWithTag("symbol-row-input").assertIsEnabled() }.isSuccess
    }

    @Test fun switchesPickTheLayoutAndEditsStayInIt() {
        val app = rule.activity
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        rule.setContent {
            MaterialTheme {
                Surface(Modifier.requiredSize(400.dp, 1300.dp).testTag("settings-root")) { QwertySymbolEditor(onBack = {}) }
            }
        }
        waitForVariant(CornerVariant.BOTH)
        shot("symbol-editor-both")

        // 打开数字行：换到另一套，第一排上方不再是数字。
        rule.onNodeWithTag("symbol-switch-number-row").performScrollTo().performClick()
        waitForVariant(CornerVariant.NR_BOTH)
        assertTrue(SettingsPreferences.isNumberRowEnabled(app))
        assertTrue(rule.onAllNodesWithText("“", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("symbol-editor-number-row-both")

        // 关掉下方角标：又是另一套。
        rule.onNodeWithTag("symbol-switch-down").performScrollTo().performClick()
        waitForVariant(CornerVariant.NR_UP)
        shot("symbol-editor-number-row-up")

        // 只换了设置页看的那一套，没有往任何一套里写东西。
        for (variant in CornerVariant.entries) assertEquals(emptyMap<String, String>(), QwertySwipeSymbols.load(app, false, variant))
        // 弹框（整排输入、单键修改）在这个测试环境里等不到空闲，不在这里点；整排文字的读写见 CornerVariantSymbolsTest。
    }
}
