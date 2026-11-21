package com.kingzcheung.xime.speech

import com.kingzcheung.xime.model.*

/** Pinned official exports, independently available even if the original market is offline. */
object SpeechModelCatalog {
    const val ZIPFORMER = "zipformer-zh-int8"
    const val ZIPFORMER_JA = "zipformer-ja-reazonspeech-fp32"
    const val ZIPFORMER_JA_INT8 = "zipformer-ja-reazonspeech-int8"
    fun isJapaneseZipformer(id: String) = id == ZIPFORMER_JA || id == ZIPFORMER_JA_INT8
    const val ZIPFORMER_TWO_PASS = "zipformer-sensevoice"
    const val PARAFORMER = "paraformer-zh-en-int8"
    const val SENSEVOICE = "sensevoice-multilingual-int8"
    const val TWO_PASS = "paraformer-sensevoice"
    /** 整句定稿的可选模型：X-ASR 中英 Zipformer（离线、自带标点）；何时代替 SenseVoice 见 [SpeechModelSelection.xasrFinalizes]。 */
    const val XASR = "xasr-zh-en-punct-int8"
    /** CyIME 自己 Release 里的原样转存（哈希与原站一致）：HuggingFace 和它的国内镜像都下不来时用。 */
    private const val RELEASE = "https://github.com/Cyletix/CyIME/releases/download/models/"
    private const val reazonBase = "https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/291488c8151be24d7da4bf7af26e533fad96e407"
    val models = listOf(
        ModelInfo(ZIPFORMER_JA_INT8, "Zipformer · 日语轻量", "约 153 MB，边说边预览，停顿后定稿。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "291488c8151b", size = "153.0 MB", files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", "$reazonBase/encoder-epoch-99-avg-1.int8.onnx", "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d", 154670139L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-int8-291488c8151b-encoder-epoch-99-avg-1.int8.onnx")),
                ModelFile("decoder-epoch-99-avg-1.int8.onnx", "$reazonBase/decoder-epoch-99-avg-1.int8.onnx", "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485", 2959337L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-int8-291488c8151b-decoder-epoch-99-avg-1.int8.onnx")),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", "$reazonBase/joiner-epoch-99-avg-1.int8.onnx", "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de", 2696970L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-int8-291488c8151b-joiner-epoch-99-avg-1.int8.onnx")),
                ModelFile("tokens.txt", "$reazonBase/tokens.txt", "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def", 45754L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-int8-291488c8151b-tokens.txt"))
            )))),
        ModelInfo(ZIPFORMER_JA, "Zipformer · 日语原版", "约 586 MB，边说边预览，停顿后定稿，加载较慢。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "291488c8151b", size = "586.4 MB", files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", "$reazonBase/encoder-epoch-99-avg-1.onnx", "ecdb0b771e16104aaf8e579cb3c1e32fbd589eb641c5946d82b615bd366c5f96", 592347848L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-fp32-291488c8151b-encoder-epoch-99-avg-1.onnx")),
                ModelFile("decoder-epoch-99-avg-1.onnx", "$reazonBase/decoder-epoch-99-avg-1.onnx", "58b18211ae06265466bfa17172dab574df94f76c8bcb61a3640c28ba860e4124", 11767836L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-fp32-291488c8151b-decoder-epoch-99-avg-1.onnx")),
                ModelFile("joiner-epoch-99-avg-1.onnx", "$reazonBase/joiner-epoch-99-avg-1.onnx", "d38a81d1191c9ed6de6a1719503692e07e3e973e2364adde0abae5eaaded1174", 10720115L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-fp32-291488c8151b-joiner-epoch-99-avg-1.onnx")),
                ModelFile("tokens.txt", "$reazonBase/tokens.txt", "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def", 45754L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "zipformer-ja-reazonspeech-fp32-291488c8151b-tokens.txt"))
            )))),
        // Paraformer、SenseVoice 国内原来只有 hf-mirror 一条线路，两百多 MB 常常很慢：加 CyIME 魔搭仓库的同名副本
        // （和 Release `models` 逐字节相同，下载后仍按哈希校验；魔搭上还没有时 404，自动换下一条）。
        ModelInfo("paraformer-zh-en-int8", "Paraformer · 中英", "中英识别，边说边出字。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "8e40c43232a1", size = "226.2 MB", files = listOf(
                ModelFile("decoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/8e40c43232a1c5c66c82111efc5820d3accca11b/decoder.int8.onnx", "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f", 71664561L,
                    mirrors = listOf(RELEASE + "paraformer-zh-en-int8-8e40c43232a1-decoder.int8.onnx", DownloadMirrors.CYIME_MODELSCOPE + "paraformer-zh-en-int8-8e40c43232a1-decoder.int8.onnx")),
                ModelFile("encoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/8e40c43232a1c5c66c82111efc5820d3accca11b/encoder.int8.onnx", "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a", 165462184L,
                    mirrors = listOf(RELEASE + "paraformer-zh-en-int8-8e40c43232a1-encoder.int8.onnx", DownloadMirrors.CYIME_MODELSCOPE + "paraformer-zh-en-int8-8e40c43232a1-encoder.int8.onnx")),
                ModelFile("tokens.txt", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/8e40c43232a1c5c66c82111efc5820d3accca11b/tokens.txt", "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6", 75756L,
                    mirrors = listOf(RELEASE + "paraformer-zh-en-int8-8e40c43232a1-tokens.txt", DownloadMirrors.CYIME_MODELSCOPE + "paraformer-zh-en-int8-8e40c43232a1-tokens.txt"))
            )))),
        // 2026-10-30 回放：公开口语 CER 6.1 %（SenseVoice 整句 7.2 %），平板外放实测与豆包同为 5.6 %；只用于中文整句定稿。
        ModelInfo(XASR, "X-ASR · 中英整句定稿", "可选。中文说完后用它整句定稿，更准更快。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "2026-06-03", size = "136.4MB",
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2", sha256 = "5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224",
                archiveMirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2"),
                files = listOf(
                    ModelFile("encoder-epoch-99-avg-1.int8.onnx", "", "", 0L),
                    ModelFile("decoder-epoch-99-avg-1.onnx", "", "", 0L),
                    ModelFile("joiner-epoch-99-avg-1.int8.onnx", "", "", 0L),
                    ModelFile("tokens.txt", "", "", 0L),
                )))),
        ModelInfo("sensevoice-multilingual-int8", "SenseVoice · 多语言", "支持中英日粤韩，停顿后出字；可独立识别，也可为流式模型二次校正。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "2365baeacb50", size = "228.5 MB", files = listOf(
                ModelFile("model.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx", "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", 239233841L,
                    mirrors = listOf(RELEASE + "sensevoice-multilingual-int8-2365baeacb50-model.int8.onnx", DownloadMirrors.CYIME_MODELSCOPE + "sensevoice-multilingual-int8-2365baeacb50-model.int8.onnx")),
                ModelFile("tokens.txt", "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt", "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", 315894L,
                    mirrors = listOf(RELEASE + "sensevoice-multilingual-int8-2365baeacb50-tokens.txt", DownloadMirrors.CYIME_MODELSCOPE + "sensevoice-multilingual-int8-2365baeacb50-tokens.txt"))
            )))),
    )
}
