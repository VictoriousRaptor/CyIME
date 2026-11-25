package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 他 11-23：九键滑行打不出「滑行」，出来的永远是 zhong；拼音就这么多音节，九键、26 键全都滑一遍，哪个滑不出来全修好。
 * 每个音节按经过各键中心的折线划（理想轨迹），用上线的词表和识别代码，要在首选或候选栏给的备选里。
 * 九键只有一个键的音节（a、ba、ca…：连按同一个键）划不出线，本来就是点按，不算。
 * 手指偏离的全量对比在 /mnt/project-files/t9-glide-1123/ 的自检脚本里跑，这里只放理想轨迹，结果固定。
 */
class T9GlideEverySyllableTest {
    private val entries = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.trim().split(' ') }
    private val words = File("src/main/assets/glide_pinyin_words.txt").readLines()
    private val syllables = DoublePinyinScheme.SYLLABLES.distinct()

    // 九键：键宽 100、高 50、缝 4（手机竖屏的比例，上下相邻两键中心只隔 0.72 个键宽高平均）。
    private val t9Keys: Map<Char, LetterKeyRect> = buildMap {
        for (digit in '2'..'9') {
            val index = digit - '1'
            put(digit, LetterKeyRect(index % 3 * 104f, index / 3 * 54f, 100f, 50f))
        }
    }
    private val codeEntries = T9Glide.codeEntries(entries)
    private val index = LinkedHashMap<String, Int>().apply { for ((code, entry) in codeEntries) put(code, entry) }
    private val lines = T9Glide.lines(index.keys)
    private val shapes = T9Glide.shapes(index.keys)
    private val t9Decoder = GlideDecoder(T9Glide.keyboard(t9Keys), index.keys.toList(), T9Glide.MAX_ENDPOINT, minPath = T9Glide.MIN_PATH)
    private val wordOf: (String) -> String? = { code -> index[code]?.let { words.getOrNull(it) } }
    private val syllableWords: Map<String, String> = HashMap<String, String>().also { out ->
        entries.forEachIndexed { i, syllable -> if (syllable.size == 1) T9Glide.digits(syllable[0])?.let { out.putIfAbsent(it, words[i]) } }
    }
    private val syllableDigits: (Int) -> List<String>? = { i -> entries.getOrNull(i)?.map { T9Glide.digits(it)!! } }

    private fun center(keys: Map<Char, LetterKeyRect>, key: Char) = keys.getValue(key).let { (it.x + it.width / 2) to (it.y + it.height / 2) }

    private fun trace(code: String, keys: Map<Char, LetterKeyRect>): Pair<FloatArray, FloatArray> {
        val points = GlideDecoder.collapseRepeats(code).map { center(keys, it) }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        points.zipWithNext().forEach { (a, b) ->
            for (step in 0 until 16) {
                val t = step / 16f
                xs += a.first + (b.first - a.first) * t; ys += a.second + (b.second - a.second) * t
            }
        }
        xs += points.last().first; ys += points.last().second
        return xs.toFloatArray() to ys.toFloatArray()
    }

    /** 和 GlideLexicon.t9Glide 同一套：识别 → 按前面的组合挑 → 备选（含补的单字）。返回打入的编码和备选（编码、词）。 */
    private fun t9(code: String, before: String = ""): Pair<String, List<Pair<String, String>>> {
        val (xs, ys) = trace(code, t9Keys)
        val found = t9Decoder.decode(xs, ys, limit = 1 + GlideLexicon.T9_ALTERNATIVES).map { it.word }
        if (found.isEmpty()) return T9Glide.turnDigits(xs, ys, t9Keys) to emptyList()
        val ranked = if (before.isEmpty()) found
            else T9Glide.preferJoint(found, before, { shapes[it].orEmpty() }, { index[it] }, syllableDigits)
        return ranked.first() to T9Glide.alternativesWithSyllable(ranked, before, GlideLexicon.T9_ALTERNATIVES,
            { lines[it].orEmpty() }, { shapes[it].orEmpty() }, wordOf) { syllableWords[it] }
    }

    @Test fun everySyllableGlidesOnNineKeys() {
        val missing = syllables.mapNotNull { syllable ->
            val code = T9Glide.digits(syllable)!!
            if (GlideDecoder.collapseRepeats(code).length < 2) return@mapNotNull null
            val (digits, alternatives) = t9(code)
            if (digits == code || alternatives.any { it.first == code }) null else "$syllable→${wordOf(digits)}"
        }
        assertEquals("九键滑不出的音节", emptyList<String>(), missing)
    }

    /** 他报的：先打 hua 再划 xing。xing 9464 和 zhong 94664 划出来一样，单看词频永远是 zhong。 */
    @Test fun huaThenXingIsHuaxingNotHuazhong() {
        val hua = T9Glide.digits("hua")!!
        val (digits, alternatives) = t9("9464", before = hua)
        assertEquals("滑行", wordOf(hua + digits))
        assertEquals("备选给整串的词", listOf("华中"), alternatives.map { it.second })
        // 组合为空时照旧（首选 中），行 在备选里。
        val (alone, aloneAlternatives) = t9("9464")
        assertEquals("中", wordOf(alone))
        assertTrue(aloneAlternatives.any { it.second == "行" })
    }

    /** 上下相邻两键（7→4 是 shi）以前不到 0.75 个键，整笔认不出，退回拐点数字 74（起）。 */
    @Test fun verticalNeighboursAreLongEnough() {
        assertEquals("744", t9("744").first)
        // fen 336 和 嗯 36 是同一条线，嗯 常用排第一，分 在备选里（以前整笔认不出，没有备选）。
        val (fen, alternatives) = t9("336")
        assertEquals("36", fen)
        assertTrue(alternatives.any { it.first == "336" })
    }

    @Test fun straightLineDropsKeysAStraightStrokePasses() {
        assertEquals("9364", T9Glide.straightLine("966364"))
        assertEquals("9364", T9Glide.straightLine("9364"))
        assertEquals("9464", T9Glide.straightLine("94654"))
        assertEquals("28", T9Glide.straightLine("258"))
        assertEquals("73", T9Glide.straightLine("753"))
        assertEquals("拐弯的键留着", "4269464", T9Glide.straightLine("42694664"))
        assertEquals("来回同一个键之间不算路过", "454", T9Glide.straightLine("454"))
    }

    /** 前面那段要落在词的音节边界上，连不成词时顺序不变。 */
    @Test fun jointOnlyReordersShapeTiesOnSyllableBoundaries() {
        val codes = listOf("94664", "9464")
        val sameShape: (String) -> List<String> = { if (it == "9464") codes else emptyList() }
        val table = mapOf("4829464" to 5, "48294664" to 9)
        val parts = mapOf(5 to listOf("482", "9464"), 9 to listOf("482", "94664"))
        assertEquals(listOf("9464", "94664"), T9Glide.preferJoint(codes, "482", sameShape, table::get, parts::get))
        // 482 在词里不是一个音节（切成 48 + 29464）：不算。
        val misaligned = mapOf(5 to listOf("48", "29464"), 9 to listOf("482", "94664"))
        assertEquals(listOf("94664", "9464"), T9Glide.preferJoint(codes, "482", sameShape, table::get, misaligned::get))
        assertEquals("前面不到两个数字不挑", codes, T9Glide.preferJoint(codes, "4", sameShape, table::get, parts::get))
        assertEquals("连不成词", codes, T9Glide.preferJoint(codes, "222", sameShape, table::get, parts::get))
    }

    // 26 键：键宽 100、高 135，第二排右移半个键，第三排右移一个半键。
    private val qwerty: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f).forEachIndexed { row, (letters, offset) ->
            letters.forEachIndexed { i, c -> put(c, LetterKeyRect(offset + i * 100f, row * 135f, 100f, 135f)) }
        }
    }

    @Test fun everySyllableGlidesOnTwentySixKeys() {
        val keyboard = GlideKeyboard(100f, qwerty.mapValues { (_, r) -> (r.x + r.width / 2) to (r.y + r.height / 2) })
        val decoder = GlideDecoder(keyboard, pinyinCodes(entries, null), syllables = glideSyllables(entries, null))
        val missing = syllables.filter { syllable ->
            if (GlideDecoder.collapseRepeats(syllable).length < 2) return@filter false
            val (xs, ys) = trace(syllable, qwerty)
            val candidates = decoder.decode(xs, ys)
            // 首选，或候选栏给的两个备选（词表里的编码，[chineseGlideAlternatives]，候选栏只取前 2 个）。
            candidates.firstOrNull()?.word != syllable && syllable !in chineseGlideAlternatives(candidates).take(2)
        }
        assertEquals("26 键滑不出的音节", emptyList<String>(), missing)
    }
}
