package com.kingzcheung.xime.ui.render

import android.graphics.Bitmap
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.CellDictionaryState
import com.kingzcheung.xime.settings.DictionaryExport
import com.kingzcheung.xime.settings.InstalledCellDictionary
import com.kingzcheung.xime.ui.settings.CellDictionarySettingsPanel
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.CellDictionaryUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 「导入词库」顶上的「导出」：选词库和格式后交给 onExport。设了 CYIME_RENDER_OUT 时出一张手机宽度的对话框图。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictionaryExportRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun exportDialogPassesTheChosenPackAndFormat() {
        val pack = InstalledCellDictionary("local_0123456789abcdef01234567", "我的游戏词", "本地导入", 1200, sha256 = "x")
        val exported = mutableListOf<Triple<String?, String, DictionaryExport.Format>>()
        rule.setContent {
            XimeTheme {
                CellDictionarySettingsPanel(CellDictionaryUiState(CellDictionaryState(installed = listOf(pack)), busy = false),
                    {}, {}, { _, _ -> }, {}, {}, {}, embedded = true,
                    onExport = { source, name, format -> exported += Triple(source, name, format) })
            }
        }
        rule.onNodeWithTag("cell-dictionary-export").performClick()
        rule.onNodeWithTag("dictionary-export-source:${pack.id}").performScrollTo().performClick()
        rule.onNodeWithTag("dictionary-export-format:GBOARD").performScrollTo().performClick()
        System.getenv("CYIME_RENDER_OUT")?.let { out ->
            rule.runOnIdle {
                val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                val bitmap = Bitmap.createBitmap(dialog.width, dialog.height, Bitmap.Config.ARGB_8888)
                dialog.draw(android.graphics.Canvas(bitmap))
                File(out, "dictionary-export.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        rule.onNode(hasText("导出") and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(listOf(Triple(pack.id, pack.name, DictionaryExport.Format.GBOARD)), exported) }
    }
}
