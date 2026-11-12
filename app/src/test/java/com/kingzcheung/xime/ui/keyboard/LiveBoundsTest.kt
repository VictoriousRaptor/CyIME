package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 用户 11-11：手写笔点 14 键 GH，按键浮标画在最下面一排。浮标的位置是按键位置减键盘位置，
 * 两者以前都用布局回调记下的 boundsInRoot：被外层裁掉一截、或回调后位置又变了，浮标就落到别处。
 * [LiveBounds] 在按下这一刻现量，不裁剪。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp-mdpi")
// 图层位移要真实图形才算得进位置（同 UndockFollowTest）。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveBoundsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun keyHangingOutOfItsParentKeepsItsRealSize() {
        val live = LiveBounds()
        var cached = Rect.Zero
        rule.setContent {
            // 40dp 高的键有一半伸出 100dp 高、裁边的父布局（好比键盘卡片）：boundsInRoot 只剩露在里面的 20dp。
            Box(Modifier.size(100.dp).clipToBounds()) {
                Box(Modifier.offset(y = 80.dp).size(40.dp).onGloballyPositioned {
                    cached = it.boundsInRoot()
                    live.coordinates = it
                })
            }
        }
        rule.runOnIdle {
            assertEquals("记下的值被裁掉一半", 20f, cached.height, 0.5f)
            val now = live.current(cached, "test")
            assertEquals(80f, now.top, 0.5f)
            assertEquals(40f, now.height, 0.5f)
        }
    }

    @Test
    fun outdatedCachedPositionIsNotUsed() {
        val live = LiveBounds()
        var y by mutableStateOf(0)
        rule.setContent {
            Box(Modifier.size(400.dp)) {
                Box(Modifier.offset(y = y.dp).size(40.dp)
                    .onGloballyPositioned { live.coordinates = it })
            }
        }
        rule.runOnIdle { y = 120 }
        rule.runOnIdle {
            // 好比回调记下的还是挪动前（或别的时候）的位置：一律按现在的位置算。
            val stale = Rect(0f, 360f, 40f, 400f)
            val now = live.current(stale, "test")
            assertEquals(120f, now.top, 0.5f)
            assertEquals(160f, now.bottom, 0.5f)
        }
    }

    @Test
    fun keyMovedOnlyByAGraphicsLayerIsMeasuredWhereItIsDrawn() {
        val live = LiveBounds()
        var shift by mutableStateOf(300f)
        rule.setContent {
            Box(Modifier.size(400.dp)) {
                // 动画只改图层位移、不重新布局时，布局回调不一定再来；现量要跟着图层走。
                Box(Modifier.graphicsLayer { translationY = shift }) {
                    Box(Modifier.offset(y = 40.dp).size(40.dp).onGloballyPositioned { live.coordinates = it })
                }
            }
        }
        rule.runOnIdle { shift = 0f }
        rule.runOnIdle {
            val now = live.current(Rect.Zero, "test")
            assertEquals(40f, now.top, 0.5f)
            assertEquals(80f, now.bottom, 0.5f)
        }
    }

    @Test
    fun detachedKeyFallsBackToCachedBounds() {
        val live = LiveBounds()
        val cached = Rect(1f, 2f, 3f, 4f)
        assertEquals(cached, live.current(cached, "test"))
        var shown by mutableStateOf(true)
        rule.setContent {
            if (shown) Box(Modifier.size(10.dp).onGloballyPositioned { live.coordinates = it })
        }
        rule.runOnIdle { shown = false }
        rule.runOnIdle { assertTrue(live.current(cached, "test") == cached) }
    }
}
