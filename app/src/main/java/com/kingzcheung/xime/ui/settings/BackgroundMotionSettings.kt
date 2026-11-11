package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kingzcheung.xime.ui.theme.BackgroundMotionTransform
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Animation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.BackgroundMotionEffect
import com.kingzcheung.xime.settings.BackgroundMotionPreferences
import com.kingzcheung.xime.settings.BackgroundMotionSpeed
import com.kingzcheung.xime.settings.PerformanceCosts
import com.kingzcheung.xime.ui.theme.BackgroundMotionPath
import com.kingzcheung.xime.ui.theme.CustomImageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.ui.theme.rememberBackgroundMotionSettings
import com.kingzcheung.xime.ui.theme.withBackgroundMotion

/**
 * 主题与外观 › 动态与轮播：一个开关（默认关），开着时再选效果和速度，有「我的图片」时还能加轮播图片。
 * 只对图片背景起作用；游戏、系统省电、系统关了动画时键盘上不动（见 BackgroundMotionGate）。
 */
@Composable
internal fun ColumnScope.BackgroundMotionSettingsItems(mainImage: String?) {
    val context = LocalContext.current
    val settings = rememberBackgroundMotionSettings()
    val scope = rememberCoroutineScope()
    val slides = remember(settings.slides) { CustomImageTheme.slides(context, settings.slides) }
    val slidePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(BackgroundMotionPreferences.MAX_SLIDES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { CustomImageTheme.addSlides(context, uris) }
            if (added < uris.size) android.widget.Toast.makeText(context,
                if (added == 0 && slides.size >= BackgroundMotionPreferences.MAX_SLIDES) "最多加 ${BackgroundMotionPreferences.MAX_SLIDES} 张"
                else "有 ${uris.size - added} 张没有加上", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    SettingsToggleItem(icon = Icons.TwoTone.Animation, title = "动态背景",
        subtitle = "图片背景缓慢移动，游戏、省电和关闭动画时不动",
        checked = settings.enabled,
        onCheckedChange = { BackgroundMotionPreferences.setEnabled(context, it) },
        costItem = PerformanceCosts.Item.BACKGROUND_MOTION)
    if (!settings.enabled) return
    Column(Modifier.settingsSearchAnchor("动态效果").fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("效果", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth().choiceCardRowHeight(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackgroundMotionEffect.entries.forEach { effect ->
                IllustratedChoiceCard(effect.label, settings.effect == effect,
                    onClick = { BackgroundMotionPreferences.setEffect(context, effect) },
                    modifier = Modifier.weight(1f), tag = "background-motion-effect:${effect.name}") {
                    MotionIllustration(effect, PREVIEW_SHOT_MS)
                }
            }
        }
        Text("速度", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().choiceCardRowHeight(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackgroundMotionSpeed.entries.forEach { speed ->
                IllustratedChoiceCard(speed.label, settings.speed == speed,
                    onClick = { BackgroundMotionPreferences.setSpeed(context, speed) },
                    modifier = Modifier.weight(1f), tag = "background-motion-speed:${speed.name}") {
                    // 示意图按真实速度的比例放快，三张之间快慢的差别和键盘上一样。
                    MotionIllustration(settings.effect, speed.shotMs * PREVIEW_SHOT_MS / BackgroundMotionSpeed.NORMAL.shotMs)
                }
            }
        }
    }
    // 轮播只给「我的图片」：主图之外再加几张，每走完一圈淡入换下一张。
    if (mainImage == null) return
    SlideStrip(
        main = mainImage,
        slides = slides,
        onAdd = { slidePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemove = { src -> scope.launch(Dispatchers.IO) { CustomImageTheme.removeSlide(context, src) } },
        onClear = { scope.launch(Dispatchers.IO) { CustomImageTheme.clearSlides(context) } },
    )
}

/**
 * 轮播图片：一排小图，主图在前（不能在这里删），每张轮播图右上角一个叉，最后一格加图。
 * 原来是「轮播图片 >」和「清空轮播图片」两行字：看不到加了哪几张，也不能只去掉一张（用户 11-09）。
 */
@Composable
private fun SlideStrip(main: String?, slides: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit,
    onClear: () -> Unit) {
    Column(Modifier.settingsSearchAnchor("轮播图片").fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("轮播图片", style = MaterialTheme.typography.bodyLarge)
                Text("每轮换一张，连主图最多 ${BackgroundMotionPreferences.MAX_SLIDES + 1} 张",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (slides.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.testTag("background-slides-clear")) {
                Text("清空")
            }
        }
        Row(Modifier.padding(top = 8.dp, end = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            main?.let { SlideThumb(it, label = "主图") }
            slides.forEach { src -> SlideThumb(src, onRemove = { onRemove(src) }) }
            if (slides.size < BackgroundMotionPreferences.MAX_SLIDES) {
                Box(Modifier.size(SLIDE_THUMB_WIDTH, SLIDE_THUMB_HEIGHT).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(onClick = onAdd).semantics { contentDescription = "加轮播图片" }
                    .testTag("background-slides-add"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private val SLIDE_THUMB_WIDTH = 72.dp
private val SLIDE_THUMB_HEIGHT = 48.dp

@Composable
private fun SlideThumb(src: String, label: String? = null, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { SLIDE_THUMB_WIDTH.roundToPx() }
    val image by produceState<ImageBitmap?>(null, src, maxPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { CustomImageTheme.thumbnail(context, src, maxPx)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(SLIDE_THUMB_WIDTH, SLIDE_THUMB_HEIGHT).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).testTag("background-slide:$src")) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (label != null) Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .45f),
                RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 4.dp))
        if (onRemove != null) Box(Modifier.align(Alignment.TopEnd).size(24.dp).clickable(onClick = onRemove)
            .semantics { contentDescription = "去掉这张" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = Color.White,
                modifier = Modifier.size(16.dp).background(Color.Black.copy(alpha = .5f), CircleShape).padding(2.dp))
        }
    }
}

/** 示意图比键盘上快 6 倍，一眼看得出怎么动。 */
private const val PREVIEW_SHOT_MS = 3_000L

/**
 * 示意图的动作幅度是键盘上的 3 倍（放大到 1.36、左右各移 18%）。键盘上的 12% 放到一张小卡片里只差几个像素，
 * 放大、缩小、平移、轮换四张看起来一模一样（用户 11-09）。只改示意图，键盘上不变。
 */
private const val PREVIEW_AMPLITUDE = 3f

internal fun previewMotion(pose: BackgroundMotionTransform, amplitude: Float = PREVIEW_AMPLITUDE): BackgroundMotionTransform =
    BackgroundMotionTransform(1f + (pose.scale - 1f) * amplitude, pose.shiftX * amplitude, pose.shiftY * amplitude,
        fadeTo = pose.fadeTo?.let { previewMotion(it, amplitude) }, fadeAlpha = pose.fadeAlpha)

/** 一小张风景（天空、太阳、山）按 [effect] 动；只在设置页显示时走。 */
@Composable
private fun MotionIllustration(effect: BackgroundMotionEffect, shotMs: Long) {
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            elapsed.longValue += (now - last).coerceIn(0L, 100L)
            last = now
        }
    }
    val sky = MaterialTheme.colorScheme.primaryContainer
    val hill = MaterialTheme.colorScheme.primary
    val sun = MaterialTheme.colorScheme.tertiary
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).drawBehind {
        val frame = Rect(Offset.Zero, size)
        withBackgroundMotion(previewMotion(BackgroundMotionPath.at(effect, elapsed.longValue, shotMs)), frame) {
            drawRect(Brush.verticalGradient(listOf(sky, Color.White.copy(alpha = .6f).compositeOver(sky))))
            drawCircle(sun, radius = size.minDimension * .16f, center = Offset(size.width * .72f, size.height * .34f))
            drawPath(Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, size.height * .72f)
                lineTo(size.width * .3f, size.height * .46f)
                lineTo(size.width * .55f, size.height * .7f)
                lineTo(size.width * .8f, size.height * .52f)
                lineTo(size.width, size.height * .66f)
                lineTo(size.width, size.height)
                close()
            }, hill)
        }
    })
}
