package com.kingzcheung.xime.speech

import com.kingzcheung.xime.service.VoiceMarks
import com.kingzcheung.xime.service.normalizeVoiceText
import com.kingzcheung.xime.service.withoutCjkSpaces
import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.*
import org.junit.Test

/** 整句定稿 keeps the model's sentence and clause marks; the 6 s mode keeps turning them into spaces. */
class SpeechPunctuationTest {
    @Test fun `kept marks become full width after Chinese and survive repeated cleanup`() {
        val once = cleanSenseVoiceText("<|zh|><|NEUTRAL|><|Speech|><|withitn|>今天天气不错, 我们出去走走吧.", keepMarks = true)
        assertEquals("今天天气不错，我们出去走走吧。", once)
        assertEquals(once, cleanSpeechText(once, keepMarks = true))
        assertEquals("今天天气不错 我们出去走走吧", cleanSpeechText(once)) // switch off: old spacing
    }

    @Test fun `number notation is not mistaken for sentence marks`() {
        assertEquals("体温36.5度，比昨天高了20%，会议10:30开始，花了1,200元。",
            cleanSpeechText("体温36.5度，比昨天高了20%，会议10:30开始，花了1,200元。", keepMarks = true))
        assertEquals("版本是1.14。", cleanSpeechText("版本是1.14.", keepMarks = true))
    }

    @Test fun `mixed Chinese and English keep ASCII marks after Latin words`() {
        assertEquals("我用 iPhone, 很好用。", cleanSpeechText("我用 iPhone，很好用。", keepMarks = true))
        assertEquals("这个 bug 太严重了！", cleanSpeechText("这个 bug 太严重了!", keepMarks = true))
    }

    @Test fun `apostrophes inside English words stay (#325)`() {
        assertEquals("you'd I'll can't", cleanSpeechText("you'd, I'll, can't."))
        assertEquals("I'll go, you'd stay.", cleanSpeechText("I'll go, you'd stay.", keepMarks = true))
        assertEquals("don’t", cleanSpeechText("don’t"))
        assertEquals("he said yes", cleanSpeechText("he said 'yes'")) // quotes around a word are not kept
    }

    @Test fun `a run of marks collapses to the strongest one`() {
        assertEquals("真的吗？", cleanSpeechText("真的吗，？", keepMarks = true))
        assertEquals("好的。", cleanSpeechText("。好的。。", keepMarks = true))
    }

    @Test fun `dictated punctuation replaces the model marks around it`() {
        assertEquals("你好，我在这里。",
            normalizeVoiceText("你好，逗号，我在这里。", InputLanguage.CHINESE, keepMarks = true))
        assertEquals("你吃饭了吗？", normalizeVoiceText("你吃饭了吗问号。", InputLanguage.CHINESE, keepMarks = true))
        assertEquals("你好，我在", normalizeVoiceText("你好逗号我在")) // switch off: dictated marks still work
        assertEquals("你好 我在", normalizeVoiceText("你好，我在。")) // switch off: model marks become spacing
    }

    @Test fun `auto punctuation can keep only some kinds of marks`() {
        fun norm(text: String, marks: VoiceMarks) = normalizeVoiceText(text, InputLanguage.CHINESE, keepMarks = true, marks = marks)
        val text = "你好，我在这里。你呢？"
        assertEquals("你好，我在这里。你呢？", norm(text, VoiceMarks()))
        assertEquals("你好 我在这里。你呢？", norm(text, VoiceMarks(comma = false)))
        assertEquals("你好，我在这里 你呢？", norm(text, VoiceMarks(period = false)))
        assertEquals("你好，我在这里。你呢", norm(text, VoiceMarks(question = false)))
        assertEquals("你好 我在这里 你呢", norm(text, VoiceMarks(comma = false, period = false, question = false)))
        assertEquals("我用 iPhone 很好用。", norm("我用 iPhone，很好用。", VoiceMarks(comma = false)))
    }

