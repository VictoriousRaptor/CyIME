package com.kingzcheung.xime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlin.concurrent.thread

/**
 * 开发者工具（只在“装到我的设备”包里起作用），给自动打字和测试脚本用，免得测试学进用户词库：
 * - `op=incognito --ez on true|false`：开关无痕，测试前打开、测试后恢复，Rime 和 CyLM 都不学。
 * - `op=unlearn`：读 Android/data/<包名>/files/unlearn.tsv（每行“词<TAB>次数”），从当前方案的用户词库里
 *   减去这么多次学习，减完的词回到词库原来的位置；用户自己别的学习不动。
 * - `op=learn`：读 Android/data/<包名>/files/learn.tsv（每行“词<TAB>拼 音<TAB>次数”），把用户自己在别处写过的词
 *   按选过这么多次学进当前方案的用户词库（他自己的聊天记录，只进他的设备）。
 * - `op=reset_cylm`：删掉 CyLM 在本机学到的那层（rime/cylm_learned.tsv），下次打开回到安装包带的用户层。
 *   之后要重启输入法进程，免得内存里的那层再写回去。
 * - `op=export`：把当前方案的用户词库（Rime 文本快照 .userdb.txt）和 CyLM 学习层复制到
 *   Android/data/<包名>/files/cyime-dev/<时间>/，供电脑 adb pull 分析。
 * - `op=snapshot`：自动打字前存一份快照（kime_settings 全部设置：无痕、全角、方案、布局等；自定义布局；
 *   rime/user.yaml；CyLM 学习层；当前方案用户词库），放在 Android/data/<包名>/files/cyime-dev/snapshot/。
 * - `op=restore`：打完后把上面这些原样放回（用户词库按快照逐条复原，期间新学的词删掉），再核对一遍。
 *   之后脚本要重启输入法进程，让内存里的设置和 CyLM 学习层重新读取，再发 `op=verify`。
 * - `op=verify`：只核对现状和快照是否完全一样，结果第一个词是 OK 或 DIFFERENT，后面列出哪里不一样。
 * - `op=cpu_bench_state`：本机 CPU 实测结果（存着的单线程、双线程中位纳秒，测过的核）和按它定的档位、卡在哪、「自动」套的档，
 *   给 tools/ime_lab/cpu_bench_calibrate.py 对照电脑上量的数。只读，不测。
 * - `op=perf_state`：列出登记了性能代价的开关（[com.kingzcheung.xime.settings.PerformanceCosts.Item]）现在开没开，
 *   一行 `id=true|false`，给 tools/ime_lab/perf_cost_bench.py 记下原状。
 * - `op=perf_switch --es id <id> --ez on true|false`：和设置页拨这个开关写同样的值；模糊音、双拼纠错写完补丁，
 *   脚本重启输入法后部署。测完先 `op=restore` 放回设置，再 `op=perf_sync` 让方案补丁跟着设置写回去。
 * - `op=cell_state`：导入词库现状，一行 `selected=a,b applied=a,b english=… applied_english=… installed=id:词数,…
 *   applying=true|false last=<上次 cell_set 的结果>`，给 tools/ime_lab/dict_cost_bench.py 记下原状、等应用完。
 * - `op=cell_set --es ids a,b --ez english false`：把开着的导入词库设成正好这几个（目录里的搜狗词库没下载的先下载），
 *   再像点「应用更改」一样应用。应用要几分钟，所以立刻回 `cell_set: started`，脚本用 cell_state 等 applying=false。
 * - `op=cell_remove --es ids a,b`：删掉这几个没在用的下载词库（脚本测完删掉它为测量下载的，原来就有的不动）。
 * - `op=data_state`：除了安装包以外还有什么在决定候选，一行 `t9_ngram=字节数:sha256前16位 models=[名:字节数,…]
 *   userdb=[名:字节数,…] personal=[名:字节数:行数,…] custom=[名:字节数,…] cells=[名:字节数,…]`：rime/ 里实际在用的词组模型
 *   （下载的哪一档、安装包带的，还是以前的安装包留下的，靠大小和校验和分清）、别的可选模型、每个用户词库、
 *   个人词条和自定义短语文件（行数含文件头）、方案补丁、导入词库目录。只读，只报名字和数量，不读出任何词条；
 *   真机和电脑对照结果前记下原状。
 * - `op=touch_stats`：把按键落点统计（[com.kingzcheung.xime.stats.TouchStatsData]：每个键的落点格、没出字的原因、
 *   几根手指交叠、删掉重打的键对等，只有计数）连同打字统计、屏幕密度写到 Android/data/<包名>/files/cyime-dev/touch-stats-<时间>/，
 *   给 tools/ime_lab/touch_stats_report.py 出报告。不含任何打过的文字。
 * - `op=enter_info`：当前输入框的 EditorInfo（包名、回车动作、「回车不做动作」标记、类型、actionId），不含文字。
 * - `op=enter_probe --es method <做法> --es marker t01`：清空当前输入框，上屏标记，用这一种做法回车，
 *   回 `result=sent|newline|nothing|other`（只报长度）。做法见 [EnterProbe.methods]；给 QQ、微信试哪种回车发送、哪种换行。
 *   会真的发出消息，只在文件传输助手、我的电脑这类发给自己的聊天里用。
 * - `op=stt_record --ez on true|false`：开关「保存语音调优录音」（和设置页同一个值），回 `stt_record=true|false`；
 *   录音存 Android/data/<包名>/files/speech_tuning/，电脑 adb pull 回放比模型。测完关回去。
 * 输入法正在部署时回 busy，稍后重试。每个 op 的结果写进广播结果（am broadcast 会打印 data=...）和 logcat 标签 DevLearning。
 * 用法：adb shell am broadcast -n com.cyletix.cyime/com.kingzcheung.xime.service.DevLearningReceiver -a com.cyletix.cyime.DEV_LEARNING --es op unlearn
 * 只有 adb（持有 DUMP 权限）能发，普通应用发不进来。
 */
class DevLearningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!BuildConfig.DEV_TOOLS) { report("dev tools off in this build"); return }
        val app = context.applicationContext
        when (intent.getStringExtra("op")) {
            "incognito" -> {
                SettingsPreferences.setIncognito(app, intent.getBooleanExtra("on", true))
                // `am broadcast` prints this, so a script can check the switch really changed.
                report("incognito=${SettingsPreferences.isIncognito(app)}")
            }
            "stt_record" -> {
                SettingsPreferences.setSttDebugRecord(app, intent.getBooleanExtra("on", false))
                report("stt_record=${SettingsPreferences.isSttDebugRecord(app)}")
            }
            "unlearn" -> {
                val file = File(app.getExternalFilesDir(null), "unlearn.tsv")
                if (!file.isFile) { report("unlearn: ${file.path} missing"); return }
                val rows = file.readText()
                background { "unlearn: ${RimeEngine.getInstance().unlearn(rows)} commits taken back" }
            }
            "learn" -> {
                val file = File(app.getExternalFilesDir(null), "learn.tsv")
                if (!file.isFile) { report("learn: ${file.path} missing"); return }
                val rows = file.readText()
                background { "learn: ${RimeEngine.getInstance().learn(rows)} words taught" }
            }
            "reset_cylm" -> {
                val layer = File(File(app.filesDir, "rime"), "cylm_learned.tsv")
                report("reset_cylm: ${if (layer.delete()) "removed" else "nothing to remove"}")
            }
            "export" -> {
                val dir = File(app.getExternalFilesDir(null), "cyime-dev/" +
                    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
                background {
                    dir.mkdirs()
                    val rime = File(app.filesDir, "rime")
                    val dict = RimeEngine.getInstance().exportUserDict(File(dir, "user_dict.userdb.txt").path)
                    val cylm = File(rime, "cylm_learned.tsv").takeIf { it.isFile }?.copyTo(File(dir, "cylm_learned.tsv"), true) != null
                    "export: ${dir.path} userdb=$dict cylm_learned=$cylm"
                }
            }
            "data_state" -> {
                // 不碰引擎，不用等部署的锁；200 MB 的模型算一遍校验和要一两秒，放后台。
                val pending = goAsync()
                thread(name = "dev-data-state") {
                    val message = runCatching { dataState(app) }.getOrElse { "data_state failed: ${it.message}" }
                    Log.i(TAG, message)
                    pending.resultData = message
                    pending.finish()
                }
            }
            "touch_stats" -> {
                val dir = File(app.getExternalFilesDir(null), "cyime-dev/touch-stats-" +
                    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
                // 不碰引擎，不用等部署的锁。
                val pending = goAsync()
                thread(name = "dev-touch-stats") {
                    val message = runCatching { com.kingzcheung.xime.stats.TouchStatsStore.export(app, dir) }
                        .getOrElse { "touch_stats failed: ${it.message}" }
                    Log.i(TAG, message)
                    pending.resultData = message
                    pending.finish()
                }
            }
            "snapshot" -> background {
                val dir = snapshotDir(app)
                dir.deleteRecursively()
                File(dir, "files").mkdirs()
                File(dir, SETTINGS_FILE).writeText(BackupManager.prefsToJson(settings(app)))
                SNAPSHOT_FILES.forEach { name ->
                    File(app.filesDir, name).takeIf { it.isFile }?.copyTo(File(dir, "files/$name"), true)
                }
                check(RimeEngine.getInstance().exportUserDict(File(dir, USERDB).path)) { "user dictionary export failed" }
                "snapshot: ${dir.path} settings=${settings(app).all.size} " +
                    "files=${SNAPSHOT_FILES.count { File(dir, "files/$it").isFile }} userdb=${dataLines(File(dir, USERDB)).size}"
            }
            "restore" -> background {
                val dir = snapshotDir(app)
                check(File(dir, SETTINGS_FILE).isFile && File(dir, USERDB).isFile) { "no snapshot in ${dir.path}" }
                BackupManager.restorePrefsJson(settings(app), File(dir, SETTINGS_FILE).readBytes(), replace = true)
                // Only files the run really changed are put back. Those are also held in memory (CyLM layer,
                // Rime options), so only then does the script have to restart the IME; a restart while the
                // user types looks like a crash to them.
                val putBack = SNAPSHOT_FILES.filter { name ->
                    val saved = File(dir, "files/$name")
                    val live = File(app.filesDir, name)
                    val a = saved.takeIf { it.isFile }?.let { comparable(name, it) }
                    val b = live.takeIf { it.isFile }?.let { comparable(name, it) }
                    if (a != b) { if (saved.isFile) saved.copyTo(live, true) else live.delete() }
                    a != b
                }
                val changed = RimeEngine.getInstance().restoreUserDict(File(dir, USERDB).path)
                check(changed >= 0) { "user dictionary restore failed" }
                "restore: userdb entries put back=$changed files put back=${putBack.joinToString(",")} " +
                    "restart=${if (putBack.isEmpty()) "no" else "yes"}; " + verify(app, dir)
            }
            "verify" -> background { verify(app, snapshotDir(app)) }
            "cpu_bench_state" -> {
                val cpu = com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.cached(app)
                val device = com.kingzcheung.xime.runtime.adaptation.DeviceCapabilityProbe.read(app)
                val verdict = com.kingzcheung.xime.runtime.adaptation.PerformanceTiers.explain(device)
                report("cpu_bench version=${com.kingzcheung.xime.runtime.adaptation.CpuBenchmark.VERSION} " +
                    "single_ns=${cpu?.singleNs ?: -1} dual_ns=${cpu?.dualNs ?: -1} " +
                    "cpus=${com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.cachedCpus(app).orEmpty()} " +
                    "tier=${verdict.tier} limit=${verdict.limit} cpu_tier=${verdict.cpuTier ?: "none"} " +
                    "auto=${com.kingzcheung.xime.settings.PerformanceProfile.autoTier(app) ?: "off"}")
            }
            "perf_state" -> report(com.kingzcheung.xime.settings.PerformanceCosts.Item.entries.joinToString(" ") {
                "${it.id}=${runCatching { com.kingzcheung.xime.settings.PerformanceCosts.isOn(app, it) }.getOrDefault(false)}"
            })
            "perf_switch" -> {
                val item = com.kingzcheung.xime.settings.PerformanceCosts.Item.byId(intent.getStringExtra("id").orEmpty())
                if (item == null) { report("perf_switch: unknown id"); return }
                val on = intent.getBooleanExtra("on", false)
                background {
                    com.kingzcheung.xime.settings.PerformanceCosts.set(app, item, on)
                    "perf_switch: ${item.id}=${com.kingzcheung.xime.settings.PerformanceCosts.isOn(app, item)}"
                }
            }
            "perf_sync" -> background {
                com.kingzcheung.xime.settings.SpellingPatches.apply(app)
                "perf_sync: done"
            }
            "cell_state" -> {
                val state = com.kingzcheung.xime.settings.CellDictionaryManager.store(app).read()
                report("selected=${state.selected.sorted().joinToString(",")} applied=${state.applied.sorted().joinToString(",")} " +
                    "english=${state.selectedEnglish} applied_english=${state.appliedEnglish} " +
                    "installed=${state.installed.joinToString(",") { "${it.id}:${it.count}" }} " +
                    "applying=$cellApplying last=${cellLast.replace(' ', '_')}")
            }
            "cell_set" -> {
                if (cellApplying) { report("busy: still applying"); return }
                val ids = intent.getStringExtra("ids").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
                val english = intent.getBooleanExtra("english", false)
                cellApplying = true
                cellLast = "running"
                // Not in background(): applying deploys and takes the engine itself, for minutes.
                thread(name = "dev-cell-set") {
                    cellLast = runCatching {
                        kotlinx.coroutines.runBlocking {
                            val manager = com.kingzcheung.xime.settings.CellDictionaryManager
                            val store = manager.store(app)
                            val installed = store.read().installed.map { it.id }.toSet()
                            ids.filter { it !in installed }.forEach { id ->
                                val offer = com.kingzcheung.xime.settings.CellDictionaryCatalog.offers.firstOrNull { it.id == id }
                                    ?: error("not installed and not in the catalog: $id")
                                store.install(manager.download(offer), offer)
                            }
                            val state = store.read()
                            state.installed.forEach { store.select(it.id, it.id in ids) }
                            store.selectEnglish(english)
                            com.kingzcheung.xime.rime.RimeConfigHelper.applyCellDictionaries(app)
                            val after = store.read()
                            check(after.applied == ids && after.appliedEnglish == english) { "applied ${after.applied}" }
                            "ok words=${after.installed.filter { it.id in ids }.sumOf { it.count }}"
                        }
                    }.getOrElse { "failed: ${it.message}" }
                    Log.i(TAG, "cell_set: $cellLast")
                    cellApplying = false
                }
                report("cell_set: started")
            }
            "cell_remove" -> {
                if (cellApplying) { report("busy: still applying"); return }
                val store = com.kingzcheung.xime.settings.CellDictionaryManager.store(app)
                val ids = intent.getStringExtra("ids").orEmpty().split(",").filter { it.isNotBlank() }
                report("cell_remove: " + ids.joinToString(" ") { id -> "$id=" + runCatching { store.remove(id); "removed" }
                    .getOrElse { "kept(${it.message})" }.replace(' ', '_') })
            }
            "enter_info" -> report(EnterProbe.describe(EnterProbe.service?.get()?.currentInputEditorInfo))
            "enter_probe" -> {
                val pending = goAsync()
                val method = intent.getStringExtra("method").orEmpty()
                val marker = intent.getStringExtra("marker")?.takeIf { it.isNotBlank() } ?: "t00"
                EnterProbe.service?.get()?.mainHandler?.post { EnterProbe.run(method, marker, pending) }
                    ?: run { pending.resultData = "enter_probe: keyboard not running"; pending.finish() }
            }
            else -> report("unknown op")
        }
    }

    private fun snapshotDir(app: Context) = File(app.getExternalFilesDir(null), "cyime-dev/snapshot")

    private fun settings(app: Context) = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    /** Entry lines of a Rime .userdb.txt snapshot (header comments are left out). */
    private fun dataLines(file: File): Set<String> =
        file.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    /** "OK", or "DIFFERENT" followed by every setting key, file and dictionary entry that doesn't match the snapshot. */
    private fun verify(app: Context, dir: File): String {
        check(File(dir, SETTINGS_FILE).isFile && File(dir, USERDB).isFile) { "no snapshot in ${dir.path}" }
        val problems = mutableListOf<String>()
        val saved = BackupManager.parsePrefs(File(dir, SETTINGS_FILE).readBytes())
        val live = BackupManager.parsePrefs(BackupManager.prefsToJson(settings(app)).toByteArray())
        val settingDiffs = (saved.keys + live.keys).toSortedSet().filter { it !in DEPLOY_STATE && saved[it] != live[it] }
        if (settingDiffs.isNotEmpty()) problems += "settings=" + settingDiffs.joinToString(",")
        SNAPSHOT_FILES.forEach { name ->
            val a = File(dir, "files/$name").takeIf { it.isFile }?.let { comparable(name, it) }
            val b = File(app.filesDir, name).takeIf { it.isFile }?.let { comparable(name, it) }
            if (a != b) problems += "file=$name"
        }
        val now = File(dir, "verify.userdb.txt")
        if (!RimeEngine.getInstance().exportUserDict(now.path)) problems += "userdb=export failed"
        else {
            val want = dataLines(File(dir, USERDB))
            val have = dataLines(now)
            val extra = have - want
            val missing = want - have
            if (extra.isNotEmpty() || missing.isNotEmpty())
                problems += "userdb=+${extra.size}/-${missing.size} " + (extra.take(5) + missing.take(5)).joinToString(" | ")
        }
        return if (problems.isEmpty()) "OK" else "DIFFERENT " + problems.joinToString("; ")
    }

    /**
     * File content as compared by [verify]. Rime rewrites the timestamps in user.yaml (last_build_time,
     * schema_access_time) whenever the IME restarts or a schema is opened, so those lines don't count;
     * the saved options and the selected schema do.
     */
    private fun comparable(name: String, file: File): String {
        if (!name.endsWith("user.yaml")) return file.readBytes().joinToString(",")
        var inAccessTimes = false
        return file.readLines().filter { line ->
            val trimmed = line.trim()
            if (inAccessTimes && line.startsWith("    ")) return@filter false
            inAccessTimes = trimmed == "schema_access_time:"
            !trimmed.startsWith("last_build_time:")
        }.joinToString("\n")
    }

    /**
     * What decides the candidates besides the package itself, by name, size and count only: no entry is read out.
     * The word model is the file really in rime/ (a downloaded tier, the bundled one, or one an earlier package
     * left behind: BundledRimeSync keeps files a package dropped), told apart by its size and checksum.
     */
    private fun dataState(app: Context): String {
        val rime = File(app.filesDir, "rime")
        fun bytes(file: File): Long =
            if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
        fun sha16(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().take(8).joinToString("") { "%02x".format(it) }
        }
        fun list(files: List<File>, describe: (File) -> String) = files.sortedBy { it.name }.joinToString(",", "[", "]", transform = describe)
        val top = rime.listFiles().orEmpty().toList()
        val model = File(rime, "t9_ngram.lm")
        val models = top.filter { it.isFile && it.name != model.name && (MODEL_FILE.matches(it.name)) }
        val stores = top.filter { it.name.endsWith(".userdb") || it.name.endsWith(".userdb.txt") }
        val personal = top.filter { it.isFile && PERSONAL_TEXT.matches(it.name) }
        val custom = top.filter { it.isFile && it.name.endsWith(".custom.yaml") }
        val cells = File(rime, "cyime_cells").listFiles().orEmpty().filter { it.isFile }
        return "t9_ngram=${if (model.isFile) "${model.length()}:${sha16(model)}" else "none"} " +
            "models=${list(models) { "${it.name}:${it.length()}" }} " +
            "userdb=${list(stores) { "${it.name}:${bytes(it)}" }} " +
            "personal=${list(personal) { "${it.name}:${it.length()}:${it.useLines { lines -> lines.count() }}" }} " +
            "custom=${list(custom) { "${it.name}:${it.length()}" }} " +
            "cells=${list(cells) { "${it.name}:${it.length()}" }}"
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        resultData = message
    }

    /**
     * Engine work off the main thread; the broadcast result carries its message.
     * A deploy holds the engine for minutes; waiting on it ran past the broadcast's time limit, and Android
     * killed the process in the middle of the deploy. So the engine is taken here with a short wait,
     * and a busy engine answers "busy" for the script to retry later.
     */
    private fun background(work: () -> String) {
        val pending = goAsync()
        thread(name = "dev-learning") {
            val lock = RimeEngine.rimeLock
            val message = if (!lock.tryLock(20, java.util.concurrent.TimeUnit.SECONDS)) "busy: engine is deploying, try again"
            else try { runCatching(work).getOrElse { "failed: ${it.message}" } } finally { lock.unlock() }
            Log.i(TAG, message)
            pending.resultData = message
            pending.finish()
        }
    }

    private companion object {
        /** cell_set runs on its own thread; cell_state reports these. */
        @Volatile var cellApplying = false
        @Volatile var cellLast = "none"
        const val ACTION = "com.cyletix.cyime.DEV_LEARNING"
        const val TAG = "DevLearning"
        const val SETTINGS_PREFS = "kime_settings"
        const val SETTINGS_FILE = "kime_settings.json"
        const val USERDB = "user_dict.userdb.txt"
        /** Optional scoring models in rime/ (data_state): word model variants, grammar, sentence model, CyLM files. */
        val MODEL_FILE = Regex(""".*\.(lm|gram|onnx)|cylm_.*""")
        /** Personal word packs and custom phrase tables in rime/ (data_state reports name, bytes and lines only). */
        val PERSONAL_TEXT = Regex("""user_.*\.dict\.yaml|custom_phrase.*\.txt""")
        /** Settings an app update rewrites on its own while redeploying Rime; not the run's doing. */
        val DEPLOY_STATE = setOf("deployment_done", "deployment_hash", "rime_assets_version", "builtin_schemas_merged")
        /** Files under filesDir an automated run may change: custom layouts, Rime saved options, CyLM learned layer. */
        val SNAPSHOT_FILES = listOf("custom-keyboard-layouts.json", "rime/user.yaml", "rime/cylm_learned.tsv")
    }
}
