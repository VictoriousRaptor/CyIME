package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 11-14：「上下滑输入符号」要能真关（关了提示以后上下滑、长按照样出符号，设置和执行不一致）；
 * 关掉、滑行也关着时，按住在键盘上滑，滑到哪个键就按哪个键，松手输入（像三星键盘）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1280dp-xhdpi")
class CornerSymbolSwitchTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val typed = mutableListOf<String>()

    private fun show(prefs: KeyboardInputPreferences) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema("")
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        val onKeyPress: (String) -> Unit = { typed += it }
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true, LocalKeyboardInputPreferences provides prefs) {
                MaterialTheme {
                    Box(Modifier.requiredSize(800.dp, 320.dp)) {
                        KeyboardLayout(onKeyPress, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onCommitText = { typed += it }),
                            KeyboardUiState(currentSchemaId = ""), false, Modifier.requiredSize(800.dp, 320.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun center(tag: String) = rule.onNode(androidx.compose.ui.test.hasTestTag(tag), true)
        .fetchSemanticsNode().boundsInRoot.center

    private fun drag(from: Offset, to: Offset) {
        rule.onRoot().performTouchInput {
            down(from)
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            up()
        }
        rule.waitForIdle()
    }

    /** 对照：开着时从 q 往下滑到 a 那一排是角标手势，出的是角标，不是 a。 */
    @Test fun withSymbolSwipesOnAVerticalDragTypesTheCorner() {
        show(KeyboardInputPreferences())
        val q = center("qwerty-key:q")
        val a = center("qwerty-key:a")
        assertTrue("两排之间要超过滑动阈值", a.y - q.y > 50f * rule.density.density)
        drag(q, Offset(q.x, a.y))
        assertEquals(1, typed.size)
        assertFalse(typed.single() == "a" || typed.single() == "q")
    }

    @Test fun switchedOffNoCornerAndSlidingPressesTheKeyUnderTheFinger() {
        show(KeyboardInputPreferences(cornerSymbolInput = false))
        // 同样的手势：不出角标，松手在 a 上就是 a。
        drag(center("qwerty-key:q"), center("qwerty-key:a"))
        assertEquals(listOf("a"), typed)
        // 横着滑过好几个键：只按松手那一个。
        drag(center("qwerty-key:s"), center("qwerty-key:g"))
        assertEquals(listOf("a", "g"), typed)
        // 滑出去又回到按下的键：按它自己。
        val d = center("qwerty-key:d")
        rule.onRoot().performTouchInput {
            down(d); moveTo(center("qwerty-key:f")); moveTo(d); up()
        }
        rule.waitForIdle()
        assertEquals(listOf("a", "g", "d"), typed)
        // 点按照常。
        rule.onRoot().performTouchInput { down(center("qwerty-key:k")); up() }
        rule.waitForIdle()
        assertEquals(listOf("a", "g", "d", "k"), typed)
    }

    /** 关掉以后长按也不出角标：「长按输入」时按住字母键不放，松手只是这个字母。 */
    @Test fun switchedOffLongPressTypesNoCorner() {
        show(KeyboardInputPreferences(cornerSymbolInput = false, symbolInputMode = SymbolInputMode.LONG_PRESS))
        val w = center("qwerty-key:w")
        rule.onRoot().performTouchInput { down(w) }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(typed.all { it == "w" })
    }

    /** 键盘上的目标：按手指位置找键，松手按它；落在键外不按。 */
    @Test fun slideTargetsPickTheKeyUnderThePoint() {
        val targets = ShiftSlideTargets()
        val hits = mutableListOf<String>()
        targets.putSlideTap("a", Rect(0f, 0f, 10f, 10f)) { hits += "a" }
        targets.putSlideTap("b", Rect(10f, 0f, 20f, 10f)) { hits += "b" }
        targets.slideOver(Offset(15f, 5f))
        assertEquals("b", targets.slideHovered)
        assertTrue(targets.sliding)
        assertTrue(targets.slideRelease())
        assertEquals(listOf("b"), hits)
        assertFalse(targets.sliding)
        // 落在所有键外面：什么都不按。
        targets.slideOver(Offset(50f, 50f))
        assertFalse(targets.slideRelease())
        assertEquals(listOf("b"), hits)
        // 键撤掉了，悬停也清掉。
        targets.slideOver(Offset(5f, 5f))
        targets.removeSlideTap("a")
        assertEquals(null, targets.slideHovered)
    }

    @Test fun switchDefaultsOnAndRoundTrips() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(KeyboardInputPreferences.read(app).cornerSymbolInput)
        KeyboardInputPreferences.read(app).copy(cornerSymbolInput = false).save(app)
        assertFalse(KeyboardInputPreferences.read(app).cornerSymbolInput)
    }
}
