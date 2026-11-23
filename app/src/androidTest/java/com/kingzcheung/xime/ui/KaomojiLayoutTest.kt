package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.data.KaomojiData
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.ui.keyboard.KaomojiKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KaomojiLayoutTest {
    @get:Rule val rule = createComposeRule()
    // 有记录时打开停在「最近」：每个测试从空记录开始，结束后放回原来的记录。
    private val recentPrefs = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("recent_usage", android.content.Context.MODE_PRIVATE)
    private val savedRecent = recentPrefs.getString(RecentUsageStore.KEY_RECENT_KAOMOJI, null)
    @Before fun clearRecent() { recentPrefs.edit().remove(RecentUsageStore.KEY_RECENT_KAOMOJI).commit() }
    @After fun restoreRecent() { recentPrefs.edit().putString(RecentUsageStore.KEY_RECENT_KAOMOJI, savedRecent).commit() }

    @Test fun opensOnRecentWhenThereIsHistoryOtherwiseOnTheFirstCategory() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var withHistory by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme { Box(Modifier.requiredSize(320.dp, 320.dp)) {
                    key(withHistory) { KaomojiKeyboard({}) }
                } }
            }
        }
        rule.onNodeWithTag("kaomoji-category:0").assertIsSelected()
        RecentUsageStore.record(context, RecentUsageStore.KEY_RECENT_KAOMOJI, KaomojiData.categories.first().faces.first())
        withHistory = true
        rule.waitForIdle()
        rule.onNodeWithTag("kaomoji-recent").assertIsSelected()
    }
    @Test fun textFacesCommitWholeStringAndHaveIndependentRecentList() {
        val output = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme { Box(Modifier.requiredSize(320.dp, 320.dp)) { KaomojiKeyboard(output::add) } }
            }
        }
        val face = KaomojiData.categories.first().faces.first()
        rule.onNodeWithTag("kaomoji:$face").performClick()
        rule.runOnIdle { assertEquals(listOf(face), output) }
        rule.onNodeWithTag("kaomoji-recent").performScrollTo().performClick()
        rule.onNodeWithTag("kaomoji:$face").assertIsDisplayed()
    }

    // #291：在「最近」页上点，顺序先不变，离开这一页再回来才按新顺序排。
    @Test fun recentPageKeepsOrderWhileTappingAndReordersAfterLeaving() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme { Box(Modifier.requiredSize(320.dp, 320.dp)) { KaomojiKeyboard({}) } }
            }
        }
        val (a, b) = KaomojiData.categories.first().faces.let { it[0] to it[1] }
        rule.onNodeWithTag("kaomoji:$a").performClick()
        rule.onNodeWithTag("kaomoji:$b").performClick()
        rule.onNodeWithTag("kaomoji-recent").performScrollTo().performClick()
        rule.waitForIdle()
        fun shownBefore(first: String, second: String): Boolean {
            fun pos(face: String) = rule.onAllNodesWithTag("kaomoji:$face").fetchSemanticsNodes()
                .map { it.boundsInRoot }.first { it.left >= 0f && it.left < 320f }
            val x = pos(first); val y = pos(second)
            return x.top < y.top || (x.top == y.top && x.left < y.left)
        }
        assertTrue(shownBefore(b, a))
        tapShown(a)
        rule.waitForIdle()
        assertTrue(shownBefore(b, a))
        rule.onNodeWithTag("kaomoji-pages").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("kaomoji-category:0").assertIsSelected()
        rule.onNodeWithTag("kaomoji-pages").performTouchInput { swipeRight() }
        rule.onNodeWithTag("kaomoji-recent").assertIsSelected()
        assertTrue(shownBefore(a, b))
    }

    private fun tapShown(face: String) {
        val nodes = rule.onAllNodesWithTag("kaomoji:$face")
        val index = nodes.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.left >= 0f && it.boundsInRoot.left < 320f }
        nodes[index].performClick()
    }

    @Test fun swipingTheGridMovesBetweenCategoriesLikeTheEmojiPanel() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme { Box(Modifier.requiredSize(320.dp, 320.dp)) { KaomojiKeyboard({}) } }
            }
        }
        rule.onNodeWithTag("kaomoji-category:0").assertIsSelected()
        rule.onNodeWithTag("kaomoji-pages").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("kaomoji-category:1").assertIsSelected()
        rule.onNodeWithTag("kaomoji:${KaomojiData.categories[1].faces.first()}").assertIsDisplayed()
        rule.onNodeWithTag("kaomoji-pages").performTouchInput { swipeRight() }
        rule.onNodeWithTag("kaomoji-category:0").assertIsSelected()
        rule.onNodeWithTag("kaomoji-pages").performTouchInput { swipeRight() }
        rule.onNodeWithTag("kaomoji-recent").assertIsSelected()
    }
}
