package com.kingzcheung.xime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-16 用户：噪音大时 SenseVoice / X-ASR 一直不断句，手动结束后已显示的字被吞掉。
 * 第二遍（按停顿或结束时整段）只剩不到一半的字时，留下已显示的文字。
 */
class NoisySecondPassTest {
    private open class Engine(private val preview: String, private val second: String) : LocalSpeechEngine {
        override val hasRefinement = true
        override val isStreaming = true
        override val lockedChinese = true
        override fun detector(): SpeechDetector = object : SpeechDetector {
            override fun speech(frame: FloatArray) = frame.any { kotlin.math.abs(it) > .03f }
            override fun reset() { }
        }
        override fun accept(samples: FloatArray) = preview
        override fun finishStream() = preview
        override fun refine(samples: FloatArray) = second
        override fun refinePunctuated(samples: FloatArray) = second
        override fun reset() { }
        override fun close() { }
    }
    private fun pcm(samples: Int, value: Int) = ByteArray(samples * 2) { if (it % 2 == 0) value.toByte() else (value shr 8).toByte() }
    private val loud = 1600

    @Test fun `a second pass that heard almost nothing keeps the visible text`() {
        assertTrue(secondPassLostText("今天下午三点开会", ""))
        assertTrue(secondPassLostText("今天下午三点开会", "嗯。"))
        assertFalse(secondPassLostText("今天下午三点开会", "今天下午开会。"))
        // 已显示的不到 4 个字：照旧听第二遍（噪音里流式模型冒出的一两个字要能被清掉）。
        assertFalse(secondPassLostText("嗯啊", ""))
        // 标点、空格不算字。
        assertFalse(secondPassLostText("你好，世界。", "你好世界"))
    }

    @Test fun `per pause an empty second pass no longer erases the preview`() {
        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = LocalSpeechSession(Engine("今天下午三点开会", ""), { output.add(it) }, {})
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, 0)) // a real pause ends the region
        assertEquals("今天下午三点开会", s.finish())
        assertFalse("the preview never disappears on screen", output.contains(""))
    }

    @Test fun `manual stop in noise keeps the preview when the whole re-read comes back nearly empty`() {
        val s = LocalSpeechSession(Engine("今天下午三点开会别迟到", "嗯。"), {}, {}, wholeSentence = true)
        s.acceptPcm(pcm(48000, loud)) // no pause at all, then the user stops
        assertEquals("今天下午三点开会别迟到", s.finish())
    }

    @Test fun `continuous noise past the 30 s cut keeps every visible sentence`() {
        val engine = object : Engine("一直在说话没有停顿", "") {
            override fun detector(): SpeechDetector = object : SpeechDetector {
                override fun speech(frame: FloatArray) = true // the VAD hears speech in the noise all the time
                override fun reset() { }
            }
        }
        val s = LocalSpeechSession(engine, {}, {}, wholeSentence = true)
        repeat(35) { s.acceptPcm(pcm(16000, loud)) } // 35 s: one forced cut at ~30 s, then the stop region
        val text = s.finish()
        assertEquals(2, text.split("一直在说话没有停顿").size - 1)
    }

    @Test fun `a real correction that is a little shorter still replaces the preview`() {
        val s = LocalSpeechSession(Engine("我想吃饭了吧吧吧", "我想吃饭了"), {}, {})
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, 0))
        assertEquals("我想吃饭了", s.finish())
    }

    @Test fun `a short noise preview is still cleared by an empty second pass`() {
        val s = LocalSpeechSession(Engine("嗯啊", ""), {}, {})
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, 0))
        assertEquals("", s.finish())
    }
}
