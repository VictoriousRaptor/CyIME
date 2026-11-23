package com.kingzcheung.xime.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「语言与方案」和键盘里的方案面板列同一份方案（用户 11-22：设置里有仓颉、注音，键盘面板里没有）。
 * 两边都从这里取，不各算各的：启用的方案，加上关着也列出的随包方案（[CyimeInputDefaults.listedWhenOff]，
 * 如五笔、仓颉、速成、笔画、注音、双拼），点了关着的才启用并部署。
 */
object SchemaPicking {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [installed] 是装着的方案（含关着的），[enabled] 是启用的方案 ID。 */
    fun offered(installed: List<SchemaInfo>, enabled: Collection<String>): List<SchemaInfo> {
        val installedIds = installed.map { it.schemaId }.toSet()
        val ids = CyimeInputDefaults.withCompanions(
            (enabled + CyimeInputDefaults.listedWhenOff).filter { it in installedIds }.distinct(), installedIds).toSet() + enabled
        return installed.filter { it.schemaId in ids }.distinctBy { it.schemaId }
    }

    /** 方案一行：同一语言的每种方案一张卡片，当前布局没有的方案用它自己的布局（与设置页同一个函数）。 */
    fun schemeRow(offeredOrdered: List<SchemaInfo>, current: SchemaInfo): List<SchemaInfo> =
        InputProfileSelection.schemeEntries(offeredOrdered, current)

    /** 启用关着的方案：组合（如小鹤 14 键）连同它需要的双拼和 14 键一起启用，并打开方案的语言。读写文件，在 IO 线程调用。 */
    fun enable(context: Context, schemaId: String) {
        val needs = CyimeInputDefaults.companionNeeds(schemaId) ?: listOf(schemaId)
        SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) + needs).distinct())
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
