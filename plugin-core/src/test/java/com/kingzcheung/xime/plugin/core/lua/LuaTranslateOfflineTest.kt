package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.http.SseHostApi
import com.kingzcheung.xime.plugin.core.lua.http.SseHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 翻译插件的离线翻译（host.translate）：没填 API 密钥用离线（中英互译），填了 API 照旧在线，
 * 连不上接口时这一句改用离线；模型没下载时栏左边是「下载」。真实 Lua 运行时 + 假宿主。
 */
class LuaTranslateOfflineTest {

    private class Store : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class FakeOffline : OfflineTranslateHostApi {
        val states = HashMap<String, String>()
        val downloads = mutableListOf<String>()
        val requests = mutableListOf<Triple<String, String, OfflineTranslateListener>>()
        val cancelled = mutableListOf<Int>()
        /** 不为 null 时在 translate() 里当场回调（模拟比 run 返回还快的结果）。 */
        var immediate: String? = null
        override fun status(from: String, to: String): OfflineTranslateStatus {
            val state = states["$from-$to"] ?: OfflineTranslateHostApi.STATE_MISSING
            return OfflineTranslateStatus(state, if (state == OfflineTranslateHostApi.STATE_DOWNLOADING) 40 else -1, 52)
        }
        override fun download(from: String, to: String): String? {
            downloads += "$from-$to"
            states["$from-$to"] = OfflineTranslateHostApi.STATE_DOWNLOADING
            return null
        }
        override fun translate(text: String, from: String, to: String, listener: OfflineTranslateListener): Int {
            requests += Triple(text, "$from-$to", listener)
            immediate?.let { listener.onDone(it) }
            return requests.size - 1
        }
        override fun cancel(id: Int) { cancelled += id }
    }

    /** 连不上的接口：流式请求排不上（-1）。 */
    private class DownSse : SseHostApi {
        override fun connect(url: String, headers: Map<String, String>, listener: SseHostListener,
                             timeoutMillis: Int?, method: String, body: ByteArray?) = -1
        override fun close(sessionId: Int) {}
        override fun lastError(): String = "Unable to resolve host"
    }

