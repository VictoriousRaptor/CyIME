package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宿主重启同一个输入框（QQ、B 站评论框上屏后重设文字）时，已经打的拼音、九键数字和还在排队的键不能丢（11-25「打字漏字」）。
 */
class RestartResumeTest {
    @Test fun candidateBarKeepsPinyinAndQueuedKeys() {
        // 默认编码在候选栏：框里没有编码，以前一重启就清空拼音、作废排队的键。
        assertEquals(RestartResume.KEEP, restartResume(inputBox = false, inlineActive = false, inlineText = null,
            composing = true, keysQueued = false))
        assertEquals(RestartResume.KEEP, restartResume(inputBox = false, inlineActive = false, inlineText = null,
            composing = false, keysQueued = true))
    }

    @Test fun inputBoxStillAdoptsFrozenCode() {
        assertEquals(RestartResume.ADOPT_INLINE, restartResume(inputBox = true, inlineActive = true, inlineText = "ni",
            composing = true, keysQueued = true))
        // 编码写进了框却已经不在打拼音：照原来的流程重启，不保留。
        assertEquals(RestartResume.NONE, restartResume(inputBox = true, inlineActive = true, inlineText = "ni",
            composing = false, keysQueued = true))
    }

    @Test fun inputBoxBeforeTheFirstCodeIsWrittenKeepsQueuedKeys() {
        // 上屏后紧跟着按的键还没写进框：框里没有要找回的编码，键照样打进去。
        assertEquals(RestartResume.KEEP, restartResume(inputBox = true, inlineActive = false, inlineText = null,
            composing = false, keysQueued = true))
    }

    @Test fun caretMovedByTheHostRestartsAsBefore() {
        // B 站评论框写满一行时重设文字、光标放回开头：接着上屏会写到开头，照原来的流程清掉。
        assertEquals(RestartResume.NONE, restartResume(inputBox = false, inlineActive = false, inlineText = null,
            composing = true, keysQueued = true, caretContinues = false))
    }

    @Test fun nothingTypedRestartsAsBefore() {
        assertEquals(RestartResume.NONE, restartResume(inputBox = false, inlineActive = false, inlineText = null,
            composing = false, keysQueued = false))
        assertEquals(RestartResume.NONE, restartResume(inputBox = true, inlineActive = false, inlineText = null,
            composing = false, keysQueued = false))
    }
}
