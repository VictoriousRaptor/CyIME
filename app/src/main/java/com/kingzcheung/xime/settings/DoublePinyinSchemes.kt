package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 首道、自然码等内置键位表和用户自定义的双拼方案（键位表 custom-double-pinyin.json 是唯一来源）。
 * Rime 方案（26 键、九键、14 键）和中英混输词表每次初始化时由已部署的小鹤双拼方案重新生成；不删除词库和学习记录。
 */
object DoublePinyinSchemes {
    private const val TAG = "DoublePinyinSchemes"
    const val FILE = "custom-double-pinyin.json"
    private const val BASE = "double_pinyin_flypy"

    fun isGenerated(id: String) = isT9(id) || isK14(id) || isBuiltin(id) && id != BASE || isCustom(id) || isOnLayout(id)
    fun isCustom(id: String) = id.startsWith(DoublePinyinScheme.CUSTOM_PREFIX) &&
        !id.endsWith(DoublePinyinScheme.T9_SUFFIX) && !id.endsWith(DoublePinyinScheme.K14_SUFFIX) &&
        DoublePinyinScheme.splitLayoutVariant(id) == null
    private fun isBuiltin(id: String) = DoublePinyinScheme.BUILTINS.any { it.id == id }
    /** 双拼方案的九键版本（每个内置和自定义方案各一个，默认不启用）。 */
    fun isT9(id: String) = id.endsWith(DoublePinyinScheme.T9_SUFFIX) && id.removeSuffix(DoublePinyinScheme.T9_SUFFIX).let {
        isBuiltin(it) || isCustom(it)
    }
    /**
     * 双拼方案的 14 键版本（小鹤的随应用内置，其余由键位表生成）：和 14 键小鹤双拼一样是组合配置，
     * 这个双拼方案和中文14键都启用时自动提供，在 14 键上切换时保持当前双拼方案。
     */
    fun isK14(id: String) = id.endsWith(DoublePinyinScheme.K14_SUFFIX) && id.removeSuffix(DoublePinyinScheme.K14_SUFFIX).let {
        isBuiltin(it) || isCustom(it)
    }

    /**
     * 双拼方案在自建合并键布局（如自己排的 20 键）上的版本，和 14 键版本一样是组合配置：
     * 这个双拼方案和那个布局都启用时自动提供，在那个布局上切换时保持当前双拼方案。
     */
    fun isOnLayout(id: String) = DoublePinyinScheme.splitLayoutVariant(id)?.first?.let { isBuiltin(it) || isCustom(it) } == true
    /** 布局版本所在的自建布局 id；不是布局版本时为 null。 */
    fun layoutOf(id: String): String? = DoublePinyinScheme.splitLayoutVariant(id)?.takeIf { isOnLayout(id) }?.second
    /** 九键、14 键、布局版本对应的 26 键双拼方案 id；其他 id 原样返回。 */
    fun baseId(id: String): String = when {
        isT9(id) -> id.removeSuffix(DoublePinyinScheme.T9_SUFFIX)
        isK14(id) -> id.removeSuffix(DoublePinyinScheme.K14_SUFFIX)
        isOnLayout(id) -> DoublePinyinScheme.splitLayoutVariant(id)!!.first
        else -> id
    }

    @Volatile private var cached: List<DoublePinyinScheme> = emptyList()

    /** 方案卡片的示例编码用；自定义方案在读过一次键位表后可查到。 */
    fun find(id: String): DoublePinyinScheme? = if (isT9(id)) find(id.removeSuffix(DoublePinyinScheme.T9_SUFFIX))
        else if (isK14(id)) find(id.removeSuffix(DoublePinyinScheme.K14_SUFFIX))
        else if (isOnLayout(id)) find(baseId(id))
        else DoublePinyinScheme.BUILTINS.firstOrNull { it.id == id } ?: cached.firstOrNull { it.id == id }

    @Synchronized fun load(context: Context): List<DoublePinyinScheme> {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList<DoublePinyinScheme>().also { cached = it }
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
            .filter { it.valid() && isCustom(it.id) }.distinctBy { it.id }.also { cached = it }
    }

    /** 内置方案（小鹤、首道、自然码等）在前，可以复制成自己的方案。 */
    fun all(context: Context): List<DoublePinyinScheme> = DoublePinyinScheme.BUILTINS + load(context)

    private fun decode(o: JSONObject): DoublePinyinScheme {
        fun keys(name: String) = o.getJSONObject(name).let { map -> map.keys().asSequence().associateWith { map.getString(it) } }
        return DoublePinyinScheme(o.getString("id"), o.getString("name"),
            keys("initials").mapValues { it.value.single() }, keys("finals").mapValues { it.value.single() }, keys("zero"))
    }

