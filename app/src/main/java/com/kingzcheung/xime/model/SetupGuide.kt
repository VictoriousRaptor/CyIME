package com.kingzcheung.xime.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe
import com.kingzcheung.xime.ui.keyboard.InputGestureLock
import com.kingzcheung.xime.ui.keyboard.InputGestureLocks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.DoublePinyinSchemes
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.InputProfiles
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.LayoutKind
import com.kingzcheung.xime.settings.PerformanceCosts
import com.kingzcheung.xime.settings.PerformanceProfile
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelSelection
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.kingzcheung.xime.platform.AndroidPlatform

/** 「按设备推荐配置」：读设备情况、照方案改设置、删掉用不到的模型。只在用户点了“按推荐设置”后改东西。 */
object SetupGuide {
    private fun canEnableKeyHints(keys: KeyboardInputPreferences): Boolean =
        InputGestureLocks.lockFor(keys, InputGestureLock.Option.DOUBLE_PINYIN_KEY_HINTS, true) == null
    private const val KEY_SHOWN = "setup_guide_shown"
    /** 引导里「有内存焦虑吗？」上次的回答，下次打开照它预选。 */
    private const val KEY_MEMORY_SAVER = "setup_guide_memory_saver"

    /** 首次设置向导之后是否已经出现过（看过、设置过或点了“以后再说”）。 */
    fun isShown(context: Context): Boolean = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_SHOWN, false)
    fun markShown(context: Context) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(KEY_SHOWN, true).apply()
        // 新装的用户看过这一页才开始下首次默认模型（按推荐设置时没选的已在 apply 里标成不下）。
        DefaultModelInstaller.start(context.applicationContext)
    }

    /** 在 IO 线程调用：会查模型文件。 */
    fun readFacts(context: Context): SetupFacts {
        // 没测过 CPU 就先测一次（约 0.3～2 秒，键盘开着、省电、发热时跳过），档位按实测算；还在「自动」时跟着改套新档。
        if (com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.ensure(context) is
                com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.Outcome.Measured) {
            com.kingzcheung.xime.settings.PerformanceProfile.onCpuMeasured(context)
        }
        val hardware = DeviceCapabilityProbe.read(context)
        ModelManager.initialize()
        val models = SetupGuidePlanner.managedIds.mapNotNull { ModelManager.getModel(it) }
        val installed = models.mapNotNull { model ->
            if (!ModelManager.isModelDownloaded(context, model)) null
            else model.id to ModelManager.getModelSizeOnDisk(context, model.id).takeIf { it > 0 }.let { it ?: catalogBytes(model) }
        }.toMap()
        val inPackage = models.filter { model ->
            model.resolvedVersion()?.let { BundledModelInstaller.packagedSource(context, model, it) } != null
        }.map { it.id }.toSet()
        val connectivity = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        val network = runCatching { connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } }.getOrNull()
        val manager = AsrModelManager(context)
        val chinese = runCatching { manager.getSelectedModelId(InputLanguage.CHINESE) }.getOrNull()
        val currentSchema = SettingsPreferences.getCurrentSchema(context)
        val profile = runCatching { InputProfiles.current(currentSchema) }.getOrNull()
        val t9Layout = profile?.layout?.kind == LayoutKind.T9
        val enabledSchemas = runCatching { SchemaManager.getEnabledSchemas(context) }.getOrDefault(emptyList())
        val typing = when (profile?.scheme) {
            InputScheme.PINYIN -> TypingWay.FULL_PINYIN
            InputScheme.DOUBLE_PINYIN -> TypingWay.DOUBLE_PINYIN
            InputScheme.WUBI, InputScheme.WUBI_PINYIN -> TypingWay.WUBI
            // 仓颉、注音、笔画这些另有打法；手写、英文等不是打中文的键盘，按全拼问。
            InputScheme.CANGJIE, InputScheme.ZHUYIN, InputScheme.STROKE -> TypingWay.KEEP
            else -> TypingWay.FULL_PINYIN
        }
        // 现在打中文的键盘；不是中文、或仓颉注音这类另有打法的，不问键盘也不换。
        val chineseLayout = profile?.takeIf { it.language == InputLanguage.CHINESE && typing != TypingWay.KEEP }?.layout
        val keyboard = when {
            chineseLayout == null -> KeyboardWay.KEEP
            chineseLayout.kind == LayoutKind.T9 -> KeyboardWay.T9
            chineseLayout.id == InputLayout.MERGED14.id -> KeyboardWay.K14
            chineseLayout.kind == LayoutKind.ALPHABETIC -> KeyboardWay.QWERTY
            else -> KeyboardWay.KEEP
        }
        val doublePinyins = DoublePinyinScheme.BUILTINS + runCatching { DoublePinyinSchemes.load(context) }.getOrDefault(emptyList())
        // 自己下载过九键词组模型（不是安装包带的那份）也算在用九键。
        val t9Download = listOf(CandidateModelCatalog.NGRAM, CandidateModelCatalog.NGRAM_SMALL).any { id ->
            ModelManager.getModel(id)?.versions?.any { hasDownloadedModelFiles(ModelStorage.getModelDir(context, id), it) } == true
        }
        val keys = KeyboardInputPreferences.read(context)
        return SetupFacts(
            tier = DeviceModelProfiles.choose(hardware).tier,
            performanceTier = com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.classify(hardware),
            // 和键盘一样按最短边 600dp 分手机、平板。
            device = if (context.resources.configuration.smallestScreenWidthDp >= 600) "平板" else "手机",
            memoryBytes = hardware.totalMemoryBytes,
            cores = hardware.processors,
            maxCpuKHz = hardware.maxCpuKHz,
            cpu = hardware.cpu,
            soc = socName(),
            language = Locale.getDefault().let { it.getDisplayLanguage(it) }.takeIf { it.isNotBlank() },
            freeBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
            online = network != null,
            metered = network != null && !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            japanese = InputLanguage.JAPANESE in LanguagePreferences.enabled(context) || Locale.getDefault().language == "ja",
            sttEnabled = SettingsPreferences.isSttEnabled(context),
            streamingVoice = chinese != null && SpeechModelSelection.hasCorrection(chinese),
            usesT9 = t9Layout || t9Download,
            usesCaption = com.kingzcheung.xime.BuildConfig.ELDER ||
                runCatching { com.kingzcheung.xime.ui.caption.CaptionLauncherIcon.isEnabled(context) }.getOrDefault(false),
            installed = installed,
            inPackage = inPackage,
            sizes = models.associate { it.id to catalogBytes(it) },
            typing = typing,
            typingName = profile?.scheme?.displayName.orEmpty(),
            // 九键、14 键、自建合并键布局上的双拼算同一种双拼（26 键那个方案）。
            doublePinyin = if (typing != TypingWay.DOUBLE_PINYIN) null else DoublePinyinSchemes.baseId(currentSchema),
            keyboard = keyboard,
            keyboardName = chineseLayout?.displayName.orEmpty(),
            shownDoublePinyin = DoublePinyinScheme.BUILTINS.map { it.id }.filterTo(linkedSetOf()) { it in enabledSchemas },
            doublePinyinChoices = doublePinyins.map { DoublePinyinChoice(it.id, it.name, it.preview(), !DoublePinyinSchemes.isCustom(it.id),
                lettersOnly = it.usesSemicolon) },
            wubiInstalled = File(SchemaManager.getRimeDir(context), "${SetupGuidePlanner.WUBI}.schema.yaml").isFile,
            wubi98Installed = File(SchemaManager.getRimeDir(context), "${SetupGuidePlanner.WUBI98}.schema.yaml").isFile,
            doublePinyinKeyHints = keys.doublePinyinKeyHints,
            canEnableDoublePinyinKeyHints = canEnableKeyHints(keys),
            fuzzy = SpellingPatches.fuzzy(context).mapTo(linkedSetOf()) { it.id },
            captionIconChangeable = !com.kingzcheung.xime.BuildConfig.ELDER,
            availMemoryBytes = runCatching {
                android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java)!!.getMemoryInfo(it) }.availMem
            }.getOrNull()?.takeIf { it > 0 },
            memorySaver = SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_MEMORY_SAVER, false),
            memoryExtras = memoryExtras(context),
        )
    }

    /** 现在开着、关掉能省内存的功能；开没开和省多少都问「性能与省电」那张代价表，两页说同一个数。 */
    private fun memoryExtras(context: Context): List<MemoryExtra> {
        val table = runCatching { PerformanceCosts.source(context)?.device?.items }.getOrNull().orEmpty()
        return SetupGuidePlanner.memoryExtras.mapNotNull { (id, title, loss) ->
            val item = PerformanceCosts.Item.byId(id) ?: return@mapNotNull null
            if (!runCatching { PerformanceCosts.isOn(context, item) }.getOrDefault(false)) return@mapNotNull null
            MemoryExtra(id, title, loss, table[id]?.memoryMb?.takeIf { it >= 1 })
        }
    }

    private fun socName(): String? {
        val soc = if (AndroidPlatform.socInfo)
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        else ""
        return soc.ifBlank { Build.HARDWARE.takeIf { it.isNotBlank() && it != Build.UNKNOWN } }
    }

    /** 目录里的大小；归档下载的模型文件清单没有字节数，用版本上写的大小（如“136.4MB”）。 */
    internal fun catalogBytes(model: ModelInfo): Long {
        val files = model.files.sumOf { it.sizeBytes }
        if (files > 0) return files
        val size = model.resolvedVersion()?.size.orEmpty()
        val number = Regex("([0-9.]+)\\s*([KMG]B)", RegexOption.IGNORE_CASE).find(size) ?: return 0
        val unit = when (number.groupValues[2].uppercase()) { "KB" -> 1024L; "MB" -> 1024L * 1024; else -> 1024L * 1024 * 1024 }
        return (number.groupValues[1].toDouble() * unit).toLong()
    }

    data class Result(val removed: Int, val freedBytes: Long, val downloading: Long, val waitingForWifi: Boolean, val failed: List<String>,
        /** 打字方式这一块改好的、没改成的，白话一句一行。 */
        val inputDone: List<String> = emptyList(), val inputFailed: List<String> = emptyList(),
        /** 在下载的、从安装包取出的模型，完成页写「语音、手写模型正在下载」。 */
        val downloadingIds: List<String> = emptyList(), val unpackingIds: List<String> = emptyList())

    /**
     * 照 [plan] 设置：改语音选项，删掉 [removeIds]（安装包里的不再解压），
     * 安装包里有的直接取回，其余排队下载（移动数据下除非 [allowMetered] 否则等 Wi-Fi）。
     */
    suspend fun apply(context: Context, plan: SetupPlan, removeIds: Set<String>, allowMetered: Boolean,
        hideDoublePinyin: Boolean = SetupGuidePlanner.HIDE_OTHER_DOUBLE_PINYIN_BY_DEFAULT): Result {
        val app = context.applicationContext
        val facts = readFacts(app)
        val manager = AsrModelManager(app)
        SettingsPreferences.setSttEnabled(app, plan.sttEnabled)
        plan.voiceMode?.let { mode ->
            listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH).forEach { runCatching { manager.setModel(mode, it) } }
        }
        val keepIds = plan.keep.map { it.id }.toSet()
        // 不在方案里的首次默认模型不再自动下载。
        SetupGuidePlanner.managedIds.filter { it !in keepIds }.forEach { DefaultModelInstaller.markHandled(app, it) }

        val removing = plan.remove.filter { it.id in removeIds }
        BundledModelInstaller.skip(app, removing.map { it.id }.filter { it in BundledModelInstaller.modelIds }.toSet())
        val failed = mutableListOf<String>()
        var freed = 0L
        for (item in removing) {
            // 安装包带的九键词组模型还没放进词库目录：关掉它，启动时就不会再放进去。
            if (item.source != SetupSource.ON_PHONE) {
                if (ModelManager.getModel(item.id)?.category == ModelCategory.CANDIDATE) CandidateModelAssets.setDisabled(app, item.id, true)
                continue
            }
            if (ModelManager.deleteModel(app, item.id)) freed += item.bytes
            else failed += item.title
        }

        val missing = plan.keep.filter { it.id !in facts.installed }
        val fromPackage = missing.filter { it.id in facts.inPackage }
        val fromNetwork = missing.filterNot { it.id in facts.inPackage }
        for (item in fromPackage) ModelManager.getModel(item.id)?.let { ModelManager.downloadModelInBackground(app, it) }
        var waiting = false
        if (fromNetwork.isNotEmpty()) {
            DefaultModelInstaller.requestGuided(app, fromNetwork.map { it.id }.toSet(), allowMetered)
            waiting = facts.metered && !allowMetered
            // 已在跑的首次下载可能还没看到新加的这几项：能下就直接开始，同一模型的下载会合并。
            if (facts.online && !waiting) fromNetwork.forEach { item ->
                ModelManager.getModel(item.id)?.let { ModelManager.downloadModelInBackground(app, it, onlyIfDefaultPending = true) }
            }
        }
        SettingsPreferences.getPrefsPublic(app).edit().putBoolean(KEY_MEMORY_SAVER, plan.answers.memorySaver).apply()
        // 有内存焦虑：语音空闲很快卸掉。和「性能与省电」改滑条写同一处；已经一样就不写，不把“自动”改成“自定义”。
        plan.voiceIdleMinutes?.let { minutes ->
            val now = PerformanceProfile.read(app)
            if (now.speechEngineIdleMinutes != minutes || now.speechRefinerIdleMinutes != minutes)
                PerformanceProfile.applyCustom(app, now.copy(speechRefinerIdleMinutes = minutes, speechEngineIdleMinutes = minutes))
        }
        // 另外选了要关的功能：和设置页、「性能与省电」写同一个值（PerformanceCosts.set）。
        plan.turnOff.forEach { extra ->
            PerformanceCosts.Item.byId(extra.id)?.let { runCatching { PerformanceCosts.set(app, it, false) } }
        }
        val inputFailed = applyInput(app, plan.input, hideDoublePinyin, facts.online)
        val inputDone = (plan.input.lines + listOfNotNull(plan.input.hideLine?.takeIf { hideDoublePinyin },
            plan.turnOff.takeIf { it.isNotEmpty() }?.let { off -> "为省内存关掉了：${off.joinToString("、") { it.title }}。" }))
            .filterNot { it.startsWith("打字方式还是") }
            .filterNot { line -> "五笔" in line && inputFailed.any { "五笔" in it } }
        markShown(app)
        FileLogger.i("SetupGuide", "按推荐设置：语音=${plan.voiceMode} 九键=${plan.t9Model} " +
            "删除=${removing.map { it.id }} 取回=${fromPackage.map { it.id }} 下载=${fromNetwork.map { it.id }} 打字=${plan.input} " +
            "省内存=${plan.answers.memorySaver} 语音卸载=${plan.voiceIdleMinutes} 关掉=${plan.turnOff.map { it.id }}")
        return Result(removing.count { it.source == SetupSource.ON_PHONE } - failed.size, freed, fromNetwork.sumOf { it.bytes }, waiting, failed,
            inputDone, inputFailed, fromNetwork.map { it.id }, fromPackage.map { it.id })
    }

    /** 「更多配置」要问的现在的值；在 IO 线程调用（玻璃效果的实测代价要读文件）。 */
    fun readMore(context: Context): SetupMoreFacts {
        val keys = KeyboardInputPreferences.read(context)
        val glass = com.kingzcheung.xime.settings.FrostedGlassPreferences.readProfiles(context).dark.enabled
        return SetupMoreFacts(
            current = SetupMoreChoices(
                spaceHold = com.kingzcheung.xime.ui.keyboard.CursorChoices.spaceHold(keys),
                deleteSkipsAssociation = SettingsPreferences.isDeleteSkipsAssociation(context),
                clearBeforeCursorOnly = keys.clearBeforeCursorOnly,
                textSize = SetupMore.textSizeOf(SettingsPreferences.getCandidateTextSize(context), keys.keyTextScale),
                feedback = SetupMore.feedbackOf(SettingsPreferences.isSoundEnabled(context), SettingsPreferences.isVibrationEnabled(context)),
                glass = glass,
                autoPunctuation = SettingsPreferences.isSttAutoPunctuation(context),
                // 存着的角标方式，不管滑行：开着滑行时键盘按长按处理，关了滑行就回到这里存的方式（见 SetupMore.effectiveCorner）。
                corner = when {
                    keys.symbolInputMode == com.kingzcheung.xime.ui.keyboard.SymbolInputMode.LONG_PRESS ->
                        com.kingzcheung.xime.ui.keyboard.CornerInputChoice.LONG_PRESS
                    keys.letterLongPressMenu -> com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE_BUBBLE
                    else -> com.kingzcheung.xime.ui.keyboard.CornerInputChoice.SWIPE
                },
                reverseSwipe = keys.reverseSymbolSwipe,
                glide = GlideChoice.of(keys.glideTyping, keys.chineseGlideTyping),
                cancelButton = SettingsPreferences.shouldShowCandidateCancelButton(context),
            ),
            sttEnabled = SettingsPreferences.isSttEnabled(context),
            predictionEnabled = SettingsPreferences.isSmartPredictionEnabled(context),
            reverseSwipe = keys.reverseSymbolSwipe,
            candidateSp = SettingsPreferences.getCandidateTextSize(context),
            keyScalePercent = (keys.keyTextScale * 100).toInt(),
            glassCost = runCatching {
                com.kingzcheung.xime.settings.PerformanceCosts.let { costs -> costs.source(context)?.let { costs.note(it,
                    com.kingzcheung.xime.settings.PerformanceCosts.Item.GLASS.id) } }
            }.getOrNull(),
            reverseDeleteSwipe = keys.reverseDeleteSwipe,
            glideLock = glideLock(keys),
        )
    }

    /** 滑行被「键盘面板滑动光标」锁住时的说明，同设置页「滑行输入」；现在开着滑行的不算锁。 */
    private fun glideLock(keys: KeyboardInputPreferences): String? =
        InputGestureLocks.lockFor(keys, InputGestureLock.Option.GLIDE_TYPING, true)
            ?.takeIf { !keys.glideTyping && !keys.chineseGlideTyping }?.message

    /**
     * 照「更多配置」改设置：只改 [from] 到 [to] 变了的项，每项都走设置页同一个写法（长按空格连带键盘滑动光标、
     * 振动关了再开恢复原来的模式），输入法监听这些设置，马上生效。返回改了的项，白话。
     */
    fun applyMore(context: Context, from: SetupMoreChoices, wanted: SetupMoreChoices): List<String> {
        val app = context.applicationContext
        if (wanted.spaceHold != from.spaceHold)
            com.kingzcheung.xime.ui.keyboard.CursorChoices.withSpaceHold(KeyboardInputPreferences.read(app), wanted.spaceHold).save(app)
        // 长按空格选了「移动光标」会关掉键盘面板滑动光标、解开滑行的锁；改完还锁着就不开滑行（设置页同一把锁）。
        val to = if (wanted.glide != from.glide && wanted.glide != GlideChoice.OFF &&
            glideLock(KeyboardInputPreferences.read(app)) != null) wanted.copy(glide = from.glide) else wanted
        if (to.glide != from.glide)
            KeyboardInputPreferences.read(app).copy(glideTyping = to.glide.english, chineseGlideTyping = to.glide.chinese).save(app)
        // 和设置页三张卡同一个写法（选长按顺带打开九键拂动）。
        if (to.corner != from.corner) to.corner.applyTo(KeyboardInputPreferences.read(app)).save(app)
        if (to.reverseSwipe != from.reverseSwipe)
            KeyboardInputPreferences.read(app).copy(reverseSymbolSwipe = to.reverseSwipe).save(app)
        if (to.cancelButton != from.cancelButton) SettingsPreferences.setShowCandidateCancelButton(app, to.cancelButton)
        if (to.deleteSkipsAssociation != from.deleteSkipsAssociation)
            SettingsPreferences.setDeleteSkipsAssociation(app, to.deleteSkipsAssociation)
        if (to.clearBeforeCursorOnly != from.clearBeforeCursorOnly)
            KeyboardInputPreferences.read(app).copy(clearBeforeCursorOnly = to.clearBeforeCursorOnly).save(app)
        if (to.textSize != from.textSize && to.textSize != TextSizeChoice.CUSTOM) {
            SettingsPreferences.setCandidateTextSize(app, to.textSize.candidateSp)
            KeyboardInputPreferences.read(app).copy(keyTextScale = to.textSize.keyScale).save(app)
        }
        if (to.feedback != from.feedback) {
            SettingsPreferences.setSoundEnabled(app, to.feedback.sound)
            SettingsPreferences.setVibrationEnabled(app, to.feedback.vibration)
        }
        if (to.glass != from.glass) com.kingzcheung.xime.settings.FrostedGlassPreferences.let { glass ->
            glass.saveProfiles(app, glass.readProfiles(app).withEnabled(to.glass))
        }
        if (to.autoPunctuation != from.autoPunctuation) SettingsPreferences.setSttAutoPunctuation(app, to.autoPunctuation)
        val changes = SetupMore.changes(from, to)
        if (changes.isNotEmpty()) FileLogger.i("SetupGuide", "更多配置：$from → $to")
        return changes
    }

    /**
     * 打字方式：模糊音、双拼方案栏、五笔、当前方案、桌面「字幕」图标。都走设置页同一套函数（双拼页、模糊音、方案市场），
     * 输入法服务照常收到改动后重新部署、切回当前方案。返回没做成的，白话。
     */
    private suspend fun applyInput(app: Context, input: InputPlan, hideDoublePinyin: Boolean, online: Boolean): List<String> {
        val failed = mutableListOf<String>()
        if (input.fuzzyOn.isNotEmpty() || input.fuzzyOff.isNotEmpty()) {
            SpellingPatches.Fuzzy.entries.forEach { item ->
                if (item.id in input.fuzzyOn) SpellingPatches.setFuzzy(app, item, true)
                if (item.id in input.fuzzyOff) SpellingPatches.setFuzzy(app, item, false)
            }
            runCatching { SpellingPatches.apply(app) }.onFailure { failed += "模糊音没改成：${it.message}" }
        }

        var wubiReady = !input.useWubi
        var redeploy = false
        val wubi = input.wubiId
        val wubiName = SetupGuidePlanner.wubiName(wubi)
        if (input.useWubi) {
            if (input.downloadWubi) {
                val scheme = com.kingzcheung.xime.settings.CuratedRimeSchemes.merge(emptyList()).first { it.id == wubi }
                val download = if (online) com.kingzcheung.xime.settings.XimeIndexSource.downloadScheme(app, scheme) else null
                val install = if (download?.success == true) com.kingzcheung.xime.settings.XimeIndexSource.installFromMarket(app, scheme) else null
                if (install?.success == true) {
                    com.kingzcheung.xime.settings.MarketVersionStore.setSchemeVersion(app, scheme.id, scheme.currentVersion)
                    wubiReady = true
                    redeploy = true
                } else failed += when {
                    !online -> "${wubiName}方案没下载：现在没联网。可以连上网后再来这页，或在 扩展 → 方案 里获取。"
                    else -> "${wubiName}方案没装上(${install?.failureReason ?: download?.failureReason ?: "下载失败"})，可以在 扩展 → 方案 里再获取。"
                }
            } else wubiReady = true
            // 装过但从方案栏关掉了：打开它。
            val enabled = SchemaManager.getEnabledSchemas(app)
            if (wubiReady && wubi !in enabled) {
                SchemaManager.setEnabledSchemas(app, enabled + wubi)
                LanguagePreferences.enableForSchema(app, wubi)
                redeploy = true
            }
        }

        // 先藏、再切当前、最后打开选中的双拼：打开那一步通知输入法部署，并切到已经写好的当前方案。
        if (hideDoublePinyin) input.hideDoublePinyin.forEach { id ->
            runCatching { DoublePinyinSchemes.setEnabled(app, id, false) }
        }
        val switchTo = input.switchTo?.takeIf { it != wubi || wubiReady }
        switchTo?.let { id ->
            // 要用的布局不在方案栏里（如 14 键没开过、全拼被关掉过）：先打开，双拼由下面那一步打开。
            // 双拼的九键版本走双拼页同一个开关（排在九键后面）；双拼的 14 键版本随双拼和中文14键自动出现。
            val missing = input.enable.filterNot { it in SchemaManager.getEnabledSchemas(app) }
            missing.filter(DoublePinyinSchemes::isT9).forEach { t9 ->
                runCatching { DoublePinyinSchemes.setEnabled(app, t9, true) }.onFailure { failed += "九键双拼没打开：${it.message}" }
            }
            val others = missing.filterNot(DoublePinyinSchemes::isT9)
            if (others.isNotEmpty()) {
                SchemaManager.setEnabledSchemas(app, SchemaManager.getEnabledSchemas(app) + others)
                others.forEach { LanguagePreferences.enableForSchema(app, it) }
                redeploy = true
            }
            SettingsPreferences.setCurrentSchema(app, id)
            InputModes.selectProfile(app, id, InputLanguage.CHINESE)
        }
        input.showDoublePinyin?.let { id ->
            runCatching { DoublePinyinSchemes.setEnabled(app, id, true) }.onFailure { failed += "双拼没打开：${it.message}" }
        }
        // 和双拼方案页的开关是同一个设置，正在显示的键盘监听它，马上生效。
        if (input.keyHintsOn && canEnableKeyHints(KeyboardInputPreferences.read(app))) {
            SettingsPreferences.getPrefsPublic(app).edit()
                .putBoolean(KeyboardInputPreferences.DOUBLE_PINYIN_KEY_HINTS_KEY, true).apply()
        }
        if (redeploy) {
            // 与「已安装的方案」里「重新部署(修复)」同一步。
            val deployed = runCatching {
                com.kingzcheung.xime.settings.KeysConfigHelper.loadConfig(app)
                com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(app)
                com.kingzcheung.xime.rime.RimeConfigHelper.redeploy(app)
            }.getOrDefault(false)
            if (!deployed) failed += "方案没部署成功；到 设置 → 已安装的方案 点「重新部署(修复)」。"
        }
        // 方案已经部署好时直接切过去；还没部署的，输入法部署完按当前方案切。
        if (switchTo != null && com.kingzcheung.xime.rime.RimeEngine.isInitialized()) runCatching {
            val engine = com.kingzcheung.xime.rime.RimeEngine.getInstance()
            if (switchTo in engine.getAvailableSchemas()) engine.switchSchema(switchTo)
        }

        input.captionIcon?.let { on ->
            // 字幕是插件（11-05）：要用就先从随包目录装上（不联网），入口才出现；不用只关图标，插件不动。
            if (on && !com.kingzcheung.xime.ui.caption.CaptionPlugin.install(app)) {
                failed += "「字幕」插件没装上，可以在 设置 → 扩展 → 插件 里装。"
                return@let
            }
            runCatching { withContext(Dispatchers.Main) { com.kingzcheung.xime.ui.caption.CaptionLauncherIcon.setEnabled(app, on) } }
                .onFailure { failed += "桌面「字幕」图标没改成，可以在 设置 → 扩展 → 插件 → 字幕 里开关。" }
        }
        return failed
    }
}
