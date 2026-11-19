package com.kingzcheung.xime.handwriting

import android.content.Context
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.ModelStorage
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * assets/handwriting 里几份大模板从哪读（1.16）：完整版、离线包在安装包里，和以前一样；轻量版不带，
 * 生僻字模板跟手写模型一起下载（[BuiltinModelCatalog.HANDWRITING_RARE]），日语手写模板装「日语」插件时下载
 * （[BuiltinModelCatalog.JAPANESE_HANDWRITING]）。下载的文件和安装包里的逐字节相同，读法不变。
 * 没下载时打开会抛 [FileNotFoundException]，调用处照旧当成「没有这份模板」：不出生僻字、没有内置假名模板。
 */
internal object HandwritingAssets {
    private val packs: Map<String, String> =
        listOf("hanzi_rare_och.bin", "hanzi_rare_och.json").associateWith { BuiltinModelCatalog.HANDWRITING_RARE } +
            listOf("kana_default_strokes.json", "kana_default_och.bin", "kana_default_och.json",
                "japanese_kanji_strokes.json", "japanese_kanji_och.bin", "japanese_kanji_och.json")
                .associateWith { BuiltinModelCatalog.JAPANESE_HANDWRITING }

    /** 安装包里有这份（带模型的安装包）。 */
    fun bundled(context: Context, name: String): Boolean =
        runCatching { context.assets.open("handwriting/$name").close(); true }.getOrDefault(false)

    /** [name] 是 assets/handwriting 下的文件名。安装包里有就读包里的，否则读下载的那份。 */
    fun open(context: Context, name: String): InputStream {
        try {
            return context.assets.open("handwriting/$name")
        } catch (missing: FileNotFoundException) {
            val pack = packs[name] ?: throw missing
            val file = File(ModelStorage.getModelDir(context, pack), name)
            if (!file.isFile) throw FileNotFoundException("handwriting/$name（还没下载 $pack）")
            return file.inputStream()
        }
    }

    fun readText(context: Context, name: String): String = open(context, name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    fun readBytes(context: Context, name: String): ByteArray = open(context, name).use { it.readBytes() }
}
