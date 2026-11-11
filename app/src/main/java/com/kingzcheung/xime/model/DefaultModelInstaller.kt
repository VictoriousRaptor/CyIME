package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.speech.SpeechModelSelection
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 首次打开应用时安装默认模型；与市场共用下载器、版本和目录，不另建模型副本。 */
object DefaultModelInstaller {
    internal val modelIds = setOf("ochwpro", SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.SENSEVOICE)
    private val started = AtomicBoolean(false)
    private val observingNetwork = AtomicBoolean(false)
    @Volatile private var bundledReady = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun preferences(context: Context) = context.getSharedPreferences("default_models", Context.MODE_PRIVATE)
    private const val KEY_ALLOW_METERED = "allow_metered"
    private const val KEY_GUIDE = "guide_ids"
    private const val KEY_AWAIT_GUIDE = "await_guide"
    private val waiting = MutableStateFlow(0L)

    /** 首次默认模型因为当前是移动数据（按流量计费）而没有下载时的总字节数；0 表示没在等。 */
    val waitingForWifiBytes: StateFlow<Long> = waiting

    /** 用户在提示里点了“用流量下载”：之后首次默认模型在按流量计费的网络上也照常下载。 */
    fun allowMeteredDownload(context: Context) {
        preferences(context).edit().putBoolean(KEY_ALLOW_METERED, true).commit()
        waiting.value = 0L
        start(context.applicationContext)
    }

    /** 当前网络按流量计费（移动数据、热点）。没联网时不算，照旧由下载失败重试处理。 */
    private fun isMetered(context: Context): Boolean = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { false }

    /** 这些默认模型里还没装好的文件一共多大（字节）。 */
    internal fun missingBytes(context: Context, ids: List<String>): Long = ids.mapNotNull { ModelManager.getModel(it) }
        .filterNot { ModelManager.isModelDownloaded(context, it) }
        .sumOf { model -> model.files.sumOf { it.sizeBytes } }

