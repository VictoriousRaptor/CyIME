package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.GestureDef
import com.kingzcheung.xime.settings.KeysConfigHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 笔画主键区的点按可以在 keys.<id>.tap 里改（用户 11-17 转来的贡献者建议）：
 * 默认排法不变（* 在 6、分词在 7）；键面字和送出值成对改，角标数字和上滑按位置不动。
 */
class StrokeKeyTapTest {
    private fun def(id: String) = strokeMainKeys.single { it.id == id }

    @Test fun `default layout is the usual stroke keyboard`() {
        assertEquals(listOf("一", "丨", "丿", "丶", "乛", "*", "分词", "，", "。"), strokeMainKeys.map { it.id })
        assertEquals((1..9).map(Int::toString), strokeMainKeys.map { it.swipeDigit })
        assertEquals(listOf("h", "s", "p", "n", "z", "*", "word_separator", "，", "。"), strokeMainKeys.map { it.commit })
        for (key in strokeMainKeys) assertEquals(StrokeTap(key.id, GestureAction.COMMIT, key.commit), strokeTapFor(key, null))
    }

    @Test fun `swapping star and separator only swaps the face and the tap`() {
        val yaml = """
            keyboard:
              stroke:
                keys:
                  "*": { tap: { label: "分词", value: "word_separator" } }
                  "分词": { tap: { label: "*", value: "*" } }
        """.trimIndent()
        val keys = KeysConfigHelper.parseKeyboardYamlSection(yaml, "stroke")!!
        assertEquals(StrokeTap("分词", GestureAction.COMMIT, "word_separator"), strokeTapFor(def("*"), keys.getValue("*").tap))
        assertEquals(StrokeTap("*", GestureAction.COMMIT, "*"), strokeTapFor(def("分词"), keys.getValue("分词").tap))
        // 只写了 tap：上滑没配，键盘按位置回退到 6、7（swipesFor 的回退），位置的数字不跟着换。
        assertNull(keys.getValue("*").swipeUp)
        assertEquals("6", def("*").swipeDigit)
        assertEquals("7", def("分词").swipeDigit)
    }

    @Test fun `value without label shows the matching default face`() {
        assertEquals("分词", strokeTapFor(def("*"), GestureDef(value = "word_separator")).label)
        assertEquals("一", strokeTapFor(def("乛"), GestureDef(value = "h")).label)
        assertEquals("@", strokeTapFor(def("。"), GestureDef(value = "@")).label)
        // 只写 label：送出 label。
        assertEquals(StrokeTap("？", GestureAction.COMMIT, "？"), strokeTapFor(def("。"), GestureDef(label = "？")))
        // 空的 tap 等于没配。
        assertEquals(StrokeTap("。", GestureAction.COMMIT, "。"), strokeTapFor(def("。"), GestureDef()))
    }

    @Test fun `tap can be an action`() {
        val tap = strokeTapFor(def("，"), GestureDef(label = "粘贴", action = GestureAction.PASTE, value = ""))
        assertEquals("粘贴", tap.label)
        assertEquals(GestureAction.PASTE, tap.action)
    }
}
