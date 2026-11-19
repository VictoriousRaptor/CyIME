package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.DefaultModelInstaller
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 轻量版的语音引擎（[SherpaLibrary]）跟着语音模型走：语音模型、声纹模型下载好时，引擎还没有就一起下（约 8 MB）。
 * 轻量版本来就要先下载语音模型才能用语音，所以用的人只是多等这一点；不用语音的人不下。
 * 完整版、离线包安装包里就有，不下载，模型管理里也不列出（[com.kingzcheung.xime.model.packagedPacks]）。
 */
object SpeechEnginePack {
    private const val KEY_MIGRATED = "speech_engine_migrated"

    /** 这台手机还缺语音引擎（安装包里没有、也没下载好）。 */
    fun missing(context: Context): Boolean =
        !SherpaLibrary.packaged(context) && ModelManager.getModel(BuiltinModelCatalog.SPEECH_ENGINE) != null &&
            !ModelManager.isModelDownloaded(context, BuiltinModelCatalog.SPEECH_ENGINE)

    /**
     * 排进默认模型队列（没网、失败时联网后和下次启动自动再试；在模型管理里删掉就不再自动补），并马上开始下：
     * 走到这里说明语音模型已经下了，要用语音。
     */
    fun request(context: Context) {
        val app = context.applicationContext ?: context
        if (!missing(app)) return
        val engine = ModelManager.getModel(BuiltinModelCatalog.SPEECH_ENGINE) ?: return
        DefaultModelInstaller.requestGuided(app, setOf(engine.id), allowMetered = false)
        ModelManager.downloadModelInBackground(app, engine)
    }

    /** 模型下载装好以后（ModelManager 调用）：是语音或声纹模型就把引擎也补上。 */
    internal fun afterInstall(context: Context, model: ModelInfo) {
        if (model.category == ModelCategory.ASR || model.category == ModelCategory.SPEAKER) request(context)
    }

    /**
     * 升级到不带引擎的轻量版时只做一次：已经下过语音模型的人马上补下引擎，语音照旧能用（下好之前点麦克风会提示在下载）。
     * 没下过语音模型的不下。
     */
    fun migrateOnce(context: Context) {
        val app = context.applicationContext ?: context
        // 安装包里自带时不记「做过」：以后换成轻量版还要补。
        if (SherpaLibrary.packaged(app)) return
        val prefs = SettingsPreferences.getPrefsPublic(app)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        val hasVoice = (ModelManager.getModelsByCategory(ModelCategory.ASR) + ModelManager.getModelsByCategory(ModelCategory.SPEAKER))
            .any { ModelManager.isModelDownloaded(app, it) }
        if (hasVoice) request(app)
    }
}
