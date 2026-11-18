package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection

/** Editor and engine ownership of a cleared, still-reversible composition. */
internal data class ClearedComposition(
    val session: Long,
    val connection: InputConnection,
    val before: String,
    val after: String,
    val revision: Long,
    val partialSegments: List<T9PartialSegment>,
    val selectedCount: Long,
    val selectedPinyin: String,
)
