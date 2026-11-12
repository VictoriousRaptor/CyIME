package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpandedCandidateLoaderTest {
    @Test fun loadsInBoundedBatchesWithoutDuplicatingGlobalIndices() = runTest {
        val offsets = mutableListOf<Pair<Int, Int>>()
        val publications = mutableListOf<List<RimeCandidate>>()
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { revision, offset, count ->
            assertEquals(42L, revision)
            offsets += offset to count
            List(count) { RimeCandidate((offset + it).toString(), "") }
        }
        loader.load(42, { true }, publish = publications::add)
        assertTrue(publications.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(0,64,128,192,256,320,384,448), offsets.map { it.first })
        assertEquals(52, offsets.last().second)
        assertEquals(64, publications.first().size)
        assertEquals((0 until 500).map(Int::toString), publications.last().map { it.text })
    }
    // 11-11：长编码的单字排在几百个词组后面。「单字」开着时读满 500 个还没单字就接着往后读。
    @Test fun singleCharacterFilterReadsPastTheUsualLimitUntilCharactersAppear() = runTest {
        val offsets = mutableListOf<Int>()
        val publications = mutableListOf<List<RimeCandidate>>()
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { _, offset, count ->
            offsets += offset
            List(count) { i -> RimeCandidate(if (offset + i < 1200) "词组" else "字", "") }
        }
        loader.load(7, { true }, singleCharacters = { true }, publish = publications::add)
        advanceUntilIdle()
        val last = publications.last()
        assertTrue(last.count { it.text == "字" } >= ExpandedCandidateLoader.SINGLE_CHARACTER_TARGET)
        assertTrue(last.size < ExpandedCandidateLoader.SINGLE_CHARACTER_SEARCH_LIMIT)
        assertEquals(last.size, offsets.last() + 64)
        // 500 之后只在读到单字时刷新，不为每批词组都刷新。
        assertTrue(publications.none { it.size in 501 until 1200 })
    }
    @Test fun singleCharacterSearchStopsAtTheLimitAndPublishesWhatItRead() = runTest {
        val publications = mutableListOf<List<RimeCandidate>>()
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { _, _, count ->
            List(count) { RimeCandidate("词组", "") }
        }
        loader.load(7, { true }, singleCharacters = { true }, publish = publications::add)
        advanceUntilIdle()
        assertEquals(ExpandedCandidateLoader.SINGLE_CHARACTER_SEARCH_LIMIT, publications.last().size)
    }
    @Test fun turningOnSingleCharactersContinuesFromWhatWasAlreadyRead() = runTest {
        val offsets = mutableListOf<Int>()
        val publications = mutableListOf<List<RimeCandidate>>()
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { _, offset, count ->
            offsets += offset
            List(count) { RimeCandidate("字", "") }
        }
        val read = List(ExpandedCandidateLoader.MAX_CANDIDATES) { RimeCandidate("词组", "") }
        loader.load(7, { true }, singleCharacters = { true }, initial = read, publish = publications::add)
        advanceUntilIdle()
        assertEquals(ExpandedCandidateLoader.MAX_CANDIDATES, offsets.first())
        assertEquals(read, publications.last().take(read.size))
        // 单字关着：读满 500 就停，和以前一样。
        assertEquals(ExpandedCandidateLoader.MAX_CANDIDATES, ExpandedCandidateLoader.readLimit(read, singleCharacters = false))
    }
    @Test fun staleEngineSnapshotIsNotPublishedAsEmptyCandidates() = runTest {
        var published = false
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { _, _, _ -> null }
        loader.load(1, { true }) { published = true }
        advanceUntilIdle()
        assertFalse(published)
    }
    @Test fun editorChangeDuringReadDropsTheBatch() = runTest {
        var current = true
        var published = false
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { _, _, _ ->
            current = false
            listOf(RimeCandidate("旧输入框", ""))
        }
        loader.load(1, { current }) { published = true }
        advanceUntilIdle()
        assertFalse(published)
    }
    @Test fun replacementCancelsOlderLoadAndEmptyListEndsValidSnapshot() = runTest {
        val publications = mutableListOf<List<RimeCandidate>>()
        val revisions = mutableListOf<Long>()
        val loader = ExpandedCandidateLoader(this, StandardTestDispatcher(testScheduler)) { revision, _, _ ->
            revisions += revision
            emptyList()
        }
        loader.load(1, { true }, publish = publications::add)
        loader.load(2, { true }, publish = publications::add)
        advanceUntilIdle()
        assertEquals(listOf(2L), revisions)
        assertEquals(listOf(emptyList<RimeCandidate>()), publications)
    }
}
