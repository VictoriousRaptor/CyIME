package com.kingzcheung.xime.speech

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Adapted from ASRInput EnergyPauseGate (MIT, Cyletix); estimates pauses, never gates PCM. */
internal class SpeechEnergyGate {
    private val recent = ArrayDeque<Float>()
    private var floor = .0001f
    private var contrast = 0f
    private var frames = 0
    private var unvoiced = 0
    fun quiet(rms: Float, vadSpeech: Boolean, speaking: Boolean): Boolean {
        recent.addLast(rms)
        if (recent.size > 125) recent.removeFirst()
        frames++
        unvoiced = if (!vadSpeech && !speaking) unvoiced + 1 else 0
        if (recent.size >= 16 && frames % 8 == 0) {
            val sorted = recent.sorted()
            val low = sorted[((sorted.size - 1) * .15).toInt()]
            val high = sorted[((sorted.size - 1) * .85).toInt()]
            val target = if (high > max(low * 2.5f, .0004f))
                min(high * .24f, low + (high - low) * .15f) else 0f
            contrast = contrast * .65f + target * .35f
            if (unvoiced >= 16) {
                val tail = recent.takeLast(16).sorted()
                if (tail[13] <= max(.0002f, tail[2] * 1.5f)) floor = floor * .75f + tail[8] * .25f
            }
        }
        return rms < max(floor * 1.6f, contrast).coerceIn(.0001f, .5f)
    }
}

internal interface SpeechDetector {
    fun speech(frame: FloatArray): Boolean
    fun reset()
}

internal enum class SpeechBoundary { PAUSE, LIMIT, STOP }

/**
 * 整句定稿：SenseVoice 只在真停顿（≥1 s）或结束时校正一整句，不再每 6 s 切。一口气说得很长时在
 * 28 s 后的第一个短停顿切，最迟 30 s 硬切（SenseVoice 单次输入上限）。2026-10-30 回放：同一
 * SenseVoice 不切段，公开口语 290 段 CER 7.5→5.6 %，噪声 0 dB 34.1→24.8 %。
 */
internal const val WHOLE_SENTENCE_MAX_SEGMENT_MS = 28000
/** 一整句（最长 30 s）在手机上的定稿时间余量；6 s 分段时用引擎自己的 5 s。 */
internal const val WHOLE_SENTENCE_DECODE_TIMEOUT_MS = 15000L

/**
 * 整句定稿结束时把这次语音输入的全部原始音频（不经 VAD 筛）再识别一遍：VAD 没打开的轻声、远场和
 * 噪声里的话也能补回。2026-10-30 回放：按停顿整句 8.33 % → 整段 7.23 %（公开 428 段），
 * 0 dB 噪声 31.7 → 24.8 %，用户本人录音 7.2 → 2.6 %。超过预算就保留按停顿定稿的文字。
 */
internal const val WHOLE_SESSION_BUDGET_MS = 15000L
/** 整段重识别最多保留 5 分钟原始音频（约 19 MB）；更长的这次输入只用按停顿定稿的文字。 */
internal const val WHOLE_SESSION_MAX_SAMPLES = 5 * 60 * 16000

/**
 * Splits [total] samples into blocks of at most [maxSamples] for the end-of-input re-read: each
 * cut is the last real pause (a region end, from [pauses]) at least 8 s into the block, else a
 * hard cut at the limit. Blocks are contiguous and cover every sample once.
 */
internal fun wholeSessionChunks(total: Int, pauses: List<Int>, maxSamples: Int = WHOLE_SENTENCE_MAX_SEGMENT_MS * 16): List<Pair<Int, Int>> {
    val chunks = mutableListOf<Pair<Int, Int>>()
    var start = 0
    while (total - start > maxSamples) {
        val cut = pauses.filter { it > start + 8 * 16000 && it <= start + maxSamples }.maxOrNull() ?: (start + maxSamples)
        chunks.add(start to cut)
        start = cut
    }
    if (total > start) chunks.add(start to total)
    return chunks
}

