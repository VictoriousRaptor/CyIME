package com.kingzcheung.xime.settings

import android.content.Context
import java.io.File
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads the installed Chinese dictionary and its imports, without starting prediction or Rime. */
object ClipboardWordSegmenter {
    suspend fun split(context: Context, text: String): List<String> = withContext(Dispatchers.IO) {
        val wanted = HashSet<String>()
        Regex("[\\p{script=Han}]+").findAll(text).forEach { match ->
            val value = match.value
            for (i in value.indices) for (end in i + 1..minOf(value.length, i + 16)) wanted.add(value.substring(i, end))
        }
        val dir = SchemaManager.getRimeDir(context).canonicalFile
        val root = SchemaManager.getReferencedDictName(context, "rime_ice") ?: "rime_ice"
        val pending = ArrayDeque(listOf(root))
        val seen = HashSet<String>()
        val words = HashMap<String, Double>()
        var total = 0.0
        while (pending.isNotEmpty()) {
            ensureActive()
            val name = pending.removeFirst()
            if (!seen.add(name)) continue
            val file = File(dir, "$name.dict.yaml").canonicalFile
            require(file.path.startsWith(dir.path + File.separator)) { "词典路径不在方案目录内" }
            check(file.isFile) { "缺少已安装词典：$name" }
            val header = StringBuilder()
            var data = false
            file.bufferedReader().useLines { lines -> lines.forEach { raw ->
                ensureActive()
                val line = raw.trim()
                if (!data) {
                    header.appendLine(line)
                    if (line == "...") data = true
                } else if (line.isNotEmpty() && !line.startsWith('#')) {
                    val columns = line.split('\t')
                    val word = columns[0].substringBefore(' ')
                    val frequency = columns.getOrNull(2)?.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 100.0
                    total += frequency
                    if (word in wanted) words[word] = maxOf(words[word] ?: 0.0, frequency)
                }
            } }
            pending.addAll(imports(header.toString()))
        }
        segment(text, words, total.coerceAtLeast(1.0))
    }

    internal fun imports(header: String): List<String> = DictionaryHelper.parseImportTables(
        header.lineSequence().map { it.substringBefore('#').trimEnd() }
            .filter { it.isNotBlank() }.joinToString("\n"))

    internal fun segment(text: String, words: Map<String, Double>, total: Double): List<String> {
        val result = mutableListOf<String>()
        // Preserve spaces, punctuation and complete Unicode code points exactly.
        Regex("[\\p{script=Han}]+|[\\p{L}\\p{N}]+|\\s+|[^\\p{L}\\p{N}\\s]").findAll(text).forEach { match ->
            val run = match.value
            if (!Character.UnicodeScript.of(run.codePointAt(0)).equals(Character.UnicodeScript.HAN)) {
                result.add(run)
            } else {
                val cost = DoubleArray(run.length + 1) { Double.POSITIVE_INFINITY }
                val next = IntArray(run.length)
                cost[run.length] = 0.0
                for (i in run.length - 1 downTo 0) {
                    if (Character.isLowSurrogate(run[i])) continue
                    val singleEnd = i + Character.charCount(run.codePointAt(i))
                    cost[i] = ln(total) + 5 + cost[singleEnd]
                    next[i] = singleEnd
                    for (end in singleEnd..minOf(run.length, i + 16)) {
                        val frequency = words[run.substring(i, end)] ?: continue
                        // Sentence entries are useful for typing, but must not swallow common words
                        // when extracting clipboard text. Compare longer entries per two-character unit.
                        val score = ln(total / frequency.coerceAtMost(total)) * maxOf(1.0, (end - i) / 2.0) + cost[end]
                        if (score < cost[i]) { cost[i] = score; next[i] = end }
                    }
                }
                var i = 0
                while (i < run.length) { result.add(run.substring(i, next[i])); i = next[i] }
            }
        }
        return result
    }
}
