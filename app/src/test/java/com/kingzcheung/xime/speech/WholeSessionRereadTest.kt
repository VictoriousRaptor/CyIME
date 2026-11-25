package com.kingzcheung.xime.speech

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 整句定稿: at the end of the input every captured sample is read once more, VAD or not. */
class WholeSessionRereadTest {
    /** VAD only hears loud frames; quiet speech never opens a region. */
    private open class Engine : LocalSpeechEngine {
        override val hasRefinement = true
        override val isStreaming = true
        val reread = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override fun detector(): SpeechDetector = object : SpeechDetector {
            override fun speech(frame: FloatArray) = frame.any { kotlin.math.abs(it) > .03f }
            override fun reset() { }
        }
        override fun accept(samples: FloatArray) = "预览"
        override fun finishStream() = "预览"
        override fun refine(samples: FloatArray) = "段"
        override fun refinePunctuated(samples: FloatArray): String { reread.add(samples.size); return "段" }
        override fun reset() { }
        override fun close() { }
    }
    private fun pcm(samples: Int, value: Int) = ByteArray(samples * 2) { if (it % 2 == 0) value.toByte() else (value shr 8).toByte() }
    private val loud = 1600   // 0.049 FS: the VAD hears it
    private val quiet = 300   // 0.009 FS: the VAD misses it, the re-read still gets it

    @Test fun `end of input re-reads all audio including speech the VAD missed`() {
        val engine = object : Engine() {
            override fun refinePunctuated(samples: FloatArray): String {
                reread.add(samples.size)
                return if (samples.size >= 4 * 16000) "整段识别的结果。" else "段"
            }
        }
        val s = LocalSpeechSession(engine, {}, {}, wholeSentence = true)
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, quiet)); s.acceptPcm(pcm(16000, loud))
        assertEquals("整段识别的结果。", s.finish())
        assertEquals(64000, engine.reread.last()) // every captured sample, once
    }

    @Test fun `SenseVoice alone keeps punctuation, decodes the last sentence and re-reads the whole input`() {
        val engine = object : Engine() {
            override val hasRefinement = false
            override val isStreaming = false
            override fun accept(samples: FloatArray) = error("no streaming model")
            override fun finishStream() = error("no streaming model")
            override fun refine(samples: FloatArray) = error("whole sentence keeps punctuation")
            override fun refinePunctuated(samples: FloatArray): String {
                reread.add(samples.size)
                return if (samples.size >= 4 * 16000) "整段识别的结果。" else "段。"
            }
        }
        val s = LocalSpeechSession(engine, {}, {}, wholeSentence = true)
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, quiet)); s.acceptPcm(pcm(16000, loud))
        assertEquals("整段识别的结果。", s.finish())
        assertEquals(64000, engine.reread.last())
        assertTrue("both sentences are decoded at their pause/stop first", engine.reread.size >= 3)
    }

    @Test fun `SenseVoice alone with the switch off keeps the old segments without a re-read`() {
        val engine = object : Engine() {
            override val hasRefinement = false
            override val isStreaming = false
        }
        LocalSpeechSession(engine, {}, {}).apply { acceptPcm(pcm(16000, loud)); finish() }
        assertTrue(engine.reread.isEmpty())
    }

    @Test fun `a rolling-preview recognizer such as Japanese Zipformer is not switched to whole sentences`() {
        val engine = object : Engine() {
            override val hasRefinement = false
            override val isStreaming = false
            override val hasRollingPreview = true
            override fun preview(samples: FloatArray) = "预览"
        }
        LocalSpeechSession(engine, {}, {}, wholeSentence = true).apply { acceptPcm(pcm(16000, loud)); finish() }
        assertTrue(engine.reread.isEmpty())
    }

    @Test fun `a quiet room with no speech region commits nothing and is not re-read`() {
        // 2026-10-31 tablet: about 2 s of room noise, SenseVoice alone re-read it and committed 「我。」.
        for (streaming in listOf(false, true)) {
            val engine = object : Engine() {
                override val hasRefinement = streaming
                override val isStreaming = streaming
                override fun refinePunctuated(samples: FloatArray): String { reread.add(samples.size); return "我。" }
            }
            val output = java.util.Collections.synchronizedList(mutableListOf<String>())
            val s = LocalSpeechSession(engine, { output.add(it) }, {}, wholeSentence = true)
            s.acceptPcm(pcm(32000, quiet))
            assertEquals("", s.finish())
            assertTrue(engine.reread.isEmpty()); assertFalse(output.contains("我。"))
        }
    }

    @Test fun `switch off or no second pass never re-reads`() {
        val off = Engine()
        LocalSpeechSession(off, {}, {}).apply { acceptPcm(pcm(16000, loud)); finish() }
        assertTrue(off.reread.isEmpty())
    }

    @Test fun `long input is cut at the last pause before 28 s and covers every sample once`() {
        val s = 16000
        assertEquals(listOf(0 to 25 * s, 25 * s to 40 * s, 40 * s to 60 * s, 60 * s to 70 * s),
            wholeSessionChunks(70 * s, listOf(12 * s, 25 * s, 40 * s, 60 * s)))
        assertEquals(listOf(0 to 28 * s, 28 * s to 56 * s, 56 * s to 70 * s), wholeSessionChunks(70 * s, emptyList()))
        assertEquals(listOf(0 to 5 * s), wholeSessionChunks(5 * s, listOf(2 * s)))
    }

    @Test fun `over budget keeps the text finalized at the pauses`() {
        val engine = object : Engine() {
            override fun refinePunctuated(samples: FloatArray): String {
                reread.add(samples.size)
                if (samples.size >= 4 * 16000) { Thread.sleep(200); return "太慢" }
                return "段"
            }
        }
        val s = LocalSpeechSession(engine, {}, {}, wholeSentence = true, wholeSessionBudgetMs = 50)
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, quiet)); s.acceptPcm(pcm(16000, loud))
        assertNotEquals("太慢", s.finish())
    }

    @Test fun `cancel during the re-read leaves no late result and a successor waits for it`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val engine = object : Engine() {
            override fun refinePunctuated(samples: FloatArray): String {
                if (samples.size >= 4 * 16000) { entered.countDown(); release.await(2, TimeUnit.SECONDS); return "迟到" }
                return "段"
            }
        }
        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = LocalSpeechSession(engine, { output.add(it) }, {}, wholeSentence = true)
        s.acceptPcm(pcm(16000, loud)); s.acceptPcm(pcm(32000, quiet)); s.acceptPcm(pcm(16000, loud))
        var result: String? = null
        val finisher = Thread { result = s.finish() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        s.cancel()
        val successor = Thread { s.awaitIdle() }.apply { start() }
        successor.join(200)
        assertTrue("awaitIdle must wait for the native decode in progress", successor.isAlive)
        release.countDown(); finisher.join(2000); successor.join(2000)
        assertFalse(successor.isAlive)
        assertNotEquals("迟到", result); assertFalse(output.contains("迟到"))
    }
}
