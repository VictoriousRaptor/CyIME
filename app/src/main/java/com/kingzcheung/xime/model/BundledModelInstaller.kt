package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.speech.SpeechModelCatalog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Offline edition: install on IO, then activate a usable local configuration once.
 * User changes/deletions after setup are preserved, including on subsequent upgrades. */
object BundledModelInstaller {
    // 离线翻译两个方向（11-23 他要完整版装上就能离线翻译）：只进完整发布包，老人包、朋友包按打包器清单不带。
    val modelIds = setOf("ochwpro",
        SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.SENSEVOICE, BuiltinModelCatalog.SPEAKER_MODEL,
        "translate-zh-en", "translate-en-zh")
    /** Model ids packed into this offline edition (empty when the manifest is missing). */
    fun bundledIds(context: Context): Set<String> = runCatching {
        val manifest = context.assets.open("bundled-models/manifest.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until manifest.length()).map { manifest.getJSONObject(it).getString("id") }.toSet()
    }.getOrDefault(emptySet())

    /** 解压与“设置向导删掉的不再解压”互斥：向导删除时若正在解压，等它解压完再删。 */
    private val lock = Mutex()
    /** 设置向导删掉的内置模型：留在安装包里，不再解压到手机；用户之后要用时再取回。 */
    private const val SKIPPED = "skipped"

    private fun prefs(context: Context) = context.getSharedPreferences("bundled_models", Context.MODE_PRIVATE)

    private fun entry(context: Context, id: String): JSONObject? = if (!com.kingzcheung.xime.BuildConfig.BUNDLED_MODELS) null
        else runCatching {
            val manifest = context.assets.open("bundled-models/manifest.json").bufferedReader().use { JSONArray(it.readText()) }
            (0 until manifest.length()).map { manifest.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        }.getOrNull()

    /** 安装包里带着的 [model]（内置模型或九键词组模型）的 [version]：返回不联网装回的动作；不带返回 null。 */
    internal fun packagedSource(context: Context, model: ModelInfo, version: ModelVersion): (suspend () -> Unit)? {
        if (model.category == ModelCategory.CANDIDATE) {
            val manifest = runCatching { context.assets.open("rime-bundled-manifest.tsv").bufferedReader().use { it.readText() } }
                .getOrNull() ?: return null
            val rows = manifest.lineSequence().map { it.split('\t') }.filter { it.size == 4 }.toList()
            val assets = version.files.map { file ->
                rows.firstOrNull { it[3] == file.name && it[0].equals(file.sha256, true) && it[1].toLongOrNull() == file.sizeBytes }
                    ?.let { file.name to it[2] } ?: return null
            }
            return {
                ModelManager.installLocal(context, model, version) { staging ->
                    for ((name, asset) in assets) context.assets.open(asset).use { input ->
                        modelDownloadFile(staging, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        val entry = entry(context, model.id)?.takeIf { it.getString("version") == version.version } ?: return null
        return { restore(context, entry) }
    }

    /** 从安装包装回一个内置模型，并且之后照常随升级更新。 */
    private suspend fun restore(context: Context, entry: JSONObject) = lock.withLock {
        val id = entry.getString("id")
        ModelManager.installBundled(context, entry)
        val prefs = prefs(context)
        check(prefs.edit().putString(id, entry.getString("version"))
            .putStringSet(SKIPPED, prefs.getStringSet(SKIPPED, emptySet()).orEmpty() - id).commit())
    }

    /** 设置向导要删掉 [ids]：正在解压就等它完成；之后这些不再从安装包解压。 */
    suspend fun skip(context: Context, ids: Set<String>) = lock.withLock {
        val prefs = prefs(context)
        check(prefs.edit().putStringSet(SKIPPED, prefs.getStringSet(SKIPPED, emptySet()).orEmpty() + ids).commit())
    }

    suspend fun install(context: Context) = lock.withLock {
        DeviceDefaults.initialize(context)
        val prefs = prefs(context)
        val skipped = prefs.getStringSet(SKIPPED, emptySet()).orEmpty()
        val manifest = context.assets.open("bundled-models/manifest.json").bufferedReader().use { JSONArray(it.readText()) }
        val entries = (0 until manifest.length()).map { manifest.getJSONObject(it) }
        // The packager may bundle only some of these; the rest download like the standard edition.
        val bundled = entries.map { it.getString("id") }.toSet()
        check(bundled.isNotEmpty() && bundled.all { it in modelIds }) {
            "内置模型清单与应用不匹配"
        }
        for (entry in entries.sortedBy { when (it.getString("id")) {
            "ochwpro" -> 0; SpeechModelCatalog.PARAFORMER -> 1; else -> 2
        } }) {
            val id = entry.getString("id")
            val version = entry.getString("version")
            if (prefs.getString(id, null) == version || id in skipped) continue
            ModelManager.installBundled(context, entry)
            check(prefs.edit().putString(id, version).commit())
        }
        if (!prefs.getBoolean("defaults_applied", false)) {
            check((bundled - skipped).all { ModelManager.isModelReady(context, it) })
            // Both editions use the same saved first-run defaults. Copying files never selects models.
            check(prefs.edit().putBoolean("defaults_applied", true).commit())
        }
        if (prefs.getInt("edition_profile", 0) < 2) {
            // Legacy small selections cannot be distinguished from manual choices. Preserve them.
            check(prefs.edit().putInt("edition_profile", 2).commit())
        }
    }
}
