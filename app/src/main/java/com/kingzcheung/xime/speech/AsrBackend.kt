package com.kingzcheung.xime.speech

import com.kingzcheung.xime.settings.InputLanguage

interface AsrBackend {
    val name: String
    /** Bounded finalization allowance; capture releases the microphone before waiting. */
    val finishTimeoutMs: Long get() = 8000L
    
    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    )
    
    fun initialize(): Boolean
    /** Local warmup uses the same language as the upcoming recording; legacy plugins ignore it. */
    fun initialize(language: InputLanguage): Boolean = initialize()
    /** On-demand capture must not wait for optional model warmup. */
    fun prepareForRecording(language: InputLanguage): Boolean = initialize(language)
    fun start(): Boolean
    /** Legacy online plugins have no language contract; preserve Chinese and reject unverified routing. */
    fun start(language: InputLanguage): Boolean {
        require(language == InputLanguage.CHINESE) { "当前语音后端尚未声明${language.displayName}支持，请使用本地多语言模型" }
        return start()
    }
    /** Only true if the current recognizer and its configured pipeline support this language. */
    fun canContinueIn(language: InputLanguage): Boolean = false
    /** 最近一次 onResult 的文字是否自带标点（内置在线接口返回服务端原文）。默认 false：本地模型、
     *  插件和在线失败后的本地回退都按原有空格化处理，只有真正产出标点的后端才声明。 */
    val resultKeepsPunctuation: Boolean get() = false
    fun processAudioChunk(buffer: ByteArray)
    fun stop()
    fun cancel()
    /**
     * 另一个线程正卡在 [stop] 里等最终结果，而这份结果已经没人要（用户又按了开始，旧会话的结果会被作废）：
     * 让它尽快返回。只用于本地识别（不再等整段重识别）；默认什么都不做，照旧等。
     */
    fun abandonFinishing() {}
    fun release()
    fun isAvailable(): Boolean
}
