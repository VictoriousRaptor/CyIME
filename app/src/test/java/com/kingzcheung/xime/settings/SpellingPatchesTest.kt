package com.kingzcheung.xime.settings

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import org.junit.Assert.*
import org.junit.Test

class SpellingPatchesTest {
    private val schema = """
        |schema:
        |  schema_id: rime_ice
        |speller:
        |  alphabet: zyxwvutsrqponmlkjihgfedcba
        |  algebra:
        |    - erase/^hm$/
        |    - abbrev/^([a-z]).+$/${'$'}1/
        |    - derive/^([nl])ve$/${'$'}1ue/
        |translator:
        |  dictionary: rime_ice
        |""".trimMargin()

    private fun algebraIn(custom: String): List<String> {
        val root = SchemaManager.yaml.parseToYamlNode(custom) as YamlMap
        val patch = root.entries.entries.single { it.key.content == "patch" }.value as YamlMap
        val list = patch.entries.entries.single { it.key.content == "speller/algebra" }.value as YamlList
        return list.items.map { (it as YamlScalar).content }
    }

    @Test fun readsTheSchemaAlgebraInOrder() {
        assertEquals(listOf("erase/^hm$/", "abbrev/^([a-z]).+$/\$1/", "derive/^([nl])ve$/\$1ue/"),
            SpellingPatches.algebraOf(schema))
        assertNull(SpellingPatches.algebraOf("translator:\n  dictionary: x\n"))
    }

    @Test fun fuzzyRulesComeBeforeTheOriginalRulesAndSurviveYamlQuoting() {
        val algebra = SpellingPatches.algebraOf(schema)!!
        val items = linkedSetOf(SpellingPatches.Fuzzy.Z, SpellingPatches.Fuzzy.AN)
        val custom = SpellingPatches.patch("", SpellingPatches.fuzzyLines(algebra, items))!!
        assertEquals(SpellingPatches.Fuzzy.Z.penalized + SpellingPatches.Fuzzy.AN.penalized + algebra, algebraIn(custom))
    }

    @Test fun otherPatchesAreKeptAndTurningEverythingOffRestoresTheFile() {
        val original = "patch:\n  \"engine/translators/+\":\n    - table_translator@custom_phrase\n  custom_phrase:\n    user_dict: custom_phrase\n"
        val lines = SpellingPatches.fuzzyLines(listOf("erase/^hm$/"), setOf(SpellingPatches.Fuzzy.NL))
        val patched = SpellingPatches.patch(original, lines)!!
        assertTrue(patched.contains("table_translator@custom_phrase"))
        assertEquals(listOf("fuzz/^n/l/", "fuzz/^l/n/", "erase/^hm$/"), algebraIn(patched))
        // 再写一次只换掉自己那一段，不重复。
        val again = SpellingPatches.patch(patched, lines)!!
        assertEquals(patched, again)
        assertEquals(original, SpellingPatches.patch(patched, emptyList()))
    }

    @Test fun aFileWithoutPatchGetsOneAndBrokenMarkersAreLeftAlone() {
        val patched = SpellingPatches.patch("# 用户注释\n", SpellingPatches.fuzzyLines(listOf("erase/^hm$/"), setOf(SpellingPatches.Fuzzy.IN)))!!
        assertTrue(patched.startsWith("# 用户注释\npatch:\n"))
        assertEquals(listOf("fuzz/ing$/in/", "fuzz/in$/ing/", "erase/^hm$/"), algebraIn(patched))
        assertNull(SpellingPatches.patch("patch:\n  # BEGIN CYIME SPELLING\n", listOf("\"translator/enable_correction\": true")))
    }

    @Test fun doublePinyinCorrectionIsOneTranslatorKey() {
        val patched = SpellingPatches.patch("", listOf("\"translator/enable_correction\": true"))!!
        val root = SchemaManager.yaml.parseToYamlNode(patched) as YamlMap
        val patch = root.entries.entries.single { it.key.content == "patch" }.value as YamlMap
        assertEquals("true", (patch.entries.entries.single { it.key.content == "translator/enable_correction" }.value as YamlScalar).content)
        assertEquals("", SpellingPatches.patch(patched, emptyList())!!.replace("patch:\n", ""))
    }

