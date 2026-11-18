package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.SwipeState
import com.kingzcheung.xime.ui.keyboard.SwipeableKeyButton
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode
import com.kingzcheung.xime.settings.ButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verify complete pointer sequences so a gesture cannot commit through multiple callbacks. */
@RunWith(AndroidJUnit4::class)
class SwipeableKeyButtonGestureTest {
    @get:Rule
    val rule = createComposeRule()

    private class Events {
        val commits = mutableListOf<String>()
        var state = SwipeState()
        var presses = 0
        var releases = 0
        var density = 1f
    }

    private fun setKey(
        longPressItems: List<String>? = listOf("q", "Q", "ä"),
        parentConsumesMoves: Boolean = false,
        parentConsumesDown: Boolean = false,
        leftEnabled: Boolean = false,
        symbolMode: SymbolInputMode = SymbolInputMode.SWIPE_UP,
        hideUpperHint: Boolean = false,
        letterDirections: Boolean = false,
        reverseSymbols: Boolean = false,
        keyOnlyLowerHint: Boolean = false,
        layout: ButtonLayout = ButtonLayout.STANDARD,
    ): Events {
        val events = Events()
        rule.setContent {
            events.density = LocalDensity.current.density
            val parentInput = if (parentConsumesMoves || parentConsumesDown) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(
                                if (parentConsumesDown) PointerEventPass.Initial else PointerEventPass.Main
                            )
                            event.changes.forEach { change ->
                                if ((parentConsumesDown && change.pressed && !change.previousPressed) ||
                                    (parentConsumesMoves && change.pressed &&
                                        change.position != change.previousPosition)
                                ) {
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            } else Modifier
            CompositionLocalProvider(LocalKeyboardInputPreferences provides
                KeyboardInputPreferences(symbolInputMode = symbolMode, reverseSymbolSwipe = reverseSymbols)) {
            Box(Modifier.size(200.dp).then(parentInput)) {
                SwipeableKeyButton(
                    text = "q",
                    onClick = { events.commits += "tap:q" },
                    backgroundColor = Color.White,
                    textColor = Color.Black,
                    modifier = Modifier.testTag("key"),
                    layoutMode = layout,
                    followSymbolSwipeDirection = letterDirections,
                    swipeText = if (hideUpperHint) null else "1",
                    symbolInputText = "1",
                    swipeDownText = if (keyOnlyLowerHint) null else "!",
                    swipeDownKeyLabel = "!",
                    onSwipe = { events.commits += "up:$it" },
                    onSwipeDown = { events.commits += "down:!" },
                    onSwipeLeft = if (leftEnabled) ({ events.commits += "previous" }) else null,
                    swipeLeftText = if (leftEnabled) "上一项" else null,
                    onPress = { events.presses++ },
                    onRelease = { events.releases++ },
                    onSwipeStateChange = { state, _ -> events.state = state },
                    longPressItems = longPressItems,
                    onLongPressSelect = { events.commits += "long:$it" },
                    shadowEnabled = false,
                )
            }
            }
        }
        return events
    }

    @Test fun heldKeyRemainsTintedAfterThePressPulseAndClearsOnRelease() {
        val events = setKey(letterDirections = true)
        val key = rule.onNodeWithTag("key")
        fun color(): Color {
            val pixels = key.captureToImage().toPixelMap()
            return pixels[pixels.width / 4, pixels.height * 3 / 4]
        }
        rule.mainClock.autoAdvance = false
        val resting = color()
        key.performTouchInput { down(center); moveTo(center + Offset(0f, 75f * events.density)) }
        rule.mainClock.advanceTimeBy(1000)
        val held = color()
        assertTrue("The held key must remain distinct after its short animation", resting != held)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals("Holding still must produce a stable highlight", held, color())
        assertTrue(events.commits.isEmpty())
        key.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(resting, color())
        assertEquals(listOf("up:1"), events.commits)
    }

    @Test fun sidewaysDriftDoesNotCancelChosenSymbolButBacktrackingStillDoes() {
        val events = setKey(letterDirections = true)
        val key = rule.onNodeWithTag("key")
        key.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 30f * events.density))
            moveTo(center + Offset(300f, 80f) * events.density)
        }
        rule.runOnIdle { assertEquals("1", events.state.keyFlick?.text); assertTrue(events.commits.isEmpty()) }
        key.performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("up:1"), events.commits) }
        key.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, -80f * events.density))
            moveTo(center + Offset(-300f, -80f) * events.density)
            moveTo(center + Offset(-300f, 0f) * events.density)
            up()
        }
        rule.runOnIdle { assertEquals("Returning along the vertical axis cancels without a normal tap", listOf("up:1"), events.commits) }
    }

    @Test fun defaultLetterFlickMovesUpperHintIntoSameKeyAndCommitsOnlyOnRelease() {
        val events = setKey(letterDirections = true)
        val key = rule.onNodeWithTag("key")
        val bounds = key.fetchSemanticsNode().boundsInRoot
        key.performTouchInput { down(center); moveTo(center + Offset(0f, 30f * events.density)) }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals("1", events.state.keyFlick?.text)
            assertTrue(events.state.keyFlick!!.fromTop)
            assertTrue(events.state.keyFlick!!.progress in 0f..0.99f)
        }
        val previewBounds = rule.onNodeWithTag("key-flick-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(previewBounds.left >= bounds.left && previewBounds.right <= bounds.right)
        assertTrue(previewBounds.top >= bounds.top && previewBounds.bottom <= bounds.bottom)
        assertEquals(bounds, key.fetchSemanticsNode().boundsInRoot)
        key.performTouchInput { moveTo(center + Offset(0f, 75f * events.density)); up() }
        rule.runOnIdle { assertEquals(listOf("up:1"), events.commits); assertEquals(SwipeState(), events.state) }
    }

    @Test fun compactKeyOnlyLowerHintPullsUpAndBacktrackingCancelsWithoutTap() {
        val events = setKey(letterDirections = true, keyOnlyLowerHint = true, layout = ButtonLayout.COMPACT)
        val key = rule.onNodeWithTag("key")
        key.performTouchInput { down(center); moveTo(center - Offset(0f, 75f * events.density)) }
        rule.runOnIdle {
            assertEquals("!", events.state.keyFlick?.text)
            assertFalse(events.state.keyFlick!!.fromTop)
            assertTrue(events.commits.isEmpty())
        }
        key.performTouchInput { moveTo(center); up() }
        rule.runOnIdle { assertTrue(events.commits.isEmpty()); assertEquals(SwipeState(), events.state) }
        key.performTouchInput { down(center); moveTo(center - Offset(0f, 75f * events.density)); up() }
        rule.runOnIdle { assertEquals(listOf("down:!"), events.commits) }
    }

    @Test fun reversedLetterFlickKeepsLegacyDirectionWithInKeyPreview() {
        val events = setKey(letterDirections = true, reverseSymbols = true)
        rule.onNodeWithTag("key").performTouchInput { down(center); moveTo(center - Offset(0f, 75f * events.density)) }
        rule.runOnIdle { assertEquals("1", events.state.keyFlick?.text); assertTrue(events.state.keyFlick!!.fromTop) }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("up:1"), events.commits) }
    }

    @Test fun defaultLetterLongPressBlocksDownwardTopSymbolAndKeepsUpwardBottomSymbol() {
        val events = setKey(letterDirections = true, symbolMode = SymbolInputMode.LONG_PRESS)
        val key = rule.onNodeWithTag("key")
        key.performTouchInput { down(center); moveTo(center + Offset(0f, 75f * events.density)); up() }
        key.performTouchInput { down(center); moveTo(center + Offset(0f, 75f * events.density)); moveTo(center); up() }
        rule.runOnIdle { assertTrue(events.commits.isEmpty()) }
        key.performTouchInput { down(center); moveTo(center - Offset(0f, 75f * events.density)); up() }
        rule.runOnIdle { assertEquals(listOf("down:!"), events.commits) }
    }

    @Test fun symbolHoldCommitsAtThreeHundredMillisecondsBeforeReleaseAndNeverRepeats() {
        val events = setKey(symbolMode = SymbolInputMode.LONG_PRESS, hideUpperHint = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(240L)
        rule.runOnIdle { assertFalse(events.state.isLongPress); assertTrue(events.commits.isEmpty()) }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("tap:q"), events.commits); events.commits.clear() }
        rule.onNodeWithTag("key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(299L, ignoreFrameDuration = true)
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
        }
        rule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        rule.runOnIdle {
            assertEquals(listOf("up:1"), events.commits)
            // 用户 11-08：到点的同时气泡换成输入的角标（一个字，不是选择菜单），按住一直显示。
            assertTrue(events.state.isLongPress)
            assertEquals(listOf("1"), events.state.longPressItems)
        }
        rule.mainClock.advanceTimeBy(1000L)
        rule.runOnIdle { assertEquals(listOf("up:1"), events.commits); assertTrue(events.state.isLongPress) }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf("up:1"), events.commits)
            assertFalse("松手气泡消失", events.state.isLongPress)
        }
    }

    @Test fun symbolHoldCancellationBeforeTimeoutInputsNothingAndAfterTimeoutDoesNotRepeat() {
        val events = setKey(symbolMode = SymbolInputMode.LONG_PRESS)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag("key").performTouchInput { cancel() }
        rule.mainClock.advanceTimeBy(500L)
        rule.runOnIdle { assertTrue(events.commits.isEmpty()) }
        rule.onNodeWithTag("key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(320L)
        rule.runOnIdle { assertEquals(listOf("up:1"), events.commits) }
        rule.onNodeWithTag("key").performTouchInput { cancel() }
        rule.runOnIdle { assertEquals(listOf("up:1"), events.commits) }
    }

    @Test fun slidingAfterImmediateSymbolInputDoesNotCommitAnotherActionOnRelease() {
        val events = setKey(symbolMode = SymbolInputMode.LONG_PRESS, leftEnabled = true)
        rule.mainClock.autoAdvance = false
        for (direction in listOf(Offset(0f, -75f), Offset(0f, 75f), Offset(-75f, 0f), Offset(75f, 0f))) {
            rule.onNodeWithTag("key").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(320L)
            rule.runOnIdle { assertEquals(listOf("up:1"), events.commits) }
            rule.onNodeWithTag("key").performTouchInput { moveTo(center + direction * events.density); up() }
            rule.runOnIdle { assertEquals(listOf("up:1"), events.commits); events.commits.clear() }
        }
    }

    @Test fun symbolHoldBlocksUpperSwipeWithoutLosingDownAndPreviousCandidateGestures() {
        val events = setKey(symbolMode = SymbolInputMode.LONG_PRESS, leftEnabled = true)
        rule.onNodeWithTag("key").performTouchInput {
            down(center); moveTo(center - Offset(0f, 75f * events.density)); up()
        }
        rule.onNodeWithTag("key").performTouchInput {
            down(center); moveTo(center - Offset(0f, 75f * events.density)); moveTo(center); up()
        }
        rule.runOnIdle { assertTrue(events.commits.isEmpty()) }
        rule.onNodeWithTag("key").performTouchInput {
            down(center); moveTo(center + Offset(0f, 75f * events.density)); up()
        }
        rule.onNodeWithTag("key").performTouchInput {
            down(center); moveTo(center - Offset(75f * events.density, 0f)); up()
        }
        rule.runOnIdle { assertEquals(listOf("down:!", "previous"), events.commits) }
    }

    @Test
    fun leftSwipePreviewsThenMovesOnceWithoutAlsoTappingOrTriggeringVerticalSwipe() {
        val events = setKey(leftEnabled = true)
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(75f * events.density, 15f * events.density))
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals("上一项", events.state.swipeText)
        }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf("previous"), events.commits)
            assertEquals(1, events.releases)
            assertEquals(SwipeState(), events.state)
        }
    }

    @Test
    fun returningOrCancellingLeftSwipeNeverFallsBackToNextCandidateTap() {
        val events = setKey(leftEnabled = true)
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(75f * events.density, 0f))
            moveTo(center)
            up()
        }
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(75f * events.density, 0f))
            cancel()
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals(2, events.releases)
        }
    }

    @Test
    fun tapCommitsExactlyOnceWithLongPressOptions() {
        val events = setKey()
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            up()
        }
        rule.runOnIdle {
            assertEquals(listOf("tap:q"), events.commits)
            assertEquals(1, events.presses)
            assertEquals(1, events.releases)
            assertEquals(SwipeState(), events.state)
        }
    }

    @Test
    fun smallFingerMovementDoesNotLoseTheTap() {
        val events = setKey()
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center + Offset(4f * events.density, 4f * events.density))
            up()
        }
        rule.runOnIdle { assertEquals(listOf("tap:q"), events.commits) }
    }

    @Test
    fun movementBelowSwipeThresholdStillCommitsOnlyTheTap() {
        val events = setKey()
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 30f * events.density))
            up()
        }
        rule.runOnIdle { assertEquals(listOf("tap:q"), events.commits) }
    }

    @Test
    fun upwardSwipePreviewsWithoutCommittingThenCommitsOnlySymbolOnRelease() {
        val events = setKey()
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 75f * events.density))
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertTrue(events.state.isSwiping)
            assertEquals("1", events.state.swipeText)
        }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf("up:1"), events.commits)
            assertEquals(1, events.releases)
            assertEquals(SwipeState(), events.state)
        }
    }

    @Test
    fun downwardSwipeWithoutLongPressOptionsAlsoWaitsForRelease() {
        val events = setKey(longPressItems = null)
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 75f * events.density))
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertTrue(events.state.isSwipeDown)
            assertEquals("!", events.state.swipeText)
        }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("down:!"), events.commits) }
    }

    @Test
    fun returningInsideTheThresholdCancelsSwipeWithoutFallingBackToTap() {
        val events = setKey()
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 75f * events.density))
            moveTo(center)
            up()
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals(SwipeState(), events.state)
        }
    }

    @Test
    fun cancelledSwipeCommitsNothingAndTheNextTapStillWorks() {
        val events = setKey()
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 75f * events.density))
            cancel()
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals(1, events.releases)
            assertEquals(SwipeState(), events.state)
        }
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            up()
        }
        rule.runOnIdle { assertEquals(listOf("tap:q"), events.commits) }
    }

    @Test
    fun longPressSelectionCommitsOnlyTheSelectedOptionOnce() {
        val events = setKey()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(450L)
        rule.runOnIdle {
            assertTrue(events.state.isLongPress)
            // 三项以上按画出来的格选：条以键为中心、每格一个键宽，长按到点就选手指下面的中间那格。
            assertEquals(1, events.state.selectedLongPressIndex)
            assertTrue(events.commits.isEmpty())
        }
        // 挪不到半个键宽还在同一格（以前三项挤在一个键宽里，这一下就换格了）。
        rule.onNodeWithTag("key").performTouchInput {
            moveTo(center + Offset(70f * events.density, 0f))
        }
        rule.runOnIdle {
            assertEquals(1, events.state.selectedLongPressIndex)
            assertTrue(events.commits.isEmpty())
        }
        rule.onNodeWithTag("key").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf("long:Q"), events.commits)
            assertEquals(1, events.releases)
            assertEquals(SwipeState(), events.state)
        }
    }

    @Test
    fun cancelledLongPressDoesNotSelectAnOption() {
        val events = setKey()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(450L)
        rule.runOnIdle { assertTrue(events.state.isLongPress) }
        rule.onNodeWithTag("key").performTouchInput { cancel() }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals(1, events.releases)
            assertEquals(SwipeState(), events.state)
        }
    }

    @Test
    fun ancestorConsumptionCancelsTapEvenWhenFingerReturnsToTheKey() {
        val events = setKey(parentConsumesMoves = true)
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            moveTo(center + Offset(30f * events.density, 0f))
            moveTo(center)
            up()
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals(1, events.releases)
            assertFalse(events.state.isPressed)
        }
    }

    @Test
    fun alreadyConsumedDownDoesNotStartAKeyGesture() {
        val events = setKey(parentConsumesDown = true)
        rule.onNodeWithTag("key").performTouchInput {
            down(center)
            up()
        }
        rule.runOnIdle {
            assertTrue(events.commits.isEmpty())
            assertEquals(0, events.presses)
            assertEquals(0, events.releases)
        }
    }
}
