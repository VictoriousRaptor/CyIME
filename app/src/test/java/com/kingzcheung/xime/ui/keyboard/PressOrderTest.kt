package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Keys side by side, each with the app's own key gesture code (detectExclusiveKeyGestures for letters,
 * detectRepeatingKeyGestures for the delete key), touched with several fingers. What comes out is written down in
 * the order the keys' actions fire.
 *
 * The tests named in [ORDER] describe the wanted press order and fail on the 105 code; the rest pass on both and
 * keep what must not change (long press, swipes, cancelled drags, the delete key's repeat).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1280dp-xhdpi")
class PressOrderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val typed = StringBuilder()
    private val key = 100f   // px
    private var slop = 0f
    private var density = 1f

    private sealed interface Spec { val name: String }
    /** A letter key. [configure] adds long press, swipes and so on to the plain tap. */
    private class Letter(override val name: String, val glide: Boolean = false,
        val configure: (KeyGestureActions) -> KeyGestureActions = { it }) : Spec
    /** The delete key: a tap writes '<', every repeat while held writes '-'. */
    private class Delete(override val name: String = "<") : Spec

    private fun show(vararg keys: Spec) {
        rule.setContent {
            slop = LocalViewConfiguration.current.touchSlop
            density = LocalDensity.current.density
            val side = with(LocalDensity.current) { key.toDp() }
            Row {
                for (spec in keys) when (spec) {
                    is Letter -> {
                        val a = spec.configure(KeyGestureActions(text = spec.name, onTap = { typed.append(spec.name) },
                            onPress = {}, onRelease = {}, onPreview = {}))
                        Box(Modifier.size(side).pointerInput(Unit) {
                            detectExclusiveKeyGestures(reserveDragForGlide = spec.glide, letterGlide = spec.glide) { a }
                        })
                    }
                    is Delete -> {
                        val a = RepeatingKeyActions(onTap = { typed.append(spec.name) }, onPress = {}, onRelease = {},
                            onPreview = {}, onRepeat = { typed.append('-') })
                        Box(Modifier.size(side).pointerInput(Unit) { detectRepeatingKeyGestures { a } })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun show(vararg letters: String) = show(*letters.map { Letter(it) }.toTypedArray())

    private fun at(index: Int, dx: Float = 0f, dy: Float = 0f) = Offset(key * index + key / 2f + dx, key / 2f + dy)

    private fun touches(gap: Long = 40L, block: TouchInjectionScope.(step: () -> Unit) -> Unit) {
        rule.onRoot().performTouchInput { block { advanceEventTime(gap) } }
        rule.waitForIdle()
    }

    // ---- what must stay as it is ----

    /** One finger after the other, nothing overlapping. */
    @Test fun separateTaps() {
        show("n", "i")
        touches { step -> down(0, at(0)); step(); up(0); step(); down(0, at(1)); step(); up(0) }
        assertEquals("ni", typed.toString())
    }

    /** The second finger is down before the first lifts; the first lifts first (ordinary rollover). */
    @Test fun rollover() {
        show("n", "i")
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(0); step(); up(1) }
        assertEquals("ni", typed.toString())
    }

    /** Same key under two thumbs (the 10-30 fix): still two letters, and a neighbour in between keeps its place. */
    @Test fun sameKeyHandover() {
        show("u", "r")
        touches { step ->
            down(0, at(0)); step()
            down(1, at(0)); step()
            up(0); step()
            down(0, at(1)); step()
            up(1); step()
            down(1, at(0)); step()
            up(0); step()
            up(1)
        }
        assertEquals("uuru", typed.toString())
    }

    /** A key held into its long press menu is not a tap: the key tapped meanwhile comes first, the menu choice on lift. */
    @Test fun longPressIsNotTypedEarly() {
        show(Letter("n") { it.copy(longPressItems = listOf("N"), onLongPressSelect = { s -> typed.append("[$s]") }) }, Letter("i"))
        touches { step ->
            down(0, at(0)); advanceEventTime(600)
            down(1, at(1)); step(); up(1); step()
            up(0)
        }
        assertEquals("i[N]", typed.toString())
    }

    /** A swipe that has started (direction taken, threshold not reached yet) is not a tap either. */
    @Test fun swipeInProgressIsNotTypedEarly() {
        show(Letter("n") { it.copy(upText = "^", onUp = { s -> typed.append(s) }) }, Letter("i"))
        touches { step ->
            down(0, at(0)); step()
            moveTo(0, at(0, dy = -(slop + 4f))); step()
            down(1, at(1)); step(); up(1); step()
            moveTo(0, at(0, dy = -(50f * density + 10f))); step()
            up(0)
        }
        assertEquals("i^", typed.toString())
    }

    /** A finger dragged sideways past the cancel distance types nothing, before or after another key. */
    @Test fun cancelledDragIsNotTyped() {
        show("n", "i", "x", "y")
        touches { step ->
            down(0, at(0)); step()
            moveTo(0, at(0, dx = 60f * density + 5f)); step()
            down(1, at(3)); step(); up(1); step()
            up(0)
        }
        assertEquals("y", typed.toString())
    }

    /** A drag on a glide key that went far and did not become a glide types nothing. */
    @Test fun farDragOnGlideKeyIsNotTyped() {
        show(Letter("n", glide = true), Letter("i", glide = true), Letter("x", glide = true), Letter("y", glide = true))
        touches { step ->
            down(0, at(0)); step()
            moveTo(0, at(0, dx = 2f * key)); step()
            down(1, at(3)); step(); up(1); step()
            up(0)
        }
        assertEquals("y", typed.toString())
    }

    /** The delete key held long repeats; a letter tapped after the repeat began does not turn it into a tap. */
    @Test fun deleteRepeatKeepsRepeating() {
        show(Delete(), Letter("n"))
        touches { step ->
            down(0, at(0)); advanceEventTime(700)
            down(1, at(1)); step(); up(1); step()
            up(0)
        }
        val text = typed.toString()
        assertEquals("repeats, then the letter, no tap of the delete key: $text", true,
            text.matches(Regex("-+n-*")) && !text.contains('<'))
    }

    // ---- press order (fails on 105) ----

    /** The second finger presses and lifts while the first is still down. */
    @Test fun nestedPress() {
        show("n", "i")
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(1); step(); up(0) }
        assertEquals("ni", typed.toString())
    }

    /** The first finger stays down while two more keys are tapped. */
    @Test fun firstKeyHeldThroughTwoTaps() {
        show("h", "a", "o")
        touches { step ->
            down(0, at(0)); step()
            down(1, at(1)); step(); up(1); step()
            down(1, at(2)); step(); up(1); step()
            up(0)
        }
        assertEquals("hao", typed.toString())
    }

    /** Three fingers, lifted in the reverse order. */
    @Test fun threeNestedPresses() {
        show("s", "h", "i")
        touches { step ->
            down(0, at(0)); step(); down(1, at(1)); step(); down(2, at(2)); step()
            up(2); step(); up(1); step(); up(0)
        }
        assertEquals("shi", typed.toString())
    }

    /** A key typed early is typed once: holding it on does not start its long press, lifting adds nothing. */
    @Test fun keyTypedEarlyDoesNotLongPress() {
        show(Letter("n") { it.copy(onLongPress = { typed.append("!") }) }, Letter("i"))
        touches { step ->
            down(0, at(0)); step()
            down(1, at(1)); step(); up(1)
            advanceEventTime(900)
            up(0)
        }
        assertEquals("ni", typed.toString())
    }

    /** After a key was typed early, a second finger on that same key is a new press of it. */
    @Test fun sameKeyAgainAfterTypedEarly() {
        show("u", "r")
        touches { step ->
            down(0, at(0)); step()
            down(1, at(1)); step(); up(1); step()
            down(1, at(0)); step()
            up(0); step()
            up(1)
        }
        assertEquals("uru", typed.toString())
    }

    /** On a glide key a finger that drifted a little (no glide) still counts, in its place. */
    @Test fun driftOnGlideKeyKeepsItsPlace() {
        show(Letter("n", glide = true), Letter("i", glide = true))
        touches { step ->
            down(0, at(0)); step()
            moveTo(0, at(0, dx = slop + 6f, dy = 3f)); step()
            down(1, at(1)); step(); up(1); step()
            up(0)
        }
        assertEquals("ni", typed.toString())
    }

    /** A letter still held when the delete key is tapped: the letter first, then the delete. */
    @Test fun deleteAfterHeldLetter() {
        show(Letter("n"), Delete())
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(1); step(); up(0) }
        assertEquals("n<", typed.toString())
    }

    /** The delete key still held when the next letter is tapped: the delete first, then the letter. */
    @Test fun letterAfterHeldDelete() {
        show(Delete(), Letter("n"))
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(1); step(); up(0) }
        assertEquals("<n", typed.toString())
    }

    companion object {
        /** The tests that describe the new behaviour. */
        val ORDER = setOf("nestedPress", "firstKeyHeldThroughTwoTaps", "threeNestedPresses", "keyTypedEarlyDoesNotLongPress",
            "sameKeyAgainAfterTypedEarly", "driftOnGlideKeyKeepsItsPlace", "deleteAfterHeldLetter", "letterAfterHeldDelete")
    }
}
