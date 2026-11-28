package com.kingzcheung.xime.speech

import android.app.Application
import android.content.Context
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 内置在线语音：设置 › 语音转文本 › 在线语音里直接填地址、密钥、模型，不用装插件。
 * 接口按地址和模型区分：OpenAI 兼容 `/audio/transcriptions`（OpenAI、Groq、硅基流动等，multipart 上传），
 * 对话接口带音频 `/chat/completions`（小米 MiMo mimo-v2.5-asr、阿里 qwen3-asr：音频 Base64 放进 input_audio，文字在 choices[0].message.content），
 * 腾讯 TokenHub 同步语音识别（地址含 /asrproxy/ 或以 sync_transcribe 结尾，JSON 上传 Base64 WAV，文字在 output.text），
 * 火山引擎(豆包)语音识别（地址是 openspeech.bytedance.com 或 wss:// 开头，见 [VolcengineSpeechApi]），
 * DeepSeek 语音识别（地址以 /tools/asr 结尾：multipart 只传 file，不传模型和语种，文字在 text）。
 * 配置单独存一个设置文件：不进备份，密钥只留在本机。
 */
object OnlineSpeechApi {
    /** 存进「用哪个在线语音」的值：选了它且打开「优先使用在线语音」时先走这里。 */
    const val BUILTIN_ID = "builtin:online_speech"
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "whisper-1"
    /** 1.15.40～1.15.50 的「在线语音」插件；装过的人第一次打开语音页或说话时，把它的设置搬回来。 */
    private const val OLD_PLUGIN_ID = "com.kingzcheung.xime.plugin.online_asr"
    private const val PREFS = "online_speech_api"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    /** 火山引擎旧版鉴权的 App ID；新版控制台 API Key 不用填。 */
    private const val KEY_APP_ID = "app_id"
    private const val KEY_STATUS = "status"
    private const val KEY_PLUGIN_MIGRATED = "plugin_migrated"
    private const val SAMPLE_RATE = 16000

    data class Config(val baseUrl: String, val apiKey: String, val model: String, val appId: String = "",
        val bodyTemplate: String = "", val textPath: String = "")

    data class Preset(val name: String, val url: String, val model: String)
    val presets = listOf(
        Preset("OpenAI", DEFAULT_BASE_URL, DEFAULT_MODEL),
        Preset("Groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo"),
        Preset("硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall"),
        Preset("小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-asr"),
        Preset("阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash"),
        Preset("腾讯 TokenHub", "https://tokenhub.tencentmaas.com/v1/wand/asrproxy/sync_transcribe", "hy-asr-3.0-preview"),
        Preset("火山引擎（豆包）", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async", VolcengineSpeechApi.DEFAULT_STREAM_RESOURCE),
        // DeepSeek 语音识别没有模型名：模型一栏留空（11-26 用户要的预设）。
        Preset("DeepSeek", DEEPSEEK_ASR_URL, ""),
    )

    const val DEEPSEEK_ASR_URL = "https://api.deepseek.com/v1/tools/asr"

    /**
     * DeepSeek 语音识别（POST …/v1/tools/asr）：multipart 只有 file 一个字段（16 位 PCM WAV 或 Ogg Opus，最大 8 MiB），
     * 没有模型、语种参数，回包 {request_id, text, duration_ms}。按地址认，不靠 OpenAI 的 /audio/transcriptions 后缀。
     */
    internal fun isDeepSeekAsr(url: String): Boolean = url.trim().trimEnd('/').lowercase().endsWith("/tools/asr")

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    }

