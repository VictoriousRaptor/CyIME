package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.MarketPluginItem
import com.kingzcheung.xime.settings.PluginsDirectIndex
import com.kingzcheung.xime.settings.PluginVersion
import com.kingzcheung.xime.settings.XimeIndexParser
import com.kingzcheung.xime.settings.XimeIndexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginMarketState(
    val plugins: List<MarketPluginItem> = emptyList(),
    val source: String = "",
    val refreshing: Boolean = false,
    val installing: String? = null,
    val message: String? = null,
)

class PluginMarketViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val state = MutableStateFlow(PluginMarketState())
    val uiState = state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (state.value.refreshing) return
        state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val local = withContext(Dispatchers.IO) { XimeIndexSource.bundledPlugins(context) }
                if (state.value.plugins.isEmpty()) showCatalog("随包目录", local)
                val remote = XimeIndexSource.remotePlugins(context)
                if (remote != null) showCatalog(remote.first, XimeIndexSource.withBundledPlugins(remote.second, local))
            } catch (e: java.io.IOException) {
                state.update { it.copy(message = e.message) }
            } finally {
                state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun showCatalog(source: String, index: PluginsDirectIndex) {
        val installed = installedVersions()
        val items = index.plugins.distinctBy { it.id }
            .filter { it.id.isNotBlank() && it.id !in XimeIndexSource.RETIRED_PLUGIN_IDS }.map {
            XimeIndexParser.toPluginItem(it, BuildConfig.VERSION_NAME, installed)
        }
        state.update { it.copy(plugins = items, source = source) }
    }

    private fun installedVersions() = PluginManager.getAllInstallPlugins().associate { it.id to it.versionName }

    fun refreshInstalled() {
        val installed = installedVersions()
        state.update { current ->
            current.copy(plugins = current.plugins.map { item ->
                item.copy(installed = item.plugin.id in installed, installedVersion = installed[item.plugin.id])
            })
        }
    }

    fun install(item: MarketPluginItem, version: PluginVersion) {
        if (state.value.installing != null || !item.compatible) return
        state.update { it.copy(installing = item.plugin.id) }
        viewModelScope.launch {
            try {
                XimeIndexSource.installMarketPlugin(context, item.plugin.id, version)
                refreshInstalled()
                val where = if (item.plugin.pluginType == "tool") "，在键盘菜单里" else ""
                state.update { it.copy(message = "「${item.plugin.name}」已装好$where") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(message = e.message ?: "安装失败") }
            } finally {
                state.update { it.copy(installing = null) }
            }
        }
    }

    fun clearMessage() = state.update { it.copy(message = null) }
}
