package com.kingzcheung.xime.ui.theme

import com.kingzcheung.xime.settings.BackgroundMotionEffect
import com.kingzcheung.xime.settings.BackgroundMotionSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 动态背景的轨迹：盖满不露底、不跳、每帧移动不到一个像素、停住时不动；什么时候不动。 */
class KeyboardBackgroundMotionTest {
    private val frameMs = BackgroundMotionPath.FRAME_INTERVAL_MS

    private fun samples(effect: BackgroundMotionEffect, speed: BackgroundMotionSpeed): List<BackgroundMotionTransform> {
        // 三圈多一点，跨过每一处首尾相接。
        val span = 3 * 3 * speed.shotMs + speed.shotMs
        return (0..span step frameMs).map { BackgroundMotionPath.at(effect, it, speed.shotMs) }
    }

    @Test fun imageAlwaysCoversTheKeyboard() {
        for (effect in BackgroundMotionEffect.entries) for (speed in BackgroundMotionSpeed.entries) {
            samples(effect, speed).forEach { t ->
                assertTrue("$effect 放大不能小于 1", t.scale >= 1f - 1e-6f)
                assertTrue("$effect 放大不超过上限", t.scale <= BackgroundMotionPath.MAX_SCALE + 1e-6f)
                assertTrue("$effect 平移不能露出底色: $t", abs(t.shiftX) <= (t.scale - 1f) / 2f + 1e-5f)
                assertTrue("$effect 平移不能露出底色: $t", abs(t.shiftY) <= (t.scale - 1f) / 2f + 1e-5f)
            }
        }
    }

    /** 渐变回起点盖满之后，下一帧直接换成起点：看到的是同一个样子，不算跳。 */
    private fun continuing(a: BackgroundMotionTransform, b: BackgroundMotionTransform): BackgroundMotionTransform =
        a.fadeTo?.takeIf { a.fadeAlpha > .95f && b.fadeTo == null } ?: a

    @Test fun motionNeverJumps() {
        for (effect in BackgroundMotionEffect.entries) for (speed in BackgroundMotionSpeed.entries) {
            samples(effect, speed).zipWithNext { prev, b ->
                val a = continuing(prev, b)
                assertTrue("$effect $speed 缩放跳了: $a → $b", abs(a.scale - b.scale) < .003f)
                assertTrue("$effect $speed 位置跳了: $a → $b", abs(a.shiftX - b.shiftX) < .003f)
            }
        }
    }

    @Test fun eachFrameMovesLessThanAPixelOnAPhoneKeyboard() {
        // 1080px 宽、约 800px 高的固定键盘；默认（中）和慢速每帧不到 1px，快速不到 1.5px。
        val width = 1080f
        for (effect in BackgroundMotionEffect.entries) for (speed in BackgroundMotionSpeed.entries) {
            val limit = if (speed == BackgroundMotionSpeed.FAST) 1.5f else 1f
            samples(effect, speed).zipWithNext { prev, b ->
                val a = continuing(prev, b)
                // 边缘的点走得最远：缩放带来的半宽变化加上平移。
                val edge = abs(b.scale - a.scale) * width / 2f + abs(b.shiftX - a.shiftX) * width
                assertTrue("$effect $speed 每帧移动 $edge px", edge < limit)
            }
        }
    }