    /** 可空：测试里的假 Context 可能没有这个设置文件，此时按未填写处理。 */
    private fun prefs(context: Context): android.content.SharedPreferences? =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): Config = prefs(context).let {
        Config(it?.getString(KEY_BASE_URL, null)?.let(::cleanUrl)?.takeIf { url -> url.isNotBlank() } ?: DEFAULT_BASE_URL,
            cleanToken(it?.getString(KEY_API_KEY, "").orEmpty()),
            it?.getString(KEY_MODEL, null)?.let(::cleanToken)?.takeIf { model -> model.isNotBlank() }
                ?: if (isDeepSeekAsr(it?.getString(KEY_BASE_URL, null).orEmpty())) "" else DEFAULT_MODEL,
            cleanToken(it?.getString(KEY_APP_ID, "").orEmpty()),
            it?.getString("body_template", "").orEmpty(), it?.getString("text_path", "").orEmpty())
    }

    private val URL_IN_TEXT = Regex("(?i)(?:https?|wss?)://[^\\s•·，。、；;,\"'<>()（）]+")

    /**
     * 地址栏只取第一个网址：连着说明文字一起粘进来（11-04 Groq 截图：地址后面跟着「• API 密钥: …• 模型: …」），
     * 请求地址就成了 /openai/v1• API 密钥…，服务商回 404。没有网址时原样去掉首尾空白。
     */
    internal fun cleanUrl(raw: String): String = URL_IN_TEXT.find(raw)?.value ?: raw.trim()

    /** 密钥、模型、App ID 里不会有空白：粘贴带进来的换行、空格、零宽字符去掉。 */
    internal fun cleanToken(raw: String): String = raw.filterNot { it.isWhitespace() || it in "\u200B\u200C\u200D\uFEFF" }

    /** 改了配置就清掉旧的测试结果，状态不会停在“可用”误导人。 */
    fun save(context: Context, config: Config) {
        prefs(context)?.edit()?.putString(KEY_BASE_URL, config.baseUrl.trim())?.putString(KEY_API_KEY, config.apiKey.trim())
            ?.putString(KEY_MODEL, config.model.trim())?.putString(KEY_APP_ID, config.appId.trim())
            ?.putString("body_template", config.bodyTemplate.trim())?.putString("text_path", config.textPath.trim())
            ?.remove(KEY_STATUS)?.apply()
    }

    fun isConfigured(context: Context): Boolean = config(context).apiKey.isNotBlank()

    /** 选了内置在线语音（「优先使用在线语音」打开时存的就是它）。 */
    fun isSelected(context: Context): Boolean = SettingsPreferences.getSttOnlinePluginId(context) == BUILTIN_ID

    /** 现在说话会先走内置在线语音。 */
    fun isActive(context: Context): Boolean =
        isSelected(context) && !SettingsPreferences.isSttUseLocal(context) && isConfigured(context)

    fun status(context: Context): String = prefs(context)?.getString(KEY_STATUS, null)
        ?: if (isConfigured(context)) "未测试" else "未填写密钥"

    /** 用一秒静音真正调用一次转写接口：地址、密钥、模型都对才算可用。在后台线程调用。 */
    fun test(context: Context): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val tested = config(context)
        val status = if (!isConfigured(context)) "未填写密钥"
            else transcribe(context, ByteArray(SAMPLE_RATE * 2), InputLanguage.CHINESE)
                .fold({ "可用（$time 测试）" }, { "不可用：${it.message}（$time 测试）" })
        if (config(context) != tested) return status(context)
        prefs(context)?.edit()?.putString(KEY_STATUS, status)?.apply()
        return status
    }

    /**
     * 「在线语音」插件 → 内置：插件里填过密钥、内置还没填时，把地址、密钥、模型搬过来；
     * 原来选的是这个插件的，改选内置（「优先使用在线语音」照旧）。只搬一次，插件本身不动。
     */
    fun migrateFromPlugin(context: Context) {
        val prefs = prefs(context) ?: return
        if (prefs.getBoolean(KEY_PLUGIN_MIGRATED, false)) return
        val app = context.applicationContext as? Application ?: return
        runCatching {
            val store = PluginConfigStoreImpl(app, OLD_PLUGIN_ID)
            val apiKey = store.get("apiKey")?.trim().orEmpty()
            if (apiKey.isNotEmpty() && !isConfigured(context)) {
                save(context, Config(store.get("baseUrl")?.trim().orEmpty().ifEmpty { DEFAULT_BASE_URL }, apiKey,
                    store.get("model")?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }))
            }
            val selected = SettingsPreferences.getSttOnlinePluginId(context)
            // 选的是这个插件，或 1.15.33～1.15.39 用内置接口时打开过「优先使用在线接口」：改选内置。
            val preferredOnline = !SettingsPreferences.getPrefsPublic(context).getBoolean(SettingsPreferences.KEY_STT_USE_LOCAL, true)
            if (isConfigured(context) && (selected == OLD_PLUGIN_ID || selected.isBlank() && preferredOnline)) {
                SettingsPreferences.setSttOnlinePluginId(context, BUILTIN_ID)
            }
        }
        prefs.edit().putBoolean(KEY_PLUGIN_MIGRATED, true).apply()
    }

    /** 腾讯 TokenHub 的同步识别地址。 */
    internal fun isTokenHub(url: String): Boolean = url.contains("/asrproxy/") || url.endsWith("sync_transcribe")

    /**
     * 走对话接口带音频的识别：小米 MiMo（api.xiaomimimo.com，模型 mimo-v2.5-asr）、阿里百炼 qwen3-asr，
     * 或地址直接填到 /chat/completions。它们没有 /audio/transcriptions，按 OpenAI 那样请求是 404（11-03 截图）。
     */
    internal fun isChatAudio(baseUrl: String, model: String): Boolean {
        val url = baseUrl.trim().trimEnd('/').lowercase()
        val m = model.trim().lowercase()
        return url.endsWith("/chat/completions") || "xiaomimimo.com" in url ||
            m.startsWith("mimo-") || m.startsWith("qwen3-asr") || m.startsWith("qwen-audio-asr")
    }

    /** 用户填的地址 → 实际请求地址：填到 /v1 为止也行，填全也行。 */
    internal fun endpoint(baseUrl: String, model: String = ""): String {
        if (VolcengineSpeechApi.isVolcengine(baseUrl)) return VolcengineSpeechApi.endpoint(baseUrl)
        val url = baseUrl.trim().trimEnd('/')
        if (isTokenHub(url) || isDeepSeekAsr(url) || url.endsWith("/audio/transcriptions") || url.endsWith("/chat/completions")) return url
        return if (isChatAudio(url, model)) "$url/chat/completions" else "$url/audio/transcriptions"
    }

    /** OpenAI 式地址 404 时，同一前缀换成对话接口再试一次（用户填到 /v1 为止时才试）。 */
    internal fun chatFallback(baseUrl: String): String? {
        val url = baseUrl.trim().trimEnd('/')
        if (isTokenHub(url) || isDeepSeekAsr(url) || url.endsWith("/audio/transcriptions") || url.endsWith("/chat/completions")) return null
        return "$url/chat/completions"
    }

    /** 对话接口识别的请求体：一条用户消息只带音频；语种只给中文、英文，其他交给接口自己认。 */
    internal fun chatAudioBody(model: String, wav: ByteArray, language: InputLanguage): JSONObject {
        val audio = JSONObject().put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)).put("format", "wav")
        val content = JSONArray().put(JSONObject().put("type", "input_audio").put("input_audio", audio))
        return JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .apply {
                when (language) {
                    InputLanguage.CHINESE -> put("asr_options", JSONObject().put("language", "zh"))
                    InputLanguage.ENGLISH -> put("asr_options", JSONObject().put("language", "en"))
                    else -> Unit
                }
            }
    }

    /** 对话接口的回包：choices[0].message.content，可能是字符串，也可能是分段列表。 */
    internal fun chatText(json: JSONObject): String? {
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        return when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { i ->
                content.opt(i).let { it as? String ?: (it as? JSONObject)?.optString("text")?.takeIf { t -> t.isNotEmpty() } }
            }.joinToString("")
            JSONObject.NULL, null -> ""
            else -> null
        }
    }

    /** HTTP 失败：带上请求地址，404 时能说出试的是哪个地址。 */
    private class HttpFailure(val code: Int, val url: String, message: String, val detail: String? = null,
        val modelMissing: Boolean = false) : IllegalStateException(message)

    /** 404 回包说的是模型不存在（OpenAI/Groq：error.code = model_not_found，或 message 提到 model）。 */
    internal fun isModelMissing(json: JSONObject?): Boolean {
        val error = json?.optJSONObject("error") ?: return false
        return error.optString("code") == "model_not_found" ||
            Regex("(?i)\\bmodel\\b.*(not exist|not found|does not)").containsMatchIn(error.optString("message"))
    }

    /** [pcm] 为 16 kHz 单声道 16 位小端 PCM。在后台线程调用。 */
    fun transcribe(context: Context, pcm: ByteArray, language: InputLanguage): Result<String> = runCatching {
        val config = config(context)
        require(config.apiKey.isNotBlank()) { "未填写密钥" }
        // 请求头只能是英文字符；密钥里混进中文或符号时 OkHttp 会把整个密钥写进报错，这里先拦下。
        require(config.apiKey.all { it in '!'..'~' }) { "密钥里有多余的文字，请重新粘贴密钥" }
        if (config.bodyTemplate.isNotBlank()) {
            return@runCatching post(config.baseUrl, config, wav(pcm), language)
        }
        if (VolcengineSpeechApi.isVolcengine(config.baseUrl)) {
            return@runCatching VolcengineSpeechApi.transcribe(client, config.baseUrl, config.appId, config.apiKey,
                config.model, pcm, ::httpError)
        }
        val url = endpoint(config.baseUrl, config.model)
        val wav = wav(pcm)
        try {
            post(url, config, wav, language)
        } catch (e: HttpFailure) {
            // 不认识的服务商：OpenAI 式地址 404，换成对话接口再试一次；还不行就把两个地址都写出来。
            // 服务商明说是模型名不对（Groq：The model `x` does not exist）时不换接口，直接说模型名。
            if (e.modelMissing) error("模型名不对（404）：${config.model}" + (e.detail?.let { "（$it）" } ?: ""))
            val fallback = if (e.code == 404 && !isChatAudio(config.baseUrl, config.model)) chatFallback(config.baseUrl) else null
            if (fallback == null) throw e
            try {
                post(fallback, config, wav, language)
            } catch (second: HttpFailure) {
                if (second.code != 404) throw second
                error("接口地址或模型名不对（404）：试过 ${e.url} 和 ${second.url}" + (e.detail?.let { "（$it）" } ?: ""))
            }
        }
    }.recoverCatching { error ->
        throw if (error is java.io.IOException) IllegalStateException("连不上服务器", error) else error
    }

    /**
     * 火山引擎(豆包)双向流式地址：这次录音边说边发、边出字（[VolcengineSpeechApi.LiveSession]）。
     * 其他接口、没填密钥或密钥有多余文字时为 null：照旧录完整段上传，错误由 [transcribe] 报。
     */
    internal fun liveSession(context: Context, onPartial: (String) -> Unit): VolcengineSpeechApi.LiveSession? {
        val config = config(context)
        if (config.bodyTemplate.isNotBlank() || config.apiKey.isBlank() || !config.apiKey.all { it in '!'..'~' } || !VolcengineSpeechApi.isLive(config.baseUrl)) return null
        return VolcengineSpeechApi.LiveSession(client, config.baseUrl, config.appId, config.apiKey, config.model, ::httpError, onPartial)
    }

    /** 发一次识别请求，返回文字。HTTP 失败抛 [HttpFailure]。 */
    private fun post(url: String, config: Config, wav: ByteArray, language: InputLanguage): String {
        val chat = url.endsWith("/chat/completions")
        val body = when {
            config.bodyTemplate.isNotBlank() -> {
                // Replace complete JSON string values so model names and audio cannot break the JSON.
                val body = config.bodyTemplate.replace("\"{{audio}}\"", JSONObject.quote(Base64.getEncoder().encodeToString(wav)))
                    .replace("\"{{model}}\"", JSONObject.quote(config.model))
                    .replace("\"{{language}}\"", JSONObject.quote(language.id))
                require("\"{{audio}}\"" in config.bodyTemplate) { "请求体缺少 {{audio}} 字段值" }
                require(runCatching { JSONObject(body) }.isSuccess) { "请求体不是有效的 JSON 对象" }
                body.toRequestBody("application/json".toMediaType())
            }
            // 语种不传：中英混说、方言都交给接口自己认。
            isTokenHub(url) -> JSONObject().put("model", config.model).put("data", Base64.getEncoder().encodeToString(wav))
                .put("voice_encode_format", "wav").toString().toRequestBody("application/json".toMediaType())
            chat -> chatAudioBody(config.model, wav, language).toString().toRequestBody("application/json".toMediaType())
            // DeepSeek：文档只列 file 一个字段，不多传 model、language、response_format。
            isDeepSeekAsr(url) -> deepSeekBody(wav)
            else -> MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model)
                .addFormDataPart("response_format", "json")
                .apply { if (language != InputLanguage.UNSPECIFIED) addFormDataPart("language", language.id) }
                .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${config.apiKey}")
            // 小米 MiMo 文档写的是 api-key 头；两个都带，哪种都认。
            .apply { if (chat) header("api-key", config.apiKey) }
            .post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val detail = serverMessage(json, text)
                // 404 时写出试的完整地址，一眼看出是地址错了还是模型名错了。
                val message = if (response.code == 404) "接口地址或模型名不对（404）：$url" + (detail?.let { "（$it）" } ?: "")
                    else httpError(response.code, detail)
                throw HttpFailure(response.code, url, message, detail, response.code == 404 && isModelMissing(json))
            }
            checkNotNull(json) { "接口返回的格式看不懂" }
            businessError(json)?.let { error(it) }
            if (config.bodyTemplate.isNotBlank() && config.textPath.isNotBlank()) {
                var value: Any? = json
                for (key in config.textPath.split('.')) value = when (val obj = value) {
                    is JSONObject -> obj.opt(key)
                    is JSONArray -> key.toIntOrNull()?.let { obj.opt(it) }
                    else -> null
                }
                return value as? String ?: error("响应中找不到指定的文字字段")
            }
            if (chat) return (chatText(json) ?: error("接口返回的格式看不懂")).trim()
            return (extractText(json, 0) ?: if (json.optString("status") == "completed") "" else error("接口返回的格式看不懂")).trim()
        }
    }

    /** DeepSeek 语音识别的请求体：只有 file。 */
    internal fun deepSeekBody(wav: ByteArray): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
        .build()

    private fun httpError(code: Int, detail: String?): String {
        val base = when (code) {
            401, 403 -> "密钥不对或没有权限（$code）"
            404 -> "接口地址或模型名不对（404）"
            413 -> "这段录音太长（413）"
            429 -> "请求太多或余额不足（429）"
            400 -> "接口不认这个请求（400）"
            else -> "接口返回 $code"
        }
        return if (detail != null) "$base：$detail" else base
    }

    /** 接口自己给的出错原因（OpenAI：error.message；腾讯：message / msg / Response.Error.Message）。 */
    internal fun serverMessage(json: JSONObject?, raw: String): String? {
        if (json == null) return raw.trim().takeIf { it.isNotEmpty() && '<' !in it }?.let(::shorten)
        val error = json.opt("error").let { if (it is JSONObject) it.optString("message").ifEmpty { it.optString("msg") } else it as? String }
        val tencent = json.optJSONObject("Response")?.optJSONObject("Error")?.optString("Message")
        return listOf(error, json.optString("message"), json.optString("msg"), json.optString("Message"),
            json.optString("error_msg"), json.optString("detail"), tencent)
            .firstOrNull { !it.isNullOrBlank() }?.let(::shorten)
    }

    private fun shorten(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(60) + "…" else it }

    /** HTTP 200 但 code 不为 0、带 error、或 status 为 failed 也算失败。 */
    private fun businessError(json: JSONObject): String? {
        val code = json.opt("code")?.takeIf { it != JSONObject.NULL }?.toString()
        val failed = code != null && code !in setOf("0", "200") || json.has("error") && !json.isNull("error") ||
            json.optString("status") == "failed" || json.optJSONObject("Response")?.has("Error") == true
        if (!failed) return null
        val head = "接口报错" + (code?.let { "（$it）" } ?: "")
        return serverMessage(json, "")?.let { "$head：$it" } ?: head
    }

    private val TEXT_KEYS = listOf("text", "transcript", "Text", "Transcript")
    private val WRAPPER_KEYS = listOf("data", "output", "result", "Result", "Data", "Response", "results", "sentences", "segments")

    /** 从返回里找识别出的文字：先找 text / transcript，再进 data / output / result 之类的外层；句子列表按顺序拼起来。 */
    internal fun extractText(value: Any?, depth: Int): String? {
        if (depth > 6) return null
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { i ->
                value.opt(i).let { it as? String ?: extractText(it, depth + 1) }?.trim()
            }.takeIf { it.isNotEmpty() }?.reduce { acc, piece ->
                if (acc.isNotEmpty() && piece.isNotEmpty() && acc.last().isLetterOrDigit() && acc.last().code < 128 &&
                    piece.first().isLetterOrDigit() && piece.first().code < 128) "$acc $piece" else acc + piece
            }
            is JSONObject -> TEXT_KEYS.firstNotNullOfOrNull { value.opt(it) as? String }
                ?: WRAPPER_KEYS.firstNotNullOfOrNull { extractText(value.opt(it), depth + 1) }
                ?: (value.opt("result") as? String) ?: (value.opt("Result") as? String)
            else -> null
        }
    }

    internal fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header.array()); write(pcm) }.toByteArray()
    }
}

