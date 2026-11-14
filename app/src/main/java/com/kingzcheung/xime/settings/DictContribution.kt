package com.kingzcheung.xime.settings

import android.content.Context
import androidx.annotation.WorkerThread
import com.kingzcheung.xime.handwriting.training.HandwritingContribution
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * 「贡献词库」：从本机用户词库挑出常用词和次数（规则见 [DictContributionRules]），用户看过、取消不想给的，
 * 再分享成文件发给作者，或者上传。
 *
 * - 什么都不会自己发出去：只有用户在这一页点分享或上传才离开手机。
 * - 用户词库原件只在读的时候导出到缓存，挑完马上删掉。
 * - 文件带一个本机随机生成的 `contributor`（不含账号或设备标识），作者合并时用它数「有几个人用这个词」：
 *   至少几个人都用的词才进词库，只有一个人用的名字不会进去。
 * - 上传和手写样本用同一个接收端（[HandwritingContribution.UPLOAD_URL]），靠 `X-CyIME-Format` 区分；
 *   接收端没建好时地址为空，页面上只有「分享」。
 */
object DictContribution {
    const val UPLOAD_URL = HandwritingContribution.UPLOAD_URL
    const val MAX_UPLOAD_BYTES = 1L * 1024 * 1024

    const val CONSENT_TEXT = "这些词只用于改进 CyIME 词库，整理出的词库会随 CyIME 公开发布。" +
        "发送前可逐词取消。魔搭接收勾选的词、拼音、次数、方案、应用版本及独立随机编号；原始贡献按此用途处理，公开的是汇总后的词库。"

    private const val PREFS = "dict_contribute"
    private const val DIR = "dict_contribute"

    fun uploadAvailable(): Boolean = UPLOAD_URL.isNotEmpty()

    /** 本机随机生成、只用来数「几个人用过这个词」的编号。和手写样本的编号分开，两份数据对不上同一个人。 */
    fun contributorId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("contributor", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("contributor", id).apply()
        return id
    }

    fun consented(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("consent", false)

    fun setConsented(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("consent", true).apply()
    }

    /**
     * 用户在贡献页删掉的词（「词<TAB>拼音」）：以后不再列出，也不会分享或上传。只记在本页，不动用户词库。
     */
    fun excluded(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("excluded", null)?.toSet().orEmpty()

    fun exclude(context: Context, word: DictContributionRules.Word): Set<String> {
        val next = excluded(context) + excludedKey(word)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("excluded", next).apply()
        return next
    }

    fun clearExcluded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("excluded").apply()
    }

    fun excludedKey(word: DictContributionRules.Word) = word.text + "\t" + word.pinyin

    data class Loaded(val schema: String, val selection: DictContributionRules.Selection)

    /** 读当前方案的用户词库并挑词。在 IO 线程调用；键盘引擎还没起来时抛异常。 */
    @WorkerThread
    suspend fun load(context: Context): Loaded {
        check(RimeConfigHelper.prepareEngine(context)) { "词库还没准备好" }
        val engine = RimeEngine.getInstance()
        check(engine.ensureSession(10_000L)) { "词库还没准备好" }
        val schema = engine.getCurrentSchema()
        val own = buildSet {
            PersonalDictManager.loadCustomPhrases(context).forEach { add(it.word) }
            if (schema.isNotEmpty()) {
                PersonalDictManager.loadCustomPhrases(context, schema).forEach { add(it.word) }
                PersonalDictManager.loadEntries(context, schema).forEach { add(it.word) }
            }
        }
        val raw = File(context.cacheDir, "dict_contribute_userdb.txt")
        try {
            check(engine.exportUserDict(raw.path)) { "读不到用户词库" }
            // 方案词库里有的词不算人名。查词不可用时（连「我们」都查不到）像人名的一律去掉。
            val lookupWorks = engine.lookupText("我们").isNotEmpty()
            val inDictionary: (String) -> Boolean = { lookupWorks && engine.lookupText(it).isNotEmpty() }
            val selection = raw.useLines(Charsets.UTF_8) { DictContributionRules.select(it, own, inDictionary) }
            return Loaded(schema, selection)
        } finally {
            raw.delete()
        }
    }

    /** 写出要分享或上传的文件；只留最新一份。 */
    fun writeFile(context: Context, words: List<DictContributionRules.Word>, schema: String, appVersion: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val id = contributorId(context)
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val file = File(dir, "cyime_dict_contribute_${id}_$date.txt")
        file.writeText(DictContributionRules.encode(words, id, schema, appVersion), Charsets.UTF_8)
        return file
    }

    /** 上传整份文件（接收端按 contributor 只留最新一份）。在 IO 线程调用；接收端返回 2xx 才算成功。 */
    @WorkerThread
    fun upload(context: Context, file: File, appVersion: String) {
        check(uploadAvailable()) { "还不能上传" }
        check(consented(context)) { "还没同意" }
        val body = gzip(file.readBytes())
        check(body.size <= MAX_UPLOAD_BYTES) { "文件太大，请改用分享" }
        val request = Request.Builder().url(UPLOAD_URL)
            .header("User-Agent", "CyIME/$appVersion")
            .header("X-CyIME-Writer", contributorId(context))
            .header("X-CyIME-Format", "${DictContributionRules.FORMAT}/${DictContributionRules.VERSION}")
            .post(body.toRequestBody("application/gzip".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val receipt = runCatching { JSONObject(response.body.string()) }.getOrNull()
            if (!response.isSuccessful || receipt?.optBoolean("ok") != true || receipt.optInt("count", 0) < 1) {
                throw IllegalStateException(receipt?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "上传未获确认（${response.code}）")
            }
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
