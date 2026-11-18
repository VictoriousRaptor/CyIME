package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NineKeyFuzzySettingsTest {
    @Test fun everySwitchOffersBothDirectionsUsingTheCurrentKeyMap() {
        val examples = mapOf(
            SpellingPatches.Fuzzy.Z to ("zi" to "zhi"), SpellingPatches.Fuzzy.C to ("ci" to "chi"),
            SpellingPatches.Fuzzy.S to ("si" to "shi"), SpellingPatches.Fuzzy.NL to ("nan" to "lan"),
            SpellingPatches.Fuzzy.FH to ("fu" to "hu"), SpellingPatches.Fuzzy.RL to ("ruo" to "luo"),
            SpellingPatches.Fuzzy.AN to ("fan" to "fang"), SpellingPatches.Fuzzy.EN to ("chen" to "cheng"),
            SpellingPatches.Fuzzy.IN to ("xin" to "xing"), SpellingPatches.Fuzzy.IAN to ("xian" to "xiang"),
            SpellingPatches.Fuzzy.UAN to ("zhuan" to "zhuang"))
        val natural = DoublePinyinScheme.BUILTINS.single { it.id == DoublePinyinScheme.ZIRANMA_ID }
        for (scheme in listOf(null, DoublePinyinScheme.FLYPY, natural)) {
            for ((group, pair) in examples) {
                val index = NineKeyFuzzyReadings(scheme, setOf(group))
                for ((typed, wanted) in listOf(pair, pair.second to pair.first)) {
                    val spelling = scheme?.code(typed) ?: typed
                    val keys = spelling.map { "22233344455566677778889999"[it - 'a'] }.joinToString("")
                    assertTrue("${scheme?.id} $keys: $typed → $wanted",
                        index.options(keys).any { it.pinyin == wanted && it.digits == keys.length &&
                            it.spelling == (scheme?.code(wanted) ?: wanted) })
                }
            }
        }
    }

    @Test fun fullAndDoubleNineKeyUseTheSelectedReadingsWithoutChangingOtherSchemes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(app.cacheDir, "fuzzy-rime").apply { mkdirs() }
        val nine = File(dir, "t9_pinyin.schema.yaml")
        File("src/main/assets/rime_chinese/t9_pinyin.schema.yaml").copyTo(nine)
        val doubleNine = DoublePinyinScheme.t9Id(DoublePinyinScheme.FLYPY.id)
        File(dir, "$doubleNine.schema.yaml").writeText(DoublePinyinScheme.FLYPY.t9Schema())
        for (id in listOf("pinyin_14jian", "stroke", "japanese_kana")) nine.copyTo(File(dir, "$id.schema.yaml"))
        val full = File(dir, "t9_pinyin.custom.yaml")
        val double = File(dir, "$doubleNine.custom.yaml")
        val personal = "patch:\n  \"menu/page_size\": 12\n"
        full.writeText(personal)
        assertFalse(SpellingPatches.sync(app, dir))
        for (group in listOf(SpellingPatches.Fuzzy.S, SpellingPatches.Fuzzy.IN)) SpellingPatches.setFuzzy(app, group, true)
        assertTrue(SpellingPatches.sync(app, dir))
        val fullRules = full.readText()
        assertTrue(fullRules.contains("fuzz/^sh/s/") && fullRules.contains("fuzz/ing$/in/"))
        assertTrue(fullRules.indexOf("fuzz/^sh/s/") > fullRules.lastIndexOf("derive/[wxyz]/9/"))
        assertFalse(fullRules.contains("fuzz/^n/l/"))
        assertTrue(double.readText().contains("\"t9/left_panel_mode\": pinyin"))
        val groups = SpellingPatches.fuzzy(app)
        val readings = NineKeyFuzzyReadings(null, groups).options("7454")
        assertTrue(readings.any { it.pinyin == "shi" && it.spelling == "shi" && it.digits == 2 && it.near })
        assertFalse(readings.any { it.pinyin == "ni" })
        val doubleReadings = NineKeyFuzzyReadings(DoublePinyinScheme.FLYPY, groups).options("7454")
        assertTrue(doubleReadings.any { it.pinyin == "si" && !it.near })
        assertTrue(doubleReadings.any { it.pinyin == "shi" && it.spelling == "ui" && it.digits == 2 && it.near })
        // 没开模糊音时双拼九键也有拼音栏：只列本方案两个键的精确读音（zhong 是 vs，按 87）。
        val exact = NineKeyFuzzyReadings(DoublePinyinScheme.FLYPY, emptySet()).options("8746")
        assertTrue(exact.any { it.pinyin == "zhong" && it.spelling == "vs" && it.digits == 2 && !it.near })
        assertTrue(exact.none { it.near })
        // 微软双拼 ing 的分号和 L 同在 5 键：95 列出 xing（送 x;）。
        assertTrue(NineKeyFuzzyReadings(DoublePinyinScheme.MICROSOFT, emptySet()).options("95")
            .any { it.pinyin == "xing" && it.spelling == "x;" && it.digits == 2 })
        System.getenv("CYIME_FUZZY_FIXTURE")?.let { path ->
            val output = File(path).apply { mkdirs() }
            listOf(nine, File(dir, "$doubleNine.schema.yaml"), full, double).forEach { it.copyTo(File(output, it.name), overwrite = true) }
        }
        assertFalse(SpellingPatches.sync(app, dir))
        SpellingPatches.setFuzzyDoublePinyin(app, false)
        assertTrue(SpellingPatches.sync(app, dir))
        assertFalse(double.exists())
        assertTrue(full.readText().contains("fuzz/^sh/s/"))
        for (group in listOf(SpellingPatches.Fuzzy.S, SpellingPatches.Fuzzy.IN)) SpellingPatches.setFuzzy(app, group, false)
        assertTrue(SpellingPatches.sync(app, dir))
        assertEquals(personal, full.readText())
        assertEquals(listOf(full.name), dir.listFiles()!!.filter { it.name.endsWith(".custom.yaml") }.map { it.name })
    }

    @Test fun digitsSwitchDerivesTheSelectedGroupsBeforeTheDigitMappingAndIsOffByDefault() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(app.cacheDir, "fuzzy-digits").apply { mkdirs() }
        File("src/main/assets/rime_chinese/t9_pinyin.schema.yaml").copyTo(File(dir, "t9_pinyin.schema.yaml"))
        val doubleNine = DoublePinyinScheme.t9Id(DoublePinyinScheme.FLYPY.id)
        File(dir, "$doubleNine.schema.yaml").writeText(DoublePinyinScheme.FLYPY.t9Schema())
        File("src/main/assets/rime_chinese/t9_pinyin.schema.yaml").copyTo(File(dir, "rime_ice.schema.yaml"))
        val full = File(dir, "t9_pinyin.custom.yaml")
        val double = File(dir, "$doubleNine.custom.yaml")
        assertFalse(SpellingPatches.fuzzyT9Digits(app))
        // 开着但一组都没开：不写任何东西。
        SpellingPatches.setFuzzyT9Digits(app, true)
        assertFalse(SpellingPatches.sync(app, dir))
        SpellingPatches.setFuzzyT9Digits(app, false)
        SpellingPatches.setFuzzy(app, SpellingPatches.Fuzzy.S, true)
        assertTrue(SpellingPatches.sync(app, dir))
        assertFalse(full.readText().contains("derive/^sh/s/"))  // 默认关：只有点读音时的近音
        SpellingPatches.setFuzzyT9Digits(app, true)
        assertTrue(SpellingPatches.sync(app, dir))
        for (file in listOf(full, double)) {
            val text = file.readText()
            assertTrue(file.name, text.indexOf("derive/^sh/s/") in 0 until text.indexOf("derive/[abc]/2/"))
            assertFalse(file.name, text.contains("derive/^n/l/"))
        }
        assertTrue(full.readText().indexOf("fuzz/^sh/s/") > full.readText().indexOf("derive/[wxyz]/9/"))
        // 26 键的全拼方案不受这个开关影响（26 键写降权的 fuzz）。
        val qwerty = File(dir, "rime_ice.custom.yaml").readText()
        assertEquals(1, Regex(Regex.escape("fuzz/^sh/s/")).findAll(qwerty).count())
        assertFalse(qwerty.contains("derive/^sh/s/"))
        assertFalse(SpellingPatches.sync(app, dir))
        SpellingPatches.setFuzzyT9Digits(app, false)
        assertTrue(SpellingPatches.sync(app, dir))
        assertFalse(full.readText().contains("derive/^sh/s/"))
        assertTrue(full.readText().contains("fuzz/^sh/s/"))
        SpellingPatches.setFuzzy(app, SpellingPatches.Fuzzy.S, false)
    }

    @Test fun nineKeyCostItemsShareTheGroupSwitchWithoutTurningEachOtherOff() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val qwerty = PerformanceCosts.Item.fuzzy(SpellingPatches.Fuzzy.S)
        val nine = PerformanceCosts.Item.t9Fuzzy(SpellingPatches.Fuzzy.S)
        PerformanceCosts.set(app, nine, true)
        assertTrue(SpellingPatches.fuzzyT9Digits(app) && SpellingPatches.Fuzzy.S in SpellingPatches.fuzzy(app))
        assertTrue(PerformanceCosts.isOn(app, qwerty) && PerformanceCosts.isOn(app, nine))
        // 关九键那一项只关「九键按数字也模糊」，这一组留着（26 键那一项还开着）。
        PerformanceCosts.set(app, nine, false)
        assertFalse(PerformanceCosts.isOn(app, nine))
        assertTrue(PerformanceCosts.isOn(app, qwerty))
        PerformanceCosts.set(app, qwerty, false)
        assertFalse(PerformanceCosts.isOn(app, qwerty) || PerformanceCosts.isOn(app, nine))
        // 九键开关开着时，这一组的九键项跟着这一组开关走。
        SpellingPatches.setFuzzyT9Digits(app, true)
        assertFalse(PerformanceCosts.isOn(app, nine))
        PerformanceCosts.set(app, qwerty, true)
        assertTrue(PerformanceCosts.isOn(app, nine))
        PerformanceCosts.set(app, qwerty, false)
        SpellingPatches.setFuzzyT9Digits(app, false)
    }
}
