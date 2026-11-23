package com.kingzcheung.xime.ui.theme

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.PerformanceCosts
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「液态玻璃强度」（11-15）：标准必须就是加这一项之前的样子，并且是默认；后两档一档比一档强；
 * 选档位不动别的设置，「性能与省电」里两档各有一行、跟着选的档位走。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LiquidGlassStrengthTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() {
        SettingsPreferences.setGlassLiquid(app, false)
        SettingsPreferences.setGlassLiquidStrength(app, SettingsPreferences.GLASS_LIQUID_STRENGTH_DEFAULT)
        LiquidGlass.rebind(app)
    }

    @Test fun standardIsExactlyTheLookBeforeThisSettingExisted() {
        val s = LiquidGlassStrength.STANDARD
        assertEquals(.75f, s.strokeDp, 0f)
        assertArrayEquals(floatArrayOf(.45f, .10f, .06f, .22f), s.rimAlphas(dark = true), 0f)
        assertArrayEquals(floatArrayOf(.75f, .10f, .06f, .35f), s.rimAlphas(dark = false), 0f)
        // 没有厚度、没有高光、浅色没有暗边：只画那一道细亮边。
        assertEquals(0f, s.bandDp, 0f)
        for (dark in listOf(true, false)) {
            assertEquals(0f, s.bandAlpha(dark), 0f)
            assertEquals(0f, s.shadeAlpha(dark), 0f)
            assertEquals(0f, s.sheenAlpha(dark), 0f)
        }
        // 图片背景折射：5dp 一圈、偏移 9.5dp、绿蓝各多偏 3% / 6%。
        assertEquals(5f, s.bevelDp, 0f)
        assertEquals(9.5f, s.bendDp, 0f)
        assertEquals(.03f, s.spread, 0f)
    }

    @Test fun eachLevelIsStrongerThanTheOneBefore() {
        val levels = LiquidGlassStrength.entries
        assertEquals(listOf("standard", "strong", "strongest"), levels.map { it.id })
        assertEquals(listOf("标准", "加强", "最强"), levels.map { it.label })
        levels.zipWithNext { a, b ->
            assertTrue(b.strokeDp > a.strokeDp)
            assertTrue(b.bevelDp > a.bevelDp && b.bendDp > a.bendDp && b.spread > a.spread)
            for (dark in listOf(true, false)) assertTrue(b.rimAlphas(dark)[0] > a.rimAlphas(dark)[0])
        }
        levels.forEach { level -> level.rimAlphas(true).plus(level.rimAlphas(false)).forEach { assertTrue(it in 0f..1f) } }
    }

    /**
     * 11-22（用户「加强的看起来很丑」）：后两档不再画三圈白描边、上半那层白和浅色暗边（像塑料边框），
     * 改成键帽上面的透镜；最强的透镜每一项都比加强强。标准档没有透镜。
     */
    @Test fun strongerLevelsAreALensNotAPlasticOutline() {
        assertEquals(null, LiquidGlassStrength.STANDARD.lens)
        for (level in listOf(LiquidGlassStrength.STRONG, LiquidGlassStrength.STRONGEST)) {
            assertEquals(0f, level.bandDp, 0f)
            for (dark in listOf(true, false)) {
                assertEquals(0f, level.bandAlpha(dark), 0f)
                assertEquals(0f, level.shadeAlpha(dark), 0f)
                assertEquals(0f, level.sheenAlpha(dark), 0f)
            }
            val lens = level.lens!!
            for (alpha in listOf(lens.visibility, lens.specular, lens.backLight, lens.glow, lens.shade)) assertTrue(alpha in 0f..1f)
            assertTrue(lens.saturation >= 1f)
            // 往外取的比透镜圈宽：键外面的图才弯得进边里。
            assertTrue(lens.bendDp > lens.bevelDp)
        }
        val a = LiquidGlassStrength.STRONG.lens!!
        val b = LiquidGlassStrength.STRONGEST.lens!!
        assertTrue(b.bevelDp > a.bevelDp && b.bendDp > a.bendDp && b.spread > a.spread)
        assertTrue(b.visibility > a.visibility && b.saturation > a.saturation)
        assertTrue(b.specular > a.specular && b.backLight > a.backLight && b.glow > a.glow)
    }

    @Test fun nothingStoredOrAnUnknownValueIsStandard() {
        assertEquals(LiquidGlassStrength.STANDARD, LiquidGlassStrength.of(null))
        assertEquals(LiquidGlassStrength.STANDARD, LiquidGlassStrength.of("from-a-newer-version"))
        assertEquals("standard", SettingsPreferences.glassLiquidStrength(app))
        LiquidGlass.rebind(app)
        assertEquals(LiquidGlassStrength.STANDARD, LiquidGlass.strength(app))
        // 原来的两个键名没动。
        assertEquals("glass_liquid", SettingsPreferences.KEY_GLASS_LIQUID)
        assertEquals("glass_liquid_strength", SettingsPreferences.KEY_GLASS_LIQUID_STRENGTH)
    }

    @Test fun choosingALevelOnlyStoresTheLevel() {
        val glassBefore = FrostedGlassPreferences.readProfiles(app)
        SettingsPreferences.setGlassLiquidStrength(app, LiquidGlassStrength.STRONGEST.id)
        LiquidGlass.rebind(app)
        assertEquals(LiquidGlassStrength.STRONGEST, LiquidGlass.strength(app))
        // 不替用户打开液态玻璃，模糊和不透明度也不动。
        assertFalse(SettingsPreferences.glassLiquid(app))
        assertEquals(glassBefore, FrostedGlassPreferences.readProfiles(app))
    }

    @Test fun theTwoStrongerLevelsAreCostItemsThatFollowTheChoice() {
        val strong = PerformanceCosts.Item.LIQUID_GLASS_STRONG
        val strongest = PerformanceCosts.Item.LIQUID_GLASS_STRONGEST
        assertFalse(PerformanceCosts.isOn(app, strong) || PerformanceCosts.isOn(app, strongest))
        // 测量脚本单开一档：液态玻璃和玻璃一起开。
        PerformanceCosts.set(app, strong, true)
        assertTrue(SettingsPreferences.glassLiquid(app))
        assertTrue(FrostedGlassPreferences.readProfiles(app).dark.enabled)
        assertTrue(PerformanceCosts.isOn(app, strong))
        assertFalse(PerformanceCosts.isOn(app, strongest))
        // 两档只会开一档。
        PerformanceCosts.set(app, strongest, true)
        assertFalse(PerformanceCosts.isOn(app, strong))
        assertTrue(PerformanceCosts.isOn(app, strongest))
        // 关的不是现在这一档：不动。
        PerformanceCosts.set(app, strong, false)
        assertTrue(PerformanceCosts.isOn(app, strongest))
        // 关现在这一档：退回标准，液态玻璃还开着。
        PerformanceCosts.set(app, strongest, false)
        assertEquals("standard", SettingsPreferences.glassLiquidStrength(app))
        assertTrue(PerformanceCosts.isOn(app, PerformanceCosts.Item.LIQUID_GLASS))
        // 液态玻璃关着时哪一档都不算开着，选的档位留着。
        SettingsPreferences.setGlassLiquidStrength(app, "strong")
        SettingsPreferences.setGlassLiquid(app, false)
        assertFalse(PerformanceCosts.isOn(app, strong))
        assertEquals("strong", SettingsPreferences.glassLiquidStrength(app))
    }
}
