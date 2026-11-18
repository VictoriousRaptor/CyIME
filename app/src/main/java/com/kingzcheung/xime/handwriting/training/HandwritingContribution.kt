package com.kingzcheung.xime.handwriting.training

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * 「贡献手写样本」：公开版里按题目写字（每人随机，见 [prompts]），写好的样本分享成文件或上传给作者，用来训练手写模型。
 *
 * - 只收这一页写的字；输入法手写板平时写的内容不经过这里，也不会被保存或上传。
 * - 样本存在 app 私有目录 `files/handwriting_contribute/`，和训练模式的样本分开。
 * - 每条样本带一个本机随机生成的 `writer`（不含任何账号或设备标识），用来把同一个人写的字归在一起。
 * - 上传前要先同意一次「样本用于训练 CyIME 手写模型」。
 */
object HandwritingContribution {
    /** 样本目录和进度存储名，传给 [TrainingSampleStore]。 */
    const val STORE_NAME = "handwriting_contribute"

    /**
     * 手写、词库与滑行练习共用的魔搭创空间接收端，按请求头区分格式。
     * 手写按 [TrainingSampleCodec.FORMAT] 发送，gzip 后不超过 [MAX_UPLOAD_BYTES]。
     */
    const val UPLOAD_URL = "https://cyletix-cyime-contribute.ms.show/upload"

    const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024

    const val LICENSE = "CC0-1.0"
    const val LICENSE_URL = "https://creativecommons.org/publicdomain/zero/1.0/deed.zh-hans"
    const val CONSENT_TEXT = "分享或上传的是贡献页选定的手写样本，包括题目、笔迹与时间、画布、识别结果（如有）、随机书写人编号、机型和应用版本。上传由魔搭接收，日常输入不会随样本发送。"
    const val LICENSE_TEXT = "我有权贡献这些样本，同意按 CC0 1.0 将其贡献至公共领域。样本可公开，任何人可复制、修改、再发布或商用。已经授予的许可无法撤回。"

    internal fun licensedSample(line: String): String =
        JSONObject(line).put("license", LICENSE).toString()

    private const val ASSET = "handwriting/contribute_prompts.tsv"
    private const val PREFS = "handwriting_contribute"

    fun uploadAvailable(): Boolean = UPLOAD_URL.isNotEmpty()

    /**
     * 这个人的题：常用字、生僻字、两字词按 [writerId] 打乱穿插，每个人不一样，几千道写不完（见 [ContributePromptDeck]）。
     * contribute_prompts.tsv 里缺数据的常用字排在前面几轮；题库读不到时只出这 150 道固定题。
     */
    fun prompts(context: Context): List<TrainingPrompt> {
        val fixed = asset(context, ASSET) { parse(it) }
        return runCatching {
            val pools = asset(context, ContributePromptDeck.POOL_ASSET) { ContributePromptDeck.pools(fixed, it) }
            ContributePromptDeck.handwriting(pools, ContributePromptDeck.seedOf(writerId(context)))
        }.getOrElse { fixed }
    }

