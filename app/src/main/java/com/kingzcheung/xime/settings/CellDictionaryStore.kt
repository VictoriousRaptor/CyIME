package com.kingzcheung.xime.settings

import com.kingzcheung.xime.settings.ScelDictionary.rimeLine
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class InstalledCellDictionary(
    val id: String, val name: String, val category: String,
    val count: Int, val skipped: Int = 0, val sourceUrl: String = "",
    val sha256: String,
    /** English words of a mixed list, offered by the English candidates (CellDictionaryEnglish). */
    val english: Int = 0,
    /** 笔画词库的词（「词 笔画编码」，没有拼音），只给笔画方案用（StrokeCellDictionary）。 */
    val stroke: Int = 0,
)

@Serializable
data class CellDictionaryState(
    val installed: List<InstalledCellDictionary> = emptyList(),
    val selected: Set<String> = emptySet(),
    val applied: Set<String> = emptySet(),
    /** Built-ins enabled once by default; a later switch-off by the user is never reverted. */
    val seeded: Set<String> = emptySet(),
    val selectedEnglish: Boolean = false,
    val appliedEnglish: Boolean = false,
    val downloadedWordRevision: Int = 0,
    val appliedWordRevision: Int = 0,
) {
    val pending: Boolean get() = selected != applied || selectedEnglish != appliedEnglish ||
        downloadedWordRevision != appliedWordRevision
}

