package com.kingzcheung.xime.service

import java.text.Normalizer

/**
 * 越南语 Telex：越南手机键盘（三星自带、Gboard、Laban Key）最常用的打法，字母键打出声调和变音字母，
 * 光标前正在打的词随打随换（和韩语拼音节一样替换光标前的字，不用组字区）。
 *
 * - 变音：aa → â、aw → ă、ee → ê、oo → ô、ow → ơ、uw → ư、uo 后打 w → ươ、词首 dd → đ。w 可以打在词尾（thuongw → thương）。
 * - 声调：s 锐、f 玄、r 问、x 跌、j 重，z 去掉声调；声调键可以打在词尾（vieetj → việt），位置自动放。
 * - 同一个键再按一次撤销并打出这个字母：ass → as、aaa → aa、uww → uw、ddd → dd。
 * - 单独一个 w 不变 ư（不然 window、wifi 会坏），ư 打 uw。
 * - 拼出来不是越南语音节就不加符号（class、facebook、Google 照打），加过符号后接着打成了不是越南语的词，换回按下的原样字母
 *   （these：thế 再打 s 变回 these）。
 * - 声调位置用老写法 hòa、thủy、khỏe（字幕语料里老写法约是新写法的 3 倍）：有 â ă ê ô ơ ư 时放在它上面（ươ 放在 ơ），
 *   一个元音放在它上面，后面有辅音时放在最后一个元音，三个元音放中间，两个元音放第一个。qu、gi 后面的 u、i 算声母。
 *
 * [word] 是光标前正在打的词，[type] 返回按下 [key] 后整个词应该变成什么；返回 word + key 就是照常追加字母。
 */
internal class VietnameseTelex {
    /** 这个词按下的原样字母（换回原样时用）和上一次给出的样子；光标前的词对不上时从它重新算。 */
    private var raw = ""
    private var shown = ""

    fun reset() {
        raw = ""
        shown = ""
    }

    fun type(word: String, key: Char): String {
        if (word != shown) raw = word
        val plain = word + key
        val result = transform(word, key)
        when {
            result == null -> {
                raw += key
                // 加过符号的词接着打成了不是越南语的样子：换回原样字母。
                shown = if (plain != raw && !plausible(plain)) raw else plain
            }
            result.undo && !plausible(result.text) && hasMarks(result.text) -> {
                // 撤销后剩下的仍带符号又不像越南语（thế 再打 s 是 thês）：是在打别的语言的词，换回原样字母（these）。
                raw += key
                shown = raw
            }
            result.undo -> {
                // 撤销以后按下的就是看到的样子（ass → as，再打 s 是 ass）。
                raw = result.text
                shown = result.text
            }
            else -> {
                raw += key
                shown = result.text
            }
        }
        return shown
    }

    private class Change(val text: String, val undo: Boolean = false)

    private fun transform(word: String, key: Char): Change? {
        val syllable = parse(word) ?: return null
        val lower = key.lowercaseChar()
        return when {
            lower in TONE_KEYS -> toneChange(syllable, key)
            lower == 'z' -> if (syllable.tone >= 0 && syllable.hasVowel()) Change(render(syllable.letters, -1)) else null
            lower == 'w' -> hornChange(syllable, key)
            lower == 'a' || lower == 'e' || lower == 'o' -> hatChange(syllable, key)
            lower == 'd' -> strokeChange(syllable, key)
            else -> null
        }
    }

    private fun toneChange(syllable: Syllable, key: Char): Change? {
        if (!syllable.hasVowel()) return null
        val tone = TONE_KEYS.indexOf(key.lowercaseChar())
        if (syllable.tone == tone) return Change(render(syllable.letters, -1) + key, undo = true)
        if (!plausible(syllable.letters, tone)) return null
        return Change(render(syllable.letters, tone))
    }

    /** w：uo → ươ，否则依次试 u → ư、o → ơ、a → ă，取第一个拼得通的；已经有 ư ơ ă 时撤销。 */
    private fun hornChange(syllable: Syllable, key: Char): Change? {
        val letters = syllable.letters
        val (start, end) = nucleus(letters) ?: return null
        // uo、ưo、uơ → ươ（先打了一半也补全）。
        for (i in start until end - 1) {
            val first = letters[i].char
            val second = letters[i + 1].char
            if (first in "uư" && second in "oơ" && !(first == 'ư' && second == 'ơ')) {
                val next = letters.toMutableList()
                next[i] = letters[i].copy(char = 'ư')
                next[i + 1] = letters[i + 1].copy(char = 'ơ')
                return if (plausible(next, syllable.tone)) Change(render(next, syllable.tone)) else null
            }
        }
        if ((start until end).any { letters[it].char in "ươă" }) {
            val undone = letters.map { it.copy(char = HORN_UNDO[it.char] ?: it.char) }
            return Change(render(undone, syllable.tone) + key, undo = true)
        }
        for ((from, to) in listOf('u' to 'ư', 'o' to 'ơ', 'a' to 'ă')) {
            val at = (start until end).firstOrNull { letters[it].char == from } ?: continue
            val next = letters.toMutableList()
            next[at] = letters[at].copy(char = to)
            if (validNucleus(next) && plausible(next, syllable.tone)) return Change(render(next, syllable.tone))
        }
        return null
    }