    @Test fun `auto punctuation can drop only the mark at the very end`() {
        fun norm(text: String, marks: VoiceMarks) = normalizeVoiceText(text, InputLanguage.CHINESE, keepMarks = true, marks = marks)
        val noEnd = VoiceMarks(trailing = false)
        assertEquals("你好，我在这里。你呢", norm("你好，我在这里。你呢？", noEnd))
        assertEquals("今天天气不错", norm("今天天气不错。", noEnd))
        assertEquals("版本是1.14", norm("版本是1.14。", noEnd))
        assertEquals("你好，我在这里", norm("你好，我在这里，", noEnd))
        // Dictated marks always stay, also at the end
        assertEquals("你吃饭了吗？", norm("你吃饭了吗问号。", noEnd))
        assertEquals("好的。", norm("好的句号", noEnd))
        // Kinds and end combine: commas off still leaves the inner period
        assertEquals("你好 我在这里。你呢", norm("你好，我在这里。你呢？", VoiceMarks(comma = false, trailing = false)))
        // Default keeps the end
        assertEquals("今天天气不错。", norm("今天天气不错。", VoiceMarks()))
    }

    @Test fun `dropping marks never touches numbers or dictated marks`() {
        fun norm(text: String, marks: VoiceMarks) = normalizeVoiceText(text, InputLanguage.CHINESE, keepMarks = true, marks = marks)
        assertEquals("体温36.5度 会议10:30开始 花了1,200元。", norm("体温36.5度，会议10:30开始，花了1,200元。", VoiceMarks(comma = false)))
        assertEquals("版本是1.14", norm("版本是1.14.", VoiceMarks(period = false)))
        assertEquals("你好，我在这里", norm("你好，逗号，我在这里。", VoiceMarks(comma = false, period = false)))
    }

    @Test fun `regions never repeat a mark and need no space after a mark`() {
        val t = SpeechTranscript(keepMarks = true)
        t.update(1, "今天天气不错。", SpeechBoundary.PAUSE)
        assertEquals("今天天气不错，我们出去吧。", t.update(2, "。我们出去吧。", SpeechBoundary.PAUSE))
        val preview = SpeechTranscript(keepMarks = true)
        preview.update(1, "第一句。", SpeechBoundary.PAUSE)
        assertEquals("第一句，第二句还在说", preview.update(2, "第二句还在说")) // streaming preview has no marks yet
    }

    @Test fun `a pause inside a sentence does not leave a full stop behind`() {
        // The user's own dictation on 1.15.2: every pause segment came back ending in 。 and the session was
        // sent before the end-of-input re-read, so the stops stayed mid-sentence.
        val t = SpeechTranscript(keepMarks = true)
        t.update(1, "你之前好好给我保证了，说的。", SpeechBoundary.PAUSE)
        t.update(2, "不用流式，用整句识别。", SpeechBoundary.PAUSE)
        t.update(3, "那肯定是哪里有问题的？", SpeechBoundary.PAUSE)
        assertEquals("你之前好好给我保证了，说的，不用流式，用整句识别，那肯定是哪里有问题的？你看这能看吗。",
            t.update(4, "你看这能看吗。", SpeechBoundary.PAUSE)) // the latest segment keeps its stop; ？ always stays
        val english = SpeechTranscript(keepMarks = true)
        english.update(1, "hello world.", SpeechBoundary.PAUSE)
        assertEquals("hello world, next one.", english.update(2, "next one.", SpeechBoundary.LIMIT))
    }

    @Test fun `session keeps marks only with whole sentence on`() {
        class Engine : LocalSpeechEngine {
            override val hasRefinement = true
            override val isStreaming = true
            override fun detector(): SpeechDetector = object : SpeechDetector {
                override fun speech(frame: FloatArray) = frame.any { it != 0f }
                override fun reset() { }
            }
            override fun accept(samples: FloatArray) = "预览"
            override fun finishStream() = "预览"
            override fun refine(samples: FloatArray) = cleanSenseVoiceText("你好，世界。")
            override fun refinePunctuated(samples: FloatArray) = cleanSenseVoiceText("你好，世界。", keepMarks = true)
            override fun reset() { }
            override fun close() { }
        }
        val pcm = ByteArray(32000) { if (it % 2 == 0) 64 else 6 }
        assertEquals("你好 世界", LocalSpeechSession(Engine(), {}, {}).apply { acceptPcm(pcm) }.finish())
        assertEquals("你好，世界。", LocalSpeechSession(Engine(), {}, {}, wholeSentence = true).apply { acceptPcm(pcm) }.finish())
    }

