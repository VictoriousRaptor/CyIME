package com.kingzcheung.xime.association

import java.io.Reader

/**
 * 整词后面常接什么（2026-11-16）。联想表（[BaseAssociationModel]，聊天语料）只认一到两个字的上文，
 * 四个字的词打完只会按最后一个字联想（「厄尔尼诺」后面出诺曼、诺斯的 曼、斯，不出「现象」）。
 *
 * 这张表在打包时从内置词库生成（build-logic/tasks-word-association.gradle.kts）：词库里的长词以另一个词开头时
 * （厄尔尼诺现象 = 厄尔尼诺 + 现象），记一行「厄尔尼诺 → 现象」，分数 = 长词的词频 ÷（开头那个词自己的词频 +
 * 所有以它开头的长词的词频），和联想表一样是「上文后面接它」的概率。怎么并进联想见 [mergeWordContinuations]。
 *
 * 文件是 gzip 文本：文件头一行，然后每行 `上文\t接续\t词频\t总数`；上文 2–4 个汉字，按 String 顺序升序，
 * 同一个上文的行连在一起、按词频从大到小，最多 [MAX_PER_CONTEXT] 行。
 */
class WordContinuationTable private constructor(
    /** All contexts back to back in sorted order; context r is contextText[contextStart[r], contextStart[r + 1]). */
    private val contextText: CharArray,
    private val contextStart: IntArray,
    /** Candidates of context r are [rowStart[r], rowStart[r + 1]), in score order. */
    private val rowStart: IntArray,
    private val text: CharArray,
    private val textStart: IntArray,
    private val scores: FloatArray,
) {
    // Packed like BaseAssociationModel, contexts too: about 116k contexts and 212k candidates, a few MB as arrays.

    /** [contextLength]: how many trailing characters of the context the matched word covers. */
    class Match(val contextLength: Int, val candidates: List<AssociationCandidate>)

    val contextCount: Int get() = contextStart.size - 1

    /** The longest word (4 down to 2 characters) the context ends with that has a row, and what follows it. */
    fun lookup(context: String, topK: Int = MAX_PER_CONTEXT): Match? {
        if (topK <= 0) return null
        val tail = context.takeLastWhile { it in '一'..'鿿' }.takeLast(MAX_CONTEXT_LENGTH)
        for (size in tail.length downTo MIN_CONTEXT_LENGTH) {
            val row = find(tail, tail.length - size)
            if (row < 0) continue
            val first = rowStart[row]
            return Match(size, List(minOf(topK, rowStart[row + 1] - first)) { index ->
                val candidate = first + index
                AssociationCandidate(
                    String(text, textStart[candidate], textStart[candidate + 1] - textStart[candidate]), scores[candidate])
            })
        }
        return null
    }

    /** Row of the context equal to tail.substring(from), or -1. */
    private fun find(tail: String, from: Int): Int {
        var low = 0
        var high = contextCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(middle, tail, from)
            if (order < 0) low = middle + 1 else if (order > 0) high = middle - 1 else return middle
        }
        return -1
    }

    /** Context [row] against tail.substring(from), in String order (UTF-16 units, then length). */
    private fun compare(row: Int, tail: String, from: Int): Int {
        val start = contextStart[row]
        val length = contextStart[row + 1] - start
        val other = tail.length - from
        for (index in 0 until minOf(length, other)) {
            val difference = contextText[start + index].code - tail[from + index].code
            if (difference != 0) return difference
        }
        return length - other
    }

    companion object {
        const val ASSET = "association/dict-words-v1.gz"
        const val HEADER = "# cyime-dict-word-continuations-v1"
        const val MIN_CONTEXT_LENGTH = 2
        const val MAX_CONTEXT_LENGTH = 4
        const val MAX_PER_CONTEXT = 4

        fun read(reader: Reader): WordContinuationTable {
            val contextText = StringBuilder()
            val text = StringBuilder()
            var contextStart = IntArray(1024)
            var rowStart = IntArray(1024)
            var textStart = IntArray(4096)
            var scores = FloatArray(4096)
            var contexts = 0
            var candidates = 0
            var last = ""
            var inRow = 0
            var lastCount = 0L
            reader.buffered().useLines { lines ->
                val iterator = lines.iterator()
                require(iterator.hasNext() && iterator.next().startsWith(HEADER)) { "整词联想表文件头不对" }
                iterator.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val fields = line.split('\t')
                    require(fields.size == 4) { "整词联想表这一行不是四列：$line" }
                    val context = fields[0]
                    val count = fields[2].toLong()
                    val total = fields[3].toLong()
                    require(context.length in MIN_CONTEXT_LENGTH..MAX_CONTEXT_LENGTH && context.all { it in '一'..'鿿' } &&
                        fields[1].length in 1..6 && count > 0 && total >= count) { "整词联想表这一行不对：$line" }
                    if (context != last) {
                        require(context > last) { "整词联想表没按上文排序：$context" }
                        if (contexts + 1 >= contextStart.size) {
                            contextStart = contextStart.copyOf(contextStart.size * 2)
                            rowStart = rowStart.copyOf(rowStart.size * 2)
                        }
                        contextStart[contexts] = contextText.length
                        rowStart[contexts] = candidates
                        contextText.append(context)
                        contexts++
                        last = context
                        inRow = 0
                    } else {
                        require(count <= lastCount) { "整词联想表同一个上文没按词频排序：$line" }
                    }
                    require(inRow < MAX_PER_CONTEXT) { "整词联想表一个上文超过 $MAX_PER_CONTEXT 行：$context" }
                    if (candidates + 1 >= textStart.size) {
                        textStart = textStart.copyOf(textStart.size * 2)
                        scores = scores.copyOf(scores.size * 2)
                    }
                    textStart[candidates] = text.length
                    text.append(fields[1])
                    scores[candidates] = count.toFloat() / total
                    candidates++
                    inRow++
                    lastCount = count
                }
            }
            contextStart[contexts] = contextText.length
            rowStart[contexts] = candidates
            textStart[candidates] = text.length
            fun chars(builder: StringBuilder) = CharArray(builder.length).also { builder.getChars(0, builder.length, it, 0) }
            return WordContinuationTable(chars(contextText), contextStart.copyOf(contexts + 1), rowStart.copyOf(contexts + 1),
                chars(text), textStart.copyOf(candidates + 1), scores.copyOf(candidates))
        }
    }
}

/**
 * 把整词的接续并进联想的分数表 [scores]（候选 → 分数，已经放好联想表按 [baseContextLength] 个字上文给的候选）。
 * 只有整词比联想表认得的上文长时才并：表里有「晚上」这一行时照旧只用它；表只认得最后一个字「诺」时，
 * 「厄尔尼诺」整词的接续并进来。两边都是「上文后面接它」的概率，乘同一个权重 [weight]，同一个候选取大的，
 * 之后照常按分数排：把握大的整词接续在前（厄尔尼诺 → 现象 0.89），按最后一两个字出的联想还在后面。
 */
internal fun mergeWordContinuations(
    scores: MutableMap<String, Float>, baseContextLength: Int, words: WordContinuationTable.Match?, weight: Float,
) {
    if (words == null || words.contextLength <= baseContextLength) return
    for (candidate in words.candidates) {
        val score = weight * candidate.score
        if (score > (scores[candidate.text] ?: 0f)) scores[candidate.text] = score
    }
}
