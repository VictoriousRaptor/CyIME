package com.kingzcheung.xime.service

import android.os.SystemClock
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginNetworkAuthHelper
import com.kingzcheung.xime.plugin.core.api.ToolPanelState
import com.kingzcheung.xime.plugin.core.api.ToolPlugin
import com.kingzcheung.xime.plugin.core.api.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 翻译栏（manifest `capabilities.tool.display: live`）：键盘上方一行输入栏，打的字进栏里；
 * 停手 [PAUSE_MS] 后交给插件生成，生成中和生成完都把结果当作未确认的字（composing）写进应用输入框，
 * 栏里再改字就整段换掉。回车：等这句出结果、定下来，清空栏接着打下一句；关栏：已写进去的留下。
 *
 * 只用 setComposingText 换字，不按记下的位置去删：用户挪了光标、或应用自己把这段定下了（比如点了发送），
 * 宁可多出一段，也不删错别的字。
 */
internal class LiveToolBar(private val service: XimeInputMethodService) {
    /** 插件调用排成一队按先后执行：旧的一句不会排到新的一句后面去生成。 */
    private val lane = Dispatchers.IO.limitedParallelism(1)
    private var pauseJob: Job? = null
    private var runJob: Job? = null
    /** 栏里的字一变、或要重新生成就递增；旧请求回来对不上就丢掉。 */
    private var seq = 0
    /** 栏里最近一次的字（变了才生成）。 */
    private var source = ""
    /** 应用输入框里由翻译栏写着、还没定下的字。 */
    private var written = ""
    /** 这段字已经有了完整结果（回车时不用再请求一次）。 */
    private var doneFor: String? = null
    /** 联网还没授权：授权页打开过一次，这次开栏不再请求，免得每停一下就弹一次。 */
    private var blocked = false
    /** 这次开栏已经提示过的错误（同一句不重复弹）。 */
    private var lastError: String? = null
    /** 最近一次往应用输入框写译文的时间：紧跟着的光标回执是自己写出来的，不当作用户点了输入框。 */
    private var lastWriteAt = 0L

    /** 新开栏。 */
    fun reset() {
        cancel()
        source = ""
        written = ""
        doneFor = null
        blocked = false
        lastError = null
        // 开栏时收掉应用输入框里没打完的拼音也会报一次光标，不能当成用户点了输入框
        lastWriteAt = SystemClock.uptimeMillis()
    }

    /** 栏里的字变了（打字、退格、清空）。 */
    fun onTextChanged(text: String) {
        if (!isOpen() || text == source) return
        source = text
        cancel()
        doneFor = null
        if (text.isBlank()) {
            write("")
            setLoading(false)
            return
        }
        val mine = seq
        pauseJob = service.serviceScope.launch {
            delay(PAUSE_MS)
            if (mine == seq) run(text, mine, finish = false)
        }
    }

    /** 插件设置变了（栏左边的圆钮换了目标语言）：栏里的字重新生成。 */
    fun retranslate() {
        if (!isOpen() || !focused()) return
        cancel()
        doneFor = null
        if (source.isNotBlank()) run(source, seq, finish = false)
    }

    /** 回车：这句定下来，栏清空。 */
    fun finish() {
        if (!isOpen()) return
        val text = ToolPanelEditTextHolder.editText?.text?.toString() ?: source
        source = text
        if (text.isBlank()) return
        if (doneFor == text && runJob?.isActive != true) {
            settle()
            return
        }
        cancel()
        run(text, seq, finish = true)
    }

    /**
     * 用户点了应用输入框，打字回到应用：栏留着。这句已经译完的，译文定下来、栏清空；
     * 还没译完的，半截译文撤掉，栏里的字留着，点回栏里再接着译（见 [enter]）。
     */
    fun leave() {
        if (!isOpen()) return
        val text = ToolPanelEditTextHolder.editText?.text?.toString() ?: source
        val done = text.isNotBlank() && doneFor == text && runJob?.isActive != true
        cancel()
        setLoading(false)
        val ic = service.currentInputConnection
        if (written.isNotEmpty() && !done) ic?.setComposingText("", 1)
        if (written.isNotEmpty()) ic?.finishComposingText()
        written = ""
        doneFor = null
        source = ""
        if (done) ToolPanelEditTextHolder.editText?.setText("")
    }

    /** 点回栏里：栏里还有没译的字就接着译，译文写在应用输入框现在的光标处。 */
    fun enter() {
        if (!isOpen()) return
        // 进栏时收掉应用输入框里没打完的拼音，同样会报一次光标
        lastWriteAt = SystemClock.uptimeMillis()
        val text = ToolPanelEditTextHolder.editText?.text?.toString().orEmpty()
        if (text.isNotBlank()) onTextChanged(text)
    }

    /**
     * 应用输入框的光标回执是不是用户自己点了输入框：栏正拿着输入、光标变了，
     * 又不是刚写译文带出来的。
     */
    fun userMovedHostCaret(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int): Boolean =
        isOpen() && focused() && liveBarCaretMovedByUser(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd, written.isNotEmpty(), SystemClock.uptimeMillis() - lastWriteAt)

    /** 关栏：写进去的留下，没出来的不再写。 */
    fun close() {
        cancel()
        if (written.isNotEmpty()) service.currentInputConnection?.finishComposingText()
        written = ""
        source = ""
        doneFor = null
    }

