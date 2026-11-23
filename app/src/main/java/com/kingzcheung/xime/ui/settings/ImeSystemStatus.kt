package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * CyIME 在系统里启用了没有、是不是当前输入法。引导第一步、设置首页和预览键盘都按这里读。
 *
 * 只看 InputMethodManager.enabledInputMethodList 不够：红米 Turbo5（Android 17）上已启用、已设为默认、能打字，
 * 引导第一步仍说没启用，进不了设置（#508）。所以几样证据有一样就算：启用列表、ENABLED_INPUT_METHODS 设置、
 * 已经是当前输入法（只有启用了才能选），以及系统已经把输入法服务开起来了（系统只开启用的输入法）。
 */
internal fun isCyimeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val listed = runCatching { imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true }
        .getOrDefault(false)
    val setting = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
    }.getOrNull()
    return imeEnabledFrom(context.packageName, listed, setting, isCyimeSelected(context),
        com.kingzcheung.xime.service.ImeServiceLiveness.alive)
}

/** [setting] 是 ENABLED_INPUT_METHODS 的原文：「包名/服务;子类型:包名/服务…」。 */
internal fun imeEnabledFrom(packageName: String, listed: Boolean, setting: String?, selected: Boolean, serviceAlive: Boolean): Boolean =
    listed || selected || serviceAlive ||
        setting.orEmpty().split(':').any { it.substringBefore(';').substringBefore('/') == packageName }

/**
 * 当前输入法是不是 CyIME：DEFAULT_INPUT_METHOD 写着 CyIME，或者 Android 14 起系统报的当前输入法是 CyIME，有一样就算
 * （两样都只会在真是 CyIME 时说是）。两样都读不到时，看输入法服务开没开：系统只把当前输入法的服务开着（#508）。
 */
internal fun isCyimeSelected(context: Context): Boolean {
    val setting = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull().orEmpty()
    return imeSelectedFrom(context.packageName, setting, currentImePackage(context),
        com.kingzcheung.xime.service.ImeServiceLiveness.alive)
}

private fun currentImePackage(context: Context): String? =
    if (AndroidPlatform.currentInputMethod) runCatching {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.currentInputMethodInfo?.packageName
    }.getOrNull() else null

internal fun imeSelectedFrom(packageName: String, setting: String, currentPackage: String?, serviceAlive: Boolean = false): Boolean =
    (setting.isNotBlank() && setting.substringBefore('/') == packageName) ||  // 「包名/服务」；本地单测里 ComponentName 是空桩
        currentPackage == packageName ||
        (setting.isBlank() && currentPackage == null && serviceAlive)

/**
 * 引导判断用到的原始证据，写进日志：认不出来时导出日志就知道是哪样读不到（#508）。不含用户输入的内容。
 */
internal fun imeStatusReport(context: Context): String {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val listed = runCatching { imm?.enabledInputMethodList?.map { it.packageName } }.getOrElse { listOf("error:${it.javaClass.simpleName}") }
    fun secure(name: String) = runCatching { Settings.Secure.getString(context.contentResolver, name) }
        .getOrElse { "error:${it.javaClass.simpleName}" }
    return "sdk=${AndroidPlatform.sdk} ${AndroidPlatform.deviceLabel} listed=$listed " +
        "enabledSetting=${secure(Settings.Secure.ENABLED_INPUT_METHODS)} default=${secure(Settings.Secure.DEFAULT_INPUT_METHOD)} " +
        "current=${currentImePackage(context)} serviceAlive=${com.kingzcheung.xime.service.ImeServiceLiveness.alive}"
}
