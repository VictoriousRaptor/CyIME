package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_MAX_LINES_KEY
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_CLOSE_AFTER_PASTE_KEY
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 设置 › 剪贴板：剪贴板 ⋯ 菜单里的开关和面板设置在这一页，写的是面板读的同一个值。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "zh-rCN-w400dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardPanelSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("clipboard_board", Context.MODE_PRIVATE)

    @Test fun everyClipboardSettingIsOnThePageAndWritesThePanelsValue() {
        prefs.edit().clear().commit()
        rule.setContent { XimeTheme(darkTheme = false) { ClipboardPanelSettingsContent(onBack = {}, onNavigateToGroups = {}) } }
        listOf("剪贴板分组", "卡片显示行数", "最近截图", "上屏后自动关闭剪贴板", "历史记录", "数量上限", "保留时间").forEach {
            rule.onNodeWithText(it).performScrollTo()
        }
        rule.onNodeWithTag("clipboard-card-lines:8").performScrollTo().performClick()
        rule.onNodeWithText("上屏后自动关闭剪贴板").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(8, prefs.getInt(CLIPBOARD_CARD_MAX_LINES_KEY, 4))
        assertFalse(prefs.getBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, true))
        System.getenv("CYIME_RENDER_OUT")?.takeIf { it.isNotEmpty() }?.let { out ->
            rule.onNodeWithText("剪贴板分组").performScrollTo()
            rule.waitForIdle()
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out).mkdirs()
            File(out, "clipboard-panel-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** 用户 11-15：验证码、敏感内容、导出导入从「剪贴板与同步」挪到这一页；同步是插件，这里只有跳过去的入口。 */
    @Test fun movedItemsAreHereAndSyncIsOneTapAway() {
        var syncOpened = 0
        rule.setContent {
            XimeTheme(darkTheme = false) {
                ClipboardPanelSettingsContent(onBack = {}, onNavigateToGroups = {}, onNavigateToSync = { syncOpened++ })
            }
        }
        listOf("新短信验证码自动复制", "记录密码等敏感内容", "导出剪贴板", "导入剪贴板").forEach {
            rule.onAllNodesWithText(it).onFirst().performScrollTo()
        }
        rule.onNodeWithText("剪贴板同步").performScrollTo().performClick()
        assertEquals(1, syncOpened)
    }
}
