import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/**
 * sherpa-onnx 的类在静态初始化里 System.loadLibrary("sherpa-onnx-jni")。安卓只在安装包的库目录里找，
 * 下载来的库这样加载不了，所以把 classes.jar 里每个 System.loadLibrary(String) 调用改成
 * com.kingzcheung.xime.speech.SherpaLibrary.loadLibrary(String)（参数和返回一样，只换类）：
 * 常量池末尾加一个类名和一个类常量，把这些方法引用的类指过去，别的字节不动。
 */
fun redirectSherpaLoads(jar: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ZipInputStream(jar.inputStream()).use { input ->
        ZipOutputStream(out).use { output ->
            var entry = input.nextEntry
            var changed = 0
            while (entry != null) {
                val bytes = input.readBytes()
                val rewritten = if (entry.name.endsWith(".class")) redirectLoadLibrary(bytes) else bytes
                if (rewritten !== bytes) changed++
                output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                output.write(rewritten); output.closeEntry()
                entry = input.nextEntry
            }
            check(changed > 0) { "sherpa-onnx classes.jar: no System.loadLibrary call found to redirect" }
        }
    }
    return out.toByteArray()
}

fun redirectLoadLibrary(b: ByteArray): ByteArray {
    fun u2(at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
    if (b.size < 10 || u2(0) != 0xCAFE || u2(2) != 0xBABE) return b
    val count = u2(8)
    val tag = IntArray(count); val first = IntArray(count); val second = IntArray(count); val offset = IntArray(count)
    val utf = arrayOfNulls<String>(count)
    var pos = 10
    var i = 1
    while (i < count) {
        val t = b[pos].toInt() and 0xff
        tag[i] = t; offset[i] = pos; pos++
        when (t) {
            1 -> { val len = u2(pos); utf[i] = java.io.DataInputStream(java.io.ByteArrayInputStream(b, pos, len + 2)).readUTF(); pos += 2 + len }
            3, 4 -> pos += 4
            5, 6 -> { pos += 8; i++ }
            7, 8, 16, 19, 20 -> { first[i] = u2(pos); pos += 2 }
            9, 10, 11, 12, 17, 18 -> { first[i] = u2(pos); second[i] = u2(pos + 2); pos += 4 }
            15 -> pos += 3
            else -> error("unknown constant pool tag $t")
        }
        i++
    }
    val hits = (1 until count).filter { index ->
        if (tag[index] != 10) return@filter false
        val owner = first[index]; val nameAndType = second[index]
        tag[owner] == 7 && utf[first[owner]] == "java/lang/System" && tag[nameAndType] == 12 &&
            utf[first[nameAndType]] == "loadLibrary" && utf[second[nameAndType]] == "(Ljava/lang/String;)V"
    }
    if (hits.isEmpty()) return b
    val name = "com/kingzcheung/xime/speech/SherpaLibrary".toByteArray(Charsets.UTF_8)
    val pool = b.copyOfRange(10, pos)
    for (index in hits) {
        val at = offset[index] - 10 + 1
        pool[at] = ((count + 1) shr 8).toByte(); pool[at + 1] = ((count + 1) and 0xff).toByte()
    }
    val out = java.io.ByteArrayOutputStream(b.size + name.size + 8)
    out.write(b, 0, 8)
    out.write((count + 2) shr 8); out.write((count + 2) and 0xff)
    out.write(pool)
    out.write(1); out.write(name.size shr 8); out.write(name.size and 0xff); out.write(name)
    out.write(7); out.write(count shr 8); out.write(count and 0xff)
    out.write(b, pos, b.size - pos)
    return out.toByteArray()
}

// Match ASRInput's runtime. Static ORT avoids replacing the IME/handwriting runtime.
// 文件名带 redirect：以前生成的 AAR（没改加载调用）不会被当成最新的直接沿用。
val speechAar = layout.buildDirectory.file("speech-runtime/sherpa-onnx-1.13.8-redirect.aar")
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
                    if (entry.name == "classes.jar") {
                        // 轻量版不带 libsherpa-onnx-jni.so，用到语音时下载（speech/SherpaLibrary.kt）：
                        // sherpa 各个类自己 System.loadLibrary，这里把这些调用改成 SherpaLibrary.loadLibrary。
                        output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                        output.write(redirectSherpaLoads(input.readBytes())); output.closeEntry()
                    } else if (entry.name != "jni/x86/libonnxruntime.so") {
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
