package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/*
 * 设置里的底部弹出面板（ModalBottomSheet）一律用这两样，不用 Material3 默认的边距。
 *
 * Material3 1.4 默认把状态栏高度当成面板里面的顶部留白，而且面板越往上留白越多（面板位置以上的部分算已让出）。
 * 内容高度落在「屏高减状态栏」到「屏高」之间时，面板高度跟着位置变，停靠点也跟着变，找不到停得住的位置：
 * 打开时一路往上升到状态栏底下；往下拉时停靠点每帧重算、面板每帧被拉回去，不跟手指，松手又按甩动速度直接关掉。
 * 「透明度与模糊」面板（预览 + 三条滑条）在大字号手机上正好落在这个区间（11-22 录屏）。
 *
 * 改法：状态栏高度放到面板外面（面板最高到状态栏下沿），面板里只留底部（导航栏、手势条）边距。
 * 面板高度从此和位置无关，停靠点固定：内容矮就停在内容高度，内容高就顶到状态栏下沿、里面自己滚动。
 */

/** 放在 ModalBottomSheet 的 modifier 上：`Modifier.windowInsetsPadding(SheetOuterInsets)`，面板最高到状态栏下沿。 */
internal val SheetOuterInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

/** 给 ModalBottomSheet 的 contentWindowInsets：只留底部边距，顶部已经在面板外面让过了。 */
internal val sheetContentInsets: @Composable () -> WindowInsets =
    { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) }
