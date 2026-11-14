package com.kingzcheung.xime.ui

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.QuickActionLayout
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class InlineQuickActionEditingTest {
    @get:Rule val rule = createComposeRule()
    private var previousInlineEditing = false

    // 菜单内编辑默认关，这里先打开，测完还原设备上的原值。
    @Before fun enableInlineEditing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        previousInlineEditing = SettingsPreferences.inlineQuickActionEditing(app)
        SettingsPreferences.setInlineQuickActionEditing(app, true)
    }

    @After fun restoreInlineEditing() {
        SettingsPreferences.setInlineQuickActionEditing(ApplicationProvider.getApplicationContext<Application>(), previousInlineEditing)
    }

    @Test fun menuEditMovesToolbarButtonAndSavesOnlyWhenConfirmed() {
        val vm = KeyboardViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.showOverlay(OverlayRoute.Menu)
        var state by mutableStateOf(KeyboardUiState(toolbarButtons = listOf("emoji", "clipboard"),
            menuButtons = listOf("settings", "sound")))
        var saved: QuickActionLayout? = null
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, state, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                    onUpdateQuickActions = { saved = it; state = state.copy(toolbarButtons = it.toolbar, menuButtons = it.menu) }),
                    Modifier.width(360.dp).height(300.dp))
            }
        }
        rule.onNodeWithContentDescription("编辑排列").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("保存排列").assertIsDisplayed()
        val source = rule.onNodeWithContentDescription("表情").fetchSemanticsNode().boundsInRoot.center
        val target = rule.onNodeWithTag("menu-item:settings").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(source); advanceEventTime(700); moveTo(target); up() }
        rule.runOnIdle { assertNull(saved) }
        rule.onNodeWithContentDescription("保存排列").performClick()
        rule.runOnIdle {
            assertEquals(listOf("clipboard"), saved?.toolbar)
            assertEquals(listOf("emoji", "settings", "sound"), saved?.menu)
        }
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
    }
}
