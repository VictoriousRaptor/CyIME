package com.kingzcheung.xime.model

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ModelIndexLoader {

    private const val TAG = "ModelIndexLoader"
    private const val MODELS_INDEX_PATH = "models/index.yaml"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    suspend fun loadFromRemote(context: Context): List<ModelInfo> = withContext(Dispatchers.IO) {
        val cfg = KeysConfigHelper.loadXimeIndexConfig(context)
        val baseUrls = cfg.baseUrls

        FileLogger.i(TAG, "Loading model index from ${baseUrls.size} mirrors")
        fetchIndexText(baseUrls)?.let(::parseModelsIndex).orEmpty()
    }

    /** 依次试各条线路，返回第一份能解析出模型的目录原文；都不行时为 null。 */
    internal fun fetchIndexText(baseUrls: List<String>): String? {
        for (base in baseUrls) {
            val host = base.substringAfter("://").substringBefore("/")
            try {
                val url = base.trimEnd('/') + "/" + MODELS_INDEX_PATH
                val text = fetchText(url)
                if (text != null && parseModelsIndex(text).isNotEmpty()) {
                    FileLogger.i(TAG, "Loaded model index from $host")
                    return text
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch from $host: ${e.message}")
            }
        }
        FileLogger.w(TAG, "All mirrors failed for model index")
        return null
    }

    internal fun parseModelsIndex(modelsText: String): List<ModelInfo> {
        return try {
            val root = yaml.parseToYamlNode(modelsText) as? YamlMap ?: return emptyList()
            val modelsNode = root["models"] as? YamlList ?: return emptyList()

            modelsNode.items.mapNotNull { node ->
                parseModelEntry(node as? YamlMap ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to parse models index: ${e.message}")
            emptyList()
        }
    }

    private fun parseModelEntry(entry: YamlMap): ModelInfo? {
        val id = (entry["id"] as? YamlScalar)?.content ?: return null
        val name = (entry["name"] as? YamlScalar)?.content ?: return null
        val description = (entry["description"] as? YamlScalar)?.content ?: ""
        val categoryStr = (entry["category"] as? YamlScalar)?.content ?: "other"
        // 智能联想 11-02 起只用内置 n-gram 联想表，旧索引里的联想模型不再列出。
        if (categoryStr.equals("prediction", ignoreCase = true)) return null
        val size = (entry["size"] as? YamlScalar)?.content ?: ""

        val category = parseCategory(categoryStr)

        val versionsNode = entry["versions"] as? YamlList ?: return null
        val versions = versionsNode.items.mapNotNull { node ->
            parseModelVersion(node as? YamlMap ?: return@mapNotNull null)
        }
        if (versions.isEmpty()) return null

        return ModelInfo(
            id = id,
            name = name,
            description = description,
            category = category,
            size = size,
            versions = versions
        )
    }

    private fun parseModelVersion(versionNode: YamlMap): ModelVersion? {
        val version = (versionNode["version"] as? YamlScalar)?.content
            ?: (versionNode["name"] as? YamlScalar)?.content ?: return null
        val date = (versionNode["date"] as? YamlScalar)?.content ?: ""
        val changelog = (versionNode["changelog"] as? YamlScalar)?.content ?: ""
        val size = (versionNode["size"] as? YamlScalar)?.content ?: ""
        val sha256 = (versionNode["sha256"] as? YamlScalar)?.content
            ?: ((versionNode["archive"] as? YamlMap)?.get("sha256") as? YamlScalar)?.content ?: ""
        val archiveUrl = (versionNode["archive"] as? YamlMap)
            ?.let { (it["url"] as? YamlScalar)?.content }
        val archiveMirrors = strings((versionNode["archive"] as? YamlMap)?.get("mirrors"))

        return ModelVersion(
            version = version,
            date = date,
            changelog = changelog,
            files = parseFiles(versionNode),
            archiveUrl = archiveUrl,
            size = size,
            sha256 = sha256,
            archiveMirrors = archiveMirrors,
        )
    }

    private fun parseFiles(versionNode: YamlMap): List<ModelFile> {
        val filesNode = versionNode["files"] as? YamlList ?: return emptyList()
        return filesNode.items.mapNotNull { node ->
            val fileEntry = node as? YamlMap ?: return@mapNotNull null
            val name = (fileEntry["name"] as? YamlScalar)?.content ?: return@mapNotNull null
            val url = (fileEntry["url"] as? YamlScalar)?.content ?: ""
            ModelFile(name = name, downloadUrl = url,
                sha256 = (fileEntry["sha256"] as? YamlScalar)?.content.orEmpty(),
                sizeBytes = (fileEntry["sizeBytes"] as? YamlScalar)?.content?.toLongOrNull() ?: 0,
                mirrors = strings(fileEntry["mirrors"]))
        }
    }

    private fun strings(node: Any?): List<String> =
        (node as? YamlList)?.items?.mapNotNull { (it as? YamlScalar)?.content }.orEmpty()

    private fun parseCategory(category: String): ModelCategory {
        return when (category.lowercase()) {
            "candidate" -> ModelCategory.CANDIDATE
            "asr" -> ModelCategory.ASR
            "handwriting" -> ModelCategory.HANDWRITING
            "speaker" -> ModelCategory.SPEAKER
            else -> ModelCategory.OTHER
        }
    }

    private fun fetchText(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.takeIf { it.isNotBlank() }
            } else {
                Log.w(TAG, "HTTP ${response.code} fetching $url")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            null
        }
    }
}
