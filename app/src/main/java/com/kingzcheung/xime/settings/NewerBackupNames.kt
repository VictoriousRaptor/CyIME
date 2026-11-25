package com.kingzcheung.xime.settings

import org.json.JSONObject

/**
 * 以后的版本做备份时换了名字的几样东西。恢复那样的备份包时都当成这一版自己的名字，
 * 新版本做的备份在这一版上照样认得出、恢复得了。只在读备份包、读键盘配置的地方用。
 */
object NewerBackupNames {
    /** 备份包里设置、文件这些条目开头的目录名。这一版的是 [BackupManager.META_PREFIX]，读备份包时当成它（[BackupManager.incomingEntry]）。 */
    const val META_PREFIX = "_cyime_backup/"

    /** rime/ 下用户自己写的键盘配置的文件名。恢复时落到这一版的文件名上；两个都在时留这一版的（[BackupRestoreTransaction]）。 */
    const val KEYBOARD_CUSTOM_FILE = "cyime.custom.yaml"

    /** 键盘配置里商店目录那一段的键名。只写了它、没写这一版的键名时照样认（[XimeConfig.storeIndex]）。 */
    const val STORE_INDEX_KEY = "store_index"

    /** 「英文」这个输入方式的内部名，存在设置的值里。这一版的是 [InputModes.ENGLISH]，恢复时换过来（[incomingSettings]）。 */
    const val ENGLISH_MODE_ID = "__cyime_english"

    private val newerEnglish = Regex("(?<![A-Za-z0-9_])" + Regex.escape(ENGLISH_MODE_ID) + "(?![A-Za-z0-9_])")

    /**
     * 备份里的主设置（settings.json，格式见 [BackupManager.prefsToJson]）：值里的 [ENGLISH_MODE_ID] 换成 [InputModes.ENGLISH]。
     * 它当值存在好几个设置里（输入方式的先后、各语言选着的条目、横竖屏各记的条目、现在用的方案……），所以不按键一个一个列，
     * 字符串值和字符串集合里整段是这个名字的都换（前后挨着字母、数字、下划线的不算）。键不动。
     * 没有可换的时候原样返回，一个字节都不变。
     */
    internal fun incomingSettings(json: ByteArray): ByteArray {
        val root = JSONObject(json.toString(Charsets.UTF_8))
        var changed = false
        fun adopt(value: String): String =
            newerEnglish.replace(value) { InputModes.ENGLISH }.also { if (it != value) changed = true }
        for (key in root.keys().asSequence().toList()) {
            val entry = root.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "s" -> entry.put("v", adopt(entry.getString("v")))
                "ss" -> entry.getJSONArray("v").let { values ->
                    for (i in 0 until values.length()) values.put(i, adopt(values.getString(i)))
                }
            }
        }
        return if (changed) root.toString().toByteArray(Charsets.UTF_8) else json
    }
}
