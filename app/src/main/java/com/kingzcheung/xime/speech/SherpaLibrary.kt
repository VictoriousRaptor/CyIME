package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import java.io.File

/**
 * 语音引擎 libsherpa-onnx-jni.so 从哪加载。完整版、离线包在安装包里；轻量版（1.16 起）不带（安装包小约 8.6 MB），
 * 和语音模型一起下载（模型目录 [BuiltinModelCatalog.SPEECH_ENGINE]，files/models/speech-engine，[SpeechEnginePack]）。
 *
 * sherpa-onnx 的类在静态初始化里 System.loadLibrary("sherpa-onnx-jni")，那样只找安装包的库目录。构建时
 * （app/build-logic/tasks-speech.gradle.kts 的 redirectSherpaLoads）把这些调用改成了这里的 [loadLibrary]：
 * 安装包里有就照旧按名字加载，没有就加载下载的那份。下载的和安装包里的是同一个文件（同一个 sherpa-onnx 1.13.8 AAR）。
 */
object SherpaLibrary {
    const val LIBRARY = "libsherpa-onnx-jni.so"
    /** 轻量版语音引擎还没下载好时，点麦克风、开字幕看到的话。 */
    const val MISSING = "语音引擎还没下载好（约 8 MB）：连上网后会自动下载，也可以在 扩展 › 模型 › 其他 里下载「语音引擎」"
    private const val NAME = "sherpa-onnx-jni"
    @Volatile private var app: Context? = null

    /** 语音进程启动时调用（AsrInferenceService），之后 sherpa 的类加载库时知道去哪找下载的那份。 */
    fun attach(context: Context) { app = context.applicationContext ?: context }

    fun packaged(context: Context): Boolean =
        context.applicationInfo.nativeLibraryDir?.let { File(it, LIBRARY).isFile } == true

    /** 下载好的那份（下载时已按哈希校验）；大小和模型目录里写的一致才算，下到一半的不算。 */
    fun downloaded(context: Context): File? {
        val expected = ModelManager.getModel(BuiltinModelCatalog.SPEECH_ENGINE)?.resolvedVersion()
            ?.files?.firstOrNull { it.name == LIBRARY }?.sizeBytes ?: return null
        val dir = ModelStorage.getModelDir(context, BuiltinModelCatalog.SPEECH_ENGINE)
        if (!dir.isDirectory) return null
        return dir.walkTopDown().firstOrNull { it.isFile && it.name == LIBRARY && it.length() == expected }
    }

    /** 不加载，只看能不能用。 */
    fun available(context: Context): Boolean = packaged(context) || downloaded(context) != null

    /** 代替 sherpa-onnx 类里的 System.loadLibrary（构建时改写的调用，签名不能改）。 */
    @JvmStatic
    fun loadLibrary(name: String) {
        val context = app
        if (name != NAME || context == null || packaged(context)) {
            System.loadLibrary(name)
            return
        }
        val file = downloaded(context)
            ?: throw UnsatisfiedLinkError(MISSING)
        // 下载的代码只读（Android 14 起动态加载的文件要求只读）。
        file.setWritable(false, false)
        System.load(file.absolutePath)
    }
}