/** Downloaded data stays outside Rime until explicitly applied. Never edits learned user dictionaries. */
class CellDictionaryStore(val directory: File) {
    companion object {
        private val lock = Any()
        @Volatile private var englishIndex: Pair<File, List<String>>? = null
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        /** Like [writeAtomic], streamed: a 40 MB imported list is copied, never read into one string. */
        internal fun copyAtomic(source: File, file: File) {
            if (file.isFile && file.length() == source.length() && sameBytes(source, file)) return
            file.parentFile?.mkdirs()
            val temp = File.createTempFile(".cell-", ".tmp", file.parentFile)
            try {
                source.inputStream().use { input -> temp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }

        private fun sameBytes(a: File, b: File): Boolean = a.inputStream().buffered(1 shl 16).use { x ->
            b.inputStream().buffered(1 shl 16).use { y ->
                var left: Int
                var right: Int
                do { left = x.read(); right = y.read() } while (left == right && left >= 0)
                left == right
            }
        }

        internal fun writeAtomic(file: File, text: String) {
            if (file.isFile && file.readText() == text) return
            file.parentFile?.mkdirs()
            val temp = File.createTempFile(".cell-", ".tmp", file.parentFile)
            try {
                temp.writeText(text, Charsets.UTF_8)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }
    }

    fun read(): CellDictionaryState = synchronized(lock) {
        val file = File(directory, "state.json")
        if (file.exists()) json.decodeFromString<CellDictionaryState>(file.readText()) else CellDictionaryState()
    }

    private fun save(state: CellDictionaryState) {
        writeAtomic(File(directory, "state.json"), json.encodeToString(state))
        if (englishIndex?.first == directory) englishIndex = null
    }

    private fun updateFile(revision: Int) = File(directory, "word-updates-$revision.json")

    private fun readWordFile(file: File): CellWordUpdates = file.inputStream().use {
        CellWordUpdates.parse(ScelDictionary.readBounded(it, CellWordUpdates.MAX_BYTES))
    }

    internal fun stageWordUpdates(bytes: ByteArray, bundled: CellWordUpdates): Boolean {
        val update = CellWordUpdates.parse(bytes)
        return synchronized(lock) {
            val old = read()
            if (update.revision <= bundled.revision || update.revision < old.downloadedWordRevision) return@synchronized false
            val file = updateFile(update.revision)
            val existing = if (file.isFile) runCatching { readWordFile(file) }.getOrNull() else null
            if (existing != null) require(existing == update) { "同一词库版本的内容不一致" }
            else writeAtomic(file, bytes.decodeToString())
            if (old.downloadedWordRevision == update.revision) return@synchronized false
            save(old.copy(downloadedWordRevision = update.revision))
            if (old.downloadedWordRevision != old.appliedWordRevision) updateFile(old.downloadedWordRevision).delete()
            true
        }
    }

    internal fun wordUpdates(revision: Int, bundled: CellWordUpdates): CellWordUpdates = synchronized(lock) {
        if (revision <= bundled.revision) bundled else readWordFile(updateFile(revision)).also {
            require(it.revision == revision) { "词库更新版本不符" }
        }
    }

    fun dictionaryFile(id: String): File {
        require(id.matches(Regex("(sogou_[0-9]+|local_[a-f0-9]{24}|builtin_[a-z0-9]+)"))) { "无效词库标识" }
        return File(directory, "cell_$id.dict.yaml")
    }

    private fun englishFile(id: String): File = File(directory, dictionaryFile(id).name.removeSuffix(".dict.yaml") + ".en.txt")

    /** 笔画词库的词，一行一个；没有时不存在。应用词库时进笔画方案的 stroke_cells（[StrokeCellDictionary]）。 */
    fun strokeWordsFile(id: String): File = File(directory, dictionaryFile(id).name.removeSuffix(".dict.yaml") + ".stroke.txt")

    fun englishWords(id: String): List<String> = englishFile(id).takeIf { it.isFile }?.readLines()
        ?.filter { it.isNotBlank() }.orEmpty()

    /** Only applied dictionaries feed the independent English keyboard. Sort once, then binary-search per key. */
    fun englishCompletions(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val words = englishIndex?.takeIf { it.first == directory }?.second ?: synchronized(lock) {
            englishIndex?.takeIf { it.first == directory }?.second ?: read().let { state ->
                val active = if (state.appliedEnglish) state.applied.sorted().flatMap(::englishWords)
                    .distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER) else emptyList()
                englishIndex = directory to active
                active
            }
        }
        val index = words.binarySearch(prefix, String.CASE_INSENSITIVE_ORDER)
        val first = if (index < 0) -index - 1 else index
        val result = ArrayList<String>()
        for (pos in first until words.size) {
            val word = words[pos]
            if (!word.startsWith(prefix, ignoreCase = true) || result.size == limit) break
            result.add(when {
                prefix.length > 1 && prefix.all(Char::isUpperCase) -> word.uppercase()
                prefix.first().isUpperCase() -> word.replaceFirstChar(Char::uppercaseChar)
                else -> word
            })
        }
        return result
    }

    fun install(bytes: ByteArray, offer: CellDictionaryOffer? = null): InstalledCellDictionary = synchronized(lock) {
        val hash = digest(bytes)
        val id = offer?.id ?: "local_${hash.take(24)}"
        val old = read()
        // An installed source is immutable while selected/applied; a failed download never damages it.
        old.installed.firstOrNull { it.id == id }?.let {
            if (dictionaryFile(id).isFile) return@synchronized it
            require(it.sha256 == hash) { "来源词库已更新，请作为本地新词库导入" }
        }
        val scel = bytes.size >= 4 && bytes[0] == 0x40.toByte() && bytes[1] == 0x15.toByte() &&
            bytes[2] == 0.toByte() && bytes[3] == 0.toByte()
        val parsed = when {
            offer != null || scel -> ScelDictionary.parse(bytes)
            bytes.firstOrNull { it.toInt().toChar() !in " \r\n\t" } == '{'.code.toByte() -> JsonCellDictionary.parse(bytes)
            else -> TxtCellDictionary.parse(bytes)  // 词 拼音 / 拼音 词 / Rime .dict.yaml
        }
        val entry = InstalledCellDictionary(id, offer?.name ?: parsed.name.ifBlank { "本地词库" },
            offer?.category ?: "本地导入", parsed.entries.size, parsed.skipped, offer?.sourceUrl.orEmpty(), hash,
            parsed.english.size)
        writeAtomic(dictionaryFile(id), ScelDictionary.toRime("cell_$id", parsed.entries))
        if (parsed.english.isEmpty()) englishFile(id).delete()
        else writeAtomic(englishFile(id), parsed.english.joinToString("\n", postfix = "\n"))
        save(old.copy(installed = old.installed.filterNot { it.id == id } + entry))
        entry
    }

    /**
     * A file picked by the user, copied to disk first (bounded, hashed on the way) instead of into memory. A plain
     * or Rime list (also inside a Gboard zip, or from a Microsoft Pinyin .dctx: [DictionaryImportFormats]) is
     * converted line by line, up to [TxtCellDictionary.MAX_BYTES] (万象's base list is 49 MB);
     * SCEL and JSON stay at 16 MB and go through [install].
     */
    fun installFile(input: InputStream): InstalledCellDictionary {
        directory.mkdirs()
        // Left behind when the app was killed mid-import; only imports use this prefix.
        directory.listFiles { file -> file.name.startsWith(".import-") }?.forEach { it.delete() }
        val source = File.createTempFile(".import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= TxtCellDictionary.MAX_BYTES) { "词库超过 128 MB，请拆分后导入" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            val (scel, json) = source.inputStream().buffered().use { stream ->
                val head = ByteArray(4)
                var read = 0
                while (read < head.size) { val n = stream.read(head, read, head.size - read); if (n < 0) break; read += n }
                var first = head.take(read).firstOrNull { it.toInt().toChar() !in " \r\n\t" }?.toInt()
                if (first == null && read == head.size) {
                    while (true) { val b = stream.read(); if (b < 0 || b.toChar() !in " \r\n\t") { first = b; break } }
                }
                (read == 4 && head[0] == 0x40.toByte() && head[1] == 0x15.toByte() && head[2] == 0.toByte() &&
                    head[3] == 0.toByte()) to (first == '{'.code)
            }
            if (scel || json) {
                require(source.length() <= ScelDictionary.MAX_BYTES) { "SCEL 和 JSON 词库最大 16 MB" }
                return install(source.readBytes())
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            // Gboard's zip and Microsoft Pinyin's .dctx are unpacked to plain lines first.
            DictionaryImportFormats.toText(source)?.let { text ->
                try { return installText(text, hash) } finally { text.delete() }
            }
            return installText(source, hash)
        } finally { source.delete() }
    }

    /** Converted outside the lock (a big list takes seconds), then moved in and recorded under it. */
    private fun installText(source: File, hash: String): InstalledCellDictionary {
        val id = "local_${hash.take(24)}"
        read().installed.firstOrNull { it.id == id }?.let { if (dictionaryFile(id).isFile) return it }
        val converted = File.createTempFile(".import-", ".tmp", directory)
        val strokeWords = File.createTempFile(".import-", ".tmp", directory)
        try {
            val summary = converted.bufferedWriter().use { output ->
                strokeWords.bufferedWriter().use { stroke ->
                    output.write(ScelDictionary.rimeHeader("cell_$id"))
                    TxtCellDictionary.read({ source.inputStream() }, { stroke.append(it).append('\n') }) { output.rimeLine(it) }
                }
            }
            return synchronized(lock) {
                val old = read()
                old.installed.firstOrNull { it.id == id }?.let { if (dictionaryFile(id).isFile) return@synchronized it }
                val entry = InstalledCellDictionary(id, "本地词库", "本地导入", summary.count, summary.skipped, "", hash,
                    summary.english.size, summary.stroke)
                Files.move(converted.toPath(), dictionaryFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
                if (summary.stroke == 0) strokeWordsFile(id).delete()
                else Files.move(strokeWords.toPath(), strokeWordsFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
                if (summary.english.isEmpty()) englishFile(id).delete()
                else writeAtomic(englishFile(id), summary.english.joinToString("\n", postfix = "\n"))
                save(old.copy(installed = old.installed.filterNot { it.id == id } + entry))
                entry
            }
        } finally { converted.delete(); strokeWords.delete() }
    }

    /**
     * Refresh a bundled dictionary from the APK, with its English words (CellDictionaryEnglish) if it has any;
     * a default-on one is enabled once on first sight.
     */
    fun installBuiltin(builtin: BuiltinCellDictionary, text: String, english: List<String> = emptyList()) = synchronized(lock) {
        val old = read()
        val hash = digest((text + english.joinToString("\n")).toByteArray())
        val count = text.substringAfter("\n...\n").lineSequence().count { it.contains('\t') }
        val entry = InstalledCellDictionary(builtin.id, builtin.name, builtin.category, count,
            sourceUrl = builtin.sourceUrl, sha256 = hash, english = english.size)
        writeAtomic(dictionaryFile(builtin.id), text)
        if (english.isEmpty()) englishFile(builtin.id).delete()
        else writeAtomic(englishFile(builtin.id), english.joinToString("\n", postfix = "\n"))
        val first = builtin.id !in old.seeded
        val enable = first && builtin.defaultOn
        if (old.installed.firstOrNull { it.id == builtin.id } == entry && !first) return@synchronized
        save(old.copy(
            installed = old.installed.filterNot { it.id == builtin.id } + entry,
            selected = if (enable) old.selected + builtin.id else old.selected,
            applied = if (enable) old.applied + builtin.id else old.applied,
            seeded = old.seeded + builtin.id,
        ))
    }

    fun select(id: String, enabled: Boolean) = synchronized(lock) {
        val old = read()
        require(old.installed.any { it.id == id } && (!enabled || dictionaryFile(id).isFile)) { "请先下载词库" }
        save(old.copy(selected = if (enabled) old.selected + id else old.selected - id))
    }

    fun selectEnglish(enabled: Boolean) = synchronized(lock) {
        save(read().copy(selectedEnglish = enabled))
    }

    /** Removes a downloaded or imported dictionary that is not in use; built-ins stay. */
    fun remove(id: String) = synchronized(lock) {
        val old = read()
        require(!id.startsWith("builtin_")) { "内置词库不能删除，可以关闭" }
        require(id !in old.applied) { "请先关闭这个词库并应用更改，再删除" }
        dictionaryFile(id).delete()
        englishFile(id).delete()
        strokeWordsFile(id).delete()
        save(old.copy(installed = old.installed.filterNot { it.id == id }, selected = old.selected - id))
    }

    fun markApplied(ids: Set<String>, english: Boolean? = null, wordRevision: Int? = null) = synchronized(lock) {
        val old = read()
        require(ids.all { id -> old.installed.any { it.id == id } })
        save(old.copy(applied = ids, appliedEnglish = english ?: old.appliedEnglish,
            appliedWordRevision = wordRevision ?: old.appliedWordRevision)) // preserve any newer user selection
    }

    /** Commit only after the engine accepted the selection; repair its previous state on failure. */
    fun activate(ids: Set<String>, english: Boolean, prepareAndDeploy: (Set<String>, Boolean, Int) -> Unit): Unit = synchronized(lock) {
        val previous = read()
        try {
            prepareAndDeploy(ids, english, previous.downloadedWordRevision)
            markApplied(ids, english, previous.downloadedWordRevision)
        } catch (error: Exception) {
            try { prepareAndDeploy(previous.applied, previous.appliedEnglish, previous.appliedWordRevision) }
            catch (restoreError: Exception) { error.addSuppressed(restoreError) }
            throw error
        }
        if (previous.appliedWordRevision != previous.downloadedWordRevision) updateFile(previous.appliedWordRevision).delete()
    }
}
