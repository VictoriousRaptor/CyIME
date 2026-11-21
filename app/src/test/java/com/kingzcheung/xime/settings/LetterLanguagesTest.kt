package com.kingzcheung.xime.settings

import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 西班牙、德、法、意大利、俄、韩语：各是一种独立语言，字母逐字上屏（韩语拼音节），默认不启用，没有语音识别；
 * 补全词表在商店里下载，不进安装包。
 */
class LetterLanguagesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repoFile(rel: String) = listOf(File(rel), File("app/$rel"), File("../$rel")).first { it.isFile }
    private val yaml by lazy { repoFile("src/main/assets/xime.yaml").readText() }

    @After fun reset() {
        LetterLayouts.setForTest(InputLanguage.FRENCH, null)
        KeysConfigHelper.setActiveKeyboardSchema("")
        KeysConfigHelper.clearSchemaBindingsForTest()
    }

    @Test fun `each is its own language typed directly`() {
        for (letters in LetterLanguage.entries) {
            val profile = InputProfiles.describe(letters.schemaId)
            assertEquals(letters.schemaId, letters.language, profile.language)
            assertEquals(letters.schemaId, InputScheme.DIRECT, profile.scheme)
            assertEquals(EngineProfile.Rime(letters.schemaId), profile.engineProfile)
            assertTrue(letters.language.typesLettersDirectly)
            assertEquals(LayoutKind.ALPHABETIC, InputProfiles.resolveLayout(letters.keyboard).kind)
        }
        assertTrue(InputLanguage.ENGLISH.typesLettersDirectly)
        assertFalse(InputLanguage.CHINESE.typesLettersDirectly)
        assertFalse(InputLanguage.JAPANESE.typesLettersDirectly)
        assertEquals("26键", InputProfiles.resolveLayout("qwerty_german").displayName)
        assertEquals("26键", InputProfiles.resolveLayout("qwerty_italian").displayName)
        assertEquals("26键", InputProfiles.resolveLayout("qwerty_vietnamese").displayName)
        assertEquals("26键", InputProfiles.resolveLayout("qwerty_malay").displayName)
        assertEquals("ЙЦУКЕН", InputProfiles.resolveLayout("jcuken_russian").displayName)
    }

    @Test fun `offered off by default with no speech recognizer`() {
        val fresh = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        for (letters in LetterLanguage.entries) {
            assertTrue(letters.language in InputLanguage.supported)
            assertFalse(letters.language in InputLanguage.speech)
            assertEquals(fresh + letters.language, LanguagePreferences.withSchemaLanguage(fresh, letters.schemaId))
            assertFalse(letters.schemaId in CyimeInputDefaults.recommended)
        }
        assertEquals(listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.JAPANESE), InputLanguage.speech)
    }

    @Test fun `schemas are bundled and only mark the language`() {
        for (letters in LetterLanguage.entries) {
            val schema = repoFile("src/main/assets/rime_letters/${letters.schemaId}.schema.yaml").readText()
            assertTrue(schema.contains("schema_id: ${letters.schemaId}"))
            // 没有词典就不需要编译产物；字母不进 Rime。
            assertFalse(schema.contains("translator:"))
            assertFalse(schema.contains("speller"))
        }
        assertTrue(repoFile("build-logic/tasks-rime-manifest.gradle.kts").readText()
            .contains("\"rime_letters\" to file(\"src/main/assets/rime_letters\")"))
        assertTrue("rime_letters" in SchemaManifestManager.bundledAssetRoots)
        // 词表不进安装包：assets 里没有，商店目录里有。
        assertFalse(File(repoFile("src/main/assets/xime.yaml").parentFile, "spanish").exists())
    }

    @Test fun `word lists are market downloads matching the files in tools`() {
        for (letters in LetterLanguage.entries) {
            val id = letters.wordsModel ?: continue
            val model = assertNotNullAndGet(com.kingzcheung.xime.model.BuiltinModelCatalog.models.firstOrNull { it.id == id }, id)
            val file = model.versions.first().files.single()
            assertEquals(letters.wordsFile, file.name)
            val source = repoFile("../tools/wordlists/${file.name}")
            assertEquals(file.name, source.length(), file.sizeBytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(file.name, hash, file.sha256)
            assertTrue(file.name, file.downloadUrl.startsWith("https://github.com/Cyletix/CyIME/releases/download/models/"))
            assertTrue(file.name, file.mirrors.any { it.startsWith(com.kingzcheung.xime.model.DownloadMirrors.CYIME_MODELSCOPE) })
        }
        assertNull(LetterLanguage.KOREAN.wordsModel)
    }

    private fun <T> assertNotNullAndGet(value: T?, message: String): T { assertNotNull(message, value); return value!! }

    @Test fun `keyboards carry their letters and long presses`() {
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(yaml)
        for (letters in LetterLanguage.entries) assertEquals(letters.keyboard, bindings[letters.schemaId])
        val spanish = requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty_spanish"))
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"), spanish[1])
        val russian = requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "jcuken_russian"))
        assertEquals(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), russian.map { it.joinToString("") })
        val korean = requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "dubeolsik_korean"))
        assertEquals(listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ"), korean.map { it.joinToString("") })
        val expected = mapOf(
            "qwerty_spanish" to mapOf("a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "ú", "n" to "ñ"),
            "qwerty_german" to mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß"),
            "qwerty_french" to mapOf("e" to "é", "a" to "à", "c" to "ç", "o" to "ô", "u" to "ù", "i" to "î"),
            "qwerty_italian" to mapOf("a" to "à", "e" to "è", "i" to "ì", "o" to "ò", "u" to "ù"),
            "qwerty_vietnamese" to mapOf("a" to "ă", "e" to "ê", "o" to "ô", "u" to "ư", "d" to "đ"),
            "jcuken_russian" to mapOf("е" to "ё", "ь" to "ъ"),
            "dubeolsik_korean" to mapOf("ㄱ" to "ㄲ", "ㅂ" to "ㅃ", "ㅔ" to "ㅖ"),
        )
        for ((section, accents) in expected) {
            val keys = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(yaml, section))
            val letters = LetterLanguage.entries.single { it.keyboard == section }
            accents.forEach { (key, accent) ->
                assertEquals("$section $key", accent, keys[key]?.longPress?.values?.firstOrNull()?.label)
                // 长按出的字母都是这个语言的字母：选中后当作字母打进词里。
                assertTrue("$section $accent", accent in letters.extraLetters)
            }
            // 字母键的角标不在 yaml 里配：和英文键盘同一规则。
            val rows = requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, section))
            for (key in rows.flatten()) assertNull("$section $key", keys[key]?.swipeUp)
        }
        assertTrue(requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_spanish"))["'"]
            ?.longPress?.values.orEmpty().map { it.label }.containsAll(listOf("¿", "¡")))
        // 越南语 o 长按还有 ơ、a 还有 â；马来语没有特殊字母，逗号键长按里有叠词用的 -。
        val vietnamese = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_vietnamese"))
        assertEquals(listOf("ô", "ơ"), vietnamese["o"]?.longPress?.values.orEmpty().map { it.label })
        assertEquals(listOf("ă", "â"), vietnamese["a"]?.longPress?.values.orEmpty().map { it.label })
        val malay = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_malay"))
        assertEquals(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty_malay")).map { it.joinToString("") })
        assertTrue("abcdefghijklmnopqrstuvwxyz".all { malay[it.toString()]?.longPress == null })
        assertTrue("-" in malay["'"]?.longPress?.values.orEmpty().map { it.label })
    }

    private fun loadKeyboards() {
        val assets = mock<android.content.res.AssetManager>()
        whenever(assets.open("xime.yaml")).thenAnswer { yaml.byteInputStream() }
        whenever(assets.open("xime.custom.yaml")).thenThrow(java.io.IOException("No custom override"))
        val context = mock<android.content.Context>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.filesDir).thenReturn(temp.root)
        whenever(context.getSharedPreferences("kime_settings", android.content.Context.MODE_PRIVATE))
            .thenReturn(mock<android.content.SharedPreferences>())
        KeysConfigHelper.loadConfig(context)
    }

    @Test fun `latin letters carry the english corner symbols and always offer accents`() {
        loadKeyboards()
        for (letters in listOf(LetterLanguage.SPANISH, LetterLanguage.GERMAN, LetterLanguage.FRENCH, LetterLanguage.ITALIAN,
                LetterLanguage.VIETNAMESE, LetterLanguage.MALAY)) {
            KeysConfigHelper.setActiveKeyboardSchema(letters.schemaId)
            for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
                val gesture = KeysConfigHelper.getKeyGesture(key, false)
                assertEquals("${letters.schemaId} $key", QwertySwipeSymbols.up(true).getValue(key), gesture?.swipeUp?.value)
                assertEquals("${letters.schemaId} $key", QwertySwipeSymbols.down(true).getValue(key), gesture?.swipeDown?.value)
            }
            assertEquals(letters.extraLetters, KeysConfigHelper.longPressLetters(isAsciiMode = false))
            // 在这些语言上切到英文：照英文键盘的规则。
            assertEquals("", KeysConfigHelper.longPressLetters(isAsciiMode = true))
        }
        KeysConfigHelper.setActiveKeyboardSchema(LetterLanguage.SPANISH.schemaId)
        assertEquals("ñ", KeysConfigHelper.getKeyRows(false)[1].last())
        // ñ 在 26 键第二排之外，不带角标。
        assertNull(KeysConfigHelper.getKeyGesture("ñ", false)?.swipeUp)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        assertEquals("", KeysConfigHelper.longPressLetters(isAsciiMode = false))
    }

    @Test fun `russian and korean corners follow the position on the 26 keys`() {
        loadKeyboards()
        for (letters in listOf(LetterLanguage.RUSSIAN, LetterLanguage.KOREAN)) {
            KeysConfigHelper.setActiveKeyboardSchema(letters.schemaId)
            val rows = KeysConfigHelper.getKeyRows(false)
            rows.forEachIndexed { row, keys ->
                keys.forEachIndexed { column, key ->
                    val owner = QwertySwipeSymbols.rows.getOrNull(row)?.getOrNull(column)?.toString()
                    val gesture = KeysConfigHelper.getKeyGesture(key, false)
                    assertEquals("$key", owner?.let { QwertySwipeSymbols.up(true).getValue(it) }, gesture?.swipeUp?.value)
                    assertEquals("$key", owner?.let { QwertySwipeSymbols.down(true).getValue(it) }, gesture?.swipeDown?.value)
                }
            }
        }
        // 第一排 й…з 上滑是 1…0，第 11 个 х 没有。
        KeysConfigHelper.setActiveKeyboardSchema(LetterLanguage.RUSSIAN.schemaId)
        assertEquals("1", KeysConfigHelper.getKeyGesture("й", false)?.swipeUp?.value)
        assertNull(KeysConfigHelper.getKeyGesture("х", false)?.swipeUp)
    }

    /** 法语 AZERTY（用户 11-16）：法语的第二个布局，默认不用；选了只换按键排列，长按、语言、方案都不变，角标跟着位置走。 */
    @Test fun `french offers azerty as a second layout`() {
        loadKeyboards()
        // 不绑方案：法语方案照旧绑 qwerty_french，AZERTY 只在选了时用。
        assertFalse(LetterLayouts.AZERTY_FRENCH in KeysConfigHelper.parseSchemaBindingsYamlText(yaml).values)
        val rows = requireNotNull(KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, LetterLayouts.AZERTY_FRENCH))
        assertEquals(listOf("azertyuiop", "qsdfghjklm", "wxcvbn"), rows.map { it.joinToString("") })
        // 按键、长按（é è ê ë、à â æ、ç、ù û ü、î ï、ô œ、ÿ）和逗号键都和 QWERTY 法语键盘一样。
        assertEquals(KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_french"),
            KeysConfigHelper.parseKeyboardYamlSection(yaml, LetterLayouts.AZERTY_FRENCH))
        assertEquals("AZERTY", InputProfiles.resolveLayout(LetterLayouts.AZERTY_FRENCH).displayName)

        val french = SchemaInfo("french", "法语", "", "", "")
        val entries = listOf(SchemaInfo("rime_ice", "雾凇拼音", "", "", ""), french, SchemaInfo("italian", "意大利语", "", "", ""))
        // 默认还是原来的 26 键。
        assertEquals("qwerty_french", InputProfiles.current("french").layout.id)
        assertEquals(listOf("qwerty_french", LetterLayouts.AZERTY_FRENCH),
            InputProfileSelection.layoutsOf(french, entries).map { it.id })
        assertEquals(listOf("26键", "AZERTY"), InputProfileSelection.languageLayouts(entries, french).map { it.displayName })
        // 点 AZERTY 保持法语方案，只换按键排列；别的语言没有这个布局。
        assertEquals(InputProfileSelection.LayoutSwitch(french, true),
            InputProfileSelection.switchLayout(entries, french, LetterLayouts.AZERTY_FRENCH))
        assertEquals(listOf("qwerty_italian"), InputProfileSelection.layoutsOf(entries[2], entries).map { it.id })

        LetterLayouts.setForTest(InputLanguage.FRENCH, LetterLayouts.AZERTY_FRENCH)
        val profile = InputProfiles.current("french")
        assertEquals(InputLanguage.FRENCH, profile.language)
        assertEquals(InputScheme.DIRECT, profile.scheme)
        assertEquals(EngineProfile.Rime("french"), profile.engineProfile)
        assertEquals(LetterLayouts.AZERTY_FRENCH, profile.layout.id)
        assertEquals(LayoutKind.ALPHABETIC, profile.layout.kind)
        KeysConfigHelper.setActiveKeyboardSchema("french")
        assertEquals(rows, KeysConfigHelper.getKeyRows(false))
        assertEquals(listOf("é", "è", "ê", "ë"), KeysConfigHelper.getKeyGesture("e", false)?.longPress?.values?.map { it.label })
        assertEquals(listOf("ç"), KeysConfigHelper.getKeyGesture("c", false)?.longPress?.values?.map { it.label })
        assertEquals(LetterLanguage.FRENCH.extraLetters, KeysConfigHelper.longPressLetters(isAsciiMode = false))
        // 角标跟着位置：a 在 q 那一格、z 在 w 那一格、w 在 z 那一格；m 在 26 键之外，留 m 自己的。
        val up = QwertySwipeSymbols.up(true)
        for ((key, cell) in listOf("a" to "q", "z" to "w", "q" to "a", "w" to "z", "m" to "m", "n" to "n"))
            assertEquals(key, up.getValue(cell), KeysConfigHelper.getKeyGesture(key, false)?.swipeUp?.value)
        // 在法语上切到英文：英文键盘照旧是 QWERTY。
        assertEquals(InputLayout.QWERTY.id, InputProfiles.current("french", asciiMode = true).layout.id)
        // 别的语言不受影响。
        assertEquals("qwerty_italian", InputProfiles.current("italian").layout.id)

        // 选回 26 键：用回原来的键盘。
        LetterLayouts.setForTest(InputLanguage.FRENCH, null)
        assertEquals("qwerty_french", InputProfiles.current("french").layout.id)
        KeysConfigHelper.setActiveKeyboardSchema("french")
        assertEquals("qwertyuiop", KeysConfigHelper.getKeyRows(false)[0].joinToString(""))
    }

    /** 在布局栏点 AZERTY / 26 键：存进 letter_layout_fr（新键，旧设置不动）；点回 26 键删掉这一项。 */
    @Test fun `picking azerty is stored per language and picking 26 keys clears it`() {
        loadKeyboards()
        val stored = mutableMapOf<String, String>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(editor.putString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
            stored[it.getArgument(0)] = it.getArgument(1); editor }
        whenever(editor.remove(org.mockito.kotlin.any())).thenAnswer { stored.remove(it.getArgument<String>(0)); editor }
        val prefs = mock<android.content.SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)
        val context = mock<android.content.Context>()
        whenever(context.getSharedPreferences("kime_settings", android.content.Context.MODE_PRIVATE)).thenReturn(prefs)
        val french = SchemaInfo("french", "法语", "", "", "")

        LetterLayouts.selectFor(context, french, LetterLayouts.AZERTY_FRENCH)
        assertEquals(mapOf("letter_layout_fr" to LetterLayouts.AZERTY_FRENCH), stored)
        assertEquals(LetterLayouts.AZERTY_FRENCH, LetterLayouts.chosenSection(InputLanguage.FRENCH))
        // 法语不借中文的字母排列（Colemak 等），chosen 仍是 null：英文、日语的排列不受影响。
        assertNull(LetterLayouts.chosen(InputLanguage.FRENCH))
        assertNull(LetterLayouts.chosenSection(InputLanguage.ENGLISH))

        LetterLayouts.selectFor(context, SchemaInfo("french", "法语", "", "", ""), "qwerty_french")
        assertTrue(stored.isEmpty())
        assertNull(LetterLayouts.chosenSection(InputLanguage.FRENCH))
        assertEquals("qwerty_french", InputProfiles.current("french").layout.id)
    }
}