    private fun encode(scheme: DoublePinyinScheme) = JSONObject().put("id", scheme.id).put("name", scheme.name)
        .put("initials", JSONObject(scheme.initials.mapValues { it.value.toString() }))
        .put("finals", JSONObject(scheme.finals.mapValues { it.value.toString() }))
        .put("zero", JSONObject(scheme.zero))

    private fun persist(context: Context, schemes: List<DoublePinyinScheme>) =
        writeAtomically(File(context.filesDir, FILE), JSONArray(schemes.map(::encode)).toString())

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            pending.outputStream().use { stream -> stream.write(content.toByteArray()); stream.fd.sync() }
            java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    private fun writeIfChanged(file: File, text: String) {
        if (!file.isFile || file.readText() != text) writeAtomically(file, text)
    }

    /**
     * 写出一个方案的 Rime 配置（含九键、14 键版本）；内容没变时不写，避免触发重新部署。
     * 小鹤双拼的 26 键和 14 键随应用内置，只写九键版本。
     */
    private fun writeSchema(rimeDir: File, scheme: DoublePinyinScheme, layouts: List<CustomKeyboardLayout>) {
        val base = File(rimeDir, "$BASE.schema.yaml")
        check(base.isFile) { "请先完成小鹤双拼部署" }
        writeLayoutVariants(rimeDir, scheme, base.readText(), layouts)
        if (scheme.id != BASE) {
            // 停顿纠错索引先写：方案文件里把它列为依赖，部署时得已经在。小鹤方案还没带这项（旧安装包的文件）时不写。
            if (base.readText().contains(DoublePinyinScheme.pauseId(BASE)))
                writeIfChanged(File(rimeDir, "${DoublePinyinScheme.pauseId(scheme.id)}.schema.yaml"), scheme.pauseSchema())
            writeIfChanged(File(rimeDir, "${scheme.id}.schema.yaml"), scheme.schema(base.readText()))
            val base14 = File(rimeDir, "${DoublePinyinScheme.k14Id(BASE)}.schema.yaml")
            // 14 键、九键没有分号键：ing 在分号上的方案（微软双拼）把分号并到 L 键（DoublePinyinScheme.k14Schema、t9Schema）。
            if (base14.isFile) writeIfChanged(File(rimeDir, "${DoublePinyinScheme.k14Id(scheme.id)}.schema.yaml"),
                scheme.k14Schema(base14.readText()))
            val flypyTable = File(rimeDir, "en_dicts/cn_en_flypy.txt")
            if (flypyTable.isFile) {
                val table = File(rimeDir, "en_dicts/${DoublePinyinScheme.cnEnName(scheme.id)}.txt")
                writeIfChanged(table, scheme.cnEnTable(flypyTable.readText()))
            }
        }
        writeIfChanged(File(rimeDir, "${DoublePinyinScheme.t9Id(scheme.id)}.schema.yaml"), scheme.t9Schema())
    }

    /**
     * 每个有合并键的自建布局各写一个本方案的版本，不再有合并键或已删除的布局的旧版本删掉。
     * 合并键布局不一定有分号键：ing 在分号上的方案（微软双拼）和 14 键一样把分号并到 L 键（DoublePinyinScheme.layoutSchema）。
     */
    private fun writeLayoutVariants(rimeDir: File, scheme: DoublePinyinScheme, flypyBase: String, layouts: List<CustomKeyboardLayout>) {
        val wanted = layouts.filter { it.mergedGroups.isNotEmpty() }
        val base26 by lazy { if (scheme.id == BASE) flypyBase else scheme.schema(flypyBase) }
        for (layout in wanted) {
            val id = DoublePinyinScheme.layoutVariantId(scheme.id, layout.id)
            writeIfChanged(File(rimeDir, "$id.schema.yaml"), scheme.layoutSchema(base26, id, layout.name, layout.mergedGroups))
        }
        val keep = wanted.map { it.id }.toSet()
        layoutVariantFiles(rimeDir, scheme.id).filter { (_, layoutId) -> layoutId !in keep }.forEach { (file, _) -> file.delete() }
    }

    /** rime 目录里 [schemeId] 的各布局版本文件和它们的布局 id。 */
    private fun layoutVariantFiles(rimeDir: File, schemeId: String): List<Pair<File, String>> =
        rimeDir.listFiles().orEmpty().mapNotNull { file ->
            val id = file.name.removeSuffix(".schema.yaml").takeIf { file.name.endsWith(".schema.yaml") } ?: return@mapNotNull null
            DoublePinyinScheme.splitLayoutVariant(id)?.takeIf { it.first == schemeId }?.let { file to it.second }
        }

