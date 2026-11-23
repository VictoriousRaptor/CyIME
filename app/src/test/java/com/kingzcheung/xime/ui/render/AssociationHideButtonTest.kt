package com.kingzcheung.xime.ui.render

import android.content.Context
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
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.commitAssociationSelection
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 上屏后的联想不放收起键盘按钮，最右箭头与组词共用展开/返回语义。
 * 设了 CYIME_RENDER_OUT=<目录> 时另出一张各主题的对照图 association-hide.png。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssociationHideButtonTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val association = CandidateBarState.from(emptyList(), emptyList(), "", "", false,
        listOf("的", "了", "吗", "一下", "什么", "怎么样", "是不是", "可以"), false, false)
    private val composing = CandidateBarState.ChineseCandidates(listOf("你好", "拟好", "你", "妮"), inputText = "nihao", hasMore = true)

    private fun toolbarActions() = com.kingzcheung.xime.keyboard.ToolbarButton.entries.take(7).map {
        com.kingzcheung.xime.keyboard.ToolbarAction(com.kingzcheung.xime.keyboard.ToolbarButtonItem.Builtin(it)) {}
    }

    @androidx.compose.runtime.Composable
    private fun Bar(state: CandidateBarState, theme: String = "soft_blue", dark: Boolean = false, glass: Boolean = false,
                    expanded: Boolean = false, onOpen: () -> Unit = {}, onHide: () -> Unit = {}, onBack: () -> Unit = {}) {
        val palette = resolveKeyboardPalette(theme, dark)
        val config = FrostedGlassConfig.defaults(dark, enabled = glass)
        Box(Modifier.width(400.dp).height(44.dp)
            .then(if (glass) Modifier.background(Brush.linearGradient(
                listOf(Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828), Color(0xFF1565C0)))) else Modifier)
            .keyboardBackground(KeyboardThemes.getRenderingScheme(theme).keyboardBackground, dark, palette.background, config)) {
            CompositionLocalProvider(LocalKeyboardPalette provides palette,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = config)) {
                CandidateBar(
                    state = state,
                    candidatePageExpanded = expanded,
                    toolbarActions = toolbarActions(),
                    visuals = CandidateBarVisuals(Color.Transparent, palette.candidateText,
                        if (dark) Color(0xFF3C4043) else Color(0xFFDADCE0), palette.accent, palette.selectedText, dark, palette.key),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {}, onShowMoreCandidates = onOpen,
                        onClearAssociation = {}, onHideKeyboard = onHide, onBack = onBack, onLogoClick = {}),
                    showPreeditPreview = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Test fun composingAndAssociationUseTheSameRightEdgeToExpand() {
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        val actions = mutableListOf<String>()
        rule.setContent {
            Column {
                Bar(CandidateBarState.Idle)
                Bar(association, onOpen = { actions += "open-association" }, onHide = { actions += "hide" })
                Bar(composing, onOpen = { actions += "open-composing" })
            }
        }
        rule.waitForIdle()
        val hides = rule.onAllNodesWithTag("toolbar-hide", useUnmergedTree = true).fetchSemanticsNodes()
        // 只有空闲有；联想和打字候选都没有。
        assertEquals(1, hides.size)
        val arrows = rule.onAllNodesWithTag("candidate-expansion", useUnmergedTree = true)
        val (assoc, typing) = arrows.fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(typing.right, assoc.right, 0.5f)
        arrows[0].performClick(); arrows[1].performClick()
        assertEquals(listOf("open-association", "open-composing"), actions)
    }

    @Test fun expandedAssociationShowsOnlyTheBackButton() {
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        var backed = 0
        rule.setContent { Bar(association, expanded = true, onBack = { backed++ }) }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("toolbar-hide", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("candidate-expansion", useUnmergedTree = true).performClick()
        assertEquals(1, backed)
    }

    @Test fun keyboardOpensPredictionsAndCommitsTheTappedWordWithoutPagingTheEngine() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val words = listOf("明天", "上午", "出发")
        val snapshot = mutableStateOf(CandidateState(associationCandidates = words))
        val committed = mutableListOf<String>()
        var engineRequests = 0
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onRequestExpandedCandidates = { engineRequests++ }, onAssociationSelect = { index ->
                check(commitAssociationSelection(snapshot.value, index, { committed.add(it) }, { _, _ -> false }))
            })
        // The post-commit list is supplied by the service. A Windows UI test has no Android Rime JNI.
        doReturn(mock<T9InputController>()).whenever(viewModel).bindT9Controller(callbacks)
        rule.setContent {
            KeyboardView(viewModel, KeyboardUiState(currentSchemaId = "rime_ice"), callbacks,
                modifier = Modifier.width(400.dp).height(284.dp).testTag("feedback-keyboard"),
                candidateState = snapshot, inputSessionManagedByHost = true)
        }
        for (word in words.drop(1)) {
            rule.onNodeWithTag("candidate-expansion").performClick()
            rule.onNodeWithTag("expanded-candidates").assertExists()
            rule.onNodeWithTag("expanded-filter-key").assertDoesNotExist()
            if (word == words[1]) saveWindow("association-expanded.png", "feedback-keyboard")
            rule.onNodeWithText(word).performClick()
            rule.onNodeWithTag("expanded-candidates").assertDoesNotExist()
        }
        assertEquals(words.drop(1), committed)
        assertEquals(0, engineRequests)
        // An async prediction replaces the row between pointer-down and selection.
        rule.onNodeWithTag("candidate-expansion").performClick()
        rule.onNodeWithText("上午").performTouchInput { down(center) }
        rule.runOnIdle {
            snapshot.value = CandidateState(associationCandidates = listOf("明天", "下午", "出发"))
        }
        rule.onNodeWithText("下午").performTouchInput { up() }
        assertEquals(words.drop(1), committed)
        rule.onNodeWithText("下午").performClick()
        assertEquals("下午", committed.last())
        rule.runOnIdle { snapshot.value = CandidateState(associationCandidates = listOf("唯一")) }
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        rule.runOnIdle {
            snapshot.value = CandidateState(pendingEnglishText = "He", associationCandidates = listOf("Hello", "Help"))
        }
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        rule.runOnIdle {
            snapshot.value = CandidateState(inputText = "ming", candidates = listOf("明天", "明"),
                isComposing = true, hasNextPage = true)
        }
        rule.onNodeWithTag("candidate-expansion").performClick()
        rule.onNodeWithTag("expanded-filter-key").performClick()
        rule.runOnIdle { assertTrue(viewModel.singleCharFilter.value) }
        assertEquals(1, engineRequests)
    }

    private fun saveWindow(name: String, cropTag: String? = null) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val visible = cropTag?.let {
            val bounds = rule.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        } ?: bitmap
        File(out).mkdirs()
        File(out, name).outputStream().use { visible.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderAssociationHideButton() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        val themes = listOf(Triple("soft_blue", false, "柔和蓝·浅"), Triple("soft_blue", true, "柔和蓝·深"),
            Triple("pure_black", false, "黑白·纯白"), Triple("pure_black", true, "黑白·纯黑"))
        rule.setContent {
            Column(Modifier.background(Color(0xFF808080)).padding(4.dp)) {
                for (glass in listOf(false, true)) for ((id, dark, label) in themes) {
                    for ((name, state) in listOf("空闲" to CandidateBarState.Idle, "联想" to association, "打字" to composing)) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("$label ${if (glass) "玻璃" else "不透明"} $name", fontSize = 10.sp, color = Color.White,
                                modifier = Modifier.width(140.dp))
                            Bar(state, id, dark, glass)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        // Robolectric 下 captureToImage 等不到 PixelCopy；直接把整个窗口画到位图上。
        saveWindow("association-hide.png")
    }
}