/** 32 ms acoustic frames, 1024 ms pre-roll, adaptive pauses. Every sample is decoded at most once. */
internal class SpeechSegmenter(
    private val detector: SpeechDetector,
    private val maxSegmentMs: Int,
    private val onAudio: (Long, FloatArray) -> Unit,
    private val onEnd: (Long, FloatArray, SpeechBoundary) -> Unit,
    private val minimumPauseMs: Int = 0,
    private val useEnergyPause: Boolean = true,
    private val resetDetectorOnPause: Boolean = false,
) {
    private val energy = SpeechEnergyGate()
    private val preRoll = ArrayDeque<FloatArray>()
    private val audio = ArrayList<FloatArray>()
    private val pending = FloatArray(512)
    private var pendingSize = 0
    private var id = 0L
    private var samples = 0
    private var quietFrames = 0
    private var speaking = false
    var charactersPerSecond = 0f

    fun accept(input: FloatArray) {
        var offset = 0
        while (offset < input.size) {
            val n = min(512 - pendingSize, input.size - offset)
            input.copyInto(pending, pendingSize, offset, offset + n)
            offset += n; pendingSize += n
            if (pendingSize == 512) { frame(pending.copyOf()); pendingSize = 0 }
        }
    }

    private fun frame(pcm: FloatArray) {
        // Only VAD needs padding. Do not invent PCM at stop or feed padding into the next segment.
        val active = detector.speech(if (pcm.size == 512) pcm else pcm.copyOf(512))
        val energyQuiet = if (useEnergyPause) {
            val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size).toFloat()
            energy.quiet(rms, active, speaking)
        } else false
        // Exact digital silence cannot start a region during VAD's trailing hangover.
        // Otherwise a VAD-only recognizer accepts quiet speech without an RMS veto.
        val quiet = !active || pcm.all { it == 0f } || useEnergyPause && energyQuiet
        if (!speaking) {
            preRoll.addLast(pcm)
            while (preRoll.size > 32) preRoll.removeFirst()
            // A forced cut does not prove that the next frame contains speech. VAD stays warm,
            // so continuous speech resumes normally, while silence must never start a new region.
            if (quiet) return
            speaking = true; id++; quietFrames = 0; charactersPerSecond = 0f
            for (part in preRoll) { audio.add(part); samples += part.size; onAudio(id, part) }
            preRoll.clear()
        } else {
            audio.add(pcm); samples += pcm.size; onAudio(id, pcm)
            quietFrames = if (quiet) quietFrames + 1 else 0
        }
        val pauseMs = max(minimumPauseMs, when {
            samples < 24000 || charactersPerSecond in .01f..3.5f -> 600
            charactersPerSecond >= 6.5f -> 256
            else -> 384
        })
        when {
            quietFrames * 32 >= pauseMs -> finish(SpeechBoundary.PAUSE)
            // Prefer a low-energy break near the cap before forcing an acoustic cut.
            samples >= maxSegmentMs * 16 && quietFrames >= 8 -> finish(SpeechBoundary.PAUSE)
            samples >= (maxSegmentMs + 2000) * 16 -> finish(SpeechBoundary.LIMIT)
        }
    }

    fun finishInput() {
        if (pendingSize > 0) { frame(pending.copyOf(pendingSize)); pendingSize = 0 }
        if (speaking) finish(SpeechBoundary.STOP)
        preRoll.clear()
        detector.reset()
    }

    private fun finish(reason: SpeechBoundary) {
        val all = FloatArray(samples)
        var offset = 0
        audio.forEach { it.copyInto(all, offset); offset += it.size }
        onEnd(id, all, reason)
        audio.clear(); preRoll.clear(); samples = 0; quietFrames = 0; speaking = false
        // A real pause can clear loudness history before a softer speaker. A forced cut
        // must keep VAD warm so continuous speech is not treated as a new recording.
        if (resetDetectorOnPause && reason == SpeechBoundary.PAUSE) detector.reset()
    }
}

private val richTags = Regex("<\\|[^|]*\\|>")
private val speechPunctuation = Regex("[\\p{P}]")
private val speechWhitespace = Regex("[\\p{Z}\\s]+")

/** Sentence and clause marks kept by 整句定稿 (full-width after Chinese text). */
private val keptMarks = mapOf(',' to '，', '，' to '，', '。' to '。', '.' to '。', '？' to '？', '?' to '？',
    '！' to '！', '!' to '！', '、' to '、', '；' to '；', ';' to '；', '：' to '：', ':' to '：')

