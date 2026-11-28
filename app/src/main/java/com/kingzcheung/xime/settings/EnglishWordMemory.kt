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
    /** What the file holds: a word, or a word with the digits typed right after it (Abc123, 2026-11-25). */
    private val STORED = Regex("[A-Za-z]{2,40}[0-9]{0,12}")
    /** A word with digits right before the caret: letters not glued to more letters or digits in front. */
    private val WORD_DIGITS = Regex("(?<![A-Za-z0-9])([A-Za-z]{2,40})([0-9]{1,12})$")

    /** A word with a capital, or of four letters and more; raw lowercase ab/abc is mostly a jianpin slip. */
    internal fun isLearnable(text: String): Boolean =
        WORD.matches(text) && (text.any { it.isUpperCase() } || text.length >= 4)

    @Synchronized
    fun remember(rimeDir: File, text: String) {
        // 删过（排到后面）的词再回车打出来就回到原位。放在最前：下面「和上一个一样就不重复记」会提前返回，
        // 以前那时不会撤销「排到后面」（2026-11-26 appointment 删了以后回车打几遍也回不来）。
        undemote(rimeDir, text)
        // A word, or a word with digits after it (Abc123 + Enter, 11-25).
        if (!STORED.matches(text) || !isLearnable(text.trimEnd { it in '0'..'9' })) return
        val file = File(rimeDir, FILE_NAME)
        runCatching {
            val lines = if (file.exists()) file.readLines() else emptyList()
            if (lines.lastOrNull() == text) return
            if (lines.size >= MAX_WORDS * 2) write(file, compact(lines + text)) else file.appendText("$text\n")
        }
    }

    /**
     * The word and the digits right before the caret (text before it: "见 Abc123" -> Abc, 123), when the word is one
     * worth remembering ([isLearnable]); null otherwise.
     */
    internal fun wordWithDigits(before: String): Pair<String, String>? {
        val match = WORD_DIGITS.find(before) ?: return null
        val word = match.groupValues[1]
        return if (isLearnable(word)) word to match.groupValues[2] else null
    }

    /**
     * Digits typed right after an English word (Abc, then 1 2 3 on the number row) are kept with it, so typing abc
     * offers Abc123. Each digit extends the newest line (Abc1 -> Abc12 -> Abc123) instead of adding another.
     */
    @Synchronized
    fun rememberWithDigits(rimeDir: File, word: String, digits: String) {
        if (!isLearnable(word) || digits.isEmpty() || digits.length > 12 || digits.any { it !in '0'..'9' }) return
        val token = word + digits
        val file = File(rimeDir, FILE_NAME)
        runCatching {
            val lines = if (file.exists()) file.readLines() else emptyList()
            val last = lines.lastOrNull()?.trim()
            when {
                last == token -> return
                last != null && last.length > word.length && token.startsWith(last) && last.startsWith(word) &&
                    last.drop(word.length).all { it in '0'..'9' } -> write(file, lines.dropLast(1) + token)
                lines.size >= MAX_WORDS * 2 -> write(file, compact(lines + token))
                else -> file.appendText("$token\n")
            }
        }
    }

    /**
     * Any keyboard, any mode (11-25 他：英文直出、九键、14 键里打的 Abc123 也要记住): called after one character that is not a
     * letter went into the document, [before] being the text before the caret with that character last. A digit extends
     * the word right before it (Abc1 -> Abc12, [rememberWithDigits]); a space or a mark ends the word before it, which is
     * remembered when it has digits or a capital after its first letter (iPhone, CyIME, GPT4). Plain words (hello, The)
     * are not: those are dictionary words or a capital at the start of a sentence.
     */
    @Synchronized
    fun rememberTyped(rimeDir: File, before: String) {
        val last = before.lastOrNull() ?: return
        if (last.isLetter()) return
        if (last in '0'..'9') {
            wordWithDigits(before)?.let { (word, digits) -> rememberWithDigits(rimeDir, word, digits) }
            return
        }
        val token = TYPED.find(before.dropLast(1))?.value ?: return
        val word = token.trimEnd { it in '0'..'9' }
        val shaped = token.length > word.length || word.drop(1).any { it.isUpperCase() }
        if (shaped && isLearnable(word) && token.length <= 52) remember(rimeDir, token)
    }

    /** A word, maybe with digits, right at the end: not glued to more letters or digits in front. */
    private val TYPED = Regex("(?<![A-Za-z0-9])[A-Za-z]{2,40}[0-9]{0,12}$")

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
        if (!STORED.matches(text) || !file.exists()) return false
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
        lines.map { it.trim() }.filter { STORED.matches(it) }.asReversed().distinct().take(MAX_WORDS).asReversed()

    private fun write(file: File, words: List<String>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(words.joinToString("") { "$it\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
