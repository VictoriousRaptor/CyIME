package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 开关性能开销：有值得说的开销才写一行，不标估算；总代价组合实测过用实测，
 * 没测过按单项相加再用最接近的实测组合校准。
 */
class PerformanceCostsTest {
    private val sample = """
        {"version": 1, "devices": [
          {"model": "SM-X800", "name": "Galaxy Tab S8+", "tier": "high", "measuredAt": "2026-11-03", "layout": "中文全拼 26 键",
           "baseline": {"keyMs": 30.0, "memoryMb": 200}, "noiseMs": 0.5,
           "items": {"neighbor_correction": {"keyMs": 3.0, "memoryMb": 2},
                     "fuzzy_z_zh": {"keyMs": 1.5, "memoryMb": 6},
                     "glass": {"keyMs": 0.2, "memoryMb": 0},
                     "t9_fuzzy_s_sh": {"keyMs": 2.0, "memoryMb": 4, "baselineKeyMs": 20.0},
                     "t9_fuzzy_an_ang": {"keyMs": 3.0, "memoryMb": 5, "baselineKeyMs": 20.0}},
           "combos": [{"name": "current", "on": ["neighbor_correction", "fuzzy_z_zh"], "keyMs": 3.6, "memoryMb": 7}],
           "future_field": 1}
        ]}
    """.trimIndent()

    private val table = checkNotNull(PerformanceCosts.parse(sample))
    private val device = table.devices.single()

    @Test fun parsesAndIgnoresUnknownFields() {
        assertEquals("SM-X800", device.model)
        assertEquals(3.0, device.items.getValue("neighbor_correction").keyMs!!, 1e-9)
        assertNull(PerformanceCosts.parse("not json"))
    }

