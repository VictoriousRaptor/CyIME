package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.TOOL_PANEL_BAR_HEIGHT
import com.kingzcheung.xime.ui.keyboard.TOOL_PANEL_HEIGHT
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.kingzcheung.xime.rime.T9InputController
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

/**
 * 11-10 用户截图：开着翻译栏打开菜单，工具栏的图标画在菜单格子上（菜单只从顶上量了一个候选栏高）。
 * 菜单、表情等面板只盖按键：从工具栏下沿开始，上面是翻译栏、AI 工具面板时也一样；面板底边贴着键盘底边。
 * 设了 CYIME_RENDER_OUT=<目录> 时出图 panel-below-tool-panel-*.png。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w392dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelBelowToolPanelTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private data class Case(val name: String, val display: String?, val extraDp: Int, val route: OverlayRoute)

    @Test fun panelsStartBelowTheToolbarWhateverSitsAboveIt() {
        KeysConfigHelper.loadConfig(app)
        AppFonts.initialize(app)
        KeyboardThemes.initFromConfig(app)
        val cases = listOf(
            Case("menu", null, 0, OverlayRoute.Menu),
            Case("menu-live", "LIVE", TOOL_PANEL_BAR_HEIGHT, OverlayRoute.Menu),
            Case("menu-tool", "DIRECT", TOOL_PANEL_HEIGHT, OverlayRoute.Menu),
            Case("schemas-live", "LIVE", TOOL_PANEL_BAR_HEIGHT, OverlayRoute.SchemaList),
        )
        val keyboardDp = 268
        // 九键控制器要加载 native 引擎，这里和 KeyboardBottomEdgeRenderTest 一样换成假的。
        val vm = spy(KeyboardViewModel(app))
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {})
        val t9 = mock<T9InputController> {
            on { leftPanelState } doReturn T9InputController.LeftPanelState.IDLE
            on { firstOptions } doReturn emptyList()
        }
        doReturn(t9).whenever(vm).bindT9Controller(callbacks)
        var case by mutableStateOf(cases.first())
        rule.setContent {
            val prefs = rememberKeyboardInputPreferences()
            CompositionLocalProvider(LocalKeyboardInputPreferences provides prefs) {
                Column(Modifier.fillMaxSize()) {
                    val c = case
                    KeyboardView(vm, KeyboardUiState(currentSchemaId = "pinyin", keyboardHeightDp = keyboardDp,
                        toolPanelVisible = c.display != null, toolPanelDisplay = c.display, toolPanelTitle = "AI 改写"),
                        callbacks,
                        // 和服务一样：键盘高度加上候选栏上面那一截（开着面板也算）
                        Modifier.fillMaxWidth().height((keyboardDp + c.extraDp).dp).testTag("keyboard"))
                }
            }
        }
        for (c in cases) {
            rule.runOnIdle { case = c; vm.showOverlay(c.route) }
            rule.waitForIdle()
            val keyboard = bounds("keyboard")
            val toolbar = bounds("toolbar-leading")
            val overlay = bounds("keyboard-overlay")
            shot(c.name)
            // 工具栏还在它自己那一行：在翻译栏、工具面板下面
            assertTrue("${c.name}: 工具栏 $toolbar 在上面那一截 ${c.extraDp}dp 之下",
                toolbar.top >= keyboard.top + c.extraDp - 0.5f)
            // 面板从工具栏下沿开始，不盖工具栏，也不在工具栏下面空出一条
            assertTrue("${c.name}: 面板 $overlay 盖住了工具栏 $toolbar", overlay.top >= toolbar.bottom - 0.5f)
            assertTrue("${c.name}: 面板 $overlay 离工具栏 $toolbar 太远", overlay.top - toolbar.bottom < 12f)
            assertEquals("${c.name}: 面板底边贴着键盘底边", keyboard.bottom, overlay.bottom, 0.5f)
            rule.runOnIdle { vm.closeOverlay() }
            rule.waitForIdle()
        }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT")?.takeIf { it.isNotEmpty() } ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out).mkdirs()
        File(out, "panel-below-tool-panel-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
