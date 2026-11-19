package com.kingzcheung.xime.ui.caption

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How far past the newest words a pull must go: long on purpose, so reading back never triggers it. */
internal val NEW_SEGMENT_PULL = 240.dp
/** The bar appears only after this share of the pull, so ordinary scrolling shows nothing. */
private const val SHOW_FROM = .15f

/**
 * 「开始新一段」 by pulling the captions well past the newest words: up in the portrait chat, towards
 * the left (pulling from the right) on the landscape timeline (user 2026-10-27). A bar fills as the
 * pull goes on; when full it says to let go, and letting go starts the new segment. Letting go
 * earlier, or pulling back, does nothing.
 */
@Composable
internal fun NewSegmentPull(modifier: Modifier, onNewSegment: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val limit = with(LocalDensity.current) { NEW_SEGMENT_PULL.toPx() }
    val haptic = LocalHapticFeedback.current
    var pulled by remember { mutableFloatStateOf(0f) }
    val connection = remember(limit) {
        object : NestedScrollConnection {
            // Pulling back first takes away from the pull before the list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pulled <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val back = maxOf(available.x, available.y)
                if (back <= 0f) return Offset.Zero
                val used = minOf(back, pulled)
                pulled -= used
                return if (available.y >= available.x) Offset(0f, used) else Offset(used, 0f)
            }
            // What the list could not scroll any more (already at the newest) adds to the pull.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val push = -minOf(available.x, available.y)
                if (push <= 0f) return Offset.Zero
                val before = pulled
                pulled = minOf(limit * 1.2f, pulled + push)
                if (before < limit && pulled >= limit) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                return if (available.y <= available.x) Offset(0f, available.y) else Offset(available.x, 0f)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val done = pulled >= limit
                pulled = 0f
                if (done) onNewSegment()
                return Velocity.Zero
            }
        }
    }
    Box(modifier.nestedScroll(connection)) {
        content()
        val progress = (pulled / limit).coerceIn(0f, 1f)
        AnimatedVisibility(progress > SHOW_FROM, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val ready = progress >= 1f
                    Text(if (ready) "松手，开始新的一段" else "继续拉，开始新的一段", fontSize = 20.sp,
                        fontWeight = if (ready) FontWeight.Bold else FontWeight.Medium,
                        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(.7f).height(8.dp))
                    Text("现在的字幕会存好，屏幕清空后接着听", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
