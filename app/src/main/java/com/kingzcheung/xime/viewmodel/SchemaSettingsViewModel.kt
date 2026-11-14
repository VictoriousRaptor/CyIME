package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.SchemaChangeApplier
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SchemaLanguageChoice
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaUiState(
    val allSchemas: List<SchemaMeta> = emptyList(),
    val enabledSchemas: List<String> = emptyList(),
    val currentSchema: String = "t9_pinyin",
    val isDeploying: Boolean = false,
    /** 启停、导入后正在自动应用（#340）。 */
    val isApplying: Boolean = false,
    val toastMessage: String? = null,
    /** 部署没过或有方案没编译出来时的说明，页面弹窗显示（Toast 在 Android 12 以后最多两行，放不下方案名和原因）。 */
    val deployReport: String? = null,
    val importReport: String? = null,
)

class SchemaSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaUiState())
    val uiState: StateFlow<SchemaUiState> = _uiState.asStateFlow()

    /** 每次导入完成发一次；方案包有同名文件待确认时带上它，页面接着问用户。 */
    private val _importCompleted = MutableSharedFlow<ImportManager.ImportResult.Content?>(extraBufferCapacity = 1)
    val importCompleted: SharedFlow<ImportManager.ImportResult.Content?> = _importCompleted.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            SchemaChangeApplier.applying.collect { applying -> _uiState.update { it.copy(isApplying = applying) } }
        }
        viewModelScope.launch {
            // 同一条编译问题这一页只弹一次：以后每次启停都会自动应用，不能每点一下开关就弹一遍。
            var shownProblem = ""
            SchemaChangeApplier.finished.collect { usable ->
                val problem = withContext(Dispatchers.IO) { RimeConfigHelper.deploymentProblem(context) }
                when {
                    problem.isNotEmpty() && problem != shownProblem -> _uiState.update { it.copy(deployReport = problem) }
                    problem.isEmpty() && !usable ->
                        _uiState.update { it.copy(deployReport = "方案没有应用成功，原因见「关于 › 日志查看器」。可点列表底部「重新部署(修复)」再试。") }
                }
                shownProblem = problem
                refresh()
            }
        }
    }

    fun refresh() {
        // 在 IO 线程读盘（discoverSchemas/getEnabledSchemas 扫描文件），避免 ON_RESUME 在主线程卡顿
        viewModelScope.launch {
            val (allSchemas, enabledSchemas, currentSchema) = withContext(Dispatchers.IO) {
                if (!KeysConfigHelper.hasLoadedLayoutBindings) KeysConfigHelper.loadConfig(context)
                Triple(
                    SchemaManager.discoverSchemas(context),
                    SchemaManager.getEnabledSchemas(context),
                    SettingsPreferences.getCurrentSchema(context),
                )
            }
            // 语言与方案页需要所有组合，只有独立的启停开关列表排除联动项。
            val sorted = allSchemas.sortedByDescending { it.schemaId in enabledSchemas }
            _uiState.update {
                it.copy(
                    allSchemas = sorted,
                    enabledSchemas = enabledSchemas,
                    currentSchema = currentSchema
                )
            }
        }
    }

    /** 没写语言的导入方案：存下用户选的语言（null 回到未标注语言），刷新列表（GitHub #86）。 */
    fun setSchemaLanguage(schemaId: String, language: InputLanguage?, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SchemaLanguageChoice.choose(context, schemaId, language) }
            refresh()
            onDone()
        }
    }

    fun toggleSchema(schema: SchemaMeta) {
        val enabled = _uiState.value.enabledSchemas.toMutableList()
        if (schema.schemaId in enabled) {
            if (enabled.size <= 1) {
                showToast("至少启用一个方案")
                return
            }
            enabled.remove(schema.schemaId)
        } else {
            enabled.add(schema.schemaId)
        }
        val turnedOn = schema.schemaId in enabled
        viewModelScope.launch {
            val (persisted, current) = withContext(Dispatchers.IO) {
                SchemaManager.setEnabledSchemas(context, enabled)
                if (turnedOn) com.kingzcheung.xime.settings.LanguagePreferences.enableForSchema(context, schema.schemaId)
                // 关掉的正是当前方案：马上换到同语言还开着的方案（#340），不然键盘一直用着它、布局栏画不出来。
                else RimeConfigHelper.moveOffDisabledSchema(context)
                SchemaManager.getEnabledSchemas(context) to SettingsPreferences.getCurrentSchema(context)
            }
            _uiState.update { it.copy(enabledSchemas = persisted, currentSchema = current) }
            // 不用再点「应用方案更改」：停手后自动增量部署（#340）。
            SchemaChangeApplier.request(context)
        }
    }

    fun selectSchema(schema: SchemaMeta) {
        if (_uiState.value.currentSchema == schema.schemaId) {
            com.kingzcheung.xime.settings.InputModes.selectProfile(context, schema.toSchemaInfo())
            return
        }
        val previous = _uiState.value.currentSchema
        SettingsPreferences.setCurrentSchema(context, schema.schemaId)
        _uiState.update { it.copy(currentSchema = schema.schemaId) }
        if (RimeEngine.isInitialized()) {
            val available = RimeEngine.getInstance().getAvailableSchemas()
            if (schema.schemaId in available) {
                // 部署/编译进行中 switchSchema 不阻塞返回 false，避免主线程等待
                val switched = RimeEngine.getInstance().switchSchema(schema.schemaId)
                if (switched) {
                    com.kingzcheung.xime.settings.InputModes.selectProfile(context, schema.toSchemaInfo())
                    showToast("已切换到${schema.name}")
                } else {
                    rollbackSchemaSelection(previous)
                    showToast("词库部署中，请稍后再切换方案")
                }
            } else {
                rollbackSchemaSelection(previous)
                // 刚启用、导入的方案还没编译进去：自动应用完就能选（#340）。
                if (!_uiState.value.isApplying) SchemaChangeApplier.request(context)
                showToast("正在应用方案更改，稍等再选")
            }
        } else {
            com.kingzcheung.xime.settings.InputModes.selectProfile(context, schema.toSchemaInfo())
        }
    }

    /**
     * 切换失败时回滚偏好与 UI 状态。不回滚有两个后果：再次点击会被
     * [selectSchema] 开头的同值短路拦住（用户永远无法重试）；偏好与
     * 引擎实际方案脱节后，IME 重启的方案恢复逻辑会拿到偏差好。
     */
    private fun rollbackSchemaSelection(previous: String) {
        SettingsPreferences.setCurrentSchema(context, previous)
        _uiState.update { it.copy(currentSchema = previous) }
    }

    fun importSchemaFile(uri: Uri) {
        viewModelScope.launch {
            when (val result = ImportManager.import(context, uri)) {
                is ImportManager.ImportResult.Content -> {
                    refresh()
                    _importCompleted.emit(result)
                    if (!result.success || (result.pendingPackageId == null && '\n' in result.message))
                        reportImport(result.message)
                    else showToast(result.message)
                }
                is ImportManager.ImportResult.Plugin -> {
                    withContext(Dispatchers.IO) { PluginManager.loadEnabledPlugins() }
                    refresh()
                    _importCompleted.emit(null)
                    showToast("插件「${result.pluginInfo?.name}」安装成功")
                }
                is ImportManager.ImportResult.Failed -> reportImport("导入失败：${result.reason}")
                is ImportManager.ImportResult.Unsupported -> showToast("不支持的文件类型")
            }
        }
    }

    /** [onDeployed] runs on the main thread only after a deployment without problems. */
    fun deploySchema(onDeployed: (() -> Unit)? = null) {
        if (_uiState.value.isDeploying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeploying = true) }
            val success = try {
                withContext(Dispatchers.IO) {
                    KeysConfigHelper.loadConfig(context)
                    KeyboardThemes.reload(context)
                    RimeConfigHelper.redeploy(context)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.kingzcheung.xime.util.FileLogger.e("SchemaSettings", "Deployment repair failed", error)
                RimeConfigHelper.recordFailure(context, error)
                false
            } finally {
                _uiState.update { it.copy(isDeploying = false) }
            }
            val problem = RimeConfigHelper.deploymentProblem(context)
            when {
                problem.isNotEmpty() -> _uiState.update { it.copy(deployReport = problem) }
                success -> { showToast("部署完成"); onDeployed?.invoke() }
                else -> _uiState.update { it.copy(deployReport = "部署失败，原因见「关于 › 日志查看器」。") }
            }
            refresh()
        }
    }

    fun clearDeployReport() {
        _uiState.update { it.copy(deployReport = null) }
    }

    fun reportImport(message: String) = _uiState.update {
        it.copy(importReport = listOfNotNull(it.importReport, message).joinToString("\n\n"))
    }

    fun clearImportReport() = _uiState.update { it.copy(importReport = null) }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }
}
