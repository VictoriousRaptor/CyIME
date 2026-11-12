package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * 滑行输入：英文滑行和中文滑行（全拼、双拼 26 键划一个词的编码）是两个开关，中文默认关；
 * 用户 10-31：滑行开着也照常画角标（听两个角标提示开关），开着英文滑行的老用户中文键盘不能变。
 * 用户 11-11：滑行接管的字母键下滑用来滑词，长按弹出上下两个角标来选（withGlideCornerMenu），所以上下角标照样都画。
 * 竖着滑不出角标符号（拖动给了滑行）；14 键不滑行。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class GlideCornerHintTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val typed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val codes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val previews: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val englishCandidates: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    private var schema by mutableStateOf("rime_ice")
    private var ascii by mutableStateOf(false)
    private var boardWidth by mutableStateOf(400)
    private var prefs by mutableStateOf(KeyboardInputPreferences())

    private fun show(glide: Boolean = true, chinese: Boolean = true, preview: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        prefs = KeyboardInputPreferences(glideTyping = glide, chineseGlideTyping = chinese, glidePreview = preview)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true, LocalKeyboardInputPreferences provides prefs) {
                MaterialTheme {
                    Box(Modifier.requiredSize(boardWidth.dp, 260.dp)) {
                        KeyboardLayout({ typed += it }, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onGlideWord = { words, _ -> codes += "en:${words.first().word}" }, onGlideCode = { code, _, _ -> codes += code },
                            onGlideEnglishPreview = { shown, _ -> previews += shown.text; englishCandidates += shown.candidates },
                            onGlidePreviewCode = { code, _, _ -> previews += code }, onGlidePreviewEnd = { previews += "" }),
                            KeyboardUiState(currentSchemaId = schema, isAsciiMode = ascii), ascii, Modifier.requiredSize(boardWidth.dp, 260.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun use(id: String, english: Boolean = false) {
        KeysConfigHelper.setActiveKeyboardSchema(id)
        KeysConfigHelper.applyLetterLayouts()
        rule.runOnIdle { schema = id; ascii = english }
        rule.waitForIdle()
    }

    @After fun reset() {
        KeysConfigHelper.setActiveKeyboardSchema("")
        KeysConfigHelper.applyLetterLayouts()
    }

    /** 键面上除键名以外画出来的字（角标）。 */
    private fun hints(key: String): List<String> {
        val tag = "qwerty-key:$key"
        return rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
            .filter { it.isNotEmpty() && !it.equals(key, ignoreCase = true) }
    }

    private fun pullDown(key: String): List<String> {
        typed.clear()
        rule.onNodeWithTag("qwerty-key:$key", true).performTouchInput {
            down(center)
            for (step in 1..7) moveTo(center + Offset(0f, 70.dp.toPx() * step / 7f))
            up()
        }
        rule.waitForIdle()
        return typed.toList()
    }

    @Test fun glidingBoardsKeepCornerHintsButNeverTypeThemOnADrag() {
        show()
        for ((id, english) in listOf("rime_ice" to false, "double_pinyin_flypy" to false, "rime_ice" to true)) {
            use(id, english)
            for (key in listOf("q", "f", "m")) {
                assertTrue("$id english=$english $key 角标照常", hints(key).isNotEmpty())
                assertTrue("$id english=$english $key 滑行时不提交角标", pullDown(key).isEmpty())
            }
        }
    }

    @Test fun cornerHintsStayWithoutGlide() {
        show(glide = false, chinese = false)
        use("rime_ice")  // 默认空方案的测试布局 q 只配了一个角标；按真实中文 26 键布局数
        assertEquals("没开滑行：上下角标都画", 2, hints("q").size)
        assertEquals(1, pullDown("q").size)
    }

    @Test fun glideOwnedLettersKeepBothCorners() {
        show(glide = false, chinese = false)
        val boards = listOf("rime_ice" to false, "double_pinyin_flypy" to false, "rime_ice" to true)
        fun snapshot() = boards.flatMap { (id, english) -> use(id, english); listOf("q", "f", "m").map { "$id/$english/$it" to hints(it) } }
        val plain = snapshot()
        plain.forEach { (key, shown) -> assertEquals("$key 没开滑行：上下两个角标 $shown", 2, shown.size) }
        rule.runOnIdle { prefs = prefs.copy(glideTyping = true, chineseGlideTyping = true) }
        rule.waitForIdle()
        // 用户 11-11「关闭数字行，滑行打不出 !@#」：下角标在长按弹出的两格里选得到，键上照样画，开关滑行位置不变。
        assertEquals("开着滑行，上下角标照样都画", plain, snapshot())
        rule.runOnIdle { prefs = prefs.copy(glideTyping = false, chineseGlideTyping = false) }
        rule.waitForIdle()
        assertEquals("关掉滑行，下角标照旧显示", plain, snapshot())
    }

    @Test fun englishGlideAloneLeavesTheChineseBoardAsBefore() {
        show(glide = true, chinese = false)
        use("rime_ice")
        glide("nihao")
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        assertEquals("中文滑行没开，中文 26 键不滑行", emptyList<String>(), codes.toList())
        assertEquals("中文 26 键没滑行，下滑照样出下角标，上下角标都画", 2, hints("q").size)
    }

    @Test fun fourteenKeysDoNotGlideAndKeepTheirHints() {
        show()
        use("pinyin_14jian")
        val first = KeysConfigHelper.getKeyRows(false).flatten().first { key -> key.isNotEmpty() && key.all { it in 'a'..'z' } }
        assertTrue("14 键不滑行，角标照旧", hints(first).isNotEmpty())
    }

    private fun glide(word: String, lift: Boolean = true, pause: Long = 0) {
        val host = rule.onRoot().fetchSemanticsNode().boundsInRoot
        fun letter(char: Char) = rule.onNodeWithTag("qwerty-key:$char", true).fetchSemanticsNode().boundsInRoot.center - host.topLeft
        val path = word.map(::letter)
        rule.onRoot().performTouchInput {
            down(path.first())
            path.zipWithNext().forEach { (a, b) -> for (step in 1..6) moveTo(a + (b - a) * (step / 6f)) }
            if (pause > 0) advanceEventTime(pause)
            if (lift) up()
        }
    }

    /**
     * 用户 11-11「先打一个字再划…划了就没了」：快打时点字母的手指还没抬，另一根手指已经开始划。
     * 以前看到第二根手指就整次放弃，这一划什么也不出；现在点的字母照常出，划的词接在后面。
     * [liftTapAt] 是划到第几步时抬起点字母的手指（超出步数就等划完再抬；点按的手指按满长按时长会出长按的字，所以不拖太久）。
     */
    private fun tapThenGlide(tap: Char, word: String, liftTapAt: Int) {
        val host = rule.onRoot().fetchSemanticsNode().boundsInRoot
        fun letter(char: Char) = rule.onNodeWithTag("qwerty-key:$char", true).fetchSemanticsNode().boundsInRoot.center - host.topLeft
        val path = word.map(::letter)
        rule.onRoot().performTouchInput {
            down(0, letter(tap))
            advanceEventTime(30)
            down(1, path.first())
            var step = 0
            var tapDown = true
            path.zipWithNext().forEach { (a, b) ->
                for (i in 1..6) {
                    moveTo(1, a + (b - a) * (i / 6f))
                    if (++step == liftTapAt) { up(0); tapDown = false }
                }
            }
            up(1)
            if (tapDown) { advanceEventTime(30); up(0) }
        }
    }

    @Test fun glideRightAfterATapWithTheOtherThumbStillGlides() {
        show(glide = true, chinese = true)
        for (liftTapAt in listOf(1, 4, 12)) {
            use("rime_ice")
            rule.runOnIdle { typed.clear(); codes.clear() }
            tapThenGlide('w', "nihao", liftTapAt)
            rule.waitUntil(5000) { codes.isNotEmpty() }
            rule.waitForIdle()
            assertEquals("中文 抬起点按的手指在第 $liftTapAt 步", listOf("nihao"), codes.toList())
            assertEquals("中文 点的字母照常出", listOf("w"), typed.toList())

            use("rime_ice", english = true)
            rule.runOnIdle { typed.clear(); codes.clear() }
            tapThenGlide('i', "hello", liftTapAt)
            rule.waitUntil(5000) { codes.isNotEmpty() }
            rule.waitForIdle()
            assertEquals("英文 抬起点按的手指在第 $liftTapAt 步", 1, codes.size)
            assertTrue(codes.first().startsWith("en:"))
            assertEquals("英文 点的字母照常出", listOf("i"), typed.toList())
        }
    }

    @Test fun glideRightAfterATapAlsoWorksWithLivePreview() {
        show(glide = false, chinese = true, preview = true)
        tapThenGlide('w', "nihao", 3)
        rule.waitUntil(5000) { codes.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf("nihao"), codes.toList())
        assertEquals(listOf("w"), typed.toList())
    }

    /** 按下期间把预览送往输入框，抬手时清除预览，再交付最终编码。 */
    @Test fun livePreviewShowsTheWordBeforeLiftingAndGoesAwayAfter() {
        show(glide = false, chinese = true, preview = true)
        glide("nihao", lift = false)
        // 第一次识别在刚确认是滑行时开始（只划过 ni），手指停下后最迟再隔一次识别就跟上整条轨迹。
        rule.waitUntil(5000) { previews.contains("nihao") }
        assertTrue(rule.onAllNodes(hasTestTag("glide-preview"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue("还没抬手，不上屏", codes.isEmpty())
        // 预览是单独的小窗（另一个根），抬手要落在键盘那个根上。
        rule.onAllNodes(isRoot())[0].performTouchInput { up() }
        rule.waitUntil(5000) { codes.isNotEmpty() }
        assertEquals(listOf("nihao"), codes.toList())
        rule.waitForIdle()
        assertEquals("", previews.last())
    }

    @Test fun noLivePreviewWhileTheSwitchIsOff() {
        show(glide = false, chinese = true, preview = false)
        glide("nihao")
        rule.waitUntil(5000) { codes.isNotEmpty() }
        assertTrue(previews.none { it.isNotEmpty() })
    }

    @Test fun englishGlideProvidesCandidatesBeforeRelease() {
        show(glide = true, chinese = false, preview = true)
        use("rime_ice", english = true)
        glide("hello", lift = false)
        rule.waitUntil(5000) { englishCandidates.any { it.isNotEmpty() } }
        assertTrue(codes.isEmpty())
        rule.onRoot().performTouchInput { up() }
        rule.waitUntil(5000) { codes.isNotEmpty() }
        assertTrue(codes.first().startsWith("en:"))
    }

    @Test fun chineseGlideTypesThePinyinOfOneWord() {
        show(glide = false, chinese = true)
        glide("nihao")
        rule.waitUntil(5000) { codes.isNotEmpty() }
        assertEquals(listOf("nihao"), codes.toList())
        assertEquals("滑行不出单个字母", emptyList<String>(), typed.toList())
    }

    @Test fun deShortGlideWorksOnNormalAndOneHandBoardsWithoutTypingACorner() {
        show(glide = false, chinese = true)
        for (width in listOf(400, 240)) {
            rule.runOnIdle { boardWidth = width; typed.clear(); codes.clear() }
            rule.waitForIdle()
            glide("de")
            rule.waitUntil(5000) { codes.isNotEmpty() }
            assertEquals("width=$width", listOf("de"), codes.toList())
            assertEquals("短滑行不额外输入 d 或角标", emptyList<String>(), typed.toList())
        }
    }
}
