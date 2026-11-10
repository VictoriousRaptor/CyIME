package com.kingzcheung.xime.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 上排数字 12、下排符号 #+，用于符号与数字共用的模式键设置。 */
internal val SymbolsAndNumbersIcon: ImageVector = ImageVector.Builder("SymbolsAndNumbers", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        // 12
        moveTo(5f, 5f); lineTo(7f, 3f); lineTo(7f, 10f)
        moveTo(14f, 4f); curveTo(14f, 2f, 20f, 2f, 20f, 5f)
        curveTo(20f, 7f, 14f, 8f, 14f, 10f); lineTo(20f, 10f)
        // #+
        moveTo(6f, 14f); lineTo(4f, 21f)
        moveTo(10f, 14f); lineTo(8f, 21f)
        moveTo(3.5f, 16f); lineTo(11f, 16f)
        moveTo(3f, 19f); lineTo(10.5f, 19f)
        moveTo(14f, 17.5f); lineTo(21f, 17.5f)
        moveTo(17.5f, 14f); lineTo(17.5f, 21f)
    }
}.build()

/**
 * 分体键盘：左右两半键盘，中间留缝。照 Material TwoTone 的画法（2 单位粗的外框、30% 浓度的底、实心键帽），
 * 和设置里其他 TwoTone 图标放在一起是同一种风格；颜色由 Icon 的 tint 决定。
 */
internal val SplitKeyboardIcon: ImageVector = ImageVector.Builder("SplitKeyboard", 24.dp, 24.dp, 24f, 24f).apply {
    // 两半的浅色底。
    path(fill = SolidColor(Color.Black), fillAlpha = 0.3f) {
        moveTo(2f, 6f); lineTo(10f, 6f); lineTo(10f, 18f); lineTo(2f, 18f); close()
        moveTo(14f, 6f); lineTo(22f, 6f); lineTo(22f, 18f); lineTo(14f, 18f); close()
    }
    // 两半的外框。
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
        roundRect(2f, 6f, 10f, 18f, 1.5f)
        roundRect(14f, 6f, 22f, 18f, 1.5f)
    }
    // 每半两排字母键和一段空格键。
    path(fill = SolidColor(Color.Black)) {
        for (left in listOf(3.9f, 15.9f)) {
            for (top in listOf(8f, 10.8f)) {
                rect(left, top, left + 1.8f, top + 1.8f)
                rect(left + 2.4f, top, left + 4.2f, top + 1.8f)
            }
            rect(left, 14f, left + 4.2f, 15.6f)
        }
    }
}.build()

private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top); lineTo(right, top); lineTo(right, bottom); lineTo(left, bottom); close()
}

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, corner: Float) {
    moveTo(left + corner, top)
    lineTo(right - corner, top)
    arcTo(corner, corner, 0f, false, true, right, top + corner)
    lineTo(right, bottom - corner)
    arcTo(corner, corner, 0f, false, true, right - corner, bottom)
    lineTo(left + corner, bottom)
    arcTo(corner, corner, 0f, false, true, left, bottom - corner)
    lineTo(left, top + corner)
    arcTo(corner, corner, 0f, false, true, left + corner, top)
    close()
}
