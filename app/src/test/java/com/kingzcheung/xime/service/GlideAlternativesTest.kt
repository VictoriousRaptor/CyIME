package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #294：中文滑行除首选外再给几个编码的备选，插在首选之后；点它换掉这一笔的编码。 */
class GlideAlternativesTest {
    private val engine = listOf(RimeCandidate("当", ""), RimeCandidate("党", ""), RimeCandidate("但", ""))

    private fun holder() = GlideAlternatives().apply {
        entry = GlideAlternatives.Entry("dh", 2, "double_pinyin_flypy", 7L,
            listOf(GlideAlternatives.Choice("但", "dj", 2), GlideAlternatives.Choice("等", "dg", 2)))
    }

    @Test fun alternativesFollowTheFirstCandidateAndSkipOnesAlreadyShown() {
        val result = holder().inject(null, "dh", engine, true, "double_pinyin_flypy", 7L)!!
        // 「但」已经在引擎候选里，不重复；「等」插在首选之后。
        assertEquals(listOf("当", "等", "党", "但"), result.candidates.map { it.text })
        assertEquals(listOf(0, -1, 1, 2), result.actions.map { it.engineIndex })
        assertEquals("dg", result.actions[1].glideInput)
    }

    @Test fun partialInputsOfTheSameStrokeKeepTheChoicesUntilTheStrokeIsTyped() {
        val holder = holder()
        // 这一笔还在逐键打（d），不作废。
        assertNull(holder.inject(null, "d", engine, true, "double_pinyin_flypy", 7L))
        assertEquals("dh", holder.entry?.input)
        holder.inject(null, "dh", engine, true, "double_pinyin_flypy", 7L)
        // 打完后接着打字，输入变了就不再给。
        assertNull(holder.inject(null, "dh'n", engine, true, "double_pinyin_flypy", 7L))
        assertNull(holder.entry)
    }

    @Test fun otherPagesSchemasAndEditorsGetNoChoices() {
        val base = CandidateTransformResult(engine, engine.indices.map(CandidateAction::engine))
        assertSame(base, holder().inject(base, "dh", engine, false, "double_pinyin_flypy", 7L))
        val switched = holder()
        assertSame(base, switched.inject(base, "dh", engine, true, "rime_ice", 7L))
        assertNull(switched.entry)
        assertSame(base, holder().inject(base, "dh", engine, true, "double_pinyin_flypy", 8L))
    }

    @Test fun choosingSwapsTheFirstCandidateIntoTheChoices() {
        val holder = holder()
        val choice = holder.choose("dj", "当")!!
        assertEquals("dj", choice.input)
        assertEquals(listOf("当" to "dh", "等" to "dg"), holder.entry!!.choices.map { it.text to it.input })
        assertEquals("dj", holder.entry!!.input)
        assertNull(holder.choose("xx", "但"))
    }

    @Test fun theStrokeIsInsertedAtTheCaretWithASeparator() {
        assertEquals("dh" to 2, GlideAlternatives.inserted("", 0, "dh"))
        assertEquals("nihc'dh" to 7, GlideAlternatives.inserted("nihc", 4, "dh"))
        assertEquals("ni'dh" to 5, GlideAlternatives.inserted("ni'", 3, "dh"))
    }
}
