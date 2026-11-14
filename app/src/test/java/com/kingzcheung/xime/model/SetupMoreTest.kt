package com.kingzcheung.xime.model

import com.kingzcheung.xime.ui.keyboard.CornerInputChoice
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「更多配置」：每题从现在的设置开始，只改点过的项；自己调过的字号、只开声音这类不在选项里的值照原样保留。 */
class SetupMoreTest {
    private val now = SetupMoreChoices(SpaceHoldAction.CURSOR, deleteSkipsAssociation = false, clearBeforeCursorOnly = false,
        textSize = TextSizeChoice.STANDARD, feedback = FeedbackChoice.SOUND_AND_VIBRATION, glass = false, autoPunctuation = false)

    @Test fun nothingTappedChangesNothing() {
        assertTrue(SetupMore.changes(now, now).isEmpty())
    }

    @Test fun onlyTappedItemsAreListed() {
        val changes = SetupMore.changes(now, now.copy(spaceHold = SpaceHoldAction.VOICE_TOGGLE, glass = true))
        assertEquals(listOf("长按空格改成「语音开关」。", "打开了「玻璃效果」。"), changes)
    }

    @Test fun defaultsMatchStandardSize() {
        // 新装：候选字 19、按键字号 120% 是「标准」，不会被当成自己调过。
        assertEquals(TextSizeChoice.STANDARD, SetupMore.textSizeOf(19, 1.2f))
        assertEquals(TextSizeChoice.LARGE, SetupMore.textSizeOf(22, 1.4f))
        assertEquals(TextSizeChoice.CUSTOM, SetupMore.textSizeOf(19, 1.0f))
        assertEquals(TextSizeChoice.CUSTOM, SetupMore.textSizeOf(24, 1.2f))
    }

    @Test fun customSizeIsKeptAndListed() {
        assertEquals(listOf(TextSizeChoice.STANDARD, TextSizeChoice.LARGE, TextSizeChoice.CUSTOM),
            SetupMore.textSizeChoices(TextSizeChoice.CUSTOM))
        assertEquals(listOf(TextSizeChoice.STANDARD, TextSizeChoice.LARGE), SetupMore.textSizeChoices(TextSizeChoice.LARGE))
        // 从「大字」改回「保持现在的」不算要写的改动。
        val custom = now.copy(textSize = TextSizeChoice.CUSTOM)
        assertTrue(SetupMore.changes(now.copy(textSize = TextSizeChoice.LARGE), custom).isEmpty())
    }

    @Test fun soundOnlyAppearsOnlyWhenCurrent() {
        assertEquals(FeedbackChoice.SOUND_ONLY, SetupMore.feedbackOf(sound = true, vibration = false))
        assertEquals(FeedbackChoice.NONE, SetupMore.feedbackOf(sound = false, vibration = false))
        assertTrue(FeedbackChoice.SOUND_ONLY !in SetupMore.feedbackChoices(FeedbackChoice.SOUND_AND_VIBRATION))
        assertTrue(FeedbackChoice.SOUND_ONLY in SetupMore.feedbackChoices(FeedbackChoice.SOUND_ONLY))
    }

    @Test fun newQuestionsStartFromDefaults() {
        // 新加的四题默认值就是设置的默认值：角标滑动、不反转、不滑行、有取消按钮；不点就不改。
        assertEquals(CornerInputChoice.SWIPE, now.corner)
        assertEquals(false, now.reverseSwipe)
        assertEquals(GlideChoice.OFF, now.glide)
        assertEquals(true, now.cancelButton)
    }

    @Test fun glideMakesCornersLongPress() {
        val glide = now.copy(glide = GlideChoice.BOTH)
        assertEquals(CornerInputChoice.LONG_PRESS, SetupMore.effectiveCorner(glide))
        assertEquals(listOf("滑行输入改成「中文、英文都滑行」。", "按键角标改成「长按输入」(开着滑行，字母键的滑动用来滑词)。"),
            SetupMore.changes(now, glide))
        // 关掉滑行回到存着的方式，不另写角标。
        val stored = now.copy(corner = CornerInputChoice.SWIPE_BUBBLE)
        assertEquals(CornerInputChoice.SWIPE_BUBBLE, SetupMore.effectiveCorner(stored))
    }

    @Test fun reverseAndCancelButtonAreListed() {
        assertEquals(listOf("打开了「反转上下滑方向」：上滑出上角标、下滑出下角标，删除键也反过来。",
            "关掉了「候选栏取消按钮」：候选词从最左边开始。"),
            SetupMore.changes(now, now.copy(reverseSwipe = true, cancelButton = false)))
    }

    @Test fun chineseOnlyGlideAppearsOnlyWhenCurrent() {
        assertEquals(GlideChoice.CHINESE, GlideChoice.of(english = false, chinese = true))
        assertTrue(GlideChoice.CHINESE !in SetupMore.glideChoices(GlideChoice.OFF))
        assertTrue(GlideChoice.CHINESE in SetupMore.glideChoices(GlideChoice.CHINESE))
    }
}
