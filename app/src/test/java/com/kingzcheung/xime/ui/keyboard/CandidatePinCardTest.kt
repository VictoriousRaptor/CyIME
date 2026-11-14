package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 2026-11-04：除了忘掉，长按候选还能固定（这个编码下排第一，固定多个按先后排），已固定的能取消。
 * 忘掉和取消照旧；手写候选（pinned = null）卡片和以前一样，只有忘掉。
 * 用户 2026-11-13（卡词频）：排第 2 位以后的词还能「固定在第 N 位」，原来的固定写成「固定第一」。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CandidatePinCardTest {
    @get:Rule val rule = createComposeRule()

    private var pins = 0
    private var forgets = 0
    private var cancels = 0
    private var heres = 0

    private fun show(pinned: Boolean?, pinnedPlace: Int = 0, placeHere: Int = 0) {
        rule.setContent {
            MaterialTheme {
                ForgetLearningConfirm("OneDrive", null, Modifier.requiredSize(380.dp, 300.dp),
                    onCancel = { cancels++ }, onConfirm = { forgets++ }, pinned = pinned, onPin = { pins++ },
                    pinnedPlace = pinnedPlace, placeHere = placeHere, onPinHere = { heres++ })
            }
        }
    }

    @Test
    fun notPinnedOffersPin() {
        show(pinned = false)
        rule.onNodeWithText("「OneDrive」").assertIsDisplayed()
        rule.onNodeWithText("固定").assertIsDisplayed()
        rule.onNodeWithTag("forget-learning-confirm-extra").performClick()
        assertEquals(1, pins)
        assertEquals(0, forgets)
    }

    @Test
    fun pinnedOffersUnpin() {
        show(pinned = true)
        rule.onNodeWithText("取消固定").performClick()
        assertEquals(1, pins)
    }

    @Test
    fun forgetAndCancelStay() {
        show(pinned = false)
        rule.onNodeWithText("忘掉").performClick()
        rule.onNodeWithText("取消").performClick()
        assertEquals(1, forgets)
        assertEquals(1, cancels)
        assertEquals(0, pins)
    }

    @Test
    fun withoutPinTheCardIsUnchanged() {
        show(pinned = null)
        rule.onNodeWithText("忘掉学习").assertIsDisplayed()
        rule.onNodeWithTag("forget-learning-confirm-extra").assertDoesNotExist()
    }

    @Test
    fun laterWordOffersPinHere() {
        show(pinned = false, placeHere = 3)
        rule.onNodeWithText("固定第一").assertIsDisplayed()
        rule.onNodeWithText("固定在第 3 位").assertIsDisplayed()
        rule.onNodeWithText("忘掉").assertIsDisplayed()
        rule.onNodeWithTag("forget-learning-confirm-extra2").performClick()
        assertEquals(1, heres)
        assertEquals(0, pins)
    }

    @Test
    fun firstWordHasOnlyPin() {
        show(pinned = false, placeHere = 1)
        rule.onNodeWithText("固定").assertIsDisplayed()
        rule.onNodeWithTag("forget-learning-confirm-extra2").assertDoesNotExist()
    }

    @Test
    fun pinnedWordSaysWhere() {
        show(pinned = true, pinnedPlace = 2, placeHere = 2)
        rule.onNodeWithText("已固定在第 2 位", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("forget-learning-confirm-extra2").assertDoesNotExist()
        rule.onNodeWithText("取消固定").performClick()
        assertEquals(1, pins)
    }
}
