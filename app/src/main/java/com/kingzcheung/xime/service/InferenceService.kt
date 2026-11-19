package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.kingzcheung.xime.association.NativeOnnxEngine
import com.kingzcheung.xime.handwriting.HandwritingInference
import com.kingzcheung.xime.handwriting.HandwritingNativeEngine
import com.kingzcheung.xime.handwriting.HanziRareTemplates
import java.io.File

class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        private const val MODEL_HANDWRITING = "handwriting"
    }

    private var onnxLibsLoaded = false

    private val binder = object : IInferenceService.Stub() {

        override fun loadModel(modelId: String, modelPath: String, extraPath: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_HANDWRITING -> loadHandwritingModel(modelPath, extraPath)
                    else -> {
                        Log.e(TAG, "Unknown modelId: $modelId")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel($modelId) failed", e)
                false
            }
        }

        override fun unloadModel(modelId: String) {
            try {
                when (modelId) {
                    MODEL_HANDWRITING -> {
                        HandwritingNativeEngine.release()
                        HandwritingInference.clearChars()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "unloadModel($modelId) failed", e)
            }
        }

        override fun isModelLoaded(modelId: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_HANDWRITING -> HandwritingNativeEngine.isInitialized()
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }

        // 智能联想 11-02 起只用内置 n-gram 联想表，不再在本进程跑联想模型。
        override fun predict(modelId: String, text: String, topK: Int): MutableList<String> = mutableListOf()

        override fun recognizeHandwriting(modelId: String, points: FloatArray, strokePointCounts: IntArray, topK: Int): MutableList<String> {
            if (modelId != MODEL_HANDWRITING) return mutableListOf()
            // 模型未加载时（如进程重启后客户端状态未同步）直接返回空，
            // 避免在未初始化的 native 引擎上推理导致进程崩溃
            if (!HandwritingNativeEngine.isInitialized()) {
                Log.w(TAG, "recognizeHandwriting called before model loaded, returning empty")
                return mutableListOf()
            }
            val strokes = HandwritingInference.decodeStrokes(points, strokePointCounts)
            val candidates = HandwritingInference.recognize(strokes, topK)
            val result = mutableListOf<String>()
            for (c in candidates) {
                result.add(c.char)
                result.add(c.score.toString())
            }
            return result
        }

        override fun recognizeHandwritingRaw(modelId: String, points: FloatArray, strokePointCounts: IntArray, n: Int): MutableList<String> {
            if (modelId != MODEL_HANDWRITING || !HandwritingNativeEngine.isInitialized()) return mutableListOf()
            val result = mutableListOf<String>()
            for ((ch, idx, score) in HandwritingInference.recognizeRaw(HandwritingInference.decodeStrokes(points, strokePointCounts), n)) {
                result.add(ch); result.add(idx.toString()); result.add(score.toString())
            }
            return result
        }

        override fun processAudioBytes(input: ByteArray, sampleRate: Int): ByteArray {
            var maxSample = 0
            for (i in input.indices step 2) {
                val low = input[i].toInt() and 0xFF
                val high = input[i + 1].toInt()
                val sample = (high shl 8) or low
                val absSample = kotlin.math.abs(sample)
                if (absSample > maxSample) maxSample = absSample
            }

            if (maxSample == 0) return input

            val peak = maxSample.toFloat() / 32768.0f
            val gain = if (peak < 0.5f) {
                kotlin.math.min(4.0f, 0.5f / peak)
            } else {
                1.0f
            }

            if (gain <= 1.0f) return input

            val output = ByteArray(input.size)
            for (i in input.indices step 2) {
                val low = input[i].toInt() and 0xFF
                val high = input[i + 1].toInt()
                var sample = ((high shl 8) or low).toShort().toFloat() * gain
                sample = sample.coerceIn(-32768f, 32767f)
                val s = sample.toInt()
                output[i] = (s and 0xFF).toByte()
                output[i + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return output
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        NativeOnnxEngine.releaseSharedEnv()
        super.onDestroy()
    }

    private fun loadOnnxLibs(): Boolean {
        if (onnxLibsLoaded) return true
        val success = NativeOnnxEngine.loadNativeLibrary(this)
        onnxLibsLoaded = success
        return success
    }

    private fun loadHandwritingModel(modelPath: String, charIndexPath: String): Boolean {
        if (!loadOnnxLibs()) return false
        // 先释放旧 session：重复加载时避免 ORT 双 session 并存推高内存
        if (HandwritingNativeEngine.isInitialized()) {
            HandwritingNativeEngine.release()
            HandwritingInference.clearChars()
        }
        if (!HandwritingNativeEngine.initialize(this, modelPath)) {
            Log.e(TAG, "HandwritingNativeEngine.initialize failed")
            return false
        }
        // idx→汉字词表在本进程加载，客户端只收最终候选字
        if (!HandwritingInference.loadCharIndex(charIndexPath)) {
            HandwritingNativeEngine.release()
            return false
        }
        // 常用度表缺失或损坏只是不调整候选顺序，不影响识别
        runCatching {
            assets.open("handwriting/hanzi_frequency.tsv").bufferedReader(Charsets.UTF_8).useLines(HandwritingInference::loadFrequency)
        }.onFailure { Log.w(TAG, "hanzi frequency table unreadable", it) }
        // 字表外生僻字模板：特征按内置模型算的，换了别的模型就不用；个人模型是在内置模型上微调的，照用
        HandwritingInference.loadRareTemplates(runCatching {
            val personal = File(modelPath).name == "ochwpro-personal.onnx"
            HanziRareTemplates.decode(
                // 轻量版的生僻字模板是跟手写模型一起下载的（HandwritingAssets），没下好时这一步失败，不出生僻字。
                com.kingzcheung.xime.handwriting.HandwritingAssets.readText(this, "hanzi_rare_och.json"),
                com.kingzcheung.xime.handwriting.HandwritingAssets.readBytes(this, "hanzi_rare_och.bin"),
                if (personal) null else sha256(File(modelPath)),
            )
        }.onFailure { Log.w(TAG, "rare hanzi templates unreadable", it) }.getOrNull())
        return true
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
