package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.KeyEffectPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class KeyboardInputPreferencesTest {
    @Test fun `symbol key cycle defaults off and persists without changing gestures or other settings`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.symbolKeyCycle)
        assertTrue(KeyboardInputPreferences.SYMBOL_KEY_CYCLE_KEY in KeyboardInputPreferences.watchedKeys)
        for (enabled in listOf(true, false)) {
            before.copy(symbolKeyCycle = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.symbolKeyCycle)
            assertEquals(before, after.copy(symbolKeyCycle = false))
        }
    }

    @Test fun `kana hints persist independently of symbol direction and cursor gestures`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.kanaDirectionHints)
        for (enabled in listOf(true, false)) {
            before.copy(kanaDirectionHints = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.kanaDirectionHints)
            assertEquals(before, after.copy(kanaDirectionHints = false))
        }
    }

    @Test fun `choosing voice for long-press space works even while space moves the cursor`() {
        val cursorOnSpace = KeyboardInputPreferences(cursorGesture = CursorGestureMode.SPACE)
        assertEquals(SpaceHoldAction.CURSOR, cursorOnSpace.effectiveSpaceHold)
        val voice = cursorOnSpace.withSpaceHold(SpaceHoldAction.VOICE_TOGGLE)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, voice.effectiveSpaceHold)
        assertEquals(CursorGestureMode.KEYBOARD, voice.cursorGesture)
        // 英文滑行锁着键盘滑动：移动光标关掉，不去占用滑行。
        val glide = cursorOnSpace.copy(glideTyping = true).withSpaceHold(SpaceHoldAction.REPEAT)
        assertEquals(SpaceHoldAction.REPEAT, glide.effectiveSpaceHold)
        assertEquals(CursorGestureMode.NONE, glide.cursorGesture)
        assertEquals(true, glide.glideTyping)
        // 没占着空格时只换空格功能，移动光标方式原样不动。
        for (mode in listOf(CursorGestureMode.KEYBOARD, CursorGestureMode.NONE)) {
            val other = KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT, cursorGesture = mode)
                .withSpaceHold(SpaceHoldAction.VOICE_TOGGLE)
            assertEquals(mode, other.cursorGesture)
            assertEquals(SpaceHoldAction.VOICE_TOGGLE, other.effectiveSpaceHold)
        }
    }

    @Test fun `uppercase key labels default off and persist without touching other settings`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.uppercaseKeyLabels)
        assertTrue(KeyboardInputPreferences.UPPERCASE_KEY_LABELS_KEY in KeyboardInputPreferences.watchedKeys)
        for (enabled in listOf(true, false)) {
            before.copy(uppercaseKeyLabels = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.uppercaseKeyLabels)
            assertEquals(before, after.copy(uppercaseKeyLabels = false))
        }
    }

    @Test fun `chinese uppercase key labels default off and persist without touching other settings`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.chineseUppercaseKeyLabels)
        assertTrue(KeyboardInputPreferences.CHINESE_UPPERCASE_KEY_LABELS_KEY in KeyboardInputPreferences.watchedKeys)
        for (enabled in listOf(true, false)) {
            before.copy(chineseUppercaseKeyLabels = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.chineseUppercaseKeyLabels)
            assertEquals(before, after.copy(chineseUppercaseKeyLabels = false))
        }
    }

    @Test fun `symbol direction defaults to pulling into key and persists either habit`() {
        assertEquals(false, KeyboardInputPreferences.read(context).reverseSymbolSwipe)
        for (reversed in listOf(true, false)) {
            val before = KeyboardInputPreferences.read(context)
            before.copy(reverseSymbolSwipe = reversed).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(reversed, after.reverseSymbolSwipe)
            assertEquals(before, after.copy(reverseSymbolSwipe = before.reverseSymbolSwipe))
        }
    }
    @Test fun `fourteen swipe preferences reload without changing shared cursor or symbol settings`() {
        val previous = KeyboardInputPreferences.read(context)
        assertEquals(true, previous.fourteenLetterSwipe)
        assertEquals(24f, previous.fourteenLetterSwipeDp, 0f)
        previous.copy(fourteenLetterSwipe = false, fourteenLetterSwipeDp = 51f).save(context)
        val restored = KeyboardInputPreferences.read(context)
        assertEquals(false, restored.fourteenLetterSwipe)
        assertEquals(52f, restored.fourteenLetterSwipeDp, 0f)
        assertEquals(previous.cursorGesture, restored.cursorGesture)
        assertEquals(previous.symbolInputMode, restored.symbolInputMode)
        assertEquals(previous.neighborCorrection, restored.neighborCorrection)
    }

    @Test fun `glide typing is off by default and the old saved default does not turn it on`() {
        assertEquals(false, KeyboardInputPreferences.read(context).glideTyping)
        // 旧版 save() 会把旧默认 true 连同其他设置一起写进 glide_typing：不能因此打开。
        values["glide_typing"] = true
        assertEquals(false, KeyboardInputPreferences.read(context).glideTyping)
        // 用户在新版自己打开后保持打开。
        KeyboardInputPreferences.read(context).copy(glideTyping = true).save(context)
        assertEquals(true, KeyboardInputPreferences.read(context).glideTyping)
        assertEquals(listOf("glide_typing_v2", "glide_typing"), com.kingzcheung.xime.keyboard.ToolbarButton.GLIDE.gestureKeys())
    }

    @Test fun `chinese glide has its own switch and an english glide user does not get it`() {
        // 用户 10-31：老版本开着英文滑行，升级后中文 26 键不能跟着滑行。
        values["glide_typing_v2"] = true
        values["glide_typing"] = true
        val upgraded = KeyboardInputPreferences.read(context)
        assertEquals(true, upgraded.glideTyping)
        assertEquals(false, upgraded.chineseGlideTyping)
        // 滑行实时预览（11-02 别的用户提的）同样默认关。
        assertEquals(false, upgraded.glidePreview)
        upgraded.save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).chineseGlideTyping)
        // 自己打开中文滑行后保持打开，英文滑行原样不动。
        KeyboardInputPreferences.read(context).copy(chineseGlideTyping = true).save(context)
        val chinese = KeyboardInputPreferences.read(context)
        assertEquals(true, chinese.chineseGlideTyping)
        assertEquals(true, chinese.glideTyping)
        // 只开中文滑行时同样锁住键盘滑动移光标。
        val onlyChinese = KeyboardInputPreferences(chineseGlideTyping = true)
        assertEquals("已被「中文滑行」锁定", InputGestureLocks.lockFor(onlyChinese,
            InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD)?.message)
        // 用户 11-08：开着滑行时字母键的滑动都给滑行，角标锁在「长按输入」，并说明是中文滑行锁的。
        assertEquals(CornerInputChoice.LONG_PRESS, CornerInputChoice.of(CornerInputChoice.SWIPE.applyTo(onlyChinese)))
        assertEquals("已被「中文滑行」锁定", InputGestureLocks.glideCornerLock(onlyChinese)?.message)
    }

    @Test fun `comma and period keys are both on by default and the old saved period default does not turn it off`() {
        val fresh = KeyboardInputPreferences.read(context)
        assertEquals(true, fresh.qwertyCommaKey)
        assertEquals(true, fresh.qwertyPeriodKey)
        // 旧版 save() 会把旧默认 false 连同其他设置一起写进 qwerty_period_key：不能因此关掉。
        values["qwerty_period_key"] = false
        assertEquals(true, KeyboardInputPreferences.read(context).qwertyPeriodKey)
        // 用户在新版自己关掉后保持关。
        KeyboardInputPreferences.read(context).copy(qwertyPeriodKey = false).save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).qwertyPeriodKey)
    }

    @Test fun `corner hint opacity defaults to today's look, persists and is bounded`() {
        assertEquals(1f, KeyboardInputPreferences().cornerHintOpacity, 0f)
        assertEquals(1f, KeyboardInputPreferences.read(context).cornerHintOpacity, 0f)
        KeyboardInputPreferences.read(context).copy(cornerHintOpacity = 0.35f).save(context)
        assertEquals(0.35f, KeyboardInputPreferences.read(context).cornerHintOpacity, 0.001f)
        assertEquals(0.1f, normalizeCornerHintOpacity(0f), 0f)
        assertEquals(1f, normalizeCornerHintOpacity(3f), 0f)
        assertEquals(1f, normalizeCornerHintOpacity(Float.NaN), 0f)
        assertTrue(KeyboardInputPreferences.CORNER_HINT_OPACITY_KEY in KeyboardInputPreferences.watchedKeys)
    }

    @Test fun `time and date candidates are off by default and stay on once turned on`() {
        // 更新不改原有习惯（用户 10-31）：新功能默认关，在 语言与方案 › 中文输出 打开。
        assertEquals(false, KeyboardInputPreferences().datetimeCandidates)
        assertEquals(false, KeyboardInputPreferences.read(context).datetimeCandidates)
        KeyboardInputPreferences.read(context).copy(datetimeCandidates = false).save(context)
        assertEquals(false, values[KeyboardInputPreferences.DATETIME_CANDIDATES_KEY])
        assertEquals(false, KeyboardInputPreferences.read(context).datetimeCandidates)
        KeyboardInputPreferences.read(context).copy(datetimeCandidates = true).save(context)
        assertEquals(true, KeyboardInputPreferences.read(context).datetimeCandidates)
    }

    @Test fun `symbol name candidates are off by default and persist`() {
        // 别人请求的功能默认关，不改原有习惯；在 语言与方案 › 中文输出 打开。
        assertEquals(false, KeyboardInputPreferences().symbolNames)
        assertEquals(false, KeyboardInputPreferences.read(context).symbolNames)
        KeyboardInputPreferences.read(context).copy(symbolNames = true).save(context)
        assertEquals(true, values[KeyboardInputPreferences.SYMBOL_NAMES_KEY])
        assertEquals(true, KeyboardInputPreferences.read(context).symbolNames)
        KeyboardInputPreferences.read(context).copy(symbolNames = false).save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).symbolNames)
    }

    @Test fun `greek letter candidates are off by default and persist`() {
        // 别人请求的功能默认关，不改原有习惯；在 语言与方案 › 中文输出 打开。
        assertEquals(false, KeyboardInputPreferences().greekLetters)
        assertEquals(false, KeyboardInputPreferences.read(context).greekLetters)
        KeyboardInputPreferences.read(context).copy(greekLetters = true).save(context)
        assertEquals(true, values[KeyboardInputPreferences.GREEK_LETTERS_KEY])
        assertEquals(true, KeyboardInputPreferences.read(context).greekLetters)
        KeyboardInputPreferences.read(context).copy(greekLetters = false).save(context)
        assertEquals(false, KeyboardInputPreferences.read(context).greekLetters)
    }

    @Test fun `bottom lift cursor is off by default and persists without touching the cursor gesture choice`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.bottomLiftCursor)
        for (enabled in listOf(true, false)) {
            before.copy(bottomLiftCursor = enabled).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(enabled, after.bottomLiftCursor)
            assertEquals(before, after.copy(bottomLiftCursor = false))
        }
        assertTrue(KeyboardInputPreferences.BOTTOM_LIFT_CURSOR_KEY in KeyboardInputPreferences.watchedKeys)
    }

    @Test fun `delete repeat speed defaults to the old feel and persists every step`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.STANDARD, before.deleteRepeatSpeed)
        for (speed in com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.entries) {
            before.copy(deleteRepeatSpeed = speed).save(context)
            val after = KeyboardInputPreferences.read(context)
            assertEquals(speed, after.deleteRepeatSpeed)
            assertEquals(before, after.copy(deleteRepeatSpeed = before.deleteRepeatSpeed))
        }
        assertTrue(com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.KEY in KeyboardInputPreferences.watchedKeys)
    }

    @Test fun `delete swipe reverse and clear distance default to the old behaviour and persist`() {
        val before = KeyboardInputPreferences.read(context)
        assertEquals(false, before.reverseDeleteSwipe)
        assertEquals(100f, before.clearSwipePercent, 0.001f)
        assertEquals(80f, before.clearSwipeDistanceDp, 0.001f)
        assertEquals(before.reverseSymbolSwipe, before.deleteSwipeReversed)
        before.copy(reverseDeleteSwipe = true, clearSwipePercent = 70f).save(context)
        val after = KeyboardInputPreferences.read(context)
        assertEquals(true, after.reverseDeleteSwipe)
        assertEquals(56f, after.clearSwipeDistanceDp, 0.001f)
        assertEquals(before, after.copy(reverseDeleteSwipe = false, clearSwipePercent = 100f))
        // 单独反向在「反转上下滑方向」之上再对调
        assertEquals(true, KeyboardInputPreferences(reverseDeleteSwipe = true).deleteSwipeReversed)
        assertEquals(false, KeyboardInputPreferences(reverseSymbolSwipe = true, reverseDeleteSwipe = true).deleteSwipeReversed)
        assertTrue(KeyboardInputPreferences.REVERSE_DELETE_SWIPE_KEY in KeyboardInputPreferences.watchedKeys)
        assertTrue(KeyboardInputPreferences.CLEAR_SWIPE_PERCENT_KEY in KeyboardInputPreferences.watchedKeys)
    }

    @Test fun `clear distance stays inside the narrow fine-tune range`() {
        assertEquals(70f, normalizeClearSwipePercent(10f), 0.001f)
        assertEquals(120f, normalizeClearSwipePercent(500f), 0.001f)
        assertEquals(90f, normalizeClearSwipePercent(88f), 0.001f)
        assertEquals(100f, normalizeClearSwipePercent(Float.NaN), 0.001f)
    }

    @Test fun `every saved setting refreshes the keyboard already on screen`() {
        // 设置页关掉滑行输入或九键拂动后，正在显示的键盘必须立即改用新值，不能等输入法重启。
        KeyboardInputPreferences().copy(glideTyping = false, t9FlickLetters = true).save(context)
        val unwatched = values.keys - KeyboardInputPreferences.watchedKeys
        assertTrue("save() 写入但键盘不监听：$unwatched", unwatched.isEmpty())
        assertTrue("glide_typing" in values && "t9_flick_letters" in values)
    }

    private val context = mock<Context>()
    private val prefs = mock<SharedPreferences>()
    private val editor = mock<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        whenever(prefs.getInt(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1)
        }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
        }
        whenever(prefs.getBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
        }
        whenever(prefs.getFloat(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Float ?: it.getArgument<Float>(1)
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer {
            val value = it.getArgument<String?>(1)
            if (value != null) values[it.getArgument(0)] = value
            editor
        }
        whenever(editor.putBoolean(any(), any())).thenAnswer {
            values[it.getArgument(0)] = it.getArgument<Boolean>(1)
            editor
        }
        whenever(editor.putFloat(any(), any())).thenAnswer {
            values[it.getArgument(0)] = it.getArgument<Float>(1)
            editor
        }
        whenever(editor.remove(any())).thenAnswer {
            values.remove(it.getArgument<String>(0))
            editor
        }
    }

    @Test
    fun `existing installs default to cursor hold and usable sensitivity`() {
        val settings = KeyboardInputPreferences.read(context)
        assertEquals(SpaceHoldAction.CURSOR, settings.spaceHold)
        assertEquals(0.2f, settings.cursorHoldSeconds, 0f)
        assertEquals(200L, settings.spaceHoldDelayMs)
        assertEquals(10f, settings.cursorStepDp, 0f)
        assertEquals(1.2f, settings.keyTextScale, 0f)
        assertEquals(1.2f, KeyboardInputPreferences().keyTextScale, 0f)
        values["key_text_scale"] = 1.15f
        assertEquals(1.15f, KeyboardInputPreferences.read(context).keyTextScale, 0f)
        assertEquals(0.5f, settings.handwritingPauseSeconds, 0f)
        assertNull(settings.symbols())
    }

    @Test fun `visual effects are opt in and explicit choices are respected`() {
        assertEquals(false, KeyboardInputPreferences.read(context).keyGlowEnabled)
        // 按键浮标默认开（用户 11-09），关掉的照样关。
        assertEquals(true, KeyboardInputPreferences.read(context).showPressBubble)
        values["key_glow_enabled"] = true
        values[SettingsPreferences.KEY_SHOW_PRESS_BUBBLE] = false
        assertEquals(true, KeyboardInputPreferences.read(context).keyGlowEnabled)
        assertEquals(false, KeyboardInputPreferences.read(context).showPressBubble)
    }

    @Test fun `frosted glass is opt in and survives unrelated input preference saves`() {
        assertEquals(FrostedGlassConfig(), KeyboardInputPreferences.read(context).frostedGlass)
        val effect = FrostedGlassConfig(true, 18f, 0.7f, 0.3f)
        FrostedGlassPreferences.save(context, effect)
        // 保存的是用户值；键盘实际使用的值只会为可读性加深，不会更透。
        fun assertKept() {
            assertEquals(effect, FrostedGlassPreferences.read(context))
            val shown = KeyboardInputPreferences.read(context).frostedGlass
            assertEquals(effect.blurRadiusDp, shown.blurRadiusDp, 0f)
            assertTrue(shown.enabled && shown.backgroundOpacity >= effect.backgroundOpacity &&
                shown.keyOpacity >= effect.keyOpacity)
        }
        assertKept()
        KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT).save(context)
        assertKept()
        assertEquals(false, KeyboardInputPreferences.read(context).keyGlowEnabled)
    }

    @Test fun `rendered appearance chooses its own glass controls despite saved display mode`() {
        values["dark_mode"] = 0
        // 浅色、深色各存各的：后存的浅色不改深色。
        val dark = FrostedGlassConfig(true, 15f, 0.84f, 0.24f)
        val light = FrostedGlassConfig(true, 11f, 0.30f, 0.84f)
        FrostedGlassPreferences.save(context, dark, true)
        FrostedGlassPreferences.save(context, light, false)
        // 白色玻璃显示时底色补到 WHITE_GLASS_PANEL_OPACITY（保存的值不变）。
        val shownLight = light.copy(backgroundOpacity = WHITE_GLASS_PANEL_OPACITY)
        assertEquals(dark, KeyboardInputPreferences.read(context, true).frostedGlass)
        assertEquals(shownLight, KeyboardInputPreferences.read(context, false).frostedGlass)
        values["dark_mode"] = 1
        assertEquals(shownLight, KeyboardInputPreferences.read(context, false).frostedGlass)
        assertEquals(dark, KeyboardInputPreferences.read(context).frostedGlass)
    }

    @Test
    fun `saved input preferences can be read without restarting the app`() {
        val changed = KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT, cursorStepDp = 6f, keyTextScale = 1.4f, fixedSymbols = "…\n→",
            showPressBubble = true) // 按键浮标是单独的设置，默认开
        changed.save(context)
        assertEquals(changed, KeyboardInputPreferences.read(context))
        changed.copy(spaceHold = SpaceHoldAction.CURSOR, cursorStepDp = 24f).save(context)
        assertEquals(SpaceHoldAction.CURSOR, KeyboardInputPreferences.read(context).spaceHold)
        assertEquals(24f, KeyboardInputPreferences.read(context).cursorStepDp, 0f)
    }

    @Test fun `cursor hold delay persists tenths normalizes invalid values and leaves other actions unchanged`() {
        for (tenth in 1..10) {
            val seconds = tenth / 10f
            KeyboardInputPreferences(cursorHoldSeconds = seconds).save(context)
            val restored = KeyboardInputPreferences.read(context)
            assertEquals(seconds, restored.cursorHoldSeconds, 0f)
            assertEquals(tenth * 100L, restored.spaceHoldDelayMs)
            for (action in listOf(SpaceHoldAction.REPEAT, SpaceHoldAction.VOICE_TOGGLE)) {
                assertEquals("every space hold action uses the space hold delay", tenth * 100L,
                    restored.copy(spaceHold = action, cursorGesture = CursorGestureMode.NONE).spaceHoldDelayMs)
                assertEquals("Space cursor override uses its configured delay", tenth * 100L,
                    restored.copy(spaceHold = action, cursorGesture = CursorGestureMode.SPACE).spaceHoldDelayMs)
            }
        }
        val invalid = listOf(
            Float.NaN to 0.2f,
            Float.POSITIVE_INFINITY to 0.2f,
            Float.NEGATIVE_INFINITY to 0.2f,
            -1f to 0.1f,
            9f to 1f,
            0.26f to 0.3f,
        )
        for ((stored, expected) in invalid) {
            values["cursor_hold_seconds"] = stored
            assertEquals("Read normalizes $stored", expected, KeyboardInputPreferences.read(context).cursorHoldSeconds, 0f)
            KeyboardInputPreferences(cursorHoldSeconds = stored).save(context)
            assertEquals("Save normalizes $stored", expected, values["cursor_hold_seconds"] as Float, 0f)
            assertEquals(expected, KeyboardInputPreferences.read(context).cursorHoldSeconds, 0f)
        }
    }

    @Test
    fun `legacy voice hold migrates to cursor`() {
        values["space_hold_action"] = "VOICE"
        assertEquals(SpaceHoldAction.CURSOR, KeyboardInputPreferences.read(context).spaceHold)
    }

    @Test fun `symbol input keeps swipe default and persists an explicit hold choice`() {
        assertEquals(SymbolInputMode.SWIPE_UP, KeyboardInputPreferences.read(context).symbolInputMode)
        val hold = KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS,
            spaceHold = SpaceHoldAction.VOICE_TOGGLE, cursorGesture = CursorGestureMode.KEYBOARD, showPressBubble = true)
        hold.save(context)
        assertEquals(hold, KeyboardInputPreferences.read(context))
        hold.copy(symbolInputMode = SymbolInputMode.SWIPE_UP).save(context)
        assertEquals(SymbolInputMode.SWIPE_UP, KeyboardInputPreferences.read(context).symbolInputMode)
        values["symbol_input_mode"] = "unknown-future-mode"
        assertEquals(SymbolInputMode.SWIPE_UP, KeyboardInputPreferences.read(context).symbolInputMode)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).effectiveSpaceHold)
    }

    @Test fun `explicit new voice toggle survives save while cursor override preserves it`() {
        val voice = KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE, cursorGesture = CursorGestureMode.KEYBOARD)
        voice.save(context)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).effectiveSpaceHold)
        voice.copy(cursorGesture = CursorGestureMode.SPACE).save(context)
        assertEquals(SpaceHoldAction.CURSOR, KeyboardInputPreferences.read(context).effectiveSpaceHold)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).spaceHold)
    }

    @Test
    fun `unknown modes and invalid numeric settings recover to usable defaults`() {
        values["space_hold_action"] = "REMOVED_MODE"
        values["cursor_step_dp"] = Float.NaN
        values["key_text_scale"] = Float.POSITIVE_INFINITY
        val settings = KeyboardInputPreferences.read(context)
        assertEquals(SpaceHoldAction.CURSOR, settings.spaceHold)
        assertEquals(10f, settings.cursorStepDp, 0f)
        assertEquals(1.2f, settings.keyTextScale, 0f)
    }

    @Test
    fun `fixed symbols preserve phrases and emoji while removing blank duplicate lines`() {
        val settings = KeyboardInputPreferences(fixedSymbols = " … \n\n👨‍👩‍👧‍👦\n自定义短语\n…\n")
        assertEquals(listOf("…", "👨‍👩‍👧‍👦", "自定义短语"), settings.symbols())
        assertNull(settings.copy(fixedSymbols = " \n \n").symbols())
    }
    @Test fun `handwriting pause persists and rejects invalid intervals`() {
        KeyboardInputPreferences(handwritingPauseSeconds = 1.8f).save(context)
        assertEquals(1.8f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        for ((stored, expected) in listOf(Float.NaN to 0.5f, -1f to 0.1f, 9f to 2.5f)) {
            values["handwriting_pause_seconds_v2"] = stored
            assertEquals(expected, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        }
    }

    @Test fun `legacy one second default migrates but new explicit one second persists`() {
        values["handwriting_pause_seconds"] = 1f
        assertEquals(0.5f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        KeyboardInputPreferences(handwritingPauseSeconds = 1f).save(context)
        assertEquals(1f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
    }
    @Test fun `handwriting delay saves tenths with a half second default`() {
        for (tenth in 1..25) {
            val value = tenth / 10f
            KeyboardInputPreferences(handwritingPauseSeconds = value).save(context)
            assertEquals(value, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
        }
        KeyboardInputPreferences(handwritingPauseSeconds = 0.56f).save(context)
        assertEquals(0.6f, KeyboardInputPreferences.read(context).handwritingPauseSeconds, 0f)
    }

    @Test fun `split keyboard is opt in and persists independently of input settings`() {
        assertEquals(false, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
        SettingsPreferences.setSplitKeyboardEnabled(context, true)
        assertEquals(true, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
        KeyboardInputPreferences().save(context)
        assertEquals(true, SettingsPreferences.isSplitKeyboardEnabled(context))
        SettingsPreferences.setSplitKeyboardEnabled(context, false)
        assertEquals(false, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
    }

    /** 屏幕太窄时分体不分开，但存着的「分体键盘」不改；转到够宽的方向再分开。 */
    @Test fun `split keyboard shows only on wide screens and keeps the stored choice`() {
        SettingsPreferences.setSplitKeyboardEnabled(context, true)
        assertEquals(false, SettingsPreferences.isSplitKeyboardShown(context, 393))
        assertEquals(true, SettingsPreferences.isSplitKeyboardEnabled(context))
        assertEquals(true, KeyboardInputPreferences.read(context).splitKeyboardEnabled)
        assertEquals(true, SettingsPreferences.isSplitKeyboardShown(context, 853))
        SettingsPreferences.setSplitKeyboardEnabled(context, false)
        for (width in listOf(393, 853)) assertEquals(false, SettingsPreferences.isSplitKeyboardShown(context, width))
    }

    @Test fun `legacy combined preference preserves both effects until edited independently`() {
        for (old in listOf(false, true)) {
            values.clear()
            values[KeyEffectPreferences.GLOW] = old
            assertEquals(old, KeyboardInputPreferences.read(context).keyAnimationEnabled)
            KeyEffectPreferences.setGlowEnabled(prefs, !old)
            assertEquals(old, KeyboardInputPreferences.read(context).keyAnimationEnabled)
            assertEquals(!old, KeyboardInputPreferences.read(context).keyGlowEnabled)
            KeyEffectPreferences.setGlowEnabled(prefs, old)
            assertEquals(old, KeyboardInputPreferences.read(context).keyAnimationEnabled)
        }
    }

    @Test fun `all four combinations persist without one switch changing the other`() {
        for (glow in listOf(false, true)) for (animation in listOf(false, true)) {
            values.clear()
            KeyEffectPreferences.setAnimationEnabled(prefs, animation)
            KeyEffectPreferences.setGlowEnabled(prefs, glow)
            val effects = KeyboardInputPreferences.read(context)
            assertEquals(glow, effects.keyGlowEnabled)
            assertEquals(animation, effects.keyAnimationEnabled)
            KeyboardInputPreferences().save(context)
            assertEquals(glow, KeyboardInputPreferences.read(context).keyGlowEnabled)
            assertEquals(animation, KeyboardInputPreferences.read(context).keyAnimationEnabled)
            KeyEffectPreferences.setAnimationEnabled(prefs, !animation)
            assertEquals(glow, KeyboardInputPreferences.read(context).keyGlowEnabled)
        }
        values.clear()
        KeyEffectPreferences.setGlowEnabled(prefs, true)
        assertEquals(false, KeyboardInputPreferences.read(context).keyAnimationEnabled)
    }

    @Test fun `language key and space have separate hold delays with their own defaults`() {
        val defaults = KeyboardInputPreferences()
        assertEquals(0.15f, defaults.languageMenuHoldSeconds, 0f)
        assertEquals(200L, defaults.spaceHoldDelayMs)
        KeyboardInputPreferences(languageMenuHoldSeconds = 0.35f, cursorHoldSeconds = 0.6f).save(context)
        val restored = KeyboardInputPreferences.read(context)
        assertEquals(0.35f, restored.languageMenuHoldSeconds, 0f)
        assertEquals(600L, restored.spaceHoldDelayMs)
    }

    @Test fun `stored old language hold default migrates to new default but custom values stay`() {
        values["language_key_hold_seconds"] = 0.1f
        assertEquals(0.15f, KeyboardInputPreferences.read(context).languageMenuHoldSeconds, 0f)
        values["language_key_hold_seconds"] = 0.35f
        assertEquals(0.35f, KeyboardInputPreferences.read(context).languageMenuHoldSeconds, 0f)
        KeyboardInputPreferences(languageMenuHoldSeconds = 0.1f).save(context)
        assertEquals(0.1f, KeyboardInputPreferences.read(context).languageMenuHoldSeconds, 0f)
    }

    @Test fun `corner long press delay keeps the old 0_3 s default, persists, is bounded and caps glide takeover`() {
        // 用户 11-08：关闭角标滑动后长按时间要能调；默认仍是原来写死的 0.3 秒。
        assertEquals(0.3f, KeyboardInputPreferences().symbolLongPressSeconds, 0f)
        assertEquals(300L, KeyboardInputPreferences.read(context).symbolLongPressMs)
        KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS, symbolLongPressSeconds = 0.55f).save(context)
        val restored = KeyboardInputPreferences.read(context)
        assertEquals(0.55f, restored.symbolLongPressSeconds, 0.001f)
        assertEquals(550L, restored.symbolLongPressMs)
        assertTrue("symbol_long_press_seconds" in KeyboardInputPreferences.watchedKeys)
        assertEquals(0.1f, normalizeSymbolLongPressSeconds(0f), 0f)
        assertEquals(1f, normalizeSymbolLongPressSeconds(5f), 0f)
        assertEquals(0.3f, normalizeSymbolLongPressSeconds(Float.NaN), 0f)
        assertEquals(0.25f, normalizeSymbolLongPressSeconds(0.26f), 0.001f)
        // 滑行接管的按住上限：滑动输入不变；长按输入时不晚于角标输入的那一刻。
        assertEquals(300L, KeyboardInputPreferences(symbolLongPressSeconds = 0.15f).glideHoldMs)
        assertEquals(150L, KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS, symbolLongPressSeconds = 0.15f).glideHoldMs)
        assertEquals(300L, KeyboardInputPreferences(symbolInputMode = SymbolInputMode.LONG_PRESS, symbolLongPressSeconds = 0.8f).glideHoldMs)
        // 开着 26 键滑行时角标被锁成长按输入，同样跟着时长走。
        assertEquals(150L, KeyboardInputPreferences(glideTyping = true, symbolLongPressSeconds = 0.15f).glideHoldMs)
    }
}
