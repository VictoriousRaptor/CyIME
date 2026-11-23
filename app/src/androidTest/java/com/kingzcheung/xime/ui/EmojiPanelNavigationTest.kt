package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.data.EmojiCategory
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.ui.keyboard.EmojiKeyboardLayout
import kotlinx.coroutines.flow.MutableStateFlow
import com.kingzcheung.xime.data.RecentUsageStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EmojiPanelNavigationTest {
    @get:Rule val rule = createComposeRule()
    @Suppress("UNCHECKED_CAST")
    private val categories = ExtensionManager::class.java.getDeclaredField("_emojiCategoriesFlow").apply { isAccessible = true }
        .get(ExtensionManager) as MutableStateFlow<List<EmojiCategory>>
    private val saved = categories.value
    @After fun restoreCategories() { categories.value = saved }
    // 打开停在哪一页取决于有没有最近使用：每个测试从空记录开始，结束后放回原来的记录。
    private val recentPrefs = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("recent_usage", android.content.Context.MODE_PRIVATE)
    private val savedRecent = recentPrefs.getString(RecentUsageStore.KEY_RECENT_EMOJIS, null)
    @Before fun clearRecent() { recentPrefs.edit().remove(RecentUsageStore.KEY_RECENT_EMOJIS).commit() }
    @After fun restoreRecent() { recentPrefs.edit().putString(RecentUsageStore.KEY_RECENT_EMOJIS, savedRecent).commit() }

    @Test fun opensOnRecentWhenThereIsHistory() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        RecentUsageStore.record(context, RecentUsageStore.KEY_RECENT_EMOJIS, "😀")
        show(com.kingzcheung.xime.data.EmojiData.categories)
        rule.onNodeWithTag("emoji-category:0").assertIsSelected()
    }

    @Test fun opensOnTheFirstBuiltinCategoryWhenRecentIsEmpty() {
        show(com.kingzcheung.xime.data.EmojiData.categories)
        rule.onNodeWithTag("emoji-category:1").assertIsSelected()
    }

    private fun show(items: List<EmojiCategory>, width: Int = 400, height: Int = 184, bottomPadding: Int = 0) {
        categories.value = items
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme {
            EmojiKeyboardLayout(onEmojiSelect = {}, onBack = {}, backgroundColor = Color(0xFF211E29),
                textColor = Color.White, accentColor = Color(0xFFCEB7F7), bottomPaddingDp = bottomPadding,
                modifier = Modifier.size(width.dp, height.dp).testTag("emoji-panel"))
        } } }
    }

    @Test fun builtinEmojiStartsBelowExpressionSelectorWithoutASingleGroupTab() {
        show(com.kingzcheung.xime.data.EmojiData.categories)
        rule.onNodeWithTag("emoji-groups").assertDoesNotExist()
        val panel = rule.onNodeWithTag("emoji-panel").fetchSemanticsNode().boundsInRoot
        val selector = rule.onNodeWithTag("expression-emoji").fetchSemanticsNode().boundsInRoot
        val pages = rule.onNodeWithTag("emoji-pages").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(selector.top >= panel.top)
        org.junit.Assert.assertTrue(pages.top >= selector.bottom)
        org.junit.Assert.assertTrue(pages.bottom <= panel.bottom)
        rule.onAllNodesWithText("😀").onLast().performClick()
        rule.waitForIdle()
        val bitmap = rule.onNodeWithTag("emoji-panel").captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "emoji-preview.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun swipingFromBuiltinsEntersTheFirstPluginCategoryWithoutSkippingIt() {
        show(listOf(EmojiCategory("内置", "内", emojis = listOf("内置项")),
            EmojiCategory("第一页", "插", emojis = listOf("甲"), isPlugin = true, pluginId = "test"),
            EmojiCategory("第二页", "插", emojis = listOf("乙"), isPlugin = true, pluginId = "test")))
        rule.onNodeWithText("内").performClick()
        rule.onNodeWithText("内置项").assertIsDisplayed()
        rule.onNodeWithTag("emoji-pages").performTouchInput { swipeLeft() }
        rule.onNodeWithText("甲").assertIsDisplayed()
        rule.onNodeWithTag("emoji-pages").performTouchInput { swipeLeft() }
        rule.onNodeWithText("乙").assertIsDisplayed()
        rule.onNodeWithTag("emoji-pages").performTouchInput { swipeRight() }
        rule.onNodeWithText("甲").assertIsDisplayed()
    }

    @Test fun pluginTabsRemainReachableWhenThereAreMoreThanThePanelCanFit() {
        show((0..11).map { i -> EmojiCategory("分类$i", "P$i", emojis = listOf("结果$i"),
            isPlugin = true, pluginId = "plugin$i") })
        rule.onNodeWithTag("emoji-categories").performScrollToIndex(12)
        rule.onNodeWithText("P11").assertIsDisplayed().performClick()
        rule.onNodeWithText("结果11").assertIsDisplayed()
    }

    @Test fun minimumPanelUsesOneControlRowAndKeepsTheSameBottomInsetInBothModes() {
        show(com.kingzcheung.xime.data.EmojiData.categories, width = 380, height = 184, bottomPadding = 20)
        val panel = rule.onNodeWithTag("emoji-panel").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("expression-controls").fetchSemanticsNode().boundsInRoot
        val delete = rule.onNodeWithTag("expression-delete").fetchSemanticsNode().boundsInRoot
        val pages = rule.onNodeWithTag("emoji-pages").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Controls consume at most one compact row", bar.height <= 40f)
        org.junit.Assert.assertTrue("Delete is in the category row", delete.top >= bar.top && delete.bottom <= bar.bottom)
        org.junit.Assert.assertEquals(bar.bottom, pages.top, 1f)
        org.junit.Assert.assertEquals(panel.bottom - 20f, pages.bottom, 1f)
        org.junit.Assert.assertTrue("Most space is content", pages.height >= 120f)
        rule.onNodeWithTag("expression-kaomoji").performClick()
        val kaomojiBar = rule.onNodeWithTag("expression-controls").fetchSemanticsNode().boundsInRoot
        val grid = rule.onNodeWithTag("kaomoji-grid").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(bar.height, kaomojiBar.height, 1f)
        org.junit.Assert.assertEquals(pages.top, grid.top, 1f)
        org.junit.Assert.assertEquals(pages.bottom, grid.bottom, 1f)
        rule.onNodeWithTag("expression-delete").assertIsDisplayed()
    }
}