    private fun mergedLayouts(context: Context) =
        runCatching { CustomKeyboardLayouts.load(context) }.getOrDefault(emptyList()).filter { it.mergedGroups.isNotEmpty() }

    /**
     * 旧版（1.15.94、1.15.95）每个双拼方案生成过一份「双拼纠错索引」（cyime_edit_*），现在换成了停顿纠错索引
     * （cyime_pause_*），旧文件没人删，被当成没写语言的导入方案列进「语言与方案」。只删名字对得上的那几份和它们的编译产物。
     */
    internal fun retireOldCorrectionIndexes(rimeDir: File) {
        val stale = rimeDir.listFiles { f -> f.name.startsWith(OLD_CORRECTION_PREFIX) && f.name.endsWith(".schema.yaml") }
            .orEmpty().filter { file ->
                runCatching { file.readText() }.getOrDefault("")
                    .contains("\n  name: $OLD_CORRECTION_NAME\n")
            }
        for (file in stale) {
            val id = file.name.removeSuffix(".schema.yaml")
            if (!file.delete()) { Log.w(TAG, "Cannot remove old ${file.name}"); continue }
            File(rimeDir, "build").listFiles { f -> f.name.startsWith("$id.") }.orEmpty().forEach { it.delete() }
        }
    }

    /**
     * 手动启用过的旧索引（手机 11-17：「已安装的方案」里列出、底部卡在「正在应用方案更改…」）：文件删掉后
     * 启用列表和当前方案里还留着它的 id，从中去掉。只去掉文件已不在的 cyime_edit_*。
     */
    internal fun staleOldIndexIds(ids: List<String>, rimeDir: File): Set<String> = ids.filter {
        it.startsWith(OLD_CORRECTION_PREFIX) && !File(rimeDir, "$it.schema.yaml").exists()
    }.toSet()

    private fun forgetOldCorrectionIndexes(context: Context, rimeDir: File) {
        val enabled = SchemaManager.readSavedEnabledSchemas(context) ?: return
        val stale = staleOldIndexIds(enabled, rimeDir)
        if (stale.isNotEmpty()) SchemaManager.setEnabledSchemas(context, enabled - stale)
        val current = SettingsPreferences.getCurrentSchema(context)
        if (staleOldIndexIds(listOf(current), rimeDir).isNotEmpty()) {
            val next = (enabled - stale).firstOrNull() ?: CyimeInputDefaults.recommended.first()
            SettingsPreferences.setCurrentSchema(context, next)
        }
    }

    private const val OLD_CORRECTION_PREFIX = "cyime_edit_"
    private const val OLD_CORRECTION_NAME = "双拼纠错索引"

    /** 初始化时调用：小鹤双拼方案随版本更新后，其他内置、自定义方案和各九键、14 键、布局版本跟着更新。 */
    @Synchronized fun install(context: Context, rimeDir: File) {
        retireOldCorrectionIndexes(rimeDir)
        runCatching { forgetOldCorrectionIndexes(context, rimeDir) }
            .onFailure { Log.w(TAG, "Cannot drop old correction indexes from the schema list", it) }
        if (!File(rimeDir, "$BASE.schema.yaml").isFile) return
        val layouts = mergedLayouts(context)
        for (scheme in DoublePinyinScheme.BUILTINS + runCatching { load(context) }.getOrDefault(emptyList())) {
            try { writeSchema(rimeDir, scheme, layouts) }
            catch (e: Exception) { Log.w(TAG, "Cannot generate ${scheme.id}", e) }
        }
    }

    /**
     * 自建布局保存或删除后调用（在启用方案之前）：各双拼方案在合并键布局上的版本跟着改，
     * 布局不再有合并键或已删除时删掉旧版本；正在用被删掉的版本时换回这个双拼方案的 26 键。
     */
    fun syncLayouts(context: Context) {
        val rimeDir = SchemaManager.getRimeDir(context)
        synchronized(this) {
            if (!File(rimeDir, "$BASE.schema.yaml").isFile) return
            val base = File(rimeDir, "$BASE.schema.yaml").readText()
            val layouts = mergedLayouts(context)
            for (scheme in DoublePinyinScheme.BUILTINS + runCatching { load(context) }.getOrDefault(emptyList())) {
                try { writeLayoutVariants(rimeDir, scheme, base, layouts) }
                catch (e: Exception) { Log.w(TAG, "Cannot generate layout variants of ${scheme.id}", e) }
            }
        }
        val current = SettingsPreferences.getCurrentSchema(context)
        if (isOnLayout(current) && !File(rimeDir, "$current.schema.yaml").isFile)
            SettingsPreferences.setCurrentSchema(context, baseId(current))
    }

