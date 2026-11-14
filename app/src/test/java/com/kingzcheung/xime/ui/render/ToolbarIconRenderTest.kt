package com.kingzcheung.xime.ui.render

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.ui.keyboard.ToolbarButtonIcon
import com.kingzcheung.xime.ui.keyboard.ownPluginButtonIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 工具栏、菜单里的 AI 回复、AI 改写、翻译用线条图标，不再显示插件的单字（应、改、译）；
 * 简繁开关的图标就是「简」或「繁」字（用户 2026-11-11）。设置了 CYIME_RENDER_OUT 时另出浅色、深色图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolbarIconRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** 三个插件 manifest 里写的按钮 id：改了 id 图标就会退回单字，这里先拦住。 */
    private fun manifestButtonId(plugin: String): String {
        val manifest = listOf(File("../plugins/$plugin/manifest.yaml"), File("plugins/$plugin/manifest.yaml")).first { it.isFile }
        return Regex("""toolbarButtons:\s*\n\s*-\s*id:\s*(\S+)""").find(manifest.readText())!!.groupValues[1]
    }

    @Test fun ownPluginButtonsHaveIcons() {
        for (plugin in listOf("ai-reply", "ai-rewrite", "translate")) {
            assertNotNull(plugin, ownPluginButtonIcon(manifestButtonId(plugin)))
        }
        assertNull(ownPluginButtonIcon("com.example.other:open"))
    }

    private fun plugin(name: String, label: String, glyph: String) = ToolbarButtonItem.Plugin(
        id = "com.kingzcheung.xime.plugin.$name:open", label = label, icon = PluginIcon(text = glyph),
        pluginId = "com.kingzcheung.xime.plugin.$name", action = "open_panel",
    )

    @Test fun iconsReplaceSingleCharacters() {
        val items = listOf(
            ToolbarButtonItem.Builtin(ToolbarButton.EMOJI) to false,
            ToolbarButtonItem.Builtin(ToolbarButton.CLIPBOARD) to false,
            ToolbarButtonItem.Builtin(ToolbarButton.VOICE) to false,
            plugin("ai_reply", "AI 回复", "应") to false,
            plugin("ai_rewrite", "AI 改写", "改") to false,
            plugin("translate", "翻译", "译") to false,
            ToolbarButtonItem.Builtin(ToolbarButton.TRADITIONAL) to false,
            ToolbarButtonItem.Builtin(ToolbarButton.TRADITIONAL) to true,
        )
        val dark = androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            val bg = if (dark.value) Color(0xFF1F1F23) else Color(0xFFECEFF3)
            val tile = if (dark.value) Color(0xFF3A3A40) else Color.White
            val tint = if (dark.value) Color(0xFFECECEE) else Color(0xFF222226)
            Column(Modifier.background(bg).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (size in listOf(22, 26)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((item, on) in items) Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) tint else tile),
                        contentAlignment = Alignment.Center,
                    ) {
                        ToolbarButtonIcon(item, tint = if (on) tile else tint.copy(alpha = .7f),
                            modifier = Modifier.size(size.dp), active = on)
                    }
                }
            }
        }
        rule.waitForIdle()
        for (glyph in listOf("应", "改", "译")) rule.onAllNodesWithText(glyph, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(2, rule.onAllNodesWithContentDescription("简体", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithContentDescription("繁体", useUnmergedTree = true).fetchSemanticsNodes().size)

        val out = System.getenv("CYIME_RENDER_OUT")
        if (out.isNullOrEmpty()) return
        File(out).mkdirs()
        for (theme in listOf("light", "dark")) {
            rule.runOnIdle { dark.value = theme == "dark" }
            rule.waitForIdle()
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out, "toolbar-icons-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
