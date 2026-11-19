package com.kingzcheung.xime.ui.caption

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Sentences of one speaker closer than this become one block on the track. */
private const val BLOCK_GAP_MS = 1_500L
/** A monologue is cut into blocks of about one screen width, so a block's start is never far off screen. */
private const val BLOCK_MAX_CHARS = 36
/** Silence longer than this is drawn as this long: when nobody talks, the timeline does not move. */
private const val MAX_GAP_MS = 1_500L
/** After the finger has rested this long, the view returns to the newest words. */
private const val IDLE_RETURN_MS = 2_000L
private val LABEL_WIDTH = 150.dp
private val BLOCK_SPACING = 10.dp
private val TRACK_SPACING = 10.dp

private class Block(val turn: Turn, val startMs: Long, val endMs: Long)

private fun blocksByTrack(controller: CaptionController): Pair<Map<Int, List<Block>>, List<Block>> {
    val tracks = HashMap<Int, MutableList<Block>>()
    val live = ArrayList<Block>()
    var open: MutableList<SegmentUi>? = null
    var openSpeaker: Int? = null
    var openEnd = 0L
    fun close() {
        val segs = open ?: return
        val turn = Turn(segs.first().id, controller.speakers.firstOrNull { it.id == openSpeaker }, segs)
        val block = Block(turn, segs.first().wallMs, segs.maxOf { it.wallMs + it.durationMs })
        if (openSpeaker == null) live.add(block) else tracks.getOrPut(openSpeaker!!) { ArrayList() }.add(block)
        open = null
    }
    for (line in controller.segments) {
        if (line.text.isEmpty()) continue
        val speaker = line.speakerId
        if (speaker != null && controller.speakers.any { it.id == speaker && it.ignored }) continue
        val joins = open != null && speaker == openSpeaker && line.wallMs - openEnd < BLOCK_GAP_MS &&
            open!!.sumOf { it.text.length } + line.text.length <= BLOCK_MAX_CHARS
        if (!joins) { close(); open = ArrayList(); openSpeaker = speaker }
        open!!.add(line)
        openEnd = line.wallMs + line.durationMs
    }
    close()
    return tracks to live
}

/** Per-segment shift that squeezes long silences, so speech stays put while the room is quiet. */
private fun silenceShift(segments: List<SegmentUi>): Map<Long, Long> {
    val shift = HashMap<Long, Long>()
    var removed = 0L
    var spokenUntil = Long.MIN_VALUE
    for (line in segments.sortedBy { it.wallMs }) {
        if (spokenUntil != Long.MIN_VALUE) {
            val gap = line.wallMs - spokenUntil
            if (gap > MAX_GAP_MS) removed += gap - MAX_GAP_MS
        }
        shift[line.id] = removed
        spokenUntil = maxOf(spokenUntil, line.wallMs + line.durationMs)
    }
    return shift
}

