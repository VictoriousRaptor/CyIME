package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.MergedLetterInput
import com.kingzcheung.xime.rime.PinyinSyllableChoices
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MergedLetterRailTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun tapAsThenTapSAndReselectAWithoutCommittingOrClosingThePage() {
        val app = rule.activity.application
        SettingsPreferences.getPrefsPublic(app).edit().putBoolean("fourteen_letter_swipe", false)
            .putString("cursor_gesture_mode", "KEYBOARD").apply()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        val schema = mutableStateOf("pinyin_14jian")
        val state = mutableStateOf(CandidateState())
        fun show(code: String) {
            val words = when (code) {
                "A" -> listOf("啊", "阿", "是", "说")
                "s" -> listOf("是", "说", "上", "时", "手", "事", "生", "神", "晒", "啥", "深", "三", "少", "水")
                else -> listOf("啊", "阿", "吖")
            }
            state.value = CandidateState(engineRevision = state.value.engineRevision + 1,
                inputText = code, preeditText = code.lowercase(), candidates = words.take(4), isComposing = true,
                hasNextPage = true, expandedCandidates = words.map { RimeCandidate(it, "") }, expandedCandidatesLoaded = true)
        }
        fun choices(): PinyinSyllableChoices {
            val raw = state.value.inputText
            return PinyinSyllableChoices(1, schema.value, state.value.engineRevision, raw, 0, "", emptyList(),
                MergedLetterInput.singleKeyGroup(raw, 1, 0))
        }
        val keys = mutableListOf<String>()
        var hold = true
        var deliver: (() -> Unit)? = null
        val callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> keys += key; show("A") },
            onCandidateSelect = { error("unexpected candidate commit") },
            onCommitText = { error("unexpected literal commit") },
            onRequestPinyinSyllables = { _, reply ->
                if (hold) deliver = { reply(choices()) } else reply(choices())
            }, onSelectPinyinSyllable = { choice, index, reply ->
                assertEquals(state.value.engineRevision, choice.engineRevision)
                show(choice.labels[index])
                reply(choices())
            })
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            KeyboardView(vm, KeyboardUiState(currentSchemaId = schema.value, isDarkTheme = true,
                inputSessionId = 1, keyboardHeightDp = 276), callbacks,
                modifier = Modifier.size(360.dp, 276.dp).testTag("keyboard"), candidateState = state,
                inputSessionManagedByHost = true)
        }
        for (id in listOf("pinyin_14jian", "double_pinyin_flypy_14jian")) {
            rule.runOnIdle {
                vm.setCandidatePageExpanded(false)
                state.value = CandidateState()
                hold = true
                schema.value = id
                vm.dispatch(KeyboardDispatchAction.InputSessionStarted(false, id))
            }
            rule.onNodeWithTag("qwerty-key:as", true).performTouchInput { click(center) }
            assertEquals("as", keys.last())
            rule.onNodeWithTag("candidate-expansion").performClick()
            val rail = hasAnyAncestor(hasTestTag("expanded-pinyin-options"))
            rule.onNode(hasText("？") and rail, true).assertDoesNotExist()
            rule.runOnIdle { hold = false; requireNotNull(deliver).invoke() }
            val a = rule.onNode(hasText("a") and rail, true)
            val s = rule.onNode(hasText("s") and rail, true)
            a.assertIsDisplayed()
            s.assertIsDisplayed().performClick()
            assertEquals("s", state.value.inputText)
            rule.onNodeWithText("是", true).assertIsDisplayed()
            rule.onNodeWithTag("expanded-filter-key", true).assertIsDisplayed()
            if (id == "pinyin_14jian") save()
            a.performClick()
            assertEquals("a", state.value.inputText)
        }
        assertEquals(listOf("as", "as"), keys)
    }

    private fun save() {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val r = rule.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInWindow
        File(out, "as-selected-s.png").outputStream().use {
            Bitmap.createBitmap(bitmap, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
