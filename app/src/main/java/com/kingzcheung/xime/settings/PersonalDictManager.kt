package com.kingzcheung.xime.settings

import android.content.Context
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import kotlinx.coroutines.sync.withLock
import java.io.File

object PersonalDictManager {
    private const val CUSTOM_PHRASE_FILE = "custom_phrase.txt"

    private fun packName(rimeDir: File, schemaId: String): String {
        // 方案自带 translator.packs 声明（如 pinyin_simp 自带 [user_simp]）时，
        // 个人词库沿用方案声明的名字，否则 librime 编译时找不到对应的 pack 源文件。
        val own = readSchemaPacks(rimeDir, schemaId)
        if (own.isNotEmpty()) return own.first()
        val dictName = schemaDictionaryName(rimeDir, schemaId)
        return "user_$dictName"
    }

    /** 从 schema 文件读取 translator.packs 中声明的 user_* 个人词库名，没有则返回空。 */
    internal fun readSchemaPacks(rimeDir: java.io.File, schemaId: String): List<String> {
        val schemaFile = java.io.File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return emptyList()
        val text = try { schemaFile.readText(Charsets.UTF_8).trimStart('\uFEFF') } catch (_: Exception) { return emptyList() }
        // kaml 解析（schema.yaml 是 YAML，避免脆弱的正则）；
        // 第三方方案的 .schema.yaml 可能含锚点/别名等非严格语法导致解析失败，此时回退正则。
        val viaKaml = readSchemaPacksKaml(text)
        if (viaKaml != null) return viaKaml
        return readSchemaPacksRegex(text)
    }

