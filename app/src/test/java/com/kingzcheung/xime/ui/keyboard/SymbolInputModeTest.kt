package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.*
import org.junit.Test

class SymbolInputModeTest {
    @Test fun `clear and undo follow corner symbol direction without changing repeat or left swipe`() {
        for (reverse in listOf(false, true)) {
            val events = mutableListOf<String>()
            val actions = RepeatingKeyActions(onTap = { events += "tap" }, onPress = {}, onRelease = {}, onPreview = {},
                onRepeat = { events += "delete" }, onLeft = { events += "composition" },
                onUp = { events += "clear" }, onDown = { events += "undo" },
                upLabel = "上滑清空", downLabel = "下滑撤回").withSymbolSwipeDirection(reverse)
            actions.onUp?.invoke(); actions.onDown?.invoke(); actions.onRepeat?.invoke(); actions.onLeft?.invoke()
            assertEquals(if (reverse) listOf("clear", "undo", "delete", "composition")
                else listOf("undo", "clear", "delete", "composition"), events)
            assertEquals(if (reverse) "上滑清空" else "上滑撤回", actions.upLabel)
            assertEquals(if (reverse) "下滑撤回" else "下滑清空", actions.downLabel)
            // “清空”提示画在键的上方：对调后下拉清空，预览仍须从上方移入。
            assertEquals(!reverse, actions.swappedVertical)
        }
    }
    @Test fun `default pulls top on down and bottom on up without changing custom payloads`() {
        val events = mutableListOf<String>()
        val original = actions(events).copy(upText = "①", downText = "！？",
            upPreviewText = "一", downPreviewText = "问号", onRight = { events += "right" })
        val mapped = original.withSymbolSwipeDirection(reverse = false)
        assertTrue(events.isEmpty())
        assertEquals("一", mapped.downPreviewText)
        assertTrue(mapped.downPreviewFromTop)
        assertEquals("问号", mapped.upPreviewText)
        assertFalse(mapped.upPreviewFromTop)
        mapped.onDown?.invoke(mapped.downText.orEmpty())
        mapped.onUp?.invoke(mapped.upText.orEmpty())
        mapped.onLeft?.invoke()
        mapped.onRight?.invoke()
        mapped.onTap()
        assertEquals(listOf("symbol:①", "down:！？", "previous", "right", "tap"), events)
        assertEquals(original.longPressItems, mapped.longPressItems)
    }

    @Test fun `reverse keeps old direction and key only preview does not change committed payload`() {
        val events = mutableListOf<String>()
        val original = actions(events).copy(upText = "1", downText = null,
            upPreviewText = "1", downPreviewText = "!")
        assertSame(original, original.withSymbolSwipeDirection(reverse = true))
        val mapped = original.withSymbolSwipeDirection(reverse = false)
        assertEquals("!", mapped.upPreviewText)
        assertNull(mapped.upText)
        mapped.onUp?.invoke(mapped.upText.orEmpty())
        assertEquals(listOf("down:"), events)
    }

    @Test fun `long press blocks the physical direction of the top slot in either habit`() {
        for (reverse in listOf(false, true)) {
            val events = mutableListOf<String>()
            val mapped = actions(events).withSymbolInput(SymbolInputMode.LONG_PRESS, "2")
                .withSymbolSwipeDirection(reverse)
            if (reverse) { assertNull(mapped.onUp); assertTrue(mapped.blockUpSwipe); assertNotNull(mapped.onDown) }
            else { assertNull(mapped.onDown); assertTrue(mapped.blockDownSwipe); assertNotNull(mapped.onUp) }
            mapped.onLongPress?.invoke()
            mapped.onRelease()
            assertEquals(listOf("symbol:2"), events)
        }
    }

    @Test fun `glide long press offers top and bottom corner and commits the chosen one`() {
        for (reverse in listOf(false, true)) {
            val events = mutableListOf<String>()
            val mapped = actions(events).copy(longPressItems = emptyList(), onLongPressSelect = null)
                .withGlideCornerMenu("1", "!", 300L).withSymbolSwipeDirection(reverse)
            // 上下滑都给了滑行：两个方向都不出角标。
            assertNull(mapped.onUp); assertNull(mapped.onDown)
            assertTrue(mapped.blockUpSwipe && mapped.blockDownSwipe)
            assertEquals(listOf("1", "!"), mapped.longPressItems)
            assertNull(mapped.onLongPress)
            assertEquals(300L, mapped.longPressTimeoutMillis)
            mapped.onLongPressSelect!!("1")
            mapped.onLongPressSelect!!("!")
            assertEquals(listOf("symbol:1", "down:!"), events)
        }
    }

    @Test fun `glide long press with one corner commits at once and accent keys stay`() {
        val events = mutableListOf<String>()
        // 开数字行时中文 Q 上方空、下方 +：按满立刻出 +。
        val lowerOnly = actions(events).copy(longPressItems = emptyList(), onLongPressSelect = null)
            .withGlideCornerMenu(null, "+")
        assertTrue(lowerOnly.longPressItems.isEmpty())
        assertEquals("+", lowerOnly.longPressBubbleText)
        lowerOnly.onLongPress!!()
        assertEquals(listOf("down:+"), events)
        // 上下一样只出一个。
        val same = actions(mutableListOf()).copy(longPressItems = emptyList()).withGlideCornerMenu("?", "?")
        assertEquals("?", same.longPressBubbleText)
        // 西班牙语元音：没有上角标可长按、本来长按出 á，不动。
        val accent = actions(mutableListOf())
        assertSame(accent, accent.withGlideCornerMenu(null, "~"))
        // 两个都没有也不动。
        val none = actions(mutableListOf()).copy(longPressItems = emptyList())
        assertSame(none, none.withGlideCornerMenu(null, null))
    }

