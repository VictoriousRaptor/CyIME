package com.kingzcheung.xime.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 已装模型自动换新版：新版应用的模型目录里，某个已经装着的模型有了更新的版本（重新训练的手写模型、九键词组模型、
 * 语音模型等），连着不计流量的网络（Wi-Fi）时在后台下载新版本，和手动更新走同一套下载、核对和整体替换；
 * 下载或核对失败时原来的模型原样保留，不用先删再下。
 *
 * 不自动更新：没装的模型、整个已停用的模型、用户在模型中心特意装的旧版本，以及自用版的九键词组模型（打包器带的个人模型）。
 * 新模型在下次加载时生效：手写键盘收起再打开；九键词组模型重新启动输入法后。
 */
object ModelAutoUpdater {
    private const val TAG = "ModelAutoUpdate"
    private const val PREFS = "model_auto_update"
    private const val KEY_ENABLED = "enabled"
    private const val PIN_PREFIX = "pinned_"
    private const val ATTEMPT_PREFIX = "attempt_"

    /** 同一个新版本最多自动试几次；之后只在模型中心手动更新。 */
    internal const val MAX_ATTEMPTS = 3
    /** 两次自动尝试同一个新版本至少隔多久。 */
    internal const val RETRY_INTERVAL_MS = 6 * 60 * 60 * 1000L
    /**
     * 两次检查至少隔多久，按墙上时间记在 [KEY_LAST_SCAN]，进程重启也不重查。
     * 以前是 10 分钟、只记在内存：Wi-Fi 信号一变网络回调就来，常驻的输入法进程每 10 分钟拉一次模型目录、
     * 解析一遍，进程每次重启又重查（用户 11-19：耗电、费流量）。新模型最晚 6 小时后自动下，同失败重试间隔。
     */
    internal const val SCAN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val KEY_LAST_SCAN = "last_scan_at"
    /** 进程刚启动时先让键盘和词库加载完。 */
    private const val STARTUP_DELAY_MS = 30_000L

    /** 下载新版后手机上至少还剩这么多空间才自动更新。 */
    private const val FREE_SPACE_RESERVE = 500L * 1024 * 1024

