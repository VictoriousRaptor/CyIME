package com.kingzcheung.xime.model

import android.content.Context
import android.os.SystemClock
import com.kingzcheung.xime.settings.KeysConfigHelper
import java.io.File

/**
 * 网上模型目录（store/models/index.yaml，魔搭和 GitHub 各一份）里内置模型的新版本（11-19）。
 *
 * 以前模型版本只写在 App 里（[BuiltinModelCatalog]），重新训练的手写模型要发新版 App 才看得到。现在打开模型管理、
 * Wi-Fi 下自动更新检查时读这份目录：内置模型有更新的版本就排在最前面，显示「更新」，下载、核对、替换照旧。
 * 只认 App 里已有的模型，只收每个文件（和压缩包）都写了 sha256、地址都是 https 的版本；读不到目录时照旧只用内置版本。
 * 读到的目录在本机存一份，下次启动先用它：没网时已经换上的新版本照样认得出。
 */
internal object RemoteModelVersions {
    private const val CACHE = "remote_models_index.yaml"
    /** 打开模型管理、网络变化都会调到：两次联网读目录至少隔这么久。 */
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    @Volatile private var lastFetch = 0L

    /**
     * [remote] 里 [builtin] 没有的版本放到最前面（目录里越靠前越新）；不认识的模型、没写全校验值的版本不要。
     * 网上目录只给数据（#498）：带程序库的模型（拍照识别引擎、日语引擎）只用 App 里写死的版本，
     * 网上的版本里有程序文件的也不要。下载哪份原生代码不能由服务器决定。
     */
    fun merge(builtin: List<ModelInfo>, remote: List<ModelInfo>): List<ModelInfo> {
        val byId = remote.groupBy { it.id }
        return builtin.map { model ->
            if (model.versions.any(::carriesCode)) return@map model
            val known = model.versions.mapTo(HashSet()) { it.version }
            val extra = byId[model.id].orEmpty().flatMap { it.versions }
                .filter { it.version.isNotBlank() && it.version !in known && pinned(it) && !carriesCode(it) }
                .distinctBy { it.version }
            if (extra.isEmpty()) model else model.copy(versions = extra + model.versions)
        }
    }

    internal fun pinned(version: ModelVersion): Boolean {
        if (version.retired || version.files.isEmpty()) return false
        val archive = version.archiveUrl
        if (archive != null && (!https(archive) || !sha(version.sha256) || !version.archiveMirrors.all(::https))) return false
        return version.files.all { file ->
            sha(file.sha256) && file.sizeBytes > 0 && (archive != null || https(file.downloadUrl)) && file.mirrors.all(::https)
        }
    }

    private val CODE = Regex("""\.(so|dex|jar|apk|odex|vdex|oat)$""", RegexOption.IGNORE_CASE)

    /** 这个版本里有程序文件（原生库、dex、jar、安装包）。 */
    internal fun carriesCode(version: ModelVersion): Boolean = version.files.any { CODE.containsMatchIn(it.name) }

    private fun sha(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    private fun https(url: String) = url.startsWith("https://")

    /** 本机存的那份目录；没有或坏了时为空。 */
    fun cached(context: Context): List<ModelInfo> = runCatching {
        File(context.filesDir, CACHE).takeIf { it.isFile }?.readText()?.let(ModelIndexLoader::parseModelsIndex)
    }.getOrNull().orEmpty()

    /** 联网读一次目录并存下来；刚读过、读不到时返回 null（照旧用手上的）。 */
    fun refresh(context: Context): List<ModelInfo>? {
        val now = SystemClock.elapsedRealtime()
        if (lastFetch != 0L && now - lastFetch < MIN_INTERVAL_MS) return null
        lastFetch = now
        val text = ModelIndexLoader.fetchIndexText(KeysConfigHelper.loadXimeIndexConfig(context).baseUrls) ?: return null
        val models = ModelIndexLoader.parseModelsIndex(text).takeIf { it.isNotEmpty() } ?: return null
        runCatching {
            val file = File(context.filesDir, CACHE)
            val tmp = File(context.filesDir, "$CACHE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        return models
    }
}
