package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NumberKeyboardGeometryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun t9NumbersKeepTextFooterAndTopRightKeyBoundsAcrossWidthsAndHeights() {
        KeysConfigHelper.loadConfig(ApplicationProvider.getApplicationContext())
        var numbers by mutableStateOf(false)
        var window by mutableStateOf(360 to 240)
        var custom by mutableStateOf(false)
        var floating by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.5f)) {
                MaterialTheme {
                    val modifier = Modifier.requiredSize(window.first.dp, window.second.dp).testTag("body")
                    val x = if (custom) 8.dp else null
                    val y = if (custom) 12.dp else null
                    if (numbers) NumberKeyboardLayout({}, Color.Gray, Color.White, Color.DarkGray,
                        modifier = modifier, keySpacingX = x, keySpacingY = y, isFloatingMode = floating)
                    else T9KeyboardLayout({}, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                        KeyboardUiState(currentSchemaId = "t9_pinyin"), remember { T9InputController() },
                        Color.Gray, Color.White, Color.DarkGray, modifier = modifier,
                        keySpacingX = x, keySpacingY = y, isFloatingMode = floating)
                }
            }
        }
        fun bounds(tag: String): Rect {
            val body = rule.onNodeWithTag("body", true).fetchSemanticsNode().boundsInRoot
            return rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot.translate(-body.topLeft)
        }
        for (size in listOf(280 to 180, 360 to 260, 360 to 600, 800 to 420))
            for (explicit in listOf(false, true)) {
                rule.runOnIdle { window = size; custom = explicit; floating = size.first == 280; numbers = false }
                val before = listOf("mode-slot-1", "mode-slot-2", "t9-delete-key").map(::bounds)
                rule.runOnIdle { numbers = true }
                val after = listOf("mode-slot-1", "mode-slot-2", "number-at-key").map(::bounds)
                before.zip(after).forEach { (a, b) ->
                    assertEquals(a.left, b.left, 1f); assertEquals(a.top, b.top, 1f)
                    assertEquals(a.right, b.right, 1f); assertEquals(a.bottom, b.bottom, 1f)
                }
            }
    }
}