    /** aa → â、ee → ê、oo → ô（同一个元音可以隔着后面的辅音：caan → cân）；已经是 â ê ô 时撤销。 */
    private fun hatChange(syllable: Syllable, key: Char): Change? {
        val letters = syllable.letters
        val (start, end) = nucleus(letters) ?: return null
        val plainVowel = key.lowercaseChar()
        val hatted = HAT[plainVowel]!!
        val hatAt = (start until end).lastOrNull { letters[it].char == hatted }
        if (hatAt != null) {
            val next = letters.toMutableList()
            next[hatAt] = letters[hatAt].copy(char = plainVowel)
            return Change(render(next, syllable.tone) + key, undo = true)
        }
        val at = (start until end).lastOrNull { letters[it].char == plainVowel } ?: return null
        val next = letters.toMutableList()
        next[at] = letters[at].copy(char = hatted)
        return if (validNucleus(next) && plausible(next, syllable.tone)) Change(render(next, syllable.tone)) else null
    }

    /** 词首 dd → đ，đ 再打 d 撤销成 dd。只认刚打的这一个 d（did 不变 đi）。 */
    private fun strokeChange(syllable: Syllable, key: Char): Change? {
        val only = syllable.letters.singleOrNull() ?: return null
        return when (only.char) {
            'd' -> Change(render(listOf(only.copy(char = 'đ')), -1))
            'đ' -> Change(render(listOf(only.copy(char = 'd')), -1) + key, undo = true)
            else -> null
        }
    }

    /** 一个字母：去掉声调后的小写字母（â、ư、đ 这些带着变音），和原来是不是大写。 */
    internal data class Letter(val char: Char, val upper: Boolean)
    internal class Syllable(val letters: List<Letter>, val tone: Int) {
        fun hasVowel() = letters.any { it.char in VOWELS }
    }