/** Model-added sentence punctuation becomes spacing; numeric notation keeps its meaning.
 * Spoken punctuation names stay as words until the final UI boundary, so repeated local
 * cleanup (SenseVoice -> transcript -> UI) cannot erase an explicitly dictated symbol.
 * [keepMarks] (整句定稿): clause/sentence marks stay — full-width after Chinese, ASCII after Latin —
 * and a run of marks collapses to its strongest one, so repeated cleanup is idempotent.
 */
internal fun cleanSpeechText(text: String, keepMarks: Boolean = false): String {
    if (keepMarks) return keepSpeechMarks(cleanSpeechText(markPlaceholders(text)))
    return cleanSpeechTextOnly(text)
}

// Marks are swapped for private-use placeholders, the ordinary cleanup runs (numbers, tags,
// spacing), then the placeholders become real marks again. Numeric '.', ',' and ':' are left
// alone here so the ordinary cleanup still decides them.
private val placeholder = keptMarks.values.distinct().withIndex().associate { (i, m) -> m to ('' + i) }
private val fromPlaceholder = placeholder.entries.associate { (m, p) -> p to m }
private fun markPlaceholders(text: String): String = buildString {
    text.forEachIndexed { i, c ->
        val mark = keptMarks[c]
        val numeric = c in ".,:" && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true
        append(if (mark != null && !numeric) placeholder.getValue(mark) else c)
    }
}
private val strength = "、，；：。！？"
private fun keepSpeechMarks(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val mark = fromPlaceholder[c]
        if (mark == null) { out.append(c); i++; continue }
        // Collapse a run of marks (and the spaces between them) into the strongest one.
        var best: Char = mark; var j = i + 1
        while (j < text.length && (fromPlaceholder[text[j]] != null || text[j] == ' ')) {
            fromPlaceholder[text[j]]?.let { if (strength.indexOf(it) > strength.indexOf(best)) best = it }
            j++
        }
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        val left = out.lastOrNull()
        // Digits are neutral: "版本 1.14。" stays Chinese, "version 2." stays ASCII.
        val word = out.lastOrNull { !it.isDigit() && it != ' ' && it != '.' }
        if (left != null) {
            if (word != null && word.code < 128 && word.isLetter()) {
                out.append(when (best) { '，', '、' -> ','; '。' -> '.'; '？' -> '?'; '！' -> '!'; '；' -> ';'; else -> ':' })
                if (j < text.length) out.append(' ')
            } else out.append(best)
        }
        i = j
    }
    return out.toString().trim()
}

private fun cleanSpeechTextOnly(text: String): String {
    val plain = richTags.replace(text, "")
    fun neighbor(from: Int, step: Int): Char? {
        var index = from
        while (index in plain.indices) {
            if (!plain[index].isWhitespace()) return plain[index]
            index += step
        }
        return null
    }
    fun startsNumber(from: Int): Boolean {
        var index = from
        while (index in plain.indices && plain[index].isWhitespace()) index++
        if (plain.getOrNull(index) in listOf('+', '-', '−', '－')) index++
        while (index in plain.indices && plain[index].isWhitespace()) index++
        val first = plain.getOrNull(index)
        return first?.isDigit() == true || first == '(' || first == '（' ||
            (first == '.' && plain.getOrNull(index + 1)?.isDigit() == true)
    }
    return speechWhitespace.replace(buildString {
        plain.forEachIndexed { i, c ->
            if (!speechPunctuation.matches(c.toString())) {
                append(c)
                return@forEachIndexed
            }
            val before = plain.getOrNull(i - 1)
            val after = plain.getOrNull(i + 1)
            val left = neighbor(i - 1, -1)
            val right = neighbor(i + 1, 1)
            val numeric = when (c) {
                '.', '．' -> after?.isDigit() == true && (before?.isDigit() == true ||
                    before == null || before.isWhitespace() || before in "+-−×÷/*=(（")
                ',' -> before?.isDigit() == true && after?.isDigit() == true
                '-', '－' -> startsNumber(i + 1)
                '/', '／', '*', '＊' -> (left?.isDigit() == true || left == ')' || left == '）') &&
                    startsNumber(i + 1)
                ':', '：' -> left?.isDigit() == true && right?.isDigit() == true
                '%', '％' -> left?.isDigit() == true
                '(', '（' -> startsNumber(i + 1)
                ')', '）' -> left?.isDigit() == true || left == '%' || left == '％' || left == ')' || left == '）'
                // 英文缩写里的撇号是词的一部分（you'd、can't、I'll，#325），只在两边都是字母时留着。
                '\'', '’' -> before?.isLetter() == true && after?.isLetter() == true
                else -> false
            }
            append(if (numeric) c else ' ')
        }
    }, " ").trim()
}

