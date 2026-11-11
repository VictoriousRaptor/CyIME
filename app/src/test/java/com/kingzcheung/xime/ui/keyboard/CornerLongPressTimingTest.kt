package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.ColemakLayout
import com.kingzcheung.xime.settings.Cyletix10Layout
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.LetterLayouts
import com.kingzcheung.xime.settings.TypewiseLayout
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config

/**
 * 关闭角标滑动（「长按输入」）时的长按（用户 11-08）：
 * - 时长按设置项走，不再写死 0.3 秒；
 * - 按满时长立刻输入角标，不等松手，松手不再输入第二次；
 * - 气泡在长按到点时显示输入的角标，松手消失；
 * - 26/14/17/18 键、各种字母排列、中英文、双拼、九键（拂动开关两种）都一样，开着滑行输入也一样。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class CornerLongPressTimingTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val typed = mutableListOf<String>()
    private var preferences by mutableStateOf(KeyboardInputPreferences())
    private var schema by mutableStateOf("rime_ice")
    private var ascii by mutableStateOf(false)

    private data class Board(val schema: String, val english: Boolean = false, val englishArrangement: String? = null)
    private val layouts = listOf(Board("rime_ice"), Board("double_pinyin_flypy"), Board("rime_ice", english = true),
        Board(Cyletix10Layout.ID), Board(ColemakLayout.ID), Board(TypewiseLayout.ID),
        Board("rime_ice", english = true, englishArrangement = Cyletix10Layout.ID),
        Board("pinyin_14jian"), Board("double_pinyin_flypy_14jian"), Board("pinyin_17jian"), Board("pinyin_18jian"))

    /** 设成 0.5 秒：比原来的 0.3 秒长，0.4 秒时还不能出字，证明不是写死的时长。 */
    private val delaySeconds = 0.5f

    private fun app() = ApplicationProvider.getApplicationContext<Application>().also { app ->
        KeysConfigHelper.loadConfig(app)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
    }

    /** [glide]：接上滑行回调，键盘才真的开滑行（同 GlideCornerHintTest）；只开设置项、没有回调时滑行不生效。 */
    private fun showLetters(glide: Boolean = false) {
        val app = app()
        val vm = KeyboardViewModel(app)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true, LocalKeyboardInputPreferences provides preferences) {
                MaterialTheme {
                    Box(Modifier.requiredSize(400.dp, 260.dp)) {
                        KeyboardLayout({ typed += it }, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onGlideWord = if (glide) ({ words, _ -> typed += "glide:${words.first().word}" }) else null,
                            onGlideCode = if (glide) ({ code, _, _ -> typed += "glide:$code" }) else null),
                            KeyboardUiState(currentSchemaId = schema), ascii, Modifier.requiredSize(400.dp, 260.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun use(board: Board) {
        LetterLayouts.setForTest(InputLanguage.ENGLISH, board.englishArrangement)
        KeysConfigHelper.setActiveKeyboardSchema(board.schema)
        KeysConfigHelper.applyLetterLayouts()
        rule.runOnIdle { schema = board.schema; ascii = board.english }
        rule.waitForIdle()
    }

    @After fun reset() {
        rule.mainClock.autoAdvance = true
        LetterLayouts.setForTest(InputLanguage.ENGLISH, null)
        KeysConfigHelper.setActiveKeyboardSchema("")
        KeysConfigHelper.applyLetterLayouts()
    }

    /** 键面右上（比中线高）画的角标，同 CornerHintGestureTest。 */
    private fun topHint(tag: String, key: String): String? {
        val center = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot.center.y
        return rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?.takeIf { it.isNotEmpty() && !it.equals(key, ignoreCase = true) }?.let { it to node.boundsInRoot.center.y }
            }.firstOrNull { (_, y) -> y < center }?.first
    }

    private fun popup() = rule.onNodeWithTag("key-choice-popup", useUnmergedTree = true)
    /** 14 键、九键拂动的方向盘（默认开）：按住不动时也算气泡，到点前不能弹（用户 11-09）。 */
    private fun disc() = rule.onNodeWithTag("letter-flick-preview", useUnmergedTree = true)

    /**
     * 按住 [key]：到点前不出字、不弹方向盘；按键浮标开着时按下就出（[keyBubble]），关着时到点前没有气泡；
     * 到点时（手还按着）恰好输入 [expected] 一次、气泡换成角标；松手后不再多出字，气泡消失。
     * 关着按键浮标也要出角标气泡，证明长按的气泡不靠它。
     */
    private fun holdAndCheck(id: String, key: SemanticsNodeInteraction, expected: String?, keyBubble: Boolean = false,
        output: () -> List<String>) {
        val before = output().size
        rule.mainClock.autoAdvance = false
        key.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(32)
        if (keyBubble) popup().assertExists("$id 按下应立即显示按键浮标") else popup().assertDoesNotExist()
        rule.mainClock.advanceTimeBy((delaySeconds * 1000).toLong() - 132)
        assertEquals("$id 没到设定时长不能出字", before, output().size)
        if (keyBubble) popup().assertExists("$id 按住时按键浮标一直在") else popup().assertDoesNotExist()
        disc().assertDoesNotExist()
        rule.mainClock.advanceTimeBy(200)
        assertEquals("$id 到点立刻输入（还没松手）", listOfNotNull(expected), output().drop(before))
        popup().assertExists("$id 长按到点后应显示输入的角标")
        disc().assertDoesNotExist()
        key.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals("$id 松手不再输入", listOfNotNull(expected), output().drop(before))
        popup().assertDoesNotExist()
    }

    private fun letterKeys(english: Boolean) = KeysConfigHelper.getKeyRows(english).flatten()
        .filter { key -> key.isNotEmpty() && key.all { it.lowercaseChar() in 'a'..'z' } }

    private fun checkLetters(everyNth: Int, boards: List<Board> = layouts, keyBubble: Boolean = false) {
        for (board in boards) {
            use(board)
            letterKeys(board.english).filterIndexed { index, _ -> index % everyNth == 0 }.forEach { key ->
                val tag = "qwerty-key:$key"
                val top = topHint(tag, key)
                assertTrue("$board $key 缺上角标", top != null)
                holdAndCheck("$board $key", rule.onNodeWithTag(tag, true), top, keyBubble) { typed.toList() }
            }
        }
    }

    @Test fun lettersCommitTheTopHintAtTheConfiguredDelayOnEveryLayout() {
        preferences = KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS, symbolLongPressSeconds = delaySeconds)
        showLetters()
        checkLetters(everyNth = 1)
    }

    @Test fun lettersShowTheKeyBubbleOnPressThenTheHintAtTheDelay() {
        // 按键浮标（用户 11-09 改回默认开）：按下就显示按的键，到点换成输入的角标，松手消失。
        preferences = KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
            symbolLongPressSeconds = delaySeconds, showPressBubble = true)
        showLetters()
        checkLetters(everyNth = 4, keyBubble = true)
    }

    @Test fun lettersBehaveTheSameWithGlideTypingOn() {
        // 滑行锁住字母键的滑动、角标锁成长按输入（99a2c07 和设置锁），长按这条路必须照常；
        // 存的仍是「滑动输入」的老用户也一样按设定时长。
        for (stored in listOf(SymbolInputMode.LONG_PRESS, SymbolInputMode.SWIPE_UP)) {
            preferences = KeyboardInputPreferences(symbolInputMode = stored, symbolLongPressSeconds = delaySeconds,
                glideTyping = true, chineseGlideTyping = true)
            if (stored == SymbolInputMode.LONG_PRESS) showLetters(glide = true) else rule.waitForIdle()
            // 存「滑动输入」时只有滑行管得到的 26 键字母（全拼、双拼、英文）被锁成长按；14/17/18 键的字母对仍是滑动。
            checkLetters(everyNth = 3, boards = if (stored == SymbolInputMode.LONG_PRESS) layouts else layouts.take(3))
        }
    }

    // ─── 九键 ───────────────────────────────────────────────

    private val committed = mutableListOf<String>()
    private var ninePrefs by mutableStateOf(KeyboardInputPreferences())
    private var nineShown = false

    /** 一个测试里只能 setContent 一次：之后换设置只改 [ninePrefs]。 */
    private fun showNine(prefs: KeyboardInputPreferences) {
        if (nineShown) {
            rule.runOnIdle { ninePrefs = prefs }
            rule.waitForIdle()
            return
        }
        nineShown = true
        ninePrefs = prefs
        app()
        val controller = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
        }
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onCommitText = { committed += it }, inputAdmissionTicket = { 1L })
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides ninePrefs) {
                Column(Modifier.width(380.dp)) {
                    T9KeyboardLayout({ committed += it }, callbacks, KeyboardUiState(currentSchemaId = "t9_pinyin"),
                        controller, Color.DarkGray, Color.White, Color.Gray, modifier = Modifier.height(250.dp))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun checkNine(prefs: KeyboardInputPreferences) {
        showNine(prefs)
        for ((digit, letters) in listOf("1" to "分词", "2" to "ABC", "5" to "JKL", "7" to "PQRS", "9" to "WXYZ")) {
            val key = rule.onAllNodesWithText(letters, ignoreCase = true, useUnmergedTree = true).onFirst()
            holdAndCheck("九键 $digit $prefs", key, digit, prefs.showPressBubble) { committed.toList() }
        }
    }

    @Test fun nineKeyShowsTheKeyBubbleOnPressWithFlickAndDisc() {
        // 以前开着方向盘的九键把按键浮标整个屏蔽了；现在按下就出，到点换数字，方向盘不出。
        checkNine(KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
            symbolLongPressSeconds = delaySeconds, t9FlickLetters = true, flickDirectionDisc = true, showPressBubble = true))
    }

    @Test fun withCornerSwipeOnTheDiscReplacesTheKeyBubbleInsteadOfStackingOnIt() {
        // 角标滑动开着时按住 180 毫秒仍会出方向盘：方向盘出来时按键浮标收掉，同一时间只有一个。
        showNine(KeyboardInputPreferences(symbolInputMode = SymbolInputMode.SWIPE_UP,
            t9FlickLetters = true, flickDirectionDisc = true, showPressBubble = true))
        val key = rule.onAllNodesWithText("JKL", ignoreCase = true, useUnmergedTree = true).onFirst()
        rule.mainClock.autoAdvance = false
        key.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(48)
        popup().assertExists("按下应立即显示按键浮标")
        disc().assertDoesNotExist()
        rule.mainClock.advanceTimeBy(212)
        disc().assertExists("按住一会儿出方向盘")
        popup().assertDoesNotExist()
        key.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        disc().assertDoesNotExist()
        popup().assertDoesNotExist()
    }

    @Test fun nineKeyDigitsCommitAtTheConfiguredDelayWithFlickLetters() {
        // 「长按输入」会顺带开九键拂动：拂动时数字以前是长按弹出、松手才输入，现在和别的布局一样到点输入。
        checkNine(KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
            symbolLongPressSeconds = delaySeconds, t9FlickLetters = true))
    }

    @Test fun nineKeyDigitsCommitAtTheConfiguredDelayWithoutFlickLetters() {
        checkNine(KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
            symbolLongPressSeconds = delaySeconds, t9FlickLetters = false))
    }

    @Test fun nineKeyDigitsBehaveTheSameWithNineKeyGlideOn() {
        for (flick in listOf(true, false)) {
            committed.clear()
            checkNine(KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
                symbolLongPressSeconds = delaySeconds, t9FlickLetters = flick, t9GlideTyping = true))
        }
    }
}
