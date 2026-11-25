package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishWordMemoryTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun words(dir: File) = File(dir, EnglishWordMemory.FILE_NAME).readLines()

    @Test
    fun remembersWordsAsTyped() {
        val dir = tempDir.root
        EnglishWordMemory.remember(dir, "Obsidian")
        EnglishWordMemory.remember(dir, "Obsidian")
        EnglishWordMemory.remember(dir, "transformer")
        assertEquals(listOf("Obsidian", "transformer"), words(dir))
    }

    @Test
    fun skipsShortLowercaseAndNonLetters() {
        val dir = tempDir.root
        listOf("abc", "nh", "a", "hello world", "C++", "123", "zhong'guo").forEach { EnglishWordMemory.remember(dir, it) }
        EnglishWordMemory.remember(dir, "KFC")
        assertEquals(listOf("KFC"), words(dir))
    }

    @Test
    fun forgetRemovesEverySpelling() {
        val dir = tempDir.root
        listOf("Obsidian", "Notion", "OBSIDIAN").forEach { EnglishWordMemory.remember(dir, it) }
        assertTrue(EnglishWordMemory.forget(dir, "Obsidian"))
        assertEquals(listOf("Notion"), words(dir))
        assertFalse(EnglishWordMemory.forget(dir, "Obsidian"))
        assertFalse(EnglishWordMemory.forget(dir, "你好"))
    }

    @Test
    fun digitsTypedAfterAWordAreKeptWithIt() {
        val dir = tempDir.root
        EnglishWordMemory.remember(dir, "Abc")
        // Abc, then 1 2 3 on the number row: one line grows instead of three.
        listOf("见 Abc1", "见 Abc12", "见 Abc123").forEach { before ->
            val (word, digits) = EnglishWordMemory.wordWithDigits(before)!!
            EnglishWordMemory.rememberWithDigits(dir, word, digits)
        }
        assertEquals(listOf("Abc", "Abc123"), words(dir))
        // Another word with digits later is its own line; the same token again adds nothing.
        EnglishWordMemory.rememberWithDigits(dir, "Model", "3")
        EnglishWordMemory.rememberWithDigits(dir, "Model", "3")
        assertEquals(listOf("Abc", "Abc123", "Model3"), words(dir))
        assertEquals(listOf("Abc", "Abc123", "Model3"), EnglishWordMemory.compact(words(dir)))
        assertTrue(EnglishWordMemory.forget(dir, "abc123"))
        assertEquals(listOf("Abc", "Model3"), words(dir))
    }

    @Test
    fun wordsTypedStraightIntoTheDocumentAreKept() {
        val dir = tempDir.root
        // English letters typed straight in (no Rime), then digits and a space: Abc123 is one line.
        listOf("hi Abc1", "hi Abc12", "hi Abc123", "hi Abc123 ").forEach { EnglishWordMemory.rememberTyped(dir, it) }
        assertEquals(listOf("Abc123"), words(dir))
        // A capital inside, or digits: kept at a space or mark. A plain word or a sentence-initial capital: not.
        listOf("I like iPhone,", "see GPT4.", "The ", "hello ", "我 CyIME ", "ab1 ").forEach { EnglishWordMemory.rememberTyped(dir, it) }
        assertEquals(listOf("Abc123", "iPhone", "GPT4", "CyIME"), words(dir))
        // Enter after Abc123 typed in Rime keeps the digits too.
        EnglishWordMemory.remember(dir, "Zed42")
        assertEquals("Zed42", words(dir).last())
    }

    @Test
    fun wordWithDigitsNeedsAWordWorthRemembering() {
        assertEquals("Abc" to "123", EnglishWordMemory.wordWithDigits("Abc123"))
        assertEquals("test" to "1", EnglishWordMemory.wordWithDigits("a test1"))
        // Short lowercase (a jianpin slip), digits only, letters glued to digits in front, Chinese: nothing.
        listOf("abc1", "123", "x2y34", "你好1", "Abc", "Abc 1").forEach {
            assertEquals(it, null, EnglishWordMemory.wordWithDigits(it))
        }
    }

    @Test
    fun compactKeepsLatestOrder() {
        assertEquals(listOf("Notion", "Obsidian"), EnglishWordMemory.compact(listOf("Obsidian", "Notion", "Obsidian", "x y")))
    }

    @Test
    fun demoteKeepsOneLowercaseLineAndPickingBringsItBack() {
        val dir = tempDir.root
        val file = File(dir, EnglishWordMemory.DEMOTED_FILE_NAME)
        assertTrue(EnglishWordMemory.demote(dir, "Nude"))
        assertTrue(EnglishWordMemory.demote(dir, "nude"))
        assertTrue(EnglishWordMemory.demote(dir, "same"))
        assertEquals(listOf("nude", "same"), file.readLines())
        assertFalse(EnglishWordMemory.demote(dir, "你好"))
        assertFalse(EnglishWordMemory.demote(dir, "B站"))
        // Typed again with Enter: remembered, and back in its dictionary place.
        EnglishWordMemory.remember(dir, "Nude")
        assertEquals(listOf("same"), file.readLines())
        EnglishWordMemory.undemote(dir, "SAME")
        assertEquals(emptyList<String>(), file.readLines())
    }

    @Test
    fun backupIncludesTheWordFile() {
        assertTrue(BackupManager.isRimePersonalFile(EnglishWordMemory.FILE_NAME))
        assertTrue(BackupManager.isRimePersonalFile(EnglishWordMemory.DEMOTED_FILE_NAME))
    }
}