    @Test fun `disabled symbol stays disabled after direction flip`() {
        val original = actions(mutableListOf()).copy(onDown = null, downText = null, downPreviewText = null)
        val mapped = original.withSymbolSwipeDirection(reverse = false)
        assertNull(mapped.onUp)
        assertNull(mapped.upPreviewText)
        assertNotNull(mapped.onDown)
    }

    private fun actions(events: MutableList<String>) = KeyGestureActions(
        text = "ABC", onTap = { events += "tap" }, onPress = {}, onRelease = {}, onPreview = {},
        onUp = { events += "symbol:$it" }, onDown = { events += "down:$it" },
        onLeft = { events += "previous" }, longPressItems = listOf("A", "B", "C"),
        onLongPressSelect = { events += "letter:$it" },
    )

    @Test fun `hold commits through timeout action without a release menu and keeps unrelated gestures`() {
        val events = mutableListOf<String>()
        val original = actions(events)
        val held = original.withSymbolInput(SymbolInputMode.LONG_PRESS, "2")
        assertEquals(300L, held.longPressTimeoutMillis)
        assertNull(held.onUp)
        assertTrue(held.blockUpSwipe)
        assertTrue(held.longPressItems.isEmpty())
        assertNull(held.onLongPressSelect)
        assertNotNull(held.onLongPress)
        assertTrue(events.isEmpty())
        held.onLongPress?.invoke()
        assertEquals(listOf("symbol:2"), events)
        held.onRelease()
        assertEquals(listOf("symbol:2"), events)
        held.onTap()
        held.onDown?.invoke("!")
        held.onLeft?.invoke()
        assertEquals(listOf("symbol:2", "tap", "down:!", "previous"), events)
    }

    @Test fun `hold uses the configured delay and shows the entered symbol until release`() {
        // 用户 11-08：长按时长可调；到点立刻输入，气泡显示输入的角标。
        val events = mutableListOf<String>()
        val held = actions(events).withSymbolInput(SymbolInputMode.LONG_PRESS, "2", timeoutMillis = 550L)
        assertEquals(550L, held.longPressTimeoutMillis)
        assertEquals("2", held.longPressBubbleText)
        held.onLongPress?.invoke()
        assertEquals(listOf("symbol:2"), events)
        assertEquals("默认时长仍是 0.3 秒", 300L, actions(events).withSymbolInput(SymbolInputMode.LONG_PRESS, "2").longPressTimeoutMillis)
        val swipe = actions(events)
        assertSame(swipe, swipe.withSymbolInput(SymbolInputMode.SWIPE_UP, "2", timeoutMillis = 550L))
        assertNull(swipe.longPressBubbleText)
    }

    @Test fun `swipe mode preserves the complete original behavior`() {
        val events = mutableListOf<String>()
        val original = actions(events)
        assertSame(original, original.withSymbolInput(SymbolInputMode.SWIPE_UP, "2"))
        assertEquals(listOf("A", "B", "C"), original.longPressItems)
        original.onLongPressSelect?.invoke("B")
        assertEquals(listOf("letter:B"), events)
    }

    @Test fun `function keys and dedicated menus do not opt in by their labels`() {
        val original = actions(mutableListOf())
        for (key in listOf(original.copy(onUp = null), original.copy(onLongPress = {}),
            original.copy(longPressDrawableIds = listOf(1, 2)))) {
            assertSame(key, key.withSymbolInput(SymbolInputMode.LONG_PRESS, "2"))
        }
        for (label in listOf(null, "", " ")) {
            assertSame(original, original.withSymbolInput(SymbolInputMode.LONG_PRESS, label))
        }
    }

    @Test fun `hidden hints and absent letter menus still use the actual configured input`() {
        val events = mutableListOf<String>()
        val hidden = actions(events).copy(upText = null, longPressItems = emptyList())
            .withSymbolInput(SymbolInputMode.LONG_PRESS, "！")
        assertTrue(hidden.longPressItems.isEmpty())
        assertNull(hidden.onLongPressSelect)
        hidden.onLongPress?.invoke()
        assertEquals(listOf("symbol:！"), events)
    }

    @Test fun `configured editing actions are excluded regardless of displayed label`() {
        assertEquals("2", symbolInputValue("2", GestureAction.COMMIT))
        assertEquals("?", symbolInputValue("?", null))
        for (action in GestureAction.entries.filter { it != GestureAction.COMMIT }) {
            assertNull(symbolInputValue("2", action))
        }
        assertNull(symbolInputValue("", GestureAction.COMMIT))
    }

    @Test fun `five option T9 menu fits narrow keyboards at phone and tablet densities`() {
        for (density in listOf(1f, 1.875f, 3f)) {
            // Existing custom menus must still fit a resized keyboard.
            val constrained = KeyboardKeyMetrics.longPressMenuWidthPx(80f * density, 5, 360f * density, 4f * density)
            assertEquals(352f * density, constrained, 0.001f)
            assertTrue(constrained / 5 >= 48f * density)
            val normal = KeyboardKeyMetrics.longPressMenuWidthPx(32f * density, 4, 360f * density, 4f * density)
            assertEquals(128f * density, normal, 0.001f)
        }
    }
}
