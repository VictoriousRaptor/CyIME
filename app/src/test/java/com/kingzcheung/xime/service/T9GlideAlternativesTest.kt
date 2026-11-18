package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #214：九键滑行首选之后给同一笔的其他词；只在这一笔就是整个组合时给，组合一变就不给。 */
class T9GlideAlternativesTest {
    private val schema = "t9"
    private val engine = listOf("高中", "高", "搞")

    private fun holder() = T9GlideAlternatives().apply { offer("42694664", listOf("高兴", "高校"), schema, 7L) }

    @Test fun theWholeStrokeGetsItsAlternativesAfterTheFirstCandidate() {
        val holder = holder()
        val injections = holder.injections("42694664", engine, true, schema, 7L)
        assertEquals(listOf("高兴", "高校"), injections.map { it.snapshot.text })
        assertTrue(injections.all { it.glideInput == "42694664" && it.contactName == null })
        assertTrue(holder.matches("42694664", schema, 7L))
    }

    @Test fun separatorsInTheInputDoNotMatter() {
        assertEquals(2, holder().injections("426'94664", engine, true, schema, 7L).size)
    }

    @Test fun wordsAlreadyAmongTheCandidatesAreNotRepeated() {
        val injections = holder().injections("42694664", listOf("高中", "高兴"), true, schema, 7L)
        assertEquals(listOf("高校"), injections.map { it.snapshot.text })
    }

    @Test fun digitsStillBeingTypedKeepTheEntryAndLaterChangesDropIt() {
        val holder = holder()
        assertTrue(holder.injections("4269", engine, true, schema, 7L).isEmpty())
        assertFalse(holder.matches("42694664", schema, 7L))
        assertEquals(2, holder.injections("42694664", engine, true, schema, 7L).size)
        // 打完这一笔后接着打字：不再给，点旧候选也不上屏。
        assertTrue(holder.injections("426946642", engine, true, schema, 7L).isEmpty())
        assertNull(holder.entry)
        assertFalse(holder.matches("42694664", schema, 7L))
    }

    @Test fun aStrokeAfterOtherTypedDigitsGetsNoAlternatives() {
        // 先点了 6 再划（模型：6 + 滑 69464）：输入不是这一笔，作废。
        val holder = T9GlideAlternatives().apply { offer("69464", listOf("模型"), schema, 7L) }
        assertTrue(holder.injections("669464", engine, true, schema, 7L).isEmpty())
        assertNull(holder.entry)
    }

    @Test fun otherPagesSchemasEditorsAndSelectedPinyinGetNone() {
        assertTrue(holder().injections("42694664", engine, false, schema, 7L).isEmpty())
        val switched = holder()
        assertTrue(switched.injections("42694664", engine, true, "t9_double", 7L).isEmpty())
        assertNull(switched.entry)
        val editor = holder()
        assertTrue(editor.injections("42694664", engine, true, schema, 8L).isEmpty())
        assertNull(editor.entry)
        // 左栏选了拼音后输入带字母，不再是这一笔。
        val pinyin = holder()
        pinyin.injections("42694664", engine, true, schema, 7L)
        assertTrue(pinyin.injections("gao94664", engine, true, schema, 7L).isEmpty())
        assertNull(pinyin.entry)
    }

    @Test fun noAlternativesClearsThePreviousStroke() {
        val holder = holder()
        holder.offer("64426", emptyList(), schema, 7L)
        assertNull(holder.entry)
        holder.offer("64426", listOf("", "你好", "你好", "拟好", "泥好"), schema, 7L)
        assertEquals(listOf("你好", "拟好"), holder.entry?.words)
    }
}
