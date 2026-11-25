package com.kingzcheung.xime.settings

import java.io.BufferedReader
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Plain-text word lists as other input methods export them, one word per line:
 * `词 拼音`, `拼音 词` (Sogou/QQ exports, `'ni'hao 你好`), `词<TAB>pin yin<TAB>权重` (Rime), `你好<TAB>ni'hao'<TAB>1`
 * (Baidu), `你好(ni|hao) 20000` (Baidu mobile), `nihao<TAB>你好<TAB>zh-CN` (Gboard's dictionary.txt), Rime user
 * dictionary snapshots (`ni hao<TAB>你好<TAB>c=3 d=0.9 t=12`). Fields are
 * separated by tabs, spaces or commas; pinyin may be written with spaces, apostrophes or run
 * together (nihao). A count column is ignored: every word gets the same weight as SCEL imports.
 * Lines without pinyin are skipped and counted. UTF-8, UTF-16 (with BOM) and GB18030 are read.
 * A line without Chinese characters is an English word (`Ame`, `Team Spirit`, `Ame<TAB>ame<TAB>100`): its text
 * before the first tab, kept as written, goes to the English candidates of every keyboard.
 * Tone marks are read too (万象 writes `阿爸<TAB>ā bà<TAB>716`). The file is read line by line, never whole: 万象's base
 * list is 49 MB with 1.49 million words (11-03: the old 16 MB in-memory read refused it).
 */
object TxtCellDictionary {
    private val word = Regex("[㐀-䶿一-鿿]{2,32}")
    private val letters = Regex("[a-zv' ]+")

    const val MAX_BYTES = 128 * 1024 * 1024
    const val MAX_ENTRIES = 2_000_000

    /** [stroke]：笔画词库的词（「词 笔画编码」，没有拼音），只给笔画方案用（[StrokeCellDictionary]）。 */
    data class Summary(val count: Int, val skipped: Int, val english: List<String>, val stroke: Int = 0)

    fun parse(bytes: ByteArray): ScelDictionary.Parsed {
        require(bytes.size <= MAX_BYTES) { "词库超过 128 MB，请拆分后导入" }
        val entries = ArrayList<DictEntry>()
        val summary = read({ bytes.inputStream() }) { entries += it }
        // 这条路不存笔画词（只有 installFile 存），只有笔画编码时照旧报没读到词，不装一个空词库。
        require(entries.isNotEmpty() || summary.english.isNotEmpty()) { "没有读到带拼音的词条（每行“词 拼音”或“拼音 词”）" }
        return ScelDictionary.Parsed("", entries, summary.skipped, summary.english)
    }

    /**
     * Streams the file twice (encoding and Rime header check, then the words). [sink] gets each word and pinyin
     * once, in file order. Duplicates are found by a 64-bit key, so 1.5 million words take 16 MB, not a map of all.
     * A line with no pinyin but a stroke code (`猫娘 pz`, h s p n z) goes to [stroke] once instead of being skipped.
     */
    fun read(open: () -> InputStream, stroke: (String) -> Unit = {}, sink: (DictEntry) -> Unit): Summary {
        val (charset, bom) = charset(open)
        // A Rime .dict.yaml: only the entries after its header, the first "..." line after line one.
        var header = -1
        lines(open, charset, bom) { index, line -> if (header < 0 && index > 0 && line == "...") header = index }
        val seen = LongSet()
        val english = LinkedHashSet<String>()
        val strokeSeen = LongSet()
        var skipped = 0
        lines(open, charset, bom) { index, raw ->
            if (index <= header) return@lines
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@lines
            val latin = englishWord(line)
            if (latin != null) { english += latin; return@lines }
            val entry = parseLine(line)
            if (entry == null) {
                val strokeWord = strokeWord(line)
                if (strokeWord == null) skipped++ else if (strokeSeen.add(key(strokeWord, "\u0001"))) stroke(strokeWord)
                return@lines
            }
            if (seen.add(key(entry.word, entry.code))) sink(entry)
            require(seen.size + english.size + strokeSeen.size <= MAX_ENTRIES) { "词库超过 200 万条词语，请拆分后导入" }
        }
        require(seen.size > 0 || english.isNotEmpty() || strokeSeen.size > 0) {
            "没有读到带拼音或笔画编码的词条（每行“词 拼音”“拼音 词”或“词 笔画编码”）"
        }
        return Summary(seen.size, skipped, english.toList(), strokeSeen.size)
    }

    private val latin = Regex("[A-Za-z0-9][A-Za-z0-9 '._&+-]{0,62}")

    /** 搜狗格式的编码一列：每个字母前一个撇号（`'a'b'o'u't`）。 */
    private val spelledCode = Regex("(?:'[A-Za-z0-9]+)+")

    /**
     * 英文词带着搜狗格式的编码（#560，深蓝词库转换的 `'a'b'o'u't about`，或词在前 `about 'a'b'o'u't`）：
     * 编码的字母和词一样时只要词，不把编码当成词的一部分。别的行原样返回。
     */
    internal fun withoutSpelledCode(line: String): String {
        val parts = line.trim().split(Regex("[ \t]+"), limit = 2)
        if (parts.size != 2) return line
        val (code, word) = when {
            spelledCode.matches(parts[0]) -> parts[0] to parts[1]
            else -> parts[1].substringAfterLast(' ').let { tail ->
                if (!spelledCode.matches(tail)) return line
                tail to (parts[0] + " " + parts[1]).removeSuffix(tail).trim()
            }
        }
        return word.takeIf { it.isNotEmpty() && CellDictionaryEnglish.letters(code) == CellDictionaryEnglish.letters(it) } ?: line
    }

    /** The English word of a line without Chinese characters, or null. A bare number or one letter is no word. */
    internal fun englishWord(line: String): String? {
        if (line.any { it.code > 0x7f }) return null
        val text = withoutSpelledCode(line).substringBefore('\t').trim().replace(Regex(" +"), " ")
        if (!latin.matches(text) || text.none(Char::isLetter)) return null
        return text.takeIf { CellDictionaryEnglish.letters(it).length >= 2 }
    }

    /** Columns that are no pinyin: Gboard's language (`zh-CN`), Rime user dictionary stats (`c=3 d=0.9 t=12`). */
    private val notPinyin = Regex("[a-z]{2,3}[-_][A-Za-z]{2,4}|[a-z]=.*")

    internal fun parseLine(line: String): DictEntry? {
        // Baidu mobile writes `你好(ni|hao) 20000`.
        val fields = line.split('\t', ' ', ',', '，', '(', ')', '|').filter { it.isNotEmpty() && !notPinyin.matches(it) }
        val text = fields.firstOrNull { word.matches(it) } ?: return null
        val pinyin = toneless(fields.filter { it != text && it.any(Char::isLetter) }
            .joinToString(" ").lowercase()).replace('ü', 'v').replace("u:", "v")
        if (pinyin.isEmpty() || !letters.matches(pinyin)) return null
        val code = syllables(pinyin, text.length) ?: return null
        return DictEntry(text, code, 100)
    }

    private val strokeCode = Regex("[hspnz']+")

    /**
     * 笔画词库的一行（11-19 贡献者）：词后面（或前面）跟 h s p n z 笔画编码，每字首笔（`猫娘 pz`）、整串笔画或用 ' 隔开都行，
     * 词频数字不读。拼音读不出来时才这样认。返回词，不是笔画行返回 null。编码不留：用的时候按笔画码表现算（[StrokeCellDictionary]）。
     */
    internal fun strokeWord(line: String): String? {
        val fields = line.split('\t', ' ', ',', '，').filter { it.isNotEmpty() }
        val text = fields.firstOrNull { word.matches(it) } ?: return null
        val codes = fields.filter { it != text && it.any(Char::isLetter) }
        if (codes.isEmpty() || !codes.all { strokeCode.matches(it.lowercase()) }) return null
        return text
    }

    /** Space-separated syllables, one per character, or null when the pinyin does not spell them. */
    internal fun syllables(pinyin: String, count: Int): String? {
        // Rime spells 略/虐 lve/nve.
        val given = pinyin.split(' ', '\'').filter { it.isNotEmpty() }
            .map { if (it == "lue" || it == "nue") it.replace('u', 'v') else it }
        if (given.size == count && given.all { it in SYLLABLES }) return given.joinToString(" ")
        // Run together (nihao) or partly separated: split the letters into exactly `count` syllables.
        val run = given.joinToString("")
        val split = Array(count + 1) { arrayOfNulls<List<String>>(run.length + 1) }
        split[0][0] = emptyList()
        for (n in 0 until count) for (start in 0 until run.length) {
            val head = split[n][start] ?: continue
            for (end in start + 1..minOf(run.length, start + 6)) {
                val syllable = run.substring(start, end)
                if (syllable in SYLLABLES && split[n + 1][end] == null) split[n + 1][end] = head + syllable
            }
        }
        return split[count][run.length]?.joinToString(" ")
    }

    private val marks = Regex("\\p{Mn}+")

    /** ā bà → a ba, lǜ/lǚ → lv. Plain pinyin passes through untouched. */
    internal fun toneless(pinyin: String): String {
        if (pinyin.all { it.code < 0x80 }) return pinyin
        return Normalizer.normalize(pinyin, Normalizer.Form.NFD).replace("u\u0308", "v").replace(marks, "")
    }

    /** UTF-16 and UTF-8 by their BOM; otherwise UTF-8 when the whole file decodes as UTF-8, else GB18030. */
    private fun charset(open: () -> InputStream): Pair<Charset, Int> {
        val head = open().use { input -> ByteArray(3).let { it.copyOf(input.readNBytesCompat(it)) } }
        val b = head.map { it.toInt() and 255 }
        return when {
            b.size >= 2 && b[0] == 0xFF && b[1] == 0xFE -> Charsets.UTF_16LE to 2
            b.size >= 2 && b[0] == 0xFE && b[1] == 0xFF -> Charsets.UTF_16BE to 2
            b.size >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF -> Charsets.UTF_8 to 3
            else -> try { lines(open, Charsets.UTF_8, 0) { _, _ -> }; Charsets.UTF_8 to 0 }
                catch (_: CharacterCodingException) { Charset.forName("GB18030") to 0 }
        }
    }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    /** Strict decoding: a byte that does not fit [charset] throws instead of becoming a replacement character. */
    private inline fun lines(open: () -> InputStream, charset: Charset, skip: Int, action: (Int, String) -> Unit) {
        open().use { input ->
            var skipped = 0L
            while (skipped < skip) { val n = input.skip(skip - skipped); if (n <= 0) break; skipped += n }
            val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            BufferedReader(java.io.InputStreamReader(input, decoder), 1 shl 16).use { reader ->
                var index = 0
                while (true) action(index++, reader.readLine() ?: break)
            }
        }
    }

    /** FNV-1a over word, tab and pinyin. */
    private fun key(word: String, code: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (c in word) hash = (hash xor c.code.toLong()) * 0x100000001b3L
        hash = (hash xor '\t'.code.toLong()) * 0x100000001b3L
        for (c in code) hash = (hash xor c.code.toLong()) * 0x100000001b3L
        return hash
    }

    /** Open-addressing set of 64-bit keys (0 is the empty slot, so key 0 is stored as 1). */
    internal class LongSet {
        private var slots = LongArray(1 shl 12)
        var size = 0
            private set

        fun add(value: Long): Boolean {
            val key = if (value == 0L) 1L else value
            if ((size + 1) * 4L > slots.size * 3L) {
                val old = slots
                slots = LongArray(old.size * 2)
                for (slot in old) if (slot != 0L) insert(slots, slot)
            }
            return insert(slots, key).also { if (it) size++ }
        }

        private fun insert(table: LongArray, key: Long): Boolean {
            val mask = table.size - 1
            val mixed = key * -0x61c8864680b583ebL
            var at = (mixed xor (mixed ushr 32)).toInt() and mask
            while (true) {
                val slot = table[at]
                if (slot == 0L) { table[at] = key; return true }
                if (slot == key) return false
                at = (at + 1) and mask
            }
        }
    }

    private val SYLLABLES = ("a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can " +
        "cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui " +
        "chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die ding diu " +
        "dong dou du duan dui dun duo e ei en eng er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei " +
        "gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai " +
        "huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke kei " +
        "ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia lian liang liao lie " +
        "lin ling liu lo long lou lu luan lun luo lv lve ma mai man mang mao me mei men meng mi mian miao mie min ming " +
        "miu mo mou mu na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo " +
        "nv nve o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin " +
        "qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai san sang sao " +
        "se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si " +
        "song sou su suan sui sun suo ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
        "wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang " +
        "yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao " +
        "zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo")
        .split(' ').toHashSet()
}
