package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘上方的拼音气泡：底色按键色叠在键盘底色上；不透明度按设置，默认 80%（用户 11-16），拼音照样看得清。 */
class PreeditBubbleColorTest {
    private val darkSurface = Color(0xFF26232F)
    private val lightSurface = Color(0xFFF2F2F2)

    @Test fun bubbleIsOpaqueKeyColorOverTheKeyboardSurface() {
        val translucentKey = Color(0xFF4A4658).copy(alpha = 0.6f)
        val bubble = preeditBubbleColor(darkSurface, translucentKey, blurBehind = false)
        assertEquals(1f, bubble.alpha, 0f)
        assertEquals(translucentKey.compositeOver(darkSurface).copy(alpha = 1f), bubble)
    }

    @Test fun missingKeyColorFallsBackToTheOpaqueSurface() {
        assertEquals(darkSurface, preeditBubbleColor(darkSurface.copy(alpha = 0.3f), Color.Unspecified, blurBehind = false))
        assertEquals(lightSurface, preeditBubbleColor(lightSurface, Color.Transparent, blurBehind = false))
    }

    @Test fun glassWithLiveBlurKeepsOnlyASliverOfSeeThrough() {
        val bubble = preeditBubbleColor(darkSurface, Color(0xFF4A4658), blurBehind = true)
        // Color 按 8 位存透明度，0.9 存成 230/255。
        assertEquals(PREEDIT_BUBBLE_GLASS_ALPHA, bubble.alpha, 1f / 255f)
        assertTrue(bubble.alpha >= 0.9f)
    }

    @Test fun userOpacityMakesTheBubbleSeeThroughAndGlassStillCapsIt() {
        val key = Color(0xFF4A4658)
        assertEquals(0.5f, preeditBubbleColor(darkSurface, key, blurBehind = false, opacity = 0.5f).alpha, 1f / 255f)
        assertEquals(0.5f, preeditBubbleColor(darkSurface, key, blurBehind = true, opacity = 0.5f).alpha, 1f / 255f)
        assertEquals(PREEDIT_BUBBLE_GLASS_ALPHA, preeditBubbleColor(darkSurface, key, blurBehind = true, opacity = 1f).alpha, 1f / 255f)
        assertEquals(1f, preeditBubbleColor(darkSurface, key, blurBehind = false).alpha, 0f)
    }

    @Test fun opacitySettingSnapsToFivePercentWithinTwentyToHundred() {
        assertEquals(0.2f, com.kingzcheung.xime.settings.SettingsPreferences.normalizePreeditBubbleOpacity(0.05f), 0.0001f)
        assertEquals(0.65f, com.kingzcheung.xime.settings.SettingsPreferences.normalizePreeditBubbleOpacity(0.66f), 0.0001f)
        assertEquals(com.kingzcheung.xime.settings.SettingsPreferences.DEFAULT_PREEDIT_BUBBLE_OPACITY,
            com.kingzcheung.xime.settings.SettingsPreferences.normalizePreeditBubbleOpacity(Float.NaN), 0f)
    }

    /** 浮在应用上的拼音默认半透明（用户 11-16），气泡和编辑条同一个值，而且落在滑条的 5% 一档上。 */
    @Test fun floatingPreeditDefaultsToEightyPercent() {
        val default = com.kingzcheung.xime.settings.SettingsPreferences.DEFAULT_PREEDIT_BUBBLE_OPACITY
        assertEquals(0.8f, default, 0f)
        assertEquals(default, com.kingzcheung.xime.settings.SettingsPreferences.normalizePreeditBubbleOpacity(default), 0f)
        assertEquals(default, KeyboardInputPreferences().preeditBubbleOpacity, 0f)
        assertEquals(default, preeditBubbleColor(darkSurface, Color(0xFF4A4658), blurBehind = false, opacity = default).alpha, 1f / 255f)
    }

    /** 半透明底上的拼音：后面是白是黑都要 4.5:1，不够时垫和候选栏玻璃同一种光晕；实底不垫。 */
    @Test fun translucentPreeditTextGetsHaloOnlyWhenAWhiteOrBlackHostWouldWashItOut() {
        val darkKey = Color(0xFF4A4A4A)
        val lightText = Color(0xFFE8EAED)
        val darkBubble = preeditBubbleColor(Color(0xFF1C1B1F), darkKey, blurBehind = false, opacity = 0.8f)
        val halo = floatingPreeditHalo(lightText, darkBubble)
        assertTrue(halo != null && halo.color == Color.Black && halo.alpha > 0f)
        listOf(Color.White, Color.Black).forEach { host ->
            val behind = halo!!.color.copy(alpha = halo.alpha).compositeOver(darkBubble.compositeOver(host))
            assertTrue(textContrast(lightText, behind) >= 4.5f)
        }
        val lightBubble = preeditBubbleColor(lightSurface, Color.White, blurBehind = false, opacity = 0.8f)
        assertEquals(null, floatingPreeditHalo(Color(0xFF202124), lightBubble))
        assertEquals(null, floatingPreeditHalo(lightText, darkBubble.copy(alpha = 1f)))
        assertEquals(null, floatingPreeditHalo(lightText, null))
    }

    @Test fun bubbleTextReachesKeycapContrastOnBothDarkAndLightThemes() {
        listOf(
            Triple(darkSurface, Color(0xFF4A4658), Color(0xFFB9A8E8)),
            Triple(darkSurface, Color(0xFF4A4658), Color(0xFF5A5470)),
            Triple(lightSurface, Color.White, Color(0xFF9A9A9A)),
        ).forEach { (surface, key, text) ->
            val bubble = preeditBubbleColor(surface, key, blurBehind = false)
            assertTrue(textContrast(readableThemeText(text, bubble), bubble) >= 4.5f)
        }
    }
}
