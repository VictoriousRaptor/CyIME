package com.kingzcheung.xime.settings

import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

internal interface BackupPreferenceStore {
    fun validate(json: ByteArray)
    fun snapshot(name: String): ByteArray
    fun restore(name: String, json: ByteArray, replace: Boolean = false)
}

/** Validate the complete archive, then commit files and preferences with rollback. */
internal object BackupRestoreTransaction {
    @Synchronized
    fun restore(filesDir: File, preferences: BackupPreferenceStore, bytes: ByteArray): Result<Unit> {
        val staging = File(filesDir, ".restore-${UUID.randomUUID()}")
        val changes = mutableListOf<Pair<File, File>>()
        val oldFiles = linkedMapOf<File, File?>()
        val settings = linkedMapOf<String, ByteArray>()
        val oldSettings = linkedMapOf<String, ByteArray>()
        val newerCustomTargets = hashSetOf<File>()
        var preserveRecovery = false
        fun target(base: File, relative: String): File {
            require(relative.isNotBlank() && !relative.startsWith("/") && !relative.contains('\\') &&
                !Regex("^[A-Za-z]:").containsMatchIn(relative) && relative.split('/').none { it == ".." }) {
                "备份包包含非法路径: $relative"
            }
            val file = File(base, relative).canonicalFile
            require(file.path.startsWith(base.canonicalPath + File.separator)) { "备份包路径越界: $relative" }
            return file
        }
        try {
            check(staging.mkdirs()) { "无法创建恢复暂存目录" }
            var count = 0
            val seen = hashSetOf<String>()
            ZipInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) { input.closeEntry(); continue }
                    // 以后的版本写的备份包用新前缀，当成这一版的
                    val name = BackupManager.incomingEntry(entry.name)
                    require(seen.add(name)) { "备份包包含重复条目: $name" }
                    val rel = name.removePrefix(BackupManager.META_PREFIX)
                    val metadata = name.startsWith(BackupManager.META_PREFIX)
                    val prefsName = when {
                        metadata && rel == "settings.json" -> "kime_settings"
                        metadata && rel.startsWith("plugin_configs/") && rel.endsWith(".json") -> {
                            val id = rel.removePrefix("plugin_configs/").removeSuffix(".json")
                            require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "..") {
                                "插件配置标识无效"
                            }
                            "plugin_cfg_$id"
                        }
                        metadata && rel.startsWith("prefs/") && rel.endsWith(".json") ->
                            rel.removePrefix("prefs/").removeSuffix(".json")
                                .takeIf { it in BackupManager.SETTINGS_PREFS || it in BackupManager.PARTIAL_PREFS }
                        else -> null
                    }
                    if (prefsName != null) {
                        val data = input.readBytes()
                        preferences.validate(data)
                        // 主设置的值里以后的版本换了名字的，换回这一版的
                        settings[prefsName] = if (metadata && rel == "settings.json") NewerBackupNames.incomingSettings(data) else data
                    } else {
                        // 以后的版本写的键盘配置文件（新文件名）落到这一版的名字上
                        val newerCustom = !metadata && name == NewerBackupNames.KEYBOARD_CUSTOM_FILE
                        val destination = when {
                            !metadata -> target(File(filesDir, "rime"), if (newerCustom) KeysConfigHelper.XIME_CUSTOM_CONFIG_FILE else name)
                            rel == "plugins.xml" -> File(filesDir, "plugins.xml")
                            rel.startsWith("plugins/") -> target(File(filesDir, "plugins"), rel.removePrefix("plugins/"))
                            rel.startsWith("files/") -> rel.removePrefix("files/").let { path ->
                                require(BackupManager.isFilesEntry(path, personal = true)) { "备份包包含未知文件: $path" }
                                target(filesDir, path)
                            }
                            rel.startsWith("cells/") -> rel.removePrefix("cells/").let { file ->
                                require(BackupManager.isCellEntry(file)) { "备份包包含未知文件: $file" }
                                target(File(filesDir, BackupManager.CELLS_DIR), file)
                            }
                            else -> null
                        }
                        if (destination != null) {
                            val clash = changes.indexOfFirst { it.first == destination }
                            // 键盘配置新旧两个名字都在：这一版读的是自己名字的那个，留它，新名字的那个不收。
                            require(clash < 0 || newerCustom || destination in newerCustomTargets) { "备份包目标重复" }
                            if (clash < 0 || !newerCustom) {
                                if (clash >= 0) changes.removeAt(clash)
                                if (newerCustom) newerCustomTargets += destination
                                val staged = File(staging, "new-$count")
                                staged.outputStream().use { input.copyTo(it) }
                                changes += destination to staged
                            }
                        }
                    }
                    count++
                    input.closeEntry()
                }
            }
            require(count > 0) { "备份包为空" }
            settings.keys.forEach { oldSettings[it] = preferences.snapshot(it) }
            changes.forEachIndexed { index, (destination, _) ->
                require(!destination.exists() || destination.isFile) { "恢复目标不是文件: ${destination.name}" }
                oldFiles[destination] = if (destination.exists()) File(staging, "old-$index").also {
                    destination.copyTo(it)
                } else null
            }
            // 备份里带了的用户词库（leveldb 目录）整个换成备份里的：本机多出来的 .log/.ldb/MANIFEST 不删的话，
            // leveldb 打开时会把多出来的 .log 重放回去，恢复后的词库又带上备份之后学的词（11-04 平板实测）。
            val rimeDir = File(filesDir, "rime").canonicalFile
            val restored = changes.map { it.first }.toSet()
            val strays = changes.map { it.first.parentFile }
                .filter { it.name.endsWith(".userdb") && it.parentFile?.canonicalFile == rimeDir }
                .distinct()
                .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it !in restored && it.name !in LEVELDB_RUNTIME } }
            val removed = strays.mapIndexed { index, file -> file to File(staging, "stray-$index").also { file.copyTo(it) } }
            try {
                changes.forEach { (destination, staged) ->
                    check(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "无法创建恢复目录" }
                    staged.copyTo(destination, overwrite = true)
                }
                strays.forEach { check(!it.exists() || it.delete()) { "无法清理旧词库文件: ${it.name}" } }
                settings.forEach { (name, data) -> preferences.restore(name, data) }
            } catch (failure: Exception) {
                oldFiles.entries.toList().asReversed().forEach { (destination, old) ->
                    try {
                        if (old == null) { if (destination.isFile) check(destination.delete()) }
                        else old.copyTo(destination, overwrite = true)
                    } catch (rollback: Exception) { failure.addSuppressed(rollback); preserveRecovery = true }
                }
                removed.forEach { (file, copy) ->
                    try { if (!file.exists()) copy.copyTo(file) }
                    catch (rollback: Exception) { failure.addSuppressed(rollback); preserveRecovery = true }
                }
                oldSettings.forEach { (name, data) ->
                    try { preferences.restore(name, data, replace = true) }
                    catch (rollback: Exception) { failure.addSuppressed(rollback); preserveRecovery = true }
                }
                throw failure
            }
            return Result.success(Unit)
        } catch (failure: Exception) {
            return Result.failure(failure)
        } finally {
            // Only this transaction's unique, app-private directory may be deleted.
            if (!preserveRecovery) staging.deleteRecursively()
        }
    }

    /** leveldb 的锁和运行日志不是数据，备份里也不带（见 [BackupManager.isRimePersonalFile]），留着不动。 */
    private val LEVELDB_RUNTIME = setOf("LOCK", "LOG", "LOG.old")
}
