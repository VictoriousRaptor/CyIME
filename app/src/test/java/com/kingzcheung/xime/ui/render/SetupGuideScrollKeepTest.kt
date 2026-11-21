package com.kingzcheung.xime.ui.render

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.model.FeedbackChoice
import com.kingzcheung.xime.model.SetupMoreChoices
import com.kingzcheung.xime.model.SetupMoreFacts
import com.kingzcheung.xime.model.TextSizeChoice
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import com.kingzcheung.xime.ui.settings.SetupGuideDraft
import com.kingzcheung.xime.ui.settings.SetupMoreContent
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 11-19：「更多配置」选了几项、滑到下面点进剪贴板同步等页面再返回，选项变回去、页面拉回顶上。
 * 离开这段界面再回来（跳转页、主页和更多配置互相切换），滚动位置和选过的回答都要接着原样。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h700dp-mdpi")
class SetupGuideScrollKeepTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val now = SetupMoreChoices(SpaceHoldAction.CURSOR, deleteSkipsAssociation = false, clearBeforeCursorOnly = false,
        textSize = TextSizeChoice.STANDARD, feedback = FeedbackChoice.SOUND_AND_VIBRATION, glass = false, autoPunctuation = false)

    @Test fun moreKeepsScrollAndAnswersAfterLeavingAndComingBack() {
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        val draft = SetupGuideDraft()
        val facts = SetupMoreFacts(now)
        var shown by mutableStateOf(true)
        var answers by mutableStateOf(now)
        rule.setContent {
            XimeTheme(darkTheme = false) {
                Box(Modifier.width(400.dp).height(700.dp)) {
                    if (shown) SetupMoreContent(facts, answers, result = null, onAnswers = { answers = it }, onBack = {},
                        onSkip = {}, onApply = {}, onDone = {}, onOpenRoute = {}, scroll = draft.moreScroll)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("setup-more-glass:true").performScrollTo().performClick()
        rule.onNodeWithTag("setup-more-open:clipboard_sync").performScrollTo()
        rule.waitForIdle()
        val at = draft.moreScroll.value
        assertTrue("没滚下去：$at", at > 0)

        // 点进跳转页：这段界面离开组合；返回：重新组合。
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()

        assertEquals(at, draft.moreScroll.value)
        rule.onNodeWithTag("setup-more-open:clipboard_sync").assertIsDisplayed()
        rule.onNodeWithTag("setup-more-glass:true").performScrollTo().assertIsSelected()
    }
}
