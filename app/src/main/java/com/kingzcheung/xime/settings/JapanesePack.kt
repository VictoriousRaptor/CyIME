package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.DefaultModelInstaller
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

/**
 * 日语词库（azooKey 词典）和日语转换引擎（libCyAzooKey.so）在哪：
 * - 带模型的安装包（完整版、离线包、朋友包）：都在安装包里（assets/azookey、lib/<abi>），和以前一样；
 * - 轻量版（11-11 起）：不带（安装包小约 24 MB），装「日语」插件（[JapanesePlugin]）时从模型目录下载
 *   （[BuiltinModelCatalog.JAPANESE_DICTIONARY]、[BuiltinModelCatalog.JAPANESE_ENGINE]，Release `models` + 魔搭）。
 * 词库照旧解到 rime/azookey/dictionary（[JapaneseSchemas.installAzooKeyDictionary]，按内容哈希核对）。以前从安装包
 * 解出来的那份版本相同就直接用：用过日语的老用户升级后只差引擎，只下约 5 MB。
 * 日语翻译器（jni/cyime_azookey）找不到引擎、打不开词库时每 5 秒重试一次，下载好不用重启键盘；没好之前日语只显示罗马字。
 */
object JapanesePack {
    /** azooKey 词典的内容哈希：tasks-japanese.gradle.kts 的 azooKeyMergedDictionaryHash，词典换了两边和模型目录一起改。 */
    const val DICTIONARY_VERSION = "7776faa0dc60bea48b2e24824d6e45924bed3a85a0b8263d7d4859d39f9a9745"
    private const val TAG = "JapanesePack"
    private const val LIBRARY = "libCyAzooKey.so"
    /** 日语翻译器从这个环境变量找下载的引擎（安装包里有的先用包里的）。 */
    private const val LIBRARY_ENV = "CYIME_AZOOKEY_LIBRARY"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun rimeDir(context: Context) = File(context.filesDir, "rime")

    /** 安装包里带着词库（带模型的安装包）。 */
    fun dictionaryBundled(context: Context): Boolean = runCatching {
        context.assets.open("azookey/dictionary.version").use { true }
    }.getOrDefault(false)

    /** 词库已经解好，是这一版（从安装包解的、下载后解的都算）。 */
    private fun dictionaryUnpacked(context: Context): Boolean = runCatching {
        File(rimeDir(context), "azookey/dictionary/.cyime-version").readText() == DICTIONARY_VERSION
    }.getOrDefault(false)

    private fun engineBundled(context: Context): Boolean =
        context.applicationInfo.nativeLibraryDir?.let { File(it, LIBRARY).isFile } == true

    /** 这台手机能下载的引擎；32 位 x86 没有（Swift 不支持），那里日语一直只显示罗马字，和以前一样。 */
    private fun engineModel(): ModelInfo? = ModelManager.getModel(BuiltinModelCatalog.JAPANESE_ENGINE)

    private fun downloadedLibrary(context: Context) =
        File(ModelStorage.getModelDir(context, BuiltinModelCatalog.JAPANESE_ENGINE), LIBRARY)

    fun engineReady(context: Context): Boolean = engineBundled(context) || engineModel() == null ||
        ModelManager.isModelDownloaded(context, BuiltinModelCatalog.JAPANESE_ENGINE)

    fun dictionaryReady(context: Context): Boolean = dictionaryBundled(context) || dictionaryUnpacked(context)

    /** 日语能转换了（或这台手机本来就只能显示罗马字）。 */
    fun ready(context: Context): Boolean = engineReady(context) && dictionaryReady(context)

    /** 还要下载的模型；词库下载了还没解开的不算（[installDictionary] 会解）。 */
    fun missing(context: Context): List<ModelInfo> = listOfNotNull(
        engineModel()?.takeUnless { engineReady(context) },
        ModelManager.getModel(BuiltinModelCatalog.JAPANESE_DICTIONARY)?.takeUnless {
            dictionaryReady(context) || ModelManager.isModelDownloaded(context, it)
        },
    )

    /** 要下载的字节数，按下载的大小算（引擎是压缩包，按目录里写的压缩后大小）。 */
    fun downloadBytes(models: List<ModelInfo>): Long = models.sumOf { downloadBytes(it) }

