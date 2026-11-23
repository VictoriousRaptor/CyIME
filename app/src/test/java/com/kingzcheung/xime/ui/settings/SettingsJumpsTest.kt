package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.ui.keyboard.CursorGestureMode
import com.kingzcheung.xime.ui.keyboard.InputGestureLocks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置说明里的「」指路点一下跳到那一项（用户 11-01）：写的名字必须真能找到，改了设置名而没改指路会在这里失败。 */
class SettingsJumpsTest {
    private val bracketed = Regex("「([^「」]+)」")

    /** 页面源码里每个 `jumps = listOf(...)` 列出的指路都认得，而且确实写在那段说明的「」里。 */
    @Test fun everyListedJumpResolves() {
        val dir = java.io.File("src/main/java/com/kingzcheung/xime/ui/settings")
        val source = dir.listFiles { f -> f.extension == "kt" }!!.joinToString("\n") { it.readText() }
        val labels = Regex("""(?:jumps|subtitleJumps) = listOf\(([^)]*)\)""").findAll(source)
            .flatMap { m -> Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toList()
        assertTrue("没找到任何指路，扫描写法变了？", labels.size >= 15)
        val missing = labels.filter { settingsJumpEntry(it) == null }
        assertTrue("认不出：$missing", missing.isEmpty())
        val unwritten = labels.filter { "「$it」" !in source }
        assertTrue("说明里没有写成「」：$unwritten", unwritten.isEmpty())
    }

    @Test fun keyTipJumpsResolveAndAppearInTheirText() {
        for (reverse in listOf(false, true)) {
            keyTipGroups(KeyboardInputPreferences(reverseSymbolSwipe = reverse)).flatMap { it.tips }.forEach { tip ->
                tip.jumps.forEach { label ->
                    assertTrue(label, "「$label」" in tip.what)
                    assertNotNull(label, settingsJumpEntry(label))
                }
            }
        }
    }

    /** 「已被「X」锁定」里的 X 都能跳过去，在那边关掉就解锁。 */
    @Test fun everyLockPointsAtARealSetting() {
        val all = listOf(
            KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS, glideTyping = true,
                cursorGesture = CursorGestureMode.KEYBOARD, doublePinyinSemicolonOnShift = true),
            KeyboardInputPreferences(chineseGlideTyping = true, shiftSyllableSplit = true),
            KeyboardInputPreferences(t9GlideTyping = true),
            // 九键空格上滑输入 0 ↔ 上滑取消（11-22），两个方向各一把。
            KeyboardInputPreferences(t9SpaceSwipeUpZero = true),
            KeyboardInputPreferences(spaceHold = com.kingzcheung.xime.ui.keyboard.SpaceHoldAction.VOICE_TOGGLE, voiceSwipeCancel = true),
        ).flatMap { InputGestureLocks.of(it) }.map { it.message }.distinct()
        assertTrue(all.size >= 7)
        for (message in all) {
            val names = bracketed.findAll(message).map { it.groupValues[1] }.toList()
            assertEquals(message, 1, names.size)
            assertNotNull(message, settingsJumpEntry(names.single()))
        }
    }

    @Test fun resolvesRowsGroupsAndPages() {
        val space = settingsJumpEntry("按键与手势 › 长按空格")!!
        assertEquals("长按空格", space.anchor)
        assertEquals(SettingsSearchIndex.first { it.title == "按键与手势" }.target, space.target)
        // 组名：跳到那一页，亮整组。
        val delete = settingsJumpEntry("按键与手势 › 删除键")!!
        assertEquals("删除键", delete.anchor)
        assertNull(delete.section)
        assertEquals(space.target, delete.target)
        // 最后一段不是设置名时退到上一段：「扩展 › 方案」打开扩展页（第一页就是方案）。
        assertEquals(SettingsRoutes.Market, settingsJumpEntry("扩展 › 方案")!!.target)
        // 「：」后面是选项值。
        assertEquals("按键角标的符号和数字", settingsJumpEntry("按键角标的符号和数字：长按输入")!!.title)
        // 整页没有锚点，只打开页面。
        assertNull(settingsJumpEntry("性能与省电")!!.anchor)
        assertNull(settingsJumpEntry("全部"))
        assertNull(settingsJumpEntry("语音开关"))
    }

    @Test fun samePageOnlyHighlights() {
        val layout = "${SettingsRoutes.LayoutDisplay}?page={page}"
        assertTrue(settingsPageMatches(layout, { "gestures" }, "${SettingsRoutes.LayoutDisplay}?page=gestures"))
        assertFalse(settingsPageMatches(layout, { "candidates" }, "${SettingsRoutes.LayoutDisplay}?page=gestures"))
        assertFalse(settingsPageMatches(layout, { null }, "${SettingsRoutes.LayoutDisplay}?page=gestures"))
        assertTrue(settingsPageMatches(layout, { null }, SettingsRoutes.LayoutDisplay))
        assertTrue(settingsPageMatches(SettingsRoutes.Theme, { null }, SettingsRoutes.Theme))
        assertFalse(settingsPageMatches(SettingsRoutes.Theme, { null }, SettingsRoutes.KeyEffect))
        assertFalse(settingsPageMatches(null, { null }, SettingsRoutes.Theme))
    }
}
