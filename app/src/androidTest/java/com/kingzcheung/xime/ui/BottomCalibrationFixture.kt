package com.kingzcheung.xime.ui

/**
 * Isolated IME tests save and restore every bottom-calibration value, including values first created by a test.
 * Padding and lowering are stored per orientation (suffix @portrait / @landscape, #329) and fall back to the
 * unsuffixed value; plus the 「底部位置」 choice and the edge-to-edge switch it writes.
 */
internal val bottomCalibrationFixtureKeys = listOf(
    "keyboard_bottom_padding_dp", "keyboard_bottom_lowering_dp", "keyboard_bottom_edge_lowering_dp",
).flatMap { listOf(it, "$it@portrait", "$it@landscape") } + listOf(
    "keyboard_bottom_lowering_limit_dp", "keyboard_bottom_position", "edge_to_edge_keyboard",
)

internal fun bottomPaddingFixtureKey(landscape: Boolean) =
    "keyboard_bottom_padding_dp" + if (landscape) "@landscape" else "@portrait"
