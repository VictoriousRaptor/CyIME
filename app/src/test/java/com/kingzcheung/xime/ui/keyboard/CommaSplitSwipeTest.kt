package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 11-24 反馈「中文逗号下滑分词失效」：开着「逗号键分词」时，中文全拼、14 键、双拼的逗号键往下滑送分词 '。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1280dp-xhdpi")
class CommaSplitSwipeTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()

    private fun show(schema: String, prefs: KeyboardInputPreferences) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        val onKeyPress: (String) -> Unit = { keys += it }
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true, LocalKeyboardInputPreferences provides prefs) {
                MaterialTheme {
                    Box(Modifier.requiredSize(800.dp, 320.dp)) {
                        KeyboardLayout(onKeyPress, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onCommitText = { keys += "commit:$it" }),
                            KeyboardUiState(currentSchemaId = schema), false, Modifier.requiredSize(800.dp, 320.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun swipeDownOnComma(down: Boolean = true) {
        val c = rule.onNode(androidx.compose.ui.test.hasTestTag("qwerty-punctuation-key"), true)
            .fetchSemanticsNode().boundsInRoot.center
        val to = Offset(c.x, c.y + (if (down) 70f else -70f) * rule.density.density)
        rule.onRoot().performTouchInput {
            down(c)
            for (step in 1..10) moveTo(c + (to - c) * (step / 10f))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun fullPinyinDefaultPrefs() {
        show("rime_ice", KeyboardInputPreferences(syllableSplitKey = true))
        swipeDownOnComma()
        assertEquals(listOf("'"), keys)
    }

    /** 关了「底排标点固定上滑」、角标下拉输入上角标：分词仍下滑（键面 ' 在左下角），副标点上滑。 */
    @Test fun fullPinyinSwipeDownForUpperCorner() {
        show("rime_ice", KeyboardInputPreferences(syllableSplitKey = true, bottomPunctuationSwipeUp = false))
        swipeDownOnComma()
        assertEquals(listOf("'"), keys)
        keys.clear()
        swipeDownOnComma(down = false)
        assertEquals(1, keys.size)
        assertTrue(keys.single(), keys.single().startsWith("commit:"))
    }

    @Test fun fullPinyinLongPressCorners() {
        show("rime_ice", KeyboardInputPreferences(syllableSplitKey = true, symbolInputMode = SymbolInputMode.LONG_PRESS))
        swipeDownOnComma()
        assertEquals(listOf("'"), keys)
    }

    @Test fun doublePinyin() {
        show("double_pinyin_flypy", KeyboardInputPreferences(syllableSplitKey = true))
        swipeDownOnComma()
        assertEquals(listOf("'"), keys)
    }

    @Test fun fourteenKey() {
        show("pinyin_14jian", KeyboardInputPreferences(syllableSplitKey = true))
        swipeDownOnComma()
        assertEquals(listOf("'"), keys)
    }
}
