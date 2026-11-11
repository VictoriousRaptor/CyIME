import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

// sherpa-onnx 1.13.8, the build that links ONNX Runtime as a shared library. Its own copy of the runtime
// is dropped: the JNI library uses the onnxruntime 1.28 already in the APK (Rime, prediction, handwriting),
// which serves the same C API version 28 it was built against. The static-runtime build carried a second
// runtime inside libsherpa-onnx-jni.so (arm64 24 MB, about 9 MB in the APK; shared build 4.8 MB).
// x86 already worked this way before.
val speechAar = layout.buildDirectory.file("speech-runtime/sherpa-onnx-1.13.8-shared-ort.aar")
val speechAssets = layout.buildDirectory.dir("generated/speech-assets")
val prepareSpeechRuntime = tasks.register("prepareSpeechRuntime") {
    outputs.file(speechAar)
    outputs.dir(speechAssets)
    doLast {
        fun fetch(url: String, file: File, sha: String) {
            fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (file.isFile && digest() == sha) return
            file.parentFile.mkdirs()
            val conn = java.net.URL(url).openConnection().apply {
                connectTimeout = 30_000; readTimeout = 300_000
            }
            conn.getInputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            check(digest() == sha) { "Speech dependency checksum mismatch: ${file.name}" }
        }
        val source = layout.buildDirectory.file("speech-runtime/original-shared-ort.aar").get().asFile
        fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
            source, "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96")
        ZipInputStream(source.inputStream()).use { input ->
            ZipOutputStream(speechAar.get().asFile.outputStream()).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    // Keep only the JNI library: the runtime comes from the APK (see above), and the
                    // C/C++ API libraries are for native callers the app does not have.
                    val dropped = entry.name.startsWith("jni/") && entry.name.substringAfterLast('/') in
                        setOf("libonnxruntime.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so")
                    if (!dropped) {
                        output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                        input.copyTo(output); output.closeEntry()
                    }
                    entry = input.nextEntry
                }
            }
        }
        fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            File(speechAssets.get().asFile, "speech/silero_vad.onnx"),
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
    }
}
dependencies.add("implementation", files(speechAar).builtBy(prepareSpeechRuntime))
tasks.named("preBuild").configure { dependsOn(prepareSpeechRuntime) }
