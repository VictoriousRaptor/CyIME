package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import com.kingzcheung.xime.settings.MarketVersionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

object ModelManager {

    private const val TAG = "ModelManager"
    private val downloads = ModelDownloadQueue(kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO))
    private val _installedRevision = MutableStateFlow(0L)
    val installedRevision: StateFlow<Long> = _installedRevision
    fun notifyInstalledModelsChanged() { _installedRevision.update { it + 1 } }

    internal suspend fun installBundled(context: Context, entry: org.json.JSONObject) {
        val id = entry.getString("id")
        val model = requireNotNull(getModel(id))
        val target = model.versions.first { it.version == entry.getString("version") }
        val guard = installGuards.getOrPut(id) { ModelInstallGuard() }
        val generation = guard.currentGeneration()
        downloadLocks.getOrPut(id) { Mutex() }.withLock {
            // 已装着同一版或更新的版本就不动；装着旧版时换成安装包里的新版（自动更新从安装包取回也走这里）。
            ModelStorage.migrateLegacyForModel(context, id)
            val installed = installedVersionOf(context, model)
            if (installed != null && !ModelAutoUpdater.isNewer(model, target, installed)) return@withLock
            val root = ModelStorage.getModelsRoot(context).apply { mkdirs() }
            val staging = File(root, ".bundle-$id-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            _downloadStates.update { it + (id to ModelDownloadState.Downloading(0f, 0, -1)) }
            try {
                val files = entry.getJSONArray("files")
                check((0 until files.length()).map { files.getJSONObject(it).getString("name") }.toSet() == target.files.map { it.name }.toSet())
                for (i in 0 until files.length()) {
                    val info = files.getJSONObject(i)
                    val name = info.getString("name")
                    val file = modelDownloadFile(staging, name)
                    file.parentFile?.mkdirs()
                    context.assets.open("bundled-models/$id/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                    check(file.length() == info.getLong("size")) { "内置模型文件不完整：$name" }
                    verifyModelDigest(file, info.getString("sha256"))
                }
                validateDownloadedModel(staging, target)
                check(guard.install(generation, staging, ModelStorage.getModelDir(context, id)) {
                    MarketVersionStore.setModelVersion(context, id, target.version)
                }) { "内置模型安装已取消：$id" }
                _downloadStates.update { it + (id to ModelDownloadState.Complete) }
                notifyInstalledModelsChanged()
            } catch (error: Exception) {
                _downloadStates.update { it + (id to ModelDownloadState.Error(error.message ?: "内置模型准备失败")) }
                throw error
            } finally { if (staging.exists()) staging.deleteRecursively() }
        }
    }

    /**
     * 安装用户从本机提供的模型文件：[fill] 把文件放进暂存目录，之后与网络下载走同一套
     * 校验（文件清单、大小、sha256）和替换流程，校验不过不会覆盖已安装的模型。
     */
    internal suspend fun installLocal(context: Context, model: ModelInfo, version: ModelVersion, fill: (File) -> Unit) {
        val guard = installGuards.getOrPut(model.id) { ModelInstallGuard() }
        val generation = guard.currentGeneration()
        downloadLocks.getOrPut(model.id) { Mutex() }.withLock {
            val root = ModelStorage.getModelsRoot(context).apply { mkdirs() }
            val staging = File(root, ".import-${model.id}-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            try {
                fill(staging)
                validateDownloadedModel(staging, version)
                check(guard.install(generation, staging, ModelStorage.getModelDir(context, model.id)) {
                    if (model.category == ModelCategory.CANDIDATE) CandidateModelAssets.installDownloaded(context, model, version)
                    MarketVersionStore.setModelVersion(context, model.id, version.version)
                }) { "模型导入已取消：${model.id}" }
                _downloadStates.update { it + (model.id to ModelDownloadState.Complete) }
                notifyInstalledModelsChanged()
            } finally { if (staging.exists()) staging.deleteRecursively() }
        }
    }

    fun isModelReady(context: Context, id: String): Boolean {
        ModelStorage.migrateLegacyForModel(context, id)
        return isModelDownloaded(context, id)
    }

    private var initialized = false
    private val _modelsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ModelInfo>>(BuiltinModelCatalog.models)
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val installGuards = ConcurrentHashMap<String, ModelInstallGuard>()
    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates

    /** 随应用发布、离线可用的模型清单。 */
    val modelsFlow: kotlinx.coroutines.flow.StateFlow<List<ModelInfo>> = _modelsFlow

    fun initialize() {
        if (initialized) return
        initialized = true
        FileLogger.i(TAG, "ModelManager initialized")
    }

    /** 不可变快照，避免下载页刷新期间修改正在遍历的清单。 */
    private fun allModels(): List<ModelInfo> = _modelsFlow.value

    fun getAllModels(): List<ModelInfo> = allModels()

    fun getModel(id: String): ModelInfo? = allModels().find { it.id == id }

    fun getModelsByCategory(category: ModelCategory): List<ModelInfo> =
        allModels().filter { it.category == category }

    // Kept for existing callers; the built-in catalog is available offline from process start.
    suspend fun loadFromRemote(context: Context) {
        _modelsFlow.value = BuiltinModelCatalog.models
    }


    fun isModelDownloaded(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return isModelDownloaded(context, model)
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        // 检测前先尝试迁移旧路径模型到统一目录，保证已下载的旧版可用
        ModelStorage.migrateLegacyForModel(context, model.id)
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        return installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id)) != null
    }

    /** 实际装着的版本（按文件内容认，老版本留下的文件也能认出），未安装为 null。 */
    fun installedVersionOf(context: Context, model: ModelInfo): ModelVersion? {
        val dir = getModelStorageDir(context, model)?.takeIf { it.exists() } ?: return null
        return installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id))
    }

    fun getModelStorageDir(context: Context, model: ModelInfo): File? {
        // A downloaded candidate cache is not installed until Rime can actually read it.
        if (model.category == ModelCategory.CANDIDATE) return CandidateModelAssets.existingDirectory(context, model)
        return ModelStorage.getModelDir(context, model.id)
    }

    fun getModelStorageDir(context: Context, id: String): File? {
        val model = getModel(id) ?: return null
        return getModelStorageDir(context, model)
    }

    fun getModelSizeOnDisk(context: Context, id: String): Long {
        val model = getModel(id) ?: return 0
        val dir = getModelStorageDir(context, model) ?: return 0
        if (!dir.exists()) return 0

        val version = installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id)) ?: return 0
        return version.files.sumOf { fileInfo ->
            val file = findModelFile(dir, version, fileInfo.name)
            if (file.exists()) file.length() else 0
        }
    }

    fun getDownloadedModels(context: Context): List<ModelInfo> {
        return allModels().filter { isModelDownloaded(context, it) }
    }

    suspend fun downloadModel(
        context: Context,
        id: String,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val model = getModel(id)
        if (model == null) {
            onProgress(ModelDownloadState.Error("未知模型: $id"))
            return
        }
        downloadModel(context, model, onProgress)
    }

    fun downloadModelInBackground(context: Context, model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit = {}, version: ModelVersion? = null,
        onlyIfDefaultPending: Boolean = false): kotlinx.coroutines.Deferred<Unit> {
        val app = context.applicationContext
        val key = "${model.id}:${(version ?: model.resolvedVersion())?.version.orEmpty()}"
        return downloads.start(key) {
            if (!installFromPackage(app, model, onProgress, version, onlyIfDefaultPending))
                performDownload(app, model, onProgress, version, onlyIfDefaultPending)
        }
    }

    /**
     * 完整版安装包里带着这个模型（设置向导或用户删掉了手机上的那份）：直接从安装包取回，不联网。
     * 包里没有、或取回失败时返回 false，照常下载。
     */
    private suspend fun installFromPackage(context: Context, model: ModelInfo, onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion?, onlyIfDefaultPending: Boolean): Boolean {
        val target = version ?: model.resolvedVersion() ?: return false
        val source = BundledModelInstaller.packagedSource(context, model, target) ?: return false
        if (onlyIfDefaultPending && !DefaultModelInstaller.isPendingDefault(context, model.id)) {
            onProgress(ModelDownloadState.Idle)
            return true
        }
        return try {
            onProgress(ModelDownloadState.Downloading(0f, 0, -1))
            source()
            _downloadStates.update { it + (model.id to ModelDownloadState.Complete) }
            onProgress(ModelDownloadState.Complete)
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            FileLogger.w(TAG, "从安装包取回 ${model.id} 失败，改为下载：${error.message}")
            false
        }
    }

    suspend fun downloadModel(context: Context, model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit, version: ModelVersion? = null,
        onlyIfDefaultPending: Boolean = false) {
        downloadModelInBackground(context, model, onProgress, version, onlyIfDefaultPending).await()
    }

    private suspend fun performDownload(
        context: Context,
        model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion?,
        onlyIfDefaultPending: Boolean,
    ) {
        val guard = installGuards.getOrPut(model.id) { ModelInstallGuard() }
        val generation = guard.currentGeneration()
        val completionBeforeRequest = guard.completionRevision
        // 自动准备与市场点击同一模型时串行；仅合并排队期间已完成的同版本下载。
        downloadLocks.getOrPut(model.id) { Mutex() }.withLock {
            if (onlyIfDefaultPending && !DefaultModelInstaller.isPendingDefault(context, model.id)) {
                onProgress(ModelDownloadState.Idle)
                return@withLock
            }
            val target = version ?: model.resolvedVersion()
            val installed = MarketVersionStore.getModelVersion(context, model.id)
            if (guard.completionRevision != completionBeforeRequest && target != null &&
                installed == target.version && hasDownloadedModelFiles(ModelStorage.getModelDir(context, model.id), target) &&
                (model.category != ModelCategory.CANDIDATE || isModelDownloaded(context, model))) {
                _downloadStates.update { it + (model.id to ModelDownloadState.Complete) }
                onProgress(ModelDownloadState.Complete)
                return@withLock
            }
            val starting = ModelDownloadState.Downloading(0f, 0, -1)
            _downloadStates.update { it + (model.id to starting) }
            onProgress(starting)
            try {
                if (model.category == ModelCategory.CANDIDATE && target != null &&
                    runCatching { validateDownloadedModel(ModelStorage.getModelDir(context, model.id), target) }.isSuccess) {
                    // A previous runtime conflict must not force another large network download.
                    val applied = guard.applyExisting(generation,
                        isStillRequested = { !onlyIfDefaultPending || DefaultModelInstaller.isPendingDefault(context, model.id) }) {
                        CandidateModelAssets.installDownloaded(context, model, target)
                        MarketVersionStore.setModelVersion(context, model.id, target.version)
                    }
                    val state = if (applied) ModelDownloadState.Complete else ModelDownloadState.Idle
                    _downloadStates.update { it + (model.id to state) }
                    if (applied) notifyInstalledModelsChanged()
                    onProgress(state)
                    return@withLock
                }
                ModelDownloader.downloadModel(context, model, { state ->
                    _downloadStates.update { it + (model.id to state) }
                    if (state is ModelDownloadState.Complete) {
                        // 日语词库下好马上解开、引擎设为只读，不等下次 Rime 准备。
                        com.kingzcheung.xime.settings.JapanesePack.afterInstall(context, model.id)
                        notifyInstalledModelsChanged()
                    }
                    onProgress(state)
                }, version, install = { staging, destination ->
                    guard.install(generation, staging, destination,
                        isStillRequested = { !onlyIfDefaultPending || DefaultModelInstaller.isPendingDefault(context, model.id) }) {
                        if (model.category == ModelCategory.CANDIDATE && target != null) {
                            CandidateModelAssets.installDownloaded(context, model, target)
                        }
                        if (target != null) MarketVersionStore.setModelVersion(context, model.id, target.version)
                    }
                })
            } catch (cancelled: CancellationException) {
                _downloadStates.update { states ->
                    if (states[model.id] is ModelDownloadState.Downloading)
                        states + (model.id to ModelDownloadState.Idle) else states
                }
                throw cancelled
            } catch (error: Exception) {
                val state = ModelDownloadState.Error(modelFailureText(error))
                _downloadStates.update { it + (model.id to state) }
                onProgress(state)
            }
        }
    }

    /** 只删 models/ 里的下载副本，不碰 rime/ 里正在用的同名文件；用于被另一档替代的模型。 */
    internal fun deleteStoredDownload(context: Context, model: ModelInfo): Boolean {
        val guard = installGuards.getOrPut(model.id) { ModelInstallGuard() }
        return guard.delete {
            val dir = ModelStorage.getModelDir(context, model.id)
            val success = !dir.exists() || dir.deleteRecursively()
            if (success) {
                MarketVersionStore.removeModelVersion(context, model.id)
                notifyInstalledModelsChanged()
                _downloadStates.update { it + (model.id to ModelDownloadState.Idle) }
            }
            success
        }
    }

    fun deleteModel(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return deleteModel(context, model)
    }

    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val guard = installGuards.getOrPut(model.id) { ModelInstallGuard() }
        return guard.delete {
            if (model.category == ModelCategory.CANDIDATE && !CandidateModelAssets.removeRuntimeCopy(context, model)) return@delete false
            val dir = if (model.category == ModelCategory.CANDIDATE) ModelStorage.getModelDir(context, model.id)
                else getModelStorageDir(context, model) ?: return@delete false
            val version = installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id))
                ?: model.resolvedVersion()
            var success = true
            for (fileInfo in version?.files.orEmpty()) {
                val file = findModelFile(dir, version!!, fileInfo.name)
                if (file.exists() && !file.delete()) success = false
            }
            if (success) {
                if (model.category == ModelCategory.ASR) com.kingzcheung.xime.speech.OptimizedEncoder.forget(context, model.id)
                MarketVersionStore.removeModelVersion(context, model.id)
                DefaultModelInstaller.markHandled(context, model.id)
                notifyInstalledModelsChanged()
                _downloadStates.update { it + (model.id to ModelDownloadState.Idle) }
            }
            success
        }
    }
}
