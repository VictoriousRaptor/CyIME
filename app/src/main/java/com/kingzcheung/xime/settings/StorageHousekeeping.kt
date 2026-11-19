package com.kingzcheung.xime.settings

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 启动时清掉上次进程被杀（覆盖安装、系统回收）时没来得及删的临时文件，以及不再使用的模型。
 * 这些文件只在操作正常结束时删除：下载到一半的模型、导入和方案安装的暂存、
 * 模型替换时挪开的旧目录、发布到 rime/ 途中的链接，一个就可能几百 MB。
 */
object StorageHousekeeping {
    private const val TAG = "StorageHousekeeping"
    private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    /** ModelDownloader / ModelImporter / SchemaManager 的暂存，以及归档模型下载中的 tar.bz2。 */
    private val CACHE_TEMP = Regex("^(?:model-download-$UUID|model-import-$UUID|schema-install-$UUID|-?\\d+\\.tar\\.bz2)$")
    /** installDownloadedModel 替换时挪开的旧模型目录。 */
    private val MODEL_PREVIOUS = Regex("^\\.(.+)-$UUID\\.previous$")
    /** ModelDownloader / installLocal / installBundled 在 models/ 里的暂存目录。 */
    private val MODEL_STAGING = Regex("^\\.(?:download|import|bundle)-.+-$UUID$")
    /** installCandidateModel 发布前的临时链接；源文件仍在 models/，删掉不丢数据。 */
    private val RUNTIME_INSTALLING = Regex("^\\..+\\.$UUID\\.installing$")
    /** 不再使用的模型：智能联想 11-02 起只用内置 n-gram 联想表，以前下载或从安装包取出的联想模型一并删掉。 */
    internal val RETIRED_MODELS = setOf("predictive-text-base", "predictive-text-small")

    /** 发给别的 App 的图片副本（ImeTextCommit / ClipboardManager），对方提交时就读走了。 */
    internal const val SHARED_COPIES = "emoji_cache"
    internal const val SHARED_COPY_MAX_AGE_MS = 24L * 60 * 60 * 1000
    /**
     * 缓存目录里自己管大小的：图片加载缓存（32 MB）、剪贴板图片（有预算，被历史引用）、更新安装包（只留一个版本）、
     * 打字速度分享图（一张）、文字识别截图（上面单独清）。WebView 的缓存由它自己管。
     */
    private val SELF_MANAGED_CACHE = setOf("coil_cache", "clipboard_images", "updates", "stats_share", "ocr-frames", SHARED_COPIES)
    /** 其余的（导出、导入、下载失败、测试留下的临时文件）三天没动过就删：正在用的文件不会三天不写。 */
    internal const val STALE_CACHE_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    /** 缓存目录里该删的：发出去超过一天的图片副本，以及不归谁管、三天没动过的条目。 */
    fun findStaleCache(cacheDir: File, now: Long = System.currentTimeMillis()): List<File> =
        File(cacheDir, SHARED_COPIES).listFiles { file -> file.lastModified() < now - SHARED_COPY_MAX_AGE_MS }.orEmpty().toList() +
            cacheDir.listFiles { file ->
                file.name !in SELF_MANAGED_CACHE && !file.name.contains("webview", ignoreCase = true) &&
                    !file.name.contains("chromium", ignoreCase = true) && newestChange(file) < now - STALE_CACHE_MAX_AGE_MS
            }.orEmpty()

    /** 目录取里面最近改动的时间，免得删掉还有文件在写的目录。 */
    private fun newestChange(file: File): Long =
        if (file.isDirectory) file.walkTopDown().maxOf { it.lastModified() } else file.lastModified()

    /**
     * 系统按文件所属的组记“缓存”：缓存目录里写出的文件挪进数据目录后还是缓存组（10-19 以前下载的模型、
     * 从缓存挪出来的目录里后来新建的文件），系统设置里缓存显示几 GB，清缓存又清不掉。
     * 把数据目录（缓存目录除外）里还属于缓存组的文件和目录改回应用自己的组，只改记账，不动内容。
     * @return 改回的字节数
     */
    fun reclaimCacheAccounting(context: Context): Long {
        val cacheGid = Os.stat(context.cacheDir.path).st_gid
        val appGid = Os.getgid()
        if (cacheGid == appGid) return 0
        val skipped = listOfNotNull(context.cacheDir, context.codeCacheDir).map { it.canonicalFile.toPath() }.toSet()
        return regroup(context.dataDir.canonicalFile.toPath(), skipped) { path ->
            val stat = Os.lstat(path.toString())
            if (stat.st_gid != cacheGid || OsConstants.S_ISLNK(stat.st_mode)) return@regroup -1L
            Os.chown(path.toString(), -1, appGid)
            if (OsConstants.S_ISREG(stat.st_mode)) stat.st_size else 0L
        }
    }

