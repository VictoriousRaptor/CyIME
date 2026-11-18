package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 11-15 双拼辅助码随包的两张码表：方案名和文件、词典对得上，每行「字 + 两个字母」，示例里写的码真在表里。 */
class AuxCodeTablesTest {
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("missing $rel")
    }

    private fun entries(id: String): List<Pair<String, String>> =
        repoFile("src/main/assets/rime_chinese/$id.dict.yaml").readLines()
            .dropWhile { it != "..." }.drop(1).filter { it.isNotBlank() }
            .map { line -> line.split('\t').let { it[0] to it[1] } }

    @Test fun `every aux table is a bundled schema with its own dictionary`() {
        for (code in SpellingPatches.AuxCode.entries) {
            val schema = repoFile("src/main/assets/rime_chinese/${code.schemaId}.schema.yaml").readText()
            assertTrue(schema.contains("schema_id: ${code.schemaId}"))
            assertTrue(schema.contains("dictionary: ${code.schemaId}"))
            assertTrue(schema.contains("enable_user_dict: false"))
            val dict = repoFile("src/main/assets/rime_chinese/${code.schemaId}.dict.yaml").readText()
            assertTrue(dict.contains("name: ${code.schemaId}"))
        }
    }

    @Test fun `codes are two letters and the examples on the settings page are real`() {
        for (code in SpellingPatches.AuxCode.entries) {
            val rows = entries(code.schemaId)
            assertTrue(code.id, rows.size > 8000)
            assertTrue(code.id, rows.all { (text, aux) -> text.isNotEmpty() && aux.matches(Regex("[a-z]{2}")) })
            assertTrue(code.id, "好" to "nz" in rows)
        }
        assertTrue("我" to "pd" in entries("cyime_aux_flypy"))
    }
}
