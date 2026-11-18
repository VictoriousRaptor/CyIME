package com.kingzcheung.xime.settings

import com.kingzcheung.xime.rime.flypySyllable
import org.junit.Assert.*
import org.junit.Test

class DoublePinyinSchemeTest {
    /** Rime 拼写运算里用到的三种规则，按顺序作用在一个字符串上（与 librime 的 xform/xlit/erase 同义）。 */
    private fun apply(rules: List<String>, input: String): String {
        var text = input
        for (rule in rules) {
            val parts = rule.split('/')
            when (parts[0]) {
                "erase" -> if (Regex(parts[1]).matches(text)) return ""
                "xform" -> text = Regex(parts[1]).replace(text, parts[2])
                "xlit" -> text = text.map { c -> parts[1].indexOf(c).let { if (it >= 0) parts[2][it] else c } }.joinToString("")
                else -> fail(rule)
            }
        }
        return text
    }

    /** 自定义方案的例子：小鹤双拼把 sh 改到 E 键。 */
    private val custom = DoublePinyinScheme.fresh(DoublePinyinScheme.FLYPY, "我的双拼").let {
        it.copy(initials = it.initials + ("sh" to 'e'))
    }

    /** 键位表上的编码；ju、qu、xu、yu 另外也能按 ü 的键打（首道 jv 出居），ü 和 u 同键时只有一个。 */
    private fun codes(scheme: DoublePinyinScheme, syllable: String): Set<String> {
        val code = scheme.code(syllable)!!
        val umlaut = scheme.finals.getValue("v")
        return if (syllable.matches(Regex("[jqxy]u")) && umlaut != scheme.finals["u"]) setOf(code, "${syllable[0]}$umlaut") else setOf(code)
    }

    @Test fun flypyTableMatchesTheShippedFlypyRules() {
        for (syllable in DoublePinyinScheme.SYLLABLES)
            assertEquals(syllable, flypySyllable(syllable), DoublePinyinScheme.FLYPY.code(syllable))
    }

    @Test fun shoudaoFollowsItsKeyMap() {
        val s = DoublePinyinScheme.SHOUDAO
        assertEquals(emptyList<String>(), s.problems())
        // 紫框：zh→V、ch→I、sh→E；零声母 12 组独立双键。
        assertEquals(listOf("vi", "ii", "ei"), listOf("zhi", "chi", "shi").map { s.code(it) })
        assertEquals(listOf("aa", "ai", "an", "ay", "ao", "ue", "ui", "en", "uf", "er", "oo", "ou"),
            listOf("a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ou").map { s.code(it) })
        // 口诀字所在的键：美尊红票样，娃（ua）向团；恳求来年，搜到风怪。
        assertEquals(listOf("mm", "zz", "hh", "pp", "yy", "hw", "xx", "tt", "kk", "qq", "ll", "nn", "ss", "dd", "ff", "gg"),
            listOf("mei", "zun", "hong", "piao", "yang", "hua", "xiang", "tuan", "ken", "qiu", "lai", "nian", "sou", "dao", "feng", "guai")
                .map { s.code(it) })
        // ue（jue）在 L，üe（lüe）在 B，ü 和 ui 在 V。
        assertEquals(listOf("jl", "lb", "lv", "dv", "ju", "xh", "ex"),
            listOf("jue", "lve", "lv", "dui", "ju", "xiong", "shuang").map { s.code(it) })
        assertEquals(mapOf("dk" to listOf("dia", "den"), "lo" to listOf("luo", "lo")), s.collisions())
        assertEquals("ex'pc", s.preview())
    }

    @Test fun shoudaoPreeditAndMixedEnglish() {
        val s = DoublePinyinScheme.SHOUDAO
        val rules = s.preeditFormat()
        assertEquals("zhong guo", apply(rules, "vhgo"))
        assertEquals("eng e ang ei er", apply(rules, "ufueayuier"))
        assertEquals("lve jue", apply(rules, "lbjl"))
        assertEquals("zhong sh", apply(rules, "vhe"))
        val table = s.cnEnTable("#@/db_name\tcn_en_flypy.txt\nX光\tXgl\nB站\tbvj\n4S店\tsiSdm\n").lines()
        assertEquals(listOf("#@/db_name\tcn_en_double_pinyin_shoudao.txt", "X光\tXgx", "B站\tbvj", "4S店\tsiSdn"), table.take(4))
    }

    @Test fun algebraTurnsEverySyllableIntoItsCode() {
        for (scheme in DoublePinyinScheme.BUILTINS + custom) {
            val rules = scheme.algebra()
            for (syllable in DoublePinyinScheme.SYLLABLES) assertEquals(syllable, codes(scheme, syllable), spellings(rules, syllable))
        }
    }

