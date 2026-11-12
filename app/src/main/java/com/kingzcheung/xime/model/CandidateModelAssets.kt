package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bridge downloaded resources to Rime's resolver without moving existing user resources. */
internal object CandidateModelAssets {
    private fun disabledKey(id: String) = "candidate_model_disabled_$id"
    private val verifiedRuntime = ConcurrentHashMap<String, List<String>>()

    fun setDisabled(context: Context, id: String, disabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(disabledKey(id), disabled).commit()
    }

    fun isDisabledAsset(context: Context, name: String): Boolean = CandidateModelCatalog.models.any { model ->
        model.files.any { it.name == name } &&
            SettingsPreferences.getPrefsPublic(context).getBoolean(disabledKey(model.id), false)
    }

    fun filterBundledManifest(context: Context, manifest: String): String = manifest.lineSequence()
        .filterNot { line ->
            val name = line.split('\t').getOrNull(3).orEmpty()
            isDisabledAsset(context, name) || hasEnabledDownload(context, name)
        }
        .joinToString("\n")

    /** 用户下载过同名的候选模型（如九键词组模型）时，安装包自带的那份不再同步回 rime/，免得把用户的选择换回去。 */
    private fun hasEnabledDownload(context: Context, name: String): Boolean = CandidateModelCatalog.models.any { model ->
        model.versions.any { version -> version.files.any { it.name == name } } &&
            !SettingsPreferences.getPrefsPublic(context).getBoolean(disabledKey(model.id), false) &&
            model.versions.any { hasDownloadedModelFiles(ModelStorage.getModelDir(context, model.id), it) }
    }

