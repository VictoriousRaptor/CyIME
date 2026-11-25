package com.kingzcheung.xime.ui.render

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalPreeditStripRow
import com.kingzcheung.xime.ui.keyboard.PREEDIT_STRIP_ROW_DP
import com.kingzcheung.xime.ui.keyboard.snapPreeditTap
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 拼音编辑「点哪改哪」（11-23 用户反馈：点开编辑时光标总在最后；分词间隔小，手指容易点错）：
 * 点屏上的拼音，打开编辑时带上点在第几个字母后面；点在音节分界附近时落在分界上，看得到的间距不变。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreeditTapTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    //                                           0123456789012345678901
    private val typing = CandidateBarState.ChineseCandidates(listOf("我们是好朋友", "我们", "我"),
        inputText = "womenshihaopengyou", preeditText = "wo men shi hao peng you", hasMore = true)

    @Test fun tappingThePinyinTellsWhichLetterItLandedAfter() {
        KeyboardThemes.initFromConfig(context)
        val opened = mutableListOf<Int?>()
        rule.setContent {
            val palette = resolveKeyboardPalette("soft_blue", false)
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(200.dp))
                Box(Modifier.width(400.dp).background(Color.White)) {
                    CompositionLocalProvider(LocalKeyboardPalette provides palette,
                        LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                            preeditStyle = SettingsPreferences.PREEDIT_STYLE_STRIP),
                        LocalPreeditStripRow provides PREEDIT_STRIP_ROW_DP.dp) {
                        CandidateBar(state = typing,
                            visuals = CandidateBarVisuals(Color.Transparent, palette.candidateText, Color.Gray, palette.accent, palette.selectedText),
                            callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {}, onShowMoreCandidates = {}),
                            onEditPreedit = { opened += it }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        val strip = rule.onNodeWithTag("preedit-strip").fetchSemanticsNode().boundsInWindow
        val textNode = rule.onNodeWithTag("preedit-tab-text", true).fetchSemanticsNode()
        val text = textNode.boundsInWindow
        val layouts = mutableListOf<TextLayoutResult>()
        textNode.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val dp = with(rule.density) { 1.dp.toPx() }
        fun tapAt(offsetX: Float) {
            rule.onNodeWithTag("preedit-strip").performTouchInput {
                click(Offset(text.left - strip.left + offsetX, strip.height / 2f))
            }
            rule.waitForIdle()
        }
        // peng 的 e、n 之间：离两边分界都远，照字落点 → 前面 13 个字母（wo men shi hao pe）。
        tapAt(layout.getHorizontalPosition(17, true))
        // hao 的 o 刚进去一点：离「hao|peng」分界不到半个指尖，落在分界上 → 前面 11 个字母，不是 10 个。
        tapAt(layout.getHorizontalPosition(13, true) + 1 * dp)
        // 拼音最前面的空白处 → 0。
        tapAt(-4 * dp)
        assertEquals(listOf<Int?>(13, 11, 0), opened)
    }

    @Test fun editorTapSnapsOnlyNearASyllableBoundary() {
        var layout: TextLayoutResult? = null
        rule.setContent {
            Text("wo'men'shi'hao", fontSize = 18.sp, modifier = Modifier.testTag("code"), onTextLayout = { layout = it })
        }
        rule.waitForIdle()
        val result = layout!!
        val slop = with(rule.density) { 10.dp.toPx() }
        val y = result.size.height / 2f
        val apostrophe = { c: Char -> c == '\'' }
        // m|en 的 e 中间：离两边隔音符都远，照字落点。
        val midE = (result.getHorizontalPosition(4, true) + result.getHorizontalPosition(5, true)) / 2f
        assertEquals(result.getOffsetForPosition(Offset(midE, y)), snapPreeditTap(result, Offset(midE, y), slop, apostrophe))
        // men 的 n 后半截，紧挨 men' 的隔音符：落在分界上（n 后面）。
        val nearBoundary = result.getHorizontalPosition(6, true) - 1f
        assertEquals(6, snapPreeditTap(result, Offset(nearBoundary, y), slop, apostrophe))
        // 末尾后面：落在末尾。
        assertEquals(14, snapPreeditTap(result, Offset(result.size.width + 20f, y), slop, apostrophe))
    }
}
