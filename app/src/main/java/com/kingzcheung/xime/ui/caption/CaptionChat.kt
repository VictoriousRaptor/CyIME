package com.kingzcheung.xime.ui.caption

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Same speaker with a pause shorter than this → one message (a longer pause starts a new one, so unpunctuated streaming text does not become one wall). */
private const val MESSAGE_GAP_MS = 1_500L
private const val MESSAGE_MAX_CHARS = 120
private const val IDLE_RETURN_MS = 2_000L

private fun messages(controller: CaptionController): List<Turn> {
    val turns = ArrayList<Turn>()
    var current = ArrayList<SegmentUi>()
    var speaker: SpeakerUi? = null
    var lastEnd = 0L
    fun flush() {
        if (current.isNotEmpty()) turns.add(Turn(current.first().id, speaker, current))
        current = ArrayList()
    }
    for (line in controller.segments) {
        if (line.text.isEmpty()) continue
        val who = line.speakerId?.let { id -> controller.speakers.firstOrNull { it.id == id } }
        if (who?.ignored == true) continue
        // 自己写的回复每句一条：连点两句不能拼成“好的是”。
        val same = current.isNotEmpty() && speaker?.id == who?.id && who?.id != CaptionController.REPLY_SPEAKER &&
            line.wallMs - lastEnd < MESSAGE_GAP_MS &&
            current.sumOf { it.text.length } + line.text.length <= MESSAGE_MAX_CHARS
        if (!same) { flush(); speaker = who }
        current.add(line)
        lastEnd = line.wallMs + line.durationMs
    }
    flush()
    return turns
}

/**
 * Portrait: a chat log. Each speaker's words are a message bubble appended at the bottom; the
 * reader's own written replies sit on the right. Following, the 2 s rest rule and "回到最新"
 * behave exactly like the landscape timeline; the button shows only after the reader has moved
 * away, never because new words are arriving.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CaptionChat(controller: CaptionController, fontSp: Int, onSpeakerTap: (SpeakerUi) -> Unit, onMessageLongPress: (Turn) -> Unit) {
    val turns = messages(controller)
    val hidden = controller.segments.count { line -> line.text.isNotEmpty() && controller.speakers.any { it.id == line.speakerId && it.ignored } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var touching by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var following by remember { mutableStateOf(true) }
    var glide by remember { mutableStateOf<Job?>(null) }
    var returnRequest by remember { mutableIntStateOf(0) }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    val count = turns.size + if (hidden > 0) 1 else 0

    fun idle() = !touching && System.currentTimeMillis() - lastTouch >= IDLE_RETURN_MS
    fun catchUp() {
        if (glide?.isActive == true || count == 0) return
        glide = scope.launch {
            while (!touching && list.canScrollForward) list.animateScrollToItem(count - 1, Int.MAX_VALUE / 2)
            following = true
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { list.layoutInfo.totalItemsCount to (turns.lastOrNull()?.text?.length ?: 0) }
            .collect { if (following || idle()) catchUp() }
    }
    LaunchedEffect(count, turns.lastOrNull()?.text) { if (following || idle()) catchUp() }
    // The newest bubble grows as its words are written out, after the text itself arrived.
    val newest = turns.lastOrNull()?.segments
    LaunchedEffect(newest?.firstOrNull()?.id, newest?.size) {
        val lines = newest ?: return@LaunchedEffect
        snapshotFlow { lines.sumOf { it.reveal.shown } }.collect { if (following || idle()) catchUp() }
    }
    LaunchedEffect(touching) {
        if (touching) { following = false; glide?.cancel() } else if (atBottom) following = true
    }
    LaunchedEffect(touching, lastTouch) {
        if (touching) return@LaunchedEffect
        delay(IDLE_RETURN_MS)
        if (!touching) catchUp()
    }
    LaunchedEffect(returnRequest) { if (returnRequest > 0) catchUp() }

    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: break
                if (!change.pressed) break
            }
            touching = false
            lastTouch = System.currentTimeMillis()
        }
    }) {
        if (turns.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("等对方说话，字会出现在这里。", fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val bubbleMax = maxWidth * .86f
            // Long-press any words to select and copy them, like other apps' text (user 2026-10-27).
            androidx.compose.foundation.text.selection.SelectionContainer {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hidden > 0) item(key = "hidden") {
                    Text("（电视声 ×$hidden 已折叠）", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(turns.size, key = { turns[it].key }) { index ->
                    val turn = turns[index]
                    val mine = turn.speaker?.id == CaptionController.REPLY_SPEAKER
                    // A new message slides in and the ones above move up smoothly instead of jumping.
                    Box(Modifier.fillMaxWidth().animateItem(fadeInSpec = androidx.compose.animation.core.tween(320),
                        placementSpec = androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
                        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Message(turn, mine, fontSp, Modifier.widthIn(max = bubbleMax), onSpeakerTap, onMessageLongPress)
                    }
                }
            }
            }
        }
        AnimatedVisibility(visible = !following && !atBottom, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            CaptionButton("回到最新", icon = Icons.Filled.KeyboardArrowDown, emphasis = CaptionEmphasis.Primary) { returnRequest++ }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Message(turn: Turn, mine: Boolean, fontSp: Int, modifier: Modifier, onSpeakerTap: (SpeakerUi) -> Unit, onLongPress: (Turn) -> Unit) {
    val speaker = turn.speaker
    val accent = speaker?.let { speakerAccent(it.colorIndex) } ?: MaterialTheme.colorScheme.outline
    val time = remember(turn.key) { clockFormat.format(Date(turn.segments.first().wallMs)) }
    Column(modifier, horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        // Name line: tap it for "这是谁？".
        Row(Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { speaker?.let(onSpeakerTap) })
            .padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(speaker?.name ?: "正在说", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                color = if (speaker == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(time, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(4.dp))
        // 气泡：半透明的主题容器色 + 键盘同款玻璃质感；自己的回话用强调色容器。
        Row(Modifier.height(IntrinsicSize.Min).clip(RoundedCornerShape(CaptionStyle.Corner))
            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.translucent(.86f))
            .visualMaterial(VisualStyles.current, CaptionStyle.Corner, level = MaterialLevel.RAISED)
            // Long-press now selects text; a tap on the bubble asks who really said it.
            .combinedClickable(onClick = { if (speaker != null) onLongPress(turn) })) {
            if (!mine) Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            ProgressiveText(turn.segments, fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp,
                color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), suffix = if (turn.live) "…" else "")
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
