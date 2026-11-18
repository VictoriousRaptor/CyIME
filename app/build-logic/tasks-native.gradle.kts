import java.util.zip.ZipFile
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity

// The official Android AAR contains the native libraries and matching C/C++ headers.
val onnxRuntime = the<VersionCatalogsExtension>().named("libs").findLibrary("onnxruntime-android").get().get()
val onnxModule = onnxRuntime.module
val onnxAar = configurations.detachedConfiguration(
    dependencies.create("${onnxModule.group}:${onnxModule.name}:${onnxRuntime.versionConstraint.requiredVersion}@aar"),
).apply { isTransitive = false }

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/jni/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    val libraries = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").map { "$it/libonnxruntime.so" }
    val jniEntries = libraries.map { "jni/$it" }

    inputs.files(onnxAar).withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(cppDir)
    outputs.files(libraries.map { File(jniLibsDir, it) })

    doLast {
        val aar = onnxAar.singleFile
        ZipFile(aar).use { archive ->
            val requiredEntries = jniEntries + listOf("headers/onnxruntime_c_api.h", "headers/onnxruntime_cxx_api.h")
            for (entry in requiredEntries) {
                check((archive.getEntry(entry)?.size ?: 0) > 0) { "${aar.name} is missing $entry" }
            }
        }

        val archive = zipTree(aar)
        // Sync the dedicated CMake directory to remove headers left by older ONNX Runtime versions.
        sync {
            from(archive) {
                include("headers/**")
                eachFile { path = "include/${path.removePrefix("headers/")}" }
            }
            from(archive) {
                include(jniEntries)
                eachFile { path = "lib/${path.removePrefix("jni/")}" }
            }
            includeEmptyDirs = false
            into(cppDir)
        }
        copy {
            from(File(cppDir, "lib")) { include(libraries) }
            into(jniLibsDir)
        }
    }
}

val buildTrie by tasks.registering {
    val inputFile = file("src/main/assets/english.txt")
    val outputFile = file("src/main/assets/english_trie.bin")

    inputs.file(inputFile)
    outputs.file(outputFile)

    doLast {
        val words = inputFile.readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        println("Loaded ${words.size} words from ${inputFile.name}")

        val nodes = mutableListOf<MutableMap<Char, Int>>()
        val nodeWords = mutableListOf<String?>()
        val nodeFreqs = mutableListOf<Int>()
        nodes.add(mutableMapOf())
        nodeWords.add(null)
        nodeFreqs.add(0)

        fun getOrCreateChild(parentIndex: Int, char: Char): Int {
            val existing = nodes[parentIndex][char]
            if (existing != null) return existing

            val newIndex = nodes.size
            nodes.add(mutableMapOf())
            nodeWords.add(null)
            nodeFreqs.add(0)
            nodes[parentIndex][char] = newIndex
            return newIndex
        }

        words.forEachIndexed { lineNum, word ->
            var current = 0
            for (char in word) {
                current = getOrCreateChild(current, char)
            }
            if (nodeWords[current] == null) {
                nodeWords[current] = word
                nodeFreqs[current] = lineNum + 1
            }
        }

        println("Built trie with ${nodes.size} nodes")

        val buffer = java.nio.ByteBuffer.allocate(512 * 1024)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        buffer.put("TRIE".toByteArray())
        buffer.put(1)
        buffer.putInt(nodes.size)

        for (i in nodes.indices) {
            val children = nodes[i]
            buffer.put(children.size.toByte())
            for ((char, childIndex) in children) {
                buffer.put(char.code.toByte())
                buffer.putInt(childIndex)
            }

            val word = nodeWords[i]
            buffer.put(if (word != null) 1 else 0)
            if (word != null) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                buffer.put(bytes.size.toByte())
                buffer.put(bytes)
                buffer.putInt(nodeFreqs[i])
            }
        }

        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        outputFile.writeBytes(data)

        println("Wrote ${data.size} bytes (${data.size / 1024}KB) to ${outputFile.name}")
    }
}

// ── librime native 构建输入保障（修"改了 C++ 代码 Run 不重编"）──
// 1) librime-t9 权威源码在顶层 jni/librime-t9，编译副本 plugins/librime-t9 由
//    Rime.cmake 在 configure 时 file(COPY) 同步。Gradle 感知不到权威源码变化、
//    不重新 configure 时副本就是旧的。改为构建期同步（Copy 增量：源变才拷，
//    拷后副本 mtime 更新，ninja 据此重编对应文件）。
val syncT9Plugin by tasks.registering {
    val authoritative = file("src/main/jni/librime-t9")
    val mirror = file("src/main/jni/librime/plugins/librime-t9")
    inputs.dir(authoritative)
    outputs.dir(mirror)
    doLast {
        copy {
            from(authoritative)
            into(mirror)
            exclude("build_test/**")  // 本地单测构建产物，不入镜像
        }
    }
}

// 2) 把 librime 核心/插件源码目录声明为 native 构建任务的显式输入：
//    AGP 对 CMake 任务的 up-to-date 判断不覆盖 submodule 源文件，
//    缺失该输入时 Android Studio 的 Run 会直接跳过 ninja，
//    改动（尤其 librime/src 核心改动）不会进 APK。
//    声明后：源码一变任务必然执行，ninja 按依赖增量重编，仍然很快。
tasks.configureEach {
    if (name.startsWith("buildCMake")) {
        dependsOn(syncT9Plugin)
        inputs.dir("src/main/jni/librime/src")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional(true)
        inputs.dir("src/main/jni/librime-t9")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional(true)
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadOnnx)
    dependsOn(buildTrie)
}