    /** 在方案栏显示或隐藏一个双拼方案；内置的 26 键双拼默认显示（见 CyimeInputDefaults.withDoublePinyin），各九键版本默认不启用，在双拼方案页自己打开。 */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val current = SchemaManager.getEnabledSchemas(context)
        val updated = if (enabled) {
            val after = current.indexOf(if (isT9(id)) "t9_pinyin" else BASE)
            if (id in current) current else if (after < 0) current + id
            else current.take(after + 1) + id + current.drop(after + 1)
        } else current.filterNot { it == id }.ifEmpty { listOf("rime_ice") }
        if (updated == current) return
        // 隐藏方案时它的 14 键版本跟着隐藏，正在用 14 键版本也要换掉。
        if (!enabled && SettingsPreferences.getCurrentSchema(context).let { it == id || it == DoublePinyinScheme.k14Id(id) ||
                isOnLayout(it) && baseId(it) == id })
            SettingsPreferences.setCurrentSchema(context, updated.firstOrNull { InputProfiles.describe(it).language == InputLanguage.CHINESE } ?: updated.first())
        SchemaManager.setEnabledSchemas(context, updated)
        // 和「已安装的方案」的开关同一条路自动应用（11-15）：以前只通知输入法，键盘没开着就一直等着用户去点部署。
        com.kingzcheung.xime.rime.SchemaChangeApplier.request(context)
    }

    fun save(context: Context, scheme: DoublePinyinScheme) {
        require(scheme.valid() && isCustom(scheme.id)) { scheme.problems().firstOrNull() ?: "方案无效" }
        synchronized(this) {
            val old = load(context)
            require((DoublePinyinScheme.BUILTINS + old).none { it.id != scheme.id && it.name == scheme.name.trim() }) { "已有同名方案" }
            val updated = if (old.any { it.id == scheme.id }) old.map { if (it.id == scheme.id) scheme else it } else old + scheme
            val rimeDir = SchemaManager.getRimeDir(context)
            val schemaFile = File(rimeDir, "${scheme.id}.schema.yaml")
            val previous = schemaFile.takeIf { it.isFile }?.readText()
            try {
                writeSchema(rimeDir, scheme, mergedLayouts(context))
                persist(context, updated)
                cached = updated
            } catch (e: Exception) {
                if (previous == null) schemaFile.delete() else writeAtomically(schemaFile, previous)
                throw e
            }
        }
        SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) + scheme.id).distinct())
        notifyChanged(context)
    }

    fun delete(context: Context, id: String) {
        require(isCustom(id))
        synchronized(this) {
            val old = load(context)
            require(old.any { it.id == id })
            persist(context, old.filterNot { it.id == id })
            cached = old.filterNot { it.id == id }
            val rimeDir = SchemaManager.getRimeDir(context)
            // 只删本方案的配置和混输词表；词库、用户词和学习记录都保留。
            File(rimeDir, "$id.schema.yaml").delete()
            File(rimeDir, "${DoublePinyinScheme.t9Id(id)}.schema.yaml").delete()
            File(rimeDir, "${DoublePinyinScheme.k14Id(id)}.schema.yaml").delete()
            File(rimeDir, "${DoublePinyinScheme.pauseId(id)}.schema.yaml").delete()
            File(rimeDir, "en_dicts/${DoublePinyinScheme.cnEnName(id)}.txt").delete()
            layoutVariantFiles(rimeDir, id).forEach { (file, _) -> file.delete() }
        }
        val ids = setOf(id, DoublePinyinScheme.t9Id(id), DoublePinyinScheme.k14Id(id))
        val fallback = if (File(SchemaManager.getRimeDir(context), "$BASE.schema.yaml").isFile) BASE else "rime_ice"
        fun removed(schemaId: String) = schemaId in ids || isOnLayout(schemaId) && baseId(schemaId) == id
        if (removed(SettingsPreferences.getCurrentSchema(context))) SettingsPreferences.setCurrentSchema(context, fallback)
        SchemaManager.setEnabledSchemas(context, SchemaManager.getEnabledSchemas(context).filterNot(::removed)
            .ifEmpty { listOf(fallback) })
        notifyChanged(context)
    }

    /** 与自定义布局共用一次“应用方案更改”：输入法收到后重新部署。 */
    private fun notifyChanged(context: Context) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(CustomKeyboardLayouts.STATUS, "等待应用双拼方案")
            .putLong(CustomKeyboardLayouts.REVISION, System.currentTimeMillis()).apply()
    }
}
