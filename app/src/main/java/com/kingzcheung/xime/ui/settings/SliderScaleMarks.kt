package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.i18n.Text
import kotlin.math.roundToInt

/** 滑条下的一个刻度：[value] 是滑条上的值，[label] 画在刻度线下，[note] 再写一行小字（如「默认」）。 */
internal data class SliderMark(val value: Float, val label: String, val note: String? = null)

/**
 * 滑条下面的刻度尺（用户 11-22：「候选字大小没有任何刻度，我甚至都不知道原来默认在哪里」）。
 * 刻度线按值在轨道上的位置画；[highlight] 那一格（默认值）用主题色加粗。
 * [trackInset]：滑条两端的值（最小、最大）离组件边缘多远。平板实测（11-22，300dpi）12 和 44 两个端点的刻点离边缘约 10dp，
 * 原来按 2dp 算，19 默认那一格偏了约 5dp。
 */
@Composable
internal fun SliderScaleMarks(
    range: ClosedFloatingPointRange<Float>,
    marks: List<SliderMark>,
    highlight: Float?,
    modifier: Modifier = Modifier,
    trackInset: Dp = 10.dp,
) {
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    fun fraction(v: Float) = ((v - range.start) / span).coerceIn(0f, 1f)
    val tickHeight = 6.dp
    val gap = 2.dp
    Layout(
        content = {
            marks.forEach { mark ->
                val on = mark.value == highlight
                val color = if (on) accent else normal
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        mark.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = if (on) FontWeight.SemiBold else null,
                        textAlign = TextAlign.Center,
                    )
                    if (mark.note != null) {
                        Text(
                            mark.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = if (on) FontWeight.SemiBold else null,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val inset = trackInset.toPx()
                val track = (size.width - 2 * inset).coerceAtLeast(1f)
                marks.forEach { mark ->
                    val on = mark.value == highlight
                    val x = inset + track * fraction(mark.value)
                    drawLine(
                        color = if (on) accent else normal.copy(alpha = 0.6f),
                        start = Offset(x, 0f),
                        end = Offset(x, (if (on) tickHeight + 2.dp else tickHeight).toPx()),
                        strokeWidth = (if (on) 2.dp else 1.dp).toPx(),
                    )
                }
            },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val inset = trackInset.toPx()
        val track = (width - 2 * inset).coerceAtLeast(1f)
        val top = (tickHeight + 2.dp + gap).roundToPx()
        val height = top + (placeables.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val cx = inset + track * fraction(marks[i].value)
                val x = (cx - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.place(x, top)
            }
        }
    }
}
