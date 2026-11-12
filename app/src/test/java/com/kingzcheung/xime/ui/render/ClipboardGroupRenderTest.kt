package com.kingzcheung.xime.ui.render

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.ui.settings.ClipboardGroupSettingsContent
import androidx.compose.ui.test.onNodeWithText
import com.kingzcheung.xime.clipboard.clipboardTabs
import com.kingzcheung.xime.clipboard.ClipboardGroupStore
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.clipboard.ClipboardFilter
import com.kingzcheung.xime.clipboard.ClipboardGroup
import com.kingzcheung.xime.clipboard.ClipboardGroupState
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 剪贴板自定义分组：分类栏里自带分类后面的分组、分组页的内容，以及长按记录后的「放入分组」菜单。
 * 手机和平板宽度、深浅色、玻璃各一张；记录全是假数据。
 * 只在设置了环境变量 CYIME_RENDER_OUT=<目录> 时出图（平时跳过）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1400dp-h1600dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardGroupRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val phrases = listOf("好的，收到", "我在路上，十分钟到", "麻烦发一下发票抬头", "上海市南京东路100号 3楼")
    private val text = phrases.mapIndexed { i, t -> ClipboardItem(i + 1L, t, timestamp = 100L - i, isPinned = true) } +
        (11L..14L).map { ClipboardItem(it, "最近复制的示例文字 $it", timestamp = 1000 - it) }
    private val pins = (1L..4L).mapTo(mutableSetOf()) { "text:$it" }
    private val groups = ClipboardGroupState(
        groups = listOf(ClipboardGroup("g1", "常用语"), ClipboardGroup("g2", "工作")),
        members = mapOf("text:1" to "g1", "text:2" to "g1", "text:3" to "g2"),
        hidden = setOf(ClipboardFilter.EMAIL),
    )

    @Test fun dragGroupAheadOfTextThenRenameAndDeleteByButton() {
        // The clipboard manager removes memberships whose backing record is gone.
        val recordId = runBlocking {
            ClipboardDatabase.getInstance(rule.activity).clipboardDao()
                .insert(ClipboardEntry(text = "分组改名后保留的记录", grouped = true))
        }
        val recordKey = "text:$recordId"
        val store = ClipboardGroupStore.getInstance(rule.activity)
        store.add("收藏", listOf(recordKey))
        val id = store.state.value.groups.single().id
        rule.setContent { XimeTheme(darkTheme = true) { ClipboardGroupSettingsContent(onBack = {}) } }
        val from = rule.onNodeWithTag("clipboard-order:group:$id").fetchSemanticsNode().boundsInRoot.center
        val to = rule.onNodeWithTag("clipboard-order:TEXT").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 50)
            for (i in 1..12) moveTo(from + (to - from) * (i / 12f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("ALL", "group:$id", "TEXT"), clipboardTabs(store.state.value).take(3).map { it.key })
        rule.onNodeWithContentDescription("改名").performClick()
        rule.onNodeWithTag("clipboard-group-name").performTextReplacement("常用")
        rule.onNodeWithTag("clipboard-group-name-save").performClick()
        assertEquals("常用", clipboardTabs(store.state.value)[1].label)
        assertEquals(id, store.state.value.members[recordKey])
        rule.onNodeWithContentDescription("删除").performClick()
        rule.onNodeWithText("取消").performClick()
        assertEquals(id, store.state.value.groups.single().id)
        rule.onNodeWithContentDescription("删除").performClick()
        rule.onNodeWithTag("clipboard-group-delete-confirm").performClick()
        rule.waitForIdle()
        assertEquals("TEXT", clipboardTabs(store.state.value)[1].key)
    }

    @Test fun boardShowsAnInterleavedGroupAheadOfBuiltInFilters() {
        KeyboardThemes.initFromConfig(rule.activity)
        val moved = groups.reordered(listOf("group:g2", "IMAGE", "TEXT", "group:g1"))
        rule.setContent { ClipboardGroupTestBoard(800, text, pins, moved, "ALL") }
        val centers = listOf("ALL", "group:g2", "IMAGE", "TEXT", "group:g1").map {
            rule.onNodeWithTag("clipboard-filter:$it").fetchSemanticsNode().boundsInRoot.center.x
        }
        assertTrue(centers.zipWithNext().all { (a, b) -> a < b })
        rule.onNodeWithTag("clipboard-filter:group:g2").performClick()
        rule.onNodeWithTag("clipboard-card:text:3").assertExists()
        rule.onNodeWithTag("clipboard-card:text:1").assertDoesNotExist()
    }

    @Test fun renderGroups() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        KeyboardThemes.initFromConfig(ApplicationProvider.getApplicationContext<Context>())
        val frame = mutableIntStateOf(0)
        rule.setContent {
            Column(Modifier.background(Color(0xFF808080)).padding(4.dp)) {
                if (frame.intValue == 0) {
                    for (width in listOf(360, 800)) for (dark in listOf(true, false)) for (glass in listOf(false, true)) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Label(width, dark, glass)
                            Board(width, dark, glass, "group:g1")
                        }
                    }
                } else {
                    val (dark, glass) = listOf(false to false, true to true)[frame.intValue - 1]
                    Row { Label(360, dark, glass); Board(360, dark, glass, "ALL") }
                }
            }
        }
        rule.waitForIdle()
        File(out!!).mkdirs()
        save(File(out, "clipboard-groups-tabs.png"))
        for (index in 1..2) {
            rule.runOnIdle { frame.intValue = index }
            rule.waitForIdle()
            rule.onNodeWithTag("clipboard-card:text:1").performTouchInput { longClick() }
            rule.waitForIdle()
            save(File(out, "clipboard-groups-item-menu-$index.png"))
            rule.onNodeWithTag("clipboard-item-group").performClick()
            rule.waitForIdle()
            save(File(out, "clipboard-groups-choose-$index.png"))
            rule.onNodeWithTag("clipboard-group-menu-dismiss").performClick()
            rule.waitForIdle()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Label(width: Int, dark: Boolean, glass: Boolean) =
        Text("${width}dp ${if (dark) "深" else "浅"} ${if (glass) "玻璃" else "实色"}", fontSize = 10.sp,
            color = Color.White, modifier = Modifier.width(90.dp))

    @androidx.compose.runtime.Composable
    private fun Board(width: Int, dark: Boolean, glass: Boolean, tab: String) {
        CompositionLocalProvider(LocalKeyboardInputPreferences provides
            KeyboardInputPreferences(frostedGlass = FrostedGlassConfig.defaults(dark, enabled = glass))) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                ClipboardGroupTestBoard(width, text, pins, groups, tab)
            }
        }
    }

    private fun save(file: File) {
        // Robolectric 下 captureToImage 等不到 PixelCopy；直接把整个窗口画到位图上。
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@androidx.compose.runtime.Composable
internal fun ClipboardGroupTestBoard(width: Int, text: List<ClipboardItem>, pins: Set<String>, groups: ClipboardGroupState, tab: String) {
    ClipboardBoardView(text, emptyList(), false, false, pins, false, null,
        onBack = {}, onQuickSend = {}, onSelectText = {}, onSelectImage = {},
        onSplit = { _, _ -> }, onAddQuick = {}, onPinsChange = {}, onRemove = {},
        onPhotoAccess = {}, onPick = {}, onSystemPaste = {}, onShare = { _, _ -> },
        onPullRemote = null, onAddPinned = {}, onEditText = { _, _, _ -> },
        groups = groups, initialTabKey = tab, onManageGroups = {},
        modifier = Modifier.width(width.dp).height(220.dp))
}