    /**
     * 反馈 11-09：首道双拼 ü 在 V 上，jv 打不出居。ju、qu、xu、yu 的 u 读 ü，也能按 ü 的键打，与 Rime 官方双拼方案和
     * 内置小鹤双拼相同；lü、nü 和 lu、nu 不受影响，屏上编码显示 ju。
     */
    @Test fun juCanAlsoBeTypedOnTheUmlautKey() {
        val s = DoublePinyinScheme.SHOUDAO
        val rules = s.algebra()
        for (syllable in listOf("ju", "qu", "xu", "yu")) assertEquals(setOf(syllable, "${syllable[0]}v"), spellings(rules, syllable))
        for (syllable in listOf("lv", "nv", "lu", "nu", "jue", "juan", "jun", "dui")) assertEquals(syllable, setOf(s.code(syllable)), spellings(rules, syllable))
        assertEquals("ju qu xu yu lv dui", apply(s.preeditFormat(), "jvqvxvyvlvdv"))
        assertEquals("ju", apply(s.preeditFormat(), "ju"))
        // 自然码、智能ABC、拼音加加同样是 jv；微软双拼 ü 在 Y 上，是 jy。
        for (scheme in listOf(DoublePinyinScheme.ZIRANMA, DoublePinyinScheme.ZNABC, DoublePinyinScheme.JIAJIA))
            assertEquals(scheme.name, setOf("ju", "jv"), spellings(scheme.algebra(), "ju"))
        assertEquals(setOf("ju", "jy"), spellings(DoublePinyinScheme.MICROSOFT.algebra(), "ju"))
        assertEquals("ju xu", apply(DoublePinyinScheme.MICROSOFT.preeditFormat(), "jyxy"))
        // 自定义方案：ü 和 u 同键不多派生；j/q/x 加 ü 键已是别的字（ü 放到 ia 的 X 上，jx 是加）时只给 y 加。
        assertEquals(setOf("ju"), spellings(custom.copy(finals = custom.finals + ("v" to 'u')).algebra(), "ju"))
        val onX = custom.copy(finals = custom.finals + ("v" to 'x'))
        assertEquals(setOf("ju"), spellings(onX.algebra(), "ju"))
        assertEquals(setOf("yu", "yx"), spellings(onX.algebra(), "yu"))
        assertEquals("jia yu", apply(onX.preeditFormat(), "jxyx"))
    }

    @Test fun preeditShowsPinyin() {
        val rules = custom.preeditFormat()
        assertEquals("zhong guo", apply(rules, "vsgo"))
        assertEquals("zhong guo", apply(rules, "vs go"))
        assertEquals("shuang'pin", apply(rules, "el'pb"))
        assertEquals("eng e ang ei", apply(rules, "egeeahei"))
        assertEquals("lve jue", apply(rules, "ltjt"))
        // 最后一个只打了声母键时显示声母。
        assertEquals("zhong sh", apply(rules, "vse"))
        assertEquals("Txu", apply(rules, "Txu"))
    }

    @Test fun invalidKeysAreReported() {
        assertTrue(custom.valid())
        assertFalse(custom.copy(initials = custom.initials + ("zh" to 'b')).valid())
        assertFalse(custom.copy(initials = custom.initials + ("zh" to 'i')).valid())
        assertFalse(custom.copy(finals = custom.finals - "iang").valid())
        assertFalse(custom.copy(zero = custom.zero + ("ang" to "a")).valid())
        assertFalse(custom.copy(name = " ").valid())
        assertFalse(custom.copy(id = "double_pinyin_flypy").valid())
    }

    @Test fun schemaReplacesOnlyTheEncoding() {
        val base = """
            # Rime schema
            schema:
              schema_id: double_pinyin_flypy
              name: 小鹤双拼
              version: "1"
              author:
                - Dvel
              description: |
                雾凇拼音 - 小鹤双拼
              dependencies:
                - melt_eng

            translator:
              dictionary: rime_ice
              prism: double_pinyin_flypy    # 注释
              preedit_format:               # 注释
                - xform/(^|[ '])aa/${'$'}1a/
                - xform/(\w)g/${'$'}1eng/
              # dictionary_exclude: []

            cn_en:
              user_dict: en_dicts/cn_en_flypy

            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
              algebra:
                - erase/^xx${'$'}/
                - xform/iu${'$'}/Ⓠ/
                # - abbrev/^(.).+${'$'}/${'$'}1/
            """.trimIndent() + "\n"
        val text = custom.schema(base)
        assertTrue(text.startsWith(DoublePinyinScheme.GENERATED_MARK + custom.id + "\n"))
        assertTrue(text.contains("  schema_id: ${custom.id}\n  language: zh\n  input_scheme: double_pinyin\n"))
        assertTrue(text.contains("  name: \"我的双拼\"\n"))
        assertTrue(text.contains("  author:\n    - Dvel\n"))
        assertTrue(text.contains("  prism: ${custom.id}\n"))
        assertTrue(text.contains("  user_dict: en_dicts/cn_en_${custom.id}\n"))
        assertTrue(text.contains("  dictionary: rime_ice\n"))
        assertTrue(text.contains("  # dictionary_exclude: []\n"))
        assertFalse(text.contains("xform/iu${'$'}/Ⓠ/") || text.contains("# - abbrev") || text.contains("(\\w)g"))
        val algebra = text.substringAfter("  algebra:\n").lines().takeWhile { it.startsWith("    - ") }
        assertEquals(custom.algebra().size + custom.separatorRules().size, algebra.size)
        assertEquals("    - \"abbrev/^(.).+${'$'}/${'$'}1'/\"", algebra.last())
        assertEquals("    - \"xform/(?<=[A-Z])\\\\s(?=[A-Z])//\"", text.substringAfter("  preedit_format:\n")
            .lines().takeWhile { it.startsWith("    - ") }.last())
    }

