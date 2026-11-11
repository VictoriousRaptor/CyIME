package com.kingzcheung.xime.ocr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.ui.settings.formatModelMegabytes

/**
 * 轻量版第一次拍照识别：说明要下载的东西和大小，点了才下载（国内魔搭、海外 GitHub，按地区自动选）。
 * 要下的是识别模型和拍照识别引擎（OpenCV）里还没有的那几样；以前下过模型、升级后只差引擎的，只下引擎。
 */
@Composable
internal fun OcrModelDownloadPrompt(textColor: Color, accent: Color, modifier: Modifier = Modifier, onReady: () -> Unit) {
    val context = LocalContext.current
    val states by ModelManager.downloadStates.collectAsState()
    val installed by ModelManager.installedRevision.collectAsState()
    val missing = remember(installed) {
        listOfNotNull(
            BuiltinModelCatalog.OCR_MODEL.takeUnless { OcrModelFiles.available(context) },
            BuiltinModelCatalog.OCR_ENGINE.takeUnless { OcrEngine.available(context) },
        ).mapNotNull { ModelManager.getModel(it) }
    }
    val downloading = missing.mapNotNull { states[it.id] as? ModelDownloadState.Downloading }.firstOrNull()
    val failed = missing.mapNotNull { states[it.id] as? ModelDownloadState.Error }.firstOrNull()
    val size = formatModelMegabytes(missing.sumOf { model -> downloadBytes(model) })
    LaunchedEffect(missing) { if (missing.isEmpty()) onReady() }
    Column(modifier.padding(horizontal = 24.dp).testTag("ocr-model-download"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                downloading != null ->
                    if (downloading.progress > 0f) "正在下载拍照识别模型 ${(downloading.progress * 100).toInt()}%…" else "正在下载拍照识别模型…"
                failed != null -> "下载失败：${failed.message}"
                else -> "拍照识别要先下载识别模型（约 $size），下载一次以后不联网也能用。"
            },
            color = textColor, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
        if (downloading == null && missing.isNotEmpty()) {
            TextButton(onClick = { missing.forEach { ModelManager.downloadModelInBackground(context, it) } }) {
                Text(if (failed != null) "重试" else "下载", color = accent)
            }
        }
    }
}

/** 要下载的字节数：归档按压缩后的大小（目录里的 size，如 “8.9 MB”），否则按文件合计。 */
private fun downloadBytes(model: com.kingzcheung.xime.model.ModelInfo): Long {
    val version = model.resolvedVersion() ?: return 0L
    if (version.archiveUrl != null) {
        val megabytes = version.size.substringBefore(" MB").trim().toDoubleOrNull()
        if (megabytes != null) return (megabytes * 1024 * 1024).toLong()
    }
    return version.files.sumOf { it.sizeBytes }
}
