package com.kingzcheung.xime.model

import android.content.Context
import java.io.File

/**
 * 这个进程实际跑的 ABI，按需下载的原生库（拍照识别引擎、日语转换引擎）按它选文件。
 * 不用 Build.SUPPORTED_ABIS 的第一个：64 位手机上装 32 位安装包时，进程是 32 位的，要 armeabi-v7a 的库。
 */
object NativeAbi {
    val current: String = of(System.getProperty("os.arch").orEmpty())

    /** os.arch → Android ABI 名。32 位 ARM 进程在 64 位内核上报 armv8l，和 armv7l 一样归到 armeabi-v7a。 */
    internal fun of(arch: String): String = when (arch.lowercase()) {
        "aarch64", "arm64" -> "arm64-v8a"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> "armeabi-v7a"
    }
}

/**
 * 带模型的安装包（完整版、离线包、朋友包）里本来就有的按需下载包：模型管理里不列出来，免得再下一份。
 * HandwritingAssets 是 internal，同一模块里可以用。
 * 轻量版里一个都没有，照常列出、能下载能删除。
 */
internal fun packagedPacks(context: Context): Set<String> = buildSet {
    val libraries = context.applicationInfo.nativeLibraryDir?.let(::File)
    if (libraries != null && File(libraries, "libopencv_java4.so").isFile) add(BuiltinModelCatalog.OCR_ENGINE)
    // 拍照识别模型也一样（11-23 他以为完整版没带拍照模型：模型市场里它还显示「下载」）。
    // 以前下载过一份的不藏，好让那份还能删；已安装页另有一行「内置」（ModelLocalScreen）。
    if (com.kingzcheung.xime.ocr.OcrModelFiles.bundled(context) &&
        !ModelManager.isModelDownloaded(context, BuiltinModelCatalog.OCR_MODEL)) add(BuiltinModelCatalog.OCR_MODEL)
    if (libraries != null && File(libraries, "libCyAzooKey.so").isFile) add(BuiltinModelCatalog.JAPANESE_ENGINE)
    if (com.kingzcheung.xime.settings.JapanesePack.dictionaryBundled(context)) add(BuiltinModelCatalog.JAPANESE_DICTIONARY)
    if (com.kingzcheung.xime.handwriting.HandwritingAssets.bundled(context, "kana_default_och.bin")) add(BuiltinModelCatalog.JAPANESE_HANDWRITING)
    if (com.kingzcheung.xime.handwriting.HandwritingAssets.bundled(context, "hanzi_rare_och.bin")) add(BuiltinModelCatalog.HANDWRITING_RARE)
}