    /**
     * 新装的用户先看「按设备推荐配置」再下载：第一次启动时还没做完首次设置、也没见过推荐配置，就记下「等推荐配置」；
     * 推荐配置按了「按推荐设置」、「以后再说」或「更多配置」（[SetupGuide.markShown]）之后才照常下载。
     * 以前装好一打开就在后台下手写和语音模型（两三百 MB），用户还没选。老用户升级不受影响；老人包没有推荐配置，照旧。
     */
    private fun awaitingGuide(context: Context): Boolean {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_AWAIT_GUIDE)) {
            val fresh = !com.kingzcheung.xime.BuildConfig.ELDER && !SettingsPreferences.isSetupCompleted(context) &&
                !SetupGuide.isShown(context) && prefs.all.keys.none { it.startsWith("handled_") || it.startsWith("pending_") }
            prefs.edit().putBoolean(KEY_AWAIT_GUIDE, fresh).commit()
        }
        return prefs.getBoolean(KEY_AWAIT_GUIDE, false) && !SetupGuide.isShown(context)
    }

    internal fun isHandled(context: Context, id: String): Boolean = preferences(context).getBoolean("handled_$id", false)

    /** 设置向导选好、要从网上下载的模型。 */
    private fun guideIds(context: Context): Set<String> = preferences(context).getStringSet(KEY_GUIDE, emptySet()).orEmpty()

    /**
     * 设置向导选好的模型和首次默认模型一样排队下载：移动数据下等 Wi-Fi（[allowMetered] 时照常下），
     * 失败重试；下好后不再自动补回，之后在模型管理里删掉也算用户的选择。
     */
    fun requestGuided(context: Context, ids: Set<String>, allowMetered: Boolean) {
        val editor = preferences(context).edit().putStringSet(KEY_GUIDE, guideIds(context) + ids)
        ids.forEach { editor.remove("handled_$it") }
        if (allowMetered) editor.putBoolean(KEY_ALLOW_METERED, true)
        editor.commit()
        start(context.applicationContext)
    }

    /** Prepare only the initial recommendation while the user still requests it. Manual
     * alternatives remain under the model centre's control, not a new auto-download loop. */
    internal fun requestedModelIds(context: Context): List<String> = buildList {
        add("ochwpro")
        if (SettingsPreferences.isSttEnabled(context) && SettingsPreferences.isSttUseLocal(context)) {
            fun dependencies(mode: String): List<String> = listOf(SpeechModelSelection.primary(mode)) +
                if (SpeechModelSelection.hasCorrection(mode)) listOf(SpeechModelCatalog.SENSEVOICE) else emptyList()
            val manager = AsrModelManager(context)
            val choices = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
            val current = dependencies(manager.getSelectedModelId()) + com.kingzcheung.xime.settings.InputLanguage.speech
                .filter { choices.contains("selected_model_${it.id}") }.flatMap { dependencies(manager.getSelectedModelId(it)) }
            addAll(dependencies(DeviceDefaults.voiceModel(context)).filter { it in current })
        }
        addAll(guideIds(context))
    }.distinct()

    internal fun isPendingDefault(context: Context, id: String): Boolean =
        !isHandled(context, id) && id in requestedModelIds(context)

    internal fun markHandled(context: Context, id: String) {
        if (id in modelIds || id in guideIds(context)) preferences(context).edit()
            .putBoolean("handled_$id", true).remove("pending_$id").commit()
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (com.kingzcheung.xime.BuildConfig.BUNDLED_MODELS && !bundledReady) {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                try { BundledModelInstaller.install(app); bundledReady = true }
                catch (error: Exception) { FileLogger.e("DefaultModels", "内置模型准备失败，下次启动重试", error) }
                finally { started.set(false) }
                // A partial offline edition: defaults that were not bundled download as usual.
                if (bundledReady) start(app)
            }
            return
        }
        if (observingNetwork.compareAndSet(false, true)) {
            runCatching { app.getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { start(app) }
                    // Wi-Fi 接上时可能先报可用、稍后才标成不计流量。
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (waiting.value > 0 && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) start(app)
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) ModelAutoUpdater.start(app)
                    }
                }) }
        }
        // 已装模型有新版本时连着 Wi-Fi 自动换新（自己判断网络和间隔）。
        ModelAutoUpdater.start(app)
        // 新装还没看推荐配置：先不下，看过后 SetupGuide.markShown 再叫一次。
        if (awaitingGuide(app)) { waiting.value = 0L; return }
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val prefs = preferences(app)
            val pending = requestedModelIds(app).filterNot { isHandled(app, it) }
            if (pending.isEmpty()) { waiting.value = 0L; started.set(false); return@launch }
            // 移动数据不自动下几百 MB：等 Wi-Fi（换网时网络回调会再进来），或用户在设置首页确认用流量下载。
            if (!prefs.getBoolean(KEY_ALLOW_METERED, false) && isMetered(app)) {
                waiting.value = missingBytes(app, pending)
                if (waiting.value > 0) { started.set(false); return@launch }
            }
            waiting.value = 0L
            try {
                ModelManager.initialize()
                repeat(3) { attempt ->
                    if (attempt > 0) delay(if (attempt == 1) 15_000L else 60_000L)
                    ModelManager.loadFromRemote(app)
                    for (id in pending) {
                        // 前一模型可能下载较久，用户此时删除后续模型应立即从队列退出。
                        if (!isPendingDefault(app, id)) continue
                        val model = ModelManager.getModel(id) ?: continue
                        // 下载先写暂存目录，已有完整市场版本可以直接复用，包括非最新版本。
                        val ready = ModelManager.isModelDownloaded(app, model)
                        var complete = ready
                        if (!ready) {
                            prefs.edit().putBoolean("pending_$id", true).commit()
                            ModelManager.downloadModel(app, model, { state ->
                                complete = state is ModelDownloadState.Complete
                            }, onlyIfDefaultPending = true)
                        }
                        if (complete && ModelManager.isModelDownloaded(app, model)) {
                            // 完成后不再自动补回，用户在模型中心删除模型也是有效选择。
                            markHandled(app, id)
                            if (id == DeviceDefaults.voiceModel(app)) {
                                val settings = SettingsPreferences.getPrefsPublic(app)
                                // 只为尚未选择语音后端的新用户设置本地默认值。
                                val editor = settings.edit()
                                if (!settings.contains(SettingsPreferences.KEY_STT_ENABLED)) {
                                    editor.putBoolean(SettingsPreferences.KEY_STT_ENABLED, true)
                                }
                                if (!settings.contains(SettingsPreferences.KEY_STT_USE_LOCAL) &&
                                    SettingsPreferences.getSttOnlinePluginId(app).isBlank()) {
                                    editor.putBoolean(SettingsPreferences.KEY_STT_USE_LOCAL, true)
                                }
                                editor.apply()
                            }
                        }
                    }
                    if (pending.none { isPendingDefault(app, it) }) return@launch
                }
            } catch (error: Exception) {
                FileLogger.w("DefaultModels", "默认模型准备失败，联网或下次启动重试：${error.message}")
            } finally { started.set(false) }
        }
    }
}
