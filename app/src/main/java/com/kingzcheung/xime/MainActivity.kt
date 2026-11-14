package com.kingzcheung.xime

import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.SettingsScreen
import com.kingzcheung.xime.ui.settings.SettingsNavigationRequest
import com.kingzcheung.xime.ui.settings.SetupWizardScreen
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var settingsNavigationRequest by mutableStateOf<SettingsNavigationRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        // 键盘菜单「设置」带的是首页（main），回到设置首页；长按跳转带具体页面。没带页面的不动导航。
        // 插件授权页：冷启动走 initialPluginId，已打开时直接进该插件的详情页；面板里的「设置」直接进设置页。
        (intent.getStringExtra("open_plugin_id")?.let { pluginLaunchRoute(it, intent.getBooleanExtra(EXTRA_OPEN_PLUGIN_SETTINGS, false)) }
            ?: intent.getStringExtra("open_fragment"))?.let { route ->
            settingsNavigationRequest = SettingsNavigationRequest(
                route, (settingsNavigationRequest?.id ?: 0) + 1,
            )
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
        /** A real permission dialog takes the user longer than this to answer. */
        private const val NO_DIALOG_MS = 500L

        /** 插件面板「设置」：直接进插件设置页，不经过详情页（11-03 要点两次设置）。 */
        const val EXTRA_OPEN_PLUGIN_SETTINGS = "open_plugin_settings"

        /** 打开插件时落到哪一页：一个插件一页，联网授权也在设置页；只有已下架的进详情页。[settings] 留着兼容旧调用。 */
        @Suppress("UNUSED_PARAMETER")
        fun pluginLaunchRoute(pluginId: String, settings: Boolean): String =
            com.kingzcheung.xime.ui.settings.pluginPageRoute(pluginId)

        /** 构造跳转到指定插件那一页的 Intent（联网被拦时，IME 等后台场景调用）。 */
        fun buildPluginAuthIntent(context: android.content.Context, pluginId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra("open_fragment", "plugins")
                putExtra("open_plugin_id", pluginId)
                // 设置已打开时只带 NEW_TASK 不会调 onNewIntent，停在旧页面；和 settingsActivityIntent 一样复用。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }

        /** 构造直接打开指定插件设置页的 Intent（键盘里插件面板的「设置」）。 */
        fun buildPluginSettingsIntent(context: android.content.Context, pluginId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra("open_fragment", "plugins")
                putExtra("open_plugin_id", pluginId)
                putExtra(EXTRA_OPEN_PLUGIN_SETTINGS, true)
                // 设置已打开时只带 NEW_TASK 不会调 onNewIntent，停在旧页面；和 settingsActivityIntent 一样复用。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    override fun onDestroy() {
        prewarmScope.cancel()
        super.onDestroy()
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Denied at once with no dialog: refused before with "don't ask again" (or twice on Android 11+). The
        // system will never ask again, so the only way left is the app's settings page; the user is sent there.
        val noDialog = !isGranted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) &&
            android.os.SystemClock.uptimeMillis() - permissionAskedAt < NO_DIALOG_MS
        if (noDialog) {
            PermissionHelper.onSentToAppSettings()
            Toast.makeText(this, "麦克风权限之前被拒绝过，请在设置的「权限」里允许麦克风", Toast.LENGTH_LONG).show()
            try {
                // Its own task: finishAndRemoveTask below must not take the settings page with it.
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { Log.w(TAG, "App settings page unavailable", e) }
        } else {
            PermissionHelper.onRecordAudioResult(isGranted)
            if (isGranted) {
                Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "麦克风权限被拒绝，无法使用语音输入", Toast.LENGTH_SHORT).show()
            }
        }
        finishAndRemoveTask()
    }
    /** When the permission request started; a denial faster than this came without a dialog. */
    private var permissionAskedAt = 0L
    
    private val prewarmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun prewarmKeyboardConfig() {
        if (RimeEngine.isInitialized()) return
        prewarmScope.launch {
            try {
                KeysConfigHelper.loadConfig(this@MainActivity)
            } catch (e: Exception) {
                Log.w(TAG, "Keyboard config pre-warm failed, will load on demand", e)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // The Application owns engine preparation, including installation and deployment.
        prewarmKeyboardConfig()

        handleSharedIntent(intent)
        
        val requestPermission = intent?.getStringExtra("request_permission")
        if (requestPermission == PermissionHelper.PERMISSION_RECORD_AUDIO) {
            if (!PermissionHelper.hasRecordAudioPermission(this)) {
                permissionAskedAt = android.os.SystemClock.uptimeMillis()
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                PermissionHelper.onRecordAudioResult(true)
                Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
            return
        }
        
        val openFragment = intent?.getStringExtra("open_fragment")
        val openPluginId = intent?.getStringExtra("open_plugin_id")
        val openPluginSettings = intent?.getBooleanExtra(EXTRA_OPEN_PLUGIN_SETTINGS, false) == true

        com.kingzcheung.xime.model.DefaultModelInstaller.start(applicationContext)

        setContent {
            val context = this
            val setupCompleted = SettingsPreferences.isSetupCompleted(context)
            var showWizard by remember { mutableStateOf(!setupCompleted) }
            var showGuide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            var wizardToSettings by remember { mutableStateOf(false) }
            var darkMode by remember { mutableIntStateOf(SettingsPreferences.getEffectiveDarkMode(context)) }
            var keyboardTheme by remember { mutableStateOf(SettingsPreferences.getKeyboardTheme(context)) }

            // Theme changes can also come from the IME while this activity stays
            // open. Its existing composition must follow the same saved choice.
            DisposableEffect(context) {
                val preferences = SettingsPreferences.getPrefsPublic(context)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == "keyboard_theme" || key == "dark_mode") {
                        darkMode = SettingsPreferences.getEffectiveDarkMode(context)
                        keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                    }
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            
            val isDarkTheme = when (darkMode) {
                2 -> isSystemInDarkTheme()
                1 -> true
                else -> false
            }

            XimeTheme(darkTheme = isDarkTheme, themeId = keyboardTheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    DisposableEffect(isDarkTheme) {
                        val window = (view.context as? ComponentActivity)?.window
                        if (window != null) {
                            val controller = WindowInsetsControllerCompat(window, view)
                            controller.isAppearanceLightStatusBars = !isDarkTheme
                            controller.isAppearanceLightNavigationBars = !isDarkTheme
                        }
                        onDispose { }
                    }
                }

                val density = LocalDensity.current
                val navBarHeight = with(density) {
                    WindowInsets.navigationBars.getBottom(density).toDp()
                }
                val scrimHeight = if (navBarHeight > 0.dp) navBarHeight else 32.dp

                Box(modifier = Modifier.fillMaxSize()) {
                    com.kingzcheung.xime.ui.settings.SettingsWidthLimit {
                    if (showWizard) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SetupWizardScreen(
                                visible = !wizardToSettings,
                                onNavigateToSchemaSettings = { wizardToSettings = true },
                                onCompleted = {
                                    showWizard = false
                                    // 老人包按规格固定配置，不在首次设置后弹出推荐配置。
                                    showGuide = !BuildConfig.ELDER && !com.kingzcheung.xime.model.SetupGuide.isShown(context)
                                }
                            )
                            if (wizardToSettings) {
                                SettingsScreen(
                                    initialRoute = "schema",
                                    onThemeChanged = {
                                        darkMode = SettingsPreferences.getEffectiveDarkMode(context)
                                        keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                                    },
                                    onWizardBack = { wizardToSettings = false }
                                )
                            }
                        }
                    } else if (showGuide) {
                        com.kingzcheung.xime.ui.settings.SetupGuideScreen(firstRun = true, onDone = { showGuide = false },
                            // 「更多配置」里的跳转：收起引导，在设置里打开那一页（返回落到设置首页）。
                            onOpenRoute = { route ->
                                showGuide = false
                                settingsNavigationRequest = SettingsNavigationRequest(route, (settingsNavigationRequest?.id ?: 0) + 1)
                            })
                    } else {
                        SettingsScreen(
                            initialRoute = openFragment,
                            initialPluginId = openPluginId,
                            initialPluginSettings = openPluginSettings,
                            navigationRequest = settingsNavigationRequest,
                            onThemeChanged = {
                                darkMode = SettingsPreferences.getEffectiveDarkMode(context)
                                keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                            }
                        )
                    }
                    }

                    // 装的不是作者签名的包就提示（每次打开设置一次）。
                    com.kingzcheung.xime.security.InstallIntegrityWarning()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scrimHeight)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (isDarkTheme) {
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f))
                                    } else {
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f))
                                    }
                                )
                            )
                    )
                }
            }
        }
    }
    
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                val mime = intent.type
                if (uri != null) {
                    if (mime?.startsWith("image/") == true) {
                        importThemeImage(uri, mime)
                    } else {
                        importSchema(uri)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uris != null) {
                    for (uri in uris) {
                        importSchema(uri)
                    }
                }
            }
        }
    }
    
    private fun importSchema(uri: android.net.Uri) {
        prewarmScope.launch {
            when (val result = ImportManager.import(this@MainActivity, uri)) {
                is ImportManager.ImportResult.Content -> {
                    launch(Dispatchers.Main) {
                        // 同名文件确认在「已安装的方案」页；装好的方案由 ImportManager 自动应用（#340）。
                        val msg = if (result.pendingPackageId != null) "${result.message}：到「已安装的方案」页确认"
                            else result.message
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                is ImportManager.ImportResult.Plugin -> {
                    com.kingzcheung.xime.plugin.core.runtime.PluginManager.loadEnabledPlugins()
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "插件「${result.pluginInfo?.name}」安装成功",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                is ImportManager.ImportResult.Failed -> {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "导入失败：${result.reason}", Toast.LENGTH_LONG).show()
                    }
                }
                is ImportManager.ImportResult.Unsupported -> {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "不支持的文件类型", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 分享图片到 Xime：保存到 rime/themes/ 并提示用户如何引用。 */
    private fun importThemeImage(uri: android.net.Uri, mimeType: String) {
        prewarmScope.launch {
            val result = withContext(Dispatchers.IO) {
                val themesDir = File(SchemaManager.getRimeDir(this@MainActivity), "themes")
                themesDir.mkdirs()
                val ext = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val name = "custom_${System.currentTimeMillis()}.$ext"
                val target = File(themesDir, name)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    name
                } catch (e: Exception) {
                    Log.w(TAG, "importThemeImage failed", e)
                    null
                }
            }
            launch(Dispatchers.Main) {
                if (result == null) {
                    Toast.makeText(this@MainActivity, "背景图导入失败", Toast.LENGTH_LONG).show()
                } else {
                    copyThemeConfigTemplate(result)
                    Toast.makeText(
                        this@MainActivity,
                        "背景图已导入 rime/themes/$result，配置模板已复制到剪贴板",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 生成可在 xime.custom.yaml 中使用的主题配置模板并复制到剪贴板。 */
    private fun copyThemeConfigTemplate(fileName: String) {
        val schemeId = fileName.substringBeforeLast('.')
        val template = """
            color_schemes:
              $schemeId:
                name: "自定义背景 ${schemeId.removePrefix("custom_")}"
                keyboard_background:
                  type: image
                  src: "themes/$fileName"
                  fit: cover
                  overlay_alpha: 0.15
                  overlay_alpha_dark: 0.30
                key_bg_color: 0x8cffffff
                key_bg_color_dark: 0x66000000
                key_text_color: 0x232323
                key_text_color_dark: 0xf2f2f2
                candidate_text_color: 0x232323
                candidate_text_color_dark: 0xf2f2f2
                candidate_selected_text_color: 0xffffff
                candidate_selected_text_color_dark: 0xffffff
        """.trimIndent()
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("xime theme", template))
    }
}
