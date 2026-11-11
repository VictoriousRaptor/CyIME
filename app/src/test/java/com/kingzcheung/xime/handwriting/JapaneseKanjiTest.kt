package com.kingzcheung.xime.handwriting

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JapaneseKanjiTest {
    private fun asset(name: String) = listOf("src/main/assets/handwriting/$name", "app/src/main/assets/handwriting/$name")
        .map(::File).first { it.isFile }.readText(Charsets.UTF_8)

    private val table by lazy { JapaneseKanji.parse(asset("japanese_kanji.json")) }

    @Test fun modelCharactersAreShownInJapaneseForms() {
        assertEquals(listOf("図"), table.forms["图"])
        assertEquals(listOf("駅"), table.forms["驿"])
        assertEquals("広", table.forms["广"]!!.first())
        assertEquals("気", table.forms["气"]!!.first())
        assertEquals(listOf("学"), table.forms["学"])
        // 不是日语字的简体字形不会出现在候选里
        assertFalse(table.forms.values.flatten().contains("图"))
    }

    @Test fun japaneseOnlyKanjiComeFromStrokeTemplates() {
        val extra = JSONObject(asset("japanese_kanji.json")).getJSONArray("extra").let { a -> List(a.length()) { a.getString(it) } }
        val templates = JSONObject(asset("japanese_kanji_strokes.json")).getJSONObject("chars")
        for (ch in listOf("込", "働", "峠", "畑", "枠")) {
            assertTrue(ch, ch in extra)
            assertTrue(ch, templates.has(ch))
        }
        // 日本字模板单独成组，不混进假名模板
        val kana = JSONObject(asset("kana_default_strokes.json")).getJSONObject("chars")
        assertFalse(kana.keys().asSequence().any { Character.isIdeographic(it.codePointAt(0)) })
        // 预算特征用的是内置汉字模型，标签与笔画一一对应
        val meta = JSONObject(asset("japanese_kanji_och.json"))
        assertEquals("8a6095f9ccd222fa391d4e98e2795f478d3d88daaa62d4844677fac18c7acb02", meta.getString("model_sha256"))
        assertEquals(templates.keys().asSequence().toSet(), meta.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
    }

    @Test fun modelKanjiJoinTheCandidatesAndCommonKanjiRankFirst() {
        // 查询特征：类 5（广）0.8、类 9（图）0.6
        val query = HandwritingTemplates.Feature(idx = intArrayOf(5, 9), vals = floatArrayOf(.8f, .6f))
        val raw = listOf(HandwritingRawCandidate("广", 5, .64f), HandwritingRawCandidate("图", 9, .36f))
        val best = HashMap<String, Float>(mapOf("あ" to .7f))
        JapaneseKanji.addModelKanji(best, raw, query, table)
        assertEquals(.8f, best["広"]!!, 1e-6f)
        assertEquals(.6f, best["図"]!!, 1e-6f)
        JapaneseKanji.applyFrequency(best, table)
        assertEquals(.7f, best["あ"]!!, 1e-6f) // 假名没有常用度，不加分
        assertTrue(best["広"]!! > .8f)
        val ranked = HandwritingTemplates.rank(query, emptyList(), listOf("あ" to HandwritingTemplates.Feature(idx = intArrayOf(5), vals = floatArrayOf(1f))),
            3, raw, table).map { it.first }
        assertEquals("広", ranked.first())
        assertTrue("図" in ranked && "あ" in ranked)
    }
}
