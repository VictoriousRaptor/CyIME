package com.kingzcheung.xime.ui.settings

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsContent(
    pluginId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val loadedInstance = remember(pluginId) { ExtensionManager.getPluginById(pluginId) }
    // 停用着或没加载上的插件也要能填密钥和地址：临时开一份只给这页用，离开时卸掉。
    val detachedInstance = remember(pluginId, loadedInstance) {
        if (loadedInstance == null) com.kingzcheung.xime.plugin.core.runtime.PluginManager.openForSettings(pluginId) else null
    }
    DisposableEffect(detachedInstance) { onDispose { runCatching { detachedInstance?.onUnload() } } }
    val pluginInstance = loadedInstance ?: detachedInstance
    val pluginInfo = remember(pluginId) { ExtensionManager.getAllInstalledPlugins().find { it.id == pluginId } }

    if (pluginInfo == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("插件设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("插件未找到")
            }
        }
        return
    }

    // 没加载上也进同一页：说明、联网、卸载照样在，只是没有设置项。
    if (pluginInstance == null) {
        PluginConfigFormScreen(
            pluginId = pluginId,
            plugin = NoSettings,
            pluginName = pluginInfo.name,
            onBack = onBack,
            note = com.kingzcheung.xime.plugin.core.security.PluginErrorLog.getLastError(pluginId)
                ?.let { "插件加载失败：${it.message}" } ?: "插件加载失败",
        )
        return
    }

    val schema = remember(pluginInstance) {
        runCatching { (pluginInstance as? IPluginConfigurable)?.getSettingsSchema().orEmpty() }
            .getOrElse { e ->
                Log.e("PluginDetailScreen", "getSettingsSchema failed for $pluginId", e)
                emptyList()
            }
    }
    val hasSchema = schema.isNotEmpty()
    val hasCustomSettings = pluginInstance is EmojiPlugin && pluginInstance.hasSettings()

    when {
        hasSchema -> {
            PluginConfigFormScreen(
                pluginId = pluginId,
                plugin = pluginInstance as IPluginConfigurable,
                pluginName = pluginInfo.name,
                schema = schema,
                onBack = onBack
            )
        }

        hasCustomSettings -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(pluginInfo.name) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            ) {
                LaunchedEffect(Unit) {
                    try {
                        (pluginInstance as EmojiPlugin).openSettings(context)
                        onBack()
                    } catch (e: Exception) {
                        Toast.makeText(context, "无法打开插件设置: ${e.message}", Toast.LENGTH_LONG).show()
                        onBack()
                    }
                }
            }
        }

        else -> {
            PluginConfigFormScreen(
                pluginId = pluginId,
                plugin = (pluginInstance as? IPluginConfigurable) ?: NoSettings,
                pluginName = pluginInfo.name,
                onBack = onBack,
                // 字幕、日语插件的设置由宿主画在这一页（hasHostSettings），不能再说没有设置项（11-12 平板）。
                note = if (hasHostSettings(pluginId)) null else "这个插件没有设置项",
            )
        }
    }
}

/** 没有设置项的插件也用同一页（说明、启用、联网、卸载）。 */
private object NoSettings : IPluginConfigurable
