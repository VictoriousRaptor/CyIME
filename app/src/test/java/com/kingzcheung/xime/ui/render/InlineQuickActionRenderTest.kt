package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.QuickActionLayout
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineQuickActionRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // 菜单内编辑默认关，这组测试测的是打开后的行为。
    @Before fun enableInlineEditing() {
        SettingsPreferences.setInlineQuickActionEditing(ApplicationProvider.getApplicationContext<Application>(), true)
    }

    @Test fun emptyMenuStillAllowsEditingAndRestoringHiddenItems() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        vm.showOverlay(OverlayRoute.Menu)
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}, onUpdateQuickActions = {})
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, KeyboardUiState(toolbarButtons = listOf("emoji"), menuButtons = emptyList()), callbacks,
                    Modifier.width(360.dp).height(300.dp), inputSessionManagedByHost = true)
            }
        }
        rule.onNodeWithContentDescription("编辑排列").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("保存排列").assertIsDisplayed()
        rule.onNodeWithContentDescription("添加剪贴板", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun leftBackLeavesEditingWithoutSavingDraft() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        vm.showOverlay(OverlayRoute.Menu)
        val state = KeyboardUiState(toolbarButtons = listOf("emoji", "clipboard"), menuButtons = listOf("settings", "sound"))
        var saved: QuickActionLayout? = null
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}, onUpdateQuickActions = { saved = it })
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, state, callbacks, Modifier.width(360.dp).height(300.dp), inputSessionManagedByHost = true)
            }
        }
        rule.onNodeWithContentDescription("编辑排列").performClick()
        val source = rule.onNodeWithContentDescription("表情").fetchSemanticsNode().boundsInRoot.center
        val target = rule.onNodeWithTag("menu-item:settings").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(source); advanceEventTime(700); moveTo(target); up() }
        rule.onNodeWithTag("menu-item:emoji").assertIsDisplayed()
        rule.onNodeWithContentDescription("隐藏声音振动", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.runOnIdle { assertNull(saved) }
        rule.onNodeWithContentDescription("展开菜单").performClick()
        rule.onNodeWithTag("menu-item:sound").assertIsDisplayed()
        rule.onNodeWithContentDescription("表情").assertIsDisplayed()
        rule.onNodeWithTag("menu-item:emoji").assertDoesNotExist()
    }

    @Test fun threeDotMenuEntryOpensAndReturnsWithoutMovingTheSlot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}, onUpdateQuickActions = {})
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, KeyboardUiState(), callbacks, Modifier.width(360.dp).height(300.dp), inputSessionManagedByHost = true)
            }
        }
        val before = rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("展开菜单").assertIsDisplayed()
        val hideSlot = rule.onNodeWithTag("toolbar-hide").fetchSemanticsNode().boundsInRoot
        val view = rule.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(image)) }
        val output = File("build/reports/inline-quick-action-menu-entry.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithContentDescription("展开菜单").performClick()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot)
        assertEquals(hideSlot, rule.onNodeWithTag("toolbar-edit").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("menu-item:#edit-layout").assertDoesNotExist()
        val menuImage = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(menuImage)) }
        File("build/reports/inline-quick-action-toolbar-edit.png").outputStream().use {
            menuImage.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("展开菜单").assertIsDisplayed()
        rule.onNodeWithTag("toolbar-hide").assertIsDisplayed()
    }

    @Test fun visibilityButtonsHideAndAppendWithoutSavingUntilConfirmed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        vm.showOverlay(OverlayRoute.Menu)
        var state by mutableStateOf(KeyboardUiState(toolbarButtons = listOf("emoji", "clipboard"), menuButtons = listOf("settings", "sound")))
        var saved: QuickActionLayout? = null
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onUpdateQuickActions = { saved = it; state = state.copy(toolbarButtons = it.toolbar, menuButtons = it.menu) })
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, state, callbacks, Modifier.width(360.dp).height(300.dp), inputSessionManagedByHost = true)
            }
        }
        val backSlot = rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot
        val editSlot = rule.onNodeWithTag("toolbar-edit").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("编辑排列").performClick()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals(backSlot, rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot)
        assertEquals(editSlot, rule.onNodeWithTag("toolbar-save").fetchSemanticsNode().boundsInRoot)
        val hint = rule.onNodeWithTag("quick-actions-save-hint").fetchSemanticsNode().boundsInRoot
        assertTrue("保存提示应位于对钩下方并留在键盘内", hint.top >= editSlot.bottom && hint.right <= 360f && hint.center.x > 180f)
        rule.onNodeWithContentDescription("隐藏声音振动", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("添加拍照识别", useUnmergedTree = true).assertIsDisplayed()
        val view = rule.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(image)) }
        val output = File("build/reports/inline-quick-action-visibility.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val badge = rule.onNodeWithContentDescription("添加拍照识别", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val opaqueBlue = android.graphics.Color.rgb(36, 120, 248)
        val bluePixels = (badge.left.toInt().coerceAtLeast(0) until badge.right.toInt().coerceAtMost(image.width)).sumOf { x ->
            (badge.top.toInt().coerceAtLeast(0) until badge.bottom.toInt().coerceAtMost(image.height)).count { y -> image.getPixel(x, y) == opaqueBlue }
        }
        assertTrue("添加按钮保持完全不透明", bluePixels > 20)
        val shown = rule.onNodeWithTag("menu-visual:settings", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val hidden = rule.onNodeWithTag("menu-visual:ocr", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val shownPixel = image.getPixel((shown.left + 12).toInt(), (shown.bottom - 12).toInt())
        val hiddenPixel = image.getPixel((hidden.left + 12).toInt(), (hidden.bottom - 12).toInt())
        assertTrue("隐藏卡片应比显示卡片更透明", android.graphics.Color.red(hiddenPixel) < android.graphics.Color.red(shownPixel))
        rule.onNodeWithContentDescription("隐藏声音振动", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("添加拍照识别", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("隐藏拍照识别", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { assertNull(saved) }
        rule.onNodeWithContentDescription("保存排列").performClick()
        rule.runOnIdle { assertEquals(listOf("settings", "ocr"), saved?.menu) }
        rule.onNodeWithTag("toolbar-edit").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        rule.onNodeWithTag("menu-item:sound").assertDoesNotExist()
        rule.onNodeWithTag("menu-item:ocr").assertIsDisplayed()
        rule.onNodeWithTag("quick-action-visibility:ocr", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun otherMenuCardsMoveThroughIntermediatePositionsWhileDragging() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        vm.showOverlay(OverlayRoute.Menu)
        val state = KeyboardUiState(toolbarButtons = listOf("emoji", "clipboard"), menuButtons = listOf("settings", "sound"))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}, onUpdateQuickActions = {})
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, state, callbacks, Modifier.width(360.dp).height(300.dp), inputSessionManagedByHost = true)
            }
        }
        rule.onNodeWithContentDescription("编辑排列").performClick()
        val source = rule.onNodeWithTag("menu-item:settings").fetchSemanticsNode().boundsInRoot.center
        val target = rule.onNodeWithTag("menu-item:sound").fetchSemanticsNode().boundsInRoot.center
        val soundBefore = rule.onNodeWithTag("menu-visual:sound", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(source); advanceEventTime(700); moveTo(target) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(70)
        val soundDuring = rule.onNodeWithTag("menu-visual:sound", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val view = rule.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(frame)) }
        val frameFile = File("build/reports/inline-quick-action-moving.png")
        frameFile.parentFile?.mkdirs()
        frameFile.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.advanceTimeBy(300)
        val soundAfter = rule.onNodeWithTag("menu-visual:sound", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("卡片应先从原位置滑出：$soundBefore → $soundDuring", soundDuring < soundBefore - 1f)
        assertTrue("卡片应经过中间位置：$soundDuring → $soundAfter", soundDuring > soundAfter + 1f)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        val toolbarSource = rule.onNodeWithContentDescription("表情").fetchSemanticsNode().boundsInRoot.center
        val toolbarTarget = rule.onNodeWithContentDescription("剪贴板").fetchSemanticsNode().boundsInRoot.center
        val clipboardBefore = rule.onNodeWithTag("toolbar-visual:clipboard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        rule.onRoot().performTouchInput { down(toolbarSource); advanceEventTime(700); moveTo(toolbarTarget) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(70)
        val clipboardDuring = rule.onNodeWithTag("toolbar-visual:clipboard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        rule.mainClock.advanceTimeBy(300)
        val clipboardAfter = rule.onNodeWithTag("toolbar-visual:clipboard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("工具栏按钮应先从原位置滑出：$clipboardBefore → $clipboardDuring", clipboardDuring < clipboardBefore - 1f)
        assertTrue("工具栏按钮应经过中间位置：$clipboardDuring → $clipboardAfter", clipboardDuring > clipboardAfter + 1f)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
    }

    @Test fun menuAndEditingStateRender() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        vm.showOverlay(OverlayRoute.Menu)
        var state by mutableStateOf(KeyboardUiState(toolbarButtons = listOf("emoji", "clipboard"), menuButtons = listOf("settings", "sound")))
        var saved: QuickActionLayout? = null
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onUpdateQuickActions = { saved = it; state = state.copy(toolbarButtons = it.toolbar, menuButtons = it.menu) })
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        rule.setContent {
            MaterialTheme {
                KeyboardView(vm, state, callbacks, Modifier.width(360.dp).height(300.dp), inputSessionManagedByHost = true)
            }
        }
        rule.onNodeWithContentDescription("编辑排列").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("保存排列").assertIsDisplayed()
        rule.onNodeWithTag("quick-actions-save-hint").assertIsDisplayed()
        val view = rule.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(image)) }
        val output = File("build/reports/inline-quick-action-editor.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.advanceTimeBy(3000)
        rule.onNodeWithTag("quick-actions-save-hint").assertDoesNotExist()
        val source = rule.onNodeWithContentDescription("表情").fetchSemanticsNode().boundsInRoot.center
        val target = rule.onNodeWithTag("menu-item:settings").fetchSemanticsNode().boundsInRoot.center
        val middle = Offset((source.x + target.x) / 2, (source.y + target.y) / 2)
        rule.onRoot().performTouchInput { down(source); advanceEventTime(700); moveTo(middle) }
        val toolbarImage = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(toolbarImage)) }
        File("build/reports/inline-quick-action-toolbar-drag.png").outputStream().use { toolbarImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val preview = rule.onNodeWithTag("quick-action-drag-preview").fetchSemanticsNode().boundsInRoot.center
        assertTrue("拖动中的按钮应跟随手指：$preview / $middle", (preview - middle).getDistance() < 3f)
        val currentTarget = rule.onNodeWithTag("menu-item:settings").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { moveTo(currentTarget) }
        val menuPreview = rule.onNodeWithTag("quick-action-drag-preview").fetchSemanticsNode().boundsInRoot
        assertTrue("拖到菜单时应变为卡片", menuPreview.height > menuPreview.width)
        val previewEmoji = rule.onNodeWithTag("menu-item:emoji").fetchSemanticsNode().boundsInRoot
        val previewSettings = rule.onNodeWithTag("menu-item:settings").fetchSemanticsNode().boundsInRoot
        assertTrue("松手前表情已插入设置前面", previewEmoji.left < previewSettings.left)
        rule.runOnIdle { assertNull(saved) }
        rule.onRoot().performTouchInput { up() }
        val card = rule.onNodeWithTag("menu-item:sound").fetchSemanticsNode().boundsInRoot.center
        val toolbar = rule.onNodeWithContentDescription("剪贴板").fetchSemanticsNode().boundsInRoot.center
        val cardMiddle = Offset((card.x + toolbar.x) / 2, (card.y + toolbar.y) / 2)
        rule.onRoot().performTouchInput { down(card); advanceEventTime(700); moveTo(cardMiddle) }
        val cardPreview = rule.onNodeWithTag("quick-action-drag-preview").fetchSemanticsNode().boundsInRoot.center
        assertTrue("拖动中的卡片应跟随手指：$cardPreview / $cardMiddle", (cardPreview - cardMiddle).getDistance() < 3f)
        val dragImage = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(dragImage)) }
        File("build/reports/inline-quick-action-drag.png").outputStream().use { dragImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val placeholder = rule.onNodeWithTag("menu-item:sound").fetchSemanticsNode().boundsInRoot
        val sampleY = (placeholder.bottom - 12f).toInt()
        assertEquals("拖动时原卡片的位置应完全透明", dragImage.getPixel(4, sampleY),
            dragImage.getPixel((placeholder.right - 12f).toInt(), sampleY))
        val currentToolbar = rule.onNodeWithContentDescription("剪贴板").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { moveTo(currentToolbar) }
        val roundPreview = rule.onNodeWithTag("quick-action-drag-preview").fetchSemanticsNode().boundsInRoot
        assertTrue("拖到工具栏时应变为圆形图标", kotlin.math.abs(roundPreview.width - roundPreview.height) < 1f)
        rule.onNodeWithTag("menu-item:sound").assertDoesNotExist()
        rule.runOnIdle { assertNull(saved) }
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithContentDescription("保存排列").performClick()
        rule.runOnIdle {
            assertEquals(listOf("sound", "clipboard"), saved?.toolbar)
            assertEquals(listOf("emoji", "settings"), saved?.menu)
        }
    }
}
