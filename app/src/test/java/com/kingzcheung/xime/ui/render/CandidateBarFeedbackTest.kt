package com.kingzcheung.xime.ui.render

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 候选栏两端按钮（用户 11-03）：最多两个——左边取消「<」，最右展开；收起键盘只在空闲时有。
 * B站反馈（10-31）的「候选词间距加宽」默认关，关着时样子不变。
 * 设了 CYIME_RENDER_OUT=<目录> 时出对照图 candidate-feedback-0.png（原样）、-1.png（加宽间距），各主题。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateBarFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val association = CandidateBarState.AssociationOnly(listOf("题", "卷", "服", "版", "了", "一下"), hasMore = true)
    private val composing = CandidateBarState.ChineseCandidates(listOf("一下", "题", "卷", "服", "版", "了"),
        inputText = "yixia", hasMore = true)

    @Test fun glidePreviewFollowsTheSelectedCodeLocation() {
        val candidateLocation = androidx.compose.runtime.mutableStateOf(true)
        val preview = androidx.compose.runtime.mutableStateOf("ni hao")
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                inlinePreeditPreview = true,
                inputTextLocation = if (candidateLocation.value) SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                    else SettingsPreferences.INPUT_TEXT_INPUT_BOX)) {
                CandidateBar(state = CandidateBarState.Idle, glidePreviewText = preview.value,
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}))
            }
        }
        rule.onNodeWithText("ni hao").assertIsDisplayed()
        rule.runOnIdle { candidateLocation.value = false }
        rule.onNodeWithText("ni hao").assertDoesNotExist()
        rule.runOnIdle { candidateLocation.value = true; preview.value = "" }
        rule.onNodeWithText("ni hao").assertDoesNotExist()
    }

    @Before fun reset() {
        KeyboardThemes.initFromConfig(context)
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON)
            .remove(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT)
            .remove(SettingsPreferences.KEY_ASSOCIATION_CANCEL_CROSS)
            .remove(SettingsPreferences.KEY_WIDE_CANDIDATE_SPACING).commit()
    }

    private fun toolbarActions() = com.kingzcheung.xime.keyboard.ToolbarButton.entries.take(7).map {
        com.kingzcheung.xime.keyboard.ToolbarAction(com.kingzcheung.xime.keyboard.ToolbarButtonItem.Builtin(it)) {}
    }

    @androidx.compose.runtime.Composable
    private fun Bar(state: CandidateBarState, theme: String = "soft_blue", dark: Boolean = false, glass: Boolean = false,
                    onCancel: () -> Unit = {}, widthDp: Int = 400, actions: Int = 7) {
        val palette = resolveKeyboardPalette(theme, dark)
        val config = FrostedGlassConfig.defaults(dark, enabled = glass)
        Box(Modifier.width(widthDp.dp).height(44.dp)
            .then(if (glass) Modifier.background(Brush.linearGradient(
                listOf(Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828), Color(0xFF1565C0)))) else Modifier)
            .keyboardBackground(KeyboardThemes.getRenderingScheme(theme).keyboardBackground, dark, palette.background, config)) {
            CompositionLocalProvider(LocalKeyboardPalette provides palette,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = config)) {
                CandidateBar(
                    state = state,
                    toolbarActions = toolbarActions().take(actions),
                    visuals = CandidateBarVisuals(Color.Transparent, palette.candidateText,
                        if (dark) Color(0xFF3C4043) else Color(0xFFDADCE0), palette.accent, palette.selectedText, dark, palette.key),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = onCancel, onShowMoreCandidates = {},
                        onClearAssociation = {}, onHideKeyboard = {}, onBack = {}, onLogoClick = {}, onAssociationSelect = {}),
                    showPreeditPreview = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun count(description: String) =
        rule.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun optionalHighlightShowsWhileHoldingEitherEdgeAndClearsOnCancel() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        assertFalse(prefs.getBoolean(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT, false))
        prefs.edit().putBoolean(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT, true).commit()
        SettingsPreferences.setAssociationCancelCross(context, true)
        var clicks = 0
        rule.setContent { Bar(association, onCancel = { clicks++ }) }
        val root = rule.activity.window.decorView
        for (tag in listOf("association-cancel-cross", "candidate-expansion")) {
            val node = rule.onNodeWithTag(tag, useUnmergedTree = true)
            val rect = node.fetchSemanticsNode().boundsInWindow
            fun image(phase: String): Bitmap = rule.runOnIdle {
                val bmp = Bitmap.createBitmap(rect.width.toInt(), rect.height.toInt(), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.translate(-rect.left, -rect.top)
                root.draw(canvas)
                System.getenv("CYIME_RENDER_OUT")?.let { out ->
                    File(out, "$tag-$phase.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                bmp
            }
            val resting = image("resting")
            node.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(300)
            assertFalse("$tag should highlight", resting.sameAs(image("pressed")))
            node.performTouchInput { cancel() }
            rule.mainClock.advanceTimeBy(400)
            assertTrue("$tag should return to rest", resting.sameAs(image("cancelled")))
        }
        assertEquals(0, clicks)
    }
    private fun countTag(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun cancelBounds(description: String = "取消输入") =
        rule.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun narrowBarSharesEdgeTargetsAndWideToolbarStillSpreadsWithCustomActions() {
        val width = androidx.compose.runtime.mutableIntStateOf(360)
        val state = androidx.compose.runtime.mutableStateOf<CandidateBarState>(CandidateBarState.Idle)
        val actions = androidx.compose.runtime.mutableIntStateOf(2)
        rule.setContent { Bar(state.value, widthDp = width.intValue, actions = actions.intValue) }
        for (available in listOf(320, 390, 720, 960)) for (count in listOf(2, 7)) {
            rule.runOnIdle { width.intValue = available; actions.intValue = count; state.value = CandidateBarState.Idle }
            val hide = bounds("toolbar-hide")
            val toolbarWidth = hide.right - bounds("toolbar-leading").left
            rule.runOnIdle { state.value = composing }
            val expand = bounds("candidate-expansion")
            val slot = (toolbarWidth - 8f * rule.density.density) / (count + 2)
            if (available < 600) {
                assertEquals(hide.center.x, expand.center.x, 1f)
                assertEquals(hide.center.y, expand.center.y, 1f)
            } else if (available < 840) {
                // 过渡段：收起键比展开键宽、比等分格窄，随宽度逐渐拉成胶囊。
                assertTrue("收起键开始拉长: ${hide.width} > ${expand.width}", hide.width > expand.width + 1f)
                assertTrue("还没到整格: ${hide.width} < $slot", hide.width < slot - 1f)
            } else {
                assertEquals(slot, hide.width, 1f)
                assertTrue("候选区不为宽工具格留白", expand.width < hide.width)
            }
        }
    }

    @Test fun associationShowsCloseOnTheLeftAndExpandOnTheRight() {
        var cancelled = 0
        rule.setContent { Bar(association, onCancel = { cancelled++ }) }
        rule.waitForIdle()
        // 联想：默认左边和打字时一样是「<」（关闭联想），最右一个展开；没有 ⊗，也没有收起键盘。
        assertEquals(1, count("关闭联想"))
        assertEquals(1, countTag("association-cancel"))
        assertEquals(0, countTag("association-cancel-cross"))
        assertEquals(0, count("取消输入"))
        assertEquals(0, countTag("toolbar-hide"))
        assertEquals(1, countTag("candidate-expansion"))
        val first = bounds("bar-association:0")
        assertTrue("「<」在第一个联想词左边", cancelBounds("关闭联想").right <= first.left + 0.5f)
        assertTrue("展开在联想词右边", bounds("candidate-expansion").left >= first.right - 0.5f)
        rule.onNodeWithContentDescription("关闭联想", useUnmergedTree = true).performClick()
        assertEquals(1, cancelled)
    }

    @Test fun crossSettingTurnsAssociationCloseIntoCircledX() {
        SettingsPreferences.setAssociationCancelCross(context, true)
        var cancelled = 0
        rule.setContent { Column { Bar(association, onCancel = { cancelled++ }); Bar(composing) } }
        rule.waitForIdle()
        // 打开「联想关闭键用叉号」：只有联想时换成 ⊗，打字时仍是「<」。
        assertEquals(1, countTag("association-cancel-cross"))
        assertEquals(0, countTag("association-cancel"))
        assertEquals(1, count("取消输入"))
        rule.onNodeWithContentDescription("关闭联想", useUnmergedTree = true).performClick()
        assertEquals(1, cancelled)
    }

    @Test fun expandButtonStaysAtTheSameRightEdgeWhileTypingAndAssociating() {
        val state = androidx.compose.runtime.mutableStateOf<CandidateBarState>(composing)
        rule.setContent { Bar(state.value) }
        rule.waitForIdle()
        // 打字：左边「<」，不是 ⊗。
        assertEquals(1, count("取消输入"))
        assertEquals(0, count("关闭联想"))
        assertEquals(0, countTag("toolbar-hide"))
        val typing = bounds("candidate-expansion")
        assertTrue("「<」在第一个候选左边", cancelBounds().right <= bounds("bar-candidate:0").left + 0.5f)
        rule.runOnIdle { state.value = association }
        rule.waitForIdle()
        assertEquals(1, count("关闭联想"))
        assertEquals(typing.right, bounds("candidate-expansion").right, 0.5f)
        assertEquals(typing.left, bounds("candidate-expansion").left, 0.5f)
    }

    @Test fun hideKeyboardOnlyWhenThereAreNoCandidates() {
        rule.setContent { Bar(CandidateBarState.Idle) }
        rule.waitForIdle()
        assertEquals(1, countTag("toolbar-hide"))
        assertEquals(0, countTag("candidate-expansion"))
        assertEquals(0, count("取消输入"))
    }

    @Test fun cancelOffLeavesOnlyTheExpandButton() {
        SettingsPreferences.setShowCandidateCancelButton(context, false)
        rule.setContent { Column { Bar(association); Bar(composing) } }
        rule.waitForIdle()
        assertEquals(0, count("取消输入"))
        assertEquals(0, count("关闭联想"))
        assertEquals(0, countTag("toolbar-hide"))
        assertEquals(2, countTag("candidate-expansion"))
    }

    /** 相邻两个候选的起点距离（像素，留白算在候选里）：打字候选和联想各量一次。 */
    private fun gaps(): Pair<Float, Float> {
        fun gap(a: String, b: String): Float {
            val left = rule.onNodeWithTag(a, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val right = rule.onNodeWithTag(b, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return right.left - left.left
        }
        return gap("bar-candidate:1", "bar-candidate:2") to gap("bar-association:0", "bar-association:1")
    }

    @Test fun wideSpacingIsOffByDefaultAndWidensBothRows() {
        assertFalse(SettingsPreferences.isWideCandidateSpacing(context))
        rule.setContent { Column { Bar(composing); Bar(association) } }
        rule.waitForIdle()
        val (typingBefore, associationBefore) = gaps()
        rule.runOnIdle { SettingsPreferences.setWideCandidateSpacing(context, true) }
        rule.waitForIdle()
        val (typingAfter, associationAfter) = gaps()
        // 默认 19sp：每侧 4dp → 4 + 5.7dp，候选之间多出约 11dp（这里 hdpi，1dp = 1.5px）。
        val extra = 2 * 19f * 0.3f * rule.density.density
        assertEquals(typingBefore + extra, typingAfter, 2f)
        assertEquals(associationBefore + extra, associationAfter, 2f)
    }

    @Test fun renderCandidateFeedback() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(context)
        val themes = listOf(Triple("soft_blue", false, "柔和蓝·浅"), Triple("soft_blue", true, "柔和蓝·深"),
            Triple("pure_black", false, "黑白·纯白"), Triple("pure_black", true, "黑白·纯黑"))
        val variants = listOf("原样" to false, "加宽间距" to true)
        val root = rule.activity.window.decorView
        val shown = androidx.compose.runtime.mutableIntStateOf(-1)
        rule.setContent {
            val index = shown.intValue
            if (index >= 0) androidx.compose.runtime.key(index) {
                Column(Modifier.background(Color(0xFF808080)).padding(4.dp)) {
                    for (glass in listOf(false, true)) for ((id, dark, label) in themes) {
                        for ((name, state) in listOf("联想" to association, "打字" to composing)) {
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text("${variants[index].first} $label ${if (glass) "玻璃" else "不透明"} $name", fontSize = 9.sp,
                                    color = Color.White, modifier = Modifier.width(150.dp))
                                Bar(state, id, dark, glass)
                            }
                        }
                    }
                }
            }
        }
        File(out!!).mkdirs()
        variants.forEachIndexed { index, (_, wide) ->
            rule.runOnIdle {
                SettingsPreferences.setWideCandidateSpacing(context, wide)
                shown.intValue = index
            }
            rule.waitForIdle()
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out, "candidate-feedback-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
