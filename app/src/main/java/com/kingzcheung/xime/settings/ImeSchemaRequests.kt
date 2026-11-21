package com.kingzcheung.xime.settings

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 设置页里给正在用的语言换了方案或布局：开着的键盘马上换过去，不等下次弹出（用户 11-20：
 * 「设置里切换 不能直接切换主界面吗」）。设置页和键盘在同一进程，这里只传话，不存盘；
 * 存盘照旧由 [SettingsPreferences.setCurrentSchema] 做，键盘没开时下次弹出照样用新的。
 */
object ImeSchemaRequests {
    private val requests = MutableSharedFlow<String>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val picked: SharedFlow<String> = requests.asSharedFlow()

    fun pick(schemaId: String) {
        if (schemaId.isNotBlank()) requests.tryEmit(schemaId)
    }

    /**
     * 键盘界面已经是这个方案就不再换，免得清掉正在打的字；引擎被设置页换了而界面还是旧的，照样要换。
     * 键盘正在打英文时不换：在设置里改中文的方案不等于要切回中文，存好了，切回中文时就是新的。
     */
    fun needsSwitch(picked: String, keyboardSchema: String, englishNow: Boolean): Boolean =
        picked.isNotBlank() && picked != keyboardSchema && !englishNow
}
