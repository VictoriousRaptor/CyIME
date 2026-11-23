package com.kingzcheung.xime.ui.render

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 关闭键放右边（11-22 他）：全面屏手势的人要「不管什么时候点右边同一个位置都能收起键盘」。
 * 开了「候选滑到头回弹展开」又选「右边」：打字时右边「×」取消输入，出联想时「×」关联想，回到空闲同一个位置是收起键盘；左边不放「<」。
 * 没选右边（默认）或开关关着时照旧。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class CandidateCloseRightTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray)
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs get() = SettingsPreferences.getPrefsPublic(context)

    @After fun reset() {
        prefs.edit().remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND)
            .remove(SettingsPreferences.KEY_CANDIDATE_CLOSE_ON_RIGHT).commit()
    }

    private val typing = CandidateBarState.ChineseCandidates(listOf("这样就很好", "这样就"), inputText = "zheyangjiuhenhao", hasMore = true)
    private val association = CandidateBarState.AssociationOnly(listOf("，", "啊", "了"), hasMore = true)

    @Test fun samePlaceClosesPredictionThenHidesKeyboard() {
        SettingsPreferences.setCandidateSwipeExpand(context, true)
        SettingsPreferences.setCandidateCloseOnRight(context, true)
        val state = mutableStateOf<CandidateBarState>(typing)
        var cancels = 0
        var hides = 0
        rule.setContent {
            CandidateBar(state = state.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = { state.value = association },
                    onCancelInput = { cancels++; state.value = CandidateBarState.Idle },
                    onShowMoreCandidates = {}, onHideKeyboard = { hides++ }),
                showPreeditPreview = false, modifier = Modifier.width(400.dp))
        }
        // 打字：右边「×」取消输入，左边没有「<」，右边也没有单独的收起键。
        rule.onNodeWithTag("candidate-close-right").assertExistsWithDescription("取消输入")
        rule.onNodeWithTag("candidate-hide").assertDoesNotExist()
        val typingX = centerX("candidate-close-right")
        // 上屏出联想：同一处是「×」关联想。
        rule.onNodeWithTag("bar-candidate:0", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("candidate-close-right").assertExistsWithDescription("关闭联想")
        rule.onNodeWithTag("association-cancel").assertDoesNotExist()
        val predictionX = centerX("candidate-close-right")
        rule.onNodeWithTag("candidate-close-right").performClick()
        rule.runOnIdle { assertEquals(1, cancels); assertEquals(0, hides) }
        // 回到空闲：同一处是收起键盘。
        val idleX = centerX("toolbar-hide")
        rule.onNodeWithTag("toolbar-hide").performClick()
        rule.runOnIdle { assertEquals(1, hides) }
        val dp = rule.density.density
        assertTrue("打字 $typingX / 联想 $predictionX / 空闲 $idleX 应在同一处",
            abs(typingX - predictionX) < 1f && abs(typingX - idleX) < 4f * dp)
    }

    @Test fun leftIsDefaultAndSameAs104() {
        SettingsPreferences.setCandidateSwipeExpand(context, true)
        assertEquals(false, SettingsPreferences.isCandidateCloseOnRight(context))
        rule.setContent {
            CandidateBar(state = typing, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {}, onShowMoreCandidates = {}, onHideKeyboard = {}),
                showPreeditPreview = false, modifier = Modifier.width(400.dp))
        }
        rule.onNodeWithTag("candidate-close-right").assertDoesNotExist()
        rule.onNodeWithTag("candidate-hide").assertExists()
        rule.onNodeWithContentDescription("取消输入", useUnmergedTree = true).assertExists()
    }

    @Test fun rightChoiceDoesNothingWhileSwipeExpandIsOff() {
        SettingsPreferences.setCandidateCloseOnRight(context, true)
        rule.setContent {
            CandidateBar(state = typing, visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {}, onShowMoreCandidates = {}, onHideKeyboard = {}),
                showPreeditPreview = false, modifier = Modifier.width(400.dp))
        }
        rule.onNodeWithTag("candidate-close-right").assertDoesNotExist()
        rule.onNodeWithTag("candidate-hide").assertDoesNotExist()
        rule.onNodeWithTag("candidate-expansion").assertExists()
    }

    private fun centerX(tag: String): Float = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.x

    /** 右边这一格存在，读屏说的是 [label]。 */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsWithDescription(label: String) {
        assertExists()
        rule.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
    }
}
