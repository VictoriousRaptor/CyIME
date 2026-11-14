package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.ui.theme.rememberBackgroundMotionSettings
import com.kingzcheung.xime.ui.theme.withBackgroundMotion

/**
 * 背景图片 › 动态背景：一个开关（默认关），开着时再选效果和速度。
 * 只对图片背景起作用，所以只在当前主题有背景图时由「背景图片」那张卡放出来；游戏、系统省电、系统关了动画时键盘上不动（见 BackgroundMotionGate）。
 */
@Composable
internal fun ColumnScope.BackgroundMotionSettingsItems() {
    val context = LocalContext.current
    val settings = rememberBackgroundMotionSettings()
    SettingsToggleItem(icon = Icons.TwoTone.Animation, title = "动态背景",
        subtitle = "图片背景缓慢移动，游戏、省电和关闭动画时不动",
        checked = settings.enabled,
        onCheckedChange = { BackgroundMotionPreferences.setEnabled(context, it) },
        costItem = PerformanceCosts.Item.BACKGROUND_MOTION)
    if (!settings.enabled) return
    // 效果要看动画才分得清，留四张小卡，按选中的速度动：一排同时看出怎么动、多快。
    // 速度只差快慢，原来三张大卡动作一样、占一整排（用户 11-13：好多按钮没用），改成一排三个字。
    Column(Modifier.settingsSearchAnchor("动态效果").fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().choiceCardRowHeight(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackgroundMotionEffect.entries.forEach { effect ->
                IllustratedChoiceCard(effect.label, settings.effect == effect,
                    onClick = { BackgroundMotionPreferences.setEffect(context, effect) },
                    modifier = Modifier.weight(1f), tag = "background-motion-effect:${effect.name}") {
                    // 示意图按真实速度的比例放快，换速度时小图跟着变快变慢。
                    MotionIllustration(effect, settings.speed.shotMs * PREVIEW_SHOT_MS / BackgroundMotionSpeed.NORMAL.shotMs)
                }
            }
        }
        MotionSpeedRow(settings.speed) { BackgroundMotionPreferences.setSpeed(context, it) }
    }
}

/** 「速度：慢 中 快」一排；选中的描强调色边。 */
@Composable
private fun MotionSpeedRow(selected: BackgroundMotionSpeed, onSelect: (BackgroundMotionSpeed) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("速度", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 4.dp))
        BackgroundMotionSpeed.entries.forEach { speed ->
            val chosen = speed == selected
            Surface(onClick = { onSelect(speed) },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                    .testTag("background-motion-speed:${speed.name}").semantics { this.selected = chosen },
                shape = RoundedCornerShape(10.dp),
                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = BorderStroke(if (chosen) 1.5.dp else 1.dp,
                    if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                    Text(speed.label, style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * 背景图片排：第一张是主图，后面是轮流显示的图，每张右上角 ×，最后一格 ＋ 加图；下面一行小字说下一步。
 * 原来主图只有一行「已设置」看不见，轮播图在另一组最底下、要先开动态背景才出现，删除分在两处
 * （用户 11-13：离得太远，以为没设置图片，删除不知道在哪删）。
 * 点第一张弹出「调整位置」「换一张」：只有它有摆放可调；其余的图轮播时都是铺满居中。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundImageStrip(
    main: String,
    slides: List<String>,
    imageOn: Boolean,
    motionOn: Boolean,
    onAdjust: () -> Unit,
    onReplace: () -> Unit,
    onRemoveMain: () -> Unit,
    onAdd: () -> Unit,
    onRemoveSlide: (String) -> Unit,
) {
    Column(Modifier.settingsSearchAnchor("轮播图片").fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        // 一行四张、全部摊开，不横向滚动：最多 9 张加一格 ＋ 三行内看全，＋ 总在看得见的地方。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileWidth = (maxWidth - THUMB_GAP * (THUMBS_PER_ROW - 1)) / THUMBS_PER_ROW
            val tileHeight = tileWidth * 2 / 3
            FlowRow(horizontalArrangement = Arrangement.spacedBy(THUMB_GAP),
                verticalArrangement = Arrangement.spacedBy(THUMB_GAP), maxItemsInEachRow = THUMBS_PER_ROW) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    SlideThumb(main, tileWidth, tileHeight, label = "第一张", onClick = { menu = true },
                        onRemove = onRemoveMain, clickLabel = "调整或换一张")
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("调整位置") }, onClick = { menu = false; onAdjust() },
                            modifier = Modifier.testTag("background-image-adjust"))
                        DropdownMenuItem(text = { Text("换一张") }, onClick = { menu = false; onReplace() },
                            modifier = Modifier.testTag("background-image-replace"))
                    }
                }
                slides.forEach { src -> SlideThumb(src, tileWidth, tileHeight, onRemove = { onRemoveSlide(src) }) }
                if (slides.size < BackgroundMotionPreferences.MAX_SLIDES) {
                    Box(Modifier.size(tileWidth, tileHeight).clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .clickable(onClick = onAdd).semantics { contentDescription = "加图片" }
                        .testTag("background-slides-add"), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        SettingsJumpText(backgroundImageHint(slides.size + 1, imageOn, motionOn), jumps = listOf("动态背景"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).testTag("background-image-hint"))
    }
}

/** 图片排下面那行字：按现在的状态只说下一步该做什么。 */
internal fun backgroundImageHint(count: Int, imageOn: Boolean, motionOn: Boolean): String = when {
    !imageOn -> "上面的开关关着：键盘用配色方案的底色，这些图片留着"
    count < 2 -> "点图片调整位置或换一张；点 ＋ 加几张，轮流显示"
    motionOn -> "共 $count 张，背景每动完一圈换下一张；点第一张调整位置"
    else -> "共 $count 张，打开下面的「动态背景」才会轮流换"
}

private const val THUMBS_PER_ROW = 4
private val THUMB_GAP = 8.dp

@Composable
private fun SlideThumb(src: String, width: Dp, height: Dp, label: String? = null, onClick: (() -> Unit)? = null,
    onRemove: () -> Unit, clickLabel: String? = null) {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { height.roundToPx() }
    val image by produceState<ImageBitmap?>(null, src, maxPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { CustomImageTheme.thumbnail(context, src, maxPx)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(width, height).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier)
        .testTag("background-slide:$src")) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (label != null) Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .45f),
                RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 4.dp))
        // 叉的点按范围 32dp：小图上也好点中，不会误点到图片本身。
        Box(Modifier.align(Alignment.TopEnd).size(32.dp).clickable(onClick = onRemove)
            .semantics { contentDescription = "删掉这张" }.testTag("background-slide-remove:$src"),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = Color.White,
                modifier = Modifier.size(20.dp).background(Color.Black.copy(alpha = .55f), CircleShape).padding(2.dp))
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
