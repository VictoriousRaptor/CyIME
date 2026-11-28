package com.kingzcheung.xime.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.kingzcheung.xime.util.FileLogger

/**
 * 「防止被系统清理」：小米、vivo、OPPO、华为、荣耀这几家，自己在安卓之上加了一套后台管理（自启动、后台耗电、
 * 一键清理、划卡即停），输入法也照杀不误。被杀之后键盘要重新唤醒、要重新加载词库。
 *
 * 业内成熟做法（各家输入法、闹钟、运动 App 都这么干，dontkillmyapp.com 有整理）：检测到这几家，带用户去系统的
 * 自启动 / 后台管理页把应用放进白名单。这里也一样，只在这几家手机上显示，点了直接打开那一页。
 *
 * 再吐槽一次 OPPO 和 vivo：这些页面没有任何公开接口，包名、类名还隔一两个大版本就换一次
 * （com.coloros.safecenter → com.oplus.battery，com.iqoo.secure → com.vivo.permissionmanager），
 * 有的版本干脆不导出不让别人打开。所以下面的名单按新到旧挨个试，全都打不开就退回系统的「应用信息」页，
 * 页面上的文字告诉用户去哪儿点。名单来自社区整理和各家公开的适配说明，打不开不会出错，只是换下一个。
 */
object OemBackgroundGuide {
    private const val TAG = "OemBackgroundGuide"

    /**
     * 纯函数，可单测：设置页那一行下面的说明，按厂商和系统写去哪儿点。[rom] 读不出时按厂商的通用说法。
     * 三星、Pixel 等没有这类反馈的给 null，设置页就不显示这一行。
     */
    fun steps(vendor: DeviceVendor, rom: OemRom?): String? = when (vendor) {
        DeviceVendor.XIAOMI -> "允许自启动，省电策略选「无限制」"
        DeviceVendor.VIVO -> if (rom?.name == "Funtouch OS") "允许自启动，后台耗电管理里允许后台高耗电"
            else "允许自启动，后台耗电管理里选「允许后台高耗电」"
        // ColorOS 12 起自启动和耗电都并进了应用信息页；最近任务里下拉卡片能锁住，一键清理不再带走它。
        DeviceVendor.OPPO -> if (OemRom.major(rom) in 1..11) "允许自启动，耗电保护里允许后台运行"
            else "允许自启动，耗电管理里允许后台运行"
        DeviceVendor.HUAWEI, DeviceVendor.HONOR -> "启动管理里关掉自动管理，三项都打开"
        DeviceVendor.SAMSUNG, DeviceVendor.OTHER -> null
    }

    /** 纯函数，可单测：按顺序试的系统页面（包名 to 类名），新系统在前。 */
    fun candidates(vendor: DeviceVendor): List<Pair<String, String>> = when (vendor) {
        DeviceVendor.XIAOMI -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        DeviceVendor.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )
        DeviceVendor.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        DeviceVendor.HUAWEI -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        DeviceVendor.HONOR -> listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        DeviceVendor.SAMSUNG, DeviceVendor.OTHER -> emptyList()
    }

    /** 从设置页打开：挨个试 [candidates]，都不行打开本应用的「应用信息」页。返回打开的是哪一页，写进日志。 */
    fun open(context: Context, vendor: DeviceVendor = AndroidPlatform.vendor): String {
        for ((pkg, cls) in candidates(vendor)) {
            val intent = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 不先 resolveActivity：安卓 11 起看不到别人的包（包可见性），查不到不代表打不开。直接开，失败就下一个。
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            if (opened) {
                FileLogger.w(TAG, "opened $pkg/$cls")
                return "$pkg/$cls"
            }
        }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { FileLogger.w(TAG, "app details unavailable", it) }
        FileLogger.w(TAG, "vendor page unavailable, opened app details (${vendor.name.lowercase()})")
        return "app_details"
    }
}
