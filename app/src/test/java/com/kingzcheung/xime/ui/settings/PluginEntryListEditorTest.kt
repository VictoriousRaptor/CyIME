package com.kingzcheung.xime.ui.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 11-19「每个都是一条可编辑删改重命名调整顺序的条目」：插件设置里的条目列表（AI 改写的提示词）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w390dp-h800dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginEntryListEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val defaults = JSONArray().apply {
        listOf(
            "polish" to ("润色" to "把下面这段话润色得更通顺自然，意思不变，长度和原文差不多。"),
            "fix" to ("改错字" to "只改正下面这段话里的错别字、标点和明显的语法错误，其他字一个都不要动。没有错就原样输出。"),
            "formal" to ("更正式" to "把下面这段话改成更正式、礼貌的书面语，意思不变。"),
            "casual" to ("更口语" to "把下面这段话改成更口语、轻松自然的说法，意思不变。"),
            "shorten" to ("缩短" to "把下面这段话缩短，只保留关键信息，意思不变。"),
            "translate" to ("翻译" to "把下面这段话翻译成{lang}。"),
        ).forEach { (id, e) -> put(org.json.JSONObject().put("id", id).put("name", e.first).put("text", e.second)) }
    }.toString()

    private var saved: String? = null
    private fun savedList(): JSONArray = JSONArray(saved!!)
    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("name") }

    private fun show(initial: String) = rule.setContent {
        XimeTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SettingsSection("提示词") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PluginEntryListEditor(
                                label = "改写动作",
                                helpText = "改写面板上的按钮按这个顺序显示。点一条改名字和提示词，长按拖动排序。",
                                placeholder = "比如：把下面这段话翻译成英文。",
                                initial = initial,
                                defaultValue = defaults,
                                onChange = { saved = it },
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun parseKeepsOrderAndUnknownFields() {
        val list = PluginEntries.parse(defaults)
        assertEquals(listOf("润色", "改错字", "更正式", "更口语", "缩短", "翻译"), list.map { it.name })
        val back = JSONArray(PluginEntries.encode(PluginEntries.move(list, 0, 2)))
        assertEquals(listOf("改错字", "更正式", "润色"), names(back).take(3))
        assertEquals("polish", back.getJSONObject(2).getString("id"))
        assertTrue(PluginEntries.parse("坏了").isEmpty())
        assertTrue(PluginEntries.parse(null).isEmpty())
        assertEquals("[]", PluginEntries.encode(emptyList()))
    }

    @Test fun renameAndEditKeepTheEntrysOwnId() {
        show(defaults)
        save("plugin-entries-list")
        rule.onNodeWithTag("entry:1").performClick()
        // 在卡片里直接改，不弹框
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithTag("entry-editor").assertExists()
        save("plugin-entries-edit")
        rule.onNodeWithTag("entry-name").performTextReplacement("纠错")
        rule.onNodeWithTag("entry-text").performTextReplacement("只改错别字。")
        rule.onNodeWithText("保存").performScrollTo().performClick()
        rule.onNodeWithTag("entry-editor").assertDoesNotExist()
        val fix = savedList().getJSONObject(1)
        assertEquals("纠错", fix.getString("name"))
        assertEquals("只改错别字。", fix.getString("text"))
        assertEquals("fix", fix.getString("id"))
        rule.onNodeWithText("纠错").assertExists()
    }

    @Test fun addPutsANewEntryAtTheEndWithoutId() {
        show(defaults)
        rule.onNodeWithText("添加").performScrollTo().performClick()
        rule.onNodeWithText("保存").assertIsNotEnabled()
        rule.onNodeWithTag("entry-name").performTextInput("日语")
        rule.onNodeWithTag("entry-text").performTextInput("把下面这段话翻译成日语。")
        rule.onNodeWithText("保存").performScrollTo().performClick()
        val list = savedList()
        assertEquals(7, list.length())
        assertEquals("日语", list.getJSONObject(6).getString("name"))
        assertFalse(list.getJSONObject(6).has("id"))
        save("plugin-entries-added")
    }

    @Test fun deleteRemovesOnlyThatEntry() {
        show(defaults)
        rule.onNodeWithTag("entry:0").performClick()
        rule.onNodeWithText("删除").performScrollTo().performClick()
        assertEquals(listOf("改错字", "更正式", "更口语", "缩短", "翻译"), names(savedList()))
    }

    @Test fun resetBringsBackTheDefaultsAfterConfirming() {
        show("[]")
        rule.onNodeWithText("一条也没有：点下面「添加」").assertExists()
        rule.onNodeWithText("恢复默认").performClick()
        save("plugin-entries-reset", dialog = true)
        rule.onNode(hasText("恢复默认") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("润色", "改错字", "更正式", "更口语", "缩短", "翻译"), names(savedList()))
        assertEquals("fix", savedList().getJSONObject(1).getString("id"))
    }

    @Test fun longPressDragMovesTheCardAndSavesTheOrder() {
        show(defaults)
        rule.onNodeWithTag("entry:0").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(12) { moveBy(Offset(0f, height * 0.1f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("改错字", "润色", "更正式"), names(savedList()).take(3))
        assertEquals("polish", savedList().getJSONObject(1).getString("id"))
    }

    private fun save(name: String, dialog: Boolean = false) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        rule.waitForIdle()
        // captureToImage 在 Robolectric 上等不到下一帧会超时：直接把窗口画进位图（弹框是最上面那个窗口）。
        rule.runOnIdle {
            val view = if (dialog) android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                else rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
