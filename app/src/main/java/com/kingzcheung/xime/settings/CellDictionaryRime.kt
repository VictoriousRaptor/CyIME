package com.kingzcheung.xime.settings

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import java.io.File

/** Settings/deployment-only adapter. No work is added to the per-keystroke candidate path. */
object CellDictionaryRime {
    internal const val START = "# BEGIN CYIME CELL DICTIONARIES"
    internal const val END = "# END CYIME CELL DICTIONARIES"
    private fun YamlMap.node(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value

    fun supports(profile: InputProfile): Boolean = profile.language == InputLanguage.CHINESE &&
        profile.mode == InputMode.KEYBOARD && profile.engineProfile is EngineProfile.Rime &&
        profile.scheme in setOf(InputScheme.PINYIN, InputScheme.DOUBLE_PINYIN)

    /** 笔画方案：导入的词换成笔画首笔另编一个词表（[StrokeCellDictionary]），不走拼音词库包。 */
    internal fun usesStrokeCells(profile: InputProfile): Boolean = profile.language == InputLanguage.CHINESE &&
        profile.engineProfile is EngineProfile.Rime && profile.scheme == InputScheme.STROKE

    internal fun packName(dictionary: String): String = "user_cyime_cells_" +
        CellDictionaryStore.digest(dictionary.toByteArray()).take(16)

    /** Add/remove only our marked append patch, retaining every existing pack and other setting. */
    internal fun patch(original: String, pack: String?, english: CellDictionaryEnglish.Kind? = null): String {
        val beginCount = original.lineSequence().count { it.trim() == START }
        val endCount = original.lineSequence().count { it.trim() == END }
        require(beginCount == endCount && beginCount <= 1) { "词库配置标记损坏，请检查方案自定义文件" }
        val managed = Regex("(?m)^[ \\t]*${Regex.escape(START)}\\r?\\n[\\s\\S]*?^[ \\t]*${Regex.escape(END)}(?:\\r?\\n|$)")
        require(beginCount == 0 || managed.containsMatchIn(original)) { "词库配置标记顺序损坏" }
        val base = original.replace(managed, "")
        if (pack == null) return base
        require(pack.matches(Regex("user_cyime_cells_[a-f0-9]{16}")))
        val map = patchMap(base)
        require(map?.node("translator/packs/+") == null && map?.node("translator/packs/@next") == null) {
            "方案已自定义追加词库，暂不能自动合并；原配置已保留"
        }
        val englishLines = english?.let { kind -> CellDictionaryEnglish.patchLines(kind) { map?.node(it) != null } }
        require(english == null || englishLines != null) { "方案已自定义追加翻译器，暂不能自动合并英文词；原配置已保留" }
        // user_* also satisfies the legacy T9 pack validator; the file already exists before deployment.
        return withBlock(base, listOf("\"translator/packs/+\": [\"$pack\"]") + englishLines.orEmpty())
    }

    /** 笔画方案：导入的词库换成笔画首笔写成 [StrokeCellDictionary.NAME]，列进方案依赖一起编译；[enabled] 为假时只去掉旧块。 */
    internal fun strokePatch(original: String, enabled: Boolean): String {
        val base = patch(original, null)
        if (!enabled) return base
        val map = patchMap(base)
        require(map?.node("schema/dependencies") == null && map?.node("schema/dependencies/+") == null) { "方案已自定义依赖，暂不能自动合并；原配置已保留" }
        return withBlock(base, listOf("\"schema/dependencies/+\": [${StrokeCellDictionary.NAME}]"))
    }

    private fun patchSource(base: String): String =
        if (base.lineSequence().all { it.isBlank() || it.trimStart().startsWith('#') })
            base + (if (base.isEmpty() || base.endsWith('\n')) "" else "\n") + "patch:\n" else base

    private fun patchMap(base: String): YamlMap? {
        val node = SchemaManager.yaml.parseToYamlNode(patchSource(base))
        require(node is YamlMap || node is YamlNull) { "方案自定义文件不是 YAML 映射" }
        return (node as? YamlMap)?.node("patch") as? YamlMap
    }

    /** 把 [lines] 作为受管块插在 patch: 下面（[base] 已去掉旧块）。 */
    private fun withBlock(base: String, lines: List<String>): String {
        val source = patchSource(base)
        val root = SchemaManager.yaml.parseToYamlNode(source) as? YamlMap
        val patchNode = root?.node("patch")
        val map = patchNode as? YamlMap
        // Reject flow maps/aliases instead of rewriting third-party YAML and losing its semantics.
        val line = Regex("(?m)^patch:[ \\t]*(?:#[^\\r\\n]*)?(?:\\r?\\n|$)").find(source)
        require(line != null || patchNode == null) { "暂不支持此方案的 patch 写法；原配置已保留" }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val indentation = if (map != null && map.entries.isNotEmpty()) {
            val following = source.substring(requireNotNull(line).range.last + 1)
            Regex("(?m)^([ ]+)\\S").find(following)?.groupValues?.get(1) ?: "  "
        } else "  "
        val block = "$indentation$START$newline" + lines.joinToString("") { "$indentation$it$newline" } + "$indentation$END$newline"
        val output = if (line == null) {
            val withoutEnd = source.trimEnd().removeSuffix("...").trimEnd()
            "$withoutEnd${newline}patch:$newline$block"
        } else {
            val at = line.range.last + 1
            source.substring(0, at).trimEnd('\r', '\n') + newline + block + source.substring(at)
        }
        SchemaManager.yaml.parseToYamlNode(output) // validate before any write
        return output
    }

    private fun dictionary(schema: String, custom: String): String {
        val root = SchemaManager.yaml.parseToYamlNode(schema) as? YamlMap ?: error("无法读取方案")
        val base = (root.node("translator") as? YamlMap)?.node("dictionary") as? YamlScalar
        val customRoot = custom.takeIf { text -> text.lineSequence().any {
            it.isNotBlank() && !it.trimStart().startsWith('#')
        } }?.let { SchemaManager.yaml.parseToYamlNode(it) as? YamlMap }
        val patches = customRoot?.node("patch") as? YamlMap
        val value = patches?.node("translator/dictionary") as? YamlScalar
            ?: (patches?.node("translator") as? YamlMap)?.node("dictionary") as? YamlScalar ?: base
        return requireNotNull(value) { "方案没有可扩展的拼音词典" }.content.also {
            require(it.matches(Regex("[a-zA-Z0-9_/.-]+")) && !it.contains("..")) { "无效的方案词典名" }
        }
    }

    data class Binding(val schemaId: String, val pack: String)

    private fun englishKind(schema: SchemaMeta, words: List<String>): CellDictionaryEnglish.Kind? =
        CellDictionaryEnglish.kind(schema.toSchemaInfo().profile.layout.kind)
            ?.takeIf { kind -> words.any { CellDictionaryEnglish.code(kind, it) != null } }

    /** Plan all patches before writing anything, so an unsupported custom config cannot half-apply. */
    internal fun prepare(rimeDir: File, store: CellDictionaryStore, schemas: List<SchemaMeta>, ids: Set<String>,
        includeEnglish: Boolean = false, updates: CellWordUpdates = CellWordUpdates.empty): List<Binding> {
        val state = store.read()
        require(ids.all { id -> state.installed.any { it.id == id } && store.dictionaryFile(id).isFile }) {
            "已启用的词库文件缺失，请重新导入"
        }
        val writes = linkedMapOf<File, String>()
        val bindings = mutableListOf<Binding>()
        val englishWords = if (includeEnglish) ids.sorted().flatMap { store.englishWords(it) }.distinct() else emptyList()
        val mainTable = updates.mainTable()
        // A changed version line changes the pack file, which is what makes the deployment hash ask for a rebuild.
        val version = if (mainTable != null || ids.any { !updates.dictionaries[it].isNullOrEmpty() }) "1.${updates.revision}" else "1"
        var strokeCells = false
        val strokeTable = File(rimeDir, "stroke.dict.yaml")
        for (schema in schemas) {
            val customFile = File(rimeDir, "${schema.schemaId}.custom.yaml")
            val original = customFile.takeIf { it.isFile }?.readText().orEmpty()
            val profile = schema.toSchemaInfo().profile
            if (usesStrokeCells(profile)) {
                // 笔画方案自己的配置写不进去时只是笔画用不上导入的词，不挡拼音那边应用。
                val enabled = ids.isNotEmpty() && strokeTable.isFile
                runCatching { strokePatch(original, enabled) }.onSuccess { patched ->
                    if (patched != original) writes[customFile] = patched
                    strokeCells = strokeCells || enabled
                }
                continue
            }
            val supported = supports(profile)
            // The maintainer's online words need the pack even when no cell dictionary is on.
            if ((ids.isEmpty() && mainTable == null) || !supported) {
                if (original.contains(START)) writes[customFile] = patch(original, null)
                continue
            }
            val source = File(rimeDir, "${schema.schemaId}.schema.yaml").readText()
            // An installed older cell block can collide with a later personal patch. Read
            // the user's dictionary override without the replaceable block first.
            // Only online words, no cell dictionary chosen: a schema whose own config cannot take the pack is
            // skipped instead of failing every schema (the user never asked for a pack there).
            val english = englishKind(schema, englishWords)
            val (pack, patched) = try {
                val name = packName(dictionary(source, patch(original, null)))
                name to patch(original, name, english)
            } catch (error: Exception) {
                if (ids.isNotEmpty()) throw error
                continue
            }
            bindings += Binding(schema.schemaId, pack)
            writes[customFile] = patched
            if (english != null) {
                writes[File(rimeDir, "${english.dictionary}.dict.yaml")] = CellDictionaryEnglish.dictionary(english, englishWords)
                writes[File(rimeDir, "${english.dictionary}.schema.yaml")] = CellDictionaryEnglish.schema(english)
            }
            writes[File(rimeDir, "$pack.dict.yaml")] = buildString {
                append("---\nname: $pack\nversion: \"$version\"\nsort: by_weight\nuse_preset_vocabulary: false\nimport_tables:\n")
                ids.sorted().forEach { append("  - cyime_cells/cell_$it\n") }
                if (mainTable != null) append("  - ${CellWordUpdates.MAIN_TABLE}\n")
                append("...\n")
            }
        }
        if (mainTable != null && bindings.isNotEmpty()) writes[File(rimeDir, "${CellWordUpdates.MAIN_TABLE}.dict.yaml")] = mainTable
        // An imported list can be 40 MB: copied as a file unless online words extend it (only built-ins, which are small).
        val copies = linkedMapOf<File, File>()
        ids.sorted().forEach { id ->
            val target = File(rimeDir, "cyime_cells/cell_$id.dict.yaml")
            if (updates.dictionaries[id].isNullOrEmpty()) copies[target] = store.dictionaryFile(id)
            else writes[target] = updates.extend(id, store.dictionaryFile(id).readText())
        }
        if (strokeCells)
            writes[File(rimeDir, "${StrokeCellDictionary.NAME}.schema.yaml")] = StrokeCellDictionary.schema
        writes.forEach { (file, text) -> CellDictionaryStore.writeAtomic(file, text) }
        copies.forEach { (file, source) -> CellDictionaryStore.copyAtomic(source, file) }
        val strokeDictionary = File(rimeDir, "${StrokeCellDictionary.NAME}.dict.yaml")
        if (strokeCells) {
            StrokeCellDictionary.write(strokeDictionary, strokeTable, ids.sorted().flatMap {
                listOf(File(rimeDir, "cyime_cells/cell_$it.dict.yaml"), store.strokeWordsFile(it)).filter(File::isFile)
            })
        } else {
            strokeDictionary.delete()
            File(rimeDir, "${StrokeCellDictionary.NAME}.schema.yaml").delete()
        }
        return bindings
    }
}
