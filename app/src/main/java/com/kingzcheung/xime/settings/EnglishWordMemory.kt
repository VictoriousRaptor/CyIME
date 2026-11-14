package com.kingzcheung.xime.settings

import java.io.File

/**
 * English words the user typed by hand in Chinese 26-key input (letters committed with Enter, e.g. Obsidian),
 * kept as typed so lua/cyime_en_memory.lua offers them again even when no dictionary has them.
 * One word per line in the Rime user directory, newest last; commits append, duplicates are compacted away.
 */
object EnglishWordMemory {
    const val FILE_NAME = "cyime_en_memory.txt"
    /**
     * Dictionary English words the user long-press deleted in Chinese 26 keys (2026-11-13: 删不掉的英文首选). They
     * have no learning record to forget, so lua/cyime_en_memory.lua puts them behind the first four candidates
     * instead; picking one again, or typing it with Enter, takes it off this list. Lowercase, one per line.
     */
    const val DEMOTED_FILE_NAME = "cyime_en_demoted.txt"
    private const val MAX_WORDS = 2000
    private val WORD = Regex("[A-Za-z]{2,40}")

    /** A word with a capital, or of four letters and more; raw lowercase ab/abc is mostly a jianpin slip. */
    internal fun isLearnable(text: String): Boolean =
        WORD.matches(text) && (text.any { it.isUpperCase() } || text.length >= 4)

    @Synchronized
    fun remember(rimeDir: File, text: String) {
        if (!isLearnable(text)) return
        val file = File(rimeDir, FILE_NAME)
        runCatching {
            val lines = if (file.exists()) file.readLines() else emptyList()
            if (lines.lastOrNull() == text) return
            if (lines.size >= MAX_WORDS * 2) write(file, compact(lines + text)) else file.appendText("$text\n")
        }
        undemote(rimeDir, text)
    }

    /** Long-press delete of a dictionary English word: it stays typable, behind the first four candidates. */
    @Synchronized
    fun demote(rimeDir: File, text: String): Boolean {
        if (!WORD.matches(text)) return false
        val file = File(rimeDir, DEMOTED_FILE_NAME)
        val word = text.lowercase()
        return runCatching {
            val lines = if (file.exists()) file.readLines().map { it.trim() } else emptyList()
            if (word !in lines) {
                if (lines.size >= MAX_WORDS) write(file, (lines + word).takeLast(MAX_WORDS)) else file.appendText("$word\n")
            }
            true
        }.getOrDefault(false)
    }

    /** Typing the word again with Enter brings it back to its dictionary place. */
    @Synchronized
    fun undemote(rimeDir: File, text: String) {
        val file = File(rimeDir, DEMOTED_FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val lines = file.readLines().map { it.trim() }
            val kept = lines.filter { it.isNotEmpty() && !it.equals(text, ignoreCase = true) }
            if (kept.size != lines.size) write(file, kept)
        }
    }

    /** Long-press delete forgets the word in every spelling; false when it was never remembered. */
    @Synchronized
    fun forget(rimeDir: File, text: String): Boolean {
        val file = File(rimeDir, FILE_NAME)
        if (!WORD.matches(text) || !file.exists()) return false
        return runCatching {
            val lines = file.readLines()
            val kept = lines.filterNot { it.trim().equals(text, ignoreCase = true) }
            if (kept.size == lines.size) return false
            write(file, compact(kept))
            true
        }.getOrDefault(false)
    }

    /** Latest spelling of each word last, at most [MAX_WORDS]. */
    internal fun compact(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { WORD.matches(it) }.asReversed().distinct().take(MAX_WORDS).asReversed()

    private fun write(file: File, words: List<String>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(words.joinToString("") { "$it\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