/**
 * 录完一段再整段上传，说话中不出预览；火山引擎(豆包)双向流式地址边说边发、边出字。
 * 识别失败时这段录音改用本地模型识别（[localFallback]），
 * 并用 [onNotice] 在键盘上说明，不会无声无息地结束。中文、英文、日语都交给接口。
 */
class OnlineApiAsrBackend(
    private val context: Context,
    private val localFallback: () -> AsrBackend? = { null },
    private val onNotice: (String) -> Unit = {},
) : AsrBackend {
    override val name: String = "在线语音"
    override val finishTimeoutMs: Long = 30_000L

    /** 接口给的文字自带标点；本地补识别的那段没有。 */
    @Volatile private var keepsPunctuation = false
    override val resultKeepsPunctuation: Boolean get() = keepsPunctuation

    private var onResult: ((String) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null
    /** 这次录音的豆包双向流式连接；为 null 时录完整段上传。 */
    @Volatile private var live: VolcengineSpeechApi.LiveSession? = null
    private var onState: ((RecognitionState) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val audio = ByteArrayOutputStream()
    private var language = InputLanguage.CHINESE

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onResult = onResult; onPartial = onPartialResult; onState = onStateChange; this.onError = onError
    }

    override fun initialize(): Boolean = OnlineSpeechApi.isConfigured(context)
    override fun start(): Boolean = start(InputLanguage.CHINESE)

    override fun start(language: InputLanguage): Boolean {
        this.language = language
        synchronized(audio) { audio.reset() }
        live?.cancel()
        live = OnlineSpeechApi.liveSession(context) { text -> onPartial?.invoke(text) }
        onState?.invoke(RecognitionState.LISTENING)
        return true
    }

    override fun canContinueIn(language: InputLanguage): Boolean = language != InputLanguage.UNSPECIFIED

    override fun processAudioChunk(buffer: ByteArray) {
        // 整段留一份：流式出错时这段录音还能交给本地模型。
        synchronized(audio) { audio.write(buffer) }
        live?.feed(buffer)
    }

    override fun stop() {
        val pcm = synchronized(audio) { audio.toByteArray().also { audio.reset() } }
        val session = live.also { live = null }
        if (pcm.isEmpty()) { session?.cancel(); onState?.invoke(RecognitionState.IDLE); return }
        onState?.invoke(RecognitionState.PROCESSING)
        (session?.let { runCatching { it.finish() } } ?: OnlineSpeechApi.transcribe(context, pcm, language))
            .onSuccess { keepsPunctuation = true; onResult?.invoke(it); onState?.invoke(RecognitionState.IDLE) }
            .onFailure { error ->
                if (recognizeLocally(pcm)) onNotice("在线语音没识别出来（${error.message}），这次已改用本地模型")
                else { onError?.invoke("在线语音：${error.message}；本地模型也没能识别"); onState?.invoke(RecognitionState.ERROR) }
            }
    }

    /** 把这段录音交给本地模型识别；本地也不可用时返回 false。 */
    private fun recognizeLocally(pcm: ByteArray): Boolean {
        val local = runCatching { localFallback() }.getOrNull() ?: return false
        return try {
            var text: String? = null
            local.setCallbacks(onResult = { text = (text.orEmpty() + it) }, onPartialResult = null,
                onStateChange = {}, onError = {})
            if (!local.initialize(language) || !local.start(language)) return false
            pcm.asList().chunked(3200).forEach { local.processAudioChunk(it.toByteArray()) }
            local.stop()
            keepsPunctuation = false
            onResult?.invoke(text.orEmpty()); onState?.invoke(RecognitionState.IDLE)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { local.release() }
        }
    }

    override fun cancel() {
        live?.cancel()
        live = null
        synchronized(audio) { audio.reset() }
        onState?.invoke(RecognitionState.IDLE)
    }

    override fun release() = cancel()
    override fun isAvailable(): Boolean = OnlineSpeechApi.isConfigured(context)
}
