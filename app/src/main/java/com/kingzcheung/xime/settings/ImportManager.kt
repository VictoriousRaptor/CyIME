package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的导入入口。
 *
 * 设计原则：**路由永远按「文件名 / 内容类型」集中在这里决定**，而不是由调用方传入的
 * "方向"参数决定。这样无论是文件选择器还是分享，都汇聚到
 * 同一个路由核心，结构上不可能出现各入口路由逻辑不同步的问题。
 *
 * 调用方（各页面）只需要关心它从哪个入口触发导入，并对返回的 [ImportResult] 给出对应的
 * 成功文案与刷新动作即可。
 */
object ImportManager {

    sealed class ImportResult {
        /**
         * 方案 / 词典 / 背景图等，走 [SchemaManager.saveImportedFile] 的落地结果。
         * 方案压缩包导入后直接安装；和已有文件同名时不覆盖，[pendingPackageId] 留给页面问用户。
         * [message] 是给用户看的一句结果。
         */
        data class Content(
            val success: Boolean,
            val installedDirect: Boolean,
            val pendingPackageId: String? = null,
            val pendingName: String = "",
            val message: String = if (success) "导入成功" else "导入失败",
        ) : ImportResult()

        /** 插件（.xipk）安装结果。 */
        data class Plugin(val pluginInfo: PluginInfo?) : ImportResult()

        data class Failed(val reason: String) : ImportResult()
        data class Unsupported(val fileName: String) : ImportResult()
    }

    /** 判断文件名是否为插件包（仅 .xipk）。 */
    fun isPluginFile(name: String): Boolean =
        name.endsWith(".xipk", ignoreCase = true)

    /** 从 URI 导入（文件选择器、分享等）。按文件名自动路由；方案、词库装好后自动应用（#340），不用再点「应用方案更改」。 */
    suspend fun import(context: Context, uri: Uri): ImportResult = route(context, uri).also { result ->
        if (result is ImportResult.Content && result.success && result.installedDirect)
            com.kingzcheung.xime.rime.SchemaChangeApplier.request(context)
    }

    private suspend fun route(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
            ?: return@withContext ImportResult.Failed("无法识别文件名")
        val name = SchemaManager.sanitizeDisplayName(displayName)
        if (listOf(".rar", ".7z", ".tar").any { name.endsWith(it, ignoreCase = true) }) {
            return@withContext ImportResult.Failed("请解开「$name」，把含 .schema.yaml 的 Rime 目录连同词库、Lua 等子目录重新压成 .zip 再导入")
        }
        if (listOf(".apk", ".exe", ".msi").any { name.endsWith(it, ignoreCase = true) }) {
            return@withContext ImportResult.Failed("「$name」是应用安装程序，请选择 Rime 方案包")
        }

        if (isPluginFile(name)) {
            when (val r = PluginManager.installerManager.installPluginFromUri(uri)) {
                is InstallerManager.InstallResult.Success -> {
                    // 覆盖安装：正在运行的旧版立刻换成新版
                    PluginManager.reloadIfRunning(r.pluginInfo.id)
                    ImportResult.Plugin(r.pluginInfo)
                }
                is InstallerManager.InstallResult.Failure -> ImportResult.Failed(r.reason)
            }
        } else {
            if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name)
                return@withContext ImportResult.Failed("文件名不能包含目录")
            val stream = try {
                when (uri.scheme) {
                    "file" -> java.io.FileInputStream(requireNotNull(uri.path))
                    else -> context.contentResolver.openInputStream(uri)
                }
            } catch (e: Exception) {
                return@withContext ImportResult.Failed("无法读取「$name」：${e.message}")
            }
            if (stream == null) {
                ImportResult.Failed("无法读取文件")
            } else {
                val r = SchemaManager.saveImportedFile(context, name, stream, stageSchemeFiles = true)
                val packageId = r.packageId
                if (r.success && packageId != null) installArchive(context, packageId, archiveTitle(name))
                else ImportResult.Content(r.success, r.installedDirect,
                    message = if (r.success && r.installedDirect) "已导入，正在应用" else if (r.success) "导入成功" else "导入失败")
            }
        }
    }

    /** 导入的方案包直接安装（并打开其中的主方案）；有同名文件时留着等确认，装不上就删掉存下的包。 */
    private suspend fun installArchive(context: Context, packageId: String, title: String): ImportResult.Content {
        val install = SchemaManager.installPackageFromMarketDir(context, packageId, displayName = title)
        return when {
            install.success -> ImportResult.Content(true, installedDirect = true, message = buildString {
                if (title.endsWith(".dict.yaml"))
                    append("已导入词库「$title」，由引用它的方案在部署时加载")
                else if (install.resourcesOnly)
                    append("「$title」只含配套文件，没有可启用的 .schema.yaml 方案")
                else append("已安装「$title」，正在应用，稍等就能在键盘上用")
                if (install.parseFailures.isNotEmpty()) append("\n" + install.parseFailures.joinToString("\n"))
                if (install.unresolvedDeps.isNotEmpty())
                    append("\n请补齐方案引用的文件：" + install.unresolvedDeps.joinToString("、"))
                if (install.keptExistingFiles.isNotEmpty())
                    append("\n保留了已有的 " + install.keptExistingFiles.joinToString("、") + "，没有覆盖，要换请单独导入这个文件")
            })
            install.conflicts.isNotEmpty() -> ImportResult.Content(true, installedDirect = false,
                pendingPackageId = packageId, pendingName = title,
                message = "「$title」里有和已装方案同名的文件，确认后再安装")
            else -> {
                runCatching { SchemaManager.getMarketDir(context, packageId).deleteRecursively() }
                ImportResult.Content(false, installedDirect = false,
                    message = "导入失败：${install.failureReason ?: "安装失败"}")
            }
        }
    }

    /** 压缩包名去掉扩展名，作为方案包的显示名。 */
    internal fun archiveTitle(name: String): String =
        Regex("""\.(zip|tar\.gz|tgz)$""", RegexOption.IGNORE_CASE).replace(name, "").ifBlank { name }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { java.io.File(it).name }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }
}
