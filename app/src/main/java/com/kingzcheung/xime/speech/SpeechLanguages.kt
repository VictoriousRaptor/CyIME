package com.kingzcheung.xime.speech

import com.kingzcheung.xime.settings.InputLanguage

/** Explicit capabilities of the shipped recognizers. Unknown models are never assumed Chinese. */
object SpeechLanguages {
    fun supports(mode: String, language: InputLanguage): Boolean = when (SpeechModelSelection.primary(mode)) {
        SpeechModelCatalog.ZIPFORMER -> language == InputLanguage.CHINESE
        SpeechModelCatalog.ZIPFORMER_JA, SpeechModelCatalog.ZIPFORMER_JA_INT8 -> language == InputLanguage.JAPANESE
        SpeechModelCatalog.PARAFORMER -> language in setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        SpeechModelCatalog.SENSEVOICE -> language in InputLanguage.speech
        else -> false
    }

    /** Keep a compatible saved choice. A language fallback is session-local, never persisted. */
    fun select(language: InputLanguage, preferred: String, isReady: (String) -> Boolean): String {
        require(language != InputLanguage.UNSPECIFIED) { "当前方案未标注语言，请在「已安装的方案」里给它选语言" }
        require(language in InputLanguage.speech) { "暂时没有${language.displayName}语音识别" }
        if (supports(preferred, language)) {
            check(isReady(preferred)) { "所选${language.displayName}语音模型尚未完整下载，请在模型管理中下载" }
            return preferred
        }
        val alternatives = when (language) {
            InputLanguage.CHINESE -> listOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.ZIPFORMER, SpeechModelCatalog.SENSEVOICE)
            InputLanguage.ENGLISH -> listOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.SENSEVOICE)
            InputLanguage.JAPANESE -> listOf(SpeechModelCatalog.ZIPFORMER_JA_INT8, SpeechModelCatalog.ZIPFORMER_JA, SpeechModelCatalog.SENSEVOICE)
            else -> emptyList()
        }
        // A selected two-pass mode may already have a suitable multilingual second recognizer.
        val candidates = if (SpeechModelSelection.hasCorrection(preferred))
            listOf(SpeechModelCatalog.SENSEVOICE) + alternatives else alternatives
        return candidates.distinct().firstOrNull { supports(it, language) && isReady(it) }
            ?: error("未安装支持${language.displayName}的语音模型，请在模型管理中下载 SenseVoice" +
                when (language) { InputLanguage.ENGLISH -> " 或 Paraformer"; InputLanguage.JAPANESE -> " 或 Zipformer 日语"; else -> "" })
    }
}
