package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.LetterLanguage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一种语言的补全词表：[words] 按词频从高到低（tools/wordlists/build_words.py 生成）。
 * 打字不用打重音和变音：cancion 给出 canción、uber 给出 über、strasse 给出 Straße、еще 给出 ещё、nguoi 给出 người；
 * 打了重音的那一位只认这个重音（越南语打了变音还没打声调时，带声调的也算：viê 给出 việt）。
 */
internal class WordLexicon(private val words: List<String>) {
    /** 词的下标按去重音小写排好，前缀查找用二分。 */
    private val keys: Array<String>
    private val byKey: IntArray

    init {
        val folded = Array(words.size) { fold(words[it]) }
        byKey = words.indices.sortedWith(compareBy({ folded[it] }, { it })).toIntArray()
        keys = Array(byKey.size) { folded[byKey[it]] }
    }

    /**
     * 打到一半的 [typed] 可以补成的词，最多 [limit] 个：去掉重音后整词一样的排前面（esta → está），
     * 再按词频排补全。不含与 [typed] 完全相同的词（它已经在候选栏第一个）。首字母大写时候选也大写。
     */
    fun complete(typed: String, limit: Int): List<String> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val prefix = fold(typed)
        val lower = typed.lowercase()
        val exact = mutableListOf<Int>()
        val longer = mutableListOf<Int>()
        var position = lowerBound(prefix)
        while (position < keys.size && keys[position].startsWith(prefix)) {
            val index = byKey[position]
            if (keepsTypedAccents(lower, words[index].lowercase())) {
                if (keys[position].length == prefix.length) exact += index else longer += index
            }
            position++
        }
        exact.sort()
        longer.sort()
        return (exact + longer).asSequence().map { cased(words[it], typed) }
            .filter { it != typed }.distinct().take(limit).toList()
    }

    private fun lowerBound(prefix: String): Int {
        var low = 0
        var high = keys.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (keys[middle] < prefix) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        private fun foldChar(char: Char): String = when (char) {
            'á', 'à', 'â', 'ä', 'ã', 'å' -> "a"; 'é', 'è', 'ê', 'ë' -> "e"; 'í', 'ì', 'î', 'ï' -> "i"
            'ó', 'ò', 'ô', 'ö', 'õ' -> "o"; 'ú', 'ù', 'û', 'ü' -> "u"; 'ñ' -> "n"; 'ç' -> "c"; 'ÿ' -> "y"
            'ß' -> "ss"; 'œ' -> "oe"; 'æ' -> "ae"; 'ё' -> "е"
            else -> VIETNAMESE_BASE[char]?.toString() ?: char.toString()
        }

        /** 越南语带声调、变音的字母（小写，上面已有的 á à â ã 这些除外）→ 底下的拉丁字母。 */
        private val VIETNAMESE_BASE: Map<Char, Char> = buildMap {
            for (char in VIETNAMESE_TONED + "ăâêôơưđ") {
                val base = if (char == 'đ') 'd' else java.text.Normalizer.normalize(char.toString(), java.text.Normalizer.Form.NFD)[0]
                put(char, base)
            }
        }

        /** 越南语元音（含变音）各带五个声调，NFC。 */
        private const val VIETNAMESE_TONED = "áàảãạắằẳẵặấầẩẫậéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ"

        /** 越南语带声调的字母去掉声调，变音留着（ệ → ê、ữ → ư）；不是这些字母原样返回。 */
        private fun withoutVietnameseTone(char: Char): Char {
            if (char !in VIETNAMESE_TONED) return char
            val parts = java.text.Normalizer.normalize(char.toString(), java.text.Normalizer.Form.NFD)
                .filter { it !in "\u0300\u0301\u0303\u0309\u0323" }
            return java.text.Normalizer.normalize(parts, java.text.Normalizer.Form.NFC)[0]
        }

        fun fold(text: String): String = buildString(text.length) { for (char in text.lowercase()) append(foldChar(char)) }

        /**
         * 打了重音（或 ñ、ß、ё）的位置，词在这一位必须是同一个字母；没打重音的位置哪个都行。
         * 位置按去重音后的字符算：ß 占两位（ss），strasse 和 straße 对得上。
         */
        private fun keepsTypedAccents(typed: String, word: String): Boolean {
            val starts = HashMap<Int, Char>(word.length * 2)
            var offset = 0
            for (char in word) {
                starts[offset] = char
                offset += foldChar(char).length
            }
            offset = 0
            for (char in typed) {
                val folded = foldChar(char)
                val inWord = starts[offset]
                if (folded != char.toString() && inWord != char && (inWord == null || withoutVietnameseTone(inWord) != char)) return false
                offset += folded.length
            }
            return true
        }

        /** Cancion → Canción，CANC → CANCIÓN；词表里本来大写的（Madrid、Haus）保持大写。 */
        fun cased(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }
}

/**
 * 各语言的补全词表：商店里的下载包（[LetterLanguage.wordsModel]），第一次用到时在 IO 线程读入。
 * 语言开着却还没下载（例如从随包词表的旧版升级）时在后台下载一次，下完后下一个字母就有补全。
 */
internal object WordCompletion {
    private const val TAG = "WordCompletion"
    private val loading = Mutex()
    private val lexicons = ConcurrentHashMap<LetterLanguage, Pair<String, WordLexicon>>()
    private val downloadRequested = ConcurrentHashMap.newKeySet<LetterLanguage>()

    fun file(context: Context, language: LetterLanguage): File? {
        val model = language.wordsModel ?: return null
        return File(com.kingzcheung.xime.model.ModelStorage.getModelDir(context, model), language.wordsFile!!)
    }

    fun isInstalled(context: Context, language: LetterLanguage): Boolean =
        file(context, language)?.let { it.isFile && it.length() > 0 } ?: true

    /** 在后台下载词表；已在下载或已装好时什么都不做。 */
    fun download(context: Context, language: LetterLanguage) {
        if (isInstalled(context, language)) return
        val model = com.kingzcheung.xime.model.ModelManager.getModel(language.wordsModel ?: return) ?: return
        com.kingzcheung.xime.model.ModelManager.downloadModelInBackground(context.applicationContext, model)
    }

    suspend fun complete(context: Context, language: LetterLanguage, typed: String, limit: Int): List<String> {
        val source = file(context, language) ?: return emptyList()
        if (!source.isFile) {
            if (downloadRequested.add(language)) download(context, language)
            return emptyList()
        }
        val signature = "${source.length()}:${source.lastModified()}"
        val loaded = lexicons[language]?.takeIf { it.first == signature }?.second ?: loading.withLock {
            lexicons[language]?.takeIf { it.first == signature }?.second ?: withContext(Dispatchers.IO) {
                runCatching {
                    WordLexicon(source.bufferedReader().useLines { lines ->
                        lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    })
                }.onFailure { Log.e(TAG, "${language.name} word list could not load", it) }.getOrNull()
            }?.also { lexicons[language] = signature to it }
        } ?: return emptyList()
        return withContext(Dispatchers.Default) { loaded.complete(typed, limit) }
    }
}
