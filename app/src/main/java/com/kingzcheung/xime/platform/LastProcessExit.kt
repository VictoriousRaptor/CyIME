package com.kingzcheung.xime.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.kingzcheung.xime.util.FileLogger

/**
 * 主进程每次启动时，把上次为什么结束写一行进日志（公开版也写）：被划掉、被系统清理、内存不够、崩溃还是自己退出。
 *
 * 「键盘要重新唤醒」「又要等加载」这类反馈，以前只能猜是不是被系统杀了。ColorOS 划卡、OriginOS / HyperOS 清后台
 * 都不会通知应用，现在日志里有系统自己记的原因，照着它说话，不再瞎猜。
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object LastProcessExit {
    private const val TAG = "LastProcessExit"

    fun log(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val last = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrNull()
            ?.firstOrNull { it.processName == context.packageName } ?: return
        FileLogger.w(TAG, describe(last.reason, last.description, last.importance, System.currentTimeMillis() - last.timestamp))
    }

    /** 纯函数，可单测。[agoMs] 是离现在多久。 */
    internal fun describe(reason: Int, description: String?, importance: Int, agoMs: Long): String {
        val why = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "自己退出"
            ApplicationExitInfo.REASON_SIGNALED -> "被信号结束"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不够被系统收回"
            ApplicationExitInfo.REASON_CRASH -> "崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生代码崩溃"
            ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限被改"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "系统认为耗电或占资源太多"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "用户操作停掉（划掉卡片、强行停止或一键清理）"
            ApplicationExitInfo.REASON_USER_STOPPED -> "所在用户被停用"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程结束"
            ApplicationExitInfo.REASON_OTHER -> "系统其他原因"
            else -> "原因 $reason"
        }
        val minutes = (agoMs / 60_000L).coerceAtLeast(0L)
        return "上次进程结束：$why | reason=$reason | 系统说明：${description.orEmpty().ifEmpty { "无" }} | " +
            "当时重要程度=$importance | ${minutes} 分钟前"
    }
}
