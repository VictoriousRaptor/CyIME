package com.kingzcheung.xime

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XimeApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(16 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .build()
    }
    
    companion object {
        private const val TAG = "XimeApplication"
    }
    
    private val applicationScope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()

        FileLogger.fileHeader = com.kingzcheung.xime.platform.AndroidPlatform.diagnosticHeader(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        )
        FileLogger.init(this)
        // 语音、联想、文字识别进程也记崩溃：以前只有主进程登记，模型进程闪退时日志里什么都没有。
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("CrashHandler", "Uncaught exception on thread: ${thread.name}", throwable)
                FileLogger.flushNow()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        // Model services run in separate processes. They must not copy/deploy the
        // same Rime directory or load the keyboard/plugin runtime a second time.
        val processName = getProcessName()
        if (processName != packageName) return
        SettingsPreferences.initializeIncognitoForVersion(this, BuildConfig.VERSION_CODE)
        // 先于任何下载、导入和词库准备列出上次中断的残留，后台删除。
        com.kingzcheung.xime.settings.StorageHousekeeping.sweepAtStartup(this, applicationScope)
        com.kingzcheung.xime.model.DeviceDefaults.initialize(this)
        com.kingzcheung.xime.settings.LanguagePreferences.initialize(this)
        com.kingzcheung.xime.ui.theme.VisualStyles.current = SettingsPreferences.getVisualStyle(this)
        com.kingzcheung.xime.ui.theme.KeyShapes.current = SettingsPreferences.getKeyShape(this)
        com.kingzcheung.xime.ui.theme.IconAppearance.reload(this)
        try {
            com.kingzcheung.xime.ui.theme.LauncherIcons.apply(this, com.kingzcheung.xime.ui.theme.IconAppearance.effective)
        } catch (e: RuntimeException) {
            // A launcher failure must not prevent typing. The explicit settings action reports it too.
            FileLogger.e(TAG, "Unable to synchronize launcher icon", e)
        }
        AppFonts.initialize(this)

        val isDebug = BuildConfig.DEBUG
        PluginManager.configStoreFactory =
            PluginManager.PluginConfigStoreFactory { app, pluginId ->
                PluginConfigStoreImpl(app, pluginId)
            }
        PluginManager.wsHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.ws.WsHostApiImpl(this, pluginId)
        }
        PluginManager.httpHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.HttpHostApiImpl(this, pluginId)
        }
        PluginManager.sseHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.SseHostApiImpl(this, pluginId)
        }
        PluginManager.cryptoHostApiFactory = {
            com.kingzcheung.xime.plugin.crypto.CryptoHostApiImpl()
        }
        PluginManager.quickSendHostApiFactory = { _ ->
            com.kingzcheung.xime.plugin.QuickSendHostApiImpl()
        }
        PluginManager.clipboardHostApiFactory = { _ ->
            com.kingzcheung.xime.plugin.ClipboardHostApiImpl(this)
        }
        PluginManager.offlineTranslateHostApiFactory = { _ ->
            com.kingzcheung.xime.translate.OfflineTranslateHostApiImpl(this)
        }
        com.kingzcheung.xime.clipboard.sync.WindowsDevices.initialize(this)
        PluginManager.initialize(this) {
            // Available offline in Release too. Preserve existing installs and configuration.
            if (!PluginManager.installPluginFromAssets(
                "builtin-plugins/windows-clipboard.xipk", forceOverwrite = false
            )) Log.e("XimeApplication", "内置 Windows 剪贴板同步初始化失败")
            if (isDebug) {
                PluginManager.installPluginsFromAssetsForDebug("plugins")
            }
            
            PluginManager.loadEnabledPlugins()
            // 字幕是插件：用过字幕的老用户升级时自动装上；每次启动按插件状态放出或收起字幕的入口。
            com.kingzcheung.xime.ui.caption.CaptionPlugin.migrateOnce(this@XimeApplication)
            // 日语也是插件（11-11）：开着日语的老用户升级时自动装上，轻量版缺的引擎马上补下载。
            com.kingzcheung.xime.settings.JapanesePlugin.migrateOnce(this@XimeApplication)
        }
        
        ExtensionManager.initialize(this)

        // 从 xime.yaml 加载配色方案
        KeyboardThemes.initFromConfig(this)

        // 从 xime.yaml 的 style 读取默认主题和显示模式
        // color_scheme 可指定 dynamic（Material You 动态配色，仅 Android 12+，
        // 低版本 getThemeById 自动回退到内置配色）
        SettingsPreferences.defaultKeyboardTheme = KeysConfigHelper.loadDefaultThemeId(this)
        SettingsPreferences.defaultDarkMode = KeysConfigHelper.loadDefaultDarkMode(this)
        SettingsPreferences.applyForkThemeDefaults(this)
        SettingsPreferences.migrateSttUseLocalOnce(this)

        // 初始化模型运行时（内存管理 + 生命周期）
        ModelRuntime.attach(this)

        com.kingzcheung.xime.model.DefaultModelInstaller.start(this)
        preInitializeRimeEngine()
    }
    
    private fun preInitializeRimeEngine() {
        if (RimeEngine.isInitialized()) {
            return
        }
        
        applicationScope.launch {
            try {
                RimeConfigHelper.prepareAutomatically(this@XimeApplication)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-initialize Rime engine", e)
            }
        }
    }
}
