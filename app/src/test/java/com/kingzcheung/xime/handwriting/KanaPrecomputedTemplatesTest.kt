package com.kingzcheung.xime.handwriting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 内置假名模板的预算特征：用安装包内置的汉字手写模型（ochwpro）算，不依赖公式模型。 */
class KanaPrecomputedTemplatesTest {
    private fun assetFile(name: String) = listOf("src/main/assets/handwriting/$name", "app/src/main/assets/handwriting/$name")
        .map(::File).first { it.isFile }

    /** 模型目录里内置汉字手写模型 ochwpro.onnx 的哈希（BuiltinModelCatalog 中同一文件，v1.2）。 */
    private val builtinOchwproSha = "8a6095f9ccd222fa391d4e98e2795f478d3d88daaa62d4844677fac18c7acb02"
    /** 还没在模型市场点「更新」的 v1.1：v1.2 的假名特征和它差得多，不声明兼容，在设备上现算。 */
    private val previousOchwproSha = "d625e6b6fa0dc39499bc15a8ac960249fcea9e71e34334522d32524557d1136d"

    @Test fun shippedFeaturesMatchTheBuiltinChineseModelAndCoverEveryKana() {
        val meta = assetFile("kana_default_och.json").readText(Charsets.UTF_8)
        val bin = assetFile("kana_default_och.bin").readBytes()
        assertEquals(builtinOchwproSha, JSONObject(meta).getString("model_sha256"))
        val rows = HandwritingTemplates.decodePrecomputed(meta, bin, builtinOchwproSha)
        assertNotNull(rows)
        rows!!
        assertTrue(rows.size >= 170 * 9)
        val strokes = JSONObject(assetFile("kana_default_strokes.json").readText(Charsets.UTF_8)).getJSONObject("chars")
        assertEquals(strokes.keys().asSequence().toSet(), rows.map { it.first }.toSet())
        for ((label, f) in rows.take(50)) {
            assertEquals(label, 1f, f.dot(f), 0.01f)
            assertTrue(label, f.idx!!.toList() == f.idx!!.sorted())
        }
    }

    @Test fun aDifferentOrPersonalModelFallsBackToComputingOnTheDevice() {
        val meta = assetFile("kana_default_och.json").readText(Charsets.UTF_8)
        val bin = assetFile("kana_default_och.bin").readBytes()
        assertNull(HandwritingTemplates.decodePrecomputed(meta, bin, "0".repeat(64)))
        assertNull(HandwritingTemplates.decodePrecomputed(meta, bin, previousOchwproSha))
        assertNull(HandwritingTemplates.decodePrecomputed(meta, bin.copyOf(bin.size - 4), builtinOchwproSha))
    }

    @Test fun sparseRecordsDecodeAndMatchSameShapedWriting() {
        // 两类：类 3 → 1.0，类 10 → 0.0（float16 0x3C00 / 0x0000）
        val bin = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(10).putShort(0).putShort(3).putShort(0x3C00.toShort()).array()
        val meta = JSONObject().put("model_sha256", "ab").put("n", 2).put("labels", JSONArray().put("あ")).toString()
        val f = HandwritingTemplates.decodePrecomputed(meta, bin, "AB")!!.single().second
        assertEquals(listOf(3, 10), f.idx!!.toList())
        assertEquals(1f, f.vals!![0], 0f)
        // 设备上：模型输出的概率开方归一化后与模板点积
        val query = HandwritingTemplates.Feature.fromProbabilities(listOf(3 to .64f, 7 to .36f))!!
        assertEquals(0.8f, query.dot(f), 1e-5f)
        val ranked = HandwritingTemplates.rank(query, emptyList(), listOf("あ" to f,
            "い" to HandwritingTemplates.Feature.fromProbabilities(listOf(7 to 1f))!!), 2)
        assertEquals(listOf("あ", "い"), ranked.map { it.first })
    }

    @Test fun ownTemplatesWinTiesAgainstBuiltinOnes() {
        val f = HandwritingTemplates.Feature.fromProbabilities(listOf(1 to 1f))!!
        val ranked = HandwritingTemplates.rank(f, listOf("ぬ" to f), listOf("め" to f), 2)
        assertEquals("ぬ", ranked.first().first)
    }
}