    /** 带 derive 的拼写运算：derive（和 abbrev）保留原拼写并加上改写后的拼写；没匹配上的不算新拼写。 */
    private fun spellings(rules: List<String>, input: String): Set<String> = rules.fold(setOf(input)) { set, rule ->
        val parts = rule.split('/')
        if (parts[0] == "derive" || parts[0] == "abbrev")
            set + set.filter { Regex(parts[1]).containsMatchIn(it) }.map { Regex(parts[1]).replace(it, parts[2]) }
        else set.map { apply(listOf(rule), it) }.filter { it.isNotEmpty() }.toSet()
    }

    /** 模糊音（[SpellingPatches.Fuzzy]）写在双拼拼写规则前面：先按读音派生，再换成本方案的双拼编码。 */
    @Test fun fuzzyPinyinReachesEveryDoublePinyinScheme() {
        for (scheme in DoublePinyinScheme.BUILTINS + custom) {
            fun typed(fuzzy: SpellingPatches.Fuzzy, syllable: String) = spellings(fuzzy.rules + scheme.algebra(), syllable)
            fun code(syllable: String) = scheme.code(syllable)!!
            assertTrue(scheme.id, code("xin") in typed(SpellingPatches.Fuzzy.IN, "xing"))
            assertTrue(scheme.id, code("xing") in typed(SpellingPatches.Fuzzy.IN, "xin"))
            assertTrue(scheme.id, code("yin") in typed(SpellingPatches.Fuzzy.IN, "ying"))
            assertTrue(scheme.id, code("zi") in typed(SpellingPatches.Fuzzy.Z, "zhi"))
            assertTrue(scheme.id, code("shi") in typed(SpellingPatches.Fuzzy.S, "si"))
            assertTrue(scheme.id, code("lan") in typed(SpellingPatches.Fuzzy.NL, "nan"))
            assertTrue(scheme.id, code("fan") in typed(SpellingPatches.Fuzzy.AN, "fang"))
            assertFalse(scheme.id, code("xian") in typed(SpellingPatches.Fuzzy.AN, "xiang"))
            // 用户 10-31：小鹤 bkgr（bing guan）要出宾馆（bin guan）；en/eng 两个方向同样。
            assertTrue(scheme.id, code("bing") in typed(SpellingPatches.Fuzzy.IN, "bin"))
            assertTrue(scheme.id, code("chen") in typed(SpellingPatches.Fuzzy.EN, "cheng"))
            assertTrue(scheme.id, code("cheng") in typed(SpellingPatches.Fuzzy.EN, "chen"))
            // 不开模糊音：每个音节只有自己的编码。
            assertEquals(scheme.id, setOf(code("xing")), spellings(scheme.algebra(), "xing"))
            // 11 组全开：每个音节派生出的拼写仍然都是两键编码，不多出没换成双拼的长拼写。
            val all = SpellingPatches.Fuzzy.entries.flatMap { it.rules } + scheme.algebra()
            for (syllable in DoublePinyinScheme.SYLLABLES) {
                val odd = spellings(all, syllable).filterNot { it.matches(Regex("[a-z;]{2}")) }
                assertTrue("${scheme.id} $syllable $odd", odd.isEmpty())
            }
        }
    }

    /** 随应用内置的 26 键小鹤双拼（雾凇 double_pinyin_flypy.schema.yaml）的拼写规则原文，不是由键位表生成的那份。 */
    private val shippedFlypy = """
        erase/^xx$/
        derive/^([jqxy])u$/$1v/
        derive/^([aoe])([ioun])$/$1$1$2/
        xform/^([aoe])(ng)?$/$1$1$2/
        xform/iu$/Ⓠ/
        xform/(.)ei$/$1Ⓦ/
        xform/uan$/Ⓡ/
        xform/[uv]e$/Ⓣ/
        xform/un$/Ⓨ/
        xform/^sh/Ⓤ/
        xform/^ch/Ⓘ/
        xform/^zh/Ⓥ/
        xform/uo$/Ⓞ/
        xform/ie$/Ⓟ/
        xform/(.)i?ong$/$1Ⓢ/
        xform/ing$|uai$/Ⓚ/
        xform/(.)ai$/$1Ⓓ/
        xform/(.)en$/$1Ⓕ/
        xform/(.)eng$/$1Ⓖ/
        xform/[iu]ang$/Ⓛ/
        xform/(.)ang$/$1Ⓗ/
        xform/ian$/Ⓜ/
        xform/(.)an$/$1Ⓙ/
        xform/(.)ou$/$1Ⓩ/
        xform/[iu]a$/Ⓧ/
        xform/iao$/Ⓝ/
        xform/(.)ao$/$1Ⓒ/
        xform/ui$/Ⓥ/
        xform/in$/Ⓑ/
        xlit/ⓆⓌⓇⓉⓎⓊⒾⓄⓅⓈⒹⒻⒼⒽⒿⓀⓁⓏⓍⒸⓋⒷⓃⓂ/qwrtyuiopsdfghjklzxcvbnm/
    """.trimIndent().lines()