    private class NoHttp : HttpHostApi {
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMillis: Int?): HttpResponse? = null
        override fun lastError(): String? = null
    }

    private fun setup(key: String? = null, offline: FakeOffline = FakeOffline()): Pair<LuaToolPluginAdapter, Store> {
        val dir = File("../plugins/translate")
        val store = Store()
        key?.let { store.set("apiKey", it) }
        val runtime = LuaScriptRuntime("com.kingzcheung.xime.plugin.translate", dir, "main.lua", store,
            httpHostApi = NoHttp(), sseHostApi = DownSse(), offlineTranslateHostApi = offline)
        assertTrue(runtime.load())
        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.translate", name = "翻译", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.2.0",
                path = File(dir, "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))
        return adapter to store
    }

    private fun LuaToolPluginAdapter.button() = getPanelState("").ui!!.first { it.type == UiNodeType.BUTTON }
    private fun LuaToolPluginAdapter.hint() = getPanelState("").ui!!.first { it.type == UiNodeType.TEXT }.value!!
    private fun LuaToolPluginAdapter.generate(text: String) { onPanelInput(text); onPanelAction("generate") }

    /** 回调经宿主执行线程排队交付：等到不再 loading。 */
    private fun LuaToolPluginAdapter.awaitIdle() {
        val deadline = System.currentTimeMillis() + 2_000
        while (getPanelState("").loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `没填密钥没下模型：圆钮是下载，只下当前方向`() {
        val offline = FakeOffline()
        val (adapter, _) = setup(offline = offline)
        assertEquals("download", adapter.button().key)
        assertTrue(adapter.hint().contains("52 MB"))
        adapter.generate("你好")
        assertTrue(offline.requests.isEmpty())
        adapter.onPanelAction("download")
        assertEquals(listOf("zh-en"), offline.downloads)
        assertTrue(adapter.hint().contains("40%"))
    }

    @Test
    fun `模型在：中文离线译成英文，英文原样`() {
        val offline = FakeOffline().apply { states["zh-en"] = OfflineTranslateHostApi.STATE_READY }
        val (adapter, _) = setup(offline = offline)
        assertEquals("lang", adapter.button().key)
        adapter.generate("你好，明天见")
        assertEquals("zh-en", offline.requests.single().second)
        assertTrue(adapter.getPanelState("").loading)
        offline.requests.single().third.onDone("“Hello, see you tomorrow.”")
        adapter.awaitIdle()
        assertEquals("离线译文不剥引号", "“Hello, see you tomorrow.”", adapter.getPanelState("").items.single().text)
        adapter.generate("hello there")
        assertEquals(1, offline.requests.size)
        assertEquals("hello there", adapter.getPanelState("").items.single().text)
    }

    @Test
    fun `结果比 run 返回还快也能收到`() {
        val offline = FakeOffline().apply { states["zh-en"] = OfflineTranslateHostApi.STATE_READY; immediate = "Thanks." }
        val (adapter, _) = setup(offline = offline)
        adapter.generate("谢谢")
        val state = adapter.getPanelState("")
        assertFalse(state.loading)
        assertEquals("Thanks.", state.items.single().text)
    }

    @Test
    fun `新的一句取消旧请求，旧结果不写`() {
        val offline = FakeOffline().apply { states["zh-en"] = OfflineTranslateHostApi.STATE_READY }
        val (adapter, _) = setup(offline = offline)
        adapter.generate("第一句")
        adapter.generate("第二句")
        assertEquals(listOf(0), offline.cancelled)
        offline.requests[0].third.onDone("STALE")
        offline.requests[1].third.onDone("Second.")
        adapter.awaitIdle()
        assertEquals("Second.", adapter.getPanelState("").items.single().text)
    }

    @Test
    fun `日文没有离线：提示要填 API`() {
        val (adapter, store) = setup()
        adapter.onPanelAction("lang")
        assertEquals("日文", store.get("targetLang"))
        assertTrue(adapter.hint().contains("中英互译"))
    }

    @Test
    fun `填了 API 但连不上：这一句改用离线`() {
        val offline = FakeOffline().apply { states["zh-en"] = OfflineTranslateHostApi.STATE_READY }
        val (adapter, _) = setup(key = "sk-test", offline = offline)
        adapter.generate("没网的时候")
        assertEquals("没网的时候", offline.requests.single().first)
        offline.requests.single().third.onDone("When offline")
        adapter.awaitIdle()
        assertEquals("When offline", adapter.getPanelState("").items.single().text)
        assertTrue(adapter.hint().contains("离线"))
    }

    @Test
    fun `填了 API、离线模型没下：照旧报连不上`() {
        val offline = FakeOffline()
        val (adapter, _) = setup(key = "sk-test", offline = offline)
        adapter.generate("你好")
        assertTrue(offline.requests.isEmpty())
        assertTrue(adapter.hint().contains("连不上接口"))
    }

    @Test
    fun `设置页：密钥不是必填，有下载离线模型按钮`() {
        val offline = FakeOffline()
        val (adapter, _) = setup(offline = offline)
        val schema = adapter.getSettingsSchema()
        assertFalse(schema.first { it.key == "apiKey" }.required)
        assertEquals(UiNodeType.BUTTON, schema.first { it.key == "downloadOffline" }.type)
        val message = kotlinx.coroutines.runBlocking { adapter.onAction("downloadOffline") }
        assertEquals(listOf("zh-en", "en-zh"), offline.downloads)
        assertTrue(message!!.contains("中译英、英译中"))
        offline.states["zh-en"] = OfflineTranslateHostApi.STATE_READY
        offline.states["en-zh"] = OfflineTranslateHostApi.STATE_READY
        assertNull(kotlinx.coroutines.runBlocking { adapter.onAction("downloadOffline") })
    }

    @Test
    fun `指定了源语言：离线按指定方向翻，不是中英就提示要填接口`() {
        val offline = FakeOffline().apply {
            states["en-zh"] = OfflineTranslateHostApi.STATE_READY
            states["zh-en"] = OfflineTranslateHostApi.STATE_READY
        }
        val (adapter, _) = setup(offline = offline)
        adapter.onPanelAction("select:targetLang:简体中文")
        adapter.onPanelAction("select:sourceLang:英文")
        adapter.generate("see you")
        assertEquals("en-zh", offline.requests.single().second)
        adapter.onPanelAction("select:sourceLang:日文")
        assertTrue(adapter.hint().contains("日文翻成简体中文"))
    }
}
