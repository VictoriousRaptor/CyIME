package com.kingzcheung.xime.settings

import com.kingzcheung.xime.runtime.adaptation.DeviceCapabilities
import com.kingzcheung.xime.runtime.adaptation.PerformanceTier
import com.kingzcheung.xime.runtime.adaptation.PerformanceTiers
import org.junit.Assert.*
import org.junit.Test

class PerformanceProfileTest {
    private val gib = DeviceCapabilities.GIB
    private val device = DeviceCapabilities(0, 35, listOf("arm64-v8a"), false, 8 * gib, 8, 3_360_000)

    @Test fun `tiers come from one shared threshold set`() {
        // 测试用的分档线，不是实测值。
        val lines = PerformanceTiers.CpuThresholds(20_000_000, 25_000_000, 60_000_000, 70_000_000)
        val fast = device.copy(cpu = com.kingzcheung.xime.runtime.adaptation.CpuMeasurement(15_000_000, 18_000_000))
        assertEquals(PerformanceTier.HIGH, PerformanceTiers.classify(fast, lines))
        // 没实测：8 GB、3.36 GHz 也只到中配（11-04）。
        assertEquals(PerformanceTier.MEDIUM, PerformanceTiers.classify(device, lines))
        assertEquals(PerformanceTier.MEDIUM, PerformanceTiers.classify(fast.copy(totalMemoryBytes = 6 * gib), lines))
        assertEquals(PerformanceTier.LOW, PerformanceTiers.classify(fast.copy(totalMemoryBytes = 3 * gib), lines))
        assertEquals(PerformanceTier.LOW, PerformanceTiers.classify(fast.copy(lowRam = null), lines))
    }

    @Test fun `a preset is shown only when every value matches, otherwise custom`() {
        PerformanceProfile.presets.forEach { (tier, values) -> assertEquals(tier, PerformanceProfile.matching(values)) }
        val high = PerformanceProfile.presets.getValue(PerformanceTier.HIGH)
        assertNull(PerformanceProfile.matching(high.copy(keyGlow = false)))
        assertNull(PerformanceProfile.matching(high.copy(speechRefinerIdleMinutes = 5)))
    }

    @Test fun `presets release the second pass before the whole engine`() {
        PerformanceProfile.presets.values.forEach {
            val engine = it.speechEngineIdleMinutes
            assertTrue(it.toString(), engine == 0 || it.speechRefinerIdleMinutes in 1..engine)
        }
    }

    @Test fun autoHoldsOnlyWhileTheValuesAreStillTheDetectedTier() {
        val high = PerformanceProfile.presets.getValue(PerformanceTier.HIGH)
        assertTrue(PerformanceProfile.isAuto(true, high, PerformanceTier.HIGH))
        // Glow switched off on the 按键效果 page: the page shows 自定义, not 自动.
        assertFalse(PerformanceProfile.isAuto(true, high.copy(keyGlow = false), PerformanceTier.HIGH))
        assertFalse(PerformanceProfile.isAuto(true, PerformanceProfile.presets.getValue(PerformanceTier.LOW), PerformanceTier.HIGH))
        assertFalse(PerformanceProfile.isAuto(false, high, PerformanceTier.HIGH))
    }

    @Test fun autoKeepsTheTierItAppliedUntilAMeasurementChangesIt() {
        val high = PerformanceProfile.presets.getValue(PerformanceTier.HIGH)
        val medium = PerformanceProfile.presets.getValue(PerformanceTier.MEDIUM)
        // 旧版本没记套的哪档：值对得上任一档就还是自动，升级后不会先变成「自定义」。
        assertTrue(PerformanceProfile.isAuto(true, high, null))
        assertTrue(PerformanceProfile.isAuto(true, medium, null))
        assertFalse(PerformanceProfile.isAuto(true, high.copy(keyGlow = false), null))
        assertFalse(PerformanceProfile.isAuto(true, medium, PerformanceTier.HIGH))
    }

    @Test fun firstRunKeyEffectsAreTheSameTierPreset() {
        listOf(device, device.copy(totalMemoryBytes = 6 * gib), device.copy(totalMemoryBytes = 3 * gib)).forEach {
            val preset = PerformanceProfile.presets.getValue(PerformanceTiers.classify(it))
            val effects = com.kingzcheung.xime.runtime.adaptation.DeviceKeyEffectDefaults.choose(it)
            assertEquals(it.toString(), preset.keyAnimation, effects.animation)
            assertEquals(it.toString(), preset.keyGlow, effects.glow)
        }
    }

