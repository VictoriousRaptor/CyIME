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
 * 候选栏一边只有一个按钮（用户 11-20）：空闲、联想、打字最右都是收起键盘；展开靠在候选上横着拉。
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

    @Test fun everyStateKeepsHideOnTheRightAndExpandsByPulling() {
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        val actions = mutableListOf<String>()
        rule.setContent {
            Column {
                Bar(CandidateBarState.Idle, onHide = { actions += "hide-idle" })
                Bar(association, onOpen = { actions += "open-association" }, onHide = { actions += "hide-association" })
                Bar(composing, onOpen = { actions += "open-composing" }, onHide = { actions += "hide-composing" })
            }
        }
        rule.waitForIdle()
        // 一边只有一个按钮（用户 11-20）：右边空闲、联想、打字都是收起键盘，同一个位置；没有展开按钮。
        val idle = rule.onNodeWithTag("toolbar-hide", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val hides = rule.onAllNodesWithTag("candidate-hide", useUnmergedTree = true)
        val (assoc, typing) = hides.fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(idle.right, assoc.right, 0.5f)
        assertEquals(typing.right, assoc.right, 0.5f)
        rule.onNodeWithTag("candidate-expansion", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("toolbar-hide", useUnmergedTree = true).performClick()
        hides[0].performClick(); hides[1].performClick()
        // 展开：在候选上横着拉过门槛松手。
        val pulls = rule.onAllNodesWithTag("candidate-pull-expand", useUnmergedTree = true)
        pulls[0].pullAcross(); pulls[1].pullAcross(leftward = false)
        assertEquals(listOf("hide-idle", "hide-association", "hide-composing", "open-association", "open-composing"), actions)
    }

    @Test fun expandedAssociationStillHidesOnTheRight() {
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        var backed = 0
        var hidden = 0
        var opened = 0
        rule.setContent { Bar(association, expanded = true, onBack = { backed++ }, onHide = { hidden++ }, onOpen = { opened++ }) }
        rule.waitForIdle()
        rule.onNodeWithTag("candidate-expansion", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("candidate-hide", useUnmergedTree = true).performClick()
        // 已经展开着：栏上再拉不会再展开一次（收回在展开页上划）。
        rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true).pullAcross()
        assertEquals(listOf(0, 1, 0), listOf(backed, hidden, opened))
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
            rule.pullOpenCandidates()
            rule.onNodeWithTag("expanded-candidates").assertExists()
            rule.onNodeWithTag("expanded-filter-key").assertDoesNotExist()
            if (word == words[1]) saveWindow("association-expanded.png", "feedback-keyboard")
            rule.onNodeWithText(word).performClick()
            rule.onNodeWithTag("expanded-candidates").assertDoesNotExist()
        }
        assertEquals(words.drop(1), committed)
        assertEquals(0, engineRequests)
        // An async prediction replaces the row between pointer-down and selection.
        rule.pullOpenCandidates()
        rule.onNodeWithText("上午").performTouchInput { down(center) }
        rule.runOnIdle {
            snapshot.value = CandidateState(associationCandidates = listOf("明天", "下午", "出发"))
        }
        rule.onNodeWithText("下午").performTouchInput { up() }
        assertEquals(words.drop(1), committed)
        rule.onNodeWithText("下午").performClick()
        assertEquals("下午", committed.last())
        rule.runOnIdle { snapshot.value = CandidateState(associationCandidates = listOf("唯一")) }
        // 只有一个联想：拉不开。
        rule.pullOpenCandidates()
        rule.onNodeWithTag("expanded-candidates").assertDoesNotExist()
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        rule.runOnIdle {
            snapshot.value = CandidateState(inputText = "ming", candidates = listOf("明天", "明"),
                isComposing = true, hasNextPage = true)
        }
        rule.pullOpenCandidates()
        rule.onNodeWithTag("expanded-filter-key").performClick()
        rule.runOnIdle { assertTrue(viewModel.singleCharFilter.value) }
        assertEquals(1, engineRequests)
        // 英文补全也能拉开展开页，在展开页上横着划收回（用户 11-20）。
        rule.runOnIdle {
            viewModel.setCandidatePageExpanded(false)
            snapshot.value = CandidateState(pendingEnglishText = "He", associationCandidates = listOf("Hello", "Help", "Here",
                "Hey", "Head", "Heart", "Heavy", "Hero", "Health", "Hearing", "Heaven", "Height"))
        }
        rule.pullOpenCandidates()
        rule.onNodeWithTag("expanded-candidates").assertExists()
        // 划得不够：弹回，不收回。
        rule.onNodeWithTag("expanded-candidates", useUnmergedTree = true).pullAcross(leftward = false, fraction = 0.1f)
        rule.onNodeWithTag("expanded-candidates").assertExists()
        rule.swipeCloseCandidates()
        rule.onNodeWithTag("expanded-candidates").assertDoesNotExist()
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