/**
 * Live timeline, left to right: one single-line track per speaker, each block placed at the time
 * it was said and as wide as its text. New speech scrolls into view smoothly. A reader who drags
 * back stays there while touching; once the finger has rested for [IDLE_RETURN_MS] the view
 * glides back to the newest words, and "回到最新" does the same at any time. Tracks scroll
 * vertically only on a clearly vertical drag, and sit centred when they do not fill the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CaptionTimeline(controller: CaptionController, fontSp: Int, onSpeakerTap: (SpeakerUi) -> Unit, onBlockLongPress: (Turn) -> Unit) {
    val (tracks, live) = blocksByTrack(controller)
    val speakers = controller.speakers.filter { !it.ignored && tracks.containsKey(it.id) }
    val hidden = controller.segments.count { line -> line.text.isNotEmpty() && controller.speakers.any { it.id == line.speakerId && it.ignored } }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val style = TextStyle(fontSize = fontSp.sp)
    val lineHeight = with(density) { (fontSp * 1.3f).sp.toDp() }
    val trackHeight = lineHeight + 22.dp
    val dpPerSecond = (fontSp * 2.4f).dp
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    var touching by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var returnRequest by remember { mutableIntStateOf(0) }
    val atEnd by remember { derivedStateOf { horizontal.value >= horizontal.maxValue - 8 } }
    val slop = LocalViewConfiguration.current.touchSlop

    if (speakers.isEmpty() && live.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("等对方说话，字会出现在这里。", fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp))
        }
        return
    }

    // Layout in time order, never overlapping, one line per block.
    val segments = controller.segments.filter { it.text.isNotEmpty() }
    val origin = segments.minOfOrNull { it.wallMs } ?: 0L
    val shift = silenceShift(segments)
    fun x(ms: Long, segmentId: Long): Dp = dpPerSecond * ((ms - origin - (shift[segmentId] ?: 0L)) / 1000f)
    // One layout for all tracks in time order. A block is wider than the time it took (text runs
    // faster than 2.4 em per second), so a long monologue pushes its track right; that push is
    // carried as a drift applied to every later block on every track. Otherwise the next speaker's
    // words would sit at their raw time, far to the left of what was said before (1.13.7 bug).
    val trackOf = HashMap<Block, Int?>()
    for ((id, list) in tracks) for (b in list) trackOf[b] = id
    for (b in live) trackOf[b] = null
    val placed = HashMap<Block, Pair<Dp, Dp>>()
    val cursors = HashMap<Int?, Dp>()
    var drift = 0.dp
    var previousLeft = 0.dp
    for (block in trackOf.keys.sortedBy { it.startMs }) {
        val text = block.turn.text + if (block.turn.live) "…" else ""
        val textWidth = with(density) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp() }
        val width = maxOf(textWidth + 36.dp, (fontSp * 2).dp)
        val timed = x(block.startMs, block.turn.segments.first().id)
        val track = trackOf[block]
        val left = maxOf(timed + drift, cursors[track] ?: 0.dp, previousLeft)
        drift = left - timed
        previousLeft = left
        cursors[track] = left + width + BLOCK_SPACING
        placed[block] = left to width
    }
    fun layout(blocks: List<Block>): List<Triple<Block, Dp, Dp>> = blocks.map { b -> placed.getValue(b).let { (l, w) -> Triple(b, l, w) } }
    val laid = speakers.associate { it.id to layout(tracks[it.id].orEmpty()) }
    val liveLaid = layout(live)
    val end = ((laid.values.flatten() + liveLaid).maxOfOrNull { it.second + it.third } ?: 0.dp) + 24.dp

    // New content: glide to it when following, or when the finger has rested ≥ 2 s. One glide runs
    // until it has caught up; restarting it on every partial result (every ~100 ms) cancelled each
    // animation before it moved, which is why the view never came back (1.13.5).
    val scope = rememberCoroutineScope()
    var glide by remember { mutableStateOf<Job?>(null) }
    var following by remember { mutableStateOf(true) }
    fun idle() = !touching && System.currentTimeMillis() - lastTouch >= IDLE_RETURN_MS
    fun catchUp() {
        if (glide?.isActive == true) return
        glide = scope.launch {
            while (!touching && horizontal.value < horizontal.maxValue - 1) {
                horizontal.animateScrollTo(horizontal.maxValue, tween(280, easing = LinearOutSlowInEasing))
            }
            following = true
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { horizontal.maxValue }.collect { if (following || idle()) catchUp() }
    }
    // A tap that leaves the view at the end (rename, long press) keeps following.
    LaunchedEffect(touching) { if (touching) { following = false; glide?.cancel() } else if (atEnd) following = true }
    // The finger has let go and rested 2 s away from the end: return without waiting for new speech.
    LaunchedEffect(touching, lastTouch) {
        if (touching) return@LaunchedEffect
        delay(IDLE_RETURN_MS)
        if (!touching) catchUp()
    }
    LaunchedEffect(returnRequest) { if (returnRequest > 0) catchUp() }

    BoxWithConstraints(Modifier.fillMaxSize()
        // Watch every touch without consuming it (for the 2 s rule). A clearly vertical drag —
        // 3× the touch slop and twice as vertical as horizontal — scrolls the speaker tracks.
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                var dx = 0f
                var dy = 0f
                var verticalDrag = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val delta = change.position - change.previousPosition
                    dx += delta.x; dy += delta.y
                    if (!verticalDrag && abs(dy) > slop * 3 && abs(dy) > abs(dx) * 2) verticalDrag = true
                    if (verticalDrag) { vertical.dispatchRawDelta(-delta.y); change.consume() }
                }
                touching = false
                lastTouch = System.currentTimeMillis()
            }
        }) {
        val boxHeight = maxHeight
        val boxWidth = maxWidth
        val showLive = liveLaid.isNotEmpty()
        val rows = speakers.size + if (showLive) 1 else 0
        val contentHeight = trackHeight * rows + TRACK_SPACING * (rows - 1) + if (hidden > 0) 28.dp else 0.dp
        Column(Modifier.fillMaxSize().verticalScroll(vertical, enabled = false)) {
            // Centred when the tracks do not fill the page.
            Spacer(Modifier.height(((boxHeight - contentHeight) / 2).coerceAtLeast(0.dp)))
            if (hidden > 0) Text("（电视声 ×$hidden 已折叠）", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(LABEL_WIDTH), verticalArrangement = Arrangement.spacedBy(TRACK_SPACING)) {
                    speakers.forEachIndexed { index, speaker -> TrackLabel(speaker, index, trackHeight, onSpeakerTap) }
                    if (showLive) Box(Modifier.height(trackHeight).fillMaxWidth().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Text("正在说", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Long-press the words to select and copy them; a tap on a block asks who really said it.
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                Box(Modifier.horizontalScroll(horizontal)) {
                    Column(Modifier.width(maxOf(end, boxWidth - LABEL_WIDTH)), verticalArrangement = Arrangement.spacedBy(TRACK_SPACING)) {
                        speakers.forEachIndexed { index, speaker ->
                            Box(Modifier.fillMaxWidth().height(trackHeight)) {
                                for ((block, left, width) in laid[speaker.id].orEmpty()) {
                                    TimelineBlock(block.turn, left, width, trackHeight, speakerAccent(speaker.colorIndex), fontSp, onBlockLongPress)
                                }
                            }
                        }
                        if (showLive) Box(Modifier.fillMaxWidth().height(trackHeight)) {
                            for ((block, left, width) in liveLaid) {
                                TimelineBlock(block.turn, left, width, trackHeight, MaterialTheme.colorScheme.outline, fontSp, null)
                            }
                        }
                    }
                }
                }
            }
        }
        // Only when the reader has moved away; new words arriving must not flash it (1.13.10).
        AnimatedVisibility(visible = !following && !atEnd, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            CaptionButton("回到最新", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, emphasis = CaptionEmphasis.Primary) { returnRequest++ }
        }
    }
}

/**
 * Speaker accents stay distinct hues (colour-blind safe) whatever the app theme: a monochrome
 * theme maps primary/secondary/tertiary to the same grey, which made every speaker look alike.
 * Everything else on the page uses the theme.
 */