    private val running = AtomicBoolean(false)
    @Volatile private var lastScan = 0L
    @Volatile private var firstScanDone = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context, force = true)
    }

    /** 模型目录里可下载的最新版本（目录里第一个没退役的）。 */
    internal fun latestVersion(model: ModelInfo): ModelVersion? = model.downloadableVersions.firstOrNull()

    /** [candidate] 比 [installed] 新：目录里越靠前越新；认不出的版本不算更新的。 */
    internal fun isNewer(model: ModelInfo, candidate: ModelVersion, installed: ModelVersion): Boolean {
        val c = model.versions.indexOfFirst { it.version == candidate.version }
        val i = model.versions.indexOfFirst { it.version == installed.version }
        return c >= 0 && i >= 0 && c < i
    }

    /** 该自动换成的版本；不用换时返回 null。[pinned] 是用户特意装的版本。 */
    internal fun pendingUpdate(model: ModelInfo, installed: ModelVersion?, pinned: String?): ModelVersion? {
        installed ?: return null
        val latest = latestVersion(model) ?: return null
        if (pinned != null && pinned == installed.version) return null
        return latest.takeIf { isNewer(model, it, installed) }
    }

    /** 这个新版本还能不能自动试：没试过、或试过不到 [MAX_ATTEMPTS] 次且离上次超过 [RETRY_INTERVAL_MS]。 */
    internal fun attemptAllowed(record: String?, version: String, now: Long): Boolean {
        val parts = record?.split('|') ?: return true
        if (parts.size != 3 || parts[0] != version) return true
        val count = parts[1].toIntOrNull() ?: return true
        val last = parts[2].toLongOrNull() ?: return true
        // 时钟被往回调过时不卡住。
        return count < MAX_ATTEMPTS && (now - last >= RETRY_INTERVAL_MS || now < last)
    }

    internal fun nextAttempt(record: String?, version: String, now: Long): String {
        val parts = record?.split('|')
        val count = if (parts?.size == 3 && parts[0] == version) parts[1].toIntOrNull() ?: 0 else 0
        return "$version|${count + 1}|$now"
    }

    fun pinnedVersion(context: Context, id: String): String? = prefs(context).getString(PIN_PREFIX + id, null)

    /** 用户在模型中心装好了 [version]：不是最新版就固定在这个版本、不自动换；装的是最新版就解除。 */
    fun onUserInstalled(context: Context, model: ModelInfo, version: ModelVersion?) {
        val editor = prefs(context).edit().remove(ATTEMPT_PREFIX + model.id)
        val latest = latestVersion(model)
        if (version == null || latest == null || version.version == latest.version) editor.remove(PIN_PREFIX + model.id)
        else editor.putString(PIN_PREFIX + model.id, version.version)
        editor.apply()
    }

    fun onUserDeleted(context: Context, id: String) {
        prefs(context).edit().remove(PIN_PREFIX + id).remove(ATTEMPT_PREFIX + id).apply()
    }

    /** 模型管理里旧版本后面的一句说明。 */
    fun updateNote(context: Context, model: ModelInfo, installedVersion: String?): String? {
        val latest = latestVersion(model) ?: return null
        val installed = model.versions.firstOrNull { it.version == installedVersion } ?: return null
        if (!isNewer(model, latest, installed)) return null
        return when {
            pinnedVersion(context, model.id) == installed.version -> "已固定在这个版本，不自动更新"
            !isEnabled(context) -> "有新版 ${latest.version}，可在扩展里更新"
            !allowedForBuild(model) -> "有新版 ${latest.version}，自用版不自动更新"
            else -> "有新版 ${latest.version}，连上 Wi-Fi 后自动更新"
        }
    }

    /** 自用版（打包器「装到我的设备」）带个人九键模型：词组模型不自动换，其他照常。 */
    private fun allowedForBuild(model: ModelInfo): Boolean =
        !(com.kingzcheung.xime.BuildConfig.DEV_TOOLS && model.category == ModelCategory.CANDIDATE)

    /** 连着不计流量的网络（Wi-Fi、有线）。没联网或按流量计费都不算。 */
    private fun onUnmeteredNetwork(context: Context): Boolean = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { false }

    /** 离上次检查（墙上时间）够久了；时钟往回调过也算到期。 */
    internal fun scanDue(lastScanAt: Long, now: Long): Boolean =
        lastScanAt <= 0L || now < lastScanAt || now - lastScanAt >= SCAN_INTERVAL_MS

    /** 应用启动、网络变化时调用；不在 Wi-Fi、关掉了或刚查过时直接返回。 */
    fun start(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        // 网络回调很频繁：内存里先挡一道，不用每次都读设置、查网络。
        if (!force && lastScan != 0L && now - lastScan < SCAN_INTERVAL_MS) return
        if (!isEnabled(app) || !onUnmeteredNetwork(app)) return
        val wallNow = System.currentTimeMillis()
        val lastAt = prefs(app).getLong(KEY_LAST_SCAN, 0L)
        // 还没到期：内存里记成上次检查的时刻，到期后第一次网络回调就查。
        if (!force && !scanDue(lastAt, wallNow)) { lastScan = now - (wallNow - lastAt); return }
        if (!running.compareAndSet(false, true)) return
        lastScan = now
        prefs(app).edit().putLong(KEY_LAST_SCAN, wallNow).apply()
        scope.launch {
            try {
                if (!firstScanDone) { delay(STARTUP_DELAY_MS); firstScanDone = true }
                scan(app)
            } catch (error: Exception) {
                FileLogger.w(TAG, "自动更新模型失败，下次联网重试：${error.message}")
            } finally { running.set(false) }
        }
    }

    private suspend fun scan(app: Context) {
        ModelManager.initialize()
        // 网上模型目录里有更新的版本（不用升级 App 就能换新模型）。
        ModelManager.loadFromRemote(app)
        for (model in ModelManager.getAllModels()) {
            if (!isEnabled(app) || !onUnmeteredNetwork(app)) return
            if (!allowedForBuild(model)) continue
            if (ModelManager.downloadStates.value[model.id] is ModelDownloadState.Downloading) continue
            val installed = ModelManager.installedVersionOf(app, model)
            val target = pendingUpdate(model, installed, pinnedVersion(app, model.id)) ?: continue
            // 新旧两份要同时放得下，再给手机留点余地；空间不够不算一次尝试。
            val bytes = target.files.sumOf { it.sizeBytes }
            val free = ModelStorage.getModelsRoot(app).apply { mkdirs() }.usableSpace
            if (free < bytes + FREE_SPACE_RESERVE) continue
            val prefs = prefs(app)
            val record = prefs.getString(ATTEMPT_PREFIX + model.id, null)
            val wallNow = System.currentTimeMillis()
            if (!attemptAllowed(record, target.version, wallNow)) continue
            prefs.edit().putString(ATTEMPT_PREFIX + model.id, nextAttempt(record, target.version, wallNow)).commit()
            FileLogger.i(TAG, "${model.id}: ${installed?.version} → ${target.version}")
            ModelManager.downloadModel(app, model, {}, target)
            if (ModelManager.installedVersionOf(app, model)?.version == target.version) {
                prefs.edit().remove(ATTEMPT_PREFIX + model.id).commit()
                onReplaced(app, model)
                FileLogger.i(TAG, "${model.id} 已更新到 ${target.version}")
            }
        }
    }

    /** 换好后让下次加载用新模型；自己写的手写模板保留。 */
    private fun onReplaced(context: Context, model: ModelInfo) {
        if (model.id == "ochwpro" && !com.kingzcheung.xime.handwriting.HandwritingEngine.personalModelFile(context).isFile) {
            com.kingzcheung.xime.handwriting.HandwritingTemplates.onBaseModelUpdated()
        }
    }
}
