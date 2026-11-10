import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Japanese conversion: azooKey (tools/azookey). Its dictionary comes from the pinned upstream commit, with
// the Mozc nouns it lacks merged into louds/, and is bundled in each APK as one zip; JapaneseSchemas
// extracts it on the phone.
val azooKeyDictionaryCommit = "4d418525b090cf49c219819d05a7e3cc2a4346eb"
// SHA-256 over the sorted lines "<path>\0<sha256 of file>\n" of the Dictionary/ files (3382 files, 25,315,483 bytes).
val azooKeyDictionaryContentHash = "72336c1a22b96fc7e06ae710e84dd3b9223799fe7fea213b7617fa3f67ea6dd9"
// louds/ files with the Mozc nouns azooKey lacks (names such as 暁山, places, organisations; ~400k words),
// built by tools/azookey/build-dictionary.sh. They replace every upstream louds/ file except charID.chid.
// Found like the libraries below (local copy by SHA-256 first, else downloaded).
// CI can build the same dictionary from its pinned sources; ZIP bytes may differ by zlib version.
// The merged dictionary content hash below remains mandatory for locally built archives too.
val azooKeyLoudsZipHash = providers.gradleProperty("cyime.azookeyLoudsZipSha256").orNull
    ?: "ebbb69a26d958a6d3498d1422f6c1315530a08ee730877cfdc33d0248c20e510"
