package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main owns requests/publication. Native generation runs in bounded background batches. */
internal class ExpandedCandidateLoader(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val readBatch: (revision: Long, offset: Int, count: Int) -> List<RimeCandidate>?,
) {
    private var request = 0L
    private var job: Job? = null

    fun cancel() { request++; job?.cancel(); job = null }

    /**
     * [singleCharacters]：「单字」开着。长编码（z'z'yong'fa）的单字排在几百上千个词组后面，
     * 读满 [MAX_CANDIDATES] 还不够 [SINGLE_CHARACTER_TARGET] 个单字时接着往后读，最多读到
     * [SINGLE_CHARACTER_SEARCH_LIMIT]。[initial] 是已经读到的候选（打开「单字」时从这里接着读）。
     */
    fun load(
        revision: Long,
        isCurrent: () -> Boolean,
        singleCharacters: () -> Boolean = { false },
        initial: List<RimeCandidate> = emptyList(),
        publish: (List<RimeCandidate>) -> Unit,
    ) {
        cancel()
        if (revision == 0L) return
        val id = request
        job = scope.launch {
            val candidates = ArrayList(initial)
            var published = candidates.size
            while (id == request && isCurrent()) {
                val limit = readLimit(candidates, singleCharacters())
                if (candidates.size >= limit) break
                val count = minOf(BATCH_SIZE, limit - candidates.size)
                val batch = withContext(worker) { readBatch(revision, candidates.size, count) } ?: return@launch
                if (id != request || !isCurrent()) return@launch
                candidates.addAll(batch)
                val ended = batch.size < count
                // 往后找单字时只在读到单字或读完时刷新，不为几十批词组反复重排展开页。
                if (candidates.size <= MAX_CANDIDATES || ended || batch.any { it.text.isSingleCharacter() }) {
                    publish(candidates.toList())
                    published = candidates.size
                }
                if (ended) return@launch
            }
            if (published != candidates.size && id == request && isCurrent()) publish(candidates.toList())
        }
    }

    companion object {
        const val BATCH_SIZE = 64
        const val MAX_CANDIDATES = 500
        const val SINGLE_CHARACTER_TARGET = 30
        const val SINGLE_CHARACTER_SEARCH_LIMIT = 6000

        /** 平时读到 [MAX_CANDIDATES]；「单字」开着且单字不够时放宽到 [SINGLE_CHARACTER_SEARCH_LIMIT]。 */
        fun readLimit(candidates: List<RimeCandidate>, singleCharacters: Boolean): Int =
            if (singleCharacters && candidates.size >= MAX_CANDIDATES &&
                candidates.count { it.text.isSingleCharacter() } < SINGLE_CHARACTER_TARGET
            ) SINGLE_CHARACTER_SEARCH_LIMIT else MAX_CANDIDATES
    }
}