@Composable
internal fun speakerAccent(index: Int): Color = speakerColor(index)

@Composable
private fun TrackLabel(speaker: SpeakerUi, index: Int, height: Dp, onTap: (SpeakerUi) -> Unit) {
    Surface(onClick = { onTap(speaker) }, modifier = Modifier.height(height).fillMaxWidth().padding(end = 8.dp),
        shape = RoundedCornerShape(CaptionStyle.Corner), color = MaterialTheme.colorScheme.surfaceContainerHigh.translucent(.86f)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(speakerAccent(speaker.colorIndex)))
            Spacer(Modifier.width(8.dp))
            Text(speaker.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineBlock(turn: Turn, left: Dp, width: Dp, height: Dp, accent: Color, fontSp: Int, onLongPress: ((Turn) -> Unit)?) {
    val time = remember(turn.key) { clock.format(Date(turn.segments.first().wallMs)) }
    Row(Modifier.offset(x = left).width(width).height(height)
        .clip(RoundedCornerShape(CaptionStyle.Corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh.translucent(.86f))
        .combinedClickable(onClick = { onLongPress?.invoke(turn) })) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp), contentAlignment = Alignment.CenterStart) {
            ProgressiveText(turn.segments, fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, softWrap = false, suffix = if (turn.live) "…" else "")
            Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp))
        }
    }
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
