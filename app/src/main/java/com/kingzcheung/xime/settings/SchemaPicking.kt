package com.kingzcheung.xime.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「语言与方案」和键盘里的方案面板列同一份方案（用户 11-22：设置里有仓颉、注音，键盘面板里没有）。
 * 两边都从这里取，不各算各的：只列启用的方案（用户 11-23：关了小鹤双拼键盘里还显示；多出一堆用不到的方案，
 * 自己在用的五笔被挤到后面）。关着的方案在「方案管理」（已安装的方案）里打开。
 */
object SchemaPicking {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [installed] 是装着的方案（含关着的），[enabled] 是启用的方案 ID；只返回启用的（连同它们的组合）。 */
    fun offered(installed: List<SchemaInfo>, enabled: Collection<String>): List<SchemaInfo> {
        val installedIds = installed.map { it.schemaId }.toSet()
        val ids = CyimeInputDefaults.withCompanions(enabled.filter { it in installedIds }.distinct(), installedIds).toSet() + enabled
        return installed.filter { it.schemaId in ids }.distinctBy { it.schemaId }
    }

    /**
     * 方案一行：只列在当前布局上真能打字的方案（设置页和键盘面板同一个函数）。
     * 用户 11-23：九键下面列出五笔，点了打不了字。别的布局才有的方案（五笔、仓颉只在 26 键和自建字母布局，
     * 注音只在大千，笔画只在笔画键盘）从布局一行进去：点那个布局就换成它的方案。
     */
    fun schemeRow(offeredOrdered: List<SchemaInfo>, current: SchemaInfo): List<SchemaInfo> {
        val layoutId = current.profile.layout.id
        return offeredOrdered.filter { it.profile.mode == InputMode.KEYBOARD && it.profile.language == current.profile.language &&
            (it.schemaId == current.schemaId || InputProfileSelection.offers(it, layoutId, offeredOrdered)) }
            .distinctBy { it.schemaId }
    }

    /** 启用关着的方案：组合（如小鹤 14 键）连同它需要的双拼和 14 键一起启用，并打开方案的语言。读写文件，在 IO 线程调用。 */
    fun enable(context: Context, schemaId: String) {
        val needs = CyimeInputDefaults.companionNeeds(schemaId) ?: listOf(schemaId)
        val before = SchemaManager.getEnabledSchemas(context)
        InputModes.placeNewlyEnabledLast(context, SchemaManager.discoverSchemas(context).map { it.toSchemaInfo() }, before, needs)
        SchemaManager.setEnabledSchemas(context, (before + needs).distinct())
        LanguagePreferences.enableForSchema(context, schemaId)
    }

    /**
     * 键盘方案面板点了关着的方案：启用、部署，部署好存成当前方案并让开着的键盘换过去
     * （走设置页同一条路 [ImeSchemaRequests]，键盘等准备好再换）。面板已经关了也照样做完。
     */
    fun enableAndPick(context: Context, schemaId: String, language: InputLanguage, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext ?: context
        scope.launch {
            val ok = try {
                enable(app, schemaId)
                KeysConfigHelper.loadConfig(app)
                com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(app)
                com.kingzcheung.xime.rime.RimeConfigHelper.redeploy(app)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.kingzcheung.xime.util.FileLogger.e("SchemaPicking", "Enable $schemaId from keyboard failed", error)
                com.kingzcheung.xime.rime.RimeConfigHelper.recordFailure(app, error)
                false
            }
            if (ok) {
                SettingsPreferences.setCurrentSchema(app, schemaId, language)
                ImeSchemaRequests.pick(schemaId)
            }
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }
}
