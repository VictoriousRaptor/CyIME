package com.kingzcheung.xime.ocr

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.ModelManager
import java.io.File

/**
 * 拍照识别处理图片用的 OpenCV（libopencv_java4.so）。完整版、离线包在安装包里；轻量版不带（安装包小约 9 MB），
 * 第一次拍照识别时和识别模型一起下载（模型目录 [BuiltinModelCatalog.OCR_ENGINE]，files/models/ocr-engine）。
 * OpenCV 的 Java 类自己不加载库（只有 OpenCVLoader 加载），这里代替 OpenCVLoader.initLocal()：
 * 安装包里有就照旧按名字加载，没有就加载下载的那份。库要的 libc++_shared.so 一直在安装包里。
 */
internal object OcrEngine {
    private const val TAG = "OcrEngine"
    private const val LIBRARY = "libopencv_java4.so"
    @Volatile private var loaded = false

    private fun packaged(context: Context): Boolean =
        context.applicationInfo.nativeLibraryDir?.let { File(it, LIBRARY).isFile } == true

    private fun downloaded(context: Context): File? {
        if (!ModelManager.isModelDownloaded(context, BuiltinModelCatalog.OCR_ENGINE)) return null
        val dir = ModelManager.getModelStorageDir(context, BuiltinModelCatalog.OCR_ENGINE) ?: return null
        return dir.walkTopDown().firstOrNull { it.isFile && it.name == LIBRARY }
    }

    /** 不加载、不联网，只看能不能用。 */
    fun available(context: Context): Boolean = packaged(context) || downloaded(context) != null

    /** 加载 OpenCV；轻量版还没下载时返回 false（该去下载）。 */
    @Synchronized
    fun load(context: Context): Boolean {
        if (loaded) return true
        loaded = try {
            if (packaged(context)) System.loadLibrary("opencv_java4")
            else {
                val file = downloaded(context) ?: return false
                // 下载的代码只读（Android 14 起动态加载的文件要求只读）。
                file.setWritable(false, false)
                System.load(file.absolutePath)
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "加载拍照识别引擎失败", error)
            false
        }
        return loaded
    }
}
