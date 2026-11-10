import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

// libcytranslate.so (offline translation engine: slimt + tools/offline_translate/cytranslate.cc), built by
// tools/offline_translate/build-android.sh and bundled prebuilt like libCyAzooKey.so. Only arm64-v8a and x86_64:
// on other CPUs the library is absent and the translate plugin reports offline translation as unsupported.
// The models themselves are not bundled; they are downloaded from the market (translate-zh-en, translate-en-zh).
//
// A local copy is used first when its SHA-256 matches: <dir>/<abi>/libcytranslate.so or
// <dir>/libcytranslate-<hash8>-<abi>.so, with <dir> from -Pcyime.translateLibDir, CYIME_TRANSLATE_LIB_DIR or
// .codex-artifacts/offline-translate/libs. Otherwise it is downloaded from Release `models` / ModelScope.
// Empty map = no library yet: the APK builds without it (offline translation unsupported everywhere).
val offlineTranslateLibraryHashes = mapOf<String, String>(
    "arm64-v8a" to "fbc9172835c1d860f146685d1e77b79e11cc2e4f2aa6c027a9e8023c88a23f55",
    "x86_64" to "78f1b569ce0d59628a4482059dc4b8cb1714ae86bbb50ecf9833110ec3286ae4",
).filterKeys { abi ->
    providers.gradleProperty("cyime.nativeAbis").orNull?.split(',')?.contains(abi) ?: true
}.mapValues { (abi, hash) ->
    providers.gradleProperty("cyime.translateLibrarySha256.$abi").orNull ?: hash
}
val offlineTranslateLibraryRoot = layout.buildDirectory.dir("generated/translate-jniLibs")
fun offlineTranslateSha256(file: File): String {
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
fun offlineTranslateDownload(url: String, target: File, what: String) {
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
val prepareOfflineTranslateLibraries by tasks.registering {
    group = "build setup"
    description = "Fetch and verify the prebuilt offline translation libraries"
    inputs.property("checksums", offlineTranslateLibraryHashes)
    val targetRoot = offlineTranslateLibraryRoot.get().asFile
    val libraries = offlineTranslateLibraryHashes.mapValues { (abi, _) -> File(targetRoot, "$abi/libcytranslate.so") }
    val localDirs = listOfNotNull(providers.gradleProperty("cyime.translateLibDir").orNull,
        providers.environmentVariable("CYIME_TRANSLATE_LIB_DIR").orNull).map { File(it) } +
        rootProject.file(".codex-artifacts/offline-translate/libs")
    outputs.dir(targetRoot)
    outputs.upToDateWhen {
        offlineTranslateLibraryHashes.all { (abi, hash) -> libraries.getValue(abi).let { it.isFile && offlineTranslateSha256(it) == hash } }
    }
    doLast {
        // A library no longer listed (ABI dropped, map emptied) must not stay in the APK.
        targetRoot.listFiles()?.filter { it.isDirectory && it.name !in offlineTranslateLibraryHashes }?.forEach { it.deleteRecursively() }
        offlineTranslateLibraryHashes.forEach { (abi, hash) ->
            val target = libraries.getValue(abi)
            if (target.isFile && offlineTranslateSha256(target) == hash) return@forEach
            target.parentFile.mkdirs()
            val name = "libcytranslate-${hash.take(8)}-$abi.so"
            val local = localDirs.flatMap { listOf(File(it, "$abi/libcytranslate.so"), File(it, name)) }
                .firstOrNull { it.isFile && offlineTranslateSha256(it) == hash }
            if (local != null) {
                logger.lifecycle("Using local offline translation library $abi: $local")
                local.copyTo(target, overwrite = true)
                return@forEach
            }
            val pending = File(target.parentFile, "$name.download")
            try {
                val urls = listOf("https://github.com/Cyletix/CyIME/releases/download/models/$name",
                    "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/$name")
                val fetched = urls.any { url ->
                    runCatching { offlineTranslateDownload(url, pending, "offline translation library $abi") }.isSuccess &&
                        offlineTranslateSha256(pending) == hash
                }
                check(fetched) { "offline translation library $abi unavailable or SHA-256 mismatch: $name" }
                java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                pending.delete()
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareOfflineTranslateLibraries) }
