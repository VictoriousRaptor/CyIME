package com.kingzcheung.xime.rime

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 启停、导入、安装、卸载方案后自动应用（GitHub #340）：不再要用户自己去点「应用方案更改」。
 * 连着改几项只部署一次（停 [QUIET_MS] 毫秒没有新改动再开始）；部署走 [RimeConfigHelper.applySchemaChanges]，
 * 和输入法启动时一样按哈希增量编译，部署完通知输入法按新的方案列表重新选方案。
 * 不挂在设置页上：页面关了、换了页照样做完。
 */
object SchemaChangeApplier {
    private const val TAG = "SchemaChangeApplier"
    private const val QUIET_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Context>(Channel.CONFLATED)
    private val requested = java.util.concurrent.atomic.AtomicLong()

    private val _applying = MutableStateFlow(false)
    /** 有改动等着应用或正在部署：设置页显示「正在应用」。 */
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _finished = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** 每应用完一次发一次：true 表示部署结果能用（个别方案编译失败的说明另见 [RimeConfigHelper.deploymentProblem]）。 */
    val finished: SharedFlow<Boolean> = _finished.asSharedFlow()

    init {
        scope.launch {
            for (first in requests) {
                var context = first
                var generation = requested.get()
                // 等用户停手：这段时间里来的新请求合并成一次。
                while (true) {
                    delay(QUIET_MS)
                    generation = requested.get()
                    context = requests.tryReceive().getOrNull() ?: break
                }
                val usable = try {
                    RimeConfigHelper.applySchemaChanges(context)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Errors too (a native library that will not load, a class that failed to initialize): this loop
                    // runs for the whole process with no handler, so one escaping would end every later apply and
                    // crash the app. In JVM tests it also leaked into whichever test ran next.
                    FileLogger.e(TAG, "Applying schema changes failed", error)
                    RimeConfigHelper.recordFailure(context, error)
                    false
                }
                // 部署期间又有改动：接着再应用一次，「正在应用」不中断。
                if (requested.get() == generation) _applying.value = false
                _finished.tryEmit(usable)
            }
        }
    }

    fun request(context: Context) {
        _applying.value = true
        requested.incrementAndGet()
        requests.trySend(context.applicationContext)
    }
}
