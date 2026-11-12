package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection

/**
 * 语音写进哪个框（11-11 用户：翻译栏里要能语音输入）。开始说话时焦点在键盘自己的输入框（翻译栏、剪贴板搜索、
 * 新增固定）就记下那个框，这次说的话、临时结果都写进它，翻译栏照常停一下就翻译；否则写应用的输入框。
 * 说完前焦点挪走也不换框；那个框拿不到连接时宁可不写，也不落到后面应用的框里。
 */
internal class VoiceTarget<T : Any>(
    private val focusedInternal: () -> T?,
    private val connectionFor: (T) -> InputConnection?,
    private val host: () -> InputConnection?,
) {
    /** 这次说话写的键盘内部输入框；写应用输入框时为 null。 */
    var editor: T? = null
        private set

    val internal: Boolean get() = editor != null

    /** 开始说话时调用一次。 */
    fun begin() {
        editor = focusedInternal()
    }

    fun connection(): InputConnection? = editor.let { box -> if (box == null) host() else connectionFor(box) }
}
