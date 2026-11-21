package com.kingzcheung.xime.rime

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import com.kingzcheung.xime.handwriting.HanziPinyin
import com.kingzcheung.xime.util.FileLogger
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 「拼音显示声调」（用户 11-20：「能不能在拼音上显示声调」）：气泡／小签里的拼音按首选候选的读音标调，
 * jiu'shi'zhe'yang → jiù'shì'zhè'yàng。只改显示，上屏、编辑拼音用的还是原来的字母。
 *
 * 词库不带声调，读音来自两张表：
 * - 手写读音表 assets/handwriting/hanzi_pinyin.tsv（逐字，多音字按常用排）：打的音节先把不同拼法分开（行 xíng/háng）；
 * - 词表 assets/pinyin/tone_words.tsv（tools/pinyin_tones/build_tone_words.py 生成）：只收逐字查会标错调的词
 *   （成为 chéng wéi、喜欢 xǐ huan）。一、不按词典标本调。
 * 逐字查时用同拼法里最常用的带调读音，单用就读轻声的虚词（[NEUTRAL_PARTICLES]）照最常用的。
 * 对不上的音节（简拼、双拼按键、九键数字、没打完的音节）原样不标：宁可不标，不标错。
 */
object PinyinTones {
    private const val TAG = "PinyinTones"
    private const val WORDS_ASSET = "pinyin/tone_words.tsv"

    /** 和 build_tone_words.py 的 NEUTRAL_PARTICLES 一致。 */
    internal const val NEUTRAL_PARTICLES = "的地得了着过吗呢吧啊啦嘛呀哇哪么们呗喽咯罢哩噢"

    /** [readings]：字的读音（多音字用 / 隔开，常用在前）；[words]：词 → 逐字读音。 */
    class Data(private val readings: (Int) -> String?, private val words: Map<String, List<String>>) {
        private val longestWord = words.keys.maxOfOrNull { it.length } ?: 0

        internal fun charReading(codePoint: Int, spelled: String): String? {
            val same = readings(codePoint)?.split('/')?.filter { spelling(it) == spelled }.orEmpty()
            val preferred = if (codePoint.toChar().toString() in NEUTRAL_PARTICLES && codePoint < 0x10000) same
                else same.filter { hasToneMark(it) }.ifEmpty { same }
            return preferred.firstOrNull()
        }

        /** 候选每个字从词表来的读音（最长匹配；词表里也有几个读音表排错的单字），不在词表里的为 null。 */
        internal fun wordReadings(chars: List<String>): Array<String?> {
            val result = arrayOfNulls<String>(chars.size)
            var index = 0
            while (index < chars.size) {
                var matched = 0
                for (length in minOf(longestWord, chars.size - index) downTo 1) {
                    val tones = words[chars.subList(index, index + length).joinToString("")] ?: continue
                    if (tones.size != length) continue
                    tones.forEachIndexed { offset, tone -> result[index + offset] = tone }
                    matched = length
                    break
                }
                index += if (matched > 0) matched else 1
            }
            return result
        }
    }

    @Volatile var data: Data? = null
        private set
    private val loading = AtomicBoolean(false)
    private val revisionState = mutableIntStateOf(0)

    /** 两张表读进来后递增；界面读它，读完立即换成标调的拼音。 */
    val revision: Int get() = revisionState.intValue

    /** 开了「拼音显示声调」才读（后台线程，两张表约 0.5 MB）。 */
    fun load(context: Context) {
        if (data != null || !loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "pinyin-tones", isDaemon = true) {
            try {
                val table = HanziPinyin.readTable(app)
                val words = app.assets.open(WORDS_ASSET).bufferedReader(Charsets.UTF_8).useLines(::parseWords)
                data = Data(table::lookup, words)
                revisionState.intValue++
            } catch (error: Exception) {
                FileLogger.w(TAG, "拼音声调表加载失败：${error.message}")
                loading.set(false)
            }
        }
    }

    internal fun parseWords(lines: Sequence<String>): Map<String, List<String>> = lines.mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
    }.toMap()

    /**
     * 把 [display] 里的拼音按 [candidate] 的读音标调，返回同样长度的字符串（只把元音换成带调的字母），
     * 所以光标、句中选择、纠错红字这些按位置画的样式原样能用。
     * 拼音前面已经选定的汉字、隔音符、空格都跳过；光标 | 和折行用的零宽空格不打断音节。
     */
    fun mark(display: String, candidate: String, data: Data): String {
        val chars = hanCharacters(candidate) ?: return display
        if (chars.isEmpty()) return display
        val tokens = syllables(display)
        if (tokens.isEmpty()) return display
        val fromWords = data.wordReadings(chars)
        val out = display.toCharArray()
        var changed = false
        for ((index, token) in tokens.withIndex()) {
            if (index >= chars.size) break
            val spelled = token.joinToString("") { display[it].toString() }.lowercase().replace('ü', 'v')
            val word = fromWords[index]?.takeIf { spelling(it) == spelled }
            val reading = word ?: data.charReading(chars[index].codePointAt(0), spelled) ?: continue
            val toned = Normalizer.normalize(reading, Normalizer.Form.NFC)
            if (toned.length != token.size) continue
            token.forEachIndexed { offset, position ->
                val letter = toned[offset]
                // 只换小写字母；原来是大写的保持原样。
                if (display[position].isLowerCase()) {
                    if (out[position] != letter) { out[position] = letter; changed = true }
                }
            }
        }
        return if (changed) String(out) else display
    }

    /** 候选全是汉字时逐字拆开；夹了字母、数字、表情的候选不标（对不准是哪个音节）。 */
    private fun hanCharacters(candidate: String): List<String>? {
        val chars = mutableListOf<String>()
        var index = 0
        while (index < candidate.length) {
            val codePoint = candidate.codePointAt(index)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return null
            chars += String(Character.toChars(codePoint))
            index += Character.charCount(codePoint)
        }
        return chars
    }

    /** 每个音节里字母的位置。字母是 a–z、ü；| 和零宽空格不打断音节，其他字符（' 空格 汉字 数字）都是分隔。 */
    private fun syllables(display: String): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for ((position, char) in display.withIndex()) {
            when {
                char.lowercaseChar() in 'a'..'z' || char == 'ü' || char == 'Ü' -> current += position
                char == '|' || char == '\u200B' -> Unit
                else -> if (current.isNotEmpty()) { result += current; current = mutableListOf() }
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** 四声的附加符号（NFD 拆开后）：ā á ǎ à。ü 的两点不算。 */
    private const val TONE_MARKS = "\u0304\u0301\u030C\u0300"

    /** 去掉声调后的拼法，ü 记作 v（和打的字母一样）。 */
    internal fun spelling(reading: String): String =
        Normalizer.normalize(Normalizer.normalize(reading, Normalizer.Form.NFD).filter { it !in TONE_MARKS }, Normalizer.Form.NFC)
            .lowercase().replace('ü', 'v')

    private fun hasToneMark(reading: String): Boolean =
        Normalizer.normalize(reading, Normalizer.Form.NFD).any { it in TONE_MARKS }
}
