package com.kingzcheung.xime.handwriting.training

/**
 * 贡献页随机出题（用户 11-12：原来手写和滑行贡献每人都是同样一组字词，五十多人交上来全一样，没用）。
 *
 * 题从 [POOL_ASSET]（3000 常用字、3000 少用字、3000 两字词，tools/handwriting_training/build_contribute_pool.py 生成）里出，
 * 按每个人的随机贡献编号打乱：不同人拿到不同的字词，同一个人每次进来顺序不变，接着上次写。题量九千多道，写不完。
 * 也可以只出某一类，或者写、滑用户自己输入的字词（[Choice]）。纯函数，单测见 ContributePromptDeckTest。
 */
object ContributePromptDeck {
    /** 一轮里每 10 道题的类型：P 缺数据的常用字（排完让给常用字）、C 常用字、R 生僻字、W 两字词。 */
    private const val PATTERN = "PCWCCRWCCW"
    private const val CHAR_HINT = "按平时的速度写"
    private const val WORD_HINT = "像平时手写输入那样写"
    /** 题库：每行 `类型<TAB>文字<TAB>附加`（char 常用字；rare 少用字，附加是读音；word 两字词，附加是拼音）。 */
    const val POOL_ASSET = "handwriting/contribute_pool.tsv"

    /** 贡献编号（16 位十六进制）当种子；不是十六进制时用字符串哈希。[salt] 让同一个人不同页面的顺序也不同。 */
    fun seedOf(writer: String, salt: String = ""): Long =
        (writer.toULongOrNull(16)?.toLong() ?: writer.hashCode().toLong()) xor salt.hashCode().toLong()

    fun <T> shuffled(items: List<T>, seed: Long): List<T> = items.shuffled(java.util.Random(seed))

    data class Pools(
        val priority: List<TrainingPrompt>,
        val common: List<TrainingPrompt>,
        val rare: List<TrainingPrompt>,
        val words: List<TrainingPrompt>,
    )

    private fun prompt(kind: String, text: String, hint: String?) = TrainingPrompt(
        id = "contribute:$kind:$text", type = TrainingPromptType.Chinese, label = text,
        source = "contribute", hint = hint, kind = kind,
    )

    private fun rows(pool: Sequence<String>): Sequence<List<String>> =
        pool.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
            .filter { it.size >= 2 && it[1].isNotBlank() }

    /**
     * [fixed] 是 contribute_prompts.tsv 解析出的题（char 缺数据的常用字、rare 生僻字、word 两字词），
     * [pool] 是 [POOL_ASSET] 的行。固定题先放进各类，题库里重复的不再出。
     */
    fun pools(fixed: List<TrainingPrompt>, pool: Sequence<String>): Pools {
        val priority = fixed.filter { it.kind == "char" }
        val rare = fixed.filter { it.kind == "rare" }.toMutableList()
        val words = fixed.filter { it.kind == "word" }.toMutableList()
        val common = ArrayList<TrainingPrompt>()
        val taken = fixed.mapTo(HashSet()) { it.label }
        rows(pool).forEach { cols ->
            val text = cols[1].trim()
            val extra = cols.getOrNull(2)?.trim().orEmpty()
            when (cols[0]) {
                "char" -> if (isOneHan(text) && taken.add(text)) common += prompt("char", text, CHAR_HINT)
                "rare" -> if (isOneHan(text) && extra.isNotEmpty() && taken.add(text)) rare += prompt("rare", text, extra)
                "word" -> if (text.length in 2..4 && taken.add(text)) words += prompt("word", text, WORD_HINT)
            }
        }
        return Pools(priority, common, rare, words)
    }

    /** 每类按 [seed] 打乱，再按 [PATTERN] 穿插；某类出完就跳过，全部出完为止。同一个题只出一次。 */
    fun handwriting(pools: Pools, seed: Long): List<TrainingPrompt> {
        val queues = mapOf(
            'P' to ArrayDeque(shuffled(pools.priority, seed xor 1)),
            'C' to ArrayDeque(shuffled(pools.common, seed xor 2)),
            'R' to ArrayDeque(shuffled(pools.rare, seed xor 3)),
            'W' to ArrayDeque(shuffled(pools.words, seed xor 4)),
        )
        val out = ArrayList<TrainingPrompt>(queues.values.sumOf { it.size })
        val seen = HashSet<String>()
        while (queues.values.any { it.isNotEmpty() }) {
            for (slot in PATTERN) {
                val queue = queues.getValue(slot).takeIf { it.isNotEmpty() }
                    ?: queues.getValue('C').takeIf { slot == 'P' && it.isNotEmpty() } ?: continue
                val next = queue.removeFirst()
                if (seen.add(next.id)) out += next
            }
        }
        return out
    }

