package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 统一过渡动效第二批：面板（用户 11-11：过渡不能影响操作，不能影响超高速打字）。
 * 收起面板的动画还没走完，露出来的键就要能按；展开途中和原来一样整块挡住。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-mdpi")
class PanelMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var panelVisible by mutableStateOf(true)
    private val hits = mutableListOf<String>()

    private fun showPanel() {
        rule.setContent {
            Box(Modifier.requiredSize(300.dp, 200.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f).clickable { hits += "top" })
                    Box(Modifier.fillMaxWidth().weight(1f).clickable { hits += "bottom" })
                }
                KeyboardPanelReveal(panelVisible, Modifier.fillMaxSize().testTag("motion-panel")) {
                    Box(Modifier.fillMaxSize().clickable { hits += "panel" })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tapAt(fraction: Float) {
        rule.onRoot().performTouchInput { val p = Offset(width / 2f, height * fraction); down(p); up() }
    }

    @Test fun closingPanelGivesUncoveredKeysBackAtOnce() {
        showPanel()
        tapAt(0.75f)
        assertEquals(listOf("panel"), hits)
        hits.clear()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { panelVisible = false; Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        // 收起约到一半（240 ms 展开 / 180 ms 收起里过了 48 ms）：下半截已经露出键盘，上半截还是面板。
        rule.mainClock.advanceTimeBy(48)
        // 动画在帧回调里改的进度要等下一帧才落到图层的裁切上（暂停时钟时测试自己推这一帧）。
        rule.runOnUiThread { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        tapAt(0.85f)
        tapAt(0.1f)
        assertEquals("露出来的键要能按，还盖着的地方不漏给键盘", listOf("bottom"), hits)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        hits.clear()
        tapAt(0.1f)
        assertEquals(listOf("top"), hits)
    }

    @Test fun openingPanelStillBlocksTheWholeSlot() {
        panelVisible = false
        showPanel()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { panelVisible = true; Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(48)
        // 展开途中（和原来一样）：还没盖到的地方也不漏给键盘，面板项也不能半露着点到。
        tapAt(0.1f)
        tapAt(0.9f)
        assertTrue(hits.toString(), hits.isEmpty())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        tapAt(0.9f)
        assertEquals(listOf("panel"), hits)
    }
}
