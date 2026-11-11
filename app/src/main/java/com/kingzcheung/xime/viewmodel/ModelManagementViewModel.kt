package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.model.DownloadMirrors
import com.kingzcheung.xime.model.DownloadRegion
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelImporter
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelVersion
import com.kingzcheung.xime.settings.MarketVersionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelItemState(
    val model: ModelInfo,
    val isDownloaded: Boolean = false,
    val diskSize: Long = 0,
    val downloadState: ModelDownloadState = ModelDownloadState.Idle,
    /** 本地已下载的模型版本（无则未下载或版本未知） */
    val installedVersion: String? = null,
) {
    /** 是否已有更新：已下载且本地版本 != 索引默认版本。 */
    val hasUpdate: Boolean
        get() {
            if (!isDownloaded || installedVersion == null) return false
            val latest = model.resolvedVersion()?.version ?: return false
            return installedVersion != latest
        }
}

data class ModelManagementUiState(
    val models: List<ModelItemState> = emptyList(),
    val isLoading: Boolean = true,
    val toastMessage: String? = null,
    /** 当前选中的模型 id → 版本字符串（空表示默认版本） */
    val selectedVersions: Map<String, String> = emptyMap(),
    /** 分类过滤：null 表示全部 */
    val selectedCategory: ModelCategory? = null,
    /** 正在从本机导入模型文件。 */
    val isImporting: Boolean = false,
    /** 模型下载线路偏好。 */
    val downloadRegion: DownloadRegion = DownloadRegion.AUTO,
    /** 自动模式下的判断结果：是否按中国大陆线路下载。 */
    val detectedChina: Boolean = false,
) {
    /** 商店里按分类过滤后的模型列表；已全部退役的条目不再提供下载（已下载的仍在模型管理里可删）。 */
    val filteredModels: List<ModelItemState>
        get() = models.filter { it.model.downloadableVersions.isNotEmpty() &&
            (selectedCategory == null || it.model.category == selectedCategory) }
}

class ModelManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(ModelManagementUiState(
        downloadRegion = DownloadMirrors.region(context),
        detectedChina = DownloadMirrors.detectChina(context),
    ))
    val uiState: StateFlow<ModelManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ModelManager.modelsFlow.drop(1).collectLatest { loadModels() }
        }
        viewModelScope.launch {
            var completed = emptySet<String>()
            ModelManager.downloadStates.collectLatest { states ->
                _uiState.update { current ->
                    current.copy(models = current.models.map { item ->
                        val state = states[item.model.id]
                        if (state == null) item else item.copy(downloadState =
                            if (state is ModelDownloadState.Complete) ModelDownloadState.Idle else state)
                    })
                }
                val nowCompleted = states.filterValues { it is ModelDownloadState.Complete }.keys
                if ((nowCompleted - completed).isNotEmpty()) loadModels()
                completed = nowCompleted
            }
        }
        viewModelScope.launch {
            loadModels()
            refreshFromRemote()
        }
    }

    private suspend fun loadModels() {
        // 安装包里本来就有的拍照识别引擎、日语词库和引擎（带模型的安装包）不列出来。
        val packaged = withContext(Dispatchers.IO) { com.kingzcheung.xime.model.packagedPacks(context) }
        val allModels = ModelManager.getAllModels().filterNot { it.id in packaged }
        val versions = withContext(Dispatchers.IO) {
            MarketVersionStore.getAllModelVersions(context)
        }
        val items = allModels.map { model ->
            val downloaded = withContext(Dispatchers.IO) {
                ModelManager.isModelDownloaded(context, model)
            }
            val size = if (downloaded) {
                withContext(Dispatchers.IO) {
                    ModelManager.getModelSizeOnDisk(context, model.id)
                }
            } else 0L
            ModelItemState(
                model = model,
                isDownloaded = downloaded,
                diskSize = size,
                installedVersion = if (downloaded) withContext(Dispatchers.IO) {
                    ModelManager.installedVersionOf(context, model)?.version
                } ?: versions[model.id] else null,
                downloadState = ModelManager.downloadStates.value[model.id]
                    ?.takeUnless { it is ModelDownloadState.Complete } ?: ModelDownloadState.Idle,
            )
        }
        _uiState.update { it.copy(models = items, isLoading = false) }
    }

    private suspend fun refreshFromRemote() {
        _uiState.update { it.copy(isLoading = true) }
        ModelManager.loadFromRemote(context)
        loadModels()
    }

    fun refresh() {
        // 连点防抖：刷新进行中忽略后续点击（重复拉取网络 index 徒增开销）
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            refreshFromRemote()
        }
    }

    /** 仅重新检查本地下载状态（不重新拉取远程 index），用于从本地管理页返回后同步。 */
    fun refreshDownloadedState() {
        viewModelScope.launch { loadModels() }
    }

    fun selectVersion(modelId: String, version: String) {
        _uiState.update {
            it.copy(selectedVersions = it.selectedVersions + (modelId to version))
        }
    }

    fun selectCategory(category: ModelCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun downloadModel(modelId: String, version: ModelVersion? = null) {
        val index = _uiState.value.models.indexOfFirst { it.model.id == modelId }
        if (index < 0) return

        viewModelScope.launch {
            updateModelState(index) {
                it.copy(downloadState = ModelDownloadState.Downloading(0f, 0, -1))
            }

            var downloadError: String? = null
            val selectedVersion = version
                ?: _uiState.value.selectedVersions[modelId]?.let { versionStr ->
                    ModelManager.getModel(modelId)?.versions?.firstOrNull { it.version == versionStr }
                }
            ModelManager.downloadModel(context, _uiState.value.models[index].model, { state ->
                when (state) {
                    is ModelDownloadState.Downloading -> {
                        updateModelState(index) { it.copy(downloadState = state) }
                    }
                    is ModelDownloadState.Error -> {
                        downloadError = state.message
                        updateModelState(index) { it.copy(downloadState = state) }
                    }
                    else -> {}
                }
            }, selectedVersion)

            if (downloadError != null) {
                updateModelState(index) { it.copy(downloadState = ModelDownloadState.Idle) }
            } else {
                val downloaded = withContext(Dispatchers.IO) {
                    ModelManager.isModelDownloaded(context, modelId)
                }
                val size = if (downloaded) {
                    withContext(Dispatchers.IO) {
                        ModelManager.getModelSizeOnDisk(context, modelId)
                    }
                } else 0L
                // 版本由 ModelManager 与最终安装一起记录，UI 不在删除后补写旧下载版本。
                val actualVersion = if (downloaded) withContext(Dispatchers.IO) {
                    MarketVersionStore.getModelVersion(context, modelId)
                } else null
                updateModelState(index) {
                    it.copy(
                        isDownloaded = downloaded,
                        diskSize = size,
                        installedVersion = actualVersion,
                        downloadState = ModelDownloadState.Idle
                    )
                }
                if (downloaded) {
                    // 特意装了旧版本就固定在这一版，不被自动更新换掉；装最新版时解除。
                    withContext(Dispatchers.IO) {
                        val model = _uiState.value.models[index].model
                        com.kingzcheung.xime.model.ModelAutoUpdater.onUserInstalled(context, model,
                            model.versions.firstOrNull { it.version == actualVersion })
                    }
                    val message = if (ModelManager.getModel(modelId)?.category == com.kingzcheung.xime.model.ModelCategory.CANDIDATE)
                        "已安装，重新启动输入法后生效" else "模型下载完成"
                    _uiState.update { s -> s.copy(toastMessage = message) }
                }
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                ModelManager.deleteModel(context, modelId)
            }
            if (success) {
                withContext(Dispatchers.IO) {
                    MarketVersionStore.removeModelVersion(context, modelId)
                    com.kingzcheung.xime.model.ModelAutoUpdater.onUserDeleted(context, modelId)
                }
                val index = _uiState.value.models.indexOfFirst { it.model.id == modelId }
                if (index >= 0) {
                    updateModelState(index) {
                        it.copy(isDownloaded = false, diskSize = 0, installedVersion = null)
                    }
                }
                _uiState.update { s -> s.copy(toastMessage = "模型已删除") }
            } else {
                // 以前删不掉时什么都不提示，看起来像按钮没反应。
                _uiState.update { s -> s.copy(toastMessage = "删除失败") }
            }
        }
    }

    fun setDownloadRegion(region: DownloadRegion) {
        DownloadMirrors.setRegion(context, region)
        _uiState.update { it.copy(downloadRegion = region) }
    }

    /** 从选中的文件、整合包或文件夹导入；按哈希自动认出属于哪个模型。 */
    fun importDocuments(uris: List<Uri>) = runImport { ModelImporter.importDocuments(context, uris) }

    fun importTree(treeUri: Uri) = runImport { ModelImporter.importTree(context, treeUri) }

    private fun runImport(block: suspend () -> ModelImporter.Report) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            val message = try { block().message() } catch (e: Exception) { "导入失败：${e.message}" }
            loadModels()
            _uiState.update { it.copy(isImporting = false, toastMessage = message) }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun updateModelState(index: Int, transform: (ModelItemState) -> ModelItemState) {
        _uiState.update { state ->
            val updated = state.models.toMutableList()
            updated[index] = transform(updated[index])
            state.copy(models = updated)
        }
    }
}