    /** 安装包自带、由 BundledRimeSync 放进 rime/ 的同名文件同样是 CyIME 自己的，用户下载新的一档时可以换掉。 */
    private fun isBundledCopy(context: Context, name: String, file: File): Boolean {
        val manifest = runCatching {
            context.assets.open("rime-bundled-manifest.tsv").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false
        return isBundledCopy(manifest, name, file)
    }

    fun existingDirectory(context: Context, model: ModelInfo): File? {
        if (model.category != ModelCategory.CANDIDATE) return null
        if (SettingsPreferences.getPrefsPublic(context).getBoolean(disabledKey(model.id), false)) return null
        val runtime = File(context.filesDir, "rime")
        return runtime.takeIf {
            model.versions.any { version ->
                if (!hasDownloadedModelFiles(runtime, version)) return@any false
                val key = "${runtime.absolutePath}/${model.id}/${version.version}"
                val signature = version.files.map { info ->
                    val file = modelDownloadFile(runtime, info.name)
                    "${info.name}:${info.sha256}:${file.length()}:${file.lastModified()}"
                }
                if (verifiedRuntime[key] == signature) return@any true
                runCatching { validateDownloadedModel(runtime, version) }.isSuccess.also { valid ->
                    if (valid) verifiedRuntime[key] = signature
                }
            }
        }
    }

    fun installAvailable(context: Context, models: List<ModelInfo> = CandidateModelCatalog.models) {
        for (model in models) {
            if (SettingsPreferences.getPrefsPublic(context).getBoolean(disabledKey(model.id), false)) continue
            val source = ModelStorage.getModelDir(context, model.id)
            val version = model.versions.firstOrNull { hasDownloadedModelFiles(source, it) } ?: continue
            // A user-owned conflicting resource must never prevent ordinary keyboard startup.
            runCatching { installCandidateModel(source, File(context.filesDir, "rime"), version) }
                .onFailure { com.kingzcheung.xime.util.FileLogger.w("CandidateModels", "候选增强未应用：${it.message}") }
        }
    }

    fun installDownloaded(context: Context, model: ModelInfo, version: ModelVersion) {
        // 用户主动安装时，同名文件若是目录里的已知内容（旧格式、或二选一的另一档）、安装包自带的那份，
        // 或以前的安装包带过的那份，属于 CyIME 自己的文件，直接替换。
        installCandidateModel(ModelStorage.getModelDir(context, model.id), File(context.filesDir, "rime"), version) { name, file ->
            val owner = knownCatalogOwner(name, file)
            if (owner != null && owner.id != model.id) setDisabled(context, owner.id, true)
            owner != null || isBundledCopy(context, name, file) || isEarlierBundledCopy(name, file)
        }
        setDisabled(context, model.id, false)
        if (model.id == CandidateModelCatalog.NGRAM || model.id == CandidateModelCatalog.NGRAM_SMALL) {
            dropSupersededGrammar(context)
            dropUnusedNgramVariant(context)
        }
    }

    /** 启动时清掉被替代或已停用模型留下的文件，免得空间只增不减。 */
    fun dropSupersededDownloads(context: Context) {
        // 清理失败只少腾空间，不能挡住输入法启动。
        val steps = listOf<(Context) -> Unit>({ dropSupersededGrammar(it) }, { dropUnusedNgramVariant(it) }, { dropRetiredModels(it) })
        for (step in steps) {
            runCatching { step(context) }
                .onFailure { com.kingzcheung.xime.util.FileLogger.w("CandidateModels", "清理旧模型失败：${it.message}") }
        }
    }

    /**
     * 九键词组模型完整版和精简版二选一：rime/ 里的 t9_ngram.lm 是哪一档的硬链接，另一档留在 models/
     * 的下载（100~200 MB）就不再起作用，删掉。不是硬链接（拷贝安装）时无法确认归属，保持原样。
     */
    fun dropUnusedNgramVariant(context: Context) {
        val variants = CandidateModelCatalog.models
            .filter { it.id == CandidateModelCatalog.NGRAM || it.id == CandidateModelCatalog.NGRAM_SMALL }
        val runtime = File(context.filesDir, "rime")
        val active = activeVariant(runtime, variants.map { it to ModelStorage.getModelDir(context, it.id) })
            ?.takeUnless { SettingsPreferences.getPrefsPublic(context).getBoolean(disabledKey(it.id), false) } ?: return
        for (other in variants) {
            if (other.id == active.id || !ModelStorage.getModelDir(context, other.id).exists()) continue
            if (!ModelManager.deleteStoredDownload(context, other))
                com.kingzcheung.xime.util.FileLogger.w("CandidateModels", "${other.id} 已被 ${active.id} 替代，但未能删除")
        }
    }

    /** 整个条目都已停用（如九键长句增强）：已下载的文件不再参与排序，删掉；自定义同名文件保留。 */
    private fun dropRetiredModels(context: Context) {
        for (model in CandidateModelCatalog.models) {
            if (model.versions.isEmpty() || !model.versions.all { it.retired }) continue
            // 语法增强在没有九键词组模型时仍参与九键排序，只在装了词组模型后删（dropSupersededGrammar）。
            if (model.id == CandidateModelCatalog.GRAMMAR) continue
            val names = model.versions.flatMap { it.files }.map { it.name }.distinct()
            val present = names.any { name ->
                modelDownloadFile(File(context.filesDir, "rime"), name).exists() ||
                    modelDownloadFile(ModelStorage.getModelDir(context, model.id), name).exists()
            }
            // deleteModel 只删认得的完整版本，下载到一半的残余再由 deleteStoredDownload 整个目录清掉。
            if (present && !(ModelManager.deleteModel(context, model) && ModelManager.deleteStoredDownload(context, model)))
                com.kingzcheung.xime.util.FileLogger.w("CandidateModels", "已停用的 ${model.id} 未能删除")
        }
    }

    /**
     * 装了九键词组模型后，解码器用词组模型打分、不再加载语法增强，390 MB 白占空间：自动删掉。
     * 需要时可在商店重新下载（删掉词组模型后它会重新起作用）。
     */
    fun dropSupersededGrammar(context: Context) {
        val runtime = File(context.filesDir, "rime")
        val ngramInstalled = CandidateModelCatalog.models
            .filter { it.id == CandidateModelCatalog.NGRAM || it.id == CandidateModelCatalog.NGRAM_SMALL }
            .any { model -> model.versions.any { hasDownloadedModelFiles(runtime, it) } }
        if (!ngramInstalled) return
        val grammar = CandidateModelCatalog.models.firstOrNull { it.id == CandidateModelCatalog.GRAMMAR } ?: return
        val modelDir = ModelStorage.getModelDir(context, grammar.id)
        val names = grammar.versions.flatMap { it.files }.map { it.name }.distinct()
        if (names.none { File(runtime, it).isFile || File(modelDir, it).isFile }) return
        // 用户从文件导入的同版万象模型也会匹配目录哈希，不能据此判定它属于九键。
        if (names.any { name ->
                val file = File(runtime, name)
                file.isFile && !File(modelDir, name).isFile && !isBundledCopy(context, name, file)
            }) return
        val manifest = File(runtime, ".cyime-bundled-manifest").takeIf { it.isFile }?.readText().orEmpty()
        if (hasExternalGrammarConsumer(runtime, manifest, names)) return
        if (!ModelManager.deleteModel(context, grammar))
            com.kingzcheung.xime.util.FileLogger.w("CandidateModels", "语法增强已被词组模型取代，但未能删除")
    }

    private fun matchesAnyVersion(model: ModelInfo, name: String, file: File): Boolean = model.versions.any { version ->
        version.files.any { info -> info.name == name && info.sizeBytes == file.length() &&
            runCatching { verifyModelDigest(file, info.sha256) }.isSuccess }
    }

    private fun knownCatalogOwner(name: String, file: File): ModelInfo? =
        CandidateModelCatalog.models.firstOrNull { matchesAnyVersion(it, name, file) }

    /** Explicit deletion only removes known catalog content, never a custom same-name file. */
    fun removeRuntimeCopy(context: Context, model: ModelInfo): Boolean {
        val root = File(context.filesDir, "rime")
        // 任一已知版本（含旧格式）都算本条目的内容，才能删掉老版本留下的文件。
        val files = model.versions.flatMap { it.files }.map { it.name }.distinct().map { it to modelDownloadFile(root, it) }
        if (files.any { (name, file) -> file.exists() && !matchesAnyVersion(model, name, file) }) return false
        setDisabled(context, model.id, true)
        return files.all { (_, file) -> !file.exists() || file.delete() }
    }
}

/** 自定义或外部方案仍引用同一个模型时，保留文件，即使它原本由模型管理页下载。 */
internal fun hasExternalGrammarConsumer(runtime: File, bundledManifest: String, grammarFiles: List<String>): Boolean {
    val names = grammarFiles.map { it.removeSuffix(".gram") }
    return runtime.listFiles()?.any { source ->
        if (!source.name.endsWith(".schema.yaml") && !source.name.endsWith(".custom.yaml")) return@any false
        if (isBundledCopy(bundledManifest, source.name, source)) return@any false
        // 同时检查编译后的方案，涵盖通过 __include 间接引用的模型。
        listOf(source, File(runtime, "build/${source.name}")).any { file ->
            file.isFile && file.useLines { lines -> lines.any { line -> names.any { it in line } } }
        }
    } ?: true
}

/**
 * 以前的公开安装包带过、模型目录里又没有的文件（哈希 to 大小）。安装包换掉或不再带它以后，它留在 rime/ 里继续用
 * （BundledRimeSync 不删包里去掉的文件），但新安装包的清单里已经没有它，会被当成用户自己的同名文件，
 * 下载目录里的九键词组模型时装不上。1.15.73 完整版带的 t9_ngram.lm（110 MB）就是这样。
 * 以后公开包带的九键模型不在模型目录里时，把它加到这里。
 */
internal val EARLIER_BUNDLED_FILES: Map<String, List<Pair<String, Long>>> = mapOf(
    "t9_ngram.lm" to listOf("eaa084e142bdeed0b82fb15969992d7a726a6631f24cc594c81d0b773c91a5da" to 109685746L),
)

/** [file] 是以前的安装包带过的那份（大小和哈希都一致），不是用户自己的文件。 */
internal fun isEarlierBundledCopy(
    name: String, file: File, known: Map<String, List<Pair<String, Long>>> = EARLIER_BUNDLED_FILES,
): Boolean = file.isFile && known[name].orEmpty().any { (sha256, size) ->
    file.length() == size && runCatching { verifyModelDigest(file, sha256) }.isSuccess
}

/** [file] 与安装包清单里同名条目的大小和哈希一致。 */
internal fun isBundledCopy(manifest: String, name: String, file: File): Boolean {
    val entry = manifest.lineSequence().map { it.split('\t') }.firstOrNull { it.size == 4 && it[3] == name } ?: return false
    return file.isFile && file.length() == entry[1].toLongOrNull() &&
        runCatching { verifyModelDigest(file, entry[0]) }.isSuccess
}

/** rime/ 里的词组模型和哪一档的下载是同一个文件（硬链接）；都不是时返回 null。 */
internal fun activeVariant(runtime: File, variants: List<Pair<ModelInfo, File>>): ModelInfo? {
    val name = variants.firstOrNull()?.first?.files?.firstOrNull()?.name ?: return null
    val installed = modelDownloadFile(runtime, name).takeIf { it.isFile } ?: return null
    return variants.firstOrNull { (_, dir) ->
        val stored = modelDownloadFile(dir, name)
        stored.isFile && runCatching { Files.isSameFile(stored.toPath(), installed.toPath()) }.getOrDefault(false)
    }?.first
}

/**
 * [existing] 已校验与 [source] 内容相同：不是同一个文件时，原子地换成 [source] 的硬链接。
 * 换不了（存储不支持硬链接等）就保持原样，内容本来就对，只是少省一份空间。
 */
internal fun relinkSameContent(source: File, existing: File) {
    if (runCatching { Files.isSameFile(source.toPath(), existing.toPath()) }.getOrDefault(true)) return
    val temporary = File(existing.parentFile, ".${existing.name}.${UUID.randomUUID()}.installing")
    try {
        Files.createLink(temporary.toPath(), source.toPath())
        Files.move(temporary.toPath(), existing.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
    } finally { temporary.delete() }
}

/** Validate the complete source before publishing any file; preserve unknown destination content. */
internal fun installCandidateModel(
    source: File, runtime: File, version: ModelVersion,
    /** 已有同名但内容不同的文件可否替换；默认保留（启动时自动应用不替换任何文件）。 */
    canReplace: (name: String, existing: File) -> Boolean = { _, _ -> false },
): Boolean {
    // Installed hardlinks share exactly the already-verified downloaded bytes, without rehashing
    // hundreds of megabytes every time the settings or IME prepares existing resources.
    if (hasDownloadedModelFiles(source, version) && version.files.all { info ->
            runCatching { Files.isSameFile(modelDownloadFile(source, info.name).toPath(),
                modelDownloadFile(runtime, info.name).toPath()) }.getOrDefault(false)
        }) return false
    validateDownloadedModel(source, version)
    val replaced = mutableSetOf<String>()
    val missing = version.files.filter { info ->
        val existing = modelDownloadFile(runtime, info.name)
        if (existing.exists()) {
            // 内容一样却是另一份拷贝（安装包带的那份、或从别处拷来的）：换成指向下载的硬链接，
            // 不然同一个模型白占两份空间（九键词组模型约 200 MB），而且每次启动都要把两份各算一遍哈希。
            try { verifyModelDigest(existing, info.sha256); relinkSameContent(findModelFile(source, version, info.name), existing); false }
            catch (error: IOException) {
                if (!canReplace(info.name, existing))
                    throw IOException("保留已有不同版本的 ${info.name}，未覆盖；请先移走该文件后重试", error)
                replaced += info.name
                true
            }
        } else true
    }
    if (missing.isEmpty()) return false
    runtime.mkdirs()
    val pending = mutableListOf<Pair<File, File>>()
    try {
        // Publish the vocabulary before ONNX so an active resolver never sees a model without it.
        for (info in missing.sortedBy { it.name.endsWith(".onnx") }) {
            val input = findModelFile(source, version, info.name)
            val destination = modelDownloadFile(runtime, info.name)
            val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.installing")
            pending += temporary to destination
            try { Files.createLink(temporary.toPath(), input.toPath()) }
            catch (_: Exception) { input.copyTo(temporary, overwrite = true) }
            verifyModelDigest(temporary, info.sha256)
        }
        for ((temporary, destination) in pending) {
            // 新文件已全部校验通过，才移走要替换的旧文件。
            if (destination.name in replaced && destination.exists() && !destination.delete())
                throw IOException("无法替换旧的 ${destination.name}")
            // A link publishes atomically without replacing a destination created after preflight.
            try { Files.createLink(destination.toPath(), temporary.toPath()) }
            catch (exists: FileAlreadyExistsException) { throw exists }
            catch (_: UnsupportedOperationException) { Files.move(temporary.toPath(), destination.toPath()) }
            catch (error: IOException) {
                if (destination.exists()) throw error
                // No REPLACE_EXISTING / ATOMIC_MOVE: preserve competing files on linkless storage.
                Files.move(temporary.toPath(), destination.toPath())
            }
        }
        return true
    } finally { pending.forEach { it.first.delete() } }
}
