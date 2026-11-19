package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.http.SseHostApi
import com.kingzcheung.xime.plugin.core.lua.http.SseHostListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 自定义请求头（11-16 用户：AI 改写、AI 回复要能加自定义请求头）：AI 改写、AI 回复、翻译的自定义兼容接口
 * 都按设置里「名字: 值」一行一个加上；同名替换默认的，值留空就不发；没填时和以前完全一样；星火不加。
 */
class LuaCustomHeadersTest {
    private lateinit var savedLocale: Locale
    @Before fun chineseInterface() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.SIMPLIFIED_CHINESE) }
    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class RecordingHttp(private val content: String) : HttpHostApi {
        val headers = mutableListOf<Map<String, String>>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMillis: Int?): HttpResponse {
            this.headers += headers
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
            return HttpResponse(status = 200, body = """{"choices":[{"message":{"content":"$escaped"}}]}""".toByteArray())
        }
        override fun lastError(): String? = null
    }

    private class RecordingSse : SseHostApi {
        val headers = mutableListOf<Map<String, String>>()
        override fun connect(url: String, headers: Map<String, String>, listener: SseHostListener,
                             timeoutMillis: Int?, method: String, body: ByteArray?): Int {
            this.headers += headers
            return this.headers.size - 1
        }
        override fun close(sessionId: Int) {}
        override fun lastError(): String? = null
    }

    private val plugins = mapOf(
        "ai-rewrite" to ("com.kingzcheung.xime.plugin.ai_rewrite" to "我明天早上八点到公司，你们先开会吧。"),
        "ai-reply" to ("com.kingzcheung.xime.plugin.ai_reply" to """{"candidates": ["好的，我准时到"]}"""),
        "translate" to ("com.kingzcheung.xime.plugin.translate" to "Hello, see you tomorrow."),
    )

    /** 走一遍「测试接口」（和生成同一条请求），返回发出去的请求头。 */
    private fun headersSent(name: String, configure: (PluginConfigStore) -> Unit): Map<String, String> {
        val (id, content) = plugins.getValue(name)
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key-000000")
        store.set("baseUrl", "https://api.example.com/v1")
        store.set("model", "test-model")
        configure(store)
        val http = RecordingHttp(content)
        val runtime = LuaScriptRuntime(id, File("../plugins/$name"), "main.lua", store, httpHostApi = http)
        assertTrue("$name main.lua 应能加载", runtime.load())
        runtime.call("testConnection")
        assertTrue("$name 应发出请求", http.headers.isNotEmpty())
        return http.headers.last()
    }

    @Test fun `三个插件都加上自定义请求头`() {
        for (name in plugins.keys) {
            val headers = headersSent(name) { it.set("customHeaders", "X-Title: CyIME\r\nHTTP-Referer: https://example.com/app") }
            assertEquals(name, "CyIME", headers["X-Title"])
            assertEquals(name, "https://example.com/app", headers["HTTP-Referer"])
            assertEquals(name, "Bearer test-key-000000", headers["Authorization"])
            assertEquals(name, "application/json", headers["Content-Type"])
        }
    }

    @Test fun `同名替换默认的，值留空就不发，注释和不成形的行不算`() {
        for (name in plugins.keys) {
            val headers = headersSent(name) {
                it.set("customHeaders", "authorization:\napi-key: test-key-000000\n# X-Note: 注释\n不成形的一行\n content-type : application/json; charset=utf-8 ")
            }
            assertFalse(name, headers.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertEquals(name, "test-key-000000", headers["api-key"])
            assertNull(name, headers["X-Note"])
            assertEquals(name, listOf("application/json; charset=utf-8"),
                headers.filterKeys { it.equals("Content-Type", ignoreCase = true) }.values.toList())
        }
    }

    @Test fun `没填时请求头和以前一样`() {
        for (name in plugins.keys) {
            assertEquals(name, mapOf("Content-Type" to "application/json", "Authorization" to "Bearer test-key-000000"),
                headersSent(name) {})
        }
    }

    @Test fun `选了讯飞星火不加自定义请求头`() {
        for (name in plugins.keys) {
            val headers = headersSent(name) {
                it.set("provider", "https://spark-api-open.xf-yun.com/x2")
                it.set("sparkPassword", "spark-password")
                it.set("customHeaders", "X-Title: CyIME")
            }
            assertNull(name, headers["X-Title"])
        }
    }

    @Test fun `翻译栏边收边出的流式请求也带上`() {
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key-000000")
        store.set("customHeaders", "X-Title: CyIME")
        val sse = RecordingSse()
        val runtime = LuaScriptRuntime("com.kingzcheung.xime.plugin.translate", File("../plugins/translate"), "main.lua", store,
            httpHostApi = RecordingHttp("Hello."), sseHostApi = sse)
        assertTrue(runtime.load())
        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.translate", name = "翻译", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.1.0",
                path = File("../plugins/translate", "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))
        adapter.getPanelState("")
        adapter.onPanelInput("你好，明天见")
        adapter.onPanelAction("generate")
        assertEquals("CyIME", sse.headers.single()["X-Title"])
    }
}
