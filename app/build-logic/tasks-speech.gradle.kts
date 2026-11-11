import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

// Match ASRInput's runtime. Static ORT avoids replacing the IME/handwriting runtime.
val speechAar = layout.buildDirectory.file("speech-runtime/sherpa-onnx-1.13.8.aar")
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
        val source = layout.buildDirectory.file("speech-runtime/original.aar").get().asFile
        fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar",
            source, "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471")
        ZipInputStream(source.inputStream()).use { input ->
            ZipOutputStream(speechAar.get().asFile.outputStream()).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    // Upstream's x86 build alone still ships shared ORT. Use the existing,
                    // backward-compatible 1.28 runtime for this ABI; do not package two copies.
                    if (entry.name != "jni/x86/libonnxruntime.so") {
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
