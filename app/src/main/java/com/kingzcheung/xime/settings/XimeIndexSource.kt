package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 下载校验状态：null=未提供sha256, true=校验通过, false=校验不通过。 */
typealias Sha256Status = Boolean?

/** 安装结果（带失败原因 + 未解决依赖 + sha256 校验状态）。 */
data class InstallResult(
    val success: Boolean,
    val unresolvedDeps: List<String> = emptyList(),
    val failureReason: String? = null,
    /** null=未提供sha256, true=校验通过, false=校验不通过 */
    val sha256Status: Sha256Status = null,
)

/** 方案列表拉取结果（含命中的来源主机名，供 UI 显示「从哪个端点拉的」）。 */
data class SchemesFetch(
    val schemes: List<MarketSchemeItem>,
    val source: String,
    val updatedAt: String = "",
)

/**
 * 方案市场数据源：从镜像基址的 rimes/index.yaml（扁平索引，schemas 内联所有 MarketScheme）
 * 获取方案列表，按版本 sha256 下载；安装后用 [RimeDependencyResolver] 补齐编译依赖。
 * 网络/Android 依赖集中在此层；解析/版本/兼容性逻辑在 [XimeIndexParser] 纯函数里。
 *
 * 端点列表通过 [xime.yaml] 的 `xime_index.base_urls` 配置，用户可自定义镜像列表。
 */
object XimeIndexSource {
    private const val TAG = "XimeIndexSource"
    private val defaultBaseUrls = CYIME_STORE_BASE_URLS

    private var baseUrls: List<String> = defaultBaseUrls
    private var mirrors: List<String> = buildMirrors(defaultBaseUrls)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val DownloadItem.fileName: String
        get() = url.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "file.${url.substringAfterLast('.').takeIf { it.length in 1..6 } ?: "bin"}"

    private val DownloadItem.sizeBytes: Long
        get() = size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
            ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L

    private fun buildMirrors(userUrls: List<String>): List<String> = userUrls

    private fun ensureConfigured(context: Context) {
        val cfg = KeysConfigHelper.loadXimeIndexConfig(context)
        if (cfg.baseUrls != baseUrls) {
            baseUrls = cfg.baseUrls
            mirrors = buildMirrors(baseUrls)
        }
    }

    /** 镜像 base → 展示用主机名（如 index.ximei.me / fastly.jsdelivr.net）。 */
    private fun hostOf(base: String): String =
        base.substringAfter("://").substringBefore("/")

    /** 跟随索引跳转：根 → 子 → 逐方案（并行、部分失败容忍）。逐个镜像尝试直到获取到方案。 */
    suspend fun fetchSchemes(context: Context, appVersion: String): Result<SchemesFetch> =
        withContext(Dispatchers.IO) {
            ensureConfigured(context)
            try {
                // 遍历所有镜像（国内先魔搭、海外先 GitHub），第一个成功获取到方案的返回
                for (base in StoreMirrors.orderedBases(context, mirrors)) {
                    val result = tryFetchFromBase(base, appVersion)
                    if (result != null) return@withContext Result.success(result)
                }
                // 全部镜像都失败
                Result.success(SchemesFetch(
                    CuratedRimeSchemes.merge(emptyList()).map { XimeIndexParser.toItem(it, appVersion) },
                    "CyIME 内置目录(远程索引暂不可用)",
                ))
            } catch (e: Exception) {
                Log.e(TAG, "fetchSchemes failed", e)
                Result.failure(e)
            }
        }

    /**
     * 尝试从一个镜像基址获取方案列表。
     * 新索引格式：直接抓取 rimes/index.yaml，其中 schemas 已内联所有 MarketScheme。
     */
    private fun tryFetchFromBase(base: String, appVersion: String): SchemesFetch? {
        val repoPath = "rimes/index.yaml"
        val host = hostOf(base)
        try {
            val text = fetchTextSingle(base, repoPath) ?: return null
            val direct = XimeIndexParser.parseDirectIndex(text)
            val schemes = CuratedRimeSchemes.merge(direct.schemas)
                .map { XimeIndexParser.toItem(it, appVersion) }
            Log.i(TAG, "tryFetchFromBase $host: 获取到 ${schemes.size} 个方案(扁平索引)")

            if (schemes.isEmpty()) {
                Log.w(TAG, "tryFetchFromBase $host: 0 个方案，尝试下一个镜像")
                return null
            }
            return SchemesFetch(schemes, host, direct.updatedAt)
        } catch (e: Exception) {
            Log.w(TAG, "tryFetchFromBase $host failed: ${e.message}")
            return null
        }
    }

