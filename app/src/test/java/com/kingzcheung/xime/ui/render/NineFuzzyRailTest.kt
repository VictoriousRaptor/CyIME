package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.NineKeyFuzzyReadings
import com.kingzcheung.xime.settings.SpellingPatches
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.FuzzyPinyinSection
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w440dp-h700dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NineFuzzyRailTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** 模糊音 11-01 起只放在「语言与方案」一处，九键也用这一份。 */
    @Test fun nineKeySettingsCanEnableAReadingGroup() {
        rule.setContent {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { FuzzyPinyinSection() }
        }
        rule.onNodeWithText("模糊音", useUnmergedTree = true).performScrollTo()
        rule.onNodeWithText("z 和 zh", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        rule.runOnIdle { org.junit.Assert.assertTrue(SpellingPatches.Fuzzy.Z in SpellingPatches.fuzzy(rule.activity)) }
        System.getenv("CYIME_RENDER_OUT")?.let {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(Canvas(bitmap)) }
            File(it, "nine-fuzzy-settings.png").outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
    }

    @Test fun nearReadingShowsItsSoundAndSendsTheCorrespondingFlypyKeys() {
        AppFonts.initialize(rule.activity.application)
        val options = NineKeyFuzzyReadings(DoublePinyinScheme.FLYPY, setOf(SpellingPatches.Fuzzy.S))
            .options("7454").map { T9InputController.SyllableOption(it.pinyin, it.digits, it.spelling, it.near) }
        val selected = options.single { it.near && it.pinyin == "shi" && it.spelling == "ui" }
        val controller = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.INPUT
            on { firstOptions } doReturn options
        }
        val state = mutableStateOf(CandidateState(inputText = "7454", candidates = listOf("司机", "日记", "奇迹"), isComposing = true))
        rule.setContent {
            Column(Modifier.width(384.dp).testTag("keyboard")) {
                CandidateBar(CandidateBarState.ChineseCandidates(state.value.candidates, inputText = "7454"),
                    visuals = CandidateBarVisuals(Color.DarkGray, Color.White, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}), showPreeditPreview = false)
                T9KeyboardLayout({}, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                    KeyboardUiState(currentSchemaId = "double_pinyin_flypy_t9"), controller, Color.DarkGray, Color.White, Color.Gray,
                    modifier = Modifier.height(260.dp), candidateState = state)
            }
        }
        // 用户 11-13：相近读音不再写 ≈，写原样的 shi，加下划线、浅色；读屏读「相近读音 shi」。
        rule.onNodeWithTag("t9-pinyin-options").performScrollToNode(hasContentDescription("相近读音 shi"))
        rule.onAllNodesWithText("≈", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("相近读音 shi", useUnmergedTree = true).performTouchInput { click() }
        verify(controller).onChoiceSelected(selected)
        System.getenv("CYIME_RENDER_OUT")?.let {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val bounds = rule.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInWindow
            rule.runOnIdle { root.draw(Canvas(bitmap)) }
            File(it, "nine-fuzzy-rail.png").outputStream().use { out ->
                Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}
