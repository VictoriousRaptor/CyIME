package com.kingzcheung.xime.settings

import android.content.Context
import com.charleskorn.kaml.*
import com.kingzcheung.xime.rime.merged14Preedit
import com.kingzcheung.xime.rime.PinyinEditDisplay
import com.kingzcheung.xime.ui.keyboard.*
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class CustomKeyboardLayoutTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun swapsMergesAndSplitsPreserveAllLettersAndRowWidths() {
        val base = CustomKeyboardLayout.fresh()
        assertTrue(base.valid())
        val swapped = base.swap("e", "j")
        assertEquals(setOf('e', 'j'), swapped.movedLetters())
        val merged = swapped.merge("q")
        assertTrue(merged.valid())
        assertEquals(25, merged.rows.flatten().size)
        assertEquals("qw", merged.rows.first().first())
        assertEquals(swapped, merged.split("qw"))
        assertEquals(merged, merged.swap("qw", "a"))
        assertTrue(merged.swap("qw", "j").valid())
        assertEquals(base, base.merge("p"))
        assertFalse(base.copy(rows = listOf(listOf("a"))).valid())
        assertFalse(base.copy(id = "../bad").valid())
    }
    @Test fun arbitraryMergedCodingAndPreviewAgree() {
        val layout = CustomKeyboardLayout.fresh().merge("q").merge("e")
        assertEquals("qen", layout.encode("wen"))
        assertEquals("qen", layout.encode("qen"))
        val groups = layout.rows.flatten()
        assertEquals("wen", merged14Preedit("qen", "qen", "wen", groups))
        assertEquals("qen", merged14Preedit("qen", "qen", "men", groups))
        val display = PinyinEditDisplay("qen", "wen", groups = groups)
        assertEquals("wen", display.text)
        assertEquals(2, display.rawOffset(2))
    }
    @Test fun generatedSchemaHasIndependentPrismAndFinalTransliteration() {
        val layout = CustomKeyboardLayout.fresh().copy(name = "测试：\"引号\"").merge("q").merge("e")
        val base = File("build/generated/chinese-assets/rime_ice/rime_ice.schema.yaml").readText()
        val node = Yaml.default.parseToYamlNode(layout.schema(base)) as YamlMap
        fun entry(map: YamlMap, key: String) = map.entries.entries.first { it.key.content == key }.value
        val shortcuts = entry(node, "cyime_particle_shortcuts") as YamlMap
        val keys = (entry(shortcuts, "alphabet") as YamlScalar).content
        // On this layout w shares q: the tail-initial shortcut for 我呢 must be qon.
        assertEquals("qon", "won".map { keys[it - 'a'] }.joinToString(""))
        val translator = entry(node, "translator") as YamlMap
        assertEquals(layout.id, (entry(translator, "prism") as YamlScalar).content)
        assertEquals("rime_ice", (entry(translator, "dictionary") as YamlScalar).content)
        val algebra = entry(entry(node, "speller") as YamlMap, "algebra") as YamlList
        // qw、er 两组：点按的代表字母和长按选的另一个字母都能打（不用整表 xlit，xlit 会把 wen 解成“额”）。
        assertEquals(listOf("derive/[w]/q/", "derive/[r]/e/"), algebra.items.takeLast(2).map { (it as YamlScalar).content })
        assertFalse(algebra.items.any { (it as YamlScalar).content.startsWith("xlit/abcdefghijklmnopqrstuvwxyz/") })
        assertEquals(layout.name, (entry(entry(node, "schema") as YamlMap, "name") as YamlScalar).content)
    }
    @Test fun legacyTransliterationIsReplacedByPerGroupDerivation() {
        val layout = CustomKeyboardLayout.fresh().merge("q").merge("e")
        val legacy = "speller:\n  algebra:\n    - erase/^xx$/\n" + CustomKeyboardLayout.legacyRule(layout) + "\ntranslator:\n  dictionary: rime_ice\n"
        assertEquals("    - xlit/abcdefghijklmnopqrstuvwxyz/abcdefghijklmnopqestuvqxyz/\n", CustomKeyboardLayout.legacyRule(layout))
        val migrated = legacy.replace(CustomKeyboardLayout.legacyRule(layout), layout.mergedRules())
        assertTrue(migrated.contains("    - erase/^xx$/\n    - derive/[w]/q/\n    - derive/[r]/e/\n\ntranslator:"))
        // 没有合并键的布局没有这条规则，也不加派生。
        assertEquals("", CustomKeyboardLayout.fresh().mergedRules())
    }
    @Test fun spareSlotMovesWithoutCreatingPunctuationAndCanBeFilledExplicitly() {
        val base = CustomKeyboardLayout.fresh().withSpareSlot()
        assertTrue(base.valid())
        assertEquals("asdfghjkl_", base.rows[1].joinToString(""))
        assertEquals(26, base.typingRows().flatten().size)
        val moved = base.swap("e", CustomKeyboardLayout.EMPTY_SLOT)
        assertTrue(moved.valid())
        assertEquals("e", moved.rows[1].last())
        assertEquals(26, moved.typingRows().flatten().size)
        assertFalse(";" in moved.typingRows().flatten())
        assertFalse("_" in moved.typingRows().flatten())
        assertEquals("wen", moved.encode("wen"))
        assertEquals(setOf('e'), moved.movedLetters())
        assertEquals(base, moved.swap("e", CustomKeyboardLayout.EMPTY_SLOT))
        val explicit = moved.setSemicolonEnabled(true)
        assertTrue(explicit.valid())
        assertEquals(27, explicit.typingRows().flatten().size)
        assertEquals(";", explicit.rows[0][2])
        assertEquals(moved, explicit.setSemicolonEnabled(false))
        assertEquals(27, base.setSemicolonEnabled(true).typingRows().flatten().size)
        assertEquals(base, base.merge("l"))
        assertTrue(base.merge("q").valid())
        assertFalse(base.copy(rows = base.rows.map { it + "_" }).valid())
        assertFalse("_" in moved.gestures(emptyMap()))
    }
    @Test fun legacyStationarySemicolonMigratesButMovedOrExplicitSemicolonSurvives() {
        val dir = tmp.newFolder()
        val context = mock<Context>(); whenever(context.filesDir).thenReturn(dir)
        val file = File(dir, "custom-keyboard-layouts.json")
        fun load(rows: String, version: Int) : CustomKeyboardLayout {
            file.writeText("""[{"formatVersion":$version,"id":"pinyin_qwjrtk","name":"既有方案","rows":"$rows"}]""")
            return CustomKeyboardLayouts.load(context).single()
        }
        val original = "q,w,e,r,t,y,u,i,o,p/a,s,d,f,g,h,j,k,l,;/z,x,c,v,b,n,m"
        assertEquals(26, load(original, 1).typingRows().flatten().size)
        assertEquals("_", load(original, 1).rows[1].last())
        assertEquals(27, load(original, 2).typingRows().flatten().size)
        assertEquals(27, load("q,w,;,r,t,y,u,i,o,p/a,s,d,f,g,h,j,k,l,e/z,x,c,v,b,n,m", 1).typingRows().flatten().size)
        assertEquals(26, load(original.replace("l,;", "l,_"), 2).typingRows().flatten().size)
    }
    @Test fun movedVowelsAndMergedLabelsKeepIndependentRedSpans() {
        val layout = CustomKeyboardLayout.fresh().swap("e", "j").merge("j")
            .copy(redVowels = true, redMoved = true)
        assertTrue(layout.isRed('E'))
        assertTrue(layout.isRed('J'))
        assertTrue(layout.isRed('A'))
        assertFalse(layout.isRed('R'))
        val text = customLayoutLabel(layout, "JR", Color.White, Color(0xFF6750A4), Color.Black)
        assertEquals(1, text.spanStyles.size)
        assertEquals(0, text.spanStyles.single().start)
        assertEquals(1, text.spanStyles.single().end)
        assertFalse(layout.copy(redVowels = false, redMoved = false).isRed('E'))
    }
    @Test fun oldLayoutsLoadWithMovedRedEnabledAndCanKeepVowelPreference() {
        val dir = tmp.newFolder()
        val context = mock<Context>(); whenever(context.filesDir).thenReturn(dir)
        File(dir, "custom-keyboard-layouts.json").writeText("""[{"id":"pinyin_qwjrtk","name":"QWJRTK","rows":"q,w,j,r,t,k,u,i,o,p/a,s,d,f,g,h,e,n,l/z,x,c,y,b,v,m","redVowels":false}]""")
        val layout = CustomKeyboardLayouts.load(context).single()
        assertTrue(layout.redMoved)
        assertFalse(layout.redVowels)
        assertTrue(layout.isRed('e'))
    }
    @Test fun freshInstallAndReloadDoNotCreateExperimentalLayouts() {
        val dir = tmp.newFolder()
        val context = mock<Context>(); whenever(context.filesDir).thenReturn(dir)
        assertTrue(CustomKeyboardLayouts.load(context).isEmpty())
        assertEquals("[]", File(dir, "custom-keyboard-layouts.json").readText())
        assertTrue(CustomKeyboardLayouts.find(QwjrtkLayout.ID)!!.valid())
        val cyletix = CustomKeyboardLayouts.find(Cyletix10Layout.ID)!!
        assertTrue(cyletix.valid())
        assertEquals(listOf("qwdrf;jkyp", "asetghliou", "zxcvbnm"), cyletix.rows.map { it.joinToString("") })
        assertTrue(CustomKeyboardLayouts.load(context).isEmpty())
        assertTrue(CustomKeyboardLayouts.load(context).isEmpty())
    }
    @Test fun savedQwjrtkLayoutOverridesOptionalPreset() {
        val dir = tmp.newFolder()
        val context = mock<Context>(); whenever(context.filesDir).thenReturn(dir)
        File(dir, "custom-keyboard-layouts.json").writeText("""[{"id":"pinyin_qwjrtk","name":"我的双拇指","rows":"q,w,j,r,t,k,u,i,o,p/a,s,d,f,g,h,e,n,l/z,x,c,y,b,v,m"}]""")
        CustomKeyboardLayouts.load(context)
        assertEquals("我的双拇指", CustomKeyboardLayouts.find(QwjrtkLayout.ID)?.name)
        assertEquals("Cyletix10（实验）", CustomKeyboardLayouts.find(Cyletix10Layout.ID)?.name)
    }
    @Test fun perKeyWidthsMoveWithLettersAndScaleWithinTheirRow() {
        val base = CustomKeyboardLayout.fresh().withSpareSlot()
        assertTrue(base.widths.isEmpty())
        val widened = base.withWidth("l", 1.4f)
        assertEquals(1.4f, widened.widthOf("l"), 0.0001f)
        assertEquals(1f, widened.widthOf("a"), 0.0001f)
        assertEquals(1f, widened.widthOf("s"), 0.0001f)
        assertTrue(widened.valid())
        val row = widened.rows[1]
        // 整排仍占原来的 10 格，只是其它键等比让出一点。
        assertEquals(10f, widened.rowWidths(row).sum(), 0.001f)
        val at = row.indexOf("l")
        assertTrue(widened.rowWidths(row)[at] > base.rowWidths(row)[at])
        assertTrue(widened.rowWidths(row)[at] < 1.4f)
        assertEquals(emptyMap<String, Float>(), widened.withWidth("l", 1f).widths)
        // 交换时宽度跟着字母走
        assertEquals(1.4f, widened.swap("l", "q").widthOf("l"), 0.0001f)
        // 合并接过两格宽度，拆分平分
        assertEquals(2f, widened.merge("a").widthOf("as"), 0.0001f)
        assertEquals(widened, widened.merge("a").split("as"))
        val wide = widened.withWidth("a", 2.4f).merge("a")
        assertEquals(3.4f, wide.widthOf("as"), 0.0001f)
        assertEquals(1.7f, wide.split("as").widthOf("a"), 0.0001f)
        // 空位和独立分号键是同一格换内容，宽度跟着走
        assertEquals(1.2f, widened.withWidth(CustomKeyboardLayout.EMPTY_SLOT, 1.2f)
            .setSemicolonEnabled(true).widthOf(";"), 0.0001f)
    }
    @Test fun savedWidthsLoadSanitizedAndOldLayoutsKeepDefaultWidths() {
        val dir = tmp.newFolder()
        val context = mock<Context>(); whenever(context.filesDir).thenReturn(dir)
        val file = File(dir, "custom-keyboard-layouts.json")
        val id = "custom_pinyin_" + "a".repeat(32)
        val rows = "q,w,e,r,t,y,u,i,o,p/a,s,d,f,g,h,j,k,l,_/z,x,c,v,b,n,m"
        file.writeText("""[{"formatVersion":2,"id":"$id","name":"旧布局","rows":"$rows"}]""")
        val old = CustomKeyboardLayouts.load(context).single()
        assertTrue(old.widths.isEmpty())
        assertEquals(1f, old.widthOf("l"), 0.0001f)
        file.writeText("""[{"formatVersion":3,"id":"$id","name":"调宽","rows":"$rows","widths":{"l":1.4,"zz":9,"s":7}}]""")
        val loaded = CustomKeyboardLayouts.load(context).single()
        assertEquals(1.4f, loaded.widthOf("l"), 0.0001f)
        // 不在布局里的键丢掉，超出范围的收到上限。
        assertFalse("zz" in loaded.widths)
        assertEquals(CustomKeyboardLayout.MAX_WIDTH, loaded.widthOf("s"), 0.0001f)
    }
    @Test fun lightDarkAndColoredThemesHaveReadableVowelsAndMovedKeys() {
        val layout = CustomKeyboardLayout.fresh().swap("e", "j").copy(redVowels = true)
        for (bg in listOf(Color.White, Color(0xFF302D38), Color(0xFFDDEBDD), Color(0xFF463049))) {
            for (accent in listOf(Color(0xFF6750A4), Color(0xFF146C2E), Color(0xFFD35645), Color(0xFF9ECBFF))) {
                val (fill, text) = customLayoutKeyColors(layout, "e", bg, Color.White, accent)
                assertNotEquals(bg, fill)
                assertTrue(layoutContrast(text, fill) >= 4.5f)
                val red = vowelColor(fill, accent, Color.White)
                assertTrue(layoutContrast(red, fill) >= 4.5f)
                assertNotEquals(vowelColor(fill, Color(0xFF146C2E), Color.White), vowelColor(fill, Color(0xFF6750A4), Color.White))
            }
        }
    }

    @Test fun cyletix10KeysCanBeRearrangedAndSaved() {
        val preset = Cyletix10Layout.preset
        assertTrue(preset.valid())
        assertTrue("swapping two keys keeps the built-in layout saveable", preset.swap("d", "e").valid())
        assertTrue(preset.copy(redVowels = true).valid())
    }

    @Test fun glassEmphasisKeepsLabelsReadableOnTheGlassSurface() {
        // 玻璃下文字按玻璃键面取对比：浅字主题对深色键面、深字主题对浅色键面，都要 ≥4.5。
        val layout = Cyletix10Layout.preset.copy(redVowels = true)
        for (text in listOf(Color.White, Color(0xFF202020))) for (accent in listOf(Color(0xFF6750A4), Color(0xFFE9DDFF), Color(0xFF1A73E8))) {
            val surface = customLayoutGlassSurface(text)
            for (key in listOf("d", "e", "q")) {
                val (_, fg) = customLayoutKeyColors(layout, key, Color(0xFF55555A), text, accent, surface)
                assertTrue("$key on glass: ${layoutContrast(fg, surface)}", layoutContrast(fg, surface) >= 4.5f)
            }
        }
    }
    @Test fun layoutSchemasFollowTheCurrentChineseSchemaInsteadOfAnOldSnapshot() {
        val root = File("build/generated/chinese-assets/rime_ice")
        val base = File(root, "rime_ice.schema.yaml").readText()
        val builtin = File(root, "pinyin_cyletix10.schema.yaml").readText()
        // An untouched built-in file from an older base (here: without the candidate filters) becomes today's file.
        val old = builtin.replace(Regex("(?m)^  filters:\\n(?:    - .*\\n)+"), "")
        assertNotEquals(builtin, old)
        assertEquals(builtin, CustomKeyboardLayouts.refreshedSchema(base, old, null))
        assertEquals(builtin, CustomKeyboardLayouts.refreshedSchema(base, builtin, null))
        // A saved layout keeps its own rows and name; refreshing the current file changes nothing.
        val saved = CustomKeyboardLayout.fresh().copy(name = "我的布局").merge("q")
        val current = saved.schema(base)
        assertEquals(current, CustomKeyboardLayouts.refreshedSchema(base, old, saved))
        assertEquals(current, CustomKeyboardLayouts.refreshedSchema(base, current, saved))
        // A rime_ice that is not CyIME's own (market or hand-written) never overwrites layout schemas.
        assertNull(CustomKeyboardLayouts.refreshedSchema(base.replace("cyime_neighbor_correction: true", ""), old, null))
    }
}
