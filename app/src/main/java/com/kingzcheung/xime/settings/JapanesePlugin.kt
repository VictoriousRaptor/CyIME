package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「日语」插件（用户 11-11：安装包大块改成按需下载，能当插件的放进插件市场；日语不是人人要）。
 *
 * 和字幕插件一样：插件包只声明 `capabilities.japanese`，日语方案、键盘和 azooKey 转换都还在宿主里。
 * 插件装着并启用时日语才打开，缺的词库和转换引擎开始下载（[JapanesePack]）；停用就从语言切换里拿掉日语，
 * 卸载时再删掉下载的词库（学习记录留着）。插件包随安装包带在插件市场目录里，装它不用联网。
 * 「语言与方案」里打开日语也会装上并启用这个插件（[onLanguageEnabled]），两边是同一个开关的两个入口。
 */
object JapanesePlugin {
    const val ID = "com.kingzcheung.xime.plugin.japanese"

    private const val TAG = "JapanesePlugin"
    private const val KEY_ACTIVE = "japanese_plugin_active"
    private const val KEY_MIGRATED = "japanese_plugin_migrated"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 日语插件装着并启用（由 [sync] 维护的偏好，不碰插件管理器）。 */
    fun isActive(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_ACTIVE, false)

    private fun installed(): Boolean = runCatching {
        PluginManager.isInitialized && PluginManager.getAllInstallPlugins().any { it.id == ID && it.capabilities?.japanese == true }
    }.getOrDefault(false)

    /**
     * 按插件现在装没装、开没开，打开或关掉日语。启动时、拨插件开关、卸载插件后调用。
     * 只在状态变了时动语言开关：插件开着、用户自己在「语言与方案」里关了日语，下次启动不会又被打开。
     * 老人包没有日语，不管。
     */
    fun sync(context: Context) {
        val app = context.applicationContext ?: context
        if (com.kingzcheung.xime.BuildConfig.ELDER) return
        val active = installed() && SettingsPreferences.isPluginEnabled(app, ID)
        val prefs = SettingsPreferences.getPrefsPublic(app)
        val was = prefs.getBoolean(KEY_ACTIVE, false)
        if (was == active && prefs.contains(KEY_ACTIVE)) return
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
        if (was == active) return
        scope.launch {
            runCatching {
                val on = InputLanguage.JAPANESE in LanguagePreferences.enabled(app)
                if (active) {
                    if (!on) LanguagePreferences.save(app, InputLanguage.JAPANESE, true)
                    JapanesePack.request(app)
                } else if (on) LanguagePreferences.save(app, InputLanguage.JAPANESE, false)
            }.onFailure { Log.e(TAG, "切换日语失败", it) }
        }
    }

    /** 卸载插件后（[SettingsPreferences.forgetUninstalledPlugin]）：关掉日语，再删掉下载的词库和引擎。 */
    fun onUninstalled(context: Context) {
        val app = context.applicationContext ?: context
        SettingsPreferences.getPrefsPublic(app).edit().putBoolean(KEY_ACTIVE, false).apply()
        scope.launch {
            runCatching {
                if (InputLanguage.JAPANESE in LanguagePreferences.enabled(app)) LanguagePreferences.save(app, InputLanguage.JAPANESE, false)
                JapanesePack.removeDownloads(app)
            }.onFailure { Log.e(TAG, "卸载日语后清理失败", it) }
        }
    }

    /**
     * 在「语言与方案」里打开了日语：插件没装就从随包目录装上（不联网），停用着就启用；都好了就只补下载。
     */
    fun onLanguageEnabled(context: Context) {
        val app = context.applicationContext ?: context
        if (com.kingzcheung.xime.BuildConfig.ELDER) return
        scope.launch {
            when {
                !installed() -> install(app)
                !SettingsPreferences.isPluginEnabled(app, ID) -> SettingsPreferences.setPluginEnabled(app, ID, true)
                else -> JapanesePack.request(app)
            }
        }
    }

    /** 从随包的插件市场目录装上日语插件并启用（不联网）。已经装着的不重装。返回装好没有。 */
    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext ?: context
        if (installed()) {
            if (!SettingsPreferences.isPluginEnabled(app, ID)) SettingsPreferences.setPluginEnabled(app, ID, true)
            return@withContext true
        }
        runCatching {
            val version = XimeIndexSource.bundledPlugins(app).plugins.first { it.id == ID }.resolvedVersion()
                ?: error("随包目录里没有日语插件的版本")
            XimeIndexSource.installMarketPlugin(app, ID, version)
            if (!SettingsPreferences.isPluginEnabled(app, ID)) SettingsPreferences.setPluginEnabled(app, ID, true)
            sync(app)
        }.onFailure { Log.e(TAG, "装日语插件失败", it) }.isSuccess
    }

    /**
     * 启动时调用（插件管理器装好内置插件之后）：升级到插件版时只做一次，开着日语的人自动装上插件，
     * 日语照旧在；轻量版缺的引擎（以前在安装包里）马上补下载。没开日语的不装。之后每次启动对一遍状态。
     */
    suspend fun migrateOnce(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = SettingsPreferences.getPrefsPublic(app)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            if (!com.kingzcheung.xime.BuildConfig.ELDER && !installed() &&
                InputLanguage.JAPANESE in LanguagePreferences.enabled(app)) install(app)
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
        // 上次没下完的（没网、下载失败）不用在这里补：下载排在默认模型队列里，启动和联网时自动再试，
        // 用户在模型管理里删掉的就不再补。
        sync(app)
    }
}
