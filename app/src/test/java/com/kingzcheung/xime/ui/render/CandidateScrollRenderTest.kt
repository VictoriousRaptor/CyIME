package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.CandidateEntry
import com.kingzcheung.xime.ui.keyboard.CandidatePage
import com.kingzcheung.xime.ui.keyboard.CandidatePageCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidatePageState
import com.kingzcheung.xime.ui.keyboard.FixedCandidateStrip
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.candidateQwertyGeometry
import com.kingzcheung.xime.ui.keyboard.remainingCandidates
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 候选栏左右滑动、长候选完整显示、展开页「单字/候选」按钮（用户 10-23）。
 * 收起：右边放不下的候选照常显示、被栏边裁掉，滑过去看全；展开：放不全的长候选在展开页里折行显示全文。
 * 主题 × 玻璃 × 26 键/九键，用真实 CandidateBar / CandidatePage 代码出图。只在设了 CYIME_RENDER_OUT 时出图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1300dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateScrollRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val themes = listOf(
        Triple("soft_blue", false, "柔和蓝·浅"), Triple("soft_blue", true, "柔和蓝·深"),
        Triple("pure_black", false, "黑白·纯白"), Triple("pure_black", true, "黑白·纯黑"),
    )
    private val long = "这个超级长的候选词用来测试能不能完整显示出来而不是被省略号截断掉"
    private val mixed = listOf("这到底", "是什么", "原理", "这到底是什么原理呀朋友们", "折倒", "这道", "浙", "这", "着", "者", "哲", "这道题")
    private val longFirst = listOf(long, "这到底", "是什么", "原理", "折倒", "这道", "浙", "这", "着", "者", "哲", "这道题",
        "折", "遮", "辙", "浙江", "这样子", "这是一个也挺长的候选词组啊", "这道理", "这一刀")

    @Test fun edgeCandidateRemainsSelectableWhenOnlyItsTapPaddingIsClipped() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeyboardThemes.initFromConfig(app)
        val words = listOf("测", "侧", "策", "CEF", "册")
        var width by mutableStateOf(320.dp)
        var selected = -1
        var visible = emptyList<String>()
        rule.setContent {
            FixedCandidateStrip(words, comments = emptyList(),
                visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                callbacks = CandidateBarCallbacks(onCandidateSelect = { selected = it },
                    onVisibleCandidatesChanged = { visible = it }),
                fontSize = 19.sp, modifier = Modifier.width(width).height(44.dp),
                scrollable = true, itemPadding = 10.dp)
        }
        val item = rule.onNodeWithTag("bar-candidate:3", useUnmergedTree = true)
        val strip = rule.onNodeWithTag("candidate-scroll-row", useUnmergedTree = true)
        val end = with(rule.density) {
            (item.fetchSemanticsNode().boundsInRoot.right - strip.fetchSemanticsNode().boundsInRoot.left).toDp()
        }
        rule.runOnIdle { width = end - 5.dp }
        item.performClick()
        System.getenv("CYIME_RENDER_OUT")?.let { shot(it, "cef-at-edge", "candidate-scroll-row") }
        val actualWidth = strip.fetchSemanticsNode().boundsInRoot.width
        rule.runOnIdle {
            org.junit.Assert.assertEquals(3, selected)
            org.junit.Assert.assertEquals("requested $width, strip $actualWidth px", words.take(4), visible)
            width = end - 15.dp
        }
        // 字被右边栏边切掉一截：照常显示，不藏起来（用户 11-11）；展开页仍从它开始排。
        item.assertExists()
        rule.runOnIdle { org.junit.Assert.assertEquals(words.take(3), visible) }
    }

    /** 用户 11-11：h'h'h'h'h'h'h'h 第一个候选很长，第二个只放得下一半，原先藏起来、要拖一下才出来。 */
    @Test fun candidateCutAtRightEdgeShowsWithoutDragging() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeyboardThemes.initFromConfig(app)
        val words = listOf("哈哈哈哈哈哈哈哈", "呵呵哈哈哈哈哈哈", "哈哈哈")
        var width by mutableStateOf(900.dp)
        var visible = emptyList<String>()
        rule.setContent {
            FixedCandidateStrip(words, comments = emptyList(),
                visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onVisibleCandidatesChanged = { visible = it }),
                fontSize = 19.sp, modifier = Modifier.width(width).height(44.dp),
                scrollable = true, itemPadding = 10.dp)
        }
        val second = rule.onNodeWithTag("bar-candidate:1", useUnmergedTree = true)
        val strip = rule.onNodeWithTag("candidate-scroll-row", useUnmergedTree = true)
        val left = strip.fetchSemanticsNode().boundsInRoot.left
        val bounds = second.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { width = with(rule.density) { ((bounds.left + bounds.right) / 2f - left).toDp() } }
        rule.waitForIdle()
        second.assertExists().assertIsDisplayed()
        rule.runOnIdle { org.junit.Assert.assertEquals(words.take(1), visible) }
    }

    @Test fun renderCandidateScroll() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(app)
        var scene by mutableStateOf("collapsed")
        var glass by mutableStateOf(false)
        var filter by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.background(Color(0xFF808080)).padding(4.dp).testTag("shot")) {
                    if (scene == "collapsed") for (g in listOf(false, true)) for ((id, dark, label) in themes) {
                        for (words in listOf(mixed, longFirst)) Row(Modifier.padding(vertical = 2.dp)) {
                            Text("$label ${if (g) "玻璃" else "不透明"}", fontSize = 10.sp, color = Color.White,
                                modifier = Modifier.width(100.dp))
                            Themed(id, dark, g, Modifier.width(400.dp).height(44.dp)) { visuals ->
                                CandidateBar(
                                    state = CandidateBarState.ChineseCandidates(words, inputText = "zhedaodi", hasMore = true),
                                    visuals = visuals,
                                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onShowMoreCandidates = {}),
                                    showPreeditPreview = false, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else for ((id, dark, label) in themes) Row(Modifier.padding(vertical = 2.dp)) {
                        Text("$label ${if (glass) "玻璃" else "不透明"}", fontSize = 10.sp, color = Color.White,
                            modifier = Modifier.width(60.dp))
                        for (t9 in listOf(false, true)) Themed(id, dark, glass, Modifier.padding(end = 4.dp).width(400.dp).height(290.dp)) { visuals ->
                            ExpandedScene(visuals, t9, filter)
                        }
                    }
                }
            }
        }
        File(out!!).mkdirs()
        fun shot(name: String) = shot(out, name, "shot")
        shot("collapsed-rest")
        val rows = rule.onAllNodesWithTag("candidate-scroll-row")
        for (i in rows.fetchSemanticsNodes().indices) rows[i].performTouchInput { swipeLeft(durationMillis = 400) }
        shot("collapsed-scrolled")
        for (g in listOf(false, true)) for (f in listOf(false, true)) {
            rule.runOnIdle { scene = "expanded"; glass = g; filter = f }
            shot("expanded-${if (g) "glass" else "solid"}${if (f) "-singlechar" else ""}")
        }
    }

    private fun shot(out: String, name: String, tag: String) {
        rule.waitForIdle()
        val body = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().first().boundsInRoot
        val root = rule.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(full)) }
        val crop = Bitmap.createBitmap(full, body.left.toInt().coerceAtLeast(0), body.top.toInt().coerceAtLeast(0),
            body.width.toInt().coerceAtMost(full.width), body.height.toInt().coerceAtMost(full.height))
        File(out, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Themed(id: String, dark: Boolean, glass: Boolean, modifier: Modifier,
                       content: @Composable (CandidateBarVisuals) -> Unit) {
        val palette = resolveKeyboardPalette(id, dark)
        val config = FrostedGlassConfig.defaults(dark, enabled = glass)
        Box(modifier
            .then(if (glass) Modifier.background(Brush.linearGradient(
                listOf(Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828), Color(0xFF1565C0)))) else Modifier)
            .keyboardBackground(KeyboardThemes.getRenderingScheme(id).keyboardBackground, dark, palette.background, config)) {
            CompositionLocalProvider(LocalKeyboardPalette provides palette,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = config)) {
                content(CandidateBarVisuals(Color.Transparent, palette.candidateText,
                    if (dark) Color(0xFF3C4043) else Color(0xFFDADCE0), palette.accent, palette.selectedText, dark, palette.key))
            }
        }
    }

    /** 和键盘里一样：候选栏实际显示了哪几个，展开页就从后面接着排。 */
    @Composable
    private fun ExpandedScene(visuals: CandidateBarVisuals, t9: Boolean, filter: Boolean) {
        var visible by remember { mutableStateOf(emptyList<String>()) }
        val all = longFirst.mapIndexed { i, w -> CandidateEntry(w, "zhe", i) }
        val entries = (if (filter) all.filter { it.text.codePointCount(0, it.text.length) == 1 } else all)
        Column {
            CandidateBar(
                state = CandidateBarState.ChineseCandidates(entries.map { it.text }, inputText = "zhe", hasMore = true),
                candidatePageExpanded = true,
                visuals = visuals,
                callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onBack = {},
                    onVisibleCandidatesChanged = { visible = it }),
                showPreeditPreview = false, modifier = Modifier.fillMaxWidth())
            val palette = LocalKeyboardPalette.current!!
            CandidatePage(
                state = CandidatePageState(
                    candidates = remainingCandidates(entries, visible),
                    backgroundColor = Color.Transparent, textColor = visuals.textColor, keyBackgroundColor = palette.key,
                    singleCharFilter = filter, railPinyinOptions = if (t9) listOf("zhe", "ze") else emptyList(),
                    railAccentColor = visuals.accentColor,
                    matchT9Geometry = t9,
                    qwertyGeometry = if (t9) null else candidateQwertyGeometry(KeysConfigHelper.getKeyRows(false),
                        fourteenKey = false, custom = false, split = false),
                ),
                callbacks = CandidatePageCallbacks(onCandidateSelect = {}, onToggleSingleCharFilter = {}),
                modifier = Modifier.fillMaxWidth().height(246.dp),
            )
        }
    }
}