// Remove SenseVoice's CJK token-spacing before punctuation normalization so acoustic
// and punctuation boundaries survive. Latin word spacing is never removed.
internal fun cleanSenseVoiceText(text: String, detectedLanguage: String = "", keepMarks: Boolean = false): String {
    // sherpa-onnx separates the language/event prefix from text. A no-speech classification
    // must be handled before tag cleanup; it is not a word-confidence score or a blacklist.
    if (detectedLanguage.removePrefix("<|").removeSuffix("|>").equals("nospeech", ignoreCase = true) ||
        text.contains("<|nospeech|>", ignoreCase = true)) return ""
    val plain = richTags.replace(text, "")
    fun cjk(c: Char) = c in '\u3040'..'\u30ff' || c in '\u3400'..'\u9fff'
    val joined = buildString {
        var i = 0
        while (i < plain.length) {
            val c = plain[i]
            if (!c.isWhitespace()) { append(c); i++; continue }
            var next = i + 1
            while (next < plain.length && plain[next].isWhitespace()) next++
            val left = lastOrNull()
            val right = plain.getOrNull(next)
            val tokenSpace = left != null && right != null &&
                (cjk(left) && (cjk(right) || right.isDigit()) || left.isDigit() && cjk(right))
            if (!tokenSpace) append(' ')
            i = next
        }
    }
    return cleanSpeechText(joined, keepMarks)
}

/**
 * Identified regions allow late second-pass results to replace exactly their preview.
 * [pauseMarks] = false (按停顿定稿时显示的文字): the second pass sees one pause segment alone and always ends it with
 * a mark, so that mark says nothing about the sentence. It is dropped at every pause or forced cut (？！ stay);
 * marks inside a segment stay, and the end-of-input re-read puts the real sentence marks back
 * (2026-11-18 用户：断句太碎导致全是逗号句号).
 */
internal class SpeechTranscript(private val keepMarks: Boolean = false, private val pauseMarks: Boolean = true) {
    private data class Region(var text: String = "", var boundary: SpeechBoundary = SpeechBoundary.STOP)
    private val regions = sortedMapOf<Long, Region>()
    fun update(id: Long, text: String, boundary: SpeechBoundary? = null): String {
        val region = regions.getOrPut(id) { Region() }
        region.text = cleanSpeechText(text, keepMarks)
        if (boundary != null) region.boundary = boundary
        return text()
    }
    private fun shown(region: Region): String =
        if (keepMarks && !pauseMarks && region.boundary != SpeechBoundary.STOP) region.text.trimEnd(*pauseOnlyMarks)
        else region.text
    fun text(): String = buildString {
        var previous = SpeechBoundary.STOP
        for (region in regions.values) {
            val regionText = shown(region)
            if (regionText.isNotEmpty()) {
                // The second pass ends every pause segment with a full stop, but most pauses fall inside a
                // sentence (on the user's recordings the whole re-read keeps a stop at 108 of 397). Once more
                // speech follows, the stop reads as a comma; ？！ stay, and the end-of-input re-read decides
                // the real sentence ends when the session is stopped rather than cancelled.
                if (keepMarks && isNotEmpty() && previous != SpeechBoundary.STOP && last() in "。.") {
                    val ascii = last() == '.'
                    setLength(length - 1)
                    append(if (ascii) ',' else '，')
                }
                // A kept mark already separates the sentences: no space, and never two marks in a row.
                if (keepMarks && isNotEmpty() && last() in "，。？！、；：") {
                    append(regionText.trimStart('，', '。', '？', '！', '、', '；', '：'))
                    previous = region.boundary
                    continue
                }
                if (isNotEmpty() && (previous != SpeechBoundary.LIMIT ||
                    last().isLetterOrDigit() && last().code < 128 && regionText.first().code < 128)) append(' ')
                append(regionText)
            }
            previous = region.boundary
        }
    }
}

/** Marks a lone pause segment always ends with; ？！ depend on the words, so they stay. */
private val pauseOnlyMarks = charArrayOf('，', '。', '、', '；', '：', ',', '.', ';', ':')
