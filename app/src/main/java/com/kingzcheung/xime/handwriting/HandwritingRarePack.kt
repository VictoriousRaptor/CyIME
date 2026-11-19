package com.kingzcheung.xime.handwriting

import android.content.Context
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.DefaultModelInstaller
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 轻量版的手写生僻字模板（1.16，约 3.5 MB 下载）跟着汉字手写模型走：手写模型下好时一起下。
 * 轻量版本来就要先下手写模型才能手写，不用手写的人不下。完整版、离线包安装包里就有（[HandwritingAssets]）。
 * 下好以后下次打开手写（手写进程重新加载模型）才出生僻字。
 */
object HandwritingRarePack {
    private const val KEY_MIGRATED = "handwriting_rare_migrated"
    private const val HANDWRITING_MODEL = "ochwpro"

    fun missing(context: Context): Boolean =
        !HandwritingAssets.bundled(context, "hanzi_rare_och.bin") &&
            ModelManager.getModel(BuiltinModelCatalog.HANDWRITING_RARE) != null &&
            !ModelManager.isModelDownloaded(context, BuiltinModelCatalog.HANDWRITING_RARE)

    /** 排进默认模型队列（没网、失败时以后自动再试；在模型管理里删掉就不再自动补），并马上开始下。 */
    fun request(context: Context) {
        val app = context.applicationContext ?: context
        if (!missing(app)) return
        val pack = ModelManager.getModel(BuiltinModelCatalog.HANDWRITING_RARE) ?: return
        DefaultModelInstaller.requestGuided(app, setOf(pack.id), allowMetered = false)
        ModelManager.downloadModelInBackground(app, pack)
    }

    /** 模型下载装好以后（ModelManager 调用）：是汉字手写模型就把生僻字模板也补上。 */
    internal fun afterInstall(context: Context, model: ModelInfo) {
        if (model.id == HANDWRITING_MODEL) request(context)
    }

    /** 升级到不带模板的轻量版时只做一次：已经下过手写模型的人马上补下，生僻字照旧能写出来。 */
    fun migrateOnce(context: Context) {
        val app = context.applicationContext ?: context
        // 安装包里自带时不记「做过」：以后换成轻量版还要补。
        if (HandwritingAssets.bundled(app, "hanzi_rare_och.bin")) return
        val prefs = SettingsPreferences.getPrefsPublic(app)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        val model = ModelManager.getModel(HANDWRITING_MODEL) ?: return
        if (ModelManager.isModelDownloaded(app, model)) request(app)
    }
}
