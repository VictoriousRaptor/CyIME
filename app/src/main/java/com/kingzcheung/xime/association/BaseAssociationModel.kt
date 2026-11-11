package com.kingzcheung.xime.association

import java.io.Reader

/**
 * Immutable public prior, separate from writable personal learning. Longest suffix wins.
 * Rows come from the same segmented corpus as the T9 word model (tools/ime_lab/ngram/
 * continuation_build.cc): score = P(continuation | trailing text), one probability scale.
 *
 * A row either starts the next word (吃饭 → 了) or finishes a word the context only began
 * (莫 → 名其妙, 我 → 们). The second kind is marked by [WORD_COMPLETIONS_ASSET]
 * (tools/ime_lab/ngram/tag_word_completions.py). It fits a bare character the user committed
 * on its own, not the end of a whole phrase: after 「你在吃饭莫」 the 莫 already ends a word.
 */
class BaseAssociationModel private constructor(
    /** Contexts in sorted order; row r belongs to contexts[r]. */
    private val contexts: Array<String>,
    /** Candidates of row r are [rowStart[r], rowStart[r + 1]), in score order. */
    private val rowStart: IntArray,
    /** Bit i of rowMask[r] marks the row's i-th candidate (score order) as finishing the last word. */
    private val rowMask: IntArray,
    private val rowNextWordShare: FloatArray,
    /** All candidate texts back to back; candidate c is text[textStart[c], textStart[c + 1]). */
    private val text: CharArray,
    private val textStart: IntArray,
    private val scores: FloatArray,
) {
    // Packed arrays instead of one String + object per candidate: the bundled table has about
    // 390k candidates under 40k contexts, which took roughly 30 MB of Java heap as objects.

    private fun completesWord(row: Int, index: Int): Boolean = rowMask[row] and (1 shl index) != 0

    private fun candidate(index: Int): AssociationCandidate =
        AssociationCandidate(String(text, textStart[index], textStart[index + 1] - textStart[index]), scores[index])

    /**
     * [afterWholeWord]: the text was committed as a phrase of two or more characters (a sentence,
     * a word, voice), so its last character ends a word. Rows that would continue that word are
     * skipped; when the table knows only the last character of a longer context and that row is
     * almost all word completions (莫 → 名/名其妙/非/斯科…), nothing from it fits and none is given.
     */
    fun predict(context: String, topK: Int = 10, afterWholeWord: Boolean = false): List<AssociationCandidate> {
        if (topK <= 0) return emptyList()
        val tail = context.takeLastWhile { it in '一'..'鿿' }.takeLast(4)
        for (size in tail.length downTo 1) {
            val row = contexts.binarySearch(tail.takeLast(size))
            if (row < 0) continue
            val first = rowStart[row]
            val count = rowStart[row + 1] - first
            if (!afterWholeWord) return List(minOf(topK, count)) { candidate(first + it) }
            if (size == 1 && tail.length >= 2 && rowNextWordShare[row] < MIN_NEXT_WORD_SHARE) return emptyList()
            val result = ArrayList<AssociationCandidate>(minOf(topK, count))
            for (index in 0 until count) {
                if (result.size == topK) break
                if (!completesWord(row, index)) result.add(candidate(first + index))
            }
            return result
        }
        return emptyList()
    }

    companion object {
        const val ASSET = "association/lccc-base-v1.bin"
        const val WORD_COMPLETIONS_ASSET = "association/lccc-base-v1.completions.gz"
        const val WORD_COMPLETIONS_HEADER = "# cyime-base-word-completions-v1"
        const val MAX_PER_CONTEXT = 16
        const val MAX_CONTEXTS = 60000
        /** Backed-off single-character rows whose next-word part is below this share are not used after a whole word. */
        const val MIN_NEXT_WORD_SHARE = 0.15f

        fun read(reader: Reader): BaseAssociationModel = build(reader, emptyMap())

        /** [wordCompletions] from [readWordCompletions] for the same table file. */
        fun readWithWordCompletions(table: Reader, wordCompletions: Map<String, Int>): BaseAssociationModel =
            build(table, wordCompletions)

        private fun build(reader: Reader, masks: Map<String, Int>): BaseAssociationModel {
            val groups = HashMap<String, MutableList<AssociationCandidate>>()
            reader.buffered().useLines { lines -> lines.filterNot { it.startsWith("#") || it.isBlank() }.forEach { line ->
                val fields = line.split('\t')
                require(fields.size == 4)
                val count = fields[2].toInt()
                val total = fields[3].toInt()
                require(fields[0].length in 1..4 && fields[1].length in 1..6 && count > 0 && total >= count)
                val list = groups.getOrPut(fields[0]) { mutableListOf() }
                require(list.size < MAX_PER_CONTEXT && groups.size <= MAX_CONTEXTS)
                list.add(AssociationCandidate(fields[1], count.toFloat() / total))
            } }
            val contexts = groups.keys.toTypedArray().also { it.sort() }
            val candidates = contexts.sumOf { groups.getValue(it).size }
            val rowStart = IntArray(contexts.size + 1)
            val rowMask = IntArray(contexts.size)
            val rowNextWordShare = FloatArray(contexts.size)
            val textStart = IntArray(candidates + 1)
            val scores = FloatArray(candidates)
            val text = StringBuilder()
            var next = 0
            contexts.forEachIndexed { row, context ->
                val list = groups.getValue(context)
                val fileMask = masks[context] ?: 0
                require(fileMask ushr list.size == 0) { "联想补词标记超出 $context 的行数" }
                // Stable sort, same order as before; marks follow their rows from file order to score order.
                val order = list.indices.sortedByDescending { list[it].score }
                var mask = 0
                order.forEachIndexed { rank, fileIndex -> if (fileMask and (1 shl fileIndex) != 0) mask = mask or (1 shl rank) }
                rowStart[row] = next
                rowMask[row] = mask
                var total = 0.0
                var nextWord = 0.0
                order.forEachIndexed { rank, fileIndex ->
                    val candidate = list[fileIndex]
                    textStart[next] = text.length
                    text.append(candidate.text)
                    scores[next] = candidate.score
                    next++
                    total += candidate.score.toDouble()
                    if (mask and (1 shl rank) == 0) nextWord += candidate.score.toDouble()
                }
                rowNextWordShare[row] = if (total <= 0.0) 1f else (nextWord / total).toFloat()
            }
            rowStart[contexts.size] = next
            textStart[candidates] = text.length
            val chars = CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
            return BaseAssociationModel(contexts, rowStart, rowMask, rowNextWordShare, chars, textStart, scores)
        }

        /** Lines `context\tMASK` after the header; bit i = the i-th row of that context in table file order. */
        fun readWordCompletions(reader: Reader): Map<String, Int> {
            val masks = HashMap<String, Int>()
            reader.buffered().useLines { lines ->
                val iterator = lines.iterator()
                require(iterator.hasNext() && iterator.next().startsWith(WORD_COMPLETIONS_HEADER)) { "联想补词标记文件头不对" }
                iterator.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val fields = line.split('\t')
                    require(fields.size == 2)
                    masks[fields[0]] = fields[1].toInt(16)
                }
            }
            return masks
        }
    }
}
