package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import com.kingzcheung.xime.ui.theme.BackgroundImageSlot
import com.kingzcheung.xime.ui.theme.BackgroundMotionPath
import com.kingzcheung.xime.ui.theme.KeyboardBackgroundMotion
import com.kingzcheung.xime.ui.theme.LocalGlassTopFeather
import com.kingzcheung.xime.ui.theme.drawUnderTopFeather

/**
 * 动态背景时键盘本体的背景图：放在键盘内容最底下的一层，图由 [slot] 提供（keyboardBackground 在原来的位置不再画图）。
 *
 * 每帧只在 graphicsLayer 块里读 [motion] 改这层的缩放和位移，不重组、不重画：按键、候选、键盘自己的底色都不动，
 * 渲染线程把这层的图按新的位置合成一遍。外面一层不跟着变形，负责裁边、分体中缝挖空和玻璃的柔化上沿，
 * 和原来画在键盘本体上的效果一样。
 */
@Composable
internal fun BoxScope.KeyboardBackgroundMotionLayer(
    slot: BackgroundImageSlot,
    motion: KeyboardBackgroundMotion,
    splitGap: SplitKeyboardGap?,
    glass: Boolean,
    /** 悬浮键盘：往下多画到拖动条底，见 [FloatingBackgroundBleed]。 */
    bleedPx: Int = 0,
) {
    val root = LocalView.current.rootView
    val feather = LocalGlassTopFeather.current?.takeIf { glass && it.windowRoot === root }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.matchParentSize()
            .bleedBottomOut(bleedPx)
            .onGloballyPositioned { origin = it.positionInWindow() }
            .clipToBounds()
            .drawWithContent {
                val featherTop = feather?.top?.value?.minus(origin.y)
                if (featherTop == null) drawContent()
                else drawUnderTopFeather(featherTop, feather.heightPx) { this@drawWithContent.drawContent() }
                // 与 splitGapCutout 一样清掉中缝；那边清的是图层下面的底色，这里清图层画上来的图。
                splitGap?.cutout(origin.y + size.height)?.let { cutout ->
                    cutout.translate(Offset(-origin.x, -origin.y))
                    drawPath(cutout, Color.Transparent, blendMode = BlendMode.Clear)
                }
            }
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val t = motion.transform
                    applyMotionPose(t, slot.frame() ?: Rect(Offset.Zero, size))
                }
        ) {
            // 换图时旧图垫在下面，新图在自己的一层里淡入；淡完旧图就丢掉，平时只有一层。
            if (slot.previousDraw != null) Box(Modifier.fillMaxSize().drawBehind { slot.previousDraw?.invoke(this) })
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = slot.fade.floatValue }.drawBehind { slot.draw?.invoke(this) })
        }
        // 放大、缩小走到头后渐变回起点：起点的样子在这一层淡入（平时不透明度 0，渲染线程直接跳过不画）。
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val t = motion.transform
                    val pose = t.fadeTo
                    alpha = if (pose == null) 0f else t.fadeAlpha.coerceIn(0f, 1f)
                    applyMotionPose(pose ?: t, slot.frame() ?: Rect(Offset.Zero, size))
                }
                .drawBehind { slot.draw?.invoke(this) }
        )
    }
    val fadeToken = slot.fadeToken
    LaunchedEffect(fadeToken) {
        if (slot.previousDraw == null) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            delay(BackgroundMotionPath.FRAME_INTERVAL_MS - 10)
            val progress = ((withFrameMillis { it } - start).toFloat() / SLIDE_FADE_MS).coerceIn(0f, 1f)
            slot.fade.floatValue = progress
            if (progress >= 1f) break
        }
        slot.previousDraw = null
    }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyMotionPose(
    pose: com.kingzcheung.xime.ui.theme.BackgroundMotionTransform, frame: Rect,
) {
    scaleX = pose.scale
    scaleY = pose.scale
    translationX = pose.shiftX * frame.width
    translationY = pose.shiftY * frame.height
    transformOrigin = TransformOrigin(
        if (size.width > 0f) frame.center.x / size.width else .5f,
        if (size.height > 0f) frame.center.y / size.height else .5f,
    )
}

/** 轮播换图的淡入时长。 */
private const val SLIDE_FADE_MS = 800f
