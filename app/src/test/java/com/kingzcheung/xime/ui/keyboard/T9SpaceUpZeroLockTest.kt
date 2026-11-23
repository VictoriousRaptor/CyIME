package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.settings.t9SpaceUpZeroSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 九键「空格上滑输入 0」↔ 按住空格说话「上滑取消」（用户 11-22）：都用空格往上滑，先开的锁住另一个，
 * 被锁的一项写明被谁锁；两个都开着时都不锁。默认都关、都不锁。
 */
class T9SpaceUpZeroLockTest {
    private val voice = KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE)

    private fun zeroLock(p: KeyboardInputPreferences) =
        InputGestureLocks.lockFor(p, InputGestureLock.Option.T9_SPACE_UP_ZERO, true)
    private fun cancelLock(p: KeyboardInputPreferences) =
        InputGestureLocks.lockFor(p, InputGestureLock.Option.VOICE_SWIPE_CANCEL, true)

    @Test fun defaultsAreOffAndUnlocked() {
        val p = KeyboardInputPreferences()
        assertFalse(p.t9SpaceSwipeUpZero)
        assertFalse(p.voiceSwipeCancel)
        assertNull(zeroLock(p))
        assertNull(cancelLock(p))
        assertNull(zeroLock(voice))
        assertNull(cancelLock(voice))
    }

    @Test fun zeroOnLocksSwipeCancel() {
        val p = voice.copy(t9SpaceSwipeUpZero = true)
        assertEquals("已被「九键与 14 键 › 空格上滑输入 0」锁定", cancelLock(p)?.message)
        assertNull(zeroLock(p))
    }

    @Test fun swipeCancelOnLocksZero() {
        val p = voice.copy(voiceSwipeCancel = true)
        assertEquals("已被「长按空格 › 上滑取消」锁定", zeroLock(p)?.message)
        assertNull(cancelLock(p))
    }

    /** 长按空格不是语音时上滑取消不起作用（设置页也不显示），不去锁 0。 */
    @Test fun swipeCancelWithoutVoiceHoldDoesNotLock() {
        val p = KeyboardInputPreferences(voiceSwipeCancel = true)
        assertEquals(SpaceHoldAction.CURSOR, p.effectiveSpaceHold)
        assertNull(zeroLock(p))
    }

    @Test fun bothOnLocksNeither() {
        val p = voice.copy(voiceSwipeCancel = true, t9SpaceSwipeUpZero = true)
        assertNull(zeroLock(p))
        assertNull(cancelLock(p))
    }

    @Test fun subtitleFollowsMergedZero() {
        assertTrue(t9SpaceUpZeroSubtitle(false).contains("→"))
        assertFalse(t9SpaceUpZeroSubtitle(true).contains("→"))
    }
}
