package com.kingzcheung.xime.speech

/** One primary recognizer, optionally followed by SenseVoice. Existing stored IDs remain valid. */
internal object SpeechModelSelection {
    val primaryIds = listOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.ZIPFORMER, SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA)

    /**
     * 语音页里能选的主模型。中文 Zipformer 2026-11-03 起不再提供（效果不如 SenseVoice / X-ASR，边说边出字已有 Paraformer）；
     * 正在用它的人，选择框里照旧显示它，直到换成别的。
     */
    fun offeredIds(currentPrimary: String): List<String> =
        primaryIds.filter { it != SpeechModelCatalog.ZIPFORMER || it == currentPrimary }

    fun supportsCorrection(mode: String) = primary(mode) in setOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.ZIPFORMER)

    fun primary(mode: String): String = when (mode) {
        SpeechModelCatalog.ZIPFORMER_TWO_PASS -> SpeechModelCatalog.ZIPFORMER
        SpeechModelCatalog.TWO_PASS -> SpeechModelCatalog.PARAFORMER
        else -> mode
    }

    /**
     * Dolphin / TeleSpeech dialect correction (1.14.25, stored as "primary+model") was removed after
     * Henan dialect tests: SenseVoice was better on both videos. Such a choice falls back to SenseVoice.
     */
    val retiredCorrectors = setOf("dolphin-base-ctc-int8", "telespeech-ctc-int8")
    fun withoutRetiredCorrector(mode: String): String {
        val at = mode.lastIndexOf('+')
        return if (at > 0 && mode.substring(at + 1) in retiredCorrectors) withCorrection(mode.substring(0, at), true) else mode
    }

    fun hasCorrection(mode: String): Boolean = mode == SpeechModelCatalog.ZIPFORMER_TWO_PASS || mode == SpeechModelCatalog.TWO_PASS

    fun selectPrimary(current: String, model: String): String {
        require(model in primaryIds)
        return withCorrection(model, hasCorrection(current))
    }

    fun withCorrection(mode: String, enabled: Boolean): String = when (val model = primary(mode)) {
        SpeechModelCatalog.ZIPFORMER -> if (enabled) SpeechModelCatalog.ZIPFORMER_TWO_PASS else model
        SpeechModelCatalog.PARAFORMER -> if (enabled) SpeechModelCatalog.TWO_PASS else model
        // SenseVoice is a complete standalone choice and never corrects itself.
        SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.ZIPFORMER_JA, SpeechModelCatalog.ZIPFORMER_JA_INT8 -> model
        else -> error("不支持的语音模型：$model")
    }

    /**
     * 整句定稿 really changes one language's dictation: Chinese only, with a streaming preview + SenseVoice
     * correction, or SenseVoice alone. Paraformer / Zipformer alone and other languages ignore the switch.
     */
    fun wholeSentenceApplies(mode: String, chinese: Boolean): Boolean =
        chinese && (hasCorrection(mode) || primary(mode) == SpeechModelCatalog.SENSEVOICE)

    /**
     * X-ASR (when downloaded) writes the final Chinese text wherever 整句定稿 applies: after a streaming preview with
     * SenseVoice correction, and for SenseVoice alone (the public default), where it then decodes every sentence.
     * 2026-10-30 same-path test, 83 clips: X-ASR whole 5.6 % CER, SenseVoice whole 8.1 %, 豆包 5.7 %.
     */
    fun xasrFinalizes(mode: String, chinese: Boolean, wholeSentenceOn: Boolean): Boolean =
        wholeSentenceOn && wholeSentenceApplies(mode, chinese)

    /** What decodes one language's dictation; the :asr service and every line of the voice settings page use this. */
    data class Pipeline(
        /** Shows words while speaking (Paraformer / Zipformer); null = text appears at each pause. */
        val preview: String?,
        /** Writes the final text: [SpeechModelCatalog.XASR], SenseVoice, or the primary model itself. */
        val finalizer: String,
        val wholeSentence: Boolean,
    )

    fun pipeline(mode: String, chinese: Boolean, wholeSentenceOn: Boolean, xasrReady: Boolean): Pipeline {
        val primary = primary(mode)
        val finalizer = when {
            xasrReady && xasrFinalizes(mode, chinese, wholeSentenceOn) -> SpeechModelCatalog.XASR
            !hasCorrection(mode) -> primary
            else -> SpeechModelCatalog.SENSEVOICE
        }
        val streaming = primary == SpeechModelCatalog.PARAFORMER || primary == SpeechModelCatalog.ZIPFORMER
        return Pipeline(primary.takeIf { streaming }, finalizer, wholeSentenceOn && wholeSentenceApplies(mode, chinese))
    }

    /**
     * 「说完后」怎么定稿：语音页每种语言一组单选（2026-11-09 起代替「二次校正」两个开关 + 「整句定稿」开关）。
     * 每项只是已有两个存储值的一种组合（模型组合里的 SenseVoice 校正 + 整句定稿开关），不新增存储、不改识别流程。
     */
    enum class Finish { NONE, SENSEVOICE, WHOLE }

    /** [mode] 能选的定稿方式；只剩 [Finish.NONE] 时（日语、只用 SenseVoice 的英文）页面不出单选。 */
    fun finishOptions(mode: String, chinese: Boolean): List<Finish> = buildList {
        add(Finish.NONE)
        if (supportsCorrection(mode)) add(Finish.SENSEVOICE)
        if (chinese && (supportsCorrection(mode) || primary(mode) == SpeechModelCatalog.SENSEVOICE)) add(Finish.WHOLE)
    }

    /** 现在实际生效的定稿方式；与 [pipeline] 同一条规则。 */
    fun finish(mode: String, chinese: Boolean, wholeSentenceOn: Boolean): Finish = when {
        wholeSentenceOn && wholeSentenceApplies(mode, chinese) -> Finish.WHOLE
        hasCorrection(mode) -> Finish.SENSEVOICE
        else -> Finish.NONE
    }

    /**
     * 选了 [finish] 后要存的值：新的模型组合，以及整句定稿开关（null = 不动；这个开关只影响中文）。
     * 流式中文选「不校正」时不动整句开关：没有 SenseVoice 校正时它本来就不生效。
     */
    fun applyFinish(mode: String, chinese: Boolean, finish: Finish): Pair<String, Boolean?> = when (finish) {
        Finish.NONE -> withCorrection(mode, false) to
            (if (chinese && primary(mode) == SpeechModelCatalog.SENSEVOICE) false else null)
        Finish.SENSEVOICE -> withCorrection(mode, true) to (if (chinese) false else null)
        Finish.WHOLE -> withCorrection(mode, true) to true
    }

    fun displayName(model: String): String? = when (model) {
        SpeechModelCatalog.ZIPFORMER -> "Zipformer · 中文"
        SpeechModelCatalog.ZIPFORMER_JA -> "Zipformer · 日语原版"
        SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "Zipformer · 日语轻量"
        SpeechModelCatalog.PARAFORMER -> "Paraformer · 中英"
        SpeechModelCatalog.SENSEVOICE -> "SenseVoice · 多语言"
        else -> null
    }
}
