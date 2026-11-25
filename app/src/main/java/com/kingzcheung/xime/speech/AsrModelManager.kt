package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.settings.InputLanguage
import java.io.File

/**
 * ASR 模型管理与选择。
 *
 * 使用官方 sherpa-onnx；保留原市场 Zipformer，补充 ASRInput 模型和双模型选择。
 * 模型清单与描述来自「扩展商店」远程索引（[ModelManager]，category=asr），
 * 新语音模型也有本地固定索引；索引离线时保留 Zipformer 兼容入口。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        /** 内置默认 ASR 模型（远程索引加载前/失败时的兜底）。 */
        val DEFAULT_MODEL = AsrModelInfo(
            id = "zipformer-zh-int8",
            name = "Zipformer · 中文",
            description = "Zipformer 架构，适合实时语音识别，int8 量化",
            language = "zh",
            size = "132.63MB",
            downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            modelType = "transducer",
            files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
            encoderFile = "encoder.int8.onnx",
            decoderFile = "decoder.onnx",
            joinerFile = "joiner.int8.onnx"
        )

        /** 把索引里的 ModelInfo 转换为 ASR 专用模型信息。 */
        private fun toAsrModelInfo(info: com.kingzcheung.xime.model.ModelInfo): AsrModelInfo {
            val version = info.resolvedVersion()
            val fileNames = info.files.map { it.name }
            val japanese = SpeechModelCatalog.isJapaneseZipformer(info.id)
            val japaneseSuffix = if (info.id == SpeechModelCatalog.ZIPFORMER_JA_INT8) ".int8.onnx" else ".onnx"
            return AsrModelInfo(
                id = info.id,
                name = SpeechModelSelection.displayName(info.id) ?: info.name,
                description = info.description,
                language = when (info.id) { SpeechModelCatalog.ZIPFORMER_JA, SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "ja"; SpeechModelCatalog.SENSEVOICE -> "auto"; SpeechModelCatalog.PARAFORMER -> "zh-en"; else -> "zh" },
                size = version?.size ?: info.size,
                downloadUrl = info.archiveUrl ?: "",
                modelType = when (info.id) { SpeechModelCatalog.ZIPFORMER_JA, SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "offline-transducer"; SpeechModelCatalog.PARAFORMER -> "paraformer"; SpeechModelCatalog.SENSEVOICE -> "sensevoice"; else -> "transducer" },
                files = fileNames,
                encoderFile = if (japanese) "encoder-epoch-99-avg-1$japaneseSuffix" else "encoder.int8.onnx",
                decoderFile = if (japanese) "decoder-epoch-99-avg-1$japaneseSuffix" else if (info.id == SpeechModelCatalog.PARAFORMER) "decoder.int8.onnx" else "decoder.onnx",
                joinerFile = if (japanese) "joiner-epoch-99-avg-1$japaneseSuffix" else "joiner.int8.onnx"
            )
        }
    }

    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = ""
    )

    /** ASR 分类的模型清单（索引优先，索引未加载时用内置默认）。 */
    fun getAsrModels(): List<AsrModelInfo> {
        val fromIndex = ModelManager.getModelsByCategory(ModelCategory.ASR)
            .map { toAsrModelInfo(it) }
        // The speech catalog is for recognizers only; a speaker (voiceprint) entry must never become a fake ASR model.
        return (fromIndex + SpeechModelCatalog.models.filter { it.category == ModelCategory.ASR }.map(::toAsrModelInfo) + DEFAULT_MODEL)
            .distinctBy { it.id }.sortedBy { SpeechModelSelection.primaryIds.indexOf(it.id).let { order -> if (order < 0) Int.MAX_VALUE else order } }
    }

    fun isModelReady(): Boolean = try { selection().ready } catch (_: Exception) { false }

    data class Selection(
        val mode: String,
        val first: AsrModelInfo?, val firstDir: File?, val secondDir: File?,
        val recognitionLanguage: String = "auto",
        /** 整句定稿用的 X-ASR 目录；为 null 时整句定稿用 SenseVoice。 */
        val finalizerDir: File? = null,
    ) {
        val ready: Boolean get() = (first == null || firstDir != null && first.files.isNotEmpty() && first.files.all { File(firstDir, it).let { f -> f.isFile && f.length() > 0 } }) &&
            (secondDir == null || listOf("model.int8.onnx", "tokens.txt").all { File(secondDir, it).let { f -> f.isFile && f.length() > 0 } })
        val key: String get() = listOfNotNull(firstDir, secondDir, finalizerDir).joinToString(prefix = "$mode:$recognitionLanguage:") { dir ->
            dir.absolutePath + ":" + dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                .joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        }
    }

    fun selection(mode: String = getSelectedModelId(), language: InputLanguage? = null): Selection {
        if (language != null) require(SpeechLanguages.supports(mode, language)) {
            "所选模型不支持${language.displayName}语音"
        }
        // SenseVoice's automatic language detection turns short, phone-quality Mandarin into Japanese
        // (2026-10-18: up to 23% of 0.5 s telephone-band clips with "auto", none with "zh"). Chinese and
        // an unspecified language always decode as Chinese, in every build. Detection stays only when the
        // voice language is explicitly Japanese or English (one shared engine for both, as before).
        val languageId = when (language) {
            InputLanguage.JAPANESE, InputLanguage.ENGLISH -> "auto"
            else -> "zh"
        }
        fun dir(id: String): File {
            ModelStorage.migrateLegacyForModel(context, id)
            return ModelStorage.getModelDir(context, id)
        }
        val firstId = when (mode) {
            SpeechModelCatalog.TWO_PASS -> SpeechModelCatalog.PARAFORMER
            SpeechModelCatalog.ZIPFORMER_TWO_PASS -> SpeechModelCatalog.ZIPFORMER
            else -> null
        }
        if (firstId != null) return Selection(mode,
            getAsrModels().first { it.id == firstId }, dir(firstId), dir(SpeechModelCatalog.SENSEVOICE), languageId)
        val info = getAsrModels().firstOrNull { it.id == mode } ?: error("不支持的语音模型：$mode")
        return if (info.modelType == "sensevoice") Selection(mode, null, null, dir(mode), languageId)
        else Selection(mode, info, dir(mode), null, languageId)
    }

    /** X-ASR for 整句定稿, when chosen ([finalizer]) and fully downloaded; when it applies is [SpeechModelSelection.xasrFinalizes]. */
    fun wholeSentenceFinalizer(selection: Selection, finalizer: String): Selection {
        if (finalizer != SpeechModelCatalog.XASR) return selection
        if (!SpeechModelSelection.xasrFinalizes(selection.mode, selection.recognitionLanguage == "zh", wholeSentenceOn = true)) return selection
        return if (isXasrReady()) selection.copy(finalizerDir = ModelStorage.getModelDir(context, SpeechModelCatalog.XASR)) else selection
    }

    /** Every X-ASR file is on disk; the settings page shows the same check the :asr service uses. */
    fun isXasrReady(): Boolean {
        val info = getAsrModels().firstOrNull { it.id == SpeechModelCatalog.XASR } ?: return false
        val dir = ModelStorage.getModelDir(context, SpeechModelCatalog.XASR)
        return info.files.isNotEmpty() && info.files.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    /** Called on the speech worker: model file checks/migration never enter the key path. */
    fun selectionForLanguage(language: InputLanguage): Selection {
        val mode = getSelectedModelId(language)
        return selection(mode, language).also { check(it.ready) { "所选${language.displayName}语音模型尚未完整下载，请在模型管理中下载" } }
    }

    private val preferences get() = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)

    /** Persist the complete selection as one value, also passed explicitly to the :asr process. */
    fun getSelectedModelId(): String {
        val initial = com.kingzcheung.xime.model.DeviceDefaults.voiceModel(context)
        return preferences.getString("selected_model", initial) ?: initial
    }

    /** Language choices are independent. Legacy global choices remain a read-only migration fallback. */
    fun getSelectedModelId(language: InputLanguage): String {
        require(language in InputLanguage.speech) {
            if (language == InputLanguage.UNSPECIFIED) "当前方案未标注语言，请在「已安装的方案」里给它选语言" else "暂时没有${language.displayName}语音识别"
        }
        preferences.getString("selected_model_${language.id}", null)?.let { stored ->
            val mode = SpeechModelSelection.withoutRetiredCorrector(stored)
            if (mode != stored) {
                preferences.edit().putString("selected_model_${language.id}", mode).apply()
                SpeechModelSelection.retiredCorrectors.forEach { ModelStorage.getModelDir(context, it).deleteRecursively() }
            }
            return mode
        }
        val legacy = getSelectedModelId()
        if (SpeechLanguages.supports(legacy, language)) return withDefaultCorrection(legacy, language)
        return runCatching { SpeechLanguages.select(language, legacy) { selection(it, language).ready } }.getOrElse {
            when (language) {
                // 一个日语模型都没下载时：指向 153 MB 的轻量版（和自动选择、开箱引导的先后一致），不是 586 MB 的原版。
                InputLanguage.JAPANESE -> SpeechModelCatalog.ZIPFORMER_JA_INT8
                InputLanguage.ENGLISH -> SpeechModelCatalog.PARAFORMER
                else -> com.kingzcheung.xime.model.DeviceDefaults.voiceModel(context)
            }
        }
    }

    /**
     * Unchosen Chinese/English default: Paraformer alone misrecognizes about one character in
     * five (2026-10-07 FLEURS, 18% CER); once SenseVoice is downloaded, correct each pause with it.
     * The 完整版 without Paraformer starts on SenseVoice alone; once Paraformer is downloaded it gets the
     * same streaming preview + SenseVoice. Any explicit per-language or legacy choice, including correction off, is kept.
     */
    private fun withDefaultCorrection(mode: String, language: InputLanguage): String =
        if ((mode == SpeechModelCatalog.PARAFORMER ||
                mode == SpeechModelCatalog.SENSEVOICE && SpeechLanguages.supports(SpeechModelCatalog.TWO_PASS, language)) &&
            !preferences.contains("selected_model") &&
            runCatching { selection(SpeechModelCatalog.TWO_PASS).ready }.getOrDefault(false)) SpeechModelCatalog.TWO_PASS
        else mode

    fun setModel(modelId: String, language: InputLanguage) {
        require(SpeechLanguages.supports(modelId, language)) { "所选模型不支持${language.displayName}语音" }
        preferences.edit().putString("selected_model_${language.id}", modelId).apply()
    }

    fun setFirstPassModel(modelId: String, language: InputLanguage) =
        setModel(SpeechModelSelection.selectPrimary(getSelectedModelId(language), modelId), language)

    fun setRefinementEnabled(enabled: Boolean, language: InputLanguage) =
        setModel(SpeechModelSelection.withCorrection(getSelectedModelId(language), enabled), language)

    fun getFirstPassModelId(): String = SpeechModelSelection.primary(getSelectedModelId())

    fun isRefinementEnabled(): Boolean = SpeechModelSelection.hasCorrection(getSelectedModelId())

    fun setFirstPassModel(modelId: String) {
        setModel(SpeechModelSelection.selectPrimary(getSelectedModelId(), modelId))
    }

    fun setRefinementEnabled(enabled: Boolean) {
        setModel(SpeechModelSelection.withCorrection(getSelectedModelId(), enabled))
    }

    fun setModel(modelId: String) {
        preferences.edit().putString("selected_model", modelId).apply()
    }
}