    /** 用户 10-31：小鹤双拼打 bkgr 出不来宾馆。打开 in/ing 模糊音（并打开「模糊音也用于双拼」）后，宾 bin 也能按 bk 打。 */
    @Test fun shippedFlypyTypesBinguanAsBkgrWithInIngFuzzy() {
        assertEquals(setOf("bb"), spellings(shippedFlypy, "bin"))
        assertEquals(setOf("gr"), spellings(shippedFlypy, "guan"))
        assertEquals(setOf("bb", "bk"), spellings(SpellingPatches.Fuzzy.IN.rules + shippedFlypy, "bin"))
        // 11 组全开时同样，每个音节仍然只派生出两键编码。
        val all = SpellingPatches.Fuzzy.entries.flatMap { it.rules } + shippedFlypy
        assertTrue("bk" in spellings(all, "bin") && "gr" in spellings(all, "guan"))
        assertTrue(DoublePinyinScheme.FLYPY.code("cheng") in spellings(all, "chen"))
        for (syllable in DoublePinyinScheme.SYLLABLES) {
            val odd = spellings(all, syllable).filterNot { it.matches(Regex("[a-z]{2}")) }
            assertTrue("$syllable $odd", odd.isEmpty())
        }
        // 键位表上的编码内置规则都能打（内置的另外多出 jv、ew 这类写法）。
        for (syllable in DoublePinyinScheme.SYLLABLES)
            assertTrue(syllable, DoublePinyinScheme.FLYPY.code(syllable) in spellings(shippedFlypy, syllable))
    }

    /**
     * 反馈 10-31（1.15.24）：双拼开了 Shift 键分词，a've 出的是 ave 不是啊这。双拼两键一字，单个键加 ' 以前没有拼写对得上。
     * a' o' e' 是零声母字本身，其他单键加 ' 是这个键开头的字；不打 ' 时每个音节仍只有自己的两键编码。
     */
    @Test fun separatorAfterOneKeyTypesThatSyllable() {
        val schemes = DoublePinyinScheme.BUILTINS + custom
        for (scheme in schemes) {
            val rules = scheme.algebra() + scheme.separatorRules()
            assertTrue(scheme.id, "a'" in spellings(rules, "a") && "e'" in spellings(rules, "e") && "o'" in spellings(rules, "o"))
            for (syllable in DoublePinyinScheme.SYLLABLES) {
                val code = scheme.code(syllable)!!
                val typed = spellings(rules, syllable)
                assertEquals("${scheme.id} $syllable", codes(scheme, syllable), typed.filterNot { '\'' in it }.toSet())
                assertTrue("${scheme.id} $syllable $typed", "${code[0]}'" in typed)
            }
        }
        val flypy = DoublePinyinScheme.FLYPY
        assertEquals(listOf("derive/^aa$/a'/", "derive/^oo$/o'/", "derive/^ee$/e'/", "abbrev/^(.).+$/${'$'}1'/"), flypy.separatorRules())
        assertEquals(setOf("aa", "a'"), spellings(shippedFlypy + flypy.separatorRules(), "a"))
        assertEquals(setOf("ve", "v'"), spellings(shippedFlypy + flypy.separatorRules(), "zhe"))
        assertEquals("derive/^oa$/a'/", DoublePinyinScheme.MICROSOFT.separatorRules().first())
        // 内置小鹤双拼在打包时加同样几条（雾凇原文的规则不在 DoublePinyinScheme 里）。
        val gradle = java.io.File("build-logic/tasks-chinese.gradle.kts").readText().replace("\\${'$'}", "${'$'}")
        for (rule in flypy.separatorRules()) assertTrue(rule, gradle.contains("\"$rule\""))
    }

