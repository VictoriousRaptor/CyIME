package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.CompositingStrategy

/**
 * 固定键盘最外层要不要画成一张离屏图。
 *
 * 只有两种情况必须：整块半透明（键和底色先合成再一起淡，不然重叠处更深），或分体挖中缝（Clear 只能清键盘自己那张图）。
 * 其余时候离屏图只有代价：任何一个键动一帧（按下的键帽动效每键 235 ms），渲染线程都要把整张键盘重画进离屏图再贴上屏。
 */
internal fun dockedKeyboardCompositing(opacity: Float, cutsGap: Boolean): CompositingStrategy =
    if (opacity < 1f || cutsGap) CompositingStrategy.Offscreen else CompositingStrategy.Auto
