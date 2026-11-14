package com.kingzcheung.xime.settings

import android.content.SharedPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 键缝动效的设置：默认关、读不到就用默认值、存的值越界时收回范围内。 */
class KeyGapLightPreferencesTest {
    private fun prefs(values: Map<String, Any>): SharedPreferences = mock<SharedPreferences>().also { prefs ->
        whenever(prefs.getBoolean(any(), any())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
        whenever(prefs.getFloat(any(), any())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Float>(1) }
        whenever(prefs.getInt(any(), any())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
    }

    @Test fun `off by default and reading never writes`() {
        val empty = prefs(emptyMap())
        assertEquals(KeyGapLightConfig(), KeyGapLightPreferences.read(empty))
        assertFalse(KeyGapLightPreferences.read(empty).enabled)
        assertFalse(KeyboardInputPreferences().gapLight.enabled)
        verify(empty, never()).edit()
    }

    @Test fun `stored choices are read back`() {
        val config = KeyGapLightPreferences.read(prefs(mapOf(
            KeyGapLightPreferences.ENABLED to true, KeyGapLightPreferences.STYLE to "diamond",
            KeyGapLightPreferences.SPEED to 1.5f, KeyGapLightPreferences.REACH to 3f, KeyGapLightPreferences.WIDTH to 3f,
            KeyGapLightPreferences.BRIGHTNESS to .5f, KeyGapLightPreferences.TAIL to .2f, KeyGapLightPreferences.LAYERS to 3,
            KeyGapLightPreferences.TYPING to false, KeyGapLightPreferences.DELETE to false,
            KeyGapLightPreferences.COMMIT to false, KeyGapLightPreferences.SWITCH to false,
            KeyGapLightPreferences.HOLD to false, KeyGapLightPreferences.SPACE to false,
            KeyGapLightPreferences.VOICE to false)))
        assertEquals(KeyGapLightConfig(true, KeyGapLightStyle.DIAMOND, 1.5f, 3f, 3f, .5f, .2f, 3,
            typing = false, delete = false, commit = false, switch = false, hold = false, space = false, voice = false), config)
    }

    @Test fun `out of range and unknown values fall back`() {
        val config = KeyGapLightPreferences.read(prefs(mapOf(
            KeyGapLightPreferences.STYLE to "sparkles", KeyGapLightPreferences.SPEED to 0f, KeyGapLightPreferences.REACH to 99f,
            KeyGapLightPreferences.WIDTH to -1f, KeyGapLightPreferences.BRIGHTNESS to 5f, KeyGapLightPreferences.TAIL to 2f,
            KeyGapLightPreferences.LAYERS to 0)))
        assertEquals(KeyGapLightStyle.TRACE, config.style)
        assertEquals(KeyGapLightPreferences.SPEED_RANGE.start, config.speed, 0f)
        assertEquals(KeyGapLightPreferences.REACH_RANGE.endInclusive, config.reach, 0f)
        assertEquals(KeyGapLightPreferences.WIDTH_RANGE.start, config.widthDp, 0f)
        assertEquals(KeyGapLightPreferences.BRIGHTNESS_RANGE.endInclusive, config.brightness, 0f)
        assertEquals(KeyGapLightPreferences.TAIL_RANGE.endInclusive, config.tail, 0f)
        assertEquals(KeyGapLightPreferences.LAYERS_RANGE.first, config.layers)
    }

    @Test fun `style ids are the stored values`() {
        // 存进设置里的字，改了会让已经选过的人回到默认。
        assertEquals(listOf("trace", "frame", "diamond"), KeyGapLightStyle.entries.map { it.id })
        KeyGapLightStyle.entries.forEach { assertEquals(it, KeyGapLightStyle.of(it.id)) }
    }

    @Test fun `the showing keyboard hears every key and the cost is registered`() {
        // 设置页一改，正在显示的键盘就要重新读；少登记一个键，那一项要等输入法重启才生效。
        assertTrue(KeyboardInputPreferences.watchedKeys.containsAll(KeyGapLightPreferences.keys))
        assertEquals(15, KeyGapLightPreferences.keys.size)
        assertNotNull(PerformanceCosts.Item.byId("key_gap_light"))
    }

    @Test fun `every event is on once the switch is on`() {
        // 总开关默认关；打开以后各种动效都默认有，不用再逐个去开。
        val config = KeyGapLightPreferences.read(prefs(mapOf(KeyGapLightPreferences.ENABLED to true)))
        assertTrue(config.typing && config.delete && config.commit && config.switch && config.hold && config.space && config.voice)
    }

    @Test fun `every effect has its own cost entry and follows the main switch`() {
        // 用户 11-11「性能一定要分的细一点」：每种动效各记一项代价，id 就是它的设置键。
        assertEquals(7, KeyGapLightPreferences.eventKeys.size)
        assertTrue(KeyGapLightPreferences.keys.containsAll(KeyGapLightPreferences.eventKeys))
        KeyGapLightPreferences.eventKeys.forEach { assertNotNull(it, PerformanceCosts.Item.byId(it)) }
        // 总开关关着时哪一种都不算开着；开了以后分项默认开，单独关掉的那种不算。
        assertFalse(KeyGapLightPreferences.eventEnabled(prefs(emptyMap()), KeyGapLightPreferences.TYPING))
        val on = prefs(mapOf(KeyGapLightPreferences.ENABLED to true, KeyGapLightPreferences.COMMIT to false))
        assertTrue(KeyGapLightPreferences.eventEnabled(on, KeyGapLightPreferences.TYPING))
        assertFalse(KeyGapLightPreferences.eventEnabled(on, KeyGapLightPreferences.COMMIT))
    }

    @Test fun `the performance page turns one effect on or off`() {
        fun editing(): Pair<SharedPreferences, SharedPreferences.Editor> {
            val editor = mock<SharedPreferences.Editor>()
            whenever(editor.putBoolean(any(), any())).thenReturn(editor)
            val prefs = mock<SharedPreferences>()
            whenever(prefs.edit()).thenReturn(editor)
            return prefs to editor
        }
        // 开一种：总开关没开就一起开。
        val (onPrefs, onEditor) = editing()
        KeyGapLightPreferences.setEvent(onPrefs, KeyGapLightPreferences.HOLD, true)
        verify(onEditor).putBoolean(KeyGapLightPreferences.HOLD, true)
        verify(onEditor).putBoolean(KeyGapLightPreferences.ENABLED, true)
        verify(onEditor).apply()
        // 关一种：只关它，总开关和别的几种不动。
        val (offPrefs, offEditor) = editing()
        KeyGapLightPreferences.setEvent(offPrefs, KeyGapLightPreferences.HOLD, false)
        verify(offEditor).putBoolean(KeyGapLightPreferences.HOLD, false)
        verify(offEditor, never()).putBoolean(org.mockito.kotlin.eq(KeyGapLightPreferences.ENABLED), any())
        // 不是分项的键不写。
        val (otherPrefs, _) = editing()
        KeyGapLightPreferences.setEvent(otherPrefs, KeyGapLightPreferences.STYLE, true)
        verify(otherPrefs, never()).edit()
    }

    @Test fun `saving writes every key and keeps values in range`() {
        val editor = mock<SharedPreferences.Editor>()
        whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
        whenever(editor.putFloat(any(), any())).thenReturn(editor)
        whenever(editor.putInt(any(), any())).thenReturn(editor)
        val prefs = mock<SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)

        KeyGapLightPreferences.save(prefs, KeyGapLightConfig(enabled = true, style = KeyGapLightStyle.FRAME,
            speed = 9f, reach = 0f, widthDp = 2.5f, brightness = .7f, tail = .3f, layers = 7,
            typing = false, delete = true, commit = false, switch = true, hold = false, space = true, voice = false))

        verify(editor).putBoolean(KeyGapLightPreferences.ENABLED, true)
        verify(editor).putString(KeyGapLightPreferences.STYLE, "frame")
        verify(editor).putFloat(KeyGapLightPreferences.SPEED, KeyGapLightPreferences.SPEED_RANGE.endInclusive)
        verify(editor).putFloat(KeyGapLightPreferences.REACH, KeyGapLightPreferences.REACH_RANGE.start)
        verify(editor).putFloat(KeyGapLightPreferences.WIDTH, 2.5f)
        verify(editor).putFloat(KeyGapLightPreferences.BRIGHTNESS, .7f)
        verify(editor).putFloat(KeyGapLightPreferences.TAIL, .3f)
        verify(editor).putInt(KeyGapLightPreferences.LAYERS, KeyGapLightPreferences.LAYERS_RANGE.last)
        verify(editor).putBoolean(KeyGapLightPreferences.TYPING, false)
        verify(editor).putBoolean(KeyGapLightPreferences.DELETE, true)
        verify(editor).putBoolean(KeyGapLightPreferences.COMMIT, false)
        verify(editor).putBoolean(KeyGapLightPreferences.SWITCH, true)
        verify(editor).putBoolean(KeyGapLightPreferences.HOLD, false)
        verify(editor).putBoolean(KeyGapLightPreferences.SPACE, true)
        verify(editor).putBoolean(KeyGapLightPreferences.VOICE, false)
        verify(editor).apply()
    }
}