    @Test fun bundledTableParses() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = listOf(File(dir, "src/main/assets/perf_costs.json"), File(dir, "app/src/main/assets/perf_costs.json")).firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        val bundled = checkNotNull(PerformanceCosts.parse(checkNotNull(file).readText()))
        // 表里每个开关名都得是登记过的，否则设置页对不上。
        bundled.devices.forEach { d -> (d.items.keys + d.combos.flatMap { it.on }).forEach { assertNotNull(it, PerformanceCosts.Item.byId(it)) } }
        // 用户 11-03：每个登记的开关装好就有一行代价，不等本机实测。
        val reference = bundled.devices.first()
        PerformanceCosts.Item.entries.forEach { item ->
            assertNotNull(item.id, reference.items[item.id]?.keyMs)
            // 11-03 起开销小到不值一提的开关不出这一行（note 为 null），这里只要求表里有数。
        }
        // 滑行预览那行写的间隔跟代码里的一致。
        assertTrue(reference.items.getValue("glide_preview").note!!
            .contains("每 ${com.kingzcheung.xime.ui.keyboard.GLIDE_PREVIEW_INTERVAL_MS} ms"))
    }

    @Test fun everyFuzzyGroupHasAnItem() {
        SpellingPatches.Fuzzy.entries.forEach { assertEquals("fuzzy_" + it.id, PerformanceCosts.Item.fuzzy(it).id) }
        SpellingPatches.Fuzzy.entries.forEach {
            val nine = PerformanceCosts.Item.t9Fuzzy(it)
            assertEquals("t9_fuzzy_" + it.id, nine.id)
            assertTrue(nine.nineKey && nine.redeploy)
            assertFalse(PerformanceCosts.Item.fuzzy(it).nineKey)
        }
        assertEquals(PerformanceCosts.Item.entries.size, PerformanceCosts.Item.entries.map { it.id }.toSet().size)
        // 双拼简拼和其他写拼写补丁的开关一样，拨完要重新部署，测量脚本按这个重启输入法再测。
        assertTrue(PerformanceCosts.Item.DOUBLE_PINYIN_ABBREVIATION.redeploy)
        assertFalse(PerformanceCosts.Item.DOUBLE_PINYIN_ABBREVIATION.nineKey)
    }

    @Test fun picksLocalThenSameModelThenSameTier() {
        val other = table.copy(devices = listOf(device.copy(model = "SM-S9180", name = "")))
        assertTrue(PerformanceCosts.pick(table, other, "SM-X800", "HIGH")!!.local)
        val sameModel = PerformanceCosts.pick(null, table, "sm-x800", "LOW")!!
        assertTrue(sameModel.sameModel); assertFalse(sameModel.local)
        val byTier = PerformanceCosts.pick(null, table, "Pixel 8", "HIGH")!!
        assertFalse(byTier.sameModel); assertFalse(byTier.scaled)
        // 没有同档位的机器：用表里第一台按档位折算，不再空着。
        val low = PerformanceCosts.pick(null, table, "Pixel 8", "LOW")!!
        assertTrue(low.scaled)
        assertEquals(3.0 * 2.8, low.device.items.getValue("neighbor_correction").keyMs!!, 1e-9)
        assertEquals(30.0 * 2.8, low.device.baseline.keyMs, 1e-9)
        assertEquals(2.0, low.device.items.getValue("neighbor_correction").memoryMb!!, 1e-9)
        assertTrue(PerformanceCosts.note(low, "neighbor_correction")!!.endsWith("(Galaxy Tab S8+ 实测按本机档位折算，仅供参考)"))
        assertNull(PerformanceCosts.pick(null, null, "SM-X800", "HIGH"))
    }

    @Test fun localTableFillsMissingItemsFromTheBundledOneAsEstimates() {
        val mine = table.copy(devices = listOf(device.copy(items = mapOf("glass" to PerformanceCosts.Cost(keyMs = 0.8)))))
        val source = PerformanceCosts.pick(mine, table, "SM-X800", "HIGH")!!
        assertTrue(source.local)
        assertFalse(source.device.items.getValue("glass").estimated)
        assertEquals(0.8, source.device.items.getValue("glass").keyMs!!, 1e-9)
        assertTrue(source.device.items.getValue("neighbor_correction").estimated)
        assertEquals("性能开销：每键 +3.0 ms(慢 10%) · 内存 +2 MB", PerformanceCosts.note(source, "neighbor_correction"))
        assertEquals(listOf("neighbor_correction"), PerformanceCosts.total(source.device, setOf("glass", "neighbor_correction")).estimated)
    }

    @Test fun voiceResidentMemoryIsInTheBundledTableAndFillsLocalTables() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            file = listOf(File(dir, "src/main/assets/perf_costs.json"), File(dir, "app/src/main/assets/perf_costs.json")).firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        val bundled = checkNotNull(PerformanceCosts.parse(checkNotNull(file).readText()))
        // 平板 11-03 实测：语音常驻时 :asr 一直占 605 MB，总开销不能再漏掉它。
        val tablet = bundled.devices.first { it.model == "SM-X800" }
        assertEquals(605.0, tablet.voiceResident!!.memoryMb!!, 1e-9)
        assertFalse(tablet.voiceResident!!.estimated)
        assertEquals("内存 +605 MB", PerformanceCosts.describe(tablet.voiceResident, tablet))
        // 本机实测表没测这一项：从安装包表补上。
        val mine = bundled.copy(devices = listOf(tablet.copy(voiceResident = null)))
        assertEquals(605.0, PerformanceCosts.pick(mine, bundled, "SM-X800", "HIGH")!!.device.voiceResident!!.memoryMb!!, 1e-9)
        // 本机测过就用本机的。
        val measured = bundled.copy(devices = listOf(tablet.copy(voiceResident = PerformanceCosts.Cost(memoryMb = 500.0))))
        assertEquals(500.0, PerformanceCosts.pick(measured, bundled, "SM-X800", "HIGH")!!.device.voiceResident!!.memoryMb!!, 1e-9)
    }

    @Test fun neverReleaseWarningSaysHowMuchAndWhatHappensWhenMemoryRunsOut() {
        val withVoice = device.copy(voiceResident = PerformanceCosts.Cost(memoryMb = 605.0))
        val same = PerformanceCosts.voiceResidentWarning(PerformanceCosts.Source(withVoice, local = false, sameModel = true))
        assertTrue(same, same.startsWith("语音模型会一直占着 605 MB 内存，"))
        assertTrue(same.contains("系统内存紧张时会自动卸掉语音模型"))
        assertTrue(same.contains("打字不受影响"))
        // 用户 11-03：说明一律英文括号。
        assertFalse(same.contains('（') || same.contains('）'))
        val other = PerformanceCosts.voiceResidentWarning(PerformanceCosts.Source(withVoice, local = false, sameModel = false))
        assertTrue(other, other.startsWith("语音模型会一直占着 605 MB 内存(Galaxy Tab S8+ 实测)，"))
        // 表里没有这项或读不到表：不写数，提醒照样有。
        assertTrue(PerformanceCosts.voiceResidentWarning(null).startsWith("语音模型会一直占着内存，"))
        assertTrue(PerformanceCosts.voiceResidentWarning(PerformanceCosts.Source(device, local = false, sameModel = true))
            .startsWith("语音模型会一直占着内存，"))
    }

    @Test fun negligibleCostsShowNoLineAndNoAbout() {
        val estimates = device.copy(items = mapOf("t9_glide_typing" to PerformanceCosts.Cost(0.0, 0.0, estimated = true),
            "glide_typing" to PerformanceCosts.Cost(0.2, 8.0, estimated = true)))
        val source = PerformanceCosts.Source(estimates, local = false, sameModel = false)
        assertTrue(estimates.allEstimated)
        // 11-03：没有值得说的开销不出这一行；不写「估算」「约」。
        assertNull(PerformanceCosts.note(source, "t9_glide_typing"))
        assertEquals("性能开销：内存 +8 MB", PerformanceCosts.note(source, "glide_typing"))
        assertEquals("合计：内存 +8 MB",
            PerformanceCosts.totalNote(source, setOf("t9_glide_typing", "glide_typing"), "合计"))
    }

    @Test fun costThatIsNotPerKeyStillGetsALine() {
        // 滑行预览不拖慢点按、不占内存，只在滑动中后台识别：照样出一行，写清楚什么时候占。
        val preview = PerformanceCosts.Cost(0.0, 0.0, estimated = true, note = "滑动中每 60 ms 在后台识别一次")
        val source = PerformanceCosts.Source(device.copy(items = mapOf("glide_preview" to preview)), local = false, sameModel = true)
        assertEquals("性能开销：滑动中每 60 ms 在后台识别一次", PerformanceCosts.note(source, "glide_preview"))
        // 本机测过这一项（没写 note）时沿用安装包表的说明。
        val mine = device.copy(items = mapOf("glide_preview" to PerformanceCosts.Cost(0.1, 0.0)))
        assertEquals("滑动中每 60 ms 在后台识别一次",
            PerformanceCosts.fillFrom(mine, source.device).items.getValue("glide_preview").note)
    }

    @Test fun noteShowsMeasuredCostAndHidesUnmeasured() {
        val source = PerformanceCosts.Source(device, local = true, sameModel = true)
        assertEquals("性能开销：每键 +3.0 ms(慢 10%) · 内存 +2 MB", PerformanceCosts.note(source, "neighbor_correction"))
        // 低于测量误差不说“慢 1%”，内存不到 1 MB 不写。
        assertNull(PerformanceCosts.note(source, "glass"))
        assertNull(PerformanceCosts.note(source, "mixed_input"))
        val reference = source.copy(local = false, sameModel = false)
        assertTrue(PerformanceCosts.note(reference, "fuzzy_z_zh")!!.endsWith("(Galaxy Tab S8+ 实测，仅供参考)"))
    }

    @Test fun totalUsesMeasuredComboWhenExact() {
        val total = PerformanceCosts.total(device, setOf("neighbor_correction", "fuzzy_z_zh"))
        assertTrue(total.measured)
        assertEquals(3.6, total.keyMs, 1e-9)
        assertEquals(7.0, total.memoryMb, 1e-9)
        assertEquals(listOf("neighbor_correction", "fuzzy_z_zh"), total.items.map { it.first })
    }

    @Test fun totalCalibratesSumWithNearestCombo() {
        // 单项和 4.7，最接近的组合实测 3.6 / 单项和 4.5 = 0.8。
        val total = PerformanceCosts.total(device, setOf("neighbor_correction", "fuzzy_z_zh", "glass", "mixed_input"))
        assertFalse(total.measured)
        assertEquals(4.7 * 0.8, total.keyMs, 1e-9)
        assertEquals(listOf("mixed_input"), total.unmeasured)
    }

    @Test fun nineKeyItemsUseTheirOwnBaselineForThePercentage() {
        val source = PerformanceCosts.Source(device, local = true, sameModel = true)
        // 九键那次全关基线 20 ms：+2.0 ms 是慢 10%，不按 26 键的 30 ms 算成 7%。
        assertEquals("九键性能开销：每键 +2.0 ms(慢 10%) · 内存 +4 MB", PerformanceCosts.note(source, "t9_fuzzy_s_sh", "九键性能开销"))
        assertEquals("性能开销(已开的 2 组合计)：每键 +5.0 ms(慢 25%) · 内存 +9 MB",
            PerformanceCosts.totalNote(source, setOf("t9_fuzzy_s_sh", "t9_fuzzy_an_ang"), "性能开销(已开的 2 组合计)"))
        // 一组都没测过（或一组都没开）不显示。
        assertNull(PerformanceCosts.totalNote(source, setOf("t9_fuzzy_z_zh"), "合计"))
        assertNull(PerformanceCosts.totalNote(source, emptySet(), "合计"))
        // 和 26 键的项混在一起时基线不同，按设备基线算。
        assertNull(PerformanceCosts.total(device, setOf("t9_fuzzy_s_sh", "glass")).baselineKeyMs)
        assertEquals(20.0, PerformanceCosts.total(device, setOf("t9_fuzzy_s_sh", "t9_fuzzy_an_ang")).baselineKeyMs!!, 1e-9)
    }

    @Test fun totalWithoutRelatedComboIsPlainSum() {
        val total = PerformanceCosts.total(device, setOf("glass"))
        assertEquals(0.2, total.keyMs, 1e-9)
        assertEquals(0.0, PerformanceCosts.total(device, emptySet()).keyMs, 1e-9)
    }

    private val withDictionaries = checkNotNull(PerformanceCosts.parse("""
        {"version": 1, "devices": [
          {"model": "SM-X800", "baseline": {"keyMs": 30.0},
           "dictionaryBaseline": {"keyMs": 32.0, "keyP95Ms": 50.0, "memoryMb": 210},
           "dictionaries": [{"words": 2000, "keyMs": 0.4, "keyP95Ms": 1.0, "memoryMb": 2},
                            {"words": 50000, "keyMs": 3.0, "keyP95Ms": 5.0, "memoryMb": 20}]}
        ]}
    """.trimIndent())).devices.single()

    @Test fun dictionaryCostFollowsTheMeasuredCurve() {
        assertNull(PerformanceCosts.dictionaryCost(device, 10_000))
        assertNull(PerformanceCosts.dictionaryCost(withDictionaries, 0))
        assertEquals(0.2, PerformanceCosts.dictionaryCost(withDictionaries, 1_000)!!.keyMs!!, 1e-9)
        assertEquals(1.7, PerformanceCosts.dictionaryCost(withDictionaries, 26_000)!!.keyMs!!, 1e-9)
        assertEquals(6.0, PerformanceCosts.dictionaryCost(withDictionaries, 100_000)!!.keyMs!!, 1e-9)
        assertEquals(11.0, PerformanceCosts.dictionaryCost(withDictionaries, 26_000)!!.memoryMb!!, 1e-9)
    }

    @Test fun dictionaryPageUsesTheSlowestKeysOfTheDeviceCurve() {
        val curve = checkNotNull(CellDictionaryCost.Curve.fromDevice(withDictionaries))
        assertTrue(curve.measuredOnDevice)
        assertEquals(2.0, CellDictionaryCost.slowdown(2_000, curve), 1e-9)   // 1.0 / 50 ms
        assertEquals(10.0, CellDictionaryCost.slowdown(50_000, curve), 1e-9)
        assertNull(CellDictionaryCost.Curve.fromDevice(device))
    }

    // ---- 11-03 设置排查：引擎实测、慢键、档位预估 ----

    @Test fun engineMeasuredCostsShowSlowKeysAndSayWhereTheyCameFrom() {
        val engine = device.copy(items = device.items + ("neighbor_correction" to
            PerformanceCosts.Cost(keyMs = 0.24, keyP95Ms = 1.18, basis = PerformanceCosts.BASIS_ENGINE)))
        val source = PerformanceCosts.Source(engine, local = false, sameModel = true)
        // 中位数低于误差不写，慢键多出 1.2 ms 要写；来源写电脑引擎实测，不冒充平板实测。
        assertEquals("性能开销：慢键 +1.2 ms(电脑引擎实测)", PerformanceCosts.note(source, "neighbor_correction"))
        // 按档位折算的系数是手机芯片的，电脑上测的不乘。
        val low = PerformanceCosts.scale(engine, 2.8)
        assertEquals(0.24, low.items.getValue("neighbor_correction").keyMs!!, 1e-9)
        // 本机表缺这项时从安装包表补，引擎实测不标成估算。
        val filled = PerformanceCosts.fillFrom(device.copy(items = emptyMap()), engine)
        assertFalse(filled.items.getValue("neighbor_correction").estimated)
        assertEquals(1.18, PerformanceCosts.total(engine, setOf("neighbor_correction")).keyP95Ms, 1e-9)
    }

    @Test fun projectedOnlyChangesTheSwitchesTheTierManages() {
        val on = setOf("glass", "liquid_glass", "press_bubble", "key_glow", "neighbor_correction")
        val low = PerformanceProfile.applied(PerformanceSettings(true, true, 0, 0, liquidGlass = true, pressBubble = true),
            com.kingzcheung.xime.runtime.adaptation.PerformanceTier.LOW)
        assertEquals(setOf("glass", "neighbor_correction"), PerformanceCosts.projected(on, low))
        val high = PerformanceProfile.applied(PerformanceSettings(false, false, 1, 1, liquidGlass = true, pressBubble = true),
            com.kingzcheung.xime.runtime.adaptation.PerformanceTier.HIGH)
        // 高配只开动效和炫光，其余照旧；液态玻璃要玻璃开着才算。
        assertEquals(on + "key_animation", PerformanceCosts.projected(on, high))
        assertFalse("liquid_glass" in PerformanceCosts.projected(on - "glass", high))
    }

    @Test fun aTierThatTurnsTheKeyGapLightOffDropsEveryEffectOfIt() {
        val on = setOf("key_gap_light", "key_gap_light_typing", "key_gap_light_commit", "neighbor_correction")
        val current = PerformanceSettings(true, true, 0, 0, keyGapLight = true)
        val low = PerformanceProfile.applied(current, com.kingzcheung.xime.runtime.adaptation.PerformanceTier.LOW)
        // 低配关总开关：各种动效的开销一起不算，别的开关照旧。
        assertEquals(setOf("neighbor_correction"), PerformanceCosts.projected(on, low))
        val medium = PerformanceProfile.applied(current, com.kingzcheung.xime.runtime.adaptation.PerformanceTier.MEDIUM)
        assertEquals(on + "key_animation", PerformanceCosts.projected(on, medium))
    }

    @Test fun loadCountsVoiceNineKeyModelAndScoresAgainstEverythingOn() {
        val withExtras = device.copy(voiceResident = PerformanceCosts.Cost(memoryMb = 600.0),
            t9Model = PerformanceCosts.Cost(keyMs = 0.5, keyP95Ms = 4.0, basis = PerformanceCosts.BASIS_ENGINE))
        val nothing = PerformanceCosts.load(withExtras, emptySet(), voiceResident = false, t9Model = false, dictionaryWords = 0)
        assertEquals(0.0, nothing.memoryMb, 1e-9)
        val voice = PerformanceCosts.load(withExtras, emptySet(), voiceResident = true, t9Model = true, dictionaryWords = 0)
        assertEquals(600.0, voice.memoryMb, 1e-9)
        assertEquals(4.0, voice.keyP95Ms, 1e-9)
        val full = PerformanceCosts.load(withExtras, PerformanceCosts.fullOn(withExtras, nineKey = false), true, true, 0)
        assertEquals(0, PerformanceCosts.loadScore(nothing, full))
        assertEquals(100, PerformanceCosts.loadScore(full, full))
        assertTrue(PerformanceCosts.loadScore(voice, full) in 1..99)
    }

    @Test fun totalLineSaysHowManyItemsAreMeasuredAndHowManyAreGuessed() {
        val mixed = device.copy(items = device.items +
            ("neighbor_correction" to PerformanceCosts.Cost(keyMs = 0.24, keyP95Ms = 1.18, basis = PerformanceCosts.BASIS_ENGINE)) +
            ("glass" to PerformanceCosts.Cost(keyMs = 2.0, memoryMb = 12.0, estimated = true)),
            t9Model = PerformanceCosts.Cost(keyMs = 0.54, keyP95Ms = 4.3, basis = PerformanceCosts.BASIS_ENGINE))
        val source = PerformanceCosts.Source(mixed, local = false, sameModel = true)
        assertEquals("开着的 4 项：电脑引擎实测 2 项，同型号实测 1 项，按同类推算 1 项。全关时每键 30.0 ms。",
            PerformanceCosts.basisLine(source, listOf("neighbor_correction", "glass", "fuzzy_z_zh", PerformanceCosts.T9_MODEL_ID)))
        assertEquals("全关时每键 30.0 ms。", PerformanceCosts.basisLine(source, emptyList()))
        // 总开销一行和单项同一写法；慢键为 0 不写，小到测不出返回 null。
        assertEquals("每键 +1.0 ms(慢 3%) · 慢键 +4.3 ms · 内存 +605 MB",
            PerformanceCosts.describeLoad(PerformanceCosts.Load(1.0, 4.3, 605.0), mixed))
        assertNull(PerformanceCosts.describeLoad(PerformanceCosts.Load(0.1, 0.0, 0.4), mixed))
    }
}