    /**
     * 从 [from] 往后找第一道这个人还没写过的题（到末尾从头找）；都写过就停在 [from]。
     * 以前写过固定题的人换成随机题后，不再重复写同一个字。
     */
    fun firstUnwritten(prompts: List<TrainingPrompt>, from: Int, written: (String) -> Boolean): Int {
        if (prompts.isEmpty()) return 0
        val start = from.coerceIn(0, prompts.size - 1)
        for (step in prompts.indices) {
            val i = (start + step) % prompts.size
            if (!written(prompts[i].id)) return i
        }
        return start
    }

    /**
     * 写什么由用户自己选（用户 11-12：「要让用户能自己选择要输入什么内容」；11-13：字母、假名、日语、一段话、公式、
     * 剪贴板也要能选，跟手写训练一样）。[kinds] 不为 null 的从这个人的随机中文题里挑；字母、假名、日文汉字、
     * 数学符号、公式用内置题（见 [fromBuiltin]、[japanese]），[Paragraph]、[Own] 要用户先填（[typed]）。
     */
    enum class Choice(val title: String, val kinds: Set<String>?, val note: String? = null) {
        Random("随机", null, "字、词、少用字穿插，每人不同"),
        Common("常用字", setOf("char")),
        Rare("少用字", setOf("rare")),
        Words("词语", setOf("word")),
        Latin("英文字母", null, "大小写字母和数字"),
        Kana("假名", null, "平假名、片假名"),
        Japanese("日文汉字", null, "日语常用的漢字"),
        Symbol("数学符号", null),
        Formula("公式", null, "一整条公式写完再点下一题"),
        Paragraph("一段话", null, "粘贴或输入一段话，逐字写"),
        Own("自己输入", null, "想写的字或词，空格隔开");

        val typed: Boolean get() = this == Paragraph || this == Own
    }

    /** 从这个人的随机题里只留选中的类型；[Choice.Random] 原样返回，其余不从这里出题。 */
    fun filter(deck: List<TrainingPrompt>, choice: Choice): List<TrainingPrompt> = when {
        choice == Choice.Random -> deck
        choice.kinds == null -> emptyList()
        else -> deck.filter { it.kind in choice.kinds }
    }

    /** 内置题（字母、假名、数学符号、随机公式）换成贡献题：id 和 kind 用 [kind]，按 [seed] 打乱。 */
    fun fromBuiltin(prompts: List<TrainingPrompt>, kind: String, seed: Long): List<TrainingPrompt> =
        shuffled(prompts.map { it.copy(id = "contribute:$kind:${it.label}", source = "contribute", kind = kind, hint = null) }
            .distinctBy { it.id }, seed)

    /** 日文汉字题：japanese_kanji.json 的常用度表（字 → 名次），按 [seed] 打乱。 */
    fun japanese(rank: Map<String, Int>, seed: Long): List<TrainingPrompt> =
        shuffled(rank.entries.filter { isOneHan(it.key) }.sortedBy { it.value }.map {
            TrainingPrompt(id = "contribute:ja:${it.key}", type = TrainingPromptType.Japanese, label = it.key,
                source = "contribute", hint = JAPANESE_HINT, kind = "ja")
        }, seed)

    private const val JAPANESE_HINT = "按日语字形写"

