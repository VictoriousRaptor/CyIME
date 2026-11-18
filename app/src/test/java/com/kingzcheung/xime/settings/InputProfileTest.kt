package com.kingzcheung.xime.settings

import android.content.Context
import android.content.res.AssetManager
import com.kingzcheung.xime.handwriting.HandwritingLanguages
import com.kingzcheung.xime.service.InputUIState
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.supportsSplitKeyboard
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class InputProfileTest {
    @Test fun `builtin alphabetic layout identifiers never leak into display names`() {
        for (section in listOf("qwerty", "qwerty_japanese")) {
            val layout = InputProfiles.resolveLayout(section)
            assertEquals(section, layout.id)
            assertEquals("26键", layout.displayName)
            assertEquals(LayoutKind.ALPHABETIC, layout.kind)
        }
        // 英文单词方案绑在英文键盘上，和英文直接输入是同一个 26 键（布局栏不出两个「26键」）。
        assertEquals(InputLayout.QWERTY, InputProfiles.resolveLayout("qwerty_en"))
    }
    @get:Rule val temporary = TemporaryFolder()
    @Before fun initialBindings() { resetBindings() }
    @After fun resetBindings() {
        KeysConfigHelper.clearSchemaBindingsForTest()
        InputProfiles.updateInstalled(emptyList())
    }
    private fun entry(id: String) = SchemaInfo(id, id, "", "", "")

    @Test fun `scheme preview uses requested codes without applying Flypy to other variants`() {
        assertEquals("pin'yin", InputProfiles.schemePreview(entry("rime_ice").profile))
        assertEquals("xn'he'ul'pb", InputProfiles.schemePreview(entry("double_pinyin_flypy").profile))
        assertNotEquals("xn'he'ul'pb", InputProfiles.schemePreview(entry("double_pinyin_mspy").profile))
    }

    @Test fun `scheme options retain concrete double pinyin variants and real targets`() {
        val entries = listOf(entry("rime_ice").copy(name = "雾凇拼音"), entry("t9_pinyin"),
            entry("double_pinyin_flypy").copy(name = "小鹤双拼"),
            entry("double_pinyin_mspy").copy(name = "微软双拼"), entry("japanese"))
        for (current in listOf(entries[0], entries[1], entries[3])) {
            val doubles = InputProfileSelection.schemeEntries(entries, current)
                .filter { it.profile.scheme == InputScheme.DOUBLE_PINYIN }
            assertEquals(listOf("小鹤双拼", "微软双拼"), doubles.map { it.name })
            assertEquals(listOf("double_pinyin_flypy", "double_pinyin_mspy"), doubles.map { it.schemaId })
            assertTrue(doubles.all { it in entries })
            assertFalse(InputProfileSelection.schemeEntries(entries, current).any { it.schemaId == "japanese" })
        }
    }

    @Test fun `concrete choices preserve compatible layout and custom variant names`() {
        val flypy = entry("double_pinyin_flypy").copy(name = "小鹤双拼")
        val flypy14 = entry("double_pinyin_flypy_14jian").copy(name = "小鹤双拼")
        val current = entry("pinyin_14jian")
        val choices = InputProfileSelection.schemeEntries(listOf(current, flypy, flypy14), current)
        assertTrue(flypy14 in choices)
        assertFalse(flypy in choices)
        assertTrue(choices.all { it.profile.layout == InputLayout.MERGED14 })
    }

    @Test fun `pinyin layouts share a scheme but retain distinct backend identities`() {
        val profiles = listOf("pinyin_simp", "rime_ice", "t9_pinyin", "pinyin_14jian").map { InputProfiles.describe(it) }
        assertEquals(setOf(InputLanguage.CHINESE), profiles.map { it.language }.toSet())
        assertEquals(setOf(InputScheme.PINYIN), profiles.map { it.scheme }.toSet())
        assertEquals(listOf(InputLayout.QWERTY, InputLayout.QWERTY, InputLayout.T9, InputLayout.MERGED14), profiles.map { it.layout })
        assertEquals(4, profiles.map { it.engineProfile }.distinct().size)
    }

    @Test fun `layout does not determine language or scheme`() {
        assertEquals(InputScheme.DOUBLE_PINYIN, entry("double_pinyin_flypy").profile.scheme)
        assertEquals(InputScheme.WUBI, entry("wubi86").profile.scheme)
        assertEquals(InputScheme.ROMAJI, entry("japanese").profile.scheme)
        for (id in listOf("double_pinyin_flypy", "wubi86", "japanese", InputModes.ENGLISH))
            assertEquals(InputLayout.QWERTY, entry(id).profile.layout)
        assertEquals(InputLanguage.ENGLISH, InputProfiles.current("japanese_kana", true).language)
        assertEquals(EngineProfile.Direct, InputProfiles.current("rime_ice", true).engineProfile)
    }

    @Test fun `Xiaohe fourteen key is a real double pinyin combination in both selectors`() {
        val id = "double_pinyin_flypy_14jian"
        val entries = listOf("pinyin_14jian", "double_pinyin_flypy", id).map(::entry)
        assertEquals(InputLanguage.CHINESE, entry(id).profile.language)
        assertEquals(InputScheme.DOUBLE_PINYIN, entry(id).profile.scheme)
        assertEquals(InputLayout.MERGED14, entry(id).profile.layout)
        assertEquals(EngineProfile.Rime(id), entry(id).profile.engineProfile)
        assertEquals(id, InputProfileSelection.changeScheme(entries, entry("pinyin_14jian"), InputScheme.DOUBLE_PINYIN)?.schemaId)
        assertEquals(id, InputProfileSelection.changeLayout(entries, entry("double_pinyin_flypy"), InputLayout.MERGED14.id)?.schemaId)
        assertEquals("double_pinyin_flypy", InputProfileSelection.changeLayout(entries, entry(id), InputLayout.QWERTY.id)?.schemaId)
        assertEquals("pinyin_14jian", InputProfileSelection.changeScheme(entries, entry(id), InputScheme.PINYIN)?.schemaId)
        assertNull(InputProfileSelection.changeLayout(entries.filterNot { it.schemaId == id }, entry("double_pinyin_flypy"), InputLayout.MERGED14.id))
    }

    @Test fun `handwriting keeps language and does not invent a foreign recognizer`() {
        for (id in listOf("rime_ice", "japanese", InputModes.ENGLISH)) {
            val keys = entry(id).profile
            val ink = keys.handwriting()
            assertEquals(keys.language, ink.language)
            assertEquals(keys.scheme, ink.scheme)
            assertEquals(InputMode.HANDWRITING, ink.mode)
            assertEquals(InputLayout.HANDWRITING, ink.layout)
            assertFalse(ink.capabilities.editablePinyin)
            // 中文、英文、日语都有手写识别（内置手写模型：汉字、其中的字母、假名模板比对）
            assertTrue(HandwritingLanguages.supports(ink.language))
        }
        // 没有识别器的语言（未标注语言的方案）仍然不能手写
        assertFalse(HandwritingLanguages.supports(InputLanguage.UNSPECIFIED))
    }

    @Test fun `third party IDs are not guessed as Chinese or Japanese`() {
        for (id in listOf("external", "japanese_unknown", "double_pinyin_fake")) {
            assertEquals(InputLanguage.UNSPECIFIED, InputProfiles.describe(id).language)
            assertEquals(InputScheme.EXTERNAL, InputProfiles.describe(id).scheme)
            assertFalse(InputProfiles.describe(id).capabilities.editablePinyin)
        }
        val thirdParty = entry("external")
        assertTrue(InputModes.available(listOf(thirdParty)).any { it.schemaId == "external" })
        assertEquals(InputLanguage.UNSPECIFIED,
            InputModes.languageChoices(listOf(thirdParty), InputModes.ENGLISH, emptyMap()).last().language)
        assertTrue(thirdParty.selectionLabel.contains("external"))
    }

    /** 11-15 小鹤音形：中文的形码方案，不算拼音、双拼，拼音的改读音、纠错这些不碰它；双拼辅助码的码表不当方案列出来。 */
    @Test fun `Xiaohe shape code is Chinese external coding and aux tables stay hidden`() {
        val flypy = InputProfiles.describe("flypy")
        assertEquals(InputLanguage.CHINESE, flypy.language)
        assertEquals(InputScheme.EXTERNAL, flypy.scheme)
        assertFalse(flypy.capabilities.editablePinyin)
        assertEquals("形码", InputProfiles.schemePreview(entry("flypy").profile))
        assertEquals("自定义编码", InputProfiles.schemePreview(entry("external").profile))
        for (id in SpellingPatches.AuxCode.schemaIds + listOf("flypydz", "flypyok")) {
            assertTrue(id, id in CyimeInputDefaults.dependencies)
        }
    }

    @Test fun `configuration override reaches runtime state and layout routing`() {
        val files = temporary.newFolder()
        val rime = File(files, "rime").apply { mkdirs() }
        val yaml = """
            keyboard:
              t9:
                schemas: [rime_ice]
              qwerty_14:
                schemas: [t9_pinyin]
                layout:
                  rows: [[[q, w], [e, r]]]
        """.trimIndent()
        val assets = mock<AssetManager>()
        whenever(assets.open("xime.yaml")).thenAnswer { ByteArrayInputStream(yaml.toByteArray()) }
        whenever(assets.open("xime.custom.yaml")).thenThrow(IOException("absent"))
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(files)
        whenever(context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE))
            .thenReturn(mock<android.content.SharedPreferences>())
        whenever(context.assets).thenReturn(assets)
        KeysConfigHelper.loadConfig(context)
        assertTrue(isT9Schema("rime_ice"))
        assertFalse(isT9Schema("t9_pinyin"))
        assertEquals(InputLayout.T9, InputUIState(currentSchemaId = "rime_ice").inputProfile.layout)
        assertEquals("拼音 · 九键", entry("rime_ice").profile.summary)
        assertEquals(InputScheme.PINYIN, InputUIState(currentSchemaId = "rime_ice").inputProfile.scheme)
        assertEquals(InputLayout.MERGED14, InputProfiles.current("t9_pinyin").layout)
        // The UI can receive the new scheme while the previous scheme's gesture cache is active.
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        assertEquals(listOf("qw", "er"), KeysConfigHelper.getKeyRows("t9_pinyin", false).first())
        assertEquals("q", KeysConfigHelper.getKeyRows("t9_pinyin", true).first().first())
        // 14 键可分体（跨中线的双字母键在分体时拆开）；九键不分体。
        assertTrue(supportsSplitKeyboard("t9_pinyin", false))
        assertTrue(supportsSplitKeyboard("t9_pinyin", true))
        assertFalse(supportsSplitKeyboard("rime_ice", false))
        File(rime, "xime.custom.yaml").writeText("keyboard:\n  t9:\n    schemas: [t9_pinyin]\n  qwerty_14:\n    schemas: [rime_ice]\n")
        KeysConfigHelper.loadConfig(context)
        assertTrue(isT9Schema("t9_pinyin"))
        assertFalse(isT9Schema("rime_ice"))
        assertEquals(InputLayout.MERGED14, entry("rime_ice").profile.layout)
    }

    @Test fun `installed metadata is used by both engine predicates and UI and refresh removes stale declarations`() {
        val files = temporary.newFolder()
        val rime = File(files, "rime").apply { mkdirs() }
        val source = File(rime, "my_romaji.schema.yaml")
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(files)
        whenever(context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE))
            .thenReturn(mock<android.content.SharedPreferences>())
        source.writeText("schema:\n  schema_id: my_romaji\n  name: 自定义日语\n  language: ja\n  input_scheme: romaji\n")
        val installed = SchemaManager.discoverSchemas(context)
        assertEquals(InputLanguage.JAPANESE, InputLanguage.forSchema("my_romaji"))
        assertTrue(com.kingzcheung.xime.service.JapaneseTyping.usesKanaCase("my_romaji", false))
        assertEquals(InputLanguage.JAPANESE, InputUIState(currentSchemaId = "my_romaji",
            schemas = installed.map { it.toSchemaInfo() }).inputProfile.language)
        source.writeText("schema:\n  schema_id: my_romaji\n  name: 未分类配置\n")
        SchemaManager.discoverSchemas(context)
        assertEquals(InputLanguage.UNSPECIFIED, InputLanguage.forSchema("my_romaji"))
        assertFalse(com.kingzcheung.xime.service.JapaneseTyping.usesKanaCase("my_romaji", false))
    }

    @Test fun `custom layouts are layouts of pinyin and merging changes layout kind only`() {
        val custom = CustomKeyboardLayout("custom_pinyin_" + "a".repeat(32), "我的布局",
            CustomKeyboardLayout.BASE.map { it.map(Char::toString) })
        assertEquals(InputScheme.PINYIN, InputProfiles.describe(custom.id).scheme)
        assertEquals(LayoutKind.ALPHABETIC, InputProfiles.resolveLayout(null, custom).kind)
        assertEquals(LayoutKind.MERGED, InputProfiles.resolveLayout(null, custom.merge("q")).kind)
        assertEquals("我的布局", InputProfiles.resolveLayout(null, custom).displayName)
        assertEquals(InputLayout.T9, InputProfiles.resolveLayout("t9", custom))
        assertEquals(InputScheme.PINYIN, InputProfiles.describe(QwjrtkLayout.ID).scheme)
    }

    @Test fun `reverse mapping returns real choices and rejects unavailable combinations`() {
        val entries = listOf("rime_ice", "pinyin_simp", "t9_pinyin", "japanese", "japanese_kana").map(::entry)
        assertEquals(listOf("rime_ice", "pinyin_simp"), InputProfiles.matching(entries,
            InputLanguage.CHINESE, InputScheme.PINYIN, InputLayout.QWERTY.id).map { it.schemaId })
        assertTrue(InputProfiles.matching(entries, InputLanguage.JAPANESE, InputScheme.PINYIN, InputLayout.T9.id).isEmpty())
    }

    @Test fun `scheme selection stays in its language and preserves layout when available`() {
        val entries = listOf("rime_ice", "t9_pinyin", "double_pinyin_flypy", "japanese", "japanese_kana").map(::entry)
        assertEquals("double_pinyin_flypy", InputProfileSelection.changeScheme(entries, entry("t9_pinyin"), InputScheme.DOUBLE_PINYIN)?.schemaId)
        assertNull(InputProfileSelection.changeScheme(entries, entry("japanese"), InputScheme.PINYIN))
        assertEquals("t9_pinyin", InputProfileSelection.preferred(entries, InputLanguage.CHINESE, "t9_pinyin", "rime_ice")?.schemaId)
        assertEquals("rime_ice", InputProfileSelection.preferred(entries, InputLanguage.CHINESE, "deleted", "rime_ice")?.schemaId)
        assertEquals("拼音 · 九键", entry("t9_pinyin").profile.summary)
    }

    @Test fun `layout choices are unique and backend variants stay separate`() {
        val entries = listOf("rime_ice", "pinyin_simp", "t9_pinyin", "pinyin_14jian", "double_pinyin_flypy", "japanese", "handwriting").map(::entry)
        assertEquals(listOf(InputScheme.PINYIN, InputScheme.DOUBLE_PINYIN), InputProfileSelection.schemes(entries, InputLanguage.CHINESE))
        assertEquals(listOf(InputLayout.QWERTY, InputLayout.T9, InputLayout.MERGED14), InputProfileSelection.layouts(entries, entry("rime_ice")))
        assertEquals(listOf("rime_ice", "pinyin_simp"), InputProfileSelection.variants(entries, entry("pinyin_simp")).map { it.schemaId })
        assertEquals("pinyin_simp", InputProfileSelection.changeLayout(entries, entry("pinyin_simp"), InputLayout.QWERTY.id)?.schemaId)
        assertEquals("pinyin_simp", InputProfileSelection.changeScheme(entries, entry("pinyin_simp"), InputScheme.PINYIN)?.schemaId)
    }

    @Test fun `changing layout preserves scheme and language and refuses imaginary combinations`() {
        val entries = listOf("rime_ice", "t9_pinyin", "double_pinyin_flypy", "japanese", "japanese_kana").map(::entry)
        assertEquals("t9_pinyin", InputProfileSelection.changeLayout(entries, entry("rime_ice"), InputLayout.T9.id)?.schemaId)
        assertNull(InputProfileSelection.changeLayout(entries, entry("double_pinyin_flypy"), InputLayout.T9.id))
        assertNull(InputProfileSelection.changeLayout(entries, entry("japanese"), InputLayout.KANA.id))
        assertEquals(listOf(InputLayout.QWERTY), InputProfileSelection.layouts(entries, entry("japanese")))
    }
}
