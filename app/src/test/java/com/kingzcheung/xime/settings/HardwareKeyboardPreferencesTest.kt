package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class HardwareKeyboardPreferencesTest {
    private val context = mock<Context>()
    private val prefs = mock<SharedPreferences>()
    private val editor = mock<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, Boolean>()
    private val sets = mutableMapOf<String, Set<String>>()
    private val strings = mutableMapOf<String, String>()

    @Before fun setUp() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1)
        }
        whenever(editor.putBoolean(any(), any())).thenAnswer {
            values[it.getArgument(0)] = it.getArgument<Boolean>(1)
            editor
        }
        whenever(prefs.getStringSet(any(), org.mockito.kotlin.anyOrNull())).thenAnswer { sets[it.getArgument<String>(0)] }
        whenever(editor.putStringSet(any(), any())).thenAnswer {
            sets[it.getArgument(0)] = it.getArgument<Set<String>>(1).toSet()
            editor
        }
        whenever(prefs.getString(any(), org.mockito.kotlin.anyOrNull())).thenAnswer { strings[it.getArgument<String>(0)] }
        whenever(editor.putString(any(), any())).thenAnswer {
            strings[it.getArgument(0)] = it.getArgument<String>(1)
            editor
        }
    }

    @Test fun passthroughAppsAreEmptyByDefaultAndToggle() {
        // #265：默认没有单独直通的应用。
        assertEquals(emptySet<String>(), HardwareKeyboardPreferences.read(context).passthroughApps)
        HardwareKeyboardPreferences.setPassthroughApp(context, "com.example.remote", true)
        HardwareKeyboardPreferences.setPassthroughApp(context, "com.example.emu", true)
        assertEquals(setOf("com.example.remote", "com.example.emu"), HardwareKeyboardPreferences.read(context).passthroughApps)
        HardwareKeyboardPreferences.setPassthroughApp(context, "com.example.remote", false)
        assertEquals(setOf("com.example.emu"), HardwareKeyboardPreferences.read(context).passthroughApps)
    }

    @Test fun lastAppIsRememberedForTheSettingsPage() {
        assertNull(HardwareKeyboardPreferences.lastApp(context))
        HardwareKeyboardPreferences.noteLastApp(context, "com.example.remote")
        assertEquals("com.example.remote", HardwareKeyboardPreferences.lastApp(context))
    }

    @Test fun existingUsersKeepAllPagingGroupsWithoutWritingPreferences() {
        values[HardwareKeyboardPreferences.PREFIX + "shift_tap"] = false
        values[HardwareKeyboardPreferences.PREFIX + "follow_cursor"] = false
        val original = values.toMap()

        assertEquals(HardwareKeyboardOptions(shiftTap = false, followCursor = false),
            HardwareKeyboardPreferences.read(context))
        assertEquals(original, values)
    }

    @Test fun associationInWindowIsOffByDefaultAndPersists() {
        assertFalse(HardwareKeyboardPreferences.read(context).associationInWindow)
        HardwareKeyboardPreferences.set(context, "association_in_window", true)
        assertEquals(HardwareKeyboardOptions(associationInWindow = true), HardwareKeyboardPreferences.read(context))
    }

    @Test fun gamePassthroughIsOffByDefaultAndPersists() {
        assertFalse(HardwareKeyboardPreferences.read(context).gamePassthrough)
        HardwareKeyboardPreferences.set(context, "game_passthrough", true)
        assertEquals(HardwareKeyboardOptions(gamePassthrough = true), HardwareKeyboardPreferences.read(context))
        HardwareKeyboardPreferences.set(context, "game_passthrough", false)
        assertEquals(HardwareKeyboardOptions(), HardwareKeyboardPreferences.read(context))
    }

    @Test fun toolShortcutsAreOnByDefaultAndPersist() {
        assertTrue(HardwareKeyboardPreferences.read(context).toolShortcuts)
        HardwareKeyboardPreferences.set(context, "tool_shortcuts", false)
        assertEquals(HardwareKeyboardOptions(toolShortcuts = false), HardwareKeyboardPreferences.read(context))
        HardwareKeyboardPreferences.set(context, "tool_shortcuts", true)
        assertEquals(HardwareKeyboardOptions(), HardwareKeyboardPreferences.read(context))
    }

    @Test fun eachPagingChoicePersistsIndependentlyAndDoesNotChangeShortcuts() {
        for (mask in 0..7) {
            HardwareKeyboardPreferences.set(context, "page_minus_equals", mask and 1 != 0)
            HardwareKeyboardPreferences.set(context, "page_brackets", mask and 2 != 0)
            HardwareKeyboardPreferences.set(context, "page_comma_period", mask and 4 != 0)
            assertEquals(HardwareKeyboardOptions(
                pageMinusEquals = mask and 1 != 0,
                pageBrackets = mask and 2 != 0,
                pageCommaPeriod = mask and 4 != 0,
            ), HardwareKeyboardPreferences.read(context))
        }
        assertEquals(3, values.size)
    }
}
