package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.content.Context
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"
    /** app 固定的 default.custom.yaml 模板（assets 根，与 xime.yaml 并列，不随 submodule 分发）。 */
    private const val ASSETS_DEFAULT_CUSTOM = "default.custom.yaml"
    private const val BUFFER_SIZE = 8192

    /** 市场索引中随 app 发布的内置默认方案集条目 id（见 rimes/index.yaml 的 `builtin`）。 */
    private const val BUILTIN_MARKET_ID = "builtin"
    /** 内置方案集的初始占位版本：`0.0.0` 保证不等于任何真实 git tag，从而在市场提示「更新」。 */
    private const val BUILTIN_PLACEHOLDER_VERSION = "0.0.0"

    /** 部署互斥：Application 预初始化与输入法服务初始化可能并发触发部署，串行化避免重复/并发全量编译。 */
    private val deploymentLock = Any()
    private val assetInitializationMutex = kotlinx.coroutines.sync.Mutex()
    
    private val preparationMutex = kotlinx.coroutines.sync.Mutex()
    private var preparedInProcess = false
    const val DEPLOYMENT_REVISION = "rime_deployment_revision"
    /** 最近一次部署没过的方案和原因（给设置页、输入法提示用）；空表示上次部署没有问题。 */
    const val DEPLOYMENT_PROBLEM = "rime_deployment_problem"

    /** Retry failures, never queue user keystrokes, and never overlap a running native deployment. */
    suspend fun prepareAutomatically(context: Context, status: (String) -> Unit = {}) {
        // 已经准备好就直接返回，不报「正在准备」：改语言、刷新语言列表都会走到这里，键盘不该为此闪一下提示。
        // 别的准备正在进行（拿不到锁）时照常报进度。
        if (preparationMutex.tryLock()) {
            try { if (readyInProcess(context)) return } finally { preparationMutex.unlock() }
        }
        retryPreparation(status = status, problem = { deploymentProblem(context) },
            preparing = { preparingNotice(context) }) { prepareEngine(context) }
    }

    // A previous IME service may have destroyed the process-wide engine.
    // Cached preparation is valid only while that native session is still usable.
    private fun readyInProcess(context: Context): Boolean =
        preparedInProcess && SettingsPreferences.isDeploymentDone(context) && RimeEngine.isInitialized() &&
            RimeEngine.getInstance().ensureSession(1_000L)

    /** 这次要准备的是哪些语言：只看还没编译出来的启用方案，中文早已编译好时不再说「准备中文」。 */
    private fun preparingNotice(context: Context): String {
        val build = File(context.filesDir, "rime/build")
        val missing = runCatching {
            SchemaManager.getEnabledSchemas(context).filter { missingBuildArtifact(build, it) != null }
                .map { com.kingzcheung.xime.settings.InputLanguage.forSchema(it) }
        }.getOrDefault(listOf(com.kingzcheung.xime.settings.InputLanguage.CHINESE))
        return preparingMessage(missing, firstTime = build.listFiles().isNullOrEmpty())
    }

    /** Share the entire copy/init/deploy operation, not just the final compilation lock. */
    suspend fun prepareEngine(context: Context): Boolean = preparationMutex.withLock {
        if (readyInProcess(context)) return@withLock true
        preparedInProcess = false
        // 每步用时进共享日志：用户报「键盘加载半分钟」时一眼看出慢在拷资源、起引擎还是重新编译。
        val t0 = android.os.SystemClock.elapsedRealtime()
        val (user, shared) = initializeRimeDataAsync(context)
        val t1 = android.os.SystemClock.elapsedRealtime()
        RimeEngine.getInstance().initialize(user, shared)
        val t2 = android.os.SystemClock.elapsedRealtime()
        val usable = ensureDeployment(context) || (retryWithoutCellEnglish(context) && ensureDeployment(context))
        FileLogger.w(TAG, "prepareEngine: data=${t1 - t0}ms init=${t2 - t1}ms deploy=${android.os.SystemClock.elapsedRealtime() - t2}ms usable=$usable")
        preparedInProcess = usable
        usable
    }

    /**
     * 启停、导入方案后自动应用（#340，由 [SchemaChangeApplier] 调）：和 [prepareEngine] 同一步，但不走「已准备好」的捷径，
     * 新方案文件、新的启用列表要按哈希增量编译进去。当前方案已经停用就换到同语言还开着的方案。
     * 部署能用时通知输入法重新选方案（[DEPLOYMENT_REVISION]），键盘、布局栏跟着新的方案列表走。
     */
    suspend fun applySchemaChanges(context: Context): Boolean {
        val before = SettingsPreferences.getDeploymentHash(context)
        val wasDone = SettingsPreferences.isDeploymentDone(context)
        val usable = preparationMutex.withLock {
            preparedInProcess = false
            val (user, shared) = initializeRimeDataAsync(context)
            RimeEngine.getInstance().initialize(user, shared)
            val usable = ensureDeployment(context) || (retryWithoutCellEnglish(context) && ensureDeployment(context))
            preparedInProcess = usable
            usable
        }
        val moved = moveOffDisabledSchema(context) != null
        // 什么都没变（导入的只是图片、重复点同一项）就不叫输入法重来一遍，免得键盘闪一下「正在加载」。
        val changed = moved || !wasDone || SettingsPreferences.getDeploymentHash(context) != before
        if (usable && changed) {
            val prefs = SettingsPreferences.getPrefsPublic(context)
            prefs.edit().putLong(DEPLOYMENT_REVISION, prefs.getLong(DEPLOYMENT_REVISION, 0L) + 1L).apply()
        }
        return usable
    }

    /** 当前方案不在启用列表里（刚被停用、卸载）：换到同语言还开着的方案，并记成该语言选中的条目。手写、英文不在列表里，不动。 */
    fun moveOffDisabledSchema(context: Context): String? {
        val current = SettingsPreferences.getCurrentSchema(context)
        val enabled = SchemaManager.getEnabledSchemas(context)
        // 键盘配置还没读进来时 isHandwritingSchema 认不出手写，再按方案本身的描述认一遍，不能把用户从手写换走。
        val handwriting = com.kingzcheung.xime.ui.keyboard.isHandwritingSchema(current) ||
            com.kingzcheung.xime.settings.InputProfiles.describe(current).layout.kind == com.kingzcheung.xime.settings.LayoutKind.HANDWRITING
        val fallback = replacementForDisabled(current, enabled, handwriting) ?: return null
        SettingsPreferences.setCurrentSchema(context, fallback)
        com.kingzcheung.xime.settings.InputModes.selectProfile(context, fallback)
        FileLogger.w(TAG, "Current schema $current is disabled, moved to $fallback")
        return fallback
    }

    /** 纯判断，可单测：[current] 已停用时换成哪个方案；不用换返回 null。 */
    internal fun replacementForDisabled(current: String, enabled: List<String>, handwriting: Boolean): String? {
        if (current.isBlank() || handwriting || current == com.kingzcheung.xime.settings.InputModes.ENGLISH) return null
        if (enabled.isEmpty() || current in enabled) return null
        return fallbackSchema(current, enabled, emptySet())?.takeIf { it != current }
    }

    /** Restore Chinese packs without the optional English translator. Keep the user's requested
     * setting pending so the dictionary screen can explicitly retry it after a successful startup. */
    private fun retryWithoutCellEnglish(context: Context): Boolean {
        val manager = com.kingzcheung.xime.settings.CellDictionaryManager
        return try {
            val store = manager.store(context)
            val state = store.read()
            if (!state.appliedEnglish) return false
            manager.prepare(context, state.applied, english = false)
            store.markApplied(state.applied, english = false)
            FileLogger.w(TAG, "Optional English cell candidates disabled for Chinese startup")
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not restore Chinese cell packs: ${e.message}")
            false
        }
    }

    /** Explicit repair from settings: initialize first, then validate both files and the live session. */
    suspend fun redeploy(context: Context): Boolean = preparationMutex.withLock {
        preparedInProcess = false
        SettingsPreferences.setDeploymentDone(context, false)
        recordProblem(context, "", usable = false)
        val (user, shared) = initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(user, shared)
        val success = synchronized(deploymentLock) {
            val usable = engine.deploy() && deploymentUsable(context)
            if (usable) storeDeploymentHash(context)
            SettingsPreferences.setDeploymentDone(context, usable)
            usable
        }
        preparedInProcess = success
        if (success) {
            // An already-created IME may still be showing its failed-startup screen.
            val prefs = SettingsPreferences.getPrefsPublic(context)
            prefs.edit().putLong(DEPLOYMENT_REVISION, prefs.getLong(DEPLOYMENT_REVISION, 0L) + 1L).apply()
        }
        success
    }

    /**
     * 部署结果能不能用：会话建好，内置方案和当前方案都编译出来了。
     * 导入、下载的方案（如雾凇原包里的搜狗双拼）编译不出来时只记下来提示用户，不再连累整个输入法
     * 「部署失败」：以前任何一个启用方案缺产物，切回内置方案也一直失败。
     */
    private fun deploymentUsable(context: Context): Boolean {
        val engine = RimeEngine.getInstance()
        val enabled = SchemaManager.getEnabledSchemas(context)
        if (enabled.isEmpty()) return recordProblem(context, "部署失败：没有启用的输入方案", usable = false)
        if (!RimeEngine.isInitialized() || !engine.ensureSession(10_000L) || engine.getCurrentSchema().isBlank())
            return recordProblem(context, "部署失败：输入引擎没有建好会话" + storageHint(context), usable = false)
        val available = engine.getAvailableSchemas().toSet()
        val build = File(context.filesDir, "rime/build")
        val broken = enabled.mapNotNull { id ->
            (missingBuildArtifact(build, id) ?: if (id !in available) "不在部署结果的方案列表里" else null)?.let { id to it }
        }.toMap()
        if (broken.isEmpty()) return recordProblem(context, "", usable = true)
        val current = SettingsPreferences.getCurrentSchema(context)
        // 一个方案编译不出来（内置的也一样）只提示它，不再挡住整个键盘：2026-11-02 用户手机上 14 键两个方案没编译出来，
        // 26 键明明能用，键盘却一直卡在「正在加载词库」。当前方案坏了就先换到同语言能用的方案。
        val fallback = if (current in broken) fallbackSchema(current, enabled, broken.keys) else current
        val usable = fallback != null
        if (fallback != null && fallback != current) SettingsPreferences.setCurrentSchema(context, fallback)
        val names = broken.entries.joinToString("；") { (id, reason) ->
            "${SchemaManager.getSchemaDisplayName(context, id) ?: id}（$id，$reason）"
        }
        FileLogger.w(TAG, "Deployment problems: $broken, current=$current, fallback=$fallback, " +
            "free=${context.filesDir.usableSpace / (1L shl 20)}MB")
        val message = if (usable) "这些方案没有编译成功，暂时不能用：$names。其他方案正常；" +
            (if (broken.keys.any { isAppSchema(context, it) }) "可在「已安装的方案」点「重新部署(修复)」重试。" else "可在「已安装的方案」停用或卸载它们。") +
            storageHint(context)
            else "部署失败：$names。" + storageHint(context)
        return recordProblem(context, message, usable)
    }

    /** 当前方案没编译出来时换用的方案：同语言优先，其次任一能用的输入方案；都不能用返回 null。 */
    internal fun fallbackSchema(current: String, enabled: List<String>, broken: Set<String>): String? {
        val working = enabled.filter {
            it !in broken && it !in com.kingzcheung.xime.settings.CyimeInputDefaults.dependencies &&
                it != com.kingzcheung.xime.settings.InputModes.ENGLISH
        }
        val language = com.kingzcheung.xime.settings.InputLanguage.forSchema(current)
        return working.firstOrNull { com.kingzcheung.xime.settings.InputLanguage.forSchema(it) == language } ?: working.firstOrNull()
    }

    /** CyIME 自带或自己生成的方案：编译失败多半是它引用的文件被改坏，提示里让用户重试而不是卸载。 */
    internal fun isAppSchema(context: Context, id: String): Boolean =
        id in com.kingzcheung.xime.settings.ChineseSchemas.ids || id in com.kingzcheung.xime.settings.CyimeInputDefaults.recommended ||
            com.kingzcheung.xime.settings.DoublePinyinSchemes.isGenerated(id) ||
            com.kingzcheung.xime.settings.CustomKeyboardLayouts.isCustom(id) || bundledSchemaIds(context).contains(id)

    @Volatile private var bundledSchemaIdsCache: Set<String>? = null
    private fun bundledSchemaIds(context: Context): Set<String> = bundledSchemaIdsCache ?: runCatching {
        context.assets.open("rime-bundled-manifest.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { it.substringAfterLast('\t').takeIf { name -> name.endsWith(".schema.yaml") && '/' !in name } }
                .map { it.removeSuffix(".schema.yaml") }.toSet()
        }
    }.getOrDefault(emptySet()).also { bundledSchemaIdsCache = it }

    /** 剩余空间太少时词库编译会写不完（部署每次都失败、有些字打不出来），提示里带上。 */
    private fun storageHint(context: Context): String {
        val freeMb = context.filesDir.usableSpace / (1L shl 20)
        return if (freeMb < LOW_STORAGE_MB) "手机剩余空间只有 $freeMb MB，词库编译可能写不下，请先清理空间再到「已安装的方案」点「重新部署(修复)」。" else ""
    }
    private const val LOW_STORAGE_MB = 300L

    private fun recordProblem(context: Context, message: String, usable: Boolean): Boolean {
        if (message.isNotEmpty()) FileLogger.w(TAG, message)
        SettingsPreferences.getPrefsPublic(context).edit().putString(DEPLOYMENT_PROBLEM, message).apply()
        return usable
    }

    /** 上次部署留下的说明；空表示没有问题。 */
    fun deploymentProblem(context: Context): String =
        SettingsPreferences.getPrefsPublic(context).getString(DEPLOYMENT_PROBLEM, "").orEmpty()

    /** 部署过程抛出异常（文件读写、内置资源校验等）时也留下原因，不再只显示「部署失败」。 */
    fun recordFailure(context: Context, error: Throwable) {
        recordProblem(context, "部署失败：${error.message ?: error.javaClass.simpleName}" + storageHint(context), usable = false)
    }

    /** Apply the selected supplemental dictionaries through the same serialized deployment path. */
    suspend fun applyCellDictionaries(context: Context): Boolean = preparationMutex.withLock {
        val manager = com.kingzcheung.xime.settings.CellDictionaryManager
        val store = manager.store(context)
        val selected = store.read()
        preparedInProcess = false
        val (user, shared) = initializeRimeDataAsync(context, restoreCellDictionaries = false)
        val engine = RimeEngine.getInstance()
        engine.initialize(user, shared)
        synchronized(deploymentLock) {
            store.activate(selected.selected, selected.selectedEnglish) { ids, english, revision ->
                val bindings = manager.prepare(context, ids, english, revision)
                // Recreate the live session, as ordinary incremental maintenance retains loaded tables.
                // Native compilation still reuses unchanged dictionaries by checksum.
                val usable = engine.deploy() && deploymentUsable(context) &&
                    manager.verify(context, bindings)
                SettingsPreferences.setDeploymentDone(context, usable)
                check(usable) { "词库编译失败，已保留待应用选择" }
                storeDeploymentHash(context)
                preparedInProcess = true
            }
        }
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().putLong(DEPLOYMENT_REVISION, prefs.getLong(DEPLOYMENT_REVISION, 0L) + 1L).apply()
        true
    }

    suspend fun initializeRimeDataAsync(context: Context, restoreCellDictionaries: Boolean = true): Pair<String, String> = assetInitializationMutex.withLock {
        val rimeDir = File(context.filesDir, "rime")
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        // 迁移旧版 market 目录（rime/market/ → market/）
        migrateOldMarketDir(context)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        // Settings/IME may query schemas concurrently while the directory is only half copied.
        // Readers must not persist a legacy/empty schema list or consume migration flags then.
        val installing = File(rimeDir, SchemaManager.ASSET_INSTALL_MARKER)
        installing.writeText("installing")
        copyAssetsToRimeDir(context, rimeDir)
        val bundledStartedAt = android.os.SystemClock.elapsedRealtime()
        val bundledChanged = com.kingzcheung.xime.settings.BundledRimeSync.install(
            rimeDir, File(context.filesDir, "rime-upgrade-backups"),
            com.kingzcheung.xime.model.CandidateModelAssets.filterBundledManifest(context,
                context.assets.open("rime-bundled-manifest.tsv").bufferedReader().use { it.readText() }),
        ) { context.assets.open(it) }
        // 进共享日志：升级后第一次弹键盘慢时，看内置资源核对、替换用了多久。
        if (bundledChanged) FileLogger.w(TAG, "Bundled assets updated in ${android.os.SystemClock.elapsedRealtime() - bundledStartedAt}ms")
        // 日语词典装不上只影响日语转换，不能连累其它输入方案启动。
        // 词典和引擎在安装包里或是「日语」插件下载的（JapanesePack）；告诉日语翻译器下载的引擎在哪。
        try {
            com.kingzcheung.xime.settings.JapanesePack.exportLibraryPath(context)
            com.kingzcheung.xime.settings.JapanesePack.installDictionary(context)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to install the Japanese dictionary", e)
        }
        // Optional downloads become available at the normal engine initialization boundary.
        // Never restart or replay an active editor merely because a model download finished.
        com.kingzcheung.xime.model.CandidateModelAssets.installAvailable(context)
        com.kingzcheung.xime.model.CandidateModelAssets.dropSupersededDownloads(context)
        com.kingzcheung.xime.settings.ChineseSchemas.installOptionalLayoutSchemas(context, rimeDir)
        com.kingzcheung.xime.settings.DoublePinyinSchemes.install(context, rimeDir)
        check(installing.delete()) { "Cannot finish bundled asset installation" }
        // 内置排列和自建布局是中文26键方案的副本：26 键方案随版本更新后，按当前方案重新生成，不停在旧快照。
        com.kingzcheung.xime.settings.CustomKeyboardLayouts.followBaseSchema(context)
        com.kingzcheung.xime.settings.CustomKeyboardLayouts.enableMeasuredCorrection(context)
        // F1: assets 会用内置 default.yaml 覆盖，这里把启用方案重新写回 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 为所有启用方案打个人词库补丁
        PersonalDictManager.ensureSchemaPacks(context)
        // 模糊音等拼写补丁：新建的布局、随版本更新的方案规则都按当前开关重写一遍，没变不写。
        try {
            com.kingzcheung.xime.settings.SpellingPatches.sync(context, rimeDir)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to sync spelling patches", e)
        }
        // 内置分类词库（如明日方舟）随 APK 更新，新装默认都关着；失败不影响输入法启动。
        try {
            com.kingzcheung.xime.settings.CellDictionaryManager.syncBuiltins(context)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to sync built-in cell dictionaries", e)
        }
        // 分类词库合不进某个方案（比如方案自定义文件写法不支持）时，prepare 一个文件都不写、原样保留上次的配置；
        // 这是配置问题，重试也不会好，不能因此卡住整个键盘。「导入的词库」页点「应用更改」时仍会显示原因。
        if (restoreCellDictionaries) try {
            com.kingzcheung.xime.settings.CellDictionaryManager.prepare(context)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Cell dictionaries not merged, previous config kept: ${e.message}")
            retryWithoutCellEnglish(context)
        }
        // 内置资源替换后部署哈希变了，由 ensureDeployment 增量维护：librime 按源文件校验和只重编变了的词库。

        Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 统一部署入口（进程内互斥）：
     * - 部署 hash 一致 → build 已是最新，对齐 deploymentDone 标记，跳过编译；
     * - hash 缺失/不一致 → 优先增量维护（librime 按文件时间戳只编译变更），
     *   仅当 build 缺失/为空或增量失败时才清空全量编译。
     *
     * 必须由调用方保证 engine 已 initialize（deploy() 未初始化时返回 false）。
     * 该入口被 Application 预初始化与输入法服务共享，配合 deploymentLock
     * 避免两者并发触发两次全量编译。
     */
    fun ensureDeployment(context: Context): Boolean {
        synchronized(deploymentLock) {
            if (!RimeEngine.isInitialized()) {
                SettingsPreferences.setDeploymentDone(context, false)
                return recordProblem(context, "部署失败：输入引擎未初始化" + storageHint(context), usable = false)
            }
            val buildDir = File(context.filesDir, "rime/build")
            val damaged = removeBrokenBuildArtifacts(buildDir)
            if (damaged.isNotEmpty()) {
                SettingsPreferences.setDeploymentHash(context, "")
                FileLogger.w(TAG, "Rebuilding damaged Rime cache: ${damaged.joinToString()}")
            }
            val hashStartedAt = android.os.SystemClock.elapsedRealtime()
            val currentHash = computeDeploymentHash(context)
            val storedHash = SettingsPreferences.getDeploymentHash(context)
            val hashMs = android.os.SystemClock.elapsedRealtime() - hashStartedAt
            if (damaged.isEmpty() && currentHash.isNotEmpty() && currentHash == storedHash && deploymentUsable(context)) {
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            // 进共享日志：每次冷启动都走到这里（哈希每次都变）就是「每次开键盘都要等半分钟」。
            FileLogger.w(TAG, "Deployment hash ${if (storedHash.isEmpty()) "missing" else if (currentHash == storedHash) "same, build unusable" else "changed"}" +
                " damaged=${damaged.size} hashMs=$hashMs changedSince=${changedSinceHash(context)}")
            val buildExists = buildDir.exists() && buildDir.listFiles()?.isNotEmpty() == true
            val engine = RimeEngine.getInstance()
            val deployed: Boolean
            if (buildExists && damaged.isEmpty()) {
                // build 已就位但配置有变化：增量维护，只编译变更的 schema/dict，
                // 避免 custom.yaml 补丁等小幅改动触发 60MB 词库全量重编译（持锁 30s+）。
                FileLogger.w(TAG, "Build exists, running incremental maintenance")
                if (engine.deployIncremental()) {
                    deployed = true
                } else {
                    FileLogger.w(TAG, "Incremental maintenance failed, falling back to full deploy")
                    buildDir.deleteRecursively()
                    buildDir.mkdirs()
                    deployed = engine.deploy()
                }
            } else {
                FileLogger.w(TAG, "Build directory missing, empty or damaged, running full deploy")
                buildDir.mkdirs()
                deployed = engine.deploy()
            }
            if (!deployed) {
                SettingsPreferences.setDeploymentDone(context, false)
                return recordProblem(context, "部署失败：词库编译未完成" + storageHint(context), usable = false)
            }
            if (deploymentUsable(context)) {
                storeDeploymentHash(context)
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            SettingsPreferences.setDeploymentDone(context, false)
            return false
        }
    }
    
    fun initializeRimeData(context: Context): Pair<String, String> = runBlocking {
        initializeRimeDataAsync(context)
    }

    fun storeDeploymentHash(context: Context) {
        val hash = computeDeploymentHash(context)
        if (hash.isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, hash)
            SettingsPreferences.getPrefsPublic(context).edit().putLong(DEPLOYMENT_HASH_AT, System.currentTimeMillis()).apply()
        }
    }

    private const val DEPLOYMENT_HASH_AT = "rime_deployment_hash_at"

    /** 上次存哈希之后改过的部署输入文件（只记文件名，最多 12 个）：查「为什么又重新部署」。 */
    private fun changedSinceHash(context: Context): String {
        val since = SettingsPreferences.getPrefsPublic(context).getLong(DEPLOYMENT_HASH_AT, 0L)
        if (since <= 0L) return "unknown"
        val rimeDir = File(context.filesDir, "rime")
        val changed = rimeDir.walkTopDown().onEnter { it == rimeDir || it.name !in setOf("build", "market") }
            .filter { it.isFile && it.lastModified() > since && it.name.endsWith(".yaml") }
            .map { it.relativeTo(rimeDir).invariantSeparatorsPath }.sorted().toList()
        return if (changed.isEmpty()) "none" else changed.take(12).joinToString(",") + if (changed.size > 12) ",+${changed.size - 12}" else ""
    }

    /**
     * 部署产物完整性检查（半成品检测）：librime 的 table/prism 产物文件头是固定
     * magic（"Rime::Table/" / "Rime::Prism/"，Metadata.format 位于文件偏移 0）。
     * 部署进行中进程被杀会留下空文件或截断的半成品——其 mtime 比源文件新，
     * librime 增量维护会视为"已是最新"而跳过重编，导致该方案查询永远零命中
     * （症状：九键方案切换正常、按键进 buffer，但候选恒空、候选栏恒 IDLE）。
     */
    internal fun isBrokenBuildArtifact(file: File): Boolean {
        val expectedMagic = when {
            file.name.endsWith(".prism.bin") -> "Rime::Prism/"
            file.name.endsWith(".table.bin") -> "Rime::Table/"
            // 未知类型（如 reverse.bin、日志）不判损坏，避免误删触发部署循环
            else -> return false
        }
        if (!file.isFile || file.length() == 0L) return true
        if (file.length() < expectedMagic.length) return true
        return try {
            java.io.FileInputStream(file).use { input ->
                val head = ByteArray(expectedMagic.length)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) return true
                    read += n
                }
                !String(head, Charsets.US_ASCII).startsWith(expectedMagic)
            }
        } catch (_: IOException) {
            // 读不了不臆断损坏，交由 librime 运行时自检兜底
            false
        }
    }

    internal fun removeBrokenBuildArtifacts(buildDir: File): List<String> {
        val damaged = buildDir.listFiles().orEmpty().filter { it.isFile && isBrokenBuildArtifact(it) }
        for (file in damaged) check(file.delete()) { "无法清理损坏的编译文件 ${file.name}" }
        return damaged.map { it.name }
    }

    fun isDeploymentComplete(context: Context): Boolean = synchronized(deploymentLock) {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return false

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        if (enabledSchemas.isEmpty()) return false

        // 损坏产物统一扫描：*.prism.bin / *.table.bin 存在但 magic 不对（部署中断
        // 半成品）→ 删除并判定未完成。产物被删除后 librime 增量维护才会重建它；
        // 同时清 stored hash，避免 ensureDeployment 因 hash 一致而短路跳过重建。
        // 词典产物按 dictionary 名生成，可能与 schemaId 不同名（如 t9_pinyin 用
        // pinyin_simp 词典），故扫描整个 build 目录而非按方案枚举。
        if (removeBrokenBuildArtifacts(buildDir).isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, "")
            return false
        }

        for (schemaId in enabledSchemas) {
            if (!File(buildDir, "$schemaId.prism.bin").exists() &&
                !File(buildDir, "$schemaId.schema.yaml").exists()) {
                return false
            }
        }

        if (!hasRequiredBuildArtifacts(context)) return false
        val currentHash = computeDeploymentHash(context)
        if (currentHash.isEmpty()) return false

        val storedHash = SettingsPreferences.getDeploymentHash(context)
        if (storedHash.isEmpty()) {
            SettingsPreferences.setDeploymentHash(context, currentHash)
            return true
        }

        if (currentHash != storedHash) {
            return false
        }

        return true
    }

    /** A compiled YAML alone is not a usable dictionary. Never cache that as ready. */
    internal fun hasRequiredBuildArtifacts(context: Context): Boolean {
        val build = File(context.filesDir, "rime/build")
        val enabled = SchemaManager.getEnabledSchemas(context)
        return enabled.isNotEmpty() && enabled.all { missingBuildArtifact(build, it) == null }
    }

    /** 方案没编译出来时返回缺的是什么（给用户看的一句），齐全时返回 null。 */
    internal fun missingBuildArtifact(build: File, id: String): String? {
        val schema = File(build, "$id.schema.yaml")
        if (!schema.isFile) return "没有编译出 $id.schema.yaml"
        val chinese = id in com.kingzcheung.xime.settings.ChineseSchemas.ids
        val translator = Regex("""(?ms)^translator:\s*\n(.*?)(?=^\S|\z)""")
            .find(schema.readText())?.groupValues?.get(1) ?: return if (chinese) "方案里没有 translator" else null
        fun value(key: String) = Regex("(?m)^  $key: *([^#\r\n]+)")
            .find(translator)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
        val dictionary = value("dictionary")?.takeIf { it.isNotBlank() } ?: return if (chinese) "方案里没有词库" else null
        val prism = value("prism") ?: dictionary
        return listOf(File(build, "$dictionary.table.bin"), File(build, "$prism.prism.bin"))
            .firstOrNull { !it.isFile || isBrokenBuildArtifact(it) }?.let { "缺少 ${it.name}" }
    }

    private fun fileUpdateDigest(digest: java.security.MessageDigest, file: File) {
        if (!file.exists()) return
        java.io.FileInputStream(file).use { input ->
            java.security.DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) { }
            }
        }
    }

    private fun computeDeploymentHash(context: Context): String {
        val rimeDir = File(context.filesDir, "rime")
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        fileUpdateDigest(digest, File(rimeDir, ".cyime-bundled-revision"))
        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        for (schemaId in enabledSchemas.sorted()) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) {
                digest.update(schemaId.toByteArray())
                fileUpdateDigest(digest, schemaFile)
            }
            val customFile = File(rimeDir, "$schemaId.custom.yaml")
            if (customFile.exists()) {
                fileUpdateDigest(digest, customFile)
            }
            // merged dict 由 app 生成、librime 实际编译，计入 hash 以便其变更（如转发器展开修复）触发重编译
            val mergedDictFile = File(rimeDir, "${schemaId}_merged.dict.yaml")
            if (mergedDictFile.exists()) {
                digest.update("${schemaId}_merged".toByteArray())
                fileUpdateDigest(digest, mergedDictFile)
            }
        }

        // 所有词典文件（内置词典与个人词库）纳入 hash：
        // 否则词典新增/变更（如 pinyin_simp.dict.yaml）不会改变 hash，
        // build 目录不重建、不重新部署，导致 table.bin 缺失（运行时反复报错）。
        rimeDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dict.yaml") }
            ?.sortedBy { it.name }
            ?.forEach { dictFile ->
                digest.update(dictFile.name.toByteArray())
                fileUpdateDigest(digest, dictFile)
            }

        rimeDir.walkTopDown().onEnter { it == rimeDir || it.name !in setOf("build", "market") }
            .filter { it.isFile && it.parentFile != rimeDir && it.name.endsWith(".dict.yaml") }
            .sortedBy { it.relativeTo(rimeDir).path }.forEach {
                digest.update("${it.relativeTo(rimeDir).invariantSeparatorsPath}:${it.length()}:${it.lastModified()}".toByteArray())
            }

        val defaultYaml = File(rimeDir, "default.yaml")
        if (defaultYaml.exists()) {
            digest.update("default".toByteArray())
            fileUpdateDigest(digest, defaultYaml)
        }

        // default.custom.yaml 变化（patch 基线修补/方案列表启停）也要触发重部署：
        // librime 编译各方案时经 DefaultConfigPlugin include default 的 menu 等节，
        // 且其 __build_info/timestamps 记录了 default.custom.yaml 的 mtime，
        // hash 失配 → 增量维护 → 各方案配置重编 → 新 page_size 编入产物全局生效。
        val defaultCustomYaml = File(rimeDir, "default.custom.yaml")
        if (defaultCustomYaml.exists()) {
            digest.update("default.custom".toByteArray())
            fileUpdateDigest(digest, defaultCustomYaml)
        }

        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        // 判断 rime/ 是否已部署过任何方案（存在任意 <name>.schema.yaml）。
        // 全新安装时 rime/ 为空 → 全量复制内置默认方案与系统文件，保证开箱即用。
        // 一旦已部署过方案（内置旧版或从市场安装的第三方方案），app 更新时
        // 一律不再用内置 assets 覆盖，避免覆盖第三方同名文件造成污染；
        // 需要更新的方案统一走方案市场。此判定不依赖具体方案 id/文件名，天然鲁棒。
        val alreadyHasSchemas = targetDir.listFiles()
            ?.any { it.isFile && it.name.endsWith(".schema.yaml") } == true
        if (alreadyHasSchemas) {
            // 仅兜底确保 default.yaml 存在（librime 入口必需），缺失时补一份，不覆盖已有。
            ensureDefaultYaml(context, targetDir)
            // 只更新确实由内置包管理且未经修改的文件，市场同名版本及个人修改保留。
            val updated = updateBuiltinAssets(context, targetDir)
            if (updated > 0) {
                Log.i(TAG, "Updated $updated builtin asset file(s)")
            }
            syncBuiltinDefaultCustom(context, targetDir)
            return false
        }
        val previousCustom = File(targetDir, ASSETS_DEFAULT_CUSTOM).takeIf { it.isFile }?.readBytes()
        val copied = try {
            copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy assets", e)
            false
        }
        if (copied) {
            seedBuiltinPackageVersion(context)
            val custom = File(targetDir, ASSETS_DEFAULT_CUSTOM)
            if (previousCustom != null) custom.writeBytes(previousCustom)
            else context.assets.open(ASSETS_DEFAULT_CUSTOM).use { input ->
                custom.outputStream().use { input.copyTo(it) }
            }
        }
        syncBuiltinDefaultCustom(context, targetDir)
        return copied
    }

    /**
     * 把 app 固定的 assets/default.custom.yaml 同步/修补到用户 rime 目录。
     *
     * 背景：menu/page_size 等全局默认经 default.custom.yaml patch 进 default.yaml，
     * 再由 librime DefaultConfigPlugin 编入每个方案的编译产物。submodule 里的同名
     * 文件只在全新安装复制一次（*.custom.yaml 被排除出强制更新），且旧版
     * setEnabledSchemas 启停方案时会把整文件重写成只剩 schema_list 的空壳——
     * page_size patch 就此丢失，候选数在 5（引擎兜底）与 20（运行时内存覆盖，
     * 重启/部署后失效）之间漂移。
     *
     * 规则：文件缺失 → 复制模板；已存在 → 内容对齐 app 设置值
     * （[patchDefaultCustomContent]，.custom.yaml 可以被覆盖，不保留 PC 遗留值）。
     * 文件变化使部署 hash 失配，由 ensureDeployment 增量重编后全局生效。
     */
    private fun syncBuiltinDefaultCustom(context: Context, targetDir: File) {
        val target = File(targetDir, ASSETS_DEFAULT_CUSTOM)
        val pageSize = SettingsPreferences.getPageSize(context).coerceAtLeast(1)
        try {
            if (!target.exists()) {
                copyAssetFile(context, ASSETS_DEFAULT_CUSTOM, target)
                // 模板基线（20）与用户设置不一致时（如 slider 调过）以设置为准
                val aligned = patchDefaultCustomContent(target.readText(), pageSize)
                if (aligned != null) {
                    target.writeText(aligned)
                }
                return
            }
            val patched = patchDefaultCustomContent(target.readText(), pageSize) ?: return
            target.writeText(patched)
            Log.i(TAG, "Aligned ${target.name} menu/page_size=$pageSize")
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to sync $ASSETS_DEFAULT_CUSTOM", e)
        }
    }

    /**
     * default.custom.yaml 的基线对齐（纯函数）：
     * 1. 移除抢占双拼编码键的 key_binder 绑定——`semicolon`/`apostrophe` 在微软双拼
     *    等方案中是合法编码键（`;` = ing，`'` 为音节分隔符），而 key_binder 优先级高于
     *    speller，has_menu 时绑定会拦截按键，按 `;` 直接选词上屏、无法组码（issue #884）；
     * 2. 把 page_size 强制对齐为 app 当前设置值。
     * 注意 page_size 只是磁盘配置基线——方案自带的 menu/page_size（内置与第三方
     * 方案多为 PC 遗留默认 5，不适配手机）经 librime MergeTree 语义压过 default
     * 层，运行时的实际生效靠 JNI 层 setPageSize 直接注入（rime_jni.cc）。
     * 无需变化时返回 null。
     */
    internal fun patchDefaultCustomContent(text: String, pageSize: Int): String? {
        val sep = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.lines()
        var changed = false
        val updated = lines.toMutableList()

        // 1. 清理与双拼编码键冲突的 key_binder 绑定（旧版模板/用户目录残留）
        val kept = updated.filterNot { isConflictingKeyBindingLine(it) }
        if (kept.size != updated.size) {
            updated.clear()
            updated.addAll(kept)
            changed = true
        }

        // 2. 对齐 menu/page_size
        val pageSizeIdx = updated.indexOfFirst { it.trimStart().startsWith("page_size:") }
        if (pageSizeIdx >= 0) {
            val raw = updated[pageSizeIdx].trimStart().removePrefix("page_size:")
                .substringBefore('#').trim()
            if (raw.toIntOrNull() != pageSize) {
                val indent = updated[pageSizeIdx].takeWhile { it == ' ' || it == '\t' }
                updated[pageSizeIdx] = "${indent}page_size: $pageSize"
                changed = true
            }
        } else {
            val patchIdx = updated.indexOfFirst { it.trim() == "patch:" }
            if (patchIdx >= 0) {
                updated.add(patchIdx + 1, "  menu:")
                updated.add(patchIdx + 2, "    page_size: $pageSize")
                changed = true
            }
        }

        return if (changed) updated.joinToString(sep) else null
    }

    /**
     * 判断一行是否为抢占分号/单引号的 key_binder 绑定（如
     * `- { when: has_menu, accept: semicolon, send: 2 }`）。这类绑定在双拼方案下
     * 会拦截 `;`（ing）/`'`（音节分隔符），必须从配置中移除。
     */
    private fun isConflictingKeyBindingLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("-") || trimmed.startsWith("#")) return false
        if (!trimmed.contains("send:")) return false
        return trimmed.contains("accept: semicolon") || trimmed.contains("accept: apostrophe")
    }

    /**
     * 全新安装复制内置默认方案集后，为市场条目 `builtin`（type=built-in 的默认方案包，
     * 一个压缩包里含多个 schema）写入一个初始占位版本号。
     *
     * 市场方案的版本号与具体 .schema.yaml 无关，是方案集所属 git 仓库的 tag（见
     * rimes/index.yaml 中 builtin 条目）。随 app 发布的内置方案集在运行时无法自报其
     * 对应的 git tag，因此写入一个不等于任何真实 tag 的占位值，使市场 `hasUpdate`
     * （installedVersion != currentVersion）成立，从而向用户提示「更新」，把默认方案
     * 从市场更新到带真实版本号的版本。仅当该条目尚无版本记录时写入。
     */
    private fun seedBuiltinPackageVersion(context: Context) {
        if (MarketVersionStore.getSchemeVersion(context, BUILTIN_MARKET_ID) == null) {
            MarketVersionStore.setSchemeVersion(context, BUILTIN_MARKET_ID, BUILTIN_PLACEHOLDER_VERSION)
        }
    }

    /** 兜底确保 default.yaml 存在（首次复制失败/旧结构迁移时可能缺失），缺失才补且不覆盖已有内容。 */
    private fun ensureDefaultYaml(context: Context, targetDir: File) {
        val defaultYaml = File(targetDir, "default.yaml")
        if (defaultYaml.exists()) return
        copyAssetFile(context, "$ASSETS_RIME_DIR/default.yaml", defaultYaml)
    }
    
    /**
     * 内置方案更新：只替换仍由内置包独占且未修改的旧版本。
     * 市场同名文件、个人修改和新版中文资源由各自安装流程管理。
     *
     * 覆盖 default.yaml 会重置 schema_list，由调用方
     * [SchemaManager.applyEnabledSchemasToDefaultYaml] 紧随其后恢复用户启用列表。
     *
     * @return 覆盖（含新增）的文件数。
     */
    private fun updateBuiltinAssets(context: Context, targetDir: File): Int {
        var updated = 0
        val registryFile = SchemaManifestManager.getRegistryFile(context)
        val manifestFile = SchemaManifestManager.getManifestFile(context, "builtin")
        val registry = runCatching { org.json.JSONObject(registryFile.readText()) }.getOrNull()
        val entries = registry?.optJSONObject("files")
        val manifest = runCatching { org.json.JSONObject(manifestFile.readText()) }.getOrNull()
        val manifestEntries = manifest?.optJSONObject("files")
        fun suppliedByNewBundle(path: String): Boolean = listOf("rime_ice", "rime_chinese").any { root ->
            runCatching { context.assets.open("$root/$path").use { } }.isSuccess
        }
        fun sync(assetSub: String, targetSub: File) {
            val assetPath = if (assetSub.isEmpty()) ASSETS_RIME_DIR else "$ASSETS_RIME_DIR/$assetSub"
            for (fileName in context.assets.list(assetPath) ?: return) {
                val childAsset = if (assetSub.isEmpty()) fileName else "$assetSub/$fileName"
                val childTarget = File(targetSub, fileName)
                val subFiles = context.assets.list("$ASSETS_RIME_DIR/$childAsset")
                if (!subFiles.isNullOrEmpty()) {
                    childTarget.mkdirs()
                    sync(childAsset, childTarget)
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    if (fileName.endsWith(".custom.yaml") || suppliedByNewBundle(childAsset)) continue
                    if (childTarget.exists()) {
                        if (assetContentEquals(context, "$ASSETS_RIME_DIR/$childAsset", childTarget)) continue
                        if (!SchemaManifestManager.canUpdateBuiltinFile(entries?.optJSONObject(childAsset),
                                SchemaManifestManager.fileSha256(childTarget))) continue
                    }
                    copyAssetFile(context, "$ASSETS_RIME_DIR/$childAsset", childTarget)
                    val hash = SchemaManifestManager.fileSha256(childTarget) ?: continue
                    entries?.optJSONObject(childAsset)?.apply { put("sha256", hash); put("size", childTarget.length()) }
                    manifestEntries?.optJSONObject(childAsset)?.apply { put("sha256", hash); put("size", childTarget.length()) }
                    SchemaManifestManager.copyToBuiltinBackup(childTarget,
                        SchemaManager.getMarketDir(context, "builtin"), childAsset)
                    updated++
                }
            }
        }
        sync("", targetDir)
        if (updated > 0) {
            if (registry != null) registryFile.writeText(registry.toString(2))
            if (manifest != null) manifestFile.writeText(manifest.toString(2))
        }
        return updated
    }

    /** assets 文件与本地文件内容逐块比对（流式，避免大词典整读进内存）。 */
    private fun assetContentEquals(context: Context, assetPath: String, target: File): Boolean {
        return try {
            context.assets.open(assetPath).use { assetStream ->
                java.io.FileInputStream(target).use { fileStream ->
                    val assetBuf = ByteArray(BUFFER_SIZE)
                    val fileBuf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val nAsset = assetStream.read(assetBuf)
                        val nFile = fileStream.read(fileBuf)
                        if (nAsset != nFile) return false
                        if (nAsset < 0) return true
                        for (i in 0 until nAsset) {
                            if (assetBuf[i] != fileBuf[i]) return false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            return false
        }
        
        var copiedAny = false
        
        for (fileName in files) {
            val fullAssetPath = "$assetPath/$fileName"
            val targetFile = File(targetDir, fileName)
            
            try {
                val subFiles = context.assets.list(fullAssetPath)
                if (!subFiles.isNullOrEmpty()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    val needsCopy = try {
                        if (targetFile.exists()) {
                            val fd = context.assets.openFd(fullAssetPath)
                            val sameSize = targetFile.length() == fd.length
                            fd.close()
                            !sameSize
                        } else true
                    } catch (_: Exception) {
                        true
                    }
                    if (needsCopy) {
                        copyAssetFile(context, fullAssetPath, targetFile)
                        copiedAny = true
                    }
                }
            } catch (e: IOException) {
                FileLogger.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists() && targetFile.name.contains("custom")) {
                return
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(context.filesDir, "rime/shared")
        val oldUserDir = File(context.filesDir, "rime/user")
        
        if (!oldSharedDir.exists() && !oldUserDir.exists()) return
        
        Log.i(TAG, "Migrating old rime directory structure to single rime/ dir...")
        
        if (!rimeDir.exists()) rimeDir.mkdirs()
        
        // 迁移 user 数据（用户配置、build 产物、userdb）
        if (oldUserDir.exists()) {
            oldUserDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 迁移 shared 数据（方案文件）
        if (oldSharedDir.exists()) {
            oldSharedDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 删除旧目录
        oldSharedDir.deleteRecursively()
        oldUserDir.deleteRecursively()
        
        Log.i(TAG, "Migration complete")
    }

    /** 迁移旧版 market 目录（rime/market/ → market/）。 */
    private fun migrateOldMarketDir(context: Context) {
        val oldMarket = File(context.filesDir, "rime/market")
        if (!oldMarket.exists()) return

        val newMarket = SchemaManager.getMarketDir(context)
        if (!newMarket.exists()) {
            // 新位置不存在，直接重命名
            if (oldMarket.renameTo(newMarket)) {
                Log.i(TAG, "Migrated rime/market/ -> market/")
            } else {
                FileLogger.w(TAG, "Failed to rename rime/market/ to market/")
            }
        } else {
            // 新位置已存在，逐项合并
            oldMarket.listFiles()?.forEach { sub ->
                val target = File(newMarket, sub.name)
                if (!target.exists()) {
                    sub.renameTo(target)
                }
            }
            oldMarket.deleteRecursively()
            Log.i(TAG, "Merged rime/market/ into market/")
        }
    }

}
