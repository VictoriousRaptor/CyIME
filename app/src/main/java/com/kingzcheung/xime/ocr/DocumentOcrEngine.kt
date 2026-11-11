package com.kingzcheung.xime.ocr

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Offline OCR lives in :ocr; its single worker never competes with the IME input queue. */
internal object DocumentOcrEngine {
    suspend fun recognize(context: Context, bitmap: Bitmap): String {
        currentCoroutineContext().ensureActive()
        return Session(context).use { it.recognize(bitmap) }
    }

    class Session(context: Context) : AutoCloseable {
        private val engine: DocumentOcrPipeline
        /** 映射在安装包上的模型跟会话同生命周期引用着，不让映射先被回收。 */
        private val source: OcrModelFiles.Source
        init {
            // 轻量版的 OpenCV 和模型一样是下载的：没下载时跟没模型一样，让键盘去提示下载。
            if (!OcrEngine.available(context)) throw OcrModelMissingException()
            check(OcrEngine.load(context)) { "Cannot load OCR image processing" }
            Core.setNumThreads(1)
            // 轻量版的模型是下载的；带模型的安装包直接映射 assets 里不压缩的模型（见 OcrModelFiles）。
            source = OcrModelFiles.prepare(context) ?: throw OcrModelMissingException()
            engine = when (val model = source) {
                is OcrModelFiles.Source.Packaged -> DocumentOcrPipeline(model.detection, model.recognition, model.characters)
                is OcrModelFiles.Source.Directory -> {
                    fun file(name: String): String = File(model.dir, name).absolutePath
                    DocumentOcrPipeline(
                        file(OcrModelFiles.DETECTION), file(OcrModelFiles.RECOGNITION), file(OcrModelFiles.CHARACTERS),
                    )
                }
            }
        }
        /** [table] lays the text out as a table in that format; null keeps plain text. */
        fun recognize(bitmap: Bitmap, @Suppress("UNUSED_PARAMETER") singleLine: Boolean = false,
                      table: OcrTableLayout.Format? = null): String {
            if (OcrFrameQuality.assess(bitmap) != OcrFrameQuality.Verdict.READY) return ""
            val rgba = Mat()
            val bgr = Mat()
            return try {
                Utils.bitmapToMat(bitmap, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                if (table != null) engine.recognizeTable(bgr, table)
                else DocumentOcrPipeline.assemble(engine.recognize(bgr))
            } finally { bgr.release(); rgba.release() }
        }
        override fun close() { engine.close() }
    }
}
