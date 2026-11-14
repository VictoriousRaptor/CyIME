package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 26 键按住 !@# 当 Ctrl：拖到字母上松手发 Ctrl+字母；不拖动且 1 秒内松手打开符号，
 * 超过 1 秒或拖出去没落在字母上松手什么都不做。
 * 字母键的快捷键键面按满 1 秒（同时限）或拖出符号键才渐变出来，点一下开符号键盘不闪（用户 10-31）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class CtrlSymbolKeyTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val keys = mutableListOf<String>()
    private val commands = mutableListOf<String>()

    private fun show(schema: String = "") {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardExplicitWidth provides true,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(symbolKeyHoldSeconds = 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 240.dp)) {
                        KeyboardLayout({ keys += it }, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onGestureAction = { action, value -> if (action == GestureAction.COMMAND) commands += value }),
                            KeyboardUiState(currentSchemaId = schema), false, Modifier.requiredSize(360.dp, 240.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
    private fun labelShown(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun holdShowsShortcutsAndDragSendsCtrlLetter() {
        show()
        assertTrue(!labelShown("复制"))
        val symbol = bounds("mode-slot-1")
        val c = bounds("qwerty-key:c")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(900)
        assertTrue("没按满时限不换键面", !labelShown("复制"))
        rule.mainClock.advanceTimeBy(100 + CTRL_FACE_FADE_MS + 50L)
        assertTrue("按满时限显示快捷键", labelShown("复制") && labelShown("粘贴") && labelShown("全选"))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
            val target = c.center - symbol.topLeft
            for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("ctrl:c"), commands)
        assertEquals("拖到字母不打开符号", emptyList<String>(), keys)
        assertTrue("松手恢复字母键面", !labelShown("复制"))
    }

    /** 点一下开符号：从按下到松手，字母键一直不换成「复制」「粘贴」。 */
    @Test fun quickTapNeverShowsShortcutFaces() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
        for (step in 1..6) {
            rule.mainClock.advanceTimeBy(50)
            assertTrue("按下 ${step * 50} 毫秒还不换键面", !labelShown("复制"))
        }
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1500)
        assertTrue(!labelShown("复制"))
        assertEquals(listOf("symbol"), keys)
    }

    /** 不等时限直接拖：一拖出符号键就显示快捷键，松在 V 上立刻发 Ctrl+V。 */
    @Test fun immediateDragShowsFacesAndSends() {
        show()
        val symbol = bounds("mode-slot-1")
        val v = bounds("qwerty-key:v")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(16)
        // 时钟手动走时，一次送进去的一串移动只有第一下被手势收到；每移一步走一帧，和真手指一样逐帧送到。
        for (step in 1..8) {
            rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
                val target = v.center - symbol.topLeft
                moveTo(center + (target - center) * (step / 8f))
            }
            rule.mainClock.advanceTimeBy(16)
        }
        rule.mainClock.advanceTimeBy(CTRL_FACE_FADE_MS + 50L)
        assertTrue("拖出去就显示快捷键，不等时限", labelShown("粘贴"))
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("ctrl:v"), commands)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun tapOpensSymbols() {
        show()
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("symbol"), keys)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun releaseWithinTheLimitOpensSymbols() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(900)
        assertEquals("按着不打开", emptyList<String>(), keys)
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("symbol"), keys)
    }

    @Test fun releaseAfterTheLimitDoesNothing() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1200)
        assertEquals("超过时限也不自己打开", emptyList<String>(), keys)
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals("超过时限松手不打开", emptyList<String>(), keys)
        assertEquals(emptyList<String>(), commands)
    }

    private fun dragFromSymbolKeyTo(tag: String) {
        val symbol = bounds("mode-slot-1")
        val goal = bounds(tag)
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
            down(center)
            val target = goal.center - symbol.topLeft
            for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun releasingOffTheLettersDoesNothing() {
        show()
        dragFromSymbolKeyTo("space-key")
        assertEquals(emptyList<String>(), keys)
        assertEquals(emptyList<String>(), commands)
    }

    /** 用户 11-03：从 !@# 拖到回车上松手是 Ctrl+回车（发送），不打字、不开符号；第一次拖、拖第二次都一样。 */
    @Test fun dragToEnterSendsCtrlEnter() {
        show()
        dragFromSymbolKeyTo("qwerty-enter-key")
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND), commands)
        dragFromSymbolKeyTo("qwerty-enter-key")
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND, CtrlShortcuts.ENTER_COMMAND), commands)
        assertEquals(emptyList<String>(), keys)
    }

    /** 用户 11-03：按住 !@#，另一根手指点回车：Ctrl+回车，不发普通回车，松开 !@# 也不开符号。 */
    @Test fun secondFingerTapOnEnterSendsCtrlEnter() {
        show()
        val symbol = bounds("mode-slot-1").center
        val enter = bounds("qwerty-enter-key").center
        rule.onRoot().performTouchInput { down(0, symbol) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { down(1, enter); up(1) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.ENTER_COMMAND), commands)
        assertEquals("不打回车也不开符号", emptyList<String>(), keys)
    }

    /** 用户 11-13：按住 !@#，另一根手指点删除：Ctrl+删除，不发普通删除，松开 !@# 也不开符号；松开后删除照常。 */
    @Test fun secondFingerTapOnDeleteSendsCtrlBackspace() {
        show()
        val symbol = bounds("mode-slot-1").center
        val delete = bounds("qwerty-delete-key").center
        rule.onRoot().performTouchInput { down(0, symbol) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { down(1, delete); up(1) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(listOf(CtrlShortcuts.BACKSPACE_COMMAND), commands)
        assertEquals("不删字也不开符号", emptyList<String>(), keys)
        rule.onNodeWithTag("qwerty-delete-key", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("delete"), keys)
    }

    /** 用户 11-13：按满时限 !@# 写成 Ctrl，松手换回。 */
    @Test fun heldSymbolKeyReadsCtrl() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(0, bounds("mode-slot-1").center) }
        rule.mainClock.advanceTimeBy(1000L + CTRL_FACE_FADE_MS + 50L)
        assertTrue("按住写 Ctrl", labelShown("Ctrl"))
        rule.onRoot().performTouchInput { up(0) }
        rule.mainClock.advanceTimeBy(100L)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("松手换回 !@#", !labelShown("Ctrl") && labelShown("!@#"))
    }

    /** 一根手指按住 !@#，另一根手指点字母：和按住 Shift 再点一样，发 Ctrl+字母、不打字，松开 !@# 也不开符号。 */
    private fun tapEachLetterWhileHolding(letters: List<Char>, waitForFaces: Boolean = true) {
        val symbol = bounds("mode-slot-1").center
        // 不等就是快速按住立刻点：时钟手动走，每下只走一帧，整段不到时限，14 键一直没拆开。
        val points = letters.map { if (waitForFaces) Offset.Zero else letterPoint(it) }
        if (!waitForFaces) rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(0, symbol) }
        // 等快捷键键面出来（14 键这时拆成单字母键）。
        if (waitForFaces) rule.mainClock.advanceTimeBy(1000L + CTRL_FACE_FADE_MS + 50L)
        fun settle() = if (waitForFaces) rule.waitForIdle() else rule.mainClock.advanceTimeBy(16)
        settle()
        for ((index, letter) in letters.withIndex()) {
            if (!waitForFaces) assertTrue("还没到时限，不换键面", !labelShown("复制"))
            val key = if (waitForFaces) bounds("qwerty-key:$letter").center else points[index]
            rule.onRoot().performTouchInput { down(1, key); up(1) }
            settle()
        }
        rule.onRoot().performTouchInput { up(0) }
        settle()
        if (!waitForFaces) rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(letters.map { "ctrl:$it" }, commands)
        assertEquals("不打字也不开符号", emptyList<String>(), keys)
    }

    private val shortcutLetters = "acvxzysfwtnorpbi".toList()

    /** 字母在键盘上的位置：单字母键取中心；14 键还没拆开时取双字母键的左半或右半中心。 */
    private fun letterPoint(letter: Char): Offset {
        val single = rule.onAllNodesWithTag("qwerty-key:$letter", true).fetchSemanticsNodes()
        if (single.isNotEmpty()) return single.first().boundsInRoot.center
        val pair = com.kingzcheung.xime.settings.FourteenKeySymbols.rows.flatten().first { letter in it && it.length == 2 }
        val b = bounds("qwerty-key:$pair")
        val x = if (pair[0] == letter) b.left + b.width / 4 else b.right - b.width / 4
        return Offset(x, b.center.y)
    }

    @Test fun fourteenKeyQuickSecondFingerTapSendsShortcutsBeforeFacesShow() {
        show("pinyin_14jian")
        tapEachLetterWhileHolding(listOf('c', 'v', 'x', 'z', 'a', 'w'), waitForFaces = false)
    }

    @Test fun quickSecondFingerTapSendsShortcutsBeforeFacesShow() {
        show()
        tapEachLetterWhileHolding(listOf('c', 'v', 'z'), waitForFaces = false)
    }

    @Test fun secondFingerTapSendsEveryShortcut() {
        show()
        tapEachLetterWhileHolding(shortcutLetters + listOf('q', 'k'))
    }

    @Test fun fourteenKeySecondFingerTapSendsEveryShortcut() {
        show("pinyin_14jian")
        tapEachLetterWhileHolding(shortcutLetters + listOf('q', 'k', 'm', 'l'))
    }

    @Test fun fourteenKeyDragSendsEveryShortcut() {
        show("pinyin_14jian")
        for (letter in shortcutLetters) {
            val symbol = bounds("mode-slot-1")
            rule.onNodeWithTag("mode-slot-1", true).performTouchInput { down(center) }
            rule.waitForIdle()
            // 不等时限直接拖：双字母键还没拆，按拆开后的位置（左半/右半）拖过去。
            val key = letterPoint(letter)
            rule.onNodeWithTag("mode-slot-1", true).performTouchInput {
                val target = key - symbol.topLeft
                for (step in 1..8) moveTo(center + (target - center) * (step / 8f))
                up()
            }
            rule.waitForIdle()
        }
        assertEquals(shortcutLetters.map { "ctrl:$it" }, commands)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun lettersTypeNormallyOnceTheSymbolKeyIsReleased() {
        show()
        rule.onNodeWithTag("qwerty-key:v", true).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("v"), keys)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun ctrlEventsMatchAPhysicalKeyboard() {
        assertEquals('c', CtrlShortcuts.letterOf(CtrlShortcuts.command('C')))
        assertEquals(null, CtrlShortcuts.letterOf("ctrl:1"))
        val events = CtrlShortcuts.keyEvents('v', 5L)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_CTRL_LEFT),
            events.map { it.keyCode })
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_UP), events.map { it.action })
        assertTrue("字母带 Ctrl", events[1].isCtrlPressed && events[2].isCtrlPressed)
        assertEquals(KeyEvent.KEYCODE_Z, CtrlShortcuts.keyEvents('z', 0L)[1].keyCode)
        // 用户 11-13：按住 !@# 点删除同实体键盘 Ctrl+删除。
        val delete = CtrlShortcuts.backspaceKeyEvents(5L)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CTRL_LEFT),
            delete.map { it.keyCode })
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_UP), delete.map { it.action })
        assertTrue("删除带 Ctrl", delete[1].isCtrlPressed && delete[2].isCtrlPressed)
    }
}
