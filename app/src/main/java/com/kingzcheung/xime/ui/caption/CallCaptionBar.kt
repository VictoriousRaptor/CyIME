package com.kingzcheung.xime.ui.caption

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Why the bar is not listening: the call is on the earpiece or a headset. */
internal enum class CallRoute { Speaker, Earpiece, Headset }

private const val CALL_FONT = 24
private const val CALL_LINES = 3

/**
 * The subtitle bar shown during calls. Only the other party is captioned: the bar listens only while
 * the call is on speakerphone, and the phone holder's remembered voice ("这是我") never shows. New
 * words fade in one by one and older lines glide up; nothing jumps.
 */
@Composable
internal fun CallCaptionBar(controller: CaptionController, route: CallRoute, onDrag: (Float) -> Unit, onClose: () -> Unit,
    /** The caption page's captions floating over other apps (「后台浮窗」), not a call. */
    floating: Boolean = false, onOpen: (() -> Unit)? = null) {
    var choosing by remember { mutableStateOf<SpeakerUi?>(null) }
    // Answered "不是" once: tapping that voice again does not ask again.
    val notSelf = remember { mutableStateListOf<Int>() }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .66f))
        .pointerInput(Unit) { detectVerticalDragGestures { change, dy -> change.consume(); onDrag(dy) } }
        // Floating: a tap on the words goes back to the full caption page.
        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
        .padding(start = 16.dp, end = 44.dp, top = 10.dp, bottom = 10.dp)) {
        Column {
            val hint = when (route) {
                CallRoute.Earpiece -> "开免提后，这里会显示对方说的话"
                CallRoute.Headset -> "戴着耳机时手机听不到对方，请改用免提"
                CallRoute.Speaker -> when {
                    controller.status == CaptionController.Status.Error || controller.status == CaptionController.Status.Held -> controller.message
                    floating && controller.status == CaptionController.Status.Paused -> "字幕已暂停，点这里回到字幕页"
                    controller.micTaken -> "麦克风被别的应用占着（比如带声音的录屏），这会儿听不到对方"
                    else -> null
                }
            }
            if (hint != null) Text(hint, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp)
            else CallLines(controller, floating, onSpeakerTap = { if (it.id !in notSelf) choosing = it })
            choosing?.let { speaker ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("这个颜色的话是你自己说的吗？", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    BarButton("是我，不显示") { controller.markSelf(speaker.id); choosing = null }
                    BarButton("不是") { notSelf += speaker.id; choosing = null }
                }
            }
        }
        Text("×", color = Color.White, fontSize = 24.sp, modifier = Modifier.align(Alignment.TopEnd).padding(start = 8.dp)
            .clickable(onClick = onClose).padding(horizontal = 6.dp))
    }
}

@Composable
private fun CallLines(controller: CaptionController, floating: Boolean, onSpeakerTap: (SpeakerUi) -> Unit) {
    RevealTicker(controller)
    val lines = controller.segments.filter { line ->
        line.text.isNotEmpty() && line.speakerId != CaptionController.SELF_SPEAKER &&
            controller.speakers.none { it.id == line.speakerId && it.ignored }
    }.takeLast(8)
    val list = rememberLazyListState()
    val lineHeight = (CALL_FONT * 1.3f)
    if (lines.isEmpty()) {
        Text(if (floating) "字幕在后台继续，说的话会显示在这里" else "通话字幕已开启，对方说的话会显示在这里",
            color = Color.White.copy(alpha = .8f), fontSize = 20.sp)
        return
    }
    val newest = lines.last()
    LaunchedEffect(lines.size, newest.id, newest.reveal.shown) { list.animateScrollToItem(lines.lastIndex, Int.MAX_VALUE / 2) }
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height((lineHeight * CALL_LINES + 8).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), userScrollEnabled = false) {
        items(lines.size, key = { lines[it].id }) { index ->
            val line = lines[index]
            val speaker = controller.speakers.firstOrNull { it.id == line.speakerId }
            CallLine(line, speaker?.let { speakerColor(it.colorIndex) }, lineHeight,
                Modifier.animateItem(fadeInSpec = tween(320), placementSpec = tween(360, easing = FastOutSlowInEasing)),
                onMarkTap = { speaker?.let(onSpeakerTap) })
        }
    }
}

/**
 * One caption line. Who spoke is a colour mark, not a "第 x 个人：" label that took half a portrait
 * line (user, 2026-10-24). The mark is the only tap target, so a tap on the words asks nothing.
 */
@Composable
internal fun CallLine(line: SegmentUi, mark: Color?, lineHeight: Float, modifier: Modifier = Modifier, onMarkTap: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (mark != null) {
            Box(Modifier.size(28.dp, lineHeight.dp).clickable(onClick = onMarkTap), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(mark))
            }
            Spacer(Modifier.width(4.dp))
        }
        ProgressiveText(listOf(line), fontSize = CALL_FONT.sp, lineHeight = lineHeight.sp, color = Color.White,
            suffix = if (!line.confirmed) "…" else "")
    }
}

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp))
        .background(Color.White.copy(alpha = .22f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}
