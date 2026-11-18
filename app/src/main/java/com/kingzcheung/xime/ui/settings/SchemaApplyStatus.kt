package com.kingzcheung.xime.ui.settings

/**
 * 「已安装的方案」里每个开着的方案那一行的应用结果（11-15 用户：部署完不知道生效没有，失败也只弹一个含糊的框）。
 * [deployed] 是编译出的方案列表（build/default.yaml），[problems] 是上次部署没生效的方案和原因。
 */
internal sealed class SchemaApplyStatus(val text: String, val isError: Boolean = false) {
    object Applying : SchemaApplyStatus("正在应用…")
    object Applied : SchemaApplyStatus("已生效")
    object Pending : SchemaApplyStatus("还没生效，稍等自动应用")
    class Failed(reason: String) : SchemaApplyStatus("没生效：$reason", isError = true)

    companion object {
        /** 关着的方案、内置英文不显示。正在应用时，还没进列表的方案显示「正在应用」，已生效的保持「已生效」。 */
        fun of(id: String, enabled: Boolean, applying: Boolean, deployed: Set<String>?, problems: Map<String, String>): SchemaApplyStatus? {
            if (!enabled) return null
            val inList = deployed?.contains(id) == true
            return when {
                applying && !inList -> Applying
                !applying && problems[id] != null -> Failed(problems.getValue(id))
                inList -> Applied
                applying -> Applying
                else -> Pending
            }
        }
    }
}