    private fun downloadBytes(model: ModelInfo): Long {
        val version = model.resolvedVersion() ?: return 0L
        val archived = version.archiveUrl?.let { version.size.substringBefore(" MB").trim().toDoubleOrNull() }
        return archived?.let { (it * 1024 * 1024).toLong() } ?: version.files.sumOf { it.sizeBytes }
    }

    /**
     * 下载缺的部分：立刻开始（装插件、打开日语都是用户自己点的；老用户升级后只差约 5 MB 的引擎，流量下也下），
     * 同时排进默认模型队列，这次没网或失败时联网后、下次启动时自动再试；在模型管理里删掉就不再自动补（[DefaultModelInstaller]）。
     */
    fun request(context: Context) {
        val app = context.applicationContext ?: context
        scope.launch {
            runCatching { installDictionary(app) }.onFailure { FileLogger.e(TAG, "日语词库解压失败", it) }
            val models = missing(app)
            if (models.isEmpty()) return@launch
            DefaultModelInstaller.requestGuided(app, models.map { it.id }.toSet(), allowMetered = false)
            models.forEach { ModelManager.downloadModelInBackground(app, it) }
        }
    }

    /**
     * 把词库解到 rime/azookey/dictionary：安装包里有就用包里的（以前的做法），否则用下载的；版本相同不重解。
     * 在后台线程调用（Rime 准备时、词库下载完时）。
     */
    fun installDictionary(context: Context) {
        val rime = rimeDir(context)
        if (dictionaryBundled(context)) {
            JapaneseSchemas.installAzooKeyDictionary(rime) { context.assets.open(it) }
            return
        }
        if (dictionaryUnpacked(context) ||
            !ModelManager.isModelDownloaded(context, BuiltinModelCatalog.JAPANESE_DICTIONARY)) return
        val dir = ModelManager.getModelStorageDir(context, BuiltinModelCatalog.JAPANESE_DICTIONARY) ?: return
        val zip = File(dir, "dictionary.zip")
        JapaneseSchemas.installAzooKeyDictionary(rime) { name ->
            when (name) {
                "azookey/dictionary.version" -> DICTIONARY_VERSION.byteInputStream()
                "azookey/dictionary.zip" -> zip.inputStream()
                else -> throw FileNotFoundException(name)
            }
        }
    }

    /** 告诉日语翻译器下载的引擎放在哪。Rime 启动前调用；安装包里带着引擎时翻译器先用包里的。 */
    fun exportLibraryPath(context: Context) {
        val path = downloadedLibrary(context).absolutePath
        runCatching { if (android.system.Os.getenv(LIBRARY_ENV) != path) android.system.Os.setenv(LIBRARY_ENV, path, true) }
            .onFailure { FileLogger.e(TAG, "设置日语引擎路径失败", it) }
    }

    /** 模型下载装好以后（ModelManager 调用）：词库马上解开，引擎设为只读。 */
    internal fun afterInstall(context: Context, id: String) {
        val app = context.applicationContext ?: context
        when (id) {
            BuiltinModelCatalog.JAPANESE_DICTIONARY -> scope.launch {
                runCatching { installDictionary(app) }.onFailure { FileLogger.e(TAG, "日语词库解压失败", it) }
            }
            BuiltinModelCatalog.JAPANESE_ENGINE -> runCatching { downloadedLibrary(app).setWritable(false, false) }
        }
    }

    /**
     * 卸载「日语」插件时：删掉下载的词库、引擎和解开的词库，学习记录（rime/azookey/memory）留着。
     * 先关掉日语再调用（日语方案不在用，翻译器不会再读词库）。安装包里带的不动。
     */
    internal fun removeDownloads(context: Context) {
        if (dictionaryBundled(context)) return
        for (id in listOf(BuiltinModelCatalog.JAPANESE_DICTIONARY, BuiltinModelCatalog.JAPANESE_ENGINE))
            if (ModelManager.getModel(id) != null) ModelManager.deleteModel(context, id)
        File(rimeDir(context), "azookey/dictionary").deleteRecursively()
    }
}