// Content hash (same scheme) of the merged dictionary: dictionary.version, checked again on the phone.
val azooKeyMergedDictionaryHash = "7776faa0dc60bea48b2e24824d6e45924bed3a85a0b8263d7d4859d39f9a9745"
// Own asset root holding only azookey/: the old generated/japanese-assets root kept the jaroomaji
// dictionaries (rime_japanese/, ~89 MB) from earlier builds, which would otherwise still be packaged.
val japaneseAssetRoot = layout.buildDirectory.dir("generated/japanese-azookey-assets/azookey")
val retiredJapaneseAssetRoot = layout.buildDirectory.dir("generated/japanese-assets")
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun download(url: String, target: File, what: String) {
    check(!gradle.startParameter.isOffline) { "Missing $what; run the build once online." }
    logger.lifecycle("Downloading $what: $url")
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 30_000
    connection.readTimeout = 600_000
    try {
        check(connection.responseCode == 200) { "$what HTTP ${connection.responseCode}: $url" }
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
    } finally {
        connection.disconnect()
    }
}
val prepareJapaneseDictionaries by tasks.registering {
    group = "build setup"
    description = "Fetch and verify the pinned azooKey dictionary for offline APK assets"
    inputs.property("commit", azooKeyDictionaryCommit)
    inputs.property("contentHash", azooKeyDictionaryContentHash)
    inputs.property("loudsZip", azooKeyLoudsZipHash)
    val targetRoot = japaneseAssetRoot.get().asFile
    val zip = File(targetRoot, "dictionary.zip")
    val version = File(targetRoot, "dictionary.version")
    outputs.files(zip, version)
    outputs.upToDateWhen { zip.isFile && version.isFile && version.readText() == azooKeyMergedDictionaryHash }
    val retiredRoot = retiredJapaneseAssetRoot.get().asFile
    val loudsZipName = "azookey-louds-mozc-${azooKeyLoudsZipHash.take(8)}.zip"
    val localDirs = listOfNotNull(providers.gradleProperty("cyime.azookeyLibDir").orNull,
        providers.environmentVariable("CYIME_AZOOKEY_LIB_DIR").orNull).map { File(it) } +
        rootProject.file(".codex-artifacts/azookey-1102/libs")
    doLast {
        retiredRoot.deleteRecursively()
        if (zip.isFile && version.isFile && version.readText() == azooKeyMergedDictionaryHash) return@doLast
        targetRoot.mkdirs()
        val archive = File(targetRoot, "upstream.zip.download")
        try {
            download("https://github.com/ensan-hcl/azooKey_dictionary_storage/archive/$azooKeyDictionaryCommit.zip",
                archive, "pinned azooKey dictionary")
            // GitHub archives are not byte-stable, so the files themselves are checked.
            val files = sortedMapOf<String, ByteArray>()
            ZipInputStream(archive.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val path = entry.name.substringAfter('/')
                    if (entry.isDirectory || !path.startsWith("Dictionary/")) continue
                    val relative = path.removePrefix("Dictionary/")
                    if (relative.substringAfterLast('/').startsWith(".")) continue
                    files[relative] = input.readBytes()
                }
            }
            val manifest = files.entries.joinToString("") { (path, bytes) -> "$path\u0000${sha256(bytes)}\n" }
            check(sha256(manifest.toByteArray()) == azooKeyDictionaryContentHash) { "azooKey dictionary content mismatch" }
            val louds = localDirs.map { File(it, loudsZipName) }.firstOrNull { it.isFile && sha256(it) == azooKeyLoudsZipHash }
                ?: File(targetRoot, "$loudsZipName.download").also { pending ->
                    val urls = listOf("https://github.com/Cyletix/CyIME/releases/download/models/$loudsZipName",
                        "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/$loudsZipName")
                    val fetched = urls.any { url ->
                        runCatching { download(url, pending, "azooKey dictionary with Mozc words") }.isSuccess &&
                            sha256(pending) == azooKeyLoudsZipHash
                    }
                    check(fetched) { "$loudsZipName unavailable or SHA-256 mismatch" }
                }
            try {
                files.keys.removeAll { it.startsWith("louds/") && it != "louds/charID.chid" }
                ZipInputStream(louds.inputStream().buffered()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.startsWith("louds/")) files[entry.name] = input.readBytes()
                    }
                }
            } finally {
                if (louds.name.endsWith(".download")) louds.delete()
            }
            val merged = files.entries.joinToString("") { (path, bytes) -> "$path\u0000${sha256(bytes)}\n" }
            check(sha256(merged.toByteArray()) == azooKeyMergedDictionaryHash) { "merged azooKey dictionary mismatch" }
            val pending = File(targetRoot, "dictionary.zip.pending")
            ZipOutputStream(pending.outputStream().buffered()).use { output ->
                output.setLevel(9)
                files.forEach { (path, bytes) ->
                    output.putNextEntry(ZipEntry(path).apply { time = 0L })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            java.nio.file.Files.move(pending.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            version.writeText(azooKeyMergedDictionaryHash)
        } finally {
            archive.delete()
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareJapaneseDictionaries) }

// libCyAzooKey.so, built by tools/azookey/build-android.sh (Swift has no x86 32-bit Android target;
// there the translator finds no library and the romaji is shown unconverted).
// A local copy is used first when its SHA-256 matches: <dir>/<abi>/libCyAzooKey.so or <dir>/libCyAzooKey-<hash8>-<abi>.so,
// with <dir> from -Pcyime.azookeyLibDir, CYIME_AZOOKEY_LIB_DIR, or .codex-artifacts/azookey-1102/libs. Otherwise it is downloaded.
val azooKeyLibraryHashes = mapOf(
    "arm64-v8a" to "16907c1acf748d8b0b731106fcc65c07d70f72eb04d6a5430718b39604b53b50",
    "armeabi-v7a" to "01f5b76a443b22993de965aa3c4d0eeca67afb1b087451ca9b7bc585cd318f19",
    "x86_64" to "6589da4104294f49b430b1a65000b6e6a064841298bad6ef9303ebc3518287dc",
).filterKeys { abi ->
    providers.gradleProperty("cyime.nativeAbis").orNull?.split(',')?.contains(abi) ?: true
}.mapValues { (abi, hash) ->
    // Explicit checksums from this CI run's source builds; downloaded releases keep the defaults.
    providers.gradleProperty("cyime.azookeyLibrarySha256.$abi").orNull ?: hash
}
val azooKeyLibraryRoot = layout.buildDirectory.dir("generated/azookey-jniLibs")
val prepareAzooKeyLibraries by tasks.registering {
    group = "build setup"
    description = "Fetch and verify the prebuilt azooKey converter libraries"
    inputs.property("checksums", azooKeyLibraryHashes)
    val targetRoot = azooKeyLibraryRoot.get().asFile
    val libraries = azooKeyLibraryHashes.mapValues { (abi, _) -> File(targetRoot, "$abi/libCyAzooKey.so") }
    val localDirs = listOfNotNull(providers.gradleProperty("cyime.azookeyLibDir").orNull,
        providers.environmentVariable("CYIME_AZOOKEY_LIB_DIR").orNull).map { File(it) } +
        rootProject.file(".codex-artifacts/azookey-1102/libs")
    outputs.files(libraries.values)
    outputs.upToDateWhen { azooKeyLibraryHashes.all { (abi, hash) -> libraries.getValue(abi).let { it.isFile && sha256(it) == hash } } }
    doLast {
        azooKeyLibraryHashes.forEach { (abi, hash) ->
            val target = libraries.getValue(abi)
            if (target.isFile && sha256(target) == hash) return@forEach
            target.parentFile.mkdirs()
            val name = "libCyAzooKey-${hash.take(8)}-$abi.so"
            val local = localDirs.flatMap { listOf(File(it, "$abi/libCyAzooKey.so"), File(it, name)) }
                .firstOrNull { it.isFile && sha256(it) == hash }
            if (local != null) {
                logger.lifecycle("Using local azooKey library $abi: $local")
                local.copyTo(target, overwrite = true)
                return@forEach
            }
            val pending = File(target.parentFile, "$name.download")
            try {
                val urls = listOf("https://github.com/Cyletix/CyIME/releases/download/models/$name",
                    "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/$name")
                val fetched = urls.any { url ->
                    runCatching { download(url, pending, "azooKey library $abi") }.isSuccess &&
                        sha256(pending) == hash
                }
                check(fetched) { "azooKey library $abi unavailable or SHA-256 mismatch: $name" }
                java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                pending.delete()
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareAzooKeyLibraries) }