    @Test fun doublePinyinFuzzyAndCorrectionShareOneBlock() {
        val flypy = listOf("erase/^xx$/", "derive/^([jqxy])u$/\$1v/", "xform/ing$/Ⓚ/", "xlit/Ⓚ/k/")
        val lines = SpellingPatches.fuzzyLines(flypy, setOf(SpellingPatches.Fuzzy.IN)) + "\"translator/enable_correction\": true"
        val patched = SpellingPatches.patch("", lines)!!
        assertEquals(SpellingPatches.Fuzzy.IN.penalized + flypy, algebraIn(patched))
        val root = SchemaManager.yaml.parseToYamlNode(patched) as YamlMap
        val patch = root.entries.entries.single { it.key.content == "patch" }.value as YamlMap
        assertEquals("true", (patch.entries.entries.single { it.key.content == "translator/enable_correction" }.value as YamlScalar).content)
        // 关掉模糊音只留纠错：同一段整段换掉，不留旧规则。
        val correctionOnly = SpellingPatches.patch(patched, listOf("\"translator/enable_correction\": true"))!!
        assertFalse(correctionOnly.contains("speller/algebra"))
        assertEquals(SpellingPatches.patch("", listOf("\"translator/enable_correction\": true")), correctionOnly)
    }

    /** 编码显示双拼键：编码显示规则清空，原来的规则原样抄给希腊字母；关掉时整段去掉，文件回到原样。 */
    @Test fun doublePinyinRawPreeditEmptiesTheFormatAndKeepsACopy() {
        val flypy = schema.replace("translator:\n  dictionary: rime_ice\n",
            "translator:\n  dictionary: rime_ice\n  preedit_format:\n    - xform/([bpmfdtnljqx])n/${'$'}1iao/\n    - \"xform/(?<=[A-Z])\\\\s(?=[A-Z])//\"\n")
        val format = SpellingPatches.preeditFormatOf(flypy)!!
        assertEquals(listOf("xform/([bpmfdtnljqx])n/\$1iao/", "xform/(?<=[A-Z])\\s(?=[A-Z])//"), format)
        assertNull(SpellingPatches.preeditFormatOf(schema))
        val patched = SpellingPatches.patch("", SpellingPatches.rawPreeditLines(format))!!
        val root = SchemaManager.yaml.parseToYamlNode(patched) as YamlMap
        val patch = root.entries.entries.single { it.key.content == "patch" }.value as YamlMap
        assertEquals(0, (patch.entries.entries.single { it.key.content == "translator/preedit_format" }.value as YamlList).items.size)
        val copy = patch.entries.entries.single { it.key.content == SpellingPatches.DOUBLE_PINYIN_READING }.value as YamlList
        assertEquals(format, copy.items.map { (it as YamlScalar).content })
        // 方案没有编码显示规则时只清空，不抄空表。
        assertEquals(listOf("\"translator/preedit_format\": []"), SpellingPatches.rawPreeditLines(emptyList()))
        assertEquals("", SpellingPatches.patch(patched, emptyList())!!.replace("patch:\n", ""))
    }

