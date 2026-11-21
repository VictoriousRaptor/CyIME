package com.kingzcheung.xime.handwriting

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HanziRareTemplatesTest {
    /** 按生成脚本的格式写模板：字序号 uint16 + n 组（类序号 uint16、数值×255 uint8）。 */
    private fun build(labels: String, templates: List<Pair<Int, List<Pair<Int, Float>>>>, n: Int, sha: String = "abc"): Pair<String, ByteArray> {
        val out = ByteArrayOutputStream()
        for ((label, entries) in templates) {
            val b = ByteBuffer.allocate(2 + n * 3).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(label.toShort())
            for (k in 0 until n) {
                val (c, v) = entries.getOrElse(k) { 0 to 0f }
                b.putShort(c.toShort()); b.put(Math.round(v * 255).toByte())
            }
            out.write(b.array())
        }
        val meta = JSONObject().put("model_sha256", sha).put("n", n).put("count", templates.size).put("labels", labels).toString()
        return meta to out.toByteArray()
    }

    /** 模型输出（类序号、概率），开方归一化后就是模板里存的数值。 */
    private fun unit(vararg p: Pair<Int, Float>): List<Pair<Int, Float>> {
        val norm = kotlin.math.sqrt(p.sumOf { it.second.toDouble() }).toFloat()
        return p.map { it.first to kotlin.math.sqrt(it.second) / norm }
    }

    @Test fun matchesTemplateWhoseShapeTheModelSees() {
        // 犇：模型看成“牛/奔”；垚：模型看成“尧/圭”
        val (meta, bin) = build("犇垚", listOf(0 to unit(10 to .6f, 11 to .4f), 1 to unit(20 to .7f, 21 to .3f)), n = 2)
        val t = HanziRareTemplates.decode(meta, bin, "abc")!!
        assertEquals(2, t.templateCount)
        val m = t.match(listOf(10 to .58f, 11 to .40f, 20 to .02f), limit = 4, minSimilarity = .6f)
        assertEquals("犇", m.first().first)
        assertTrue(m.first().second > .95f)
        assertTrue(m.none { it.first == "垚" })
    }

    @Test fun onlyTemplatesWhoseTopClassIsAmongTheQueryTopClassesAreCompared() {
        // 模板首位类 50 不在查询前 32 类里：不比（分桶），即使后面的类重合
        val far = (100 until 140).map { it to 1f / 40 }
        val (meta, bin) = build("婍", listOf(0 to unit(50 to .9f, 100 to .1f)), n = 2)
        val t = HanziRareTemplates.decode(meta, bin, null)!!
        assertTrue(t.match(far, 4, 0f).isEmpty())
    }

    @Test fun keepsBestTemplatePerCharacter() {
        val (meta, bin) = build("䴓", listOf(0 to unit(1 to .5f, 2 to .5f), 0 to unit(1 to .9f, 3 to .1f)), n = 2)
        val m = HanziRareTemplates.decode(meta, bin, null)!!.match(listOf(1 to .9f, 3 to .1f), 4, 0f)
        assertEquals(1, m.size)
        assertTrue(m.single().second > .98f)
    }

    @Test fun rejectsTemplatesBuiltForAnotherModelOrTruncatedFiles() {
        val (meta, bin) = build("犇", listOf(0 to unit(1 to 1f)), n = 2, sha = "abc")
        assertNull(HanziRareTemplates.decode(meta, bin, "def"))
        assertNull(HanziRareTemplates.decode(meta, bin.copyOf(bin.size - 1), "abc"))
        assertNotNull(HanziRareTemplates.decode(meta, bin, "ABC"))
        // 声明兼容的旧模型（同一模型微调前的版本）照用，别的仍然拒绝
        val compatible = org.json.JSONObject(meta).put("compatible_model_sha256", org.json.JSONArray().put("old")).toString()
        assertNotNull(HanziRareTemplates.decode(compatible, bin, "OLD"))
        assertNull(HanziRareTemplates.decode(compatible, bin, "def"))
    }

    @Test fun bundledTemplatesCoverStandardCharactersMissingFromTheModel() {
        val dir = listOf(File("src/main/assets/handwriting"), File("app/src/main/assets/handwriting")).firstOrNull { it.isDirectory } ?: return
        val meta = File(dir, "hanzi_rare_och.json").readText()
        val t = HanziRareTemplates.decode(meta, File(dir, "hanzi_rare_och.bin").readBytes(), null)
        assertNotNull(t)
        val labels = JSONObject(meta).getString("labels")
        // 通用规范汉字表里、内置模型没有的字；扩展A；只能手写的部件拼字
        "犇垚燚叕圐圙婍䴓瀺槻".forEach { assertTrue(it.toString(), labels.contains(it)) }
        // 扩展 B 以后的字；繁体写法 11-18 起也有模板（d42686012）；模型字表里的简体字仍不在里面
        for (ch in listOf("𬭊", "𠙶", "𫫇", "說", "國")) assertTrue(ch, labels.contains(ch))
        "的中".forEach { assertFalse(it.toString(), labels.contains(it)) }
    }

    /** 回归（11-19）：1.15.100 按 UTF-16 下标取字，扩展 B 的字只取到半个代理对。 */
    @Test fun supplementaryLabelsDoNotShiftFollowingTemplates() {
        val (meta, bin) = build("𬭊國", listOf(0 to unit(3 to 1f), 1 to unit(4 to 1f)), n = 1)
        val templates = HanziRareTemplates.decode(meta, bin, null)!!
        assertEquals("𬭊", templates.match(listOf(3 to 1f), 1, .9f).single().first)
        assertEquals("國", templates.match(listOf(4 to 1f), 1, .9f).single().first)
    }
}