    /** 从镜像基址获取文件内容，失败返回 null。 */
    private fun fetchTextSingle(base: String, repoPath: String): String? {        return try {
            client.newCall(Request.Builder().url(base + repoPath).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchTextSingle $base$repoPath failed: ${e.message}")
            null
        }
    }

    /**
     * 已经内置或已并入别的插件、市场不再列出的插件（11-03 瘦身）。线上旧索引还没更新时也照样隐藏；
     * 已装的照常能用，在「插件」页卸载。
     */
    internal val RETIRED_PLUGIN_IDS = setOf(
        "online_asr", "typing_stats", "ai_translate", "ai_write", "kaomoji", "meme_bunny", "quick_send_demo",
        "funasr_asr", "tencent_asr", "volc_asr", "webdav_backup", "webdav_clipboard_sync", "ximed_sync",
    ).mapTo(HashSet()) { "com.kingzcheung.xime.plugin.$it" }

    /** 已下架的插件；老包的 manifest 里也有只写短 id（如 ai_translate）的。 */
    internal fun isRetiredPlugin(pluginId: String): Boolean =
        pluginId in RETIRED_PLUGIN_IDS || "com.kingzcheung.xime.plugin.$pluginId" in RETIRED_PLUGIN_IDS

    /**
     * 键盘菜单里能不能自动放这个插件的按钮。已下架的不放：以前开过的 AI 翻译在 11-03 又自己冒进菜单，
     * 和 AI 改写里的翻译重复。用户自己拖进工具栏或菜单的照旧留着。
     */
    internal fun mayAutoPlaceButtons(pluginId: String): Boolean = pluginId !in RETIRED_PLUGIN_IDS

    /**
     * 线上目录补上安装包自带的插件：线上没有的整条补上；随包版本更新时用随包那条，线上独有的旧版本留在后面。
     * 国内先读魔搭，魔搭目录没同步时翻译、字幕这些随包插件照样能在市场里找到、离线装上（11-09）。
     */
    internal fun withBundledPlugins(remote: PluginsDirectIndex, bundled: PluginsDirectIndex): PluginsDirectIndex {
        val byId = remote.plugins.associateBy { it.id }
        val merged = remote.plugins.map { online ->
            val local = bundled.plugins.find { it.id == online.id } ?: return@map online
            if (com.kingzcheung.xime.plugin.core.util.VersionUtil.compare(local.currentVersion, online.currentVersion) <= 0) online
            else local.copy(versions = local.versions + online.versions.filter { v -> local.versions.none { it.version == v.version } })
        }
        return remote.copy(plugins = merged + bundled.plugins.filter { it.id !in byId })
    }

    internal fun bundledPlugins(context: Context): PluginsDirectIndex =
        context.assets.open("plugin-market/index.yaml").bufferedReader().use {
            XimeIndexParser.parsePluginsDirectIndex(it.readText())
        }

    internal suspend fun remotePlugins(context: Context): Pair<String, PluginsDirectIndex>? =
        withContext(Dispatchers.IO) {
            for (base in StoreMirrors.orderedBases(context, KeysConfigHelper.loadXimeIndexConfig(context).baseUrls)) {
                val text = fetchTextSingle(base, "plugins/index.yaml") ?: continue
                val index = runCatching { XimeIndexParser.parsePluginsDirectIndex(text) }.getOrNull() ?: continue
                if (index.plugins.isNotEmpty()) return@withContext hostOf(base) to index
            }
            null
        }

    internal suspend fun installMarketPlugin(context: Context, pluginId: String, version: PluginVersion) =
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("market-", ".xipk", context.cacheDir)
            val firstInstall = PluginManager.getAllInstallPlugins().none { it.id == pluginId }
            try {
                val copies = version.downloadUrls
                val bundled = bundledPlugins(context).plugins.find { it.id == pluginId }
                    ?.versions?.find { it.version == version.version }?.downloadUrls.orEmpty()
                var failure = "没有可校验的下载地址"
                val verified = copies.any { copy ->
                    val hash = copy.sha256?.takeIf { it.isNotBlank() } ?: version.sha256
                    if (!hash.matches(Regex("[a-fA-F0-9]{64}"))) return@any false
                    try {
                        val local = bundled.find { it.sha256.equals(hash, ignoreCase = true) }
                        if (local != null) {
                            val file = local.url.substringAfterLast('/')
                            context.assets.open("plugin-market/packages/$file").use { copyPlugin(it, tmp, hash) }
                        } else {
                            // 同一个包的几条线路（国内魔搭、jsDelivr、GitHub）按地区排好，逐条试到校验通过为止。
                            var lastError: Exception? = null
                            val downloaded = StoreMirrors.fileCandidates(context, copy.url, hash).any { url ->
                                try {
                                    val request = Request.Builder().url(url).build()
                                    require(request.url.isHttps) { "插件包需要 HTTPS 下载地址" }
                                    client.newCall(request).execute().use { response ->
                                        check(response.isSuccessful) { "下载失败(HTTP ${response.code})" }
                                        val body = response.body ?: throw IOException("下载内容为空")
                                        body.byteStream().use { copyPlugin(it, tmp, hash) }
                                    }
                                    true
                                } catch (e: Exception) {
                                    lastError = e
                                    false
                                }
                            }
                            if (!downloaded) throw lastError ?: IOException("下载失败")
                        }
                        true
                    } catch (e: Exception) {
                        failure = e.message ?: "下载失败"
                        false
                    }
                }
                check(verified) { failure }
                val result = PluginManager.installerManager.installPlugin(
                    tmp, forceOverwrite = true, source = PluginSource.REMOTE,
                    expectedId = pluginId, expectedVersion = version.version,
                )
                if (result is InstallerManager.InstallResult.Failure) throw IOException(result.reason)
                PluginManager.reloadIfRunning(pluginId)
                // 从自己的市场第一次装：直接启用，按钮放进键盘菜单，装完就能用（用户 2026-11-02「下载了怎么用」）。
                // 更新不改用户的开关；停用着的等打开开关时再放按钮。
                // 只管工具类（键盘菜单里有按钮的）；语音等单选类仍在各自的设置页里选用。
                // 以前装过但从没拨过开关的（旧版市场装完默认停用）也算，再装一次就能用；拨过的不动。
                val neverChosen = !com.kingzcheung.xime.plugin.core.config.PluginEnablementStore.hasChoice(context, pluginId)
                if ((firstInstall || neverChosen) && PluginManager.getAllInstallPlugins().find { it.id == pluginId }?.type == "tool") {
                    SettingsPreferences.setPluginEnabled(context, pluginId, true)
                    PluginManager.launchPlugin(pluginId)
                }
                PluginManager.getAllInstallPlugins().find { it.id == pluginId }
                    ?.takeIf { SettingsPreferences.isPluginEnabled(context, it.id) }
                    ?.let { info -> SettingsPreferences.placeNewPluginButtons(context, info.toolbarButtons.map { it.id }) }
            } finally {
                tmp.delete()
            }
        }

