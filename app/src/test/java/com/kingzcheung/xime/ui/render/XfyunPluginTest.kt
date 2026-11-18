package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.LuaScriptRuntime
import com.kingzcheung.xime.plugin.core.lua.LuaToolPluginAdapter
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.crypto.CryptoHostApiImpl
import com.kingzcheung.xime.ui.settings.PluginConfigFormScreen
import com.kingzcheung.xime.ui.theme.XimeTheme
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w390dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XfyunPluginTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val spark = "https://spark-api-open.xf-yun.com/x2"
    private val its = "https://itrans.xf-yun.com/v1/its"
    private val values = mutableMapOf("apiKey" to "another-provider-key", "sparkPassword" to "sample-password",
        "itsKey" to "sample-key", "itsAppId" to "sample-app", "itsSecret" to "sample-secret")
    private val store = object : PluginConfigStore {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
        override fun keys(): Set<String> = values.keys
        override fun remove(key: String) { values.remove(key) }
    }
    private fun runtime(name: String, dir: File = listOf(File("../plugins/$name"), File("plugins/$name")).first { it.isDirectory },
                        respond: (String, Map<String, String>, JSONObject) -> JSONObject): LuaScriptRuntime {
        val http = object : HttpHostApi {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMillis: Int?): HttpResponse {
                assertEquals("POST", method)
                return HttpResponse(200, body = respond(url, headers, JSONObject(String(body!!))).toString().toByteArray())
            }
            override fun lastError(): String? = null
        }
        return LuaScriptRuntime(name, dir, "main.lua", store, httpHostApi = http, cryptoHostApi = CryptoHostApiImpl())
            .also { assertTrue(it.load()) }
    }

    @Test fun installedPreXfyunPluginsStillUseTheirOriginalCustomApi() {
        val root = listOf(File("../store/plugins/packages"), File("store/plugins/packages")).first { it.isDirectory }
        values["baseUrl"] = "https://compatible.example.test/v1"
        values["model"] = "existing-model"
        for ((name, version) in mapOf("ai-reply" to "0.7.0", "ai-rewrite" to "0.3.2", "translate" to "0.3.0")) {
            val dir = File(rule.activity.cacheDir, name).apply { mkdirs() }
            java.util.zip.ZipFile(File(root, "$name-$version.xipk")).use { zip ->
                zip.getInputStream(zip.getEntry("main.lua")).use { input ->
                    File(dir, "main.lua").outputStream().use { input.copyTo(it) }
                }
            }
            var called = false
            val lua = runtime(name, dir) { url, headers, body ->
                called = true
                assertEquals("https://compatible.example.test/v1/chat/completions", url)
                assertEquals("Bearer another-provider-key", headers["Authorization"])
                assertEquals("existing-model", body.getString("model"))
                val reply = when (name) {
                    "ai-reply" -> """{"candidates":["好呀","可以"]}"""
                    "ai-rewrite" -> "我明天早上八点到公司，你们先开会吧。"
                    else -> "See you tomorrow."
                }
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply))))
            }
            try {
                val id = "com.kingzcheung.xime.plugin.${name.replace('-', '_')}"
                val adapter = LuaToolPluginAdapter(lua, PluginContext(rule.activity.application,
                    PluginInfo(id, name, 0, 1, version, "", "", type = "tool"), configStore = store))
                assertTrue("$name reads its original settings", adapter.isConfigured())
                assertTrue(adapter.getSettingsSchema().any { it.key == "apiKey" })
                assertTrue("$name connection check", lua.call("testConnection").isnil())
                assertTrue("$name used the custom endpoint", called)
            } finally { lua.close() }
        }
    }

    @Test fun sparkTranslationRewriteAndRepliesUseOnlySparkCredentials() {
        values["provider"] = spark
        for (name in listOf("translate", "ai-rewrite", "ai-reply")) {
            var called = false
            val lua = runtime(name) { url, headers, body ->
                called = true
                assertEquals("$spark/chat/completions", url)
                assertEquals("Bearer sample-password", headers["Authorization"])
                assertEquals("spark-x", body.getString("model"))
                assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
                val reply = when (name) {
                    "ai-reply" -> """{"candidates":["好呀","可以"]}"""
                    "ai-rewrite" -> "我明天早上八点到公司，你们先开会吧。"
                    else -> "See you tomorrow."
                }
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply))))
            }
            try {
                val error = lua.call("testConnection")
                assertTrue("$name: $error", error.isnil())
                assertTrue(called)
            } finally { lua.close() }
        }
    }

    @Test fun existingPluginInstancesCheckTheCredentialsOfTheSelectedProvider() {
        for (name in listOf("translate", "ai-rewrite", "ai-reply")) {
            values.remove("provider")
            values.remove("sparkPassword")
            val lua = runtime(name) { _, _, _ -> error("Settings must not contact a service") }
            try {
                val id = "com.kingzcheung.xime.plugin.${name.replace('-', '_')}"
                val adapter = LuaToolPluginAdapter(lua, PluginContext(rule.activity.application,
                    PluginInfo(id, name, 0, 1, "1", "", "", type = "tool"), configStore = store))
                // Older hosts cache this one list and only understand the original "required" property.
                val legacyFields = LuaScriptRuntime.tableToList(lua.call("getSettingsSchema"))
                    .associateBy { it.get("key").tojstring() }
                for (key in listOf("apiKey", "sparkPassword") + if (name == "translate") listOf("itsKey", "itsSecret", "itsAppId") else emptyList()) {
                    assertTrue("$name exposes $key on older hosts", key in legacyFields)
                    assertFalse(legacyFields.getValue(key).get("required").toboolean())
                }
                assertTrue("$name reads the existing custom API key", adapter.isConfigured())
                values["provider"] = spark
                assertFalse("$name requires its separate Spark password", adapter.isConfigured())
                values["sparkPassword"] = "sample-password"
                assertTrue(adapter.isConfigured())
                values["provider"] = "自定义兼容接口"
                assertTrue(adapter.isConfigured())
                assertEquals("another-provider-key", values["apiKey"])
            } finally { lua.close() }
        }
    }

    @Test fun translateSignsTheSelectedLanguagePairAndDecodesTheTranslation() {
        values["provider"] = its
        // 自动检测是和界面语言互译（0.4.2）；这里指定原文语言，验证签名和所选的语言对
        values["sourceLang"] = "日文"
        values["targetLang"] = "韩文"
        val app = rule.activity.application
        val id = "com.kingzcheung.xime.plugin.translate"
        val lua = runtime("translate") { url, headers, body ->
            val signed = url.toHttpUrl()
            assertEquals(its, url.substringBefore('?'))
            assertNull(headers["Authorization"])
            val date = signed.queryParameter("date")!!
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("sample-secret".toByteArray(), "HmacSHA256")) }
            val sig = Base64.getEncoder().encodeToString(mac.doFinal("host: itrans.xf-yun.com\ndate: $date\nPOST /v1/its HTTP/1.1".toByteArray()))
            val auth = String(Base64.getDecoder().decode(signed.queryParameter("authorization")))
            assertTrue(auth.contains("api_key=\"sample-key\"") && auth.contains("signature=\"$sig\""))
            assertEquals("sample-app", body.getJSONObject("header").getString("app_id"))
            val languages = body.getJSONObject("parameter").getJSONObject("its")
            assertEquals("ja", languages.getString("from"))
            assertEquals("ko", languages.getString("to"))
            assertEquals("ありがとう", String(Base64.getDecoder().decode(body.getJSONObject("payload").getJSONObject("input_data").getString("text"))))
            val encoded = Base64.getEncoder().encodeToString("""{"trans_result":{"dst":"감사합니다"}}""".toByteArray())
            JSONObject().put("header", JSONObject().put("code", 0)).put("payload", JSONObject().put("result", JSONObject().put("text", encoded)))
        }
        try {
            val adapter = LuaToolPluginAdapter(lua, PluginContext(app, PluginInfo(id, "翻译", 0, 1, "0.4.0", "", "", type = "tool"), configStore = store))
            adapter.onPanelInput("ありがとう")
            adapter.onPanelAction("generate")
            assertEquals("감사합니다", adapter.getPanelState("").items.single().text)
            val schema = adapter.getSettingsSchema()
            app.getSharedPreferences("plugin_cfg_$id", 0).edit().putString("provider", its).apply()
            com.kingzcheung.xime.plugin.core.runtime.PluginManager.initialize(app)
            rule.setContent { XimeTheme(darkTheme = false) { PluginConfigFormScreen(id, adapter, "翻译", schema, {}) } }
            rule.onNodeWithText("APISecret").assertExists()
            rule.onNodeWithText("API 密钥").assertDoesNotExist()
            rule.onNodeWithText("星火 X2 APIPassword").assertDoesNotExist()
            assertEquals("讯飞机器翻译", schema.first { it.key == "provider" }.optionLabels[its])
            System.getenv("CYIME_RENDER_OUT")?.let { directory -> rule.runOnIdle {
                val view = rule.activity.window.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bmp))
                File(directory, "xfyun-translation-settings.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } }
            // Robolectric has no Android Keystore. Use a temporary AES key for actual encrypted form writes.
            val keyField = com.kingzcheung.xime.plugin.SecureValueCipher::class.java.getDeclaredField("cachedKey")
            keyField.isAccessible = true
            val originalKey = keyField.get(null)
            try {
                keyField.set(null, javax.crypto.KeyGenerator.getInstance("AES").generateKey())
                rule.onNodeWithText("讯飞机器翻译").performClick()
                rule.onNodeWithText("自定义兼容接口").performClick()
                rule.onNodeWithText("接口地址").assertExists()
                rule.onNodeWithText("APISecret").assertDoesNotExist()
                rule.onNodeWithText("自定义兼容接口").performClick()
                rule.onNodeWithText("讯飞星火 X2").performClick()
                rule.onNodeWithText("星火 X2 APIPassword").assertExists()
                rule.onNodeWithText("API 密钥").assertDoesNotExist()
            } finally { keyField.set(null, originalKey) }
        } finally { lua.close() }
    }
}
