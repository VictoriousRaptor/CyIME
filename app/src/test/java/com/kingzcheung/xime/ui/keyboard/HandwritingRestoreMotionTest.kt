package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 11-05：按记忆恢复全屏手写时直接全屏；点「全屏手写」照旧动画展开，收起照旧动画。
 * 不手动拨时钟：让测试时钟自己走完动画，记下每次重组看到的进度，看有没有中间帧。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class HandwritingRestoreMotionTest {
    @get:Rule val rule = createComposeRule()

    private val expanded = mutableStateOf(false)
    private val instant = mutableStateOf(false)
    private var motion = HandwritingExpansion(0f, false)
    private val seen = mutableListOf<Float>()

    private fun host() {
        rule.setContent {
            val current = rememberHandwritingExpansion(true, expanded.value, 1L, instant = instant.value)
            motion = current
            SideEffect { seen += current.fraction }
        }
        rule.waitForIdle()
        seen.clear()
    }

    private fun midFrames() = seen.filter { it > .01f && it < .99f }

    @Test fun restoredFullscreenOpensWithoutTheExpandAnimation() {
        host()
        rule.runOnUiThread { expanded.value = true; instant.value = true }
        rule.waitForIdle()
        assertTrue(motion.active)
        assertEquals(1f, motion.fraction, .001f)
        assertTrue("恢复时不应有展开的中间帧：$seen", midFrames().isEmpty())
        // 之后点「收起全屏手写」：照旧动画收起。
        seen.clear()
        rule.runOnUiThread { expanded.value = false; instant.value = false }
        rule.waitForIdle()
        assertFalse(motion.active)
        assertEquals(0f, motion.fraction, .001f)
        assertTrue("收起应照旧有动画：$seen", midFrames().isNotEmpty())
    }

    @Test fun tappingFullscreenStillAnimates() {
        host()
        rule.runOnUiThread { expanded.value = true }
        rule.waitForIdle()
        assertTrue(motion.active)
        assertEquals(1f, motion.fraction, .001f)
        assertTrue("点全屏应照旧有展开动画：$seen", midFrames().isNotEmpty())
    }
}
