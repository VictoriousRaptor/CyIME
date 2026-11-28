package com.kingzcheung.xime.speech

import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application
import com.kingzcheung.xime.settings.SettingsPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 内置在线语音：地址怎么拼、返回里怎么找字、从「在线语音」插件搬设置。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnlineSpeechApiTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `OpenAI 地址填到 v1 或填全都行，TokenHub 原样用`() {
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", OnlineSpeechApi.endpoint("https://api.groq.com/openai/v1/"))
        assertEquals("https://x.cn/v1/audio/transcriptions", OnlineSpeechApi.endpoint("https://x.cn/v1/audio/transcriptions"))
        val hub = "https://tokenhub.tencentmaas.com/v1/wand/asrproxy/sync_transcribe"
        assertEquals(hub, OnlineSpeechApi.endpoint(hub))
        assertTrue(OnlineSpeechApi.isTokenHub(hub))
        assertFalse(OnlineSpeechApi.isTokenHub("https://api.openai.com/v1"))
    }

    @Test fun `DeepSeek 语音识别：地址原样用、只传 file、读顶层 text`() {
        val url = OnlineSpeechApi.DEEPSEEK_ASR_URL
        assertEquals(url, OnlineSpeechApi.endpoint(url))
        assertEquals(url, OnlineSpeechApi.endpoint("$url/"))
        assertTrue(OnlineSpeechApi.isDeepSeekAsr(url))
        assertFalse(OnlineSpeechApi.isDeepSeekAsr("https://api.deepseek.com/v1"))
        assertNull(OnlineSpeechApi.chatFallback(url))
        val body = OnlineSpeechApi.deepSeekBody(OnlineSpeechApi.wav(ByteArray(320)))
        assertEquals(1, body.size)
        assertTrue(body.part(0).headers!!["Content-Disposition"]!!.contains("name=\"file\""))
        assertEquals("转写结果", OnlineSpeechApi.extractText(
            JSONObject("""{"request_id":"r1","text":"转写结果","duration_ms":1200}"""), 0))
        assertEquals("", OnlineSpeechApi.extractText(JSONObject("""{"request_id":"r2","text":"","duration_ms":1000}"""), 0))
    }

    @Test fun `返回里的文字：OpenAI text、腾讯 output text、句子列表`() {
        assertEquals("你好", OnlineSpeechApi.extractText(JSONObject("""{"text":"你好"}"""), 0))
        assertEquals("测试一下", OnlineSpeechApi.extractText(JSONObject("""{"status":"completed","output":{"text":"测试一下"}}"""), 0))
        assertEquals("hello world", OnlineSpeechApi.extractText(JSONObject("""{"segments":[{"text":"hello"},{"text":"world"}]}"""), 0))
        assertNull(OnlineSpeechApi.extractText(JSONObject("""{"id":1}"""), 0))
    }

    @Test fun `出错时显示接口自己的原因`() {
        assertEquals("Invalid API key", OnlineSpeechApi.serverMessage(JSONObject("""{"error":{"message":"Invalid API key"}}"""), ""))
        assertEquals("参数错误", OnlineSpeechApi.serverMessage(JSONObject("""{"Response":{"Error":{"Message":"参数错误"}}}"""), ""))
        assertNull(OnlineSpeechApi.serverMessage(null, "<html>502</html>"))
    }

    @Test fun `插件里填过的接口搬回内置，并改选内置`() {
        app.getSharedPreferences("online_speech_api", 0).edit().clear().commit()
        // 插件设置文件；没有 enc: 前缀的值按明文读（测试环境没有 Android 密钥库）。
        app.getSharedPreferences("plugin_cfg_com.kingzcheung.xime.plugin.online_asr", 0).edit()
            .putString("apiKey", "sk-test").putString("baseUrl", "https://api.siliconflow.cn/v1")
            .putString("model", "FunAudioLLM/SenseVoiceSmall").commit()
        SettingsPreferences.setSttOnlinePluginId(app, "com.kingzcheung.xime.plugin.online_asr")
        SettingsPreferences.setSttUseLocal(app, false)

        OnlineSpeechApi.migrateFromPlugin(app)

        val config = OnlineSpeechApi.config(app)
        assertEquals("sk-test", config.apiKey)
        assertEquals("https://api.siliconflow.cn/v1", config.baseUrl)
        assertEquals("FunAudioLLM/SenseVoiceSmall", config.model)
        assertTrue(OnlineSpeechApi.isSelected(app))
        assertTrue(OnlineSpeechApi.isActive(app))
    }

    @Test fun `小米 MiMo、qwen3-asr 走对话接口，其他照旧`() {
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", OnlineSpeechApi.endpoint("https://api.xiaomimimo.com/v1", "mimo-v2.5-asr"))
        assertEquals("https://x.cn/compatible-mode/v1/chat/completions", OnlineSpeechApi.endpoint("https://x.cn/compatible-mode/v1/", "qwen3-asr-flash"))
        assertEquals("https://x.cn/v1/chat/completions", OnlineSpeechApi.endpoint("https://x.cn/v1/chat/completions", "whatever"))
        // 硅基流动的 TeleSpeechASR 名字里有 ASR，但走的是 /audio/transcriptions
        assertEquals("https://api.siliconflow.cn/v1/audio/transcriptions", OnlineSpeechApi.endpoint("https://api.siliconflow.cn/v1", "TeleAI/TeleSpeechASR"))
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", OnlineSpeechApi.endpoint("https://api.groq.com/openai/v1", "whisper-large-v3-turbo"))
    }

    @Test fun `对话接口的请求体和回包`() {
        val body = OnlineSpeechApi.chatAudioBody("mimo-v2.5-asr", byteArrayOf(1, 2, 3), com.kingzcheung.xime.settings.InputLanguage.CHINESE)
        assertEquals("mimo-v2.5-asr", body.getString("model"))
        val part = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", part.getString("type"))
        assertEquals("data:audio/wav;base64,AQID", part.getJSONObject("input_audio").getString("data"))
        assertEquals("wav", part.getJSONObject("input_audio").getString("format"))
        assertEquals("zh", body.getJSONObject("asr_options").getString("language"))
        assertFalse(OnlineSpeechApi.chatAudioBody("m", byteArrayOf(), com.kingzcheung.xime.settings.InputLanguage.JAPANESE).has("asr_options"))

        assertEquals("你好", OnlineSpeechApi.chatText(JSONObject("""{"choices":[{"message":{"role":"assistant","content":"你好"}}]}""")))
        assertEquals("ab", OnlineSpeechApi.chatText(JSONObject("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}""")))
        assertEquals("", OnlineSpeechApi.chatText(JSONObject("""{"choices":[{"message":{"content":null}}]}""")))
        assertNull(OnlineSpeechApi.chatText(JSONObject("""{"text":"x"}""")))
    }

    /**
     * 本机起一个假接口（单元测试里没有 MockWebServer、也没有 JDK 的 HttpServer，用 ServerSocket 手写最小 HTTP/1.1）：
     * 记下每次请求的路径、api-key 头和请求体，每次回完就断开连接。
     */
    private class FakeServer(val handler: (path: String) -> Pair<Int, String>) : AutoCloseable {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val apiKeyHeaders = java.util.Collections.synchronizedList(mutableListOf<String?>())
        private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { serve(it) }
            }
        }.apply { isDaemon = true; start() }

        private fun serve(client: java.net.Socket) {
            val input = java.io.BufferedInputStream(client.getInputStream())
            fun readLine(): String {
                val line = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                    if (b != '\r'.code) line.write(b)
                }
                return line.toString("UTF-8")
            }
            val requestLine = readLine()
            if (requestLine.isEmpty()) return
            val path = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
            var length = 0
            var apiKey: String? = null
            while (true) {
                val header = readLine()
                if (header.isEmpty()) break
                val name = header.substringBefore(':').trim().lowercase()
                val value = header.substringAfter(':').trim()
                if (name == "content-length") length = value.toInt()
                if (name == "api-key") apiKey = value
            }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n == -1) break
                read += n
            }
            paths += path; bodies += String(body, 0, read, Charsets.UTF_8); apiKeyHeaders += apiKey
            val (code, text) = handler(path)
            val bytes = text.toByteArray(Charsets.UTF_8)
            val out = client.getOutputStream()
            out.write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }

        val base get() = "http://127.0.0.1:${socket.localPort}/v1"
        override fun close() { socket.close(); thread.join(2000) }
    }

    @Test fun customRequestTestsUseTheSameEndpointAndResponsePathAsDictation() {
        FakeServer {  path -> if (path == "/v1/custom") 200 to """{"data":[{"value":"测试"}]}""" else 404 to "{}" }.use { server ->
            val config = OnlineSpeechApi.Config(server.base + "/custom", "test-key", "custom-model",
                bodyTemplate = """{"model":"{{model}}","audio":"{{audio}}"}""", textPath = "data.0.value")
            OnlineSpeechApi.save(app, config)
            assertTrue(OnlineSpeechApi.test(app).startsWith("可用"))
            val request = JSONObject(server.bodies.single())
            assertEquals("custom-model", request.getString("model"))
            assertTrue(request.getString("audio").startsWith("UklGR"))
            OnlineSpeechApi.save(app, config.copy(textPath = "data.0.missing"))
            assertTrue(OnlineSpeechApi.test(app).startsWith("不可用"))
        }
        FakeServer { 401 to """{"error":{"message":"denied"}}""" }.use { server ->
            configure(server.base, "whisper-1")
            assertTrue(OnlineSpeechApi.test(app).startsWith("不可用"))
        }
    }

    private fun configure(baseUrl: String, model: String) {
        app.getSharedPreferences("online_speech_api", 0).edit().clear().commit()
        OnlineSpeechApi.save(app, OnlineSpeechApi.Config(baseUrl, "sk-test", model))
    }

    @Test fun `MiMo：直接走对话接口，带 api-key 头，文字从 choices 取`() {
        FakeServer { path ->
            if (path == "/v1/chat/completions") 200 to """{"choices":[{"message":{"content":" 明天见 "}}]}""" else 404 to "{}"
        }.use { server ->
            configure(server.base, "mimo-v2.5-asr")
            val text = OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE).getOrThrow()
            assertEquals("明天见", text)
            assertEquals(listOf("/v1/chat/completions"), server.paths)
            assertEquals("sk-test", server.apiKeyHeaders.single())
            assertTrue(server.bodies.single().contains("\"input_audio\""))
        }
    }

    @Test fun `不认识的服务商：audio transcriptions 404 就换对话接口再试`() {
        FakeServer { path ->
            if (path == "/v1/chat/completions") 200 to """{"choices":[{"message":{"content":"你好"}}]}""" else 404 to """{"error":{"message":"Not Found"}}"""
        }.use { server ->
            configure(server.base, "some-asr")
            assertEquals("你好", OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE).getOrThrow())
            assertEquals(listOf("/v1/audio/transcriptions", "/v1/chat/completions"), server.paths)
        }
    }

    @Test fun `两个地址都 404：把试过的完整地址写出来`() {
        FakeServer { 404 to "{}" }.use { server ->
            configure(server.base, "whisper-1")
            val message = OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE)
                .exceptionOrNull()!!.message!!
            assertTrue(message, message.contains("${server.base}/audio/transcriptions") && message.contains("${server.base}/chat/completions"))
        }
        FakeServer { 404 to "{}" }.use { server ->
            configure("${server.base}/audio/transcriptions", "whisper-1")
            val message = OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE)
                .exceptionOrNull()!!.message!!
            // 地址填全了就不猜：只试这一个，原样写出来
            assertEquals(1, server.paths.size)
            assertTrue(message, message.contains("${server.base}/audio/transcriptions"))
        }
    }

    @Test fun `Groq、硅基流动：OpenAI 式上传，文字从 text 取`() {
        FakeServer { path -> if (path == "/v1/audio/transcriptions") 200 to """{"text":"测试"}""" else 404 to "{}" }.use { server ->
            configure(server.base, "FunAudioLLM/SenseVoiceSmall")
            assertEquals("测试", OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE).getOrThrow())
            assertTrue(server.bodies.single().contains("name=\"file\""))
            assertNull(server.apiKeyHeaders.single())
        }
    }
    @Test fun `地址栏连着说明文字粘进来：只取网址；密钥、模型去掉换行空格`() {
        assertEquals("https://api.groq.com/openai/v1",
            OnlineSpeechApi.cleanUrl("https://api.groq.com/openai/v1\n• API 密钥: 粘贴刚复制的 key\n• 模型: whisper-large-v3"))
        assertEquals("https://api.siliconflow.cn/v1", OnlineSpeechApi.cleanUrl("  地址：https://api.siliconflow.cn/v1，模型 FunAudioLLM/SenseVoiceSmall"))
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel", OnlineSpeechApi.cleanUrl("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel "))
        assertEquals("https://api.xiaomimimo.com/v1", OnlineSpeechApi.cleanUrl("https://api.xiaomimimo.com/v1"))
        assertEquals("abc", OnlineSpeechApi.cleanUrl(" abc "))
        assertEquals("gsk_abc", OnlineSpeechApi.cleanToken(" gsk_a\u200Bb c\n"))
    }

    @Test fun `Groq：地址带着说明文字、密钥和模型带换行也能请求到 audio transcriptions`() {
        FakeServer { path -> if (path == "/v1/audio/transcriptions") 200 to """{"text":"你好"}""" else 404 to "{}" }.use { server ->
            app.getSharedPreferences("online_speech_api", 0).edit().clear().commit()
            OnlineSpeechApi.save(app, OnlineSpeechApi.Config("${server.base}\n• API 密钥: 粘贴刚复制的 key\n• 模型: …",
                "sk-\ntest", "whisper-large-v3\n"))
            assertEquals("你好", OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE).getOrThrow())
            assertEquals(listOf("/v1/audio/transcriptions"), server.paths)
            val body = server.bodies.single()
            assertTrue(body, body.contains("whisper-large-v3\r\n") && body.contains("name=\"language\"") && body.contains("name=\"response_format\""))
        }
    }

    @Test fun `模型名不对（Groq 404 model_not_found）：不换接口，直接说模型名`() {
        FakeServer {
            404 to """{"error":{"message":"The model `whisper-lerga-v3` does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}"""
        }.use { server ->
            configure(server.base, "whisper-lerga-v3")
            val message = OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE)
                .exceptionOrNull()!!.message!!
            assertEquals(listOf("/v1/audio/transcriptions"), server.paths)
            assertTrue(message, message.startsWith("模型名不对（404）：whisper-lerga-v3"))
        }
    }

    @Test fun `密钥里混进中文：不发请求，报错里不带密钥`() {
        FakeServer { 200 to """{"text":"x"}""" }.use { server ->
            app.getSharedPreferences("online_speech_api", 0).edit().clear().commit()
            OnlineSpeechApi.save(app, OnlineSpeechApi.Config(server.base, "sk-secret粘贴", "whisper-1"))
            val message = OnlineSpeechApi.transcribe(app, ByteArray(3200), com.kingzcheung.xime.settings.InputLanguage.CHINESE)
                .exceptionOrNull()!!.message!!
            assertEquals("密钥里有多余的文字，请重新粘贴密钥", message)
            assertFalse(message.contains("secret"))
            assertTrue(server.paths.isEmpty())
        }
    }
}
