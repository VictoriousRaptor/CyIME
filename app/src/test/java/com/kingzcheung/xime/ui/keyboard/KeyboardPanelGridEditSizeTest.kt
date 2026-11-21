package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.QuickActionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PR #510：菜单只有一页时，没点编辑的卡片也按满页大小排，点「编辑排列」前后卡片大小、位置不变
 * （11-17 的 4ee25907 只固定了编辑中的格子，没点编辑时卡片被撑大，一点编辑就缩回去）。
 * 只画网格本身，不画整个键盘：整块 KeyboardView 的渲染用例在同一个 JVM 里多一个就会把堆撑爆。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-mdpi")
class KeyboardPanelGridEditSizeTest {
    @get:Rule val rule = createComposeRule()

    private val items = listOf("emoji", "clipboard", "settings")

    private var editor by mutableStateOf<InlineQuickActionEditor?>(null)

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalInlineQuickActionEditor provides editor) {
                KeyboardPanelGrid(items, isLandscape = false, textColor = Color.Black, pagerTag = "menu-pages",
                    modifier = Modifier.requiredSize(360.dp, 230.dp)) { item, cell ->
                    Box(cell.testTag("cell:$item"))
                }
            }
        }
    }

    private fun bounds() = rule.onNodeWithTag("cell:settings").fetchSemanticsNode().boundsInRoot

    @Test fun cardsKeepTheirSizeWhenEditingStarts() {
        val host = InlineQuickActionEditor()
        editor = host
        show()
        val before = bounds()
        rule.runOnIdle { host.start(QuickActionLayout(emptyList(), items), emptyList(), items) }
        rule.waitForIdle()
        val during = bounds()
        assertEquals(before.width, during.width, 0.5f)
        assertEquals(before.height, during.height, 0.5f)
        assertEquals(before.top, during.top, 0.5f)
        assertEquals(before.left, during.left, 0.5f)
    }

    /** 没有菜单编辑宿主的面板照旧按格子个数自适应：三个格子排成三列，比满页四列宽。 */
    @Test fun panelsWithoutAnEditorStillFitTheirItems() {
        show()
        val adaptive = bounds()
        rule.runOnIdle { editor = InlineQuickActionEditor() }
        rule.waitForIdle()
        assertTrue(adaptive.width > bounds().width + 1f)
    }
}
