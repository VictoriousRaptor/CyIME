package com.kingzcheung.xime.rime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kingzcheung.xime.platform.AndroidPlatform
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首次准备词库时让系统别停下它（11-20、11-22 两位用户卡在设置向导第 2 步「正在准备词库」）。
 *
 * 首次编译要把雾凇词库编成约 54 MB 的 rime_ice.table.bin，云端用 1.15.104 安装包里的词库实测单核 28 秒、
 * 内存峰值约 840 MB，手机上更慢。以前这一步只是应用进程里的一个后台线程：
 * - 向导第 1 步要用户切到系统设置去启用 CyIME，应用一进后台，Android 14 起会冻结缓存进程，
 *   小米等系统更早冻结；占着近 1 GB 内存的后台进程也最先被系统杀掉，回来后从头再编。
 * - 等「几分钟」时屏幕熄了，CPU 睡下，编译同样停住。
 * 所以准备只在用户一直盯着屏幕时才往前走。这个前台服务在向导准备期间开着（通知栏显示第几步），
 * 拿一把有时限的唤醒锁，进程不被冻结、不被先杀、熄屏也继续编。准备完成、向导不再等、或到了时限就自己停。
 * 它不自己做准备：准备照旧由应用启动时的预准备和向导完成（同一把锁），这里只看 [PreparationProgress]。
 */
class PreparationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(PreparationProgress.state.value),
                if (AndroidPlatform.foregroundServiceType) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // 系统不让开前台服务（应用已在后台等）：准备照常进行，只是没有这层保护。
            FileLogger.w(TAG, "Cannot start the preparation foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher?.isActive == true) return START_NOT_STICKY
        acquireWakeLock()
        FileLogger.w(TAG, "Keeping CyIME awake while the dictionary is prepared")
        watcher = scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            val updates = launch {
                PreparationProgress.state.collect { state ->
                    runCatching { manager?.notify(NOTIFICATION_ID, notification(state)) }
                    delay(1_000L)
                }
            }
            val done = withTimeoutOrNull(MAX_MS) { PreparationProgress.state.first { it.step == PreparationProgress.Step.DONE } }
            updates.cancel()
            finish(if (done != null) "preparation done" else "time limit ${MAX_MS / 60_000} min reached")
        }
        return START_NOT_STICKY
    }

    /** Android 15 起 dataSync 前台服务一天最多 6 小时，到点系统会叫这里；照做停掉，不然系统判应用出错。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish("system time limit")
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(why: String) {
        FileLogger.w(TAG, "Stop keeping CyIME awake: $why")
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CyIME:prepare-dictionary")
                .apply { setReferenceCounted(false); acquire(MAX_MS) }
        }.onFailure { FileLogger.w(TAG, "No wake lock: ${it.message}") }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun notification(state: PreparationProgress.State): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "准备词库", NotificationManager.IMPORTANCE_LOW).apply {
                description = "首次使用时准备词库，完成后通知自动消失"
                setShowBadge(false)
            })
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val (title, text) = PreparationProgress.notificationText(state)
        val fraction = PreparationProgress.fraction(state)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.kingzcheung.xime.R.drawable.cyime_mark_facet)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, (fraction * 100).toInt(), state.step == null)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    companion object {
        private const val TAG = "PreparationService"
        private const val CHANNEL = "dictionary_preparation"
        private const val NOTIFICATION_ID = 0x5e7a
        /** 首次编译手机上一般几分钟；给足 20 分钟，到点不管完没完都放掉唤醒锁，不让一次卡死耗光电。 */
        internal const val MAX_MS = 20 * 60_000L

        /** 向导在等准备时调用（界面在前台，系统允许开前台服务）；开不了就算了，准备照常进行。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PreparationService::class.java))
            } catch (e: Exception) {
                FileLogger.w(TAG, "Preparation service not started: ${e.message}")
            }
        }

        /** 向导不再等了（准备完成、出错、离开向导）。服务没开时什么也不做。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PreparationService::class.java)) }
        }
    }
}