    /**
     * 走一遍 [root]（不跟随链接，跳过 [skipped] 目录），对每个目录和文件调用 [regroupOne]：
     * 它改了组就返回字节数，没改返回 -1。单个文件失败不影响其他文件。
     */
    internal fun regroup(root: Path, skipped: Set<Path>, regroupOne: (Path) -> Long): Long {
        var bytes = 0L
        fun visit(path: Path) {
            runCatching { regroupOne(path) }.getOrNull()?.takeIf { it > 0 }?.let { bytes += it }
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir in skipped) return FileVisitResult.SKIP_SUBTREE
                visit(dir)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isSymbolicLink) visit(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return bytes
    }

    /** 清理后缓存目录仍超过 [BREAKDOWN_THRESHOLD] 时，按顶层条目列出各占多少（大的在前），写进日志好查是谁。 */
    internal fun cacheBreakdown(cacheDir: File): String? {
        val sizes = cacheDir.listFiles().orEmpty().map { it.name to StorageStats.directorySize(it) }
        val total = sizes.sumOf { it.second }
        if (total < BREAKDOWN_THRESHOLD) return null
        return "缓存目录共 ${total / 1024 / 1024} MB：" + sizes.sortedByDescending { it.second }.take(12)
            .joinToString("，") { (name, size) -> "$name ${size / 1024 / 1024} MB" }
    }

    internal const val BREAKDOWN_THRESHOLD = 200L * 1024 * 1024

    /**
     * 只收集调用时已经存在的条目，所以必须在本进程开始任何下载、导入或安装之前调用；
     * 之后新建的临时文件名字带新的 UUID，不会被这次清理碰到。
     */
    fun findLeftovers(cacheDir: File, modelsDir: File, rimeDir: File): List<File> =
        cacheDir.listFiles { file -> CACHE_TEMP.matches(file.name) }.orEmpty().toList() +
            // 文字识别请求的截图，识别完即删；进程被杀时留下。
            File(cacheDir, "ocr-frames").listFiles { file -> file.isFile }.orEmpty() +
            modelsDir.listFiles { file -> MODEL_PREVIOUS.matches(file.name) || MODEL_STAGING.matches(file.name) ||
                file.name in RETIRED_MODELS }.orEmpty() +
            rimeDir.listFiles { file -> file.isFile && RUNTIME_INSTALLING.matches(file.name) }.orEmpty()

    /** 返回腾出的字节数。被挪开的旧模型若原位置已空（替换中途被杀），放回原处而不是删除。 */
    fun removeLeftovers(files: List<File>): Long {
        var freed = 0L
        for (file in files) {
            val original = MODEL_PREVIOUS.matchEntire(file.name)?.let { File(file.parentFile, it.groupValues[1]) }
            if (original != null && !original.exists()) {
                if (!file.renameTo(original)) FileLogger.w(TAG, "无法放回被挪开的模型 ${file.name}")
                continue
            }
            val size = StorageStats.directorySize(file)
            if (file.deleteRecursively()) freed += size
        }
        return freed
    }

    /** 主进程启动时调用：同步列出残留（很快），后台删除，再把误记成缓存的数据改回来。 */
    fun sweepAtStartup(context: Context, scope: CoroutineScope) {
        val leftovers = runCatching {
            (findLeftovers(context.cacheDir, StorageStats.modelsDir(context), StorageStats.rimeDir(context)) +
                // 拍照识别模型改为直接映射安装包后，以前解出来的副本（约 21 MB）。
                listOfNotNull(com.kingzcheung.xime.ocr.OcrModelFiles.obsoleteExtractedDir(context))).distinct()
        }.getOrDefault(emptyList())
        scope.launch {
            if (leftovers.isNotEmpty()) runCatching { removeLeftovers(leftovers) }
                .onSuccess { FileLogger.i(TAG, "清掉上次中断留下的临时文件 ${leftovers.size} 项，共 ${it / 1024 / 1024} MB") }
                .onFailure { FileLogger.w(TAG, "清理临时文件失败：${it.message}") }
            runCatching { findStaleCache(context.cacheDir).filter { it.exists() }.let { it.size to removeLeftovers(it) } }
                .onSuccess { (count, freed) -> if (count > 0) FileLogger.w(TAG, "清掉过期缓存 $count 项，共 ${freed / 1024 / 1024} MB") }
                .onFailure { FileLogger.w(TAG, "清理过期缓存失败：${it.message}") }
            runCatching { reclaimCacheAccounting(context) }
                .onSuccess { if (it > 0) FileLogger.w(TAG, "数据目录里 ${it / 1024 / 1024} MB 原先被系统算成缓存，已改回数据") }
                .onFailure { FileLogger.w(TAG, "改回缓存记账失败：${it.message}") }
            runCatching { cacheBreakdown(context.cacheDir) }.getOrNull()?.let { FileLogger.w(TAG, it) }
        }
    }
}
