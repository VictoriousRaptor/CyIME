package com.kingzcheung.xime.ui.menubar

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 11-11：九键下打开布局选择那一下从右往左滑进来好看，26 键、14 键都要有；
 * 距离按九键那一下（约一张卡片），不能从第一张滑过来（后面卡片多了会滑很久）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class LayoutPanelSlideInTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val schemas = listOf("rime_ice", "t9_pinyin", "pinyin_14jian").map { SchemaInfo(it, it, "", "", "") }
    private var current by mutableStateOf("rime_ice")

    private val layoutCard = SemanticsMatcher("layout card") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("panel-layout:") == true
    }

    /** 每张卡片的左边（不按列表边缘裁掉，滑入开头被挤出列表的卡片也算）。 */
    private fun cards(): Map<String, Float> = rule.onAllNodes(layoutCard, useUnmergedTree = true).fetchSemanticsNodes()
        .associate { it.config[SemanticsProperties.TestTag] to it.positionInRoot.x }

    private fun selectedCard(): Rect = rule.onAllNodes(layoutCard, useUnmergedTree = true).fetchSemanticsNodes()
        .first { it.config.getOrNull(SemanticsProperties.Selected) == true }.boundsInRoot

    @Test fun everyLayoutSlidesInFromTheRightByAboutOneCard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                // 每换一次当前布局就是重新打开一次面板。
                key(current) {
                    SchemaListView(schemas, current, Color.White, Color.Blue, Color.Black, Color(0xFFEEEEEE),
                        onSelectSchema = {}, modifier = Modifier.requiredSize(400.dp, 280.dp))
                }
            }
        }
        for (schema in listOf("rime_ice", "t9_pinyin", "pinyin_14jian")) {
            rule.runOnUiThread { current = schema }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            val opening = cards()
            assertTrue("$schema：面板里应有布局卡片", opening.isNotEmpty())
            rule.mainClock.advanceTimeBy(800)
            val settled = cards()
            val chosen = selectedCard()
            val list = rule.onNodeWithTag("profile-layout-list:list", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$schema：停下后当前布局在看得见的地方 ($chosen / $list)",
                chosen.left >= list.left - 1f && chosen.left < list.right)
            val card = chosen.width
            val moves = opening.mapNotNull { (tag, start) -> settled[tag]?.let { start - it } }
            assertTrue(moves.isNotEmpty())
            for (move in moves) {
                // 从右往左滑：开头在停下位置的右边，大约一张卡片（加间距）的距离，不更远。
                assertTrue("$schema：应从右边滑进来（移动 $move，卡片宽 $card）", move > card * 0.5f)
                assertTrue("$schema：滑动距离不超过一张卡片多一点（移动 $move，卡片宽 $card）", move <= card + 20f)
            }
            // 整排一起动，每张移动一样多。
            assertEquals(moves.max(), moves.min(), 1f)
            // 停下后不再动。
            rule.mainClock.advanceTimeBy(300)
            for ((tag, end) in cards()) settled[tag]?.let { assertEquals("$schema：$tag 停下后不该再动", it, end, 1f) }
        }
        rule.mainClock.autoAdvance = true
    }
}
