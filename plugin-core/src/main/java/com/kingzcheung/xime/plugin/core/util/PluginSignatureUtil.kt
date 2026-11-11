package com.kingzcheung.xime.plugin.core.util

import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.TrustLevel

/**
 * 插件信任等级判定。
 *
 * Lua 脚本插件无 APK 签名，信任由来源判定：
 * 内置（ASSET / SYSTEM）和自家插件市场（REMOTE：按自家目录里的 sha256 校验过才装）视为官方（TRUSTED），
 * 用户自己导入的文件（FILE）视为第三方（THIRD_PARTY）。
 */
object PluginSignatureUtil {

    /**
     * Lua 脚本插件信任判定：按插件来源分类，
     * 内置插件和插件市场装的标记为官方（11-09：AI 回复、翻译等自家插件不该显示「第三方」），
     * 用户导入的插件标记为第三方。
     */
    fun classifyLuaPlugin(source: PluginSource): TrustLevel {
        return when (source) {
            PluginSource.SYSTEM, PluginSource.ASSET, PluginSource.REMOTE -> TrustLevel.TRUSTED
            PluginSource.FILE -> TrustLevel.THIRD_PARTY
        }
    }
}
