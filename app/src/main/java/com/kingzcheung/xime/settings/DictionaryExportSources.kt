package com.kingzcheung.xime.settings

import android.content.Context
import androidx.annotation.WorkerThread
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import java.io.File

/** 「导出词库」能导出的词：当前方案学到的词（用户词库），或某个导入的词库。格式见 [DictionaryExport]。 */
object DictionaryExportSources {
    /** 当前输入方案的用户词库，次数多的在前。原件只导到缓存里读，读完就删。在 IO 线程调用。 */
    @WorkerThread
    suspend fun userWords(context: Context): List<DictEntry> {
        check(RimeConfigHelper.prepareEngine(context)) { "词库还没准备好" }
        val engine = RimeEngine.getInstance()
        check(engine.ensureSession(10_000L)) { "词库还没准备好" }
        val raw = File(context.cacheDir, "dict_export_userdb.txt")
        try {
            check(engine.exportUserDict(raw.path)) { "读不到用户词库" }
            return raw.useLines(Charsets.UTF_8) { DictionaryExport.fromUserdb(it) }
        } finally {
            raw.delete()
        }
    }

    /** 一个导入的词库（手机里存的 Rime 词典）。 */
    fun packWords(store: CellDictionaryStore, id: String): List<DictEntry> {
        val file = store.dictionaryFile(id)
        require(file.isFile) { "词库文件不见了，请重新导入" }
        return file.useLines(Charsets.UTF_8) { DictionaryExport.fromRimeDictionary(it) }
    }
}