    private fun copyPlugin(input: InputStream, file: File, expectedHash: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= InstallerManager.MAX_ARCHIVE_FILE_BYTES) { "插件包超过大小上限" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expectedHash, ignoreCase = true)) { "插件包 SHA-256 校验失败" }
    }

    /**
     * 下载一个方案到 market 目录（仅下载，不解压）。
     * 遍历所有 downloadUrl（如主包 + 语言模型等），逐一下载到 market/{schemeId}/。
     */
    suspend fun downloadScheme(
        context: Context,
        scheme: MarketScheme,
        version: String? = null,
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        val v = if (version != null) {
            scheme.versions.firstOrNull { it.version == version }
        } else {
            scheme.resolvedVersion()
        } ?: return@withContext InstallResult(false, failureReason = "无可用版本")
        if (v.downloadUrls.isEmpty() || v.downloadUrls.all { it.url.isBlank() }) {
            return@withContext InstallResult(false, failureReason = "缺少下载地址")
        }

        val items = v.downloadUrls.filter { it.url.isNotBlank() }
        val totalBytesAll = items.sumOf { it.sizeBytes }
        var accumulatedBytes = 0L
        var anyVerified = false

        for (dl in items) {
            val sha = dl.sha256?.takeIf { it.isNotBlank() }
            // 带 sha256 的文件按地区换线路（国内先魔搭转存）；文件名始终取原地址的，安装时按名字找。
            var result = SchemaManager.DownloadResult(false)
            for (url in StoreMirrors.fileCandidates(context, dl.url, sha)) {
                result = SchemaManager.downloadToMarket(
                    context, url, scheme.id, dl.fileName, sha,
                    onProgress = { read, _ ->
                        val overall = accumulatedBytes + read
                        if (totalBytesAll > 0) onDownloadProgress(overall, totalBytesAll)
                    },
                )
                if (result.success) break
            }
            accumulatedBytes += dl.sizeBytes
            if (!result.success) {
                val schemeDir = SchemaManager.getMarketDir(context, scheme.id)
                if (schemeDir.exists()) schemeDir.deleteRecursively()
                val reason = if (result.sha256Verified == false)
                    "文件校验失败(sha256 不匹配)，文件可能不完整" else "下载失败"
                return@withContext InstallResult(
                    false, failureReason = reason, sha256Status = result.sha256Verified
                )
            } else if (result.sha256Verified == true) {
                anyVerified = true
            }
        }
        InstallResult(success = true, sha256Status = if (anyVerified) true else null)
    }

    /**
     * 从 market 目录安装已下载的方案到 rime 目录（解压/复制 + 依赖补齐）。
     * 安装前检测文件冲突（同名且内容不同），发现真正冲突则阻止安装。
     */
    suspend fun installFromMarket(
        context: Context,
        scheme: MarketScheme,
        resolveDepUrl: (String) -> String? = { null },
        switchEnabled: Boolean = true,
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!SchemaManager.isSchemeDownloaded(context, scheme.id)) {
            return@withContext InstallResult(false, failureReason = "压缩包不存在，请先下载")
        }
        val result = SchemaManager.installPackageFromMarketDir(
            context = context,
            packageId = scheme.id,
            displayName = scheme.name,
            version = scheme.currentVersion,
            fromMarket = true,
            dependencies = scheme.dependencies,
            resolveDepUrl = resolveDepUrl,
            switchEnabled = switchEnabled,
        )
        if (!result.success) {
            val reason = result.failureReason ?: result.conflicts.joinToString("、") { c ->
                "${c.fileName}(已被 ${c.claimedBy.joinToString("、")} 使用)"
            }
            return@withContext InstallResult(false, failureReason = reason)
        }
        InstallResult(success = true, unresolvedDeps = result.unresolvedDeps)
    }
}