    private fun cancel() {
        pauseJob?.cancel()
        pauseJob = null
        runJob?.cancel()
        runJob = null
        seq++
    }

    private fun focused(): Boolean = service.uiState.value.toolPanelInputFocused

    private fun isOpen(): Boolean {
        val state = service.uiState.value
        return state.toolPanelVisible && state.toolPanelDisplay == ToolResult.LIVE.name
    }

    private fun setLoading(loading: Boolean) {
        if (service.uiState.value.toolPanelLoading != loading) {
            service.uiState.value = service.uiState.value.copy(toolPanelLoading = loading)
        }
    }

    private fun run(text: String, mine: Int, finish: Boolean) {
        if (blocked) return
        val pluginId = service.uiState.value.toolPanelPluginId
        // 使用前授权检测：接口域名没授权时引导去授权，不发请求
        if (!PluginNetworkAuthHelper.ensureAuthorized(service, pluginId)) {
            blocked = true
            return
        }
        val epoch = service.uiState.value.toolPanelRequestEpoch
        val startedAt = System.currentTimeMillis()
        fun current() = mine == seq && isOpen() && focused() && service.uiState.value.toolPanelRequestEpoch == epoch
        setLoading(true)
        runJob = service.serviceScope.launch {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin
            if (plugin == null) {
                setLoading(false)
                return@launch
            }
            withContext(lane) {
                runCatching {
                    plugin.onPanelInput(text)
                    plugin.onPanelAction("generate")
                }
            }
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (true) {
                val state: ToolPanelState? = withContext(lane) {
                    runCatching { plugin.getPanelState(text) }.getOrNull()
                }
                if (!current()) return@launch
                if (state == null) {
                    setLoading(false)
                    return@launch
                }
                service.uiState.value = service.uiState.value.copy(
                    toolPanelUiNodes = state.ui ?: service.uiState.value.toolPanelUiNodes,
                    toolPanelLoading = state.loading,
                )
                val out = state.items.firstOrNull()?.text.orEmpty()
                // 生成中有一段就先写一段；生成完没有结果（出错）也清掉，免得留着上一句的译文
                if (out.isNotEmpty() || !state.loading) write(out)
                if (!state.loading) {
                    if (out.isNotEmpty()) doneFor = text else showError(pluginId, startedAt)
                    break
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    setLoading(false)
                    break
                }
                delay(POLL_MS)
            }
            if (finish && current() && doneFor == text) settle()
        }
    }

    /** 没出结果：插件记下的错误（没填密钥、连不上……）弹一次。 */
    private fun showError(pluginId: String, since: Long) {
        val error = com.kingzcheung.xime.plugin.core.security.PluginErrorLog.getLastError(pluginId) ?: return
        val message = error.message
        if (message.isNullOrEmpty() || error.timestamp < since || message == lastError) return
        lastError = message
        android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** 这句定下来：输入框里的字变成正文，栏清空接着打下一句。 */
    private fun settle() {
        if (written.isNotEmpty()) service.currentInputConnection?.finishComposingText()
        written = ""
        doneFor = null
        source = ""
        ToolPanelEditTextHolder.editText?.setText("")
    }

    private fun write(text: String) {
        if (text == written) return
        // 打开栏以后换了输入框：不往新框里写，关栏
        if (!service.toolPanelEditorStillCurrent()) {
            service.closeToolPanel()
            return
        }
        val ic = service.currentInputConnection ?: return
        lastWriteAt = SystemClock.uptimeMillis()
        ic.setComposingText(text, 1)
        written = text
    }

    companion object {
        /** 写完译文后这么久之内的光标回执算自己写出来的。 */
        const val OWN_WRITE_MS = 500L
        /** 停手多久开始生成。 */
        const val PAUSE_MS = 600L
        private const val POLL_MS = 150L
        private const val TIMEOUT_MS = 30_000L
    }
}

/**
 * 翻译栏拿着输入时，应用输入框报来的光标变化是不是用户点了输入框（要把输入还给应用）。
 * 刚写过译文（[LiveToolBar.OWN_WRITE_MS] 之内）的回执是自己写的；光标正好停在自己写的组合区末尾也是。
 */
internal fun liveBarCaretMovedByUser(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
    candidatesStart: Int, candidatesEnd: Int, wroteComposing: Boolean, sinceLastWriteMs: Long): Boolean {
    if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return false
    if (sinceLastWriteMs in 0 until LiveToolBar.OWN_WRITE_MS) return false
    if (wroteComposing && candidatesStart >= 0 && newSelStart == newSelEnd && newSelEnd == candidatesEnd) return false
    return true
}

/** 插件面板里选择项选了一个值时交给插件的动作：`select:<设置名>:<值>`。 */
internal fun toolPanelSelectAction(key: String, option: String): String = "select:$key:$option"

/** 插件 ui 里给工具栏长按列表用的选择（style = "quick"，有选项）。 */
internal fun toolPanelQuickNode(nodes: List<com.kingzcheung.xime.plugin.core.config.UiNode>?): com.kingzcheung.xime.keyboard.PressMenuOptions? {
    val node = nodes?.firstOrNull {
        it.type == com.kingzcheung.xime.plugin.core.config.UiNodeType.SELECT && it.style == "quick" &&
            !it.key.isNullOrBlank() && it.options.isNotEmpty()
    } ?: return null
    return com.kingzcheung.xime.keyboard.PressMenuOptions(node.key!!, node.label.orEmpty(), node.options, node.value)
}