    @Test fun effectsStartWhereTheyShould() {
        val shot = BackgroundMotionSpeed.NORMAL.shotMs
        assertTrue(BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_IN, 0, shot).isIdentity)
        assertTrue(BackgroundMotionPath.at(BackgroundMotionEffect.CYCLE, 0, shot).isIdentity)
        assertEquals(BackgroundMotionPath.MAX_SCALE, BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_OUT, 0, shot).scale, 1e-6f)
        assertEquals(BackgroundMotionPath.MAX_SCALE, BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_IN, shot, shot).scale, 1e-6f)
        assertEquals(1f, BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_OUT, shot, shot).scale, 1e-6f)
        val pan = BackgroundMotionPath.at(BackgroundMotionEffect.PAN, 0, shot)
        assertEquals(-BackgroundMotionPath.MAX_SHIFT, pan.shiftX, 1e-6f)
        assertEquals(BackgroundMotionPath.MAX_SHIFT, BackgroundMotionPath.at(BackgroundMotionEffect.PAN, shot, shot).shiftX, 1e-6f)
        // 轮换：放大移到左边 → 平移到右边 → 缩回原样。
        assertEquals(-BackgroundMotionPath.MAX_SHIFT, BackgroundMotionPath.at(BackgroundMotionEffect.CYCLE, shot, shot).shiftX, 1e-6f)
        assertEquals(BackgroundMotionPath.MAX_SHIFT, BackgroundMotionPath.at(BackgroundMotionEffect.CYCLE, 2 * shot, shot).shiftX, 1e-6f)
        assertTrue(BackgroundMotionPath.at(BackgroundMotionEffect.CYCLE, 3 * shot, shot).isIdentity)
    }

    /** 用户 11-08：放大走到头不能倒着缩回去，缩小也一样；走到头后渐变回起点，一圈结束正好是起点。 */
    @Test fun zoomKeepsItsDirectionAndFadesBackToTheStart() {
        for (speed in BackgroundMotionSpeed.entries) for ((effect, direction) in
            listOf(BackgroundMotionEffect.ZOOM_IN to 1, BackgroundMotionEffect.ZOOM_OUT to -1)) {
            val shot = speed.shotMs
            val cycle = BackgroundMotionPath.cycleMs(effect, shot)
            val frames = (0L until cycle step frameMs).map { BackgroundMotionPath.at(effect, it, shot) }
            frames.zipWithNext { a, b ->
                assertTrue("$effect $speed 倒着走了: ${a.scale} → ${b.scale}", (b.scale - a.scale) * direction >= -1e-6f)
            }
            val start = BackgroundMotionPath.at(effect, 0, shot)
            val fading = frames.filter { it.fadeTo != null }
            assertTrue("$effect 走到头要渐变回起点", fading.isNotEmpty())
            fading.forEach { assertEquals("$effect 淡入的是起点的样子", start.scale, it.fadeTo!!.scale, 1e-6f) }
            fading.zipWithNext { a, b -> assertTrue("$effect 渐变只往起点走", b.fadeAlpha >= a.fadeAlpha) }
            assertTrue("$effect 渐变结束前几乎盖满", fading.last().fadeAlpha > .95f)
            assertEquals("$effect 一圈结束回到起点", start, BackgroundMotionPath.at(effect, cycle, shot))
            assertEquals("渐变时长", shot + BackgroundMotionPath.RETURN_FADE_MS, cycle)
        }
    }

    @Test fun panAndCycleNeverFade() {
        val shot = BackgroundMotionSpeed.NORMAL.shotMs
        for (effect in listOf(BackgroundMotionEffect.PAN, BackgroundMotionEffect.CYCLE))
            (0L..3 * shot step frameMs).forEach { assertEquals(null, BackgroundMotionPath.at(effect, it, shot).fadeTo) }
    }

    @Test fun fadingPoseIsNotIdentityAndDominantSwitchesHalfway() {
        val pose = BackgroundMotionTransform(1f, 0f, 0f, fadeTo = BackgroundMotionTransform(1.12f, 0f, 0f), fadeAlpha = .3f)
        assertFalse("缩小走到原样、上面还盖着放大的那层时不能当成静止", pose.isIdentity)
        assertEquals(1f, pose.dominant.scale, 0f)
        assertEquals(1.12f, pose.copy(fadeAlpha = .6f).dominant.scale, 0f)
    }

    @Test fun pausedClockKeepsThePosition() {
        val motion = KeyboardBackgroundMotion(BackgroundMotionEffect.CYCLE, BackgroundMotionSpeed.NORMAL.shotMs)
        repeat(80) { motion.advance(BackgroundMotionPath.FRAME_INTERVAL_MS) }
        val before = motion.transform
        motion.covered = true
        assertEquals("停住时位置不变", before, motion.transform)
        // 主线程卡住 5 秒后只走一小步，不跳。
        motion.advance(5_000)
        val step = BackgroundMotionPath.at(BackgroundMotionEffect.CYCLE, 4_100, BackgroundMotionSpeed.NORMAL.shotMs)
        assertEquals(step, motion.transform)
    }

    @Test fun slowPartsSkipFramesThatMoveLessThanHalfAPixel() {
        // 手机宽 1080、平板宽 2560：按新帧距走，每一帧仍在原来的轨迹上，相邻两帧最多挪半个像素
        // （原来每 50 ms 一帧就超过半像素的地方照旧 50 ms 一帧），渐变那一段照旧 50 ms 一帧。
        for (widthPx in listOf(1080f, 2560f)) for (effect in BackgroundMotionEffect.entries) for (speed in BackgroundMotionSpeed.entries) {
            val cycle = BackgroundMotionPath.cycleMs(effect, speed.shotMs)
            var t = 0L
            var frames = 0
            while (t < 2 * cycle) {
                val interval = BackgroundMotionPath.nextFrameIntervalMs(effect, t, speed.shotMs, widthPx, widthPx)
                assertTrue(interval in frameMs..BackgroundMotionPath.MAX_FRAME_INTERVAL_MS && interval % frameMs == 0L)
                val a = BackgroundMotionPath.at(effect, t, speed.shotMs)
                val b = BackgroundMotionPath.at(effect, t + interval, speed.shotMs)
                if (interval > frameMs) {
                    assertEquals("渐变中不拉长帧距", null, a.fadeTo)
                    assertTrue("$effect $speed $widthPx @$t 挪太多",
                        BackgroundMotionPath.maxCornerShiftPx(a, b, widthPx, widthPx) <= BackgroundMotionPath.MAX_STEP_PX)
                }
                t += interval
                frames++
            }
            assertTrue("$effect $speed 不比原来出帧多", frames <= 2 * cycle / frameMs + 1)
        }
        // 慢速放大在手机上少出一半以上的帧。
        val shot = BackgroundMotionSpeed.SLOW.shotMs
        var t = 0L; var frames = 0
        while (t < shot) { t += BackgroundMotionPath.nextFrameIntervalMs(BackgroundMotionEffect.ZOOM_IN, t, shot, 1080f, 1080f); frames++ }
        assertTrue("frames=$frames", frames < shot / frameMs / 2)
    }

    @Test fun longerFramesAdvanceTheClockInFull() {
        val shot = BackgroundMotionSpeed.SLOW.shotMs
        val motion = KeyboardBackgroundMotion(BackgroundMotionEffect.ZOOM_IN, shot)
        motion.advance(300, maxStepMs = 300 + KeyboardBackgroundMotion.STALL_ALLOWANCE_MS)
        assertEquals(BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_IN, 300, shot), motion.transform)
        motion.advance(5_000, maxStepMs = 300 + KeyboardBackgroundMotion.STALL_ALLOWANCE_MS)
        assertEquals("卡住后照样只多走一点", BackgroundMotionPath.at(BackgroundMotionEffect.ZOOM_IN, 650, shot), motion.transform)
    }

    @Test fun slidesChangeOncePerCycleAtItsStartingPose() {
        val shot = BackgroundMotionSpeed.NORMAL.shotMs
        for (effect in BackgroundMotionEffect.entries) {
            val cycle = BackgroundMotionPath.cycleMs(effect, shot)
            assertEquals(0, BackgroundMotionPath.slideIndex(effect, cycle - 1, shot, 3))
            assertEquals(1, BackgroundMotionPath.slideIndex(effect, cycle, shot, 3))
            assertEquals(2, BackgroundMotionPath.slideIndex(effect, 2 * cycle, shot, 3))
            assertEquals("走完一轮回到第一张", 0, BackgroundMotionPath.slideIndex(effect, 3 * cycle, shot, 3))
            assertEquals("一张图不轮播", 0, BackgroundMotionPath.slideIndex(effect, 5 * cycle, shot, 1))
            // 换图那一刻正好回到这一圈开头的样子，新图从头开始动。
            assertEquals(BackgroundMotionPath.at(effect, 0, shot), BackgroundMotionPath.at(effect, cycle, shot))
        }
    }

    @Test fun slideSourceFollowsTheClock() {
        val shot = BackgroundMotionSpeed.FAST.shotMs
        val motion = KeyboardBackgroundMotion(BackgroundMotionEffect.PAN, shot, listOf("themes/a.jpg", "themes/b.jpg"))
        assertEquals("themes/a.jpg", motion.slideSource)
        repeat((BackgroundMotionPath.cycleMs(BackgroundMotionEffect.PAN, shot) / BackgroundMotionPath.FRAME_INTERVAL_MS).toInt()) {
            motion.advance(BackgroundMotionPath.FRAME_INTERVAL_MS)
        }
        assertEquals("themes/b.jpg", motion.slideSource)
        assertEquals("不轮播时没有图", null, KeyboardBackgroundMotion(BackgroundMotionEffect.PAN, shot, listOf("themes/a.jpg")).slideSource)
    }

    @Test fun gameBatterySaverAndNoAnimationsStopTheMotion() {
        assertTrue(BackgroundMotionGate.allowed(enabled = true, imageBackground = true, game = false, systemAllows = true, surface = true))
        assertFalse("默认关", BackgroundMotionGate.allowed(enabled = false, imageBackground = true, game = false, systemAllows = true, surface = true))
        assertFalse("纯色、渐变不动", BackgroundMotionGate.allowed(enabled = true, imageBackground = false, game = false, systemAllows = true, surface = true))
        assertFalse("游戏里不动", BackgroundMotionGate.allowed(enabled = true, imageBackground = true, game = true, systemAllows = true, surface = true))
        assertFalse("实体键盘浮条、语音悬浮条不动", BackgroundMotionGate.allowed(enabled = true, imageBackground = true, game = false, systemAllows = true, surface = false))
        assertFalse("省电", BackgroundMotionGate.systemAllows(powerSave = true, animationsOn = true))
        assertFalse("关了动画", BackgroundMotionGate.systemAllows(powerSave = false, animationsOn = false))
        assertTrue(BackgroundMotionGate.systemAllows(powerSave = false, animationsOn = true))
    }
}
