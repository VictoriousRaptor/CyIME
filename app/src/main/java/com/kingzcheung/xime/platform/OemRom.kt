package com.kingzcheung.xime.platform

/**
 * 厂商系统的名字和版本（ColorOS 12.1、OriginOS 5、HyperOS 2 …），日志开头写，厂商适配按版本分支时用。
 *
 * 同是安卓 12，ColorOS 11 和 ColorOS 12 的后台管理、设置页位置都不一样，只看 [DeviceVendor] 不够；
 * 可这几家谁也不在公开接口里报自己的系统版本，只能读各家自己起名的系统属性，名字还隔几代一改：
 * OPPO 从 ro.build.version.opporom 改成 ro.build.version.oplusrom，小米 HyperOS 还留着 ro.miui.ui.version.name
 * （值是 V816 这种，#332 的日志里就把 HyperOS 写成了「MIUI V816」），所以先认新名字再认旧名字。
 */
data class OemRom(val name: String, val version: String) {
    /** 「ColorOS 12.1」，读不出版本时只写名字。 */
    val label: String get() = if (version.isEmpty()) name else "$name $version"

    companion object {
        /**
         * 纯函数，可单测：[prop] 读一个系统属性（读不到给 null 或空串）。认不出返回 null，调用处当普通安卓。
         * 版本里的前缀（V12.1、OS2.0.1、EmotionUI_14.0）去掉，只留数字部分。
         */
        fun detect(vendor: DeviceVendor, prop: (String) -> String?): OemRom? {
            fun read(key: String) = prop(key)?.trim().orEmpty()
            return when (vendor) {
                DeviceVendor.OPPO -> {
                    // 一加海外版叫 OxygenOS、realme 叫 realme UI，底子都是 ColorOS，版本号沿用 ColorOS 的。
                    val version = read("ro.build.version.oplusrom").ifEmpty { read("ro.build.version.opporom") }
                    val name = when {
                        read("ro.oxygen.version").isNotEmpty() -> "OxygenOS"
                        read("ro.build.version.realmeui").isNotEmpty() -> "realme UI"
                        else -> "ColorOS"
                    }
                    if (version.isEmpty() && name == "ColorOS") null else OemRom(name, versionNumber(version))
                }
                DeviceVendor.VIVO -> {
                    // ro.vivo.os.name 是 Funtouch 或 vivo（OriginOS 国内版写 vivo）；ro.vivo.os.version 是版本号。
                    val version = read("ro.vivo.os.version")
                    val osName = read("ro.vivo.os.name")
                    when {
                        version.isEmpty() && osName.isEmpty() -> null
                        osName.contains("funtouch", ignoreCase = true) -> OemRom("Funtouch OS", versionNumber(version))
                        else -> OemRom("OriginOS", versionNumber(version))
                    }
                }
                DeviceVendor.XIAOMI -> {
                    val hyper = read("ro.mi.os.version.name")
                    val miui = read("ro.miui.ui.version.name")
                    when {
                        hyper.isNotEmpty() -> OemRom("HyperOS", versionNumber(hyper))
                        // MIUI 写「V125」「V140」，不带点：12.5、14.0。
                        miui.isNotEmpty() -> OemRom("MIUI", versionNumber(miui).let {
                            if ('.' !in it && it.length == 3) "${it.dropLast(1)}.${it.last()}" else it })
                        else -> null
                    }
                }
                DeviceVendor.HUAWEI, DeviceVendor.HONOR -> {
                    val harmony = read("hw_sc.build.platform.version")
                    val magic = read("ro.build.version.magic")
                    val emui = read("ro.build.version.emui")
                    when {
                        harmony.isNotEmpty() -> OemRom("HarmonyOS", versionNumber(harmony))
                        magic.isNotEmpty() -> OemRom("MagicOS", versionNumber(magic))
                        emui.isNotEmpty() -> OemRom("EMUI", versionNumber(emui))
                        else -> null
                    }
                }
                DeviceVendor.SAMSUNG, DeviceVendor.OTHER -> null
            }
        }

        /** 「V12.1」「OS2.0.1.0.VNCCNXM」「EmotionUI_14.0.0」「V816」→ 去掉前面的字母，取开头的数字段。 */
        internal fun versionNumber(raw: String): String =
            Regex("""\d+(\.\d+)*""").find(raw)?.value.orEmpty()

        /** 大版本号（「12.1」→ 12），按版本分支用；读不出给 0。 */
        fun major(rom: OemRom?): Int = rom?.version?.substringBefore('.')?.toIntOrNull() ?: 0
    }
}