    /** 双拼在自建合并键布局上的版本也写模糊音：只认这些版本的方案文件，不认全拼布局、14 键和补丁文件。 */
    @Test fun doublePinyinLayoutVariantsAreListedFromTheRimeDirectory() {
        val dir = kotlin.io.path.createTempDirectory("spelling").toFile()
        try {
            val layout = "custom_pinyin_" + "2".repeat(32)
            val flypy = DoublePinyinScheme.layoutVariantId(DoublePinyinScheme.FLYPY.id, layout)
            val ziranma = DoublePinyinScheme.layoutVariantId(DoublePinyinScheme.ZIRANMA_ID, layout)
            for (id in listOf("rime_ice", DoublePinyinScheme.FLYPY.id, layout, ziranma, flypy, DoublePinyinScheme.k14Id(DoublePinyinScheme.ZIRANMA_ID)))
                java.io.File(dir, "$id.schema.yaml").writeText("schema:\n  schema_id: $id\n")
            java.io.File(dir, "$flypy.custom.yaml").writeText("patch:\n")
            assertEquals(listOf(flypy, ziranma), SpellingPatches.doublePinyinLayoutVariants(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anAndAngLeaveIanAndUanToTheirOwnGroups() {
        val toAn = Regex("(^|[^iu])ang$")
        val toAng = Regex("(^|[^iu])an$")
        assertTrue(toAn.containsMatchIn("fang") && toAn.containsMatchIn("ang") && toAn.containsMatchIn("zhang"))
        assertFalse(toAn.containsMatchIn("xiang") || toAn.containsMatchIn("guang"))
        assertTrue(toAng.containsMatchIn("fan") && toAng.containsMatchIn("an") && toAng.containsMatchIn("yan"))
        assertFalse(toAng.containsMatchIn("xian") || toAng.containsMatchIn("guan"))
        assertEquals("derive/(^|[^iu])ang$/\$1an/", SpellingPatches.Fuzzy.AN.rules[0])
    }

    @Test fun everyGroupDerivesBothWaysAndHasAStableId() {
        for (item in SpellingPatches.Fuzzy.entries) {
            assertEquals(item.title, 2, item.rules.size)
            assertTrue(item.title, item.rules.all { it.startsWith("derive/") })
        }
        assertEquals(SpellingPatches.Fuzzy.entries.size, SpellingPatches.Fuzzy.entries.map { it.id }.toSet().size)
    }

    /** 14 键全拼也写模糊音（11-01 反馈：设置在「九键与 14 键」页，14 键开了却不模糊）：规则排在合并键派生前面。 */
    @Test fun builtinFourteenKeyPinyinGetsFuzzyRulesBeforeItsMergedKeyRules() {
        assertTrue("pinyin_14jian" in SpellingPatches.builtinFullPinyinSchemas)
        val algebra = SpellingPatches.algebraOf(java.io.File("src/main/assets/rime_chinese/pinyin_14jian.schema.yaml").readText())!!
        assertTrue(algebra.any { it.contains("[as]") })
        val items = linkedSetOf(SpellingPatches.Fuzzy.S, SpellingPatches.Fuzzy.AN, SpellingPatches.Fuzzy.EN)
        val custom = SpellingPatches.patch("", SpellingPatches.fuzzyLines(algebra, items))!!
        assertEquals(items.flatMap { it.penalized } + algebra, algebraIn(custom))
    }

    /** 按 Rime 的意思跑一遍拼写规则（xform 改、derive/abbrev 加、erase 删、xlit 换字），只用于检查规则之间的先后。 */
    private fun spellingsOf(rules: List<String>, syllable: String): Set<String> = rules.fold(setOf(syllable)) { spellings, rule ->
        val parts = rule.split('/')
        val (op, from, to) = Triple(parts[0], parts[1], parts[2])
        when (op) {
            "xlit" -> spellings.mapTo(linkedSetOf()) { s ->
                s.map { c -> from.indexOf(c).let { if (it < 0) c else to[it] } }.joinToString("")
            }
            "erase" -> spellings.filterTo(linkedSetOf()) { !Regex(from).matches(it) }
            "xform" -> spellings.mapTo(linkedSetOf()) { it.replace(Regex(from), to) }
            else -> spellings + spellings.map { it.replace(Regex(from), to) }
        }
    }

    /**
     * 双拼简拼（11-02）：按拼音首字母打（zh 也能打 z），也能打本方案 zh 所在的键；a、o、e 开头的字的简拼不被零声母规则改成 aa、oo、ee；
     * 完整双拼编码和原来的 ' 分词拼写都还在，不留 # 记号。
     */
    @Test fun doublePinyinAbbreviationAddsInitialsAroundTheSchemeRules() {
        val scheme = DoublePinyinScheme.FLYPY
        val original = scheme.algebra() + scheme.separatorRules()
        val (before, after) = SpellingPatches.abbreviationRules(scheme)
        val rules = before + original + after
        for (syllable in listOf("zhong", "guo", "ai", "an", "e", "er", "shi", "chi", "a")) {
            val old = spellingsOf(original, syllable)
            val new = spellingsOf(rules, syllable)
            assertTrue(syllable, new.containsAll(old))
            assertTrue(syllable, new.none { '#' in it })
        }
        assertTrue(spellingsOf(rules, "zhong").containsAll(listOf("vs", "z", "v")))
        assertTrue(spellingsOf(rules, "shi").containsAll(listOf("ui", "s", "u")))
        assertTrue(spellingsOf(rules, "guo").containsAll(listOf("go", "g")))
        assertEquals(spellingsOf(original, "ai") + "a", spellingsOf(rules, "ai"))
        // 单字母的音节没有简拼：e 还是 ee。
        assertEquals(spellingsOf(original, "e"), spellingsOf(rules, "e"))
        // 方案不明时只按拼音首字母。
        assertEquals(listOf("abbrev/^([a-z]).+$/\$1#/"), SpellingPatches.abbreviationRules(null).first)
    }

    @Test fun doublePinyinAbbreviationSitsBetweenFuzzyRulesAndTheSchemeRules() {
        val algebra = listOf("erase/^xx$/", "xform/^zh/V/")
        val abbreviation = SpellingPatches.abbreviationRules(DoublePinyinScheme.FLYPY)
        val lines = SpellingPatches.doublePinyinLines(algebra, linkedSetOf(SpellingPatches.Fuzzy.IN), abbreviation)
        assertEquals("\"speller/algebra\":", lines.first())
        val expected = SpellingPatches.Fuzzy.IN.penalized + abbreviation.first + algebra + abbreviation.second
        assertEquals(expected.map { "  - \"" + it.replace("\\", "\\\\") + "\"" }, lines.drop(1))
    }

    /**
     * 14 键双拼（11-15）：模糊音、简拼和 26 键双拼一样写在最前，派生出的近音拼音、简拼都再换成合并键，
     * 所以 zi 也能打出 zhi 的 14 键编码，zhong 能打 Z。
     */
    @Test fun fourteenKeyDoublePinyinGetsFuzzyAndAbbreviationBeforeItsMergedKeys() {
        val base = java.io.File("src/main/assets/rime_chinese/double_pinyin_flypy_14jian.schema.yaml").readText()
        for (scheme in listOf(DoublePinyinScheme.FLYPY, DoublePinyinScheme.BUILTINS.first { it.id == DoublePinyinScheme.ZIRANMA_ID })) {
            val algebra = if (scheme == DoublePinyinScheme.FLYPY) SpellingPatches.algebraOf(base)!!
                else SpellingPatches.algebraOf(scheme.k14Schema(base).lineSequence().drop(1).joinToString("\n"))!!
            val abbreviation = SpellingPatches.abbreviationRules(scheme)
            val rules = SpellingPatches.Fuzzy.Z.penalized + abbreviation.first + algebra + abbreviation.second
            assertTrue(scheme.id, spellingsOf(rules, "zi").containsAll(spellingsOf(algebra, "zhi")))
            assertTrue(scheme.id, "Z" in spellingsOf(rules, "zhong"))
            assertTrue(scheme.id, spellingsOf(rules, "zhong").none { '#' in it })
        }
    }

    /** 11-03 统一纠错：顺序、多键、漏键默认开（不写补丁，同以前）；关了只写方案里本来有的键。 */
    @Test fun typoSwitchesWriteOnlyWhatIsTurnedOffAndPresent() {
        val iceSchema = schema + "  enable_correction: true\n  cyime_neighbor_correction: true\n" +
            "  cyime_doubled_letter_correction: true\n  cyime_transposition_correction: true\n" +
            "  cyime_extra_letter_correction: true\n  cyime_stray_initial_correction: true\n" +
            "cyime_omission_schema: cyime_omission\ncyime_edit_schema: cyime_letter_swap\n"
        val all = SpellingPatches.Typo.entries.toSet()
        assertTrue(SpellingPatches.typoParts(iceSchema, all).all { it.lines.isEmpty() })
        val lines = SpellingPatches.typoParts(iceSchema, emptySet()).flatMap { it.lines }
        assertEquals(listOf("\"translator/cyime_transposition_correction\": false", "\"cyime_edit_schema\": \"\"",
            "\"translator/cyime_extra_letter_correction\": false", "\"translator/cyime_doubled_letter_correction\": false",
            "\"cyime_omission_schema\": \"\""), lines)
        // 只关顺序：停顿后的打反补查也关，漏键、多键不动。
        assertEquals(listOf("\"translator/cyime_transposition_correction\": false", "\"cyime_edit_schema\": \"\""),
            SpellingPatches.typoParts(iceSchema, all - SpellingPatches.Typo.TRANSPOSITION).flatMap { it.lines })
        // 14 键等没有这些键的方案什么都不写。
        assertTrue(SpellingPatches.typoParts(schema, emptySet()).all { it.lines.isEmpty() })
        // 写出来是合法补丁，Rime 读到的是 false 和空名。
        val custom = SpellingPatches.patch("", lines)!!
        val patch = (SchemaManager.yaml.parseToYamlNode(custom) as YamlMap).entries.entries.single { it.key.content == "patch" }.value as YamlMap
        fun value(key: String) = (patch.entries.entries.single { it.key.content == key }.value as YamlScalar).content
        assertEquals("false", value("translator/cyime_transposition_correction"))
        assertEquals("", value("cyime_omission_schema"))
        assertEquals("", value("cyime_edit_schema"))
    }

    private val doublePinyinSchema = """
        |schema:
        |  schema_id: double_pinyin_flypy
        |  dependencies:
        |    - melt_eng
        |    - radical_pinyin
        |engine:
        |  filters:
        |    - simplifier@traditionalize_user
        |    - lua_filter@*search@radical_pinyin
        |    - uniquifier
        |key_binder:
        |  search: "`"
        |""".trimMargin()

    private fun patchedList(custom: String, key: String): List<String>? {
        val root = SchemaManager.yaml.parseToYamlNode(custom) as YamlMap
        val patch = root.entries.entries.single { it.key.content == "patch" }.value as YamlMap
        val list = patch.entries.entries.singleOrNull { it.key.content == key }?.value as? YamlList ?: return null
        return list.items.map { (it as YamlScalar).content }
    }

    /** 11-15 双拼辅助码：只把部件拼音那道筛选换成形码表，别的滤镜顺序不动，码表方案加进依赖一起编，再留个标记给输入法读。 */
    @Test fun auxCodeSwapsOnlyTheRadicalSearchAndAddsItsTable() {
        val parts = SpellingPatches.auxCodeParts(doublePinyinSchema, SpellingPatches.AuxCode.FLYPY)
        val custom = SpellingPatches.patch("", parts.flatMap { it.lines })!!
        assertEquals(listOf("simplifier@traditionalize_user", "lua_filter@*search@cyime_aux_flypy", "uniquifier"),
            patchedList(custom, "engine/filters"))
        assertEquals(listOf("melt_eng", "radical_pinyin", "cyime_aux_flypy"), patchedList(custom, "schema/dependencies"))
        assertTrue(custom.contains("\"${SpellingPatches.AUX_CODE_MARK}\": flypy"))
    }

    @Test fun switchingAuxTablesDoesNotPileUpDependencies() {
        val withZrm = doublePinyinSchema.replace("    - radical_pinyin\n", "    - radical_pinyin\n    - cyime_aux_zrm\n")
        val parts = SpellingPatches.auxCodeParts(withZrm, SpellingPatches.AuxCode.FLYPY)
        val custom = SpellingPatches.patch("", parts.flatMap { it.lines })!!
        assertEquals(listOf("melt_eng", "radical_pinyin", "cyime_aux_flypy"), patchedList(custom, "schema/dependencies"))
    }

    /** 关着、或者方案自己改过没有部件拼音筛选时，一行都不写，方案原样。 */
    @Test fun auxCodeOffOrUnknownSchemaWritesNothing() {
        val off = SpellingPatches.auxCodeParts(null, null)
        assertTrue(off.all { it.lines.isEmpty() })
        assertTrue(SpellingPatches.auxCodeParts(doublePinyinSchema, null).all { it.lines.isEmpty() })
        val noSearch = doublePinyinSchema.replace("    - lua_filter@*search@radical_pinyin\n", "")
        assertTrue(SpellingPatches.auxCodeParts(noSearch, SpellingPatches.AuxCode.ZRM).all { it.lines.isEmpty() })
        assertTrue(SpellingPatches.auxCodeParts("not: [yaml", SpellingPatches.AuxCode.ZRM).all { it.lines.isEmpty() })
        // 开过再关：补丁段整段拿掉，回到原文件。
        val original = "patch:\n  \"translator/enable_correction\": true\n"
        val on = SpellingPatches.patch(original, SpellingPatches.auxCodeParts(doublePinyinSchema, SpellingPatches.AuxCode.ZRM).flatMap { it.lines })!!
        assertEquals(original, SpellingPatches.patch(on, off.flatMap { it.lines }))
    }

    @Test fun auxCodeIdsAndTablesStayStable() {
        // 存的值是 id，改了旧设置就读不出来。
        assertEquals(listOf("zrm", "flypy"), SpellingPatches.AuxCode.entries.map { it.id })
        assertEquals(setOf("cyime_aux_zrm", "cyime_aux_flypy"), SpellingPatches.AuxCode.schemaIds)
        assertNull(SpellingPatches.AuxCode.of(null))
        assertNull(SpellingPatches.AuxCode.of("off"))
        assertEquals("`", SpellingPatches.AUX_CODE_TRIGGER)
    }

    /** 纠错强度（11-16）：和出厂方案一样的档不写也不读方案文件，没有这个键的方案不写，别的档写成方案补丁。 */
    @Test fun correctionStrengthIsWrittenOnlyWhenItDiffersFromTheShippedScheme() {
        val ice = "t9:\n  slip_correction_margin: 9.2\n  correction_lead_bias: ${TypoStrength.LEAD_DEFAULT.bias}\n"
        var read = 0
        assertTrue(SpellingPatches.leadLines({ read++; ice }, TypoStrength.LEAD_DEFAULT).isEmpty())
        assertEquals(0, read)
        assertEquals(listOf("\"t9/correction_lead_bias\": 99.0"), SpellingPatches.leadLines({ ice }, TypoStrength.Lead.NEVER))
        assertEquals(listOf("\"t9/correction_lead_bias\": 0.0"), SpellingPatches.leadLines({ ice }, TypoStrength.Lead.STANDARD))
        assertEquals(listOf("\"t9/correction_lead_bias\": -2.3"), SpellingPatches.leadLines({ ice }, TypoStrength.Lead.EAGER))
        // 14 键等不纠错的方案没有这个键，什么都不写。
        assertTrue(SpellingPatches.leadLines({ schema }, TypoStrength.Lead.NEVER).isEmpty())
        val nine = "t9:\n  joint_decoder: true\n  digit_edit_margin: ${TypoStrength.DIGIT_DEFAULT.margin}\n"
        assertTrue(SpellingPatches.digitLines({ nine }, TypoStrength.DIGIT_DEFAULT).isEmpty())
        assertEquals(listOf("\"t9/digit_edit_margin\": 6.0"), SpellingPatches.digitLines({ nine }, TypoStrength.Digit.STRICT))
        assertTrue(SpellingPatches.digitLines({ schema }, TypoStrength.Digit.STRICT).isEmpty())
        // 写出来是合法补丁，Rime 读到的是数。
        val custom = SpellingPatches.patch("", SpellingPatches.leadLines({ ice }, TypoStrength.Lead.EAGER))!!
        val patch = (SchemaManager.yaml.parseToYamlNode(custom) as YamlMap).entries.entries.single { it.key.content == "patch" }.value as YamlMap
        assertEquals("-2.3", (patch.entries.entries.single { it.key.content == "t9/correction_lead_bias" }.value as YamlScalar).content)
        // 存的档名认不出来时回到默认档。
        assertEquals(TypoStrength.LEAD_DEFAULT, TypoStrength.Lead.of("gone"))
        assertEquals(TypoStrength.LEAD_DEFAULT, TypoStrength.Lead.of(null))
        assertEquals(TypoStrength.DIGIT_DEFAULT, TypoStrength.Digit.of(null))
    }

    /** 默认档就是出厂方案里写的数：改了一边忘了另一边，默认档的人会拿到别的强度。 */
    @Test fun defaultStrengthMatchesTheShippedSchemes() {
        val gradle = java.io.File("build-logic/tasks-chinese.gradle.kts").readText()
        assertTrue(gradle.contains("\"  correction_lead_bias: ${TypoStrength.LEAD_DEFAULT.bias}\\n\""))
        val nine = java.io.File("src/main/assets/rime_chinese/t9_pinyin.schema.yaml").readText()
        assertTrue(nine.contains("\n  digit_edit_margin: ${TypoStrength.DIGIT_DEFAULT.margin}\n"))
        assertTrue(nine.contains("\ncyime_edit_schema: cyime_digit_edit\n"))
        assertTrue(nine.contains("    - cyime_digit_edit\n    - cyime_digit_tap\n"))
    }
}
