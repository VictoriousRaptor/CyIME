package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.CompositingStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 固定键盘外层：不透明、不挖缝时不画离屏图；半透明或挖中缝照旧离屏。 */
class KeyboardLayerCompositingTest {
    @Test fun opaqueKeyboardDrawsDirectly() {
        assertEquals(CompositingStrategy.Auto, dockedKeyboardCompositing(1f, cutsGap = false))
    }

    @Test fun translucentKeyboardStillFadesAsOneLayer() {
        assertEquals(CompositingStrategy.Offscreen, dockedKeyboardCompositing(0.8f, cutsGap = false))
        assertEquals(CompositingStrategy.Offscreen, dockedKeyboardCompositing(0f, cutsGap = false))
    }

    @Test fun splitGapCutStillClearsOnlyTheKeyboardLayer() {
        assertEquals(CompositingStrategy.Offscreen, dockedKeyboardCompositing(1f, cutsGap = true))
    }

    /** 源码检查：宿主给固定键盘的外层走这条规则，不再写死离屏。 */
    @Test fun serviceUsesTheRuleForTheDockedKeyboard() {
        val source = java.io.File(sourceRoot(), "service/XimeInputMethodService.kt").readText()
        val start = source.indexOf("modifier = if (state.isFloatingMode || state.showKeyboardResize) Modifier")
        check(start >= 0)
        val block = source.substring(start, start + 600)
        assert(block.contains("dockedKeyboardCompositing(")) { block }
        assert(!block.contains("compositingStrategy = CompositingStrategy.Offscreen")) { block }
    }

    private fun sourceRoot(): java.io.File = listOf("src/main/java/com/kingzcheung/xime",
        "app/src/main/java/com/kingzcheung/xime").map { java.io.File(it) }.first { it.isDirectory }
}