    private fun <T> asset(context: Context, name: String, read: (Sequence<String>) -> T): T =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).useLines(read)

    internal fun parse(lines: Sequence<String>): List<TrainingPrompt> = lines.mapNotNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val cols = line.split('\t')
        if (cols.size < 2 || cols[1].isBlank()) return@mapNotNull null
        val kind = cols[0].trim()
        val text = cols[1].trim()
        TrainingPrompt(
            id = "contribute:$kind:$text",
            type = TrainingPromptType.Chinese,
            label = text,
            source = "contribute",
            hint = cols.getOrNull(2)?.trim()?.ifEmpty { null },
            kind = kind,
        )
    }.toList()

    /** 本机随机生成、只用于给样本分组的书写人编号。 */
    fun writerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("writer", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("writer", id).apply()
        return id
    }

    fun consented(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("accepted_license", null) == LICENSE

    fun setConsented(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("accepted_license", LICENSE).apply()
    }

    /** 上次上传时样本文件已有的行数：再上传只传新写的。 */
    fun uploadedLines(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("uploaded_lines", 0)

    private fun setUploadedLines(context: Context, lines: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("uploaded_lines", lines).apply()
    }

    fun resetUploaded(context: Context) {
        setUploadedLines(context, 0)
        setHeld(context, emptySet())
    }

    /** 已经传到服务器的条数：处理过的行里减去服务器当时收不了、留着下次再传的。 */
    fun uploadedCount(context: Context): Int = (uploadedLines(context) - held(context).size).coerceAtLeast(0)

    /** 服务器当时收不了（题目太长、笔画太多）而留下的样本 id，接收端放宽后下次上传补传。 */
    internal fun held(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("held_ids", null).orEmpty().toSet()

    private fun setHeld(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("held_ids", ids.toSet()).apply()
    }

    /**
     * 接收端对每条样本的上限。线上接收端（11-03 起）题目最多 8 个字、60 笔、4000 点；
     * 1.15.89 起能写公式、一段话，题目更长，一条超限整批都会被拒（「题目不对」），以后每次上传都卡在同一批。
     * 新接收端在健康检查里报 max_label / max_strokes / max_points，没报就按旧上限。
     */
    internal data class Limits(val label: Int = 8, val strokes: Int = 60, val points: Int = 4000) {
        fun accepts(line: String): Boolean {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return false
            if (o.optString("label").length > label) return false
            val list = o.optJSONArray("strokes") ?: return true
            if (list.length() > strokes) return false
            var n = 0
            for (i in 0 until list.length()) n += list.optJSONObject(i)?.optJSONArray("points")?.length() ?: 0
            return n <= points
        }
    }

    private fun limits(appVersion: String): Limits = runCatching {
        val request = Request.Builder().url(endpoint.removeSuffix("upload")).header("User-Agent", "CyIME/$appVersion").get().build()
        client.newCall(request).execute().use { r ->
            val o = JSONObject(r.body.string())
            val base = Limits()
            Limits(o.optInt("max_label", base.label), o.optInt("max_strokes", base.strokes), o.optInt("max_points", base.points))
        }
    }.getOrElse { Limits() }

    /**
     * 自用包里「手写训练」一页两用：记住上次停在贡献题还是训练题库，下次进来接着写。
     * 没切换过时进训练题库，顶栏就是题库分类（中文汉字、英语、日语…）；11-08 改成默认进贡献题后，
     * 一进来只见「贡献手写样本」、看不到分类（用户 11-09：「没有分类选择了」），改回。
     */
    fun lastOpened(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("last_opened", false)

    fun setLastOpened(context: Context, contribute: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("last_opened", contribute).apply()
    }

    /** 贡献页选的「写什么」（随机、常用字、少用字、词语、自己输入），下次进来照旧。 */
    fun choice(context: Context): ContributePromptDeck.Choice {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("choice", null)
        return ContributePromptDeck.Choice.entries.firstOrNull { it.name == name } ?: ContributePromptDeck.Choice.Random
    }

    fun setChoice(context: Context, choice: ContributePromptDeck.Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("choice", choice.name).apply()
    }

    /** 「自己输入」填的字词，原样存着。 */
    fun ownText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("own_text", null).orEmpty()

    fun setOwnText(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("own_text", text).apply()
    }

    /** 「一段话」填的那段话，原样存着。 */
    fun paragraphText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("paragraph_text", null).orEmpty()

    fun setParagraphText(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("paragraph_text", text).apply()
    }

    /** 「随机」以外各类写到第几题（随机的位置沿用原来存的那份，升级后接着写）。 */
    fun position(context: Context, choice: ContributePromptDeck.Choice): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("pos_${choice.name}", 0)

    fun setPosition(context: Context, choice: ContributePromptDeck.Choice, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("pos_${choice.name}", index).apply()
    }

    /** 上传结果：[count] 是这次传了几条；没有新样本时为 0。[held] 是服务器暂时收不了、留着的条数（可改用分享）。 */
    data class UploadResult(val count: Int, val held: Int = 0)

    /** 实际请求的地址；单元测试换成本机服务器。 */
    internal var endpoint: String = UPLOAD_URL

    /** 一次请求最多传这么多条：接收端一次最多收 1000 条，写得多的人分几次传。 */
    internal const val UPLOAD_CHUNK_LINES = 300

    /**
     * 把样本文件里还没上传过的行 gzip 后分批上传。在 IO 线程调用。
     * 每批接收端确认后就记下已上传到哪一行；中途失败抛异常，已确认的批次不会重传，下次从没传的接着传。
     * 服务器收不了的条目（[Limits]）不放进请求，记下 id 留着，不再让一条卡住后面所有的；接收端放宽后下次补传。
     */
    fun upload(context: Context, samples: File, appVersion: String): UploadResult {
        check(uploadAvailable()) { "还不能上传" }
        check(consented(context)) { "还没同意" }
        val lines = if (samples.isFile) samples.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()
        val limits = limits(appVersion)
        var done = uploadedLines(context).coerceIn(0, lines.size)
        val held = held(context).toMutableSet()
        var sent = 0
        // 以前留下的，现在接收端收得了就补传
        if (held.isNotEmpty()) {
            for (batch in lines.subList(0, done).filter { idOf(it) in held && limits.accepts(it) }.chunked(UPLOAD_CHUNK_LINES)) {
                post(context, batch, appVersion)
                sent += batch.size
                held -= batch.map(::idOf).toSet()
                setHeld(context, held)
            }
        }
        while (done < lines.size) {
            val chunk = lines.subList(done, minOf(lines.size, done + UPLOAD_CHUNK_LINES))
            val (fits, tooBig) = chunk.partition(limits::accepts)
            if (fits.isNotEmpty()) post(context, fits, appVersion)
            sent += fits.size
            held += tooBig.map(::idOf)
            setHeld(context, held)
            done += chunk.size
            setUploadedLines(context, done)
        }
        return UploadResult(sent, held.size)
    }

    private fun idOf(line: String): String = runCatching { JSONObject(line).optString("id") }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: line.hashCode().toString()

    private fun post(context: Context, batch: List<String>, appVersion: String) {
        val body = gzip(batch.joinToString("\n", postfix = "\n", transform = ::licensedSample).toByteArray(Charsets.UTF_8))
        check(body.size <= MAX_UPLOAD_BYTES) { "样本太多，请先分享文件" }
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", "CyIME/$appVersion")
            .header("X-CyIME-Writer", writerId(context))
            .header("X-CyIME-Format", "${TrainingSampleCodec.FORMAT}/${TrainingSampleCodec.VERSION}")
            .post(body.toRequestBody("application/gzip".toMediaType()))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.InterruptedIOException) {
            // 魔搭创空间闲置会休眠，第一次请求要等它醒
            throw IllegalStateException("服务器在启动，过一分钟再点上传", e)
        }
        response.use {
            val receipt = runCatching { JSONObject(it.body.string()) }.getOrNull()
            if (!it.isSuccessful || receipt?.optBoolean("ok") != true || receipt.optInt("count", -1) != batch.size) {
                throw IllegalStateException(receipt?.optString("error")?.takeIf { e -> e.isNotBlank() }
                    ?: "上传未获确认（${it.code}）")
            }
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 休眠的创空间醒来要几十秒
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
