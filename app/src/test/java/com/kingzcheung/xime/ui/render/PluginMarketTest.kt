package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.XimeIndexSource
import com.kingzcheung.xime.ui.settings.SettingsScreen
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w360dp-h680dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginMarketTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        File(app.filesDir, "rime/xime.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("xime_index:\n  base_urls: []\n")
        }
        KeyboardThemes.initFromConfig(app)
        PluginManager.initialize(app)
        // PluginManager 是单例：前面别的测试装过的插件还在列表里，这里先卸干净，「第一次装」才成立。
        runBlocking { for (plugin in XimeIndexSource.bundledPlugins(app).plugins) PluginManager.installerManager.uninstallPlugin(plugin.id) }
    }

    @After fun removeTestInstallations() = runBlocking {
        for (plugin in XimeIndexSource.bundledPlugins(app).plugins) {
            PluginManager.installerManager.uninstallPlugin(plugin.id)
        }
    }

    @Test fun browseFromManagementAndInstallWithoutNetwork() {
        val plugin = XimeIndexSource.bundledPlugins(app).plugins.first { it.name == "AI 改写" }
        rule.setContent { XimeTheme { SettingsScreen(initialRoute = "plugins") } }
        rule.onNodeWithContentDescription("从文件安装插件").assertIsDisplayed()
        rule.onNodeWithContentDescription("插件市场").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(plugin.name).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(plugin.name).assertIsDisplayed()
        System.getenv("CYIME_RENDER_OUT")?.let { dir ->
            val view = rule.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { view.draw(Canvas(image)) }
            File(dir, "plugin-market.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        rule.onNodeWithText(plugin.name).performClick()
        rule.onNodeWithText("安装此版本").performClick()
        // 装进列表之后才在后台线程里拨开关：等两件事都做完再看，整套跑时机器忙也不会抢在前面。
        rule.waitUntil(10_000) {
            PluginManager.getAllInstallPlugins().any { it.id == plugin.id } && SettingsPreferences.isPluginEnabled(app, plugin.id)
        }
        val info = PluginManager.getAllInstallPlugins().single { it.id == plugin.id }
        // 自家市场装的是官方插件（11-09「怎么是第三方」）。
        assertEquals(TrustLevel.TRUSTED, info.trustLevel)
        // 市场第一次装工具类插件直接启用（11-02 AI 插件找得到）；授权的网址仍要用户自己点。
        assertTrue(SettingsPreferences.isPluginEnabled(app, plugin.id))
        assertTrue(SettingsPreferences.getPluginAuthorizedHosts(app, plugin.id).isEmpty())
        rule.onNodeWithTag("market-installed").performClick()
        rule.onNodeWithText("插件管理").assertIsDisplayed()
    }

    @Test fun wrongIdentityOrBrokenUpdateKeepsInstalledFiles() = runBlocking {
        val catalog = XimeIndexSource.bundledPlugins(app).plugins
        for (entry in catalog) XimeIndexSource.installMarketPlugin(app, entry.id, entry.resolvedVersion()!!)
        val plugin = catalog.single { it.name == "AI 回复" }
        val version = plugin.resolvedVersion()!!
        val path = File(PluginManager.getAllInstallPlugins().single { it.id == plugin.id }.path)
        val original = path.readBytes()
        val bad = File(app.cacheDir, "broken.xipk")
        ZipOutputStream(bad.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.yaml"))
            it.write("id: ${plugin.id}\nversion: 9.0.0\n".toByteArray())
        }
        for (id in listOf("wrong.id", plugin.id)) {
            val result = PluginManager.installerManager.installPlugin(bad, true, PluginSource.REMOTE, id, "9.0.0")
            assertTrue(result is InstallerManager.InstallResult.Failure)
            assertArrayEquals(original, path.readBytes())
            assertEquals(version.version, PluginManager.getAllInstallPlugins().single { it.id == plugin.id }.version)
        }
    }
}