    /**
     * 一段话逐字写：去掉空白、按出现顺序去重。汉字算 char（和常用字题同一个 id），假名算 kana，字母数字算 latin，
     * 其余（标点、符号）算 text。
     */
    fun paragraph(text: String): List<TrainingPrompt> {
        val seen = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !Character.isISOControl(cp)) {
                seen += String(Character.toChars(cp))
            }
        }
        return seen.map { ch ->
            val cp = ch.codePointAt(0)
            val (kind, type) = when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> "char" to TrainingPromptType.Chinese
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "kana" to TrainingPromptType.Kana
                else -> if (cp < 0x80 && Character.isLetterOrDigit(cp)) "latin" to TrainingPromptType.Latin
                    else "text" to TrainingPromptType.Custom
            }
            TrainingPrompt(id = "contribute:$kind:$ch", type = type, label = ch, source = "contribute", kind = kind)
        }
    }

    /** 自己输入的一段：按空白和常见标点分开，去重；接收端一题最多 [MAX_OWN_CHARS] 个字。 */
    const val MAX_OWN_CHARS = 8
    private val OWN_SEPARATORS = Regex("[\\s,，、;；。.!！?？/|]+")

    data class Own<T>(val prompts: List<T>, val tooLong: Int = 0, val unknown: Int = 0)

    private fun ownTokens(text: String): List<String> =
        text.split(OWN_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * 手写自己输入的题：一个汉字算 char（和常用字题同一个 id，写过的计在一起），其余算 word。
     * 超过 [MAX_OWN_CHARS] 个字的不要（计入 tooLong）。
     */
    fun ownHandwriting(text: String): Own<TrainingPrompt> {
        var tooLong = 0
        val prompts = ownTokens(text).mapNotNull { token ->
            if (token.codePointCount(0, token.length) > MAX_OWN_CHARS) { tooLong++; return@mapNotNull null }
            val kind = if (isOneHan(token)) "char" else "word"
            val han = token.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
            TrainingPrompt(id = "contribute:$kind:$token", type = if (han) TrainingPromptType.Chinese else TrainingPromptType.Custom,
                label = token, source = "contribute", hint = OWN_HINT, kind = kind)
        }
        return Own(prompts, tooLong = tooLong)
    }

    private const val OWN_HINT = "自己选的"

    /**
     * 滑行自己输入的题。[english] 时只收 2～16 个字母的英文词（转小写）；中文、九键时字母直接当拼音，
     * 汉字词用 [pinyinOf] 查拼音（查不到计入 unknown，请用户直接输拼音）。
     */
    fun ownGlide(text: String, english: Boolean, pinyinOf: (String) -> String?): Own<GlidePrompt> {
        var tooLong = 0
        var unknown = 0
        val prompts = ownTokens(text).mapNotNull { raw ->
            val token = raw.lowercase()
            when {
                LETTERS.matches(token) -> GlidePrompt(token)
                token.all { it in 'a'..'z' } -> { if (token.length > 16) tooLong++ else unknown++; null }
                english -> { unknown++; null }
                else -> pinyinOf(raw)?.takeIf { LETTERS.matches(it) }?.let { GlidePrompt(it, raw) }
                    ?: run { if (raw.length > MAX_OWN_CHARS) tooLong++ else unknown++; null }
            }
        }.distinctBy { it.letters }
        return Own(prompts, tooLong, unknown)
    }

    private val LETTERS = Regex("[a-z]{2,16}")

    /**
     * 汉字词查拼音：先查 [POOL_ASSET] 的两字词，查不到就逐字拼 hanzi_pinyin.tsv 的第一个读音（去掉声调，ü 写成 v）。
     * 多音字可能拼错，题目上会显示出来，用户可以改输拼音。
     */
    fun pinyinLookup(pool: Sequence<String>, hanziPinyin: Sequence<String>): (String) -> String? {
        val words = HashMap<String, String>()
        rows(pool).filter { it[0] == "word" }.forEach { cols ->
            cols.getOrNull(2)?.replace(" ", "")?.takeIf { it.isNotEmpty() }?.let { words.putIfAbsent(cols[1].trim(), it) }
        }
        val chars = HashMap<String, String>()
        hanziPinyin.forEach { line ->
            val cols = line.split('\t')
            if (cols.size >= 2 && cols[1].isNotBlank()) chars.putIfAbsent(cols[0], toneless(cols[1].substringBefore('/')))
        }
        return { word -> words[word] ?: spell(word, chars) }
    }

    private fun spell(word: String, chars: Map<String, String>): String? {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            out.append(chars[String(Character.toChars(cp))] ?: return null)
            i += Character.charCount(cp)
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    internal fun toneless(reading: String): String =
        java.text.Normalizer.normalize(reading.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace("u\u0308", "v").filter { it in 'a'..'z' }

    private fun isOneHan(text: String): Boolean =
        text.codePointCount(0, text.length) == 1 &&
            Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN

    /** 滑行贡献的一道题：[letters] 是要滑的字母，[label] 是显示用的词（中文题），英文题为空。 */
    data class GlidePrompt(val letters: String, val label: String = "")

    /** english.txt 是网页词频，前几千里有这些不适合当题的词。 */
    private val ENGLISH_BLOCK = setOf("sex", "sexy", "porn", "xxx", "teen", "teens", "gay", "lesbian", "naked", "nude",
        "fetish", "cialis", "viagra", "casino", "poker", "gambling", "escort", "fuck", "shit", "damn", "ass", "bitch")
    private val ENGLISH_WORD = Regex("[a-z]{3,10}")
    private val HAS_VOWEL = Regex("[aeiouy]")

    /** 英文题：english.txt 前 [limit] 个常用词里 3～10 个字母的（没有元音的缩写如 www、html 不要），按 [seed] 打乱。 */
    fun englishGlide(lines: Sequence<String>, seed: Long, limit: Int = 5000): List<GlidePrompt> =
        shuffled(lines.take(limit).map { it.trim() }
            .filter { ENGLISH_WORD.matches(it) && HAS_VOWEL.containsMatchIn(it) && it !in ENGLISH_BLOCK }
            .distinct().map { GlidePrompt(it) }.toList(), seed)

    private val PINYIN_LETTERS = Regex("[a-z]{2,16}")

    /** 中文、九键题：[POOL_ASSET] 里的两字词，滑全拼（拼音去掉空格），按 [seed] 打乱。 */
    fun pinyinGlide(pool: Sequence<String>, seed: Long): List<GlidePrompt> =
        shuffled(rows(pool).filter { it[0] == "word" }.mapNotNull { cols ->
            val letters = cols.getOrNull(2)?.replace(" ", "")?.takeIf { PINYIN_LETTERS.matches(it) }
            letters?.let { GlidePrompt(it, cols[1].trim()) }
        }.distinctBy { it.letters }.toList(), seed)
}