    @Test fun `pause segments shown while speaking keep no mark of their own`() {
        // 2026-11-18 用户：断句太碎导致全是逗号句号。A lone pause segment always ends with a mark; only marks inside
        // a segment, ？！ and the final stop stay, and the end-of-input re-read decides the sentence marks.
        val t = SpeechTranscript(keepMarks = true, pauseMarks = false)
        t.update(1, "你之前好好给我保证了，说的。", SpeechBoundary.PAUSE)
        t.update(2, "不用流式，用整句识别。", SpeechBoundary.LIMIT)
        t.update(3, "那肯定是哪里有问题的？", SpeechBoundary.PAUSE)
        assertEquals("你之前好好给我保证了，说的 不用流式，用整句识别那肯定是哪里有问题的？你看这能看吗。",
            t.update(4, "你看这能看吗。", SpeechBoundary.STOP))
        val streaming = SpeechTranscript(keepMarks = true, pauseMarks = false)
        streaming.update(1, "第一句。", SpeechBoundary.PAUSE)
        assertEquals("第一句 第二句还在说", streaming.update(2, "第二句还在说"))
        val english = SpeechTranscript(keepMarks = true, pauseMarks = false)
        english.update(1, "hello world.", SpeechBoundary.PAUSE)
        assertEquals("hello world next one.", english.update(2, "next one.", SpeechBoundary.STOP))
    }

    @Test fun `whole sentence session shows no pause marks while speaking and the re-read decides them`() {
        class Engine : LocalSpeechEngine {
            override val hasRefinement = true
            override val isStreaming = true
            override fun detector(): SpeechDetector = object : SpeechDetector {
                override fun speech(frame: FloatArray) = frame.any { it != 0f }
                override fun reset() { }
            }
            override fun accept(samples: FloatArray) = "预览"
            override fun finishStream() = "预览"
            override fun refine(samples: FloatArray) = "你好"
            override fun refinePunctuated(samples: FloatArray) =
                if (samples.size > 3 * 16000) "你好，世界，你好，世界。" else cleanSenseVoiceText("你好，世界。", keepMarks = true)
            override fun reset() { }
            override fun close() { }
        }
        val loud = ByteArray(32000) { if (it % 2 == 0) 64 else 6 }
        val silence = ByteArray(48000)
        val shown = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = LocalSpeechSession(Engine(), { shown.add(it) }, {}, wholeSentence = true)
        s.acceptPcm(loud); s.acceptPcm(silence); s.acceptPcm(loud)
        assertEquals("你好，世界，你好，世界。", s.finish())
        assertTrue(shown.toString(), shown.any { it.startsWith("你好，世界 预览") })
        assertTrue(shown.toString(), shown.none { it.startsWith("你好，世界。") || it.startsWith("你好，世界，预览") })
    }

    @Test fun `no pause space keeps English word spaces`() {
        assertEquals("你好世界", withoutCjkSpaces("你好 世界"))
        assertEquals("你好，世界", withoutCjkSpaces("你好， 世界"))
        assertEquals("我用iPhone打字", withoutCjkSpaces("我用 iPhone 打字"))
        assertEquals("I love you", withoutCjkSpaces("I love you"))
        assertEquals("hello world你好", withoutCjkSpaces("hello  world 你好"))
        assertEquals("안녕 하세요", withoutCjkSpaces("안녕 하세요"))
        assertEquals("はい分かりました", withoutCjkSpaces("はい 分かりました"))
        assertEquals("体温36.5度", withoutCjkSpaces("体温 36.5度"))
    }
}
