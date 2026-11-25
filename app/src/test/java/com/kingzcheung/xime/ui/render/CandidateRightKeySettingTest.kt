package com.kingzcheung.xime.ui.render

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 候选栏两种用法（11-23 他：两套不能藏在「回弹」开关后面；11-24 定名「默认」「经典」）。
 * 「默认」= 1.15.101；选「经典」存成左右滑 + 关闭键放右边，「左右滑展开候选」锁成开着、「显示 ‹ 取消键」「关联想用 ⊗」不显示。
 * 存的键名不变（candidate_swipe_expand、candidate_close_on_right）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateRightKeySettingTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before @After fun reset() {
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND)
            .remove(SettingsPreferences.KEY_CANDIDATE_CLOSE_ON_RIGHT)
            .remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_BEFORE_CLOSE)
            .remove(SettingsPreferences.KEY_CANDIDATE_RIGHT_HIDES)
            .remove(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON).commit()
    }

    @Test fun defaultIsExpandAndCloseTurnsSwipeOn() {
        KeyboardThemes.initFromConfig(context)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") } }
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("candidate-right-key:close"))
        rule.onNodeWithTag("candidate-right-key:expand").assertIsSelected()
        rule.onNodeWithTag("candidate-right-key:close").assertIsNotSelected()
        rule.onNodeWithText("显示 ‹ 取消键").assertExists()

        rule.onNodeWithTag("candidate-right-key:close").performClick()
        rule.onNodeWithTag("candidate-right-key:close").assertIsSelected()
        assertEquals(true, SettingsPreferences.isCandidateSwipeExpand(context))
        assertEquals(true, SettingsPreferences.isCandidateCloseOnRight(context))
        rule.onNodeWithText("显示 ‹ 取消键").assertDoesNotExist()
        rule.onNodeWithText("选了「经典」时只能靠滑展开候选", substring = true).assertExists()

        // 选回「展开」：关闭键回左边，左右滑恢复成选「关闭」前的关着（11-23 平板实测）。
        rule.onNodeWithTag("candidate-right-key:expand").performClick()
        rule.onNodeWithTag("candidate-right-key:expand").assertIsSelected()
        assertEquals(false, SettingsPreferences.isCandidateCloseOnRight(context))
        assertEquals(false, SettingsPreferences.isCandidateSwipeExpand(context))
        rule.onNodeWithText("显示 ‹ 取消键").assertExists()
    }

    /** 第三张卡「右侧收起」（原名收键盘）（= 1.15.103，11-24）：选它打开并锁住左右滑，取消键那一行照旧在；换到「经典」再回「默认」，左右滑恢复成最早的关着。 */
    @Test fun hideCardSitsBetweenAndRestoresSwipe() {
        KeyboardThemes.initFromConfig(context)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") } }
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("candidate-right-key:hide"))
        rule.onNodeWithTag("candidate-right-key:hide").performClick()
        rule.onNodeWithTag("candidate-right-key:hide").assertIsSelected()
        assertEquals(com.kingzcheung.xime.settings.CandidateRightKey.HIDE, SettingsPreferences.candidateRightKey(context))
        assertEquals(true, SettingsPreferences.isCandidateSwipeExpand(context))
        assertEquals(false, SettingsPreferences.isCandidateCloseOnRight(context))
        rule.onNodeWithText("选了「右侧收起」时只能靠滑展开候选", substring = true).assertExists()
        rule.onNodeWithText("显示 ‹ 取消键").assertExists()
        // 卡片在长列表里，先滚到再点（不滚时点不到，选项不变）。
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("candidate-right-key:close"))
        rule.onNodeWithTag("candidate-right-key:close").performClick()
        assertEquals(com.kingzcheung.xime.settings.CandidateRightKey.CLOSE, SettingsPreferences.candidateRightKey(context))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("candidate-right-key:expand"))
        rule.onNodeWithTag("candidate-right-key:expand").performClick()
        assertEquals(com.kingzcheung.xime.settings.CandidateRightKey.EXPAND, SettingsPreferences.candidateRightKey(context))
        assertEquals(false, SettingsPreferences.isCandidateSwipeExpand(context))
    }

    /** 选「关闭」前自己开着左右滑：选回「展开」后照旧开着；在「关闭」上再点一次也不会把记下的值盖掉。 */
    @Test fun swipeComesBackToWhatItWas() {
        SettingsPreferences.setCandidateSwipeExpand(context, true)
        SettingsPreferences.setCandidateRightKeyCloses(context, true)
        SettingsPreferences.setCandidateRightKeyCloses(context, false)
        assertEquals(true, SettingsPreferences.isCandidateSwipeExpand(context))

        SettingsPreferences.setCandidateSwipeExpand(context, false)
        SettingsPreferences.setCandidateRightKeyCloses(context, true)
        SettingsPreferences.setCandidateRightKeyCloses(context, true)
        assertEquals(true, SettingsPreferences.isCandidateSwipeExpand(context))
        SettingsPreferences.setCandidateRightKeyCloses(context, false)
        assertEquals(false, SettingsPreferences.isCandidateSwipeExpand(context))
        assertEquals(false, SettingsPreferences.isCandidateCloseOnRight(context))
    }

    /** 11-25 他：「顺序不对、名字蠢」——从上到下默认、经典、右侧收起。高屏让三张卡都在屏上。 */
    @Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1500dp-xhdpi")
    @Test fun cardsGoDefaultClassicThenRightHide() {
        KeyboardThemes.initFromConfig(context)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") } }
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("candidate-right-key:expand"))
        val top = listOf("expand", "close", "hide").map {
            rule.onNodeWithTag("candidate-right-key:$it").fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(top.sorted(), top)
        rule.onNodeWithText("右侧收起").assertExists()
        rule.onNodeWithText("收键盘").assertDoesNotExist()
    }

    /** 出图对照效果图（11-24 他：做出来要和效果图一样）：三张卡一张图，深色主题，存到 build/reports/。 */
    @Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1500dp-xhdpi")
    @Test fun renderCardsNextToTheMock() {
        KeyboardThemes.initFromConfig(context)
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(context)
        SettingsPreferences.setDarkMode(context, 1)
        for (mode in listOf(com.kingzcheung.xime.settings.CandidateRightKey.EXPAND, com.kingzcheung.xime.settings.CandidateRightKey.CLOSE)) {
            SettingsPreferences.setCandidateRightKey(context, mode)
            val shown = androidx.compose.runtime.mutableStateOf(false)
            if (mode == com.kingzcheung.xime.settings.CandidateRightKey.EXPAND) rule.setContent {
                if (shown.value) XimeTheme(darkTheme = true, themeId = "soft_blue") {
                    LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") }
            }
            rule.runOnIdle { shown.value = false }
            rule.runOnIdle { shown.value = true }
            rule.waitForIdle()
            // 先滚到最下面那个开关，再滚回第一张卡：三张卡排在屏幕上方，下面的开关和小字都在图里。
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("settings-search-anchor:左右滑展开候选"))
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("candidate-right-key:expand"))
            rule.waitForIdle()
            val view = rule.activity.window.decorView
            val frame = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            rule.runOnIdle { view.draw(android.graphics.Canvas(frame)) }
            java.io.File("build/reports/candidate-mode-cards-${mode.name.lowercase()}.png").apply { parentFile?.mkdirs() }
                .outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
        }
        SettingsPreferences.getPrefsPublic(context).edit().remove("dark_mode").commit()
    }
}