    @Test fun scoreRunsFromLowToFullAndTiersSitInOrder() {
        val low = PerformanceProfile.presets.getValue(PerformanceTier.LOW)
        val medium = PerformanceProfile.presets.getValue(PerformanceTier.MEDIUM)
        val high = PerformanceProfile.presets.getValue(PerformanceTier.HIGH)
        assertEquals(0, PerformanceProfile.score(low))
        assertEquals(45, PerformanceProfile.score(medium))
        assertEquals(88, PerformanceProfile.score(high))
        assertEquals(100, PerformanceProfile.score(PerformanceSettings(true, true, 0, 0)))
    }

    @Test fun refinerKeptLongerThanTheWholeEngineDoesNotScoreMore() {
        val low = PerformanceProfile.presets.getValue(PerformanceTier.LOW)
        assertEquals(PerformanceProfile.score(low), PerformanceProfile.score(low.copy(speechRefinerIdleMinutes = 0)))
    }

    @Test fun offLevelValuesScoreLikeTheNearestSliderLevel() {
        val medium = PerformanceProfile.presets.getValue(PerformanceTier.MEDIUM)
        assertEquals(PerformanceProfile.idleLevelIndex(30), PerformanceProfile.idleLevelIndex(25))
        assertEquals(PerformanceProfile.score(medium), PerformanceProfile.score(medium.copy(speechEngineIdleMinutes = 25)))
    }

    @Test fun lowerTiersAlsoTurnOffHeavyEffectsButHighNeverTurnsThemOn() {
        val high = PerformanceProfile.presets.getValue(PerformanceTier.HIGH)
        // 高配不管后四项：开着点按气泡、液态玻璃也还是高配。
        assertEquals(PerformanceTier.HIGH, PerformanceProfile.matching(high.copy(pressBubble = true, liquidGlass = true)))
        val medium = PerformanceProfile.presets.getValue(PerformanceTier.MEDIUM)
        assertEquals(PerformanceTier.MEDIUM, PerformanceProfile.matching(medium.copy(pressBubble = true)))
        assertNull(PerformanceProfile.matching(medium.copy(liquidGlass = true)))
        val low = PerformanceProfile.presets.getValue(PerformanceTier.LOW)
        assertNull(PerformanceProfile.matching(low.copy(handwritingLive = true)))
        // 选档：前四项换成这档的，要关的关掉，没开的不替用户打开。
        val everything = PerformanceSettings(true, true, 0, 0, true, true, true, true)
        assertEquals(low, PerformanceProfile.applied(everything, PerformanceTier.LOW))
        assertEquals(medium.copy(pressBubble = true, glidePreview = true, handwritingLive = true),
            PerformanceProfile.applied(everything, PerformanceTier.MEDIUM))
        assertEquals(high, PerformanceProfile.applied(PerformanceSettings(false, false, 1, 1), PerformanceTier.HIGH))
        PerformanceTier.entries.forEach { assertEquals(it, PerformanceProfile.matching(PerformanceProfile.applied(everything, it))) }
    }

    @Test fun onlyTheLowTierTurnsTheKeyGapLightOff() {
        // 键缝动效默认关，档位只关不开：低配关掉，中配、高配照原样，哪一档都不会替用户打开。
        val lit = PerformanceSettings(true, true, 0, 0, keyGapLight = true)
        assertFalse(PerformanceProfile.applied(lit, PerformanceTier.LOW).keyGapLight)
        assertTrue(PerformanceProfile.applied(lit, PerformanceTier.MEDIUM).keyGapLight)
        assertTrue(PerformanceProfile.applied(lit, PerformanceTier.HIGH).keyGapLight)
        PerformanceTier.entries.forEach {
            assertFalse(PerformanceProfile.applied(lit.copy(keyGapLight = false), it).keyGapLight)
            assertEquals(it, PerformanceProfile.matching(PerformanceProfile.applied(lit, it)))
        }
        // 低配档上自己把它打开了，就不再是低配。
        assertNull(PerformanceProfile.matching(PerformanceProfile.presets.getValue(PerformanceTier.LOW).copy(keyGapLight = true)))
        assertEquals(PerformanceTier.MEDIUM,
            PerformanceProfile.matching(PerformanceProfile.presets.getValue(PerformanceTier.MEDIUM).copy(keyGapLight = true)))
    }
}