    @Test fun t9VersionTypesEachSyllableOnTwoDigitKeys() {
        // 微软双拼 ing 的分号和 L 同在 5 键。
        val digits = mapOf("abc" to '2', "def" to '3', "ghi" to '4', "jkl;" to '5', "mno" to '6', "pqrs" to '7', "tuv" to '8', "wxyz" to '9')
        fun t9(code: String) = code.map { c -> digits.entries.first { c in it.key }.value }.joinToString("")
        for (scheme in DoublePinyinScheme.BUILTINS + custom) {
            val id = DoublePinyinScheme.t9Id(scheme.id)
            val text = scheme.t9Schema()
            // 点选拼音栏的读音会把双拼键送进输入，微软双拼的 x; 也要认。
            assertEquals(scheme.id, scheme.usesSemicolon, text.contains("  alphabet: zyxwvutsrqponmlkjihgfedcba9876543210;\n"))
            val laiLe = t9(scheme.code("lai")!! + scheme.code("le")!!)
            assertTrue("来了 follows this scheme's own two-key codes", "exact: [\"$laiLe 来了\"]" in text)
            assertTrue(text.startsWith(DoublePinyinScheme.GENERATED_MARK + id + "\n"))
            assertTrue(text.contains("  schema_id: $id\n  language: zh\n  input_scheme: double_pinyin\n"))
            assertTrue(text.contains("  prism: $id\n"))
            assertTrue(text.contains("  left_panel_mode: pinyin\n  double_pinyin: true\n  isDisplayOriginalPreedit: false\n"))
            assertTrue(text.contains("    - t9_processor\n") && text.contains("    - t9_filter\n"))
            assertFalse(text.contains("joint_decoder") || text.contains("cylm"))
            val rules = text.substringAfter("  algebra:\n").lines().takeWhile { it.startsWith("    - ") }
                .map { it.removePrefix("    - \"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
            assertEquals(scheme.algebra().size + 8, rules.size)
            for (syllable in DoublePinyinScheme.SYLLABLES) {
                val code = scheme.code(syllable)!!
                val result = spellings(rules, syllable)
                assertTrue("$syllable $result", code in result && t9(code) in result)
                assertTrue("$syllable $result", result.all { it.length == 2 })
            }
        }
        assertEquals("87", t9(DoublePinyinScheme.FLYPY.code("zhong")!!))
        assertEquals("95", t9(DoublePinyinScheme.MICROSOFT.code("xing")!!))
        assertEquals("小鹤双拼九键", DoublePinyinScheme.FLYPY.t9Schema().substringAfter("  name: \"").substringBefore('"'))
    }

    @Test fun mixedEnglishTableFollowsTheScheme() {
        val flypy = "# Rime table\n#@/db_name\tcn_en_flypy.txt\n# 此行之后不能写注释\nX光\tXgl\n4S店\tsiSdm\nJ.K.罗琳\tJKlolb\n上网\tuhwh\n坏\tz\n"
        val table = custom.cnEnTable(flypy).lines()
        assertEquals("#@/db_name\tcn_en_${custom.id}.txt", table[1])
        assertEquals(listOf("X光\tXgl", "4S店\tsiSdm", "J.K.罗琳\tJKlolb", "上网\tehwh"), table.drop(3).take(4))
        // 拆不开的行（编码与汉字数对不上）跳过。
        assertFalse(table.any { it.startsWith("坏") })
        assertEquals(8, table.size)
    }

    /** Rime 官方 rime-double-pinyin 的拼写运算（自然码 double_pinyin、智能ABC double_pinyin_abc、拼音加加 double_pinyin_pyjj）。 */
    private val upstream = mapOf(
        DoublePinyinScheme.ZIRANMA to """
            erase/^xx$/
            derive/^([jqxy])u$/$1v/
            derive/^([aoe])([ioun])$/$1$1$2/
            xform/^([aoe])(ng)?$/$1$1$2/
            xform/iu$/Q/
            xform/[iu]a$/W/
            xform/[uv]an$/R/
            xform/[uv]e$/T/
            xform/ing$|uai$/Y/
            xform/^sh/U/
            xform/^ch/I/
            xform/^zh/V/
            xform/uo$/O/
            xform/[uv]n$/P/
            xform/i?ong$/S/
            xform/[iu]ang$/D/
            xform/(.)en$/$1F/
            xform/(.)eng$/$1G/
            xform/(.)ang$/$1H/
            xform/ian$/M/
            xform/(.)an$/$1J/
            xform/iao$/C/
            xform/(.)ao$/$1K/
            xform/(.)ai$/$1L/
            xform/(.)ei$/$1Z/
            xform/ie$/X/
            xform/ui$/V/
            xform/(.)ou$/$1B/
            xform/in$/N/
            xlit/QWRTYUIOPSDFGHMJCKLZXVBN/qwrtyuiopsdfghmjcklzxvbn/
        """,
        DoublePinyinScheme.ZNABC to """
            erase/^xx$/
            derive/^([jqxy])u$/$1v/
            xform/^zh/A/
            xform/^ch/E/
            xform/^sh/V/
            xform/^([aoe].*)$/O$1/
            xform/ei$/Q/
            xform/ian$/W/
            xform/er$|iu$/R/
            xform/[iu]ang$/T/
            xform/ing$/Y/
            xform/uo$/O/
            xform/uan$/P/
            xform/i?ong$/S/
            xform/[iu]a$/D/
            xform/en$/F/
            xform/eng$/G/
            xform/ang$/H/
            xform/an$/J/
            xform/iao$/Z/
            xform/ao$/K/
            xform/in$|uai$/C/
            xform/ai$/L/
            xform/ie$/X/
            xform/ou$/B/
            xform/un$/N/
            xform/[uv]e$|ui$/M/
            xlit/QWERTYOPASDFGHJKLZXCVBNM/qwertyopasdfghjklzxcvbnm/
        """,
        DoublePinyinScheme.JIAJIA to """
            erase/^xx$/
            derive/^([jqxy])u$/$1v/
            derive/^([aoe].*)$/o$1/
            xform/^([ae])(.*)$/$1$1$2/
            xform/iu$/N/
            xform/[iu]a$/B/
            xform/er$|ing$/Q/
            xform/[uv]an$/C/
            xform/[uv]e$|uai$/X/
            xform/^sh/I/
            xform/^ch/U/
            xform/^zh/V/
            xform/uo$/O/
            xform/[uv]n$/Z/
            xform/i?ong$/Y/
            xform/[iu]ang$/H/
            xform/(.)en$/$1R/
            xform/(.)eng$/$1T/
            xform/(.)ang$/$1G/
            xform/ian$/J/
            xform/(.)an$/$1F/
            xform/iao$/K/
            xform/(.)ao$/$1D/
            xform/(.)ai$/$1S/
            xform/(.)ei$/$1W/
            xform/ie$/M/
            xform/ui$/V/
            xform/(.)ou$/$1P/
            xform/in$/L/
            xlit/QWRTYUIOPSDFGHMJCKLZXVBN/qwrtyuiopsdfghmjcklzxvbn/
        """,
        DoublePinyinScheme.MICROSOFT to """
            erase/^xx$/
            derive/^([jqxy])u$/$1v/
            derive/^([aoe].*)$/o$1/
            xform/^([ae])(.*)$/$1$1$2/
            xform/iu$/Q/
            xform/[iu]a$/W/
            xform/er$|[uv]an$/R/
            xform/[uv]e$/T/
            xform/v$|uai$/Y/
            xform/^sh/U/
            xform/^ch/I/
            xform/^zh/V/
            xform/uo$/O/
            xform/[uv]n$/P/
            xform/i?ong$/S/
            xform/[iu]ang$/D/
            xform/(.)en$/$1F/
            xform/(.)eng$/$1G/
            xform/(.)ang$/$1H/
            xform/ian$/M/
            xform/(.)an$/$1J/
            xform/iao$/C/
            xform/(.)ao$/$1K/
            xform/(.)ai$/$1L/
            xform/(.)ei$/$1Z/
            xform/ie$/X/
            xform/ui$/V/
            derive/T$/V/
            xform/(.)ou$/$1B/
            xform/in$/N/
            xform/ing$/;/
            xlit/QWRTYUIOPSDFGHMJCKLZXVBN/qwrtyuiopsdfghmjcklzxvbn/
        """,
    ).mapValues { (_, rules) -> rules.trimIndent().lines().filter { it.isNotBlank() } }

    @Test fun builtinsTypeWhatTheOfficialRimeSchemesType() {
        for ((scheme, rules) in upstream) {
            assertEquals(scheme.name, emptyList<String>(), scheme.problems())
            for (syllable in DoublePinyinScheme.SYLLABLES) {
                val official = spellings(rules, syllable)
                assertTrue("${scheme.name} $syllable ${scheme.code(syllable)} $official", scheme.code(syllable) in official)
            }
            // 官方方案的 ju 也能按 ü 的键打（自然码 jv、微软 jy），生成的同样。
            for (syllable in listOf("ju", "qu", "xu", "yu"))
                assertEquals("${scheme.name} $syllable", spellings(rules, syllable), spellings(scheme.algebra(), syllable))
        }
        val z = DoublePinyinScheme.ZIRANMA
        assertEquals(listOf("vs", "ih", "ud", "sr", "jp", "ly", "xy", "lz", "lv", "lt"),
            listOf("zhong", "chang", "shuang", "suan", "jun", "ling", "xing", "lei", "lv", "lve").map { z.code(it) })
        assertEquals("ud'pn", z.preview())
        assertEquals(listOf("oa", "ol", "or"), listOf("a", "ai", "er").map { DoublePinyinScheme.ZNABC.code(it) })
        assertEquals(listOf("as", "eq", "op"), listOf("ai", "er", "ou").map { DoublePinyinScheme.JIAJIA.code(it) })
        val m = DoublePinyinScheme.MICROSOFT
        assertTrue(m.usesSemicolon)
        assertFalse(DoublePinyinScheme.BUILTINS.filter { it != m }.any { it.usesSemicolon })
        assertEquals(listOf("l;", "x;", "ud", "vs", "ly", "lt", "iy", "ol", "or", "ob"),
            listOf("ling", "xing", "shuang", "zhong", "lv", "lve", "chuai", "ai", "er", "ou").map { m.code(it) })
        assertEquals("ud'pn", m.preview())
        assertEquals(setOf("ing"), m.keyLabels().getValue(';').second.toSet())
    }

    @Test fun microsoftPreeditAndSchemaTakeTheSemicolon() {
        val m = DoublePinyinScheme.MICROSOFT
        val rules = m.preeditFormat()
        assertEquals("ling xing", apply(rules, "l;x;"))
        assertEquals("shuang'pin", apply(rules, "ud'pn"))
        assertEquals("ai er ou", apply(rules, "ol or ob"))
        assertEquals("zhong sh", apply(rules, "vsu"))
        // 分号进字母表（引号里的也认），不进 initials；其他方案的字母表不变。
        val base = "schema:\n  schema_id: double_pinyin_flypy\n  name: 小鹤双拼\n  description: |\n    x\n" +
            "translator:\n  prism: double_pinyin_flypy\n  preedit_format:\n    - xform/a/b/\n" +
            "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA`  # 注释\n" +
            "  initials: zyxwvutsrqponmlkjihgfedcba\n  algebra:\n    - erase/^xx$/\n"
        val text = m.schema(base)
        assertTrue(text.contains("  alphabet: zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA`;  # 注释\n"))
        assertTrue(text.contains("  initials: zyxwvutsrqponmlkjihgfedcba\n"))
        assertTrue(m.schema(base.replace("alphabet: zyx", "alphabet: \"zyx").replace("`  #", "`\"  #")).contains("A`;\"  #"))
        assertTrue(DoublePinyinScheme.ZIRANMA.schema(base).contains("A`  # 注释\n"))
    }

    @Test fun builtinPreeditShowsPinyin() {
        val rules = DoublePinyinScheme.ZIRANMA.preeditFormat()
        assertEquals("zhong guo", apply(rules, "vsgo"))
        assertEquals("shuang'pin", apply(rules, "ud'pn"))
        assertEquals("ang eng er", apply(rules, "aheger"))
        assertEquals("lve jue", apply(rules, "ltjt"))
        assertEquals("zhong sh", apply(rules, "vsu"))
    }

    private val base14 = listOf("src/main/assets", "app/src/main/assets").map { java.io.File(it, "rime_chinese/double_pinyin_flypy_14jian.schema.yaml") }
        .first { it.isFile }.readText()

    /** 14 键、合并键布局没有分号键：分号（微软双拼的 ing）也能用 L 键打。 */
    private fun onL(code: String) = setOf(code, code.replace(';', 'l'))

    private fun text14(scheme: DoublePinyinScheme) = scheme.k14Schema(base14).substringAfter("  algebra:\n").lines()
        .takeWhile { it.startsWith("    - ") }.map { it.removePrefix("    - \"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\") }

    @Test fun fourteenKeyVersionTypesEachKeyExactlyOrMerged() {
        val groups = mapOf("qw" to 'Q', "er" to 'E', "ty" to 'T', "ui" to 'U', "op" to 'O', "as" to 'A', "df" to 'D',
            "gh" to 'G', "jk" to 'J', "zx" to 'Z', "cv" to 'C', "bn" to 'B')
        fun merged(c: Char) = groups.entries.firstOrNull { c in it.key }?.value ?: c
        // 14 键没有分号键：微软双拼的 ing（分号）并到 L 键，x; 也能打 xl、Zl。
        for (scheme in DoublePinyinScheme.BUILTINS.drop(1) + custom) {
            val id = DoublePinyinScheme.k14Id(scheme.id)
            val text = scheme.k14Schema(base14)
            assertTrue(text.startsWith(DoublePinyinScheme.GENERATED_MARK + id + "\n"))
            assertTrue(text.contains("  schema_id: $id\n"))
            assertTrue(text.contains("  name: ${org.json.JSONObject.quote(scheme.name)}\n"))
            assertTrue(text.contains("  language: zh\n  input_scheme: double_pinyin\n"))
            assertTrue(text.contains("  prism: $id\n"))
            assertFalse(text.contains("double_pinyin_flypy_14jian"))
            // 词库、候选规则、符号和编码显示照 14 键小鹤双拼。
            for (line in listOf("  dictionary: rime_ice\n", "    - cyime_candidate_policy\n", "  alphabet: zyxwvutsrqponmlkjihgfedcbaQETUOADGJZCB\n",
                "    - xlit/QETUOADGJZCB/qetuoadgjzcb/\n", "  __include: cyime_punctuator:/punctuator\n"))
                assertTrue(line, text.contains(line))
            val rules = text.substringAfter("  algebra:\n").lines().takeWhile { it.startsWith("    - ") }
                .map { it.removePrefix("    - \"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
            assertEquals("erase/^[A-Z]$/", rules.first())
            assertEquals(1 + scheme.algebra().size + (if (scheme.usesSemicolon) 1 else 0) + 24, rules.size)
            for (syllable in DoublePinyinScheme.SYLLABLES) {
                val result = spellings(rules, syllable)
                val expected = codes(scheme, syllable).flatMap(::onL).flatMap { setOf(it, "${merged(it[0])}${it[1]}", "${it[0]}${merged(it[1])}", "${merged(it[0])}${merged(it[1])}") }.toSet()
                assertEquals(syllable, expected, result)
            }
        }
        // 自然码 zhong = VS：点 CV 键、AS 键，或侧滑出 V、S。
        assertEquals("CA", DoublePinyinScheme.ZIRANMA.code("zhong")!!.map(::merged).joinToString(""))
        assertTrue("Zl" in spellings(text14(DoublePinyinScheme.MICROSOFT), "xing"))
    }

    /** 自己排的 20 键（we、yu、op、as、jk、cv 合并）上的双拼：每一位都可以点合并键（送键上第一个字母）或长按选精确字母。 */
    @Test fun layoutVersionTypesEachKeyExactlyOrMerged() {
        val base = """
            schema:
              schema_id: double_pinyin_flypy
              name: 小鹤双拼
              description: |
                雾凇拼音 - 小鹤双拼

            translator:
              dictionary: rime_ice
              prism: double_pinyin_flypy    # 注释
              enable_correction: true
              cyime_neighbor_correction: true
              preedit_format:
                - xform/(\w)g/${'$'}1eng/

            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
              algebra:
                - erase/^xx${'$'}/
            """.trimIndent() + "\n"
        val groups = listOf("q", "we", "r", "t", "yu", "i", "op", "as", "d", "f", "g", "h", "jk", "l", "z", "x", "cv", "b", "n", "m")
        fun merged(c: Char) = groups.firstOrNull { c in it }?.get(0) ?: c
        val layout = "custom_pinyin_" + "2".repeat(32)
        for (scheme in DoublePinyinScheme.BUILTINS.drop(1) + custom) {
            val id = DoublePinyinScheme.layoutVariantId(scheme.id, layout)
            assertEquals(scheme.id to layout, DoublePinyinScheme.splitLayoutVariant(id))
            val text = scheme.layoutSchema(scheme.schema(base), id, "二十键", groups)
            assertTrue(text.startsWith(DoublePinyinScheme.GENERATED_MARK + id + "\n"))
            assertEquals(1, text.lines().count { it.startsWith(DoublePinyinScheme.GENERATED_MARK) })
            assertTrue(text.contains("  schema_id: $id\n  language: zh\n  input_scheme: double_pinyin\n"))
            // 名称和 26 键双拼相同：方案栏里同一个方案，换布局时保持。
            assertTrue(text.contains("  name: ${org.json.JSONObject.quote(scheme.name)}\n"))
            assertTrue(text.contains("自建布局「二十键」"))
            assertTrue(text.contains("  prism: $id\n  spelling_hints: 8\n  always_show_comments: true\n"))
            // 代表字母是小写，26 键的编码显示会把它显示成别的音节；临键纠错按 26 键位置算，合并键上不开。
            assertTrue(text.contains("  preedit_format: []\n"))
            assertFalse(text.contains("enable_correction") || text.contains("cyime_neighbor_correction"))
            val rules = text.substringAfter("  algebra:\n").lines().takeWhile { it.startsWith("    - ") }
                .map { it.removePrefix("    - \"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
            assertEquals(scheme.algebra() + scheme.separatorRules() + listOfNotNull("derive/;/l/".takeIf { scheme.usesSemicolon }) + listOf(0, 1).flatMap { p -> groups.filter { it.length == 2 }.map { "derive/^(.{$p})[${it[1]}](.*)$/\$1${it[0]}\$2/" } }, rules)
            for (syllable in DoublePinyinScheme.SYLLABLES) {
                val expected = codes(scheme, syllable).flatMap(::onL).flatMap { setOf(it, "${merged(it[0])}${it[1]}", "${it[0]}${merged(it[1])}", "${merged(it[0])}${merged(it[1])}") }.toSet()
                assertEquals(syllable, expected, spellings(rules, syllable).filterNot { '\'' in it }.toSet())
            }
        }
        assertEquals(null, DoublePinyinScheme.splitLayoutVariant(DoublePinyinScheme.k14Id(DoublePinyinScheme.ZIRANMA_ID)))
        assertEquals(null, DoublePinyinScheme.splitLayoutVariant("custom_pinyin_" + "2".repeat(32)))
    }

    @Test fun flypyFourteenKeyAssetMatchesTheGeneratedRules() {
        // 随应用内置的 14 键小鹤双拼和由键位表生成的 14 键版本打出同样的编码（两边都有 ju→jv 的写法）。
        val asset = base14.substringAfter("  algebra:\n").lines().takeWhile { it.startsWith("    - ") || it.startsWith("    #") }
            .filter { it.startsWith("    - ") }.map { it.removePrefix("    - ").removeSurrounding("\"") }
        val generated = DoublePinyinScheme.FLYPY.copy(id = DoublePinyinScheme.ZIRANMA_ID).k14Schema(base14)
            .substringAfter("  algebra:\n").lines().takeWhile { it.startsWith("    - ") }
            .map { it.removePrefix("    - \"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
        for (syllable in DoublePinyinScheme.SYLLABLES) {
            val fromAsset = spellings(asset, syllable)
            val fromTable = spellings(generated, syllable)
            assertTrue("$syllable $fromTable $fromAsset", fromAsset.containsAll(fromTable))
        }
    }
}
