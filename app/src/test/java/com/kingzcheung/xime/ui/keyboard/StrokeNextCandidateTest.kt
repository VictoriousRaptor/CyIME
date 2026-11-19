package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.strokeGlyphs
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.advanceT9Candidate
import com.kingzcheung.xime.service.shownPreedit
import com.kingzcheung.xime.service.spaceCandidateIndex
import com.kingzcheung.xime.service.usesT9CandidateNavigation
import com.kingzcheung.xime.settings.EngineProfile
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SpellingPatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 笔画键盘 11-18 贡献者反馈：
 * - 0 键打字时是「下一项」（同九键），空格、标点上屏标亮的候选；没在打字时点按打 0。
 * - 主键区的 ，。和左栏一样走符号上屏那条路（打着笔画时先上屏候选再接符号）。
 * - 左栏和九键侧栏同一套，前四个 ，。？！，末尾「编辑」。
 * - 编码默认显示 h s p n z，开关打开时整串换成 一丨丿丶乛。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xhdpi")
class StrokeNextCandidateTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val stroke = InputProfile(InputLanguage.CHINESE, InputScheme.STROKE, InputLayout.STROKE, EngineProfile.Rime("stroke"))
    private val composing = CandidateState(candidates = listOf("骆驼", "络腮", "络"), candidateComments = listOf("", "", "zh"),
        inputText = "zzhpzns'", preeditText = "zzhpzns'", isComposing = true, engineRevision = 3)

    private val keys = mutableListOf<String>()
    private val literals = mutableListOf<String>()
    private val nexts = mutableListOf<CandidateState>()
    private val previous = mutableListOf<CandidateState>()

    private fun show(state: CandidateState) {
        AppFonts.initialize(rule.activity.application)
        KeysConfigHelper.loadConfig(rule.activity.application)
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onNextCandidate = { nexts += it }, onPreviousCandidate = { previous += it })
        val candidateState = mutableStateOf(state)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(),
                LocalKeyboardInputActions provides KeyboardInputActions(onCommitText = { literals += it })) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 260.dp)) {
                        StrokeKeyboardLayout({ keys += it }, Color.DarkGray, Color.White, Color.Gray,
                            modifier = Modifier.requiredSize(360.dp, 260.dp),
                            candidateState = candidateState, callbacks = callbacks, inputProfile = stroke)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, true).performTouchInput { down(center); up() }
        rule.waitForIdle()
    }

    @Test fun strokeUsesNextCandidateButKeepsItsOwnCode() {
        assertTrue(composing.usesT9CandidateNavigation(stroke))
        assertFalse(CandidateState().usesT9CandidateNavigation(stroke))
        val second = composing.advanceT9Candidate(stroke)
        assertEquals(1, second.spaceCandidateIndex(stroke))
        // 笔画的编码不是拼音：换了高亮也不按九键读音改编码。
        assertEquals("zzhpzns'", second.shownPreedit(stroke))
        // 别的方案放在笔画布局上（第三方）不算。
        assertFalse(composing.usesT9CandidateNavigation(stroke.copy(scheme = InputScheme.WUBI)))
    }

    @Test fun zeroKeyIsNextWhileTypingAndZeroWhenIdle() {
        show(composing)
        tap("stroke-zero-key")
        assertEquals(1, nexts.size)
        assertTrue("打着笔画时 0 键不打 0", "0" !in keys)
    }

    @Test fun zeroKeyTypesZeroWhenIdle() {
        show(CandidateState())
        tap("stroke-zero-key")
        assertEquals(listOf("0"), keys)
        assertTrue(nexts.isEmpty())
    }

    @Test fun commaAndPeriodKeysCommitLikeTheSideRailStrokesStayCodes() {
        show(CandidateState())
        tap("stroke-key:，")
        tap("stroke-key:。")
        tap("stroke-key:一")
        tap("stroke-key:*")
        tap("stroke-key:分词")
        assertEquals(listOf("，", "。"), literals)
        assertEquals(listOf("h", "*", "word_separator"), keys)
        assertTrue(strokeTapIsLiteral("，") && strokeTapIsLiteral("……"))
        assertFalse(strokeTapIsLiteral("h") || strokeTapIsLiteral("*") || strokeTapIsLiteral("word_separator") || strokeTapIsLiteral("0"))
    }

    @Test fun sideRailStartsWithCommaPeriodQuestionExclamationAndEndsWithEdit() {
        show(CandidateState())
        assertEquals(listOf("，", "。", "？", "！"), KeysConfigHelper.getStrokeSideSymbols().take(4))
        // 超过一屏四格，能上下滑（九键侧栏同款）。
        assertTrue(KeysConfigHelper.getStrokeSideSymbols().size > NineGridFrame.SIDE_VISIBLE_SLOTS)
        assertTrue(rule.onAllNodesWithTag("stroke-side-symbols", true).fetchSemanticsNodes().isNotEmpty())
        tap("stroke-side-symbol:0")
        assertEquals(listOf("，"), literals)
    }

    @Test fun glyphOptionTurnsTheWholeCodeIntoStrokes() {
        assertEquals("⼀⼁'乛⼃⼂*", strokeGlyphs("hs'zpn*"))
        assertEquals("根本就⼂⼀", strokeGlyphs("根本就nh"))
        val on = SpellingPatches.strokeGlyphParts(true).flatMap { it.lines }
        assertTrue(on.any { it.contains("xlit/hspnz/⼀⼁⼃⼂乛/") })
        assertTrue(on.any { it.contains("xform/~//") })
        assertTrue(SpellingPatches.strokeGlyphParts(false).all { it.lines.isEmpty() })
    }
}
