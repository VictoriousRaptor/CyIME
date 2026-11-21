package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaValue
import java.io.File

/**
 * AI 改写插件（plugins/ai-rewrite）：真实 Lua 运行时 + 假接口。
 * 结果通过校验才出现在面板里；答非所问、套话、丢数字的结果被拦下，原文不动。
 */
class LuaAiRewritePluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class FakeApi : HttpHostApi {
        var content: String = ""
        var status: Int = 200
        var lastUrl: String? = null
        var lastBody: String? = null
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            lastUrl = url
            lastBody = body?.toString(Charsets.UTF_8)
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            return HttpResponse(status = status,
                body = """{"choices":[{"message":{"content":"$escaped"}}]}""".toByteArray())
        }
        override fun lastError(): String? = null
    }

    /** 像 Kimi K2.5 以后的模型：带了 temperature 就回 400；其他情况按 [status]/[errorBody] 回。 */
    private class FixedTemperatureApi : HttpHostApi {
        val bodies = mutableListOf<String>()
        var status: Int = 200
        var errorBody: String = ""
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            val text = body?.toString(Charsets.UTF_8).orEmpty()
            bodies += text
            if (text.contains("\"temperature\"")) {
                return HttpResponse(status = 400, body = """{"error":{"message":"invalid temperature: only 1 is allowed for this model","type":"invalid_request_error"}}""".toByteArray())
            }
            if (status != 200) return HttpResponse(status = status, body = errorBody.toByteArray())
            return HttpResponse(status = 200,
                body = """{"choices":[{"message":{"content":"我明天早上八点到公司，你们先开会吧。"}}]}""".toByteArray())
        }
        override fun lastError(): String? = null
    }

    private fun runtimeWith(api: HttpHostApi, model: String): LuaScriptRuntime {
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key-000000")
        store.set("baseUrl", "https://api.example.com/v1")
        store.set("model", model)
        val runtime = LuaScriptRuntime("com.kingzcheung.xime.plugin.ai_rewrite", File("../plugins/ai-rewrite"), "main.lua", store, httpHostApi = api)
        assertTrue("main.lua 应能加载", runtime.load())
        return runtime
    }

    private val source = "这个方案我觉得还行吧，不过预算那块可能得再看看，别超了。"

    private fun setup(config: Map<String, String> = emptyMap()): Triple<LuaScriptRuntime, LuaToolPluginAdapter, FakeApi> {
        val dir = File("../plugins/ai-rewrite")
        assertTrue("ai-rewrite 插件目录应存在: ${dir.absolutePath}", dir.exists())
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key")
        store.set("baseUrl", "https://api.example.com/v1/")
        config.forEach { (k, v) -> store.set(k, v) }
        lastStore = store
        val api = FakeApi()
        val runtime = LuaScriptRuntime("com.kingzcheung.xime.plugin.ai_rewrite", dir, "main.lua", store, httpHostApi = api)
        assertTrue("main.lua 应能加载", runtime.load())
        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.ai_rewrite", name = "AI 改写", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.1.0",
                path = File(dir, "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))
        return Triple(runtime, adapter, api)
    }

    private fun validate(runtime: LuaScriptRuntime, mode: String, src: String, raw: String): String? {
        val cleaned = runtime.call("_cleanOutput", LuaValue.valueOf(raw), LuaValue.valueOf(src))
        val reason = runtime.call("_validate", LuaValue.valueOf(mode), LuaValue.valueOf(src), cleaned)
        return if (reason.isnil()) null else reason.tojstring()
    }

    @Test
    fun `润色结果通过校验后出现在面板里`() {
        val (_, adapter, api) = setup()
        adapter.getPanelState(source)
        api.content = "以下是润色后的内容：\n“这个方案我觉得还不错，不过预算部分可能需要再看看，别超支了。”"
        adapter.onPanelAction("mode:polish")
        val state = adapter.getPanelState("")
        assertEquals(1, state.items.size)
        assertEquals("这个方案我觉得还不错，不过预算部分可能需要再看看，别超支了。", state.items[0].text)
        assertEquals("https://api.example.com/v1/chat/completions", api.lastUrl)
        assertTrue("原文要用标记框起来", api.lastBody?.contains("<原文>") == true)
    }

    @Test
    fun `答非所问的结果被拦下，不给替换`() {
        val (_, adapter, api) = setup()
        adapter.getPanelState(source)
        api.content = "今天天气晴朗，适合出门散步，记得带伞以防万一下雨。"
        adapter.onPanelAction("mode:polish")
        assertTrue(adapter.getPanelState("").items.isEmpty())
    }

    @Test
    fun `校验规则`() {
        val (runtime, _, _) = setup()
        val q = "你们明天几点开会呀，我想旁听一下可以吗？"
        assertEquals(null, validate(runtime, "fix", "我明天早上八点到公司，你门先开会把。", "我明天早上八点到公司，你们先开会吧。"))
        assertEquals(null, validate(runtime, "polish", q, "你们明天几点开会？我想旁听一下，可以吗？"))
        assertTrue(validate(runtime, "polish", q, "明天上午十点开会，欢迎旁听。") != null)
        assertTrue(validate(runtime, "polish", source, "抱歉，我无法帮你判断预算。") != null)
        assertTrue(validate(runtime, "polish", source, "当然可以！这个方案我觉得还不错，不过预算部分可能需要再看看。") != null)
        assertTrue(validate(runtime, "polish", "下午3点在5号会议室，记得带电脑过来哈", "下午在会议室，记得带电脑过来。") != null)
        assertTrue(validate(runtime, "shorten", source, source + "我们下周再讨论。") != null)
        assertEquals(null, validate(runtime, "fix", "i has a apple and two banana", "I have an apple and two bananas"))
        // 翻译（原「AI 翻译」并进来）：换了语言不比字，只拦答非所问和套话
        assertEquals(null, validate(runtime, "translate", "明天晚上我们去哪吃饭？", "Where are we having dinner tomorrow night?"))
        assertTrue(validate(runtime, "translate", "明天晚上我们去哪吃饭？", "We could go to the hot pot place.") != null)
        assertEquals(null, validate(runtime, "translate", "对不起，我迟到了。", "I'm sorry, I'm late."))
        assertEquals(null, validate(runtime, "translate", "Sure, see you tomorrow.", "当然，明天见。"))
        assertTrue(validate(runtime, "translate", "去哪吃？", "好的，这是翻译：Where to eat?") != null)
    }

    @Test
    fun `接口报错或没填密钥时不给结果`() {
        val (_, adapter, api) = setup()
        adapter.getPanelState(source)
        api.status = 401
        api.content = source
        adapter.onPanelAction("mode:fix")
        assertTrue(adapter.getPanelState("").items.isEmpty())
    }

    @Test
    fun `打开面板时没有字就清掉上一次的原文和结果`() {
        val (_, adapter, api) = setup()
        adapter.getPanelState(source)
        api.content = "这个方案我觉得还不错，不过预算部分可能需要再看看，别超支了。"
        adapter.onPanelAction("mode:polish")
        assertEquals(1, adapter.getPanelState("").items.size)
        val reopened = adapter.getPanelState("")
        assertTrue(reopened.items.isEmpty())
        assertEquals("没有要改的字", reopened.ui?.firstOrNull()?.label)
    }

    @Test
    fun `面板里改了原文，之后按改好的字改写`() {
        val (_, adapter, api) = setup()
        val opened = adapter.getPanelState(source)
        assertEquals(source, opened.ui?.firstOrNull { it.key == "host:edit" }?.value)
        val edited = "预算部分可能需要再看看"
        adapter.onPanelInput(edited)
        assertEquals(edited, adapter.getPanelState("").ui?.firstOrNull { it.key == "host:edit" }?.value)
        api.content = "预算部分可能还要再看看"
        adapter.onPanelAction("mode:polish")
        assertTrue("请求带的是改好的字", api.lastBody?.contains(edited) == true)
    }

    @Test
    fun `只接受固定 temperature 的模型：去掉 temperature 重发一次，之后不再带`() {
        val api = FixedTemperatureApi()
        val runtime = runtimeWith(api, "kimi-k3")
        assertTrue("测试接口应通过", runtime.call("testConnection").isnil())
        assertEquals(2, api.bodies.size)
        assertTrue(api.bodies[0].contains("\"temperature\""))
        assertTrue(!api.bodies[1].contains("\"temperature\""))
        assertTrue(runtime.call("testConnection").isnil())
        assertEquals("记住了，第二次直接不带", 3, api.bodies.size)
    }

    @Test
    fun `接口报错时带上服务器原话，密钥遮掉`() {
        val api = FixedTemperatureApi()
        val runtime = runtimeWith(api, "kimi-k3")
        api.status = 400
        api.errorBody = """{"error":{"message":"Invalid request: test-key-000000 is wrong\nmore","type":"invalid_request_error"}}"""
        assertEquals("400: Invalid request: *** is wrong", runtime.call("testConnection").tojstring())
        api.status = 404
        api.errorBody = """{"error":{"message":"Not found the model kimi-k9 or Permission denied"}}"""
        assertEquals("接口地址或模型名不对（404）：Not found the model kimi-k9 or Permission denied",
            runtime.call("testConnection").tojstring())
        api.status = 400
        api.errorBody = """{"code":20012,"message":"Model does not exist. Please check it carefully.","data":null}"""
        assertEquals("400: Model does not exist. Please check it carefully.", runtime.call("testConnection").tojstring())
        api.status = 502
        api.errorBody = "<html><body>Bad Gateway</body></html>"
        assertEquals("接口返回 502", runtime.call("testConnection").tojstring())
    }

    @Test
    fun `普通接口照旧带 temperature`() {
        val (_, adapter, api) = setup()
        adapter.getPanelState(source)
        api.content = "这个方案我觉得还不错，不过预算部分可能需要再看看，别超支了。"
        adapter.onPanelAction("mode:polish")
        assertTrue(api.lastBody?.contains("\"temperature\"") == true)
    }

    // ---- 改写动作列表（0.6.0，11-19「每个都是一条可编辑删改重命名调整顺序的条目」） ----

    private var lastStore: PluginConfigStore? = null

    @Suppress("UNCHECKED_CAST")
    private fun entries(json: String?): List<Map<String, Any?>> =
        com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.decode(json!!) as List<Map<String, Any?>>

    private fun buttons(adapter: LuaToolPluginAdapter): List<Pair<String, String>> =
        adapter.getPanelState(source).ui.orEmpty().filter { it.type == com.kingzcheung.xime.plugin.core.config.UiNodeType.BUTTON }
            .map { it.key.orEmpty() to it.label.orEmpty() }

    private fun prompts(adapter: LuaToolPluginAdapter): List<Map<String, Any?>> {
        val field = adapter.getSettingsSchema().single { it.key == "prompts" }
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.ENTRIES, field.type)
        return entries(field.defaultValue)
    }

    @Test
    fun `没改过的：默认六条，按原来的顺序`() {
        val (_, adapter, _) = setup()
        val defaults = prompts(adapter)
        assertEquals(listOf("润色", "改错字", "更正式", "更口语", "缩短", "翻译"),
            defaults.map { it["name"] })
        assertEquals(listOf("润色", "改错字", "更正式", "更口语", "缩短", "翻译", "设置"), buttons(adapter).map { it.second })
    }

    @Test
    fun `旧版改过的提示词和自定义动作带进列表，旧键不删`() {
        val (_, adapter, api) = setup(mapOf(
            "prompt_polish" to "我自己的润色要求",
            "custom_label" to "日语",
            "prompt_custom" to "把下面这段话翻译成日语。",
        ))
        adapter.getSettingsSchema()
        val saved = entries(lastStore!!.get("prompts"))
        assertEquals(7, saved.size)
        assertEquals("我自己的润色要求", saved[0]["text"])
        assertEquals("polish", saved[0]["id"])
        assertEquals("日语", saved[6]["name"])
        assertEquals("把下面这段话翻译成日语。", saved[6]["text"])
        assertEquals("我自己的润色要求", lastStore!!.get("prompt_polish"))
        assertEquals("日语", buttons(adapter)[6].second)
        api.content = "这个方案我觉得还不错，不过预算部分可能需要再看看，别超支了。"
        adapter.onPanelAction("mode:polish")
        assertTrue(api.lastBody!!.contains("我自己的润色要求"))
    }

    @Test
    fun `面板按列表的顺序和名字显示，删掉的不显示，自己加的能用`() {
        val list = """[{"name":"英文","text":"把下面这段话翻译成英文。"},""" +
            """{"id":"fix","name":"纠错","text":"只改错别字。"},{"name":"空的","text":"  "}]"""
        val (_, adapter, api) = setup(mapOf("prompts" to list))
        val shown = buttons(adapter)
        assertEquals(listOf("英文", "纠错", "设置"), shown.map { it.second })
        api.content = "This plan looks fine, but the budget part may need another look."
        adapter.onPanelAction(shown[0].first)
        assertTrue(api.lastBody!!.contains("把下面这段话翻译成英文。"))
        assertEquals(1, adapter.getPanelState("").items.size)
        // 改了名字和提示词的自带条目，照旧用改错字的校验
        adapter.getPanelState(source)
        api.content = "这个方案我觉得还不错，不过预算部分可能需要再看看，别超支了。"
        adapter.onPanelAction("mode:fix")
        assertTrue(api.lastBody!!.contains("只改错别字。"))
    }

    @Test
    fun `全删光了：面板说去设置里加`() {
        val (_, adapter, _) = setup(mapOf("prompts" to "[]"))
        val state = adapter.getPanelState(source)
        assertEquals(listOf("设置"), buttons(adapter).map { it.second })
        assertTrue(state.ui.orEmpty().any { it.value?.contains("还没有改写动作") == true })
    }
}