    internal companion object {
        /** s f r x j：锐、玄、问、跌、重。 */
        const val TONE_KEYS = "sfrxj"
        private val TONE_MARKS = charArrayOf('́', '̀', '̉', '̃', '̣')
        const val VOWELS = "aăâeêioôơuưy"
        /** a–z 之外越南语的字母（不含声调），键盘长按出的就是这些。 */
        const val SHAPED = "ăâêôơưđ"
        private val HAT = mapOf('a' to 'â', 'e' to 'ê', 'o' to 'ô')
        private val HORN_UNDO = mapOf('ư' to 'u', 'ơ' to 'o', 'ă' to 'a')
        private val UNSHAPE = mapOf('ă' to 'a', 'â' to 'a', 'ê' to 'e', 'ô' to 'o', 'ơ' to 'o', 'ư' to 'u', 'đ' to 'd')

        private val INITIALS = setOf("", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l", "m", "n", "ng",
            "ngh", "nh", "p", "ph", "qu", "r", "s", "t", "th", "tr", "v", "x")
        private val FINALS = setOf("", "c", "ch", "m", "n", "ng", "nh", "p", "t")
        private val STOP_FINALS = setOf("c", "ch", "p", "t")
        /** 后面不跟辅音的元音组合。 */
        private val OPEN = setOf("a", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
            "ai", "ao", "au", "âu", "ay", "ây", "eo", "êu", "ia", "iu", "oa", "oe", "oi", "ôi", "ơi", "ua", "uê", "ui",
            "uơ", "ưa", "ưi", "ưu", "uy", "iêu", "yêu", "oai", "oay", "oeo", "uây", "uôi", "ươi", "ươu", "uya", "uyu")
        /** 后面跟辅音的元音组合。 */
        private val CLOSED = setOf("a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
            "iê", "yê", "uô", "ươ", "oa", "oă", "oe", "oo", "uâ", "uê", "uy", "uyê")
        /** 不看变音时拼得通的元音组合（打到一半还没加 ^ 或 w：tie → tiê、nguoi → người）。 */
        private val OPEN_SKELETONS = OPEN.mapTo(HashSet()) { unshape(it) }
        private val CLOSED_SKELETONS = CLOSED.mapTo(HashSet()) { unshape(it) }

        private fun unshape(text: String) = buildString { for (c in text) append(UNSHAPE[c] ?: c) }

        /** 拆出字母和声调；有不是越南语字母的字符或两个声调时返回 null。 */
        fun parse(word: String): Syllable? {
            if (word.isEmpty()) return null
            val letters = ArrayList<Letter>(word.length)
            var tone = -1
            for (char in word) {
                val base = StringBuilder(2)
                for (part in Normalizer.normalize(char.toString(), Normalizer.Form.NFD)) {
                    val mark = TONE_MARKS.indexOf(part)
                    if (mark < 0) base.append(part)
                    else if (tone >= 0) return null
                    else tone = mark
                }
                val shaped = Normalizer.normalize(base, Normalizer.Form.NFC)
                if (shaped.length != 1) return null
                val lower = shaped[0].lowercaseChar()
                if (lower !in 'a'..'z' && lower !in SHAPED) return null
                letters += Letter(lower, shaped[0].isUpperCase())
            }
            if (tone >= 0 && letters.none { it.char in VOWELS }) return null
            return Syllable(letters, tone)
        }

        /** 元音段 [start, end)；没有元音时 null。qu、gi 后面还有元音时 u、i 算声母。 */
        fun nucleus(letters: List<Letter>): Pair<Int, Int>? {
            var start = letters.indexOfFirst { it.char in VOWELS }
            if (start < 0) return null
            if (start == 1 && letters.size > 2 && letters[2].char in VOWELS &&
                (letters[0].char == 'q' && letters[1].char == 'u' || letters[0].char == 'g' && letters[1].char == 'i')) start = 2
            var end = start
            while (end < letters.size && letters[end].char in VOWELS) end++
            return start to end
        }

        private fun parts(letters: List<Letter>): Triple<String, String, String>? {
            val (start, end) = nucleus(letters) ?: return null
            val chars = letters.map { it.char }.joinToString("")
            return Triple(chars.substring(0, start), chars.substring(start, end), chars.substring(end))
        }

        /** 元音组合（带变音）本身拼得通：选 w 变哪个元音时用（oa + w 是 oă，不是 ơa）。 */
        private fun validNucleus(letters: List<Letter>): Boolean {
            val (_, vowels, final) = parts(letters) ?: return false
            return if (final.isEmpty()) vowels in OPEN || vowels in CLOSED else vowels in CLOSED
        }

        /**
         * 像不像一个（打到一半的）越南语音节：声母、元音组合（不看变音）、尾辅音都对得上，
         * c ch p t 结尾的不能是玄、问、跌声。还没有元音时只看声母。
         */
        fun plausible(letters: List<Letter>, tone: Int): Boolean {
            val chars = letters.map { it.char }.joinToString("")
            val split = parts(letters) ?: return unshape(chars).let { initial ->
                INITIALS.any { unshape(it).startsWith(initial) }
            }
            val (initial, vowels, final) = split
            if (initial !in INITIALS || final !in FINALS && FINALS.none { it.startsWith(final) }) return false
            val skeleton = unshape(vowels)
            val vowelsFit = if (final.isEmpty()) skeleton in OPEN_SKELETONS || skeleton in CLOSED_SKELETONS
                else skeleton in CLOSED_SKELETONS
            if (!vowelsFit) return false
            return !(final in STOP_FINALS && tone in 1..3)
        }

        private fun hasMarks(word: String): Boolean = parse(word)?.let { syllable ->
            syllable.tone >= 0 || syllable.letters.any { it.char in SHAPED }
        } ?: false

        fun plausible(word: String): Boolean = parse(word)?.let { plausible(it.letters, it.tone) } ?: false

        /** 声调放在哪个字母上（老写法，见类说明）。 */
        fun tonePosition(letters: List<Letter>): Int {
            val (start, end) = nucleus(letters) ?: return -1
            val shaped = (start until end).lastOrNull { letters[it].char in "âăêôơư" }
            return when {
                shaped != null -> shaped
                end - start == 1 -> start
                end < letters.size -> end - 1
                end - start == 3 -> start + 1
                else -> start
            }
        }

        fun render(letters: List<Letter>, tone: Int): String {
            val at = if (tone >= 0) tonePosition(letters) else -1
            return buildString {
                letters.forEachIndexed { index, letter ->
                    val char = if (letter.upper) letter.char.uppercaseChar() else letter.char
                    if (index == at) append(Normalizer.normalize("$char${TONE_MARKS[tone]}", Normalizer.Form.NFC))
                    else append(char)
                }
            }
        }

        /**
         * 把一个词的声调挪到 [tonePosition] 的位置（新写法 hoà、thuỷ 变成 hòa、thủy）；不是越南语音节原样返回。
         * 词表统一成键盘打出来的写法，补全时不出两个一样的词。
         */
        fun normalizeTone(word: String): String {
            val syllable = parse(word) ?: return word
            if (syllable.tone < 0) return word
            return render(syllable.letters, syllable.tone)
        }
    }
}