    private fun readSchemaPacksKaml(text: String): List<String>? {
        return try {
            val root = SchemaManager.yaml.parseToYamlNode(text) as? YamlMap ?: return emptyList()
            val packs = (root["translator"] as? YamlMap)?.get("packs") as? YamlList ?: return emptyList()
            packs.items.mapNotNull { (it as? YamlScalar)?.content }
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.startsWith("user_") }
        } catch (_: Exception) {
            null
        }
    }

    private fun readSchemaPacksRegex(text: String): List<String> {
        return try {
            val block = Regex("""^translator:[\s\S]*?^  packs:\s*\n([\s\S]*?)(?=^\S|\Z)""", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1) ?: return emptyList()
            Regex("""^\s*-\s*"?(\w+)"?""", RegexOption.MULTILINE).findAll(block)
                .map { it.groupValues[1] }
                .filter { it.startsWith("user_") }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getCustomPhraseFile(context: Context, schemaId: String? = null): File {
        val rimeDir = SchemaManager.getRimeDir(context)
        val dictName = if (schemaId != null) getCustomPhraseDictName(rimeDir, schemaId)
                       else CUSTOM_PHRASE_FILE.removeSuffix(".txt")
        return File(rimeDir, "$dictName.txt")
    }

    /**
     * 从方案的 .custom.yaml 中读取 `custom_phrase.user_dict` 定义的文件名。
     * 例如 `user_dict: custom_phrase_double` → 对应 `custom_phrase_double.txt`。
     * 若无声明则返回默认的 `custom_phrase`。
     */
    internal fun getCustomPhraseDictName(rimeDir: File, schemaId: String): String {
        val customText = File(rimeDir, "${schemaId}.custom.yaml")
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
        if (customText != null) {
            val fromCustom = parseCustomPhraseDictName(customText)
            if (fromCustom != null) return fromCustom
        }
        val schemaText = File(rimeDir, "${schemaId}.schema.yaml")
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
        if (schemaText != null) {
            val fromSchema = parseCustomPhraseDictName(schemaText)
            if (fromSchema != null) return fromSchema
        }
        return CUSTOM_PHRASE_FILE.removeSuffix(".txt")
    }

    /** 从文本中解析 `custom_phrase.user_dict` 声明的文件名，没有则返回 null。 */
    private fun parseCustomPhraseDictName(text: String): String? {
        // Third-party schemas can contain YAML tags unsupported by our parser.
        // Only read this translator's own block (or its Rime slash-path override).
        val path = Regex("""(?m)^\s*["']?custom_phrase/user_dict["']?\s*:\s*([^#\r\n]+)""")
        val block = Regex("""(?m)^([ ]*)["']?custom_phrase["']?:[ \t]*(?:#.*)?\r?$""").find(text)
        val value = path.find(text)?.groupValues?.get(1) ?: block?.let {
            text.substring(it.range.last + 1).lineSequence().filter { line -> line.isNotBlank() && !line.trimStart().startsWith('#') }
                .takeWhile { line -> line.takeWhile { ch -> ch == ' ' }.length > it.groupValues[1].length }
                .firstOrNull { line -> line.trimStart().startsWith("user_dict:") }?.substringAfter(':')?.substringBefore('#')
        }
        return value?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotEmpty() }
    }

    
    fun ensureCustomPhraseFileExists(context: Context, schemaId: String? = null) {
        val file = getCustomPhraseFile(context, schemaId)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
""", Charsets.UTF_8)
        }
    }

    fun loadCustomPhrases(context: Context, schemaId: String? = null): List<DictEntry> {
        val file = getCustomPhraseFile(context, schemaId)
        if (!file.exists()) return emptyList()
        return try {
            parseStableDbEntries(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomPhrases(context: Context, schemaId: String? = null, entries: List<DictEntry>) {
        val file = getCustomPhraseFile(context, schemaId)
        file.parentFile?.mkdirs()
        CellDictionaryStore.writeAtomic(file, buildStableDbText(STABLEDB_HEADER, entries))
    }

    /** Bumped after saved phrases are ready; the keyboard then re-selects its schema in its input queue. */
    const val CUSTOM_PHRASE_REVISION = "custom_phrase_revision"

    /**
     * Saved phrases reach the running keyboard. A schema's first phrase adds its phrase translator
     * (deployed incrementally); then the keyboard re-selects its schema, since an open session never
     * re-reads custom_phrase.txt. Not on Main: the first phrase may deploy.
     */
    suspend fun applyCustomPhrases(
        context: Context,
        schemaId: String?,
        deploy: () -> Unit = {
            if (com.kingzcheung.xime.rime.RimeEngine.isInitialized()) com.kingzcheung.xime.rime.RimeConfigHelper.ensureDeployment(context)
        },
        notifyKeyboard: () -> Unit = {
            val prefs = SettingsPreferences.getPrefsPublic(context)
            prefs.edit().putLong(CUSTOM_PHRASE_REVISION, prefs.getLong(CUSTOM_PHRASE_REVISION, 0L) + 1L).apply()
        },
    ) {
        val rimeDir = SchemaManager.getRimeDir(context)
        val schemas = schemaId?.let(::listOf) ?: SchemaManager.getEnabledSchemas(context)
        fun patches() = schemas.map { File(rimeDir, "$it.custom.yaml").takeIf(File::exists)?.readText(Charsets.UTF_8) }
        val before = patches()
        if (schemaId != null) ensureSchemaPack(context, schemaId) else ensureSchemaPacks(context)
        if (patches() != before) deploy()
        notifyKeyboard()
    }

    /** 清理 custom_phrase 文件（仅当文件为空时删除，避免丢失用户数据）。 */
    fun removeCustomPhraseFile(context: Context, schemaId: String? = null) {
        val file = getCustomPhraseFile(context, schemaId)
        if (file.exists() && file.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() && !it.startsWith('#') } == 0) {
            file.delete()
        }
    }

    // ── 方案配置补丁 ──

    private val schemaPacksMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureSchemaPacks(context: Context) {
        schemaPacksMutex.withLock {
            val rimeDir = SchemaManager.getRimeDir(context)
            val enabledSchemas = SchemaManager.getEnabledSchemas(context)
            for (schemaId in enabledSchemas) {
                ensureSchemaPackInner(rimeDir, context, schemaId)
            }
        }
    }

    suspend fun ensureSchemaPack(context: Context, schemaId: String) {
        schemaPacksMutex.withLock {
            val rimeDir = SchemaManager.getRimeDir(context)
            ensureSchemaPackInner(rimeDir, context, schemaId)
        }
    }

    private suspend fun ensureSchemaPackInner(rimeDir: java.io.File, context: Context, schemaId: String) {
        val schemaFile = java.io.File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return
        val customFile = File(rimeDir, "${schemaId}.custom.yaml")
        if (!optionalPhrasePatch(customFile) { repairTranslatorFile(customFile) }) return
        // 手写方案（schemas 绑定声明）不经过 librime 词典输入，跳过补丁。
        if (isHandwritingSchema(schemaId)) return
        // 注音的编码是大千键码，短语的拼音字母码在注音里是另一串注音（ji 是 ㄨㄛ），不挂短语。
        // 仓颉同理：短语的拼音码在仓颉里是别的字的码（nh 是 弓竹），会抢在码表前面。
        if (InputProfiles.describe(schemaId).scheme in setOf(InputScheme.ZHUYIN, InputScheme.CANGJIE)) return
        // 西班牙、德、法、俄、韩语的字母不进 Rime，中文短语挂上去也用不到。
        if (LetterLanguage.ofSchema(schemaId) != null) return
        // 个人词库合并规则已移除：清理旧版本写入的 merged 词典引用，
        // 让 librime 按 schema 原声明编译原始词典名（如 pinyin_simp.table.bin），
        // 恢复 wubi86_pinyin 等方案的 reverse_lookup 拼音反查。
        cleanupStaleMergedPatch(rimeDir, schemaId)
        // 九键打出来的是数字、14 键是合并键的大写码、双拼是双拼码：短语换成这个键盘实际打出的编码另存一份，方案挂这一份。
        val doublePinyin = DoublePinyinSchemes.find(schemaId) ?: if (DoublePinyinSchemes.isGenerated(schemaId))
            DoublePinyinSchemes.load(context).let { DoublePinyinSchemes.find(schemaId) } else null
        val keys = keyedPhrases(schemaId, doublePinyin)
        val sharedName = keys?.dictName ?: "custom_phrase"
        val configured = getCustomPhraseDictName(rimeDir, schemaId)
        val dictName = if (isGeneratedPhraseDict(configured)) sharedName else configured
        // 方案自己的 custom_phrase 带着词典（万象：dictionary: custom_phrase，它的 Lua 置顶词靠这一块），
        // 整块换成我们的会让它的置顶词失效：不动这一块，「我的短语」另挂一份。
        val ownTable = hasOwnPhraseDictionary(schemaFile.readText(Charsets.UTF_8))
        if (ownTable) dropGeneratedMainPhrase(customFile)
        if (keys != null) writeKeyedPhrases(rimeDir, keys)
        val phraseFile = File(rimeDir, "$dictName.txt")
        if (!phraseFile.exists()) {
            phraseFile.parentFile?.mkdirs()
            phraseFile.writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
""", Charsets.UTF_8)
        }
        // 有实际条目时才注入翻译器，避免 Rime 因空 .table.bin 报错
        if (!ownTable && phraseFile.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() && !it.startsWith('#') } > 0) {
            optionalPhrasePatch(customFile) { applyCustomPhraseTranslator(rimeDir, schemaId, dictName) }
        }
        // Some letter schemes (e.g. Xiaohe) have a private phrase table. Keep it
        // and its entries; add the table the user actually edits in 我的短语.
        if ((dictName != sharedName || ownTable) &&
            File(rimeDir, "$sharedName.txt").takeIf { it.isFile }?.useLines { rows ->
                rows.any { it.isNotBlank() && !it.startsWith('#') }
            } == true) {
            optionalPhrasePatch(customFile) {
                applyCustomPhraseTranslator(rimeDir, schemaId, sharedName, "cyime_shared_phrase")
                retargetCustomPhraseDict(customFile, sharedName, "cyime_shared_phrase")
            }
        }
        // Migrate our old table binding, never retarget a user's private table.
        if (dictName != configured) retargetCustomPhraseDict(customFile, dictName)
    }

    /**
     * Keyboards whose keys do not type the letters of a phrase code. The shared custom_phrase.txt stays the
     * only list the user edits; each of these gets a copy whose codes are what its keys send.
     */
    internal enum class PhraseKeys(val dictName: String) {
        /** abc→2 … wxyz→9, the digits the nine keys send. */
        T9("custom_phrase_t9"),
        /** A pair key taps its capital (qw→Q, er→E …; l and m stay); letters swiped one by one stay as written. */
        MERGED14("custom_phrase_k14");

        fun codes(code: String): List<String> {
            val letters = code.lowercase().filterNot { it == ' ' || it == '\'' }
            if (letters.isEmpty() || letters.any { it !in 'a'..'z' }) return emptyList()
            return when (this) {
                T9 -> listOf(letters.map { T9_DIGITS[it - 'a'] }.joinToString(""))
                MERGED14 -> listOf(letters.map { MERGED14_KEYS[it - 'a'] }.joinToString(""), letters).distinct()
            }
        }

        companion object {
            private const val T9_DIGITS = "22233344455566677778889999"
            private const val MERGED14_KEYS = "ABCDEDGGUJJlmBOOQEATUCQZTZ"
            fun of(schemaId: String): PhraseKeys? {
                val profile = if (KeysConfigHelper.hasLoadedLayoutBindings) InputProfiles.current(schemaId)
                    else InputProfiles.describe(schemaId)
                return when {
                    profile.layout.kind == LayoutKind.T9 -> T9
                    profile.layout == InputLayout.MERGED14 -> MERGED14
                    else -> null
                }
            }
        }
    }

    /** 双拼方案的短语副本名，后面接方案 id（含九键、14 键、自建布局版本的后缀）。 */
    private const val DOUBLE_PINYIN_PHRASE_PREFIX = "custom_phrase_sp_"

    /** 方案顶层 custom_phrase 块自带非空 dictionary：不是 Rime 常见的「dictionary: ""＋user_dict」用户短语表。 */
    internal fun hasOwnPhraseDictionary(schemaText: String): Boolean {
        val block = Regex("""(?m)^custom_phrase:[ \t]*(?:#.*)?\r?$""").find(schemaText) ?: return false
        val value = schemaText.substring(block.range.last + 1).lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
            .takeWhile { it.startsWith(" ") || it.startsWith("\t") }
            .firstOrNull { it.trimStart().startsWith("dictionary:") }
            ?.substringAfter(':')?.substringBefore('#')?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
        return !value.isNullOrEmpty()
    }

    /** 以前给这类方案写进去的整块 custom_phrase 和它的翻译器（只认我们写的原样内容）。 */
    private fun dropGeneratedMainPhrase(customFile: File) {
        if (!customFile.isFile) return
        val text = customFile.readText(Charsets.UTF_8)
        val phrase = """table_translator@custom_phrase"""
        val translator = Regex("""(?m)^  ["']?engine/translators/(?:@before 0+|\+|@next|@after last)["']?:[ \t]*""" +
            """(?:$phrase|\[[ \t]*$phrase[ \t]*\]|\r?\n    - $phrase)[ \t]*(?:\r?\n|$)""")
        val defaults = """(?:dictionary: ""|user_dict: custom_phrase|db_class: stabledb|enable_completion: false|enable_sentence: false|initial_quality: 99)"""
        val block = Regex("""(?m)^  ["']?custom_phrase["']?:[ \t]*\r?\n(?:    $defaults[ \t]*(?:\r?\n|$))+""")
        val updated = block.replace(translator.replace(text, ""), "")
        if (updated != text) CellDictionaryStore.writeAtomic(customFile, updated)
    }

    /** 我们自己生成、可以换绑的短语表；方案自带的专用短语表（如小鹤的 custom_phrase_double）不算。 */
    private fun isGeneratedPhraseDict(name: String) = name == "custom_phrase" ||
        PhraseKeys.entries.any { it.dictName == name } || name.startsWith(DOUBLE_PINYIN_PHRASE_PREFIX)

    /** 一个键盘自己的短语副本：表名，以及一条短语编码在这个键盘上实际打出的编码。 */
    internal class KeyedPhrases(val dictName: String, val codes: (String) -> List<String>)

    /**
     * 这个方案要不要短语副本：九键、14 键按 [PhraseKeys] 换码；双拼（含九键、14 键双拼）先把全拼编码换成
     * 这套双拼的编码（b站 填 bzhan，小鹤里打 bvj），原编码也留着（用户本来就填双拼码时照样能打）。
     */
    internal fun keyedPhrases(schemaId: String, doublePinyin: DoublePinyinScheme?): KeyedPhrases? {
        val keys = PhraseKeys.of(schemaId)
        if (doublePinyin == null) return keys?.let { KeyedPhrases(it.dictName, it::codes) }
        return KeyedPhrases("$DOUBLE_PINYIN_PHRASE_PREFIX$schemaId") { code ->
            val typed = listOf(code) + doublePinyinCodes(doublePinyin, code)
            (if (keys == null) typed else typed.flatMap(keys::codes)).distinct()
        }
    }

    /**
     * 我的短语「固定在第几位」按编码存（cyime_pins.txt）：一条短语编码在每个开着的键盘上实际打出的编码，
     * 和各键盘短语副本用同一套换算（九键数字、14 键合并码、双拼码），再加上原编码本身。
     */
    internal fun phraseTypedCodes(context: Context, code: String): List<String> {
        val typed = linkedSetOf(code.trim().filterNot { it == ' ' || it == '\'' })
        for (schemaId in SchemaManager.getEnabledSchemas(context)) {
            val doublePinyin = DoublePinyinSchemes.find(schemaId) ?: if (DoublePinyinSchemes.isGenerated(schemaId))
                DoublePinyinSchemes.load(context).let { DoublePinyinSchemes.find(schemaId) } else null
            keyedPhrases(schemaId, doublePinyin)?.codes?.invoke(code.trim())?.let(typed::addAll)
        }
        return typed.filter { it.isNotEmpty() }
    }

    private val PINYIN_SYLLABLES by lazy { DoublePinyinScheme.SYLLABLES.toSet() }

    /**
     * 全拼写的短语编码换成 [scheme] 的双拼编码。按音节拆，也认单独一个声母（简拼，如 bzhan 的 b、dz）；
     * 拆法不唯一时（fangan = fang'an / fan'gan）几种都给，' 和空格是用户写的分隔。拆不成拼音的编码（符号快捷码等）不换。
     */
    internal fun doublePinyinCodes(scheme: DoublePinyinScheme, code: String): List<String> {
        val parts = code.lowercase().split('\'', ' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        return parts.fold(listOf("")) { heads, part ->
            val tails = doublePinyinPart(scheme, part)
            if (tails.isEmpty()) return emptyList()
            heads.flatMap { head -> tails.map { head + it } }.take(MAX_PHRASE_READINGS)
        }
    }

    private const val MAX_PHRASE_READINGS = 4

    /** 一段连续字母的双拼编码：音节越少越好，整音节优先于单个声母。 */
    private fun doublePinyinPart(scheme: DoublePinyinScheme, letters: String): List<String> {
        if (letters.any { it !in 'a'..'z' }) return emptyList()
        val best = arrayOfNulls<Pair<Int, List<String>>>(letters.length + 1).also { it[0] = 0 to listOf("") }
        for (start in letters.indices) {
            val (cost, heads) = best[start] ?: continue
            for (end in start + 1..minOf(letters.length, start + 6)) {
                val piece = letters.substring(start, end)
                val (step, key) = when {
                    // lue、nue 是 lve、nve 的另一种写法。
                    piece in PINYIN_SYLLABLES || piece == "lue" || piece == "nue" ->
                        2 to (scheme.code(piece.replace("ue", "ve").takeIf { it in PINYIN_SYLLABLES } ?: piece) ?: continue)
                    piece in DoublePinyinScheme.SPECIAL_INITIALS -> 3 to (scheme.initials[piece]?.toString() ?: continue)
                    piece.length == 1 && piece[0] in DoublePinyinScheme.PLAIN_INITIALS -> 3 to piece
                    else -> continue
                }
                val total = cost + step
                val codes = heads.map { it + key }
                val current = best[end]
                best[end] = when {
                    current == null || total < current.first -> total to codes
                    total == current.first -> total to (current.second + codes).distinct().take(MAX_PHRASE_READINGS)
                    else -> current
                }
            }
        }
        return best[letters.length]?.second.orEmpty()
    }

    internal fun t9PhraseReadings(rimeDir: File): Map<Pair<String, String>, String> {
        val source = File(rimeDir, CUSTOM_PHRASE_FILE)
        if (!source.isFile) return emptyMap()
        val content = try { source.readText(Charsets.UTF_8) } catch (_: java.io.IOException) { return emptyMap() }
        return buildMap {
            for (entry in parseStableDbEntries(content)) {
                val digits = PhraseKeys.T9.codes(entry.code).singleOrNull() ?: continue
                putIfAbsent(entry.word to digits, entry.code.lowercase().replace(' ', '\''))
            }
        }
    }

    internal fun writeKeyedPhrases(rimeDir: File, keys: PhraseKeys) =
        writeKeyedPhrases(rimeDir, KeyedPhrases(keys.dictName, keys::codes))

    /** Rewrites the keyed copy only when its content changes, so an unchanged list costs no reload. */
    internal fun writeKeyedPhrases(rimeDir: File, keys: KeyedPhrases) {
        val source = File(rimeDir, CUSTOM_PHRASE_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
        val entries = parseStableDbEntries(source).flatMap { entry -> keys.codes(entry.code).map { entry.copy(code = it) } }
        val text = buildStableDbText(STABLEDB_HEADER.replace("db_name\tcustom_phrase", "db_name\t${keys.dictName}"), entries)
        val target = File(rimeDir, "${keys.dictName}.txt")
        CellDictionaryStore.writeAtomic(target, text)
    }

    /** Points an existing custom_phrase translator patch at [dictName]; other patch lines are kept. */
    internal fun retargetCustomPhraseDict(customFile: File, dictName: String, namespace: String = "custom_phrase") {
        if (!customFile.isFile) return
        val text = customFile.readText(Charsets.UTF_8)
        val name = Regex.escape(namespace)
        val scalar = """(?:"[^"\r\n]*"|'[^'\r\n]*'|[^#\s]+)"""
        val block = Regex("""(?m)^([ ]+["']?$name["']?:[ \t]*\r?\n(?:[ ]{4,}[^\n]*\n)*?[ ]{4,}user_dict:[ \t]*)$scalar""")
        val path = Regex("""(?m)^([ ]+["']?$name/user_dict["']?:[ \t]*)$scalar""")
        val updated = listOf(block, path).fold(text) { yaml, key ->
            key.replace(yaml) { it.groupValues[1] + dictName }
        }
        if (updated != text) CellDictionaryStore.writeAtomic(customFile, updated)
    }

    /**
     * 清理旧版本个人词库合并规则遗留的产物：
     * 1. 移除 `${schemaId}.custom.yaml` 中的 `translator/dictionary: <schemaId>_merged` 引用，
     *    让 librime 按 schema 原声明编译原始词典名（如 pinyin_simp.table.bin）；
     * 2. 删除 `${schemaId}_merged.dict.yaml` 合并表转发文件。
     */
    internal fun cleanupStaleMergedPatch(rimeDir: java.io.File, schemaId: String) {
        val mergedDict = java.io.File(rimeDir, "${schemaId}_merged.dict.yaml")
        if (mergedDict.exists()) {
            mergedDict.delete()
        }
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (!customFile.exists()) return
        val text = customFile.readText(Charsets.UTF_8)
        val mergedRef = "\"translator/dictionary\": ${schemaId}_merged"
        if (!text.contains(mergedRef)) return
        val cleaned = text
            .replace(Regex("""^[ \t]*"?translator/dictionary"?\s*:\s*${schemaId}_merged\s*$""", RegexOption.MULTILINE), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd('\n', '\r', ' ') + "\n"
        if (cleaned != text) {
            customFile.writeText(cleaned, Charsets.UTF_8)
        }
    }

    private inline fun optionalPhrasePatch(file: File, change: () -> Unit): Boolean {
        try {
            change()
            return true
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.kingzcheung.xime.util.FileLogger.w("PersonalDictManager", "Skipped optional phrase patch in ${file.name}: ${e.message}")
            return false
        }
    }

    internal fun repairTranslatorFile(file: File) {
        if (!file.isFile) return
        val original = file.readText()
        val repaired = TranslatorAppendPatch.repair(original)
        if (repaired == original) return
        val backup = File(file.parentFile, file.name + ".before-translator-repair")
        if (!backup.exists()) CellDictionaryStore.writeAtomic(backup, original)
        CellDictionaryStore.writeAtomic(file, repaired)
    }

    // 为方案添加 custom_phrase 翻译器（独立于主词典音节表）
    internal fun applyCustomPhraseTranslator(rimeDir: java.io.File, schemaId: String, dictName: String, namespace: String = "custom_phrase") {
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        val text = customFile.takeIf { it.isFile }?.readText().orEmpty()
        val patch = (SchemaManager.yaml.parseToYamlNode(text.ifBlank { "patch: {}" }) as? YamlMap)?.get("patch") as? YamlMap
        val phrase = "table_translator@$namespace"
        val beforeFirst = "engine/translators/@before 0"
        val atFront = patch?.entries?.any { (key, value) ->
            key.content.startsWith(beforeFirst) && key.content.removePrefix(beforeFirst).all { it == '0' } &&
                (value as? YamlScalar)?.content == phrase
        } == true
        val existing = patch?.get<YamlMap>(namespace)
        val defaults = mapOf("dictionary" to "\"\"", "user_dict" to dictName, "db_class" to "stabledb",
            "enable_completion" to "false", "enable_sentence" to "false", "initial_quality" to "99")
        val options = if (existing == null) {
            "  \"$namespace\":\n" + defaults.entries.joinToString("") { (key, value) -> "    $key: $value\n" }
        } else defaults.entries.filter { (key, _) ->
            key !in existing.entries.keys.map { it.content } && patch.entries.keys.none { it.content == "$namespace/$key" }
        }.joinToString("") { (key, value) -> "  \"$namespace/$key\": $value\n" }
        if (atFront && options.isEmpty()) return
        val occupied = patch?.entries?.keys.orEmpty().map { it.content }
        // Rime elects a translator by comparing it with its neighbour. Appending
        // can starve an exact match behind the Pinyin/English translators.
        val frontKey = generateSequence(beforeFirst) { it + "0" }.first { it !in occupied }
        val front = if (atFront) "" else "  \"$frontKey\": $phrase\n"
        val content = "$front$options"
        // Drop the old generated singleton append when moving it to the front.
        // Lists containing other translators and lines with user comments stay intact.
        val escaped = Regex.escape(phrase)
        val oldKeys = setOf("engine/translators/+", "engine/translators/@next", "engine/translators/@after last")
        val withoutLegacy = patch?.entries.orEmpty().entries.fold(text) { source, (key, value) ->
            val only = if (value is YamlList) value.items.singleOrNull() else value
            if (key.content !in oldKeys || (only as? YamlScalar)?.content != phrase) source
            else source.replace(Regex("""(?m)^  ["']?${Regex.escape(key.content)}["']?:[ \t]*(?:$escaped|\r?\n    - $escaped|\[[ \t]*$escaped[ \t]*\])[ \t]*(?:\r?\n|$)"""), "")
        }
        val result = TranslatorAppendPatch.repair(insertUnderPatch(withoutLegacy, content))
        SchemaManager.yaml.parseToYamlNode(result)
        CellDictionaryStore.writeAtomic(customFile, result)
    }

    /** 在 YAML 的 `patch:` 块下增量插入内容，不覆盖已有配置。 */
    private fun insertUnderPatch(text: String, content: String): String {
        if (text.isBlank()) return "patch:\n$content"
        val cleaned = text.trimEnd('\n', '\r', ' ').removeSuffix("...").trimEnd()
        val patchLine = Regex("""(?m)^patch:[ \t]*(?:\{[ \t]*\}[ \t]*)?(?:#.*)?$""").find(cleaned)
        if (patchLine == null) return "$cleaned\n\npatch:\n$content"
        val at = patchLine.range.last + 1
        return cleaned.substring(0, patchLine.range.first) + patchLine.value.replace(Regex("""\{[ \t]*\}"""), "") +
            "\n$content" + cleaned.substring(at) + "\n"
    }

    /** 从 schema 文件中读取 translator.dictionary 名称，没有则用 schemaId 兜底。 */
    private fun schemaDictionaryName(rimeDir: File, schemaId: String): String {
        val schemaFile = File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return schemaId
        val regex = Regex("""translator:.*?dictionary:\s*(\S+)""", setOf(RegexOption.DOT_MATCHES_ALL))
        return regex.find(schemaFile.readText(Charsets.UTF_8))?.groupValues?.get(1) ?: schemaId
    }

    // ── 个人词库 ──

    /**
     * 解析方案实际引用的个人词库文件名。
     * 优先级：方案自带 translator.packs → custom.yaml 中的 translator/packs → translator/dictionary（一路追溯到 user_*） → 默认 packName。
     */
    fun resolvePersonalDictFile(rimeDir: File, schemaId: String): File {
        // 方案自带 translator.packs 声明（开源方案仓库常见，如 pinyin_simp 的 [user_simp]）：
        // 个人词库以方案声明为准，不适用我们自造的 user_${dictionary} 命名。
        val own = readSchemaPacks(rimeDir, schemaId)
        if (own.isNotEmpty()) return File(rimeDir, "${own.first()}.dict.yaml")
        val customFile = File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            // 1) translator/packs 中最前面的 user_* 或显式声明的名字
            val packs = Regex(""""?translator/packs"?\s*:\s*\[(.+?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.split(',')?.firstOrNull { it.trim().removeSurrounding("\"").startsWith("user_") }
            if (packs != null) return File(rimeDir, "${packs.trim().removeSurrounding("\"")}.dict.yaml")
            // 2) translator/dictionary → 如果是 *_merged 则去合并文件中找 import_tables
            val dict = Regex(""""?translator/dictionary"?\s*:\s*"?(\w+)"?""").find(text)?.groupValues?.get(1)
            if (dict != null) {
                if (dict.endsWith("_merged")) {
                    val mergedFile = File(rimeDir, "$dict.dict.yaml")
                    if (mergedFile.exists()) {
                        val pk = Regex("""^[ \t]+-\s+(user_\S+)""", RegexOption.MULTILINE).findAll(mergedFile.readText(Charsets.UTF_8))
                            .map { it.groupValues[1] }.firstOrNull()
                        if (pk != null) return File(rimeDir, "$pk.dict.yaml")
                    }
                }
                return File(rimeDir, "$dict.dict.yaml")
            }
        }
        return getPackFile(rimeDir, schemaId)
    }

    /** 从 Context 获取 rime 目录下的个人词库文件。 */
    private fun getPackFile(rimeDir: File, schemaId: String): File =
        File(rimeDir, "${packName(rimeDir, schemaId)}.dict.yaml")

    /** 旧版文件名映射（兼容性），新文件不存在时用于兜底读取。 */
    private fun legacyPackFile(rimeDir: File, schemaId: String): File? {
        val oldName = when (schemaId) {
            "pinyin_simp", "t9_pinyin", "pinyin_14jian", "pinyin_17jian", "pinyin_18jian" -> "user_simp_pinyin"
            "wubi86", "wubi86_pinyin" -> "user_simp_wubi"
            else -> return null
        }
        val file = File(rimeDir, "$oldName.dict.yaml")
        return file.takeIf { it.exists() }
    }

    fun loadEntries(context: Context, schemaId: String): List<DictEntry> {
        val rimeDir = SchemaManager.getRimeDir(context)
        val file = resolvePersonalDictFile(rimeDir, schemaId)
        if (!file.exists()) {
            val legacy = legacyPackFile(rimeDir, schemaId)
            if (legacy != null) return try {
                parsePersonalDictEntries(legacy.readText(Charsets.UTF_8))
            } catch (_: Exception) { emptyList() }
            return emptyList()
        }
        return try {
            parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun parsePersonalDictEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        var inData = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!inData) { if (line == "...") inData = true; continue }
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            } else {
                val spaceIdx = line.indexOf(' ')
                if (spaceIdx >= 0) {
                    out.add(DictEntry(line.substring(0, spaceIdx), line.substring(spaceIdx + 1).trim()))
                }
            }
        }
        return out
    }

    private const val STABLEDB_HEADER = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    internal fun parseStableDbEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            }
        }
        return out
    }

    internal fun buildStableDbText(header: String, entries: List<DictEntry>): String {
        val sb = StringBuilder()
        sb.append(header.trimEnd('\n', '\r')).append('\n')
        for (e in entries) {
            // 短语里的换行存成 U+2028，表里仍是一行（见 PhraseLineBreaks）。
            sb.append(PhraseLineBreaks.stored(e.word)).append('\t').append(e.code)
            if (e.weight != null) sb.append('\t').append(e.weight)
            sb.append('\n')
        }
        return sb.toString()
    }
}
