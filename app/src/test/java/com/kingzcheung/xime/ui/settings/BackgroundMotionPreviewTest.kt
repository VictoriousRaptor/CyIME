package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.settings.BackgroundMotionEffect
import com.kingzcheung.xime.ui.theme.BackgroundMotionPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 主题与外观 › 动态与轮播的示意图：四种效果要看得出不一样，图又不能露出边。 */
class BackgroundMotionPreviewTest {
    private val shot = 3_000L

    @Test fun `preview moves three times as far as the keyboard`() {
        val zoomed = previewMotion(BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_IN, shot - 1, shot))
        assertEquals(1f + (BackgroundMotionPath.MAX_SCALE - 1f) * 3f, zoomed.scale, .01f)
        val panned = previewMotion(BackgroundMotionPath.at(BackgroundMotionEffect.PAN, 0L, shot))
        assertEquals(-BackgroundMotionPath.MAX_SHIFT * 3f, panned.shiftX, 1e-4f)
    }

    @Test fun `preview still covers its card at every moment`() {
        for (effect in BackgroundMotionEffect.entries) for (t in 0L..(4 * shot) step 50L) {
            val pose = previewMotion(BackgroundMotionPath.at(effect, t, shot))
            for (p in listOfNotNull(pose, pose.fadeTo)) {
                assertTrue("$effect at $t: $p", abs(p.shiftX) <= (p.scale - 1f) / 2f + 1e-4f)
                assertTrue(p.scale >= 1f)
            }
        }
    }

    @Test fun `the four effect cards look different from each other`() {
        // 四张卡同一时刻起步：起步后一秒，任意两张的缩放或位移都差出一眼看得出的量（卡片宽的 3% 以上）。
        val poses = BackgroundMotionEffect.entries.associateWith {
            previewMotion(BackgroundMotionPath.at(it, 1_000L, shot)).dominant
        }
        val effects = poses.keys.toList()
        for (i in effects.indices) for (j in i + 1 until effects.size) {
            val a = poses.getValue(effects[i])
            val b = poses.getValue(effects[j])
            val apart = maxOf(abs(a.scale - b.scale), abs(a.shiftX - b.shiftX))
            assertTrue("${effects[i]} vs ${effects[j]}: $a / $b", apart >= .03f)
        }
    }
}
