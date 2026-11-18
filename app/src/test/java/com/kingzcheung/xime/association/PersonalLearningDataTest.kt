package com.kingzcheung.xime.association

import org.junit.Assert.*
import org.junit.Test

class PersonalLearningDataTest {
    @Test fun legacyObservationsAreNotUniqueWordCounts() {
        val data = PersonalLearningData.decode("""{"bigrams":[{"tokens":["你","好"],"count":3}],"trigrams":[],"recentInputs":[]}""")
        assertEquals(1, data.uniqueSequences)
        assertEquals(3L, data.observations)
        assertEquals(data, PersonalLearningData.decode(data.encode()))
    }
    @Test fun wholeWordProfileRoundTripsSeparatelyFromPhoneLearning() {
        val data = PersonalLearningData(profileName = "自用", continuations = listOf(PersonalContinuation("我想", "看看", 12)))
        assertEquals(data, PersonalLearningData.decode(data.encode()))
        assertEquals(0L, data.observations)
        assertEquals("看看", PersonalContinuationIndex(data.continuations).predict("今天我想").single().text)
        assertTrue(PersonalContinuationIndex(data.continuations).predict("我想。").isEmpty())
    }
    @Test fun weightedTrieUsesConditionalCountsAndDoesNotRepeatInsertion() {
        val trie = NgramTrie()
        trie.insert(listOf("你", "好"), 900_000)
        trie.insert(listOf("你", "们"), 100_000)
        assertEquals(.9f, trie.next(listOf("你")).toMap().getValue("好"), .00001f)
        assertEquals(.1f, trie.getFrequency(listOf("你", "们")), .00001f)
        assertTrue(trie.next(listOf("我")).isEmpty())
    }
    @Test fun editableExportRoundTripsAndDeletingALineRemovesThatEntry() {
        val data = PersonalLearningData(
            bigrams = listOf(LearnedSequence(listOf("你", "好"), 3), LearnedSequence(listOf("我", "们"), 5),
                LearnedSequence(listOf("\n", "\""), 1)),
            trigrams = listOf(LearnedSequence(listOf("我", "们", "的"), 2)),
            recentInputs = listOf("我", "们"), profileName = "自用 \"方案\"",
            continuations = listOf(PersonalContinuation("我想", "看看", 12), PersonalContinuation("想", "问问", 2)))
        val text = data.encodeForEditing()
        assertEquals(data, PersonalLearningData.decode(text))
        assertEquals(PersonalLearningData(), PersonalLearningData.decode(PersonalLearningData().encodeForEditing()))
        // 每条一行：在电脑上删掉一行 (不是最后一行) 就删掉这一条，其余不变。
        val lines = text.lines()
        val target = lines.single { "tokens" in it && "[\"我\",\"们\"]" in it }
        val edited = PersonalLearningData.decode(lines.filterNot { it == target }.joinToString("\n"))
        assertEquals(data.copy(bigrams = data.bigrams.filterNot { it.tokens == listOf("我", "们") }), edited)
        val profileLine = lines.single { "看看" in it }
        assertEquals(data.continuations.drop(1),
            PersonalLearningData.decode(lines.filterNot { it == profileLine }.joinToString("\n")).continuations)
    }
    @Test fun addImportKeepsLocalLearningAndIsIdempotent() {
        val local = PersonalLearningData(
            bigrams = listOf(LearnedSequence(listOf("你", "好"), 5), LearnedSequence(listOf("我", "们"), 2)),
            trigrams = listOf(LearnedSequence(listOf("我", "们", "的"), 2)),
            recentInputs = listOf("好"), profileName = "",
            continuations = listOf(PersonalContinuation("我想", "看看", 3)))
        val imported = PersonalLearningData(
            bigrams = listOf(LearnedSequence(listOf("你", "好"), 2), LearnedSequence(listOf("我", "们"), 9), LearnedSequence(listOf("大", "家"), 4)),
            recentInputs = listOf("家"), profileName = "自用",
            continuations = listOf(PersonalContinuation("我想", "看看", 1), PersonalContinuation("想", "问问", 6)))
        val merged = local.mergedWith(imported)
        assertEquals(listOf(LearnedSequence(listOf("你", "好"), 5), LearnedSequence(listOf("我", "们"), 9),
            LearnedSequence(listOf("大", "家"), 4)), merged.bigrams)
        assertEquals(local.trigrams, merged.trigrams)
        assertEquals(local.recentInputs, merged.recentInputs)
        assertEquals("自用", merged.profileName)
        assertEquals(listOf(PersonalContinuation("我想", "看看", 3), PersonalContinuation("想", "问问", 6)), merged.continuations)
        assertEquals(merged, merged.mergedWith(imported))
        assertEquals(merged, PersonalLearningData.decode(merged.encodeForEditing()))
        // 没有语料方案的文件不换掉本机语料方案的同一份列表，保存时不必重写语料文件。
        assertSame(local.continuations, local.mergedWith(imported.copy(continuations = emptyList())).continuations)
    }
    @Test fun addImportStaysWithinStoredLimits() {
        val many = (0 until PersonalLearningData.MAX_PROFILE_ENTRIES).map { PersonalContinuation("词$it", "接", 1) }
        val merged = PersonalLearningData(continuations = many)
            .mergedWith(PersonalLearningData(continuations = listOf(PersonalContinuation("我想", "看看", 9))))
        assertEquals(PersonalLearningData.MAX_PROFILE_ENTRIES, merged.continuations.size)
        assertEquals("看看", merged.continuations.first().text)
        PersonalLearningData.decode(merged.encodeForEditing())
    }
    @Test fun otherCyimeFilesAreReportedAsNotLearningFiles() {
        for (text in listOf(
            """{"items":[{"text":"你好","time":1}]}""",
            """{"format":"cyime.dictionary.v1","entries":[]}""",
            """{"format":"cyime-personal-learning","version":1,"trigrams":[]}"""
        )) {
            try { PersonalLearningData.decode(text); fail(text) } catch (_: NotLearningFileException) { }
        }
        try { PersonalLearningData.decode("""{"format":"cyime-personal-learning","version":2,"bigrams":[],"trigrams":[]}"""); fail() }
        catch (error: IllegalArgumentException) { assertFalse(error is NotLearningFileException) }
    }
    @Test fun malformedOrDuplicateImportsAreRejectedBeforeReplacement() {
        for (row in listOf(
            """{"tokens":["你","好"],"count":0}""",
            """{"tokens":["你","好"],"count":1.5}""",
            """{"tokens":["你"],"count":1}""",
            """{"tokens":["你","好"],"count":1},{"tokens":["你","好"],"count":2}"""
        )) {
            try { PersonalLearningData.decode("""{"bigrams":[$row],"trigrams":[]}"""); fail(row) }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun longestContextWinsWithoutScanningUnrelatedRows() {
        val rows = (1..10_000).map { PersonalContinuation("词$it", "接话", 1) } +
            listOf(PersonalContinuation("我想", "看看", 2), PersonalContinuation("想", "问问", 10))
        val index = PersonalContinuationIndex(rows)
        repeat(1000) { assertEquals("看看", index.predict("我想").first().text) }
    }

    @Test fun storedLearningOverLimitsIsTrimmedInsteadOfRejected() {
        val rows = (0 until PersonalLearningData.MAX_SEQUENCES + 5).joinToString(",") {
            """{"tokens":["${'a' + it % 26}","${(0x4e00 + it % 20_000).toChar()}${(0x4e00 + it / 20_000).toChar()}"],"count":${if (it == 0) 3_000_000 else 1 + it % 7}}"""
        }
        val text = """{"bigrams":[$rows],"trigrams":[],"recentInputs":[]}"""
        try { PersonalLearningData.decode(text); fail("imports stay strict") } catch (_: IllegalArgumentException) { }
        val data = PersonalLearningData.decode(text, storedLocally = true)
        assertEquals(PersonalLearningData.STORED_KEPT, data.bigrams.size)
        assertTrue(data.bigrams.all { it.count in 1..PersonalLearningData.MAX_COUNT })
        assertEquals(listOf("a", "一一"), data.bigrams.first().tokens)
        assertEquals(data, PersonalLearningData.decode(data.encode()))
    }
    @Test fun dataWithinLimitsIsStoredUnchanged() {
        val data = PersonalLearningData(bigrams = listOf(LearnedSequence(listOf("你", "好"), 3)))
        assertSame(data, data.boundedForStorage())
    }

    // 用户 11-15：「喂自己语料」只能选 txt，md 都选不了。现在任意文字文件都按打过这些字来学。
    private fun pairs(data: PersonalLearningData) = data.bigrams.associate { it.tokens.joinToString("") to it.count }

    @Test fun markdownIsLearnedWithoutSymbolsLinksOrCrossLineJoins() {
        val data = PersonalLearningData.fromText("# 原神攻略\n\n- **刻晴**很强\n[看这里](https://example.com/a)：原神\n")
        val learned = pairs(data)
        assertEquals(2, learned["原神"])
        assertTrue("刻晴" in learned && "晴很" in learned)
        assertTrue(learned.keys.none { it.contains('#') || it.contains('*') || it.contains('-') || it.contains('/') })
        assertFalse("略刻" in learned)  // 行与行不连
        assertFalse(learned.keys.any { it == "ht" || it == "ex" })
        assertTrue(data.trigrams.any { it.tokens == listOf("原", "神", "攻") })
    }

    @Test fun subtitleNumbersAndTimesAreSkipped() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\n今天开黑\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\n今天上分\r\n"
        val learned = pairs(PersonalLearningData.fromText(srt))
        assertEquals(2, learned["今天"])
        assertTrue(learned.keys.none { it.any(Char::isDigit) })
    }

    @Test fun csvAndJsonKeepWordsAndCountsStayWithinImportLimits() {
        val data = PersonalLearningData.fromText("名字,说明\n绝区零,游戏\n{\"text\":\"绝区零\"}\n")
        assertEquals(2, pairs(data)["绝区"])
        // 导出再按学习文件读回去，格式合法（每个字一格、次数在范围内）
        assertEquals(data, PersonalLearningData.decode(data.encode()))
        assertTrue(PersonalLearningData.fromText("\n  \n123\n").bigrams.isEmpty())
    }

    @Test fun textIsRecognisedByContentNotExtension() {
        assertEquals("你好 md", PersonalLearningData.textOf("\uFEFF你好 md".toByteArray(Charsets.UTF_8)))
        assertEquals("记事本存的", PersonalLearningData.textOf("记事本存的".toByteArray(charset("GB18030"))))
        assertNull(PersonalLearningData.textOf(ByteArray(0)))
        assertNull(PersonalLearningData.textOf(byteArrayOf(0x40, 0x15, 0x00, 0x00, 0x12, 0x34)))  // 搜狗 scel 这类二进制
        assertNull(PersonalLearningData.textOf(ByteArray(200) { (1 + it % 30).toByte() }))
    }
}
