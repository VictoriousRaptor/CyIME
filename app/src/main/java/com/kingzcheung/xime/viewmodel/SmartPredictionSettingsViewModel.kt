package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SmartPredictionUiState(
    val isEnabled: Boolean = false,
    val isInitialized: Boolean = false,
    val cacheSize: Int = 0,
    val observations: Long = 0,
    val profileSize: Int = 0,
    val learningStatus: String = "",
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val toastMessage: String? = null
)

class SmartPredictionSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private var modelOperation: kotlinx.coroutines.Job? = null
    
    private val _uiState = MutableStateFlow(SmartPredictionUiState(
        isEnabled = SettingsPreferences.isSmartPredictionEnabled(context),
        isInitialized = AssociationManager.isInitialized()
    ))
    val uiState: StateFlow<SmartPredictionUiState> = _uiState.asStateFlow()
    
    init {
        loadCacheSize()
    }
    
    private fun loadCacheSize() {
        viewModelScope.launch {
            try {
                updateLearningCounts()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(learningStatus = "读取失败：${error.message}") }
            }
        }
    }

    private suspend fun updateLearningCounts() {
        val data = withContext(Dispatchers.IO) { AssociationManager.learningData(context) }
        _uiState.update { it.copy(cacheSize = data.uniqueSequences, observations = data.observations,
            profileSize = data.continuations.size) }
    }

    /** 操作自己要换成功提示时写这里（比如导入的是文字文件），null 用 learningOperation 的默认提示。 */
    @Volatile private var operationNote: String? = null

    private fun learningOperation(success: String, block: suspend () -> Unit) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, learningStatus = "处理中…") }
            try {
                operationNote = null
                withContext(Dispatchers.IO) { block() }
                updateLearningCounts()
                val message = operationNote ?: success
                _uiState.update { it.copy(learningStatus = message, toastMessage = message) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = "操作失败：${error.message}"
                _uiState.update { it.copy(learningStatus = message, toastMessage = message) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun exportLearningData(uri: android.net.Uri) = learningOperation("已导出学习文件") {
        // 按存储上限裁剪后再导出，保证导出的文件一定能原样导入回来。
        val data = AssociationManager.learningData(context).boundedForStorage()
        AssociationManager.saveUserData()
        val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "无法写入所选位置" }
        output.bufferedWriter().use { it.write(data.encodeForEditing()) }
    }

    fun importLearningData(uri: android.net.Uri, merge: Boolean) = learningOperation(if (merge) "已新增导入" else "已导入并替换") {
        val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件" }
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= com.kingzcheung.xime.association.PersonalLearningData.MAX_BYTES) { "学习文件超过 32 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= com.kingzcheung.xime.association.PersonalLearningData.MAX_BYTES) { "学习文件超过 32 MB" }
        val learning = try {
            com.kingzcheung.xime.association.PersonalLearningData.decode(bytes.toString(Charsets.UTF_8))
        } catch (_: org.json.JSONException) {
            null
        } catch (_: com.kingzcheung.xime.association.NotLearningFileException) {
            null
        }
        if (learning != null) {
            AssociationManager.importLearningData(context, learning, merge)
            return@learningOperation
        }
        // 不是学习文件就当自己的语料（txt、md、csv、srt…，不看扩展名，看内容是不是文字）：按打过这些字来学，只新增。
        // 讯飞、搜狗等词库和别的二进制文件读不成文字，指到真正的词库导入入口，不显示报错原文。
        val text = com.kingzcheung.xime.association.PersonalLearningData.textOf(bytes)
            ?: throw IllegalArgumentException("这不是学习文件，也不是文字文件。词库请在「词库」页的「导入词库」导入")
        val corpus = com.kingzcheung.xime.association.PersonalLearningData.fromText(text)
        require(corpus.uniqueSequences > 0) { "文件里没有能学的文字" }
        AssociationManager.importLearningData(context, corpus, merge = true)
        operationNote = "已从文字学到 ${corpus.uniqueSequences} 组搭配" + if (merge) "" else "（文字文件只新增，没有替换）"
    }
    
    fun setEnabled(enabled: Boolean) {
        SettingsPreferences.setSmartPredictionEnabled(context, enabled)
        _uiState.update { it.copy(isEnabled = enabled) }
        
        if (enabled && !_uiState.value.isInitialized) {
            loadModel()
        } else if (!enabled) {
            releaseModel()
        }
    }
    
    private fun loadModel() {
        modelOperation?.cancel()
        modelOperation = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val success = withContext(Dispatchers.IO) {
                AssociationManager.initialize(context)
            }
            
            _uiState.update { it.copy(
                isInitialized = success,
                isLoading = false
            )}
            
            if (!success) {
                _uiState.update { it.copy(toastMessage = "联想加载失败") }
            }
        }
    }
    
    private fun releaseModel() {
        modelOperation?.cancel()
        AssociationManager.release()
        _uiState.update { it.copy(isInitialized = false, isLoading = false) }
    }
    
    fun saveUserData() {
        learningOperation("已保存到本机；导出请使用“导出文件”") { AssociationManager.saveUserData() }
    }
    
    fun refreshCacheSize() {
        learningOperation("已刷新统计") { }
    }
    
    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
    
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